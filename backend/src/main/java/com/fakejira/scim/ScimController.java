package com.fakejira.scim;

import com.fakejira.admin.AppSettings;
import com.fakejira.audit.AuditLog;
import com.fakejira.session.SessionService;
import com.fakejira.team.Team;
import com.fakejira.team.TeamRepository;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM 2.0 provisioning (RFC 7643/7644) for identity providers such as Okta, Entra ID and OneLogin: create, update,
 * deactivate and look up people, and sync groups to FakeJIRA teams. Authenticated with a bearer token an admin
 * generates; everything else about the API is off until then. Deleting a user deactivates the account (their work
 * and name stay); an admin can delete it for good.
 */
@RestController
@RequestMapping(value = "/scim/v2", produces = {ScimController.SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
@Transactional
public class ScimController {

    static final String SCIM_JSON = "application/scim+json";
    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    static final String TOKEN_KEY = "scim.token-hash";
    private static final Pattern FILTER = Pattern.compile("^\\s*([A-Za-z.]+)\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_PATH = Pattern.compile("^members\\[value eq \"([^\"]+)\"\\]$", Pattern.CASE_INSENSITIVE);

    static class ScimError extends RuntimeException {
        final HttpStatus status;
        final String scimType;

        ScimError(HttpStatus status, String scimType, String detail) {
            super(detail);
            this.status = status;
            this.scimType = scimType;
        }
    }

    private final UserRepository users;
    private final TeamRepository teams;
    private final AppSettings settings;
    private final SessionService sessions;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final String siteUrl;
    private final SecureRandom random = new SecureRandom();

    public ScimController(UserRepository users, TeamRepository teams, AppSettings settings, SessionService sessions,
                          PasswordEncoder passwords, AuditLog audit, ObjectMapper json, com.fakejira.mail.MailService site) {
        this.users = users;
        this.teams = teams;
        this.settings = settings;
        this.sessions = sessions;
        this.passwords = passwords;
        this.audit = audit;
        this.json = json;
        this.siteUrl = site.link("/scim/v2");
    }

    // ---- Token (used by the admin endpoints) ----------------------------------------------------------------------------

    /** Creates a new token (replacing any old one) and returns it; only its hash is stored. */
    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "fjscim_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        settings.put(TOKEN_KEY, hash(token));
        return token;
    }

    public void revokeToken() {
        settings.put(TOKEN_KEY, "");
    }

    public boolean tokenSet() {
        return settings.get(TOKEN_KEY).filter(s -> !s.isBlank()).isPresent();
    }

    private void authenticate(String header) {
        String stored = settings.get(TOKEN_KEY).orElse("");
        if (stored.isBlank()) {
            throw new ScimError(HttpStatus.UNAUTHORIZED, null, "SCIM provisioning is not enabled.");
        }
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : "";
        if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), hash(token).getBytes(StandardCharsets.UTF_8))) {
            throw new ScimError(HttpStatus.UNAUTHORIZED, null, "Invalid SCIM token.");
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Discovery ---------------------------------------------------------------------------------------------------

    @GetMapping("/ServiceProviderConfig")
    public ResponseEntity<JsonNode> config(@RequestHeader(value = "Authorization", required = false) String auth) {
        authenticate(auth);
        ObjectNode node = json.createObjectNode();
        node.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig");
        node.putObject("patch").put("supported", true);
        node.putObject("bulk").put("supported", false).put("maxOperations", 0).put("maxPayloadSize", 0);
        node.putObject("filter").put("supported", true).put("maxResults", 200);
        node.putObject("changePassword").put("supported", false);
        node.putObject("sort").put("supported", false);
        node.putObject("etag").put("supported", false);
        ObjectNode scheme = node.putArray("authenticationSchemes").addObject();
        scheme.put("type", "oauthbearertoken").put("name", "Bearer token").put("description", "Generated by an admin in FakeJIRA");
        return ok(node);
    }

    @GetMapping("/ResourceTypes")
    public ResponseEntity<JsonNode> resourceTypes(@RequestHeader(value = "Authorization", required = false) String auth) {
        authenticate(auth);
        ArrayNode types = json.createArrayNode();
        types.addObject().put("id", "User").put("name", "User").put("endpoint", "/Users").put("schema", USER_SCHEMA);
        types.addObject().put("id", "Group").put("name", "Group").put("endpoint", "/Groups").put("schema", GROUP_SCHEMA);
        return ok(list(types, types.size(), 1));
    }

    // ---- Users -------------------------------------------------------------------------------------------------------

    @GetMapping("/Users")
    public ResponseEntity<JsonNode> listUsers(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @RequestParam(required = false) String filter,
                                              @RequestParam(defaultValue = "1") int startIndex,
                                              @RequestParam(defaultValue = "100") int count) {
        authenticate(auth);
        Predicate<User> match = u -> true;
        if (filter != null && !filter.isBlank()) {
            String[] clause = filter(filter);
            String value = clause[1];
            match = switch (clause[0].toLowerCase(Locale.ROOT)) {
                case "username" -> u -> value.equalsIgnoreCase(userName(u)) || value.equalsIgnoreCase(u.getUsername());
                case "emails", "emails.value" -> u -> value.equalsIgnoreCase(u.getEmail());
                case "externalid" -> u -> value.equals(u.getExternalId());
                case "id" -> u -> value.equals(String.valueOf(u.getId()));
                default -> throw new ScimError(HttpStatus.BAD_REQUEST, "invalidFilter", "Unsupported filter attribute " + clause[0]);
            };
        }
        List<User> found = users.findAllByOrderByCreatedAtAsc().stream()
                .filter(u -> u.getStatus() != AccountStatus.DELETED).filter(match).toList();
        return ok(page(found.stream().map(this::userNode).toList(), startIndex, count));
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<JsonNode> getUser(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable String id) {
        authenticate(auth);
        return ok(userNode(user(id)));
    }

    @PostMapping(value = "/Users", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<JsonNode> createUser(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestBody JsonNode body) {
        authenticate(auth);
        String userName = text(body, "userName");
        if (userName == null || userName.isBlank()) {
            throw new ScimError(HttpStatus.BAD_REQUEST, "invalidValue", "userName is required");
        }
        String email = email(body);
        if (email == null) {
            email = userName.contains("@") ? userName : null;
        }
        if (email == null) {
            throw new ScimError(HttpStatus.BAD_REQUEST, "invalidValue", "An email address is required");
        }
        String finalEmail = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> existing = users.findByEmailIgnoreCase(finalEmail).filter(u -> u.getStatus() != AccountStatus.DELETED);
        if (existing.isPresent() && existing.get().getScimUserName() != null
                || users.findAllByOrderByCreatedAtAsc().stream().anyMatch(u -> userName.equalsIgnoreCase(u.getScimUserName()))) {
            throw new ScimError(HttpStatus.CONFLICT, "uniqueness", "A user with this userName or email already exists");
        }
        User user;
        if (existing.isPresent()) {
            // Someone who signed up before SCIM was switched on: take over their account.
            user = existing.get();
        } else {
            byte[] secret = new byte[24];
            random.nextBytes(secret);
            user = new User(username(userName, finalEmail), finalEmail, passwords.encode(Base64.getEncoder().encodeToString(secret)));
            user.setPasswordSet(false);
        }
        user.setScimUserName(userName.trim());
        user.setExternalId(text(body, "externalId"));
        applyNames(user, body);
        if (body.has("active")) {
            setActive(user, bool(body.get("active")));
        }
        users.save(user);
        audit.record(null, "scim", "scim.user.create", user.getUsername(), existing.isPresent() ? "linked existing account" : null);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.parseMediaType(SCIM_JSON))
                .header("Location", siteUrl + "/Users/" + user.getId()).body(userNode(user));
    }

    @PutMapping(value = "/Users/{id}", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<JsonNode> replaceUser(@RequestHeader(value = "Authorization", required = false) String auth,
                                                @PathVariable String id, @RequestBody JsonNode body) {
        authenticate(auth);
        User user = user(id);
        if (text(body, "userName") != null) {
            user.setScimUserName(text(body, "userName").trim());
        }
        if (body.has("externalId")) {
            user.setExternalId(text(body, "externalId"));
        }
        String email = email(body);
        if (email != null) {
            changeEmail(user, email);
        }
        applyNames(user, body);
        setActive(user, !body.has("active") || bool(body.get("active")));
        audit.record(null, "scim", "scim.user.update", user.getUsername(), null);
        return ok(userNode(user));
    }

    @PatchMapping(value = "/Users/{id}", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<JsonNode> patchUser(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @PathVariable String id, @RequestBody JsonNode body) {
        authenticate(auth);
        User user = user(id);
        for (JsonNode op : operations(body)) {
            String kind = op.path("op").asText().toLowerCase(Locale.ROOT);
            if (kind.equals("remove")) {
                continue; // nothing on a user can be cleared
            }
            if (!kind.equals("replace") && !kind.equals("add")) {
                throw new ScimError(HttpStatus.BAD_REQUEST, "invalidSyntax", "Unsupported op " + kind);
            }
            String path = op.path("path").asText("");
            JsonNode value = op.get("value");
            if (path.isEmpty() && value != null && value.isObject()) {
                // Okta style: {"op":"replace","value":{"active":false,"name.givenName":…}}
                value.fields().forEachRemaining(e -> patchUserField(user, e.getKey(), e.getValue()));
            } else {
                patchUserField(user, path, value);
            }
        }
        audit.record(null, "scim", "scim.user.update", user.getUsername(), null);
        return ok(userNode(user));
    }

    private void patchUserField(User user, String path, JsonNode value) {
        switch (path.toLowerCase(Locale.ROOT)) {
            case "active" -> setActive(user, bool(value));
            case "username" -> user.setScimUserName(value.asText().trim());
            case "externalid" -> user.setExternalId(value.asText());
            case "displayname", "name.formatted" -> user.setDisplayName(cut(value.asText(), 60));
            case "name" -> applyNames(user, json.createObjectNode().set("name", value));
            case "emails", "emails[type eq \"work\"].value", "emails[primary eq true].value" -> {
                String email = value.isArray() ? email(json.createObjectNode().set("emails", value)) : value.asText();
                if (email != null) changeEmail(user, email);
            }
            default -> {
                // Attributes FakeJIRA does not keep (phone numbers, titles…) are accepted and ignored.
            }
        }
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Void> deleteUser(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String id) {
        authenticate(auth);
        User user = user(id);
        setActive(user, false);
        audit.record(null, "scim", "scim.user.deactivate", user.getUsername(), null);
        return ResponseEntity.noContent().build();
    }

    // ---- Groups (FakeJIRA teams) -------------------------------------------------------------------------------------

    @GetMapping("/Groups")
    public ResponseEntity<JsonNode> listGroups(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestParam(required = false) String filter,
                                               @RequestParam(defaultValue = "1") int startIndex,
                                               @RequestParam(defaultValue = "100") int count) {
        authenticate(auth);
        Predicate<Team> match = t -> true;
        if (filter != null && !filter.isBlank()) {
            String[] clause = filter(filter);
            match = switch (clause[0].toLowerCase(Locale.ROOT)) {
                case "displayname" -> t -> t.getName().equalsIgnoreCase(clause[1]);
                case "id" -> t -> String.valueOf(t.getId()).equals(clause[1]);
                default -> throw new ScimError(HttpStatus.BAD_REQUEST, "invalidFilter", "Unsupported filter attribute " + clause[0]);
            };
        }
        return ok(page(teams.findAllByOrderByNameAsc().stream().filter(match).map(this::groupNode).toList(), startIndex, count));
    }

    @GetMapping("/Groups/{id}")
    public ResponseEntity<JsonNode> getGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @PathVariable String id) {
        authenticate(auth);
        return ok(groupNode(team(id)));
    }

    @PostMapping(value = "/Groups", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<JsonNode> createGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                                @RequestBody JsonNode body) {
        authenticate(auth);
        String name = text(body, "displayName");
        if (name == null || name.isBlank()) {
            throw new ScimError(HttpStatus.BAD_REQUEST, "invalidValue", "displayName is required");
        }
        if (teams.findAllByOrderByNameAsc().stream().anyMatch(t -> t.getName().equalsIgnoreCase(name.trim()))) {
            throw new ScimError(HttpStatus.CONFLICT, "uniqueness", "A group with this name already exists");
        }
        User owner = users.findAllByOrderByCreatedAtAsc().stream().filter(User::isAdmin).findFirst()
                .orElseThrow(() -> new ScimError(HttpStatus.CONFLICT, null, "No admin account to own the team"));
        Team team = new Team(cut(name.trim(), 60), handle(name), "Synced from your identity provider", owner);
        setMembers(team, body.path("members"));
        teams.save(team);
        audit.record(null, "scim", "scim.group.create", "@" + team.getHandle(), team.getName());
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.parseMediaType(SCIM_JSON))
                .header("Location", siteUrl + "/Groups/" + team.getId()).body(groupNode(team));
    }

    @PutMapping(value = "/Groups/{id}", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<JsonNode> replaceGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                                 @PathVariable String id, @RequestBody JsonNode body) {
        authenticate(auth);
        Team team = team(id);
        if (text(body, "displayName") != null) {
            team.setName(cut(text(body, "displayName").trim(), 60));
        }
        team.getMembers().clear();
        setMembers(team, body.path("members"));
        return ok(groupNode(team));
    }

    @PatchMapping(value = "/Groups/{id}", consumes = {SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<JsonNode> patchGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @PathVariable String id, @RequestBody JsonNode body) {
        authenticate(auth);
        Team team = team(id);
        for (JsonNode op : operations(body)) {
            String kind = op.path("op").asText().toLowerCase(Locale.ROOT);
            String path = op.path("path").asText("");
            JsonNode value = op.get("value");
            Matcher member = MEMBER_PATH.matcher(path);
            if (kind.equals("remove") && member.matches()) {
                team.getMembers().removeIf(u -> String.valueOf(u.getId()).equals(member.group(1)));
            } else if (kind.equals("remove") && path.equalsIgnoreCase("members")) {
                if (value != null && value.isArray()) {
                    for (JsonNode m : value) team.getMembers().removeIf(u -> String.valueOf(u.getId()).equals(m.path("value").asText()));
                } else {
                    team.getMembers().clear();
                }
            } else if ((kind.equals("add") || kind.equals("replace")) && path.equalsIgnoreCase("members")) {
                if (kind.equals("replace")) team.getMembers().clear();
                setMembers(team, value);
            } else if (kind.equals("replace") && (path.equalsIgnoreCase("displayName") || path.isEmpty())) {
                String name = path.isEmpty() ? text(value, "displayName") : value.asText();
                if (name != null && !name.isBlank()) team.setName(cut(name.trim(), 60));
            } else {
                throw new ScimError(HttpStatus.BAD_REQUEST, "invalidPath", "Unsupported operation " + kind + " " + path);
            }
        }
        return ok(groupNode(team));
    }

    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<Void> deleteGroup(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable String id) {
        authenticate(auth);
        Team team = team(id);
        audit.record(null, "scim", "scim.group.delete", "@" + team.getHandle(), team.getName());
        teams.delete(team);
        return ResponseEntity.noContent().build();
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    @org.springframework.web.bind.annotation.ExceptionHandler(ScimError.class)
    public ResponseEntity<JsonNode> error(ScimError e) {
        ObjectNode node = json.createObjectNode();
        node.putArray("schemas").add(ERROR_SCHEMA);
        node.put("status", String.valueOf(e.status.value()));
        if (e.scimType != null) node.put("scimType", e.scimType);
        node.put("detail", e.getMessage());
        return ResponseEntity.status(e.status).contentType(MediaType.parseMediaType(SCIM_JSON)).body(node);
    }

    private ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(SCIM_JSON)).body(body);
    }

    private User user(String id) {
        return parseId(id).flatMap(users::findById).filter(u -> u.getStatus() != AccountStatus.DELETED)
                .orElseThrow(() -> new ScimError(HttpStatus.NOT_FOUND, null, "User " + id + " not found"));
    }

    private Team team(String id) {
        return parseId(id).flatMap(teams::findById)
                .orElseThrow(() -> new ScimError(HttpStatus.NOT_FOUND, null, "Group " + id + " not found"));
    }

    private static Optional<Long> parseId(String id) {
        try {
            return Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void setActive(User user, boolean active) {
        if (active && user.getStatus() == AccountStatus.SUSPENDED) {
            user.setStatus(AccountStatus.ACTIVE);
        } else if (!active && user.getStatus() == AccountStatus.ACTIVE) {
            if (user.isAdmin() && users.findAllByOrderByCreatedAtAsc().stream()
                    .filter(u -> u.isAdmin() && u.getStatus() == AccountStatus.ACTIVE).count() <= 1) {
                throw new ScimError(HttpStatus.CONFLICT, "mutability", "Cannot deactivate the last admin");
            }
            user.setStatus(AccountStatus.SUSPENDED);
            if (user.getId() != null) {
                sessions.revokeAll(user.getId(), null);
            }
        }
    }

    private void changeEmail(User user, String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.equalsIgnoreCase(user.getEmail())) {
            return;
        }
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new ScimError(HttpStatus.CONFLICT, "uniqueness", "Another account already uses " + normalized);
        }
        user.setEmail(normalized);
    }

    private void applyNames(User user, JsonNode body) {
        String display = text(body, "displayName");
        JsonNode name = body.path("name");
        if (display == null && name.isObject()) {
            display = text(name, "formatted");
            if (display == null) {
                String given = text(name, "givenName");
                String family = text(name, "familyName");
                display = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
            }
        }
        if (display != null && !display.isBlank()) {
            user.setDisplayName(cut(display.trim(), 60));
        }
    }

    private void setMembers(Team team, JsonNode members) {
        if (members == null || !members.isArray()) {
            return;
        }
        for (JsonNode member : members) {
            parseId(member.path("value").asText()).flatMap(users::findById)
                    .filter(u -> u.getStatus() != AccountStatus.DELETED).ifPresent(team.getMembers()::add);
        }
    }

    private String username(String userName, String email) {
        String base = (userName.contains("@") ? userName.substring(0, userName.indexOf('@')) : userName)
                .replaceAll("[^A-Za-z0-9._-]", "");
        if (base.length() < 4) base = (email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9._-]", "") + "user");
        if (base.length() > 32) base = base.substring(0, 32);
        String candidate = base;
        for (int i = 2; users.existsByUsernameIgnoreCase(candidate); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    private String handle(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[^a-z0-9]+|-+$", "");
        if (base.length() < 2) base = "team";
        if (base.length() > 36) base = base.substring(0, 36);
        String candidate = base;
        for (int i = 2; teams.existsByHandleIgnoreCase(candidate) || users.existsByUsernameIgnoreCase(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private String userName(User user) {
        return user.getScimUserName() != null ? user.getScimUserName() : user.getUsername();
    }

    private ObjectNode userNode(User user) {
        ObjectNode node = json.createObjectNode();
        node.putArray("schemas").add(USER_SCHEMA);
        node.put("id", String.valueOf(user.getId()));
        if (user.getExternalId() != null) node.put("externalId", user.getExternalId());
        node.put("userName", userName(user));
        node.put("displayName", user.getDisplayName());
        node.putObject("name").put("formatted", user.getDisplayName());
        node.putArray("emails").addObject().put("value", user.getEmail()).put("type", "work").put("primary", true);
        node.put("active", user.getStatus() == AccountStatus.ACTIVE);
        ArrayNode groups = node.putArray("groups");
        teams.findForMember(user.getId()).forEach(t -> groups.addObject().put("value", String.valueOf(t.getId())).put("display", t.getName()));
        node.putObject("meta").put("resourceType", "User").put("created", String.valueOf(user.getCreatedAt()))
                .put("location", siteUrl + "/Users/" + user.getId());
        return node;
    }

    private ObjectNode groupNode(Team team) {
        ObjectNode node = json.createObjectNode();
        node.putArray("schemas").add(GROUP_SCHEMA);
        node.put("id", String.valueOf(team.getId()));
        node.put("displayName", team.getName());
        ArrayNode members = node.putArray("members");
        team.getMembers().forEach(u -> members.addObject().put("value", String.valueOf(u.getId())).put("display", u.getUsername()));
        node.putObject("meta").put("resourceType", "Group").put("location", siteUrl + "/Groups/" + team.getId());
        return node;
    }

    private ObjectNode page(List<? extends JsonNode> all, int startIndex, int count) {
        int start = Math.max(1, startIndex);
        int size = Math.max(0, Math.min(200, count));
        List<JsonNode> slice = new ArrayList<>(all.subList(Math.min(all.size(), start - 1), Math.min(all.size(), start - 1 + size)));
        ArrayNode resources = json.createArrayNode();
        slice.forEach(resources::add);
        ObjectNode node = list(resources, all.size(), start);
        return node;
    }

    private ObjectNode list(ArrayNode resources, int total, int start) {
        ObjectNode node = json.createObjectNode();
        node.putArray("schemas").add(LIST_SCHEMA);
        node.put("totalResults", total);
        node.put("startIndex", start);
        node.put("itemsPerPage", resources.size());
        node.set("Resources", resources);
        return node;
    }

    private static String[] filter(String filter) {
        Matcher m = FILTER.matcher(filter);
        if (!m.matches()) {
            throw new ScimError(HttpStatus.BAD_REQUEST, "invalidFilter", "Only `attribute eq \"value\"` filters are supported");
        }
        return new String[]{m.group(1), m.group(2).replace("\\\"", "\"").replace("\\\\", "\\")};
    }

    private static List<JsonNode> operations(JsonNode body) {
        JsonNode ops = body.path("Operations");
        if (!ops.isArray()) {
            throw new ScimError(HttpStatus.BAD_REQUEST, "invalidSyntax", "Operations are required");
        }
        List<JsonNode> list = new ArrayList<>();
        ops.forEach(list::add);
        return list;
    }

    private static String email(JsonNode body) {
        JsonNode emails = body.path("emails");
        if (!emails.isArray() || emails.isEmpty()) {
            return null;
        }
        for (JsonNode e : emails) {
            if (e.path("primary").asBoolean(false) && e.hasNonNull("value")) return e.get("value").asText();
        }
        return emails.get(0).path("value").asText(null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** SCIM clients send booleans as JSON booleans or as "True"/"False" strings (Entra ID). */
    private static boolean bool(JsonNode value) {
        return value != null && (value.isBoolean() ? value.asBoolean() : "true".equalsIgnoreCase(value.asText().trim()));
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
