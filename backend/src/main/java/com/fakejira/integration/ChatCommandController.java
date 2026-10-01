package com.fakejira.integration;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.project.ProjectRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Slash commands from Slack, Mattermost and Discord, and task link previews ("unfurls") in Slack.
 * Each platform's request is verified: Slack by its signing secret, Mattermost by its token, Discord by Ed25519.
 */
@RestController
@Transactional
public class ChatCommandController {

    private static final Logger log = LoggerFactory.getLogger(ChatCommandController.class);
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final ChatIntegrationRepository integrations;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final ChatCommands commands;
    private final TaskRepository tasks;
    private final MailService mail;
    private final ObjectMapper json;
    private final String slackApi;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ChatCommandController(ChatIntegrationRepository integrations, ProjectRepository projects, ProjectAccess access,
                                 CurrentUser currentUser, ChatCommands commands, TaskRepository tasks, MailService mail,
                                 ObjectMapper json, @Value("${app.slack.api-url:https://slack.com/api}") String slackApi) {
        this.integrations = integrations;
        this.projects = projects;
        this.access = access;
        this.currentUser = currentUser;
        this.commands = commands;
        this.tasks = tasks;
        this.mail = mail;
        this.json = json;
        this.slackApi = slackApi.replaceAll("/+$", "");
    }

    // ---- Settings ------------------------------------------------------------------------------------------------

    public record Settings(boolean slackSigningSecret, boolean slackBotToken, boolean mattermostToken, boolean discordPublicKey,
                           String slackCommandUrl, String slackEventsUrl, String mattermostCommandUrl, String discordInteractionsUrl) {
    }

    /** Each field: null keeps the current value, "" removes it. */
    public record SettingsRequest(@Size(max = 100) String slackSigningSecret,
                                  @Size(max = 200) @Pattern(regexp = "^$|^xoxb-.+", message = "Slack bot tokens start with xoxb-") String slackBotToken,
                                  @Size(max = 100) String mattermostToken,
                                  @Pattern(regexp = "^$|^[0-9a-fA-F]{64}$", message = "The Discord public key is 64 hex characters") String discordPublicKey) {
    }

    @GetMapping("/api/projects/{key}/chat-commands")
    @Transactional(readOnly = true)
    public Settings settings(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        return settings(project, integrations.findByProjectId(project.getId()).orElse(new ChatIntegration(project.getId())));
    }

    @PutMapping("/api/projects/{key}/chat-commands")
    public Settings save(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody SettingsRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        ChatIntegration integration = integrations.findByProjectId(project.getId()).orElseGet(() -> new ChatIntegration(project.getId()));
        if (request.slackSigningSecret() != null) integration.setSlackSigningSecret(blankToNull(request.slackSigningSecret()));
        if (request.slackBotToken() != null) integration.setSlackBotToken(blankToNull(request.slackBotToken()));
        if (request.mattermostToken() != null) integration.setMattermostToken(blankToNull(request.mattermostToken()));
        if (request.discordPublicKey() != null) integration.setDiscordPublicKey(blankToNull(request.discordPublicKey()));
        integrations.save(integration);
        return settings(project, integration);
    }

    private Settings settings(Project project, ChatIntegration c) {
        String base = "/api/integrations/";
        return new Settings(c.getSlackSigningSecret() != null, c.getSlackBotToken() != null, c.getMattermostToken() != null,
                c.getDiscordPublicKey() != null, mail.link(base + "slack/" + project.getKey() + "/command"),
                mail.link(base + "slack/" + project.getKey() + "/events"), mail.link(base + "mattermost/" + project.getKey() + "/command"),
                mail.link(base + "discord/" + project.getKey() + "/interactions"));
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.trim();
    }

    // ---- Slack -----------------------------------------------------------------------------------------------------------

    @PostMapping(value = "/api/integrations/slack/{key}/command", consumes = "application/x-www-form-urlencoded")
    public Map<String, Object> slackCommand(@PathVariable String key,
                                            @RequestHeader(value = "X-Slack-Request-Timestamp", defaultValue = "") String timestamp,
                                            @RequestHeader(value = "X-Slack-Signature", defaultValue = "") String signature,
                                            @RequestBody byte[] body) {
        Project project = project(key);
        ChatIntegration integration = integration(project, i -> i.getSlackSigningSecret() != null);
        verifySlack(integration.getSlackSigningSecret(), timestamp, signature, body);
        Map<String, String> form = form(body);
        ChatCommands.Reply reply = commands.run(project, form.get("text"), "@" + form.getOrDefault("user_name", "someone"),
                "Slack", ChatCommands.Style.SLACK);
        return Map.of("response_type", reply.visible() ? "in_channel" : "ephemeral", "text", reply.text());
    }

    /** Slack Events API: the URL check, and link_shared events for unfurling task links. */
    @PostMapping("/api/integrations/slack/{key}/events")
    public Map<String, Object> slackEvents(@PathVariable String key,
                                           @RequestHeader(value = "X-Slack-Request-Timestamp", defaultValue = "") String timestamp,
                                           @RequestHeader(value = "X-Slack-Signature", defaultValue = "") String signature,
                                           @RequestBody byte[] body) throws java.io.IOException {
        Project project = project(key);
        ChatIntegration integration = integration(project, i -> i.getSlackSigningSecret() != null);
        verifySlack(integration.getSlackSigningSecret(), timestamp, signature, body);
        JsonNode payload = json.readTree(body);
        if ("url_verification".equals(payload.path("type").asText())) {
            return Map.of("challenge", payload.path("challenge").asText());
        }
        JsonNode event = payload.path("event");
        if ("link_shared".equals(event.path("type").asText()) && integration.getSlackBotToken() != null) {
            Map<String, Object> unfurls = new LinkedHashMap<>();
            for (JsonNode link : event.path("links")) {
                String url = link.path("url").asText();
                taskFromUrl(project, url).ifPresent(task -> unfurls.put(url, Map.of(
                        "title", task.getKey() + " · " + task.getTitle(), "title_link", commands.url(task),
                        "text", commands.summary(task, ChatCommands.Style.SLACK).lines().skip(1).findFirst().orElse(""),
                        "footer", project.getName() + " · FakeJIRA")));
            }
            if (!unfurls.isEmpty()) {
                unfurl(integration.getSlackBotToken(), event.path("channel").asText(), event.path("message_ts").asText(), unfurls);
            }
        }
        return Map.of("ok", true);
    }

    private void unfurl(String token, String channel, String ts, Map<String, Object> unfurls) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(slackApi + "/chat.unfurl"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("channel", channel, "ts", ts, "unfurls", unfurls))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (!json.readTree(response.body()).path("ok").asBoolean(false)) {
                log.warn("Slack unfurl failed: {}", response.body());
            }
        } catch (Exception e) {
            log.warn("Slack unfurl failed: {}", e.toString());
        }
    }

    /** Task links in this project: …/tasks/123 or …/p/KEY/…?task=… */
    java.util.Optional<Task> taskFromUrl(Project project, String url) {
        String site = mail.link("/");
        if (url == null || !url.startsWith(site.replaceAll("/+$", ""))) {
            return java.util.Optional.empty();
        }
        Matcher matcher = java.util.regex.Pattern.compile("/tasks/(\\d{1,18})").matcher(url);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        return tasks.findById(Long.valueOf(matcher.group(1))).filter(t -> t.getProject().getId().equals(project.getId()));
    }

    static void verifySlack(String secret, String timestamp, String signature, byte[] body) {
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing Slack timestamp.");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Stale Slack request.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            String expected = "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Slack signature.");
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Mattermost -------------------------------------------------------------------------------------------------------

    @PostMapping(value = "/api/integrations/mattermost/{key}/command", consumes = "application/x-www-form-urlencoded")
    public Map<String, Object> mattermostCommand(@PathVariable String key, @RequestBody byte[] body) {
        Project project = project(key);
        ChatIntegration integration = integration(project, i -> i.getMattermostToken() != null);
        Map<String, String> form = form(body);
        String token = form.getOrDefault("token", "");
        if (!MessageDigest.isEqual(integration.getMattermostToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid token.");
        }
        ChatCommands.Reply reply = commands.run(project, form.get("text"), "@" + form.getOrDefault("user_name", "someone"),
                "Mattermost", ChatCommands.Style.MARKDOWN);
        return Map.of("response_type", reply.visible() ? "in_channel" : "ephemeral", "text", reply.text());
    }

    // ---- Discord ------------------------------------------------------------------------------------------------------------

    @PostMapping("/api/integrations/discord/{key}/interactions")
    public ResponseEntity<Map<String, Object>> discord(@PathVariable String key,
                                                       @RequestHeader(value = "X-Signature-Ed25519", defaultValue = "") String signature,
                                                       @RequestHeader(value = "X-Signature-Timestamp", defaultValue = "") String timestamp,
                                                       @RequestBody byte[] body) throws java.io.IOException {
        Project project = project(key);
        ChatIntegration integration = integration(project, i -> i.getDiscordPublicKey() != null);
        if (!verifyDiscord(integration.getDiscordPublicKey(), timestamp, signature, body)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid request signature.");
        }
        JsonNode payload = json.readTree(body);
        int type = payload.path("type").asInt();
        if (type == 1) {
            return ResponseEntity.ok(Map.of("type", 1));
        }
        if (type != 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unsupported interaction."));
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode option : payload.path("data").path("options")) {
            text.append(option.path("value").asText()).append(' ');
        }
        JsonNode user = payload.path("member").path("user").isMissingNode() ? payload.path("user") : payload.path("member").path("user");
        ChatCommands.Reply reply = commands.run(project, text.toString(), "@" + user.path("username").asText("someone"),
                "Discord", ChatCommands.Style.MARKDOWN);
        Map<String, Object> data = new HashMap<>();
        data.put("content", reply.text().length() > 1900 ? reply.text().substring(0, 1900) + "…" : reply.text());
        data.put("allowed_mentions", Map.of("parse", List.of()));
        if (!reply.visible()) {
            data.put("flags", 64);
        }
        return ResponseEntity.ok(Map.of("type", 4, "data", data));
    }

    static boolean verifyDiscord(String publicKeyHex, String timestamp, String signatureHex, byte[] body) {
        try {
            byte[] raw = HexFormat.of().parseHex(publicKeyHex);
            byte[] encoded = new byte[ED25519_X509_PREFIX.length + raw.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, raw.length);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            return verifier.verify(HexFormat.of().parseHex(signatureHex));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------------------

    private Project project(String key) {
        return projects.findByKey(key.toUpperCase()).orElseThrow(() -> ApiException.notFound("No chat integration here."));
    }

    private ChatIntegration integration(Project project, java.util.function.Predicate<ChatIntegration> configured) {
        return integrations.findByProjectId(project.getId()).filter(configured)
                .orElseThrow(() -> ApiException.notFound("No chat integration here."));
    }

    private static Map<String, String> form(byte[] body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        integrations.deleteForProject(event.projectId());
    }
}
