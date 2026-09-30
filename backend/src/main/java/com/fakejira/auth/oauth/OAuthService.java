package com.fakejira.auth.oauth;

import com.fakejira.audit.AuditLog;
import com.fakejira.auth.AuthDtos.AuthResponse;
import com.fakejira.auth.AuthService;
import com.fakejira.auth.TokenService;
import com.fakejira.common.ApiException;
import com.fakejira.mail.MailService;
import com.fakejira.user.AccountService.UserDeleting;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "Sign in with Google / GitHub / your company" using the OAuth 2.0 authorization code flow. The company provider is
 * any OpenID Connect issuer (Okta, Entra ID, Keycloak, Auth0…), set up from its discovery document. SAML sign-in
 * ({@link SamlService}) shares the state, cookie and account handling here.
 */
@Service
public class OAuthService {

    private static final Duration STATE_VALIDITY = Duration.ofMinutes(10);

    /** A verified profile from the provider. */
    record Profile(String subject, String email, boolean emailVerified, String login, String name) {
    }

    /** Where the browser goes after the callback: a fragment the sign-in page reads. */
    public record Outcome(String fragment) {
    }

    private final Map<String, OAuthProvider> providers = new LinkedHashMap<>();
    private final UserIdentityRepository identities;
    private final UserRepository users;
    private final AuthService auth;
    private final PasswordEncoder passwords;
    private final MailService site;
    private final AuditLog audit;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OAuthService(Environment env, UserIdentityRepository identities, UserRepository users, AuthService auth,
                        PasswordEncoder passwords, MailService site, AuditLog audit, JwtEncoder encoder,
                        SecretKey jwtSecretKey, ObjectMapper json) {
        this.identities = identities;
        this.users = users;
        this.auth = auth;
        this.passwords = passwords;
        this.site = site;
        this.audit = audit;
        this.encoder = encoder;
        this.decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        this.json = json;
        register(env, "google", "Google", "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token", "https://openidconnect.googleapis.com/v1/userinfo", null,
                "openid email profile");
        register(env, "github", "GitHub", "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token", "https://api.github.com/user",
                "https://api.github.com/user/emails", "read:user user:email");
        this.oidcIssuer = env.getProperty("app.oauth.oidc.issuer", "").trim().replaceAll("/+$", "");
        this.oidcTrustEmail = env.getProperty("app.oauth.oidc.trust-email", Boolean.class, false);
        if (!oidcIssuer.isEmpty()) {
            providers.put("oidc", new OAuthProvider("oidc", env.getProperty("app.oauth.oidc.label", "Single sign-on"),
                    env.getProperty("app.oauth.oidc.client-id", ""), env.getProperty("app.oauth.oidc.client-secret", ""),
                    null, null, null, null, env.getProperty("app.oauth.oidc.scope", "openid email profile")));
        }
    }

    private final String oidcIssuer;
    private final boolean oidcTrustEmail;
    private volatile OAuthProvider oidcResolved;
    private SamlService saml;

    @org.springframework.beans.factory.annotation.Autowired
    void setSaml(@org.springframework.context.annotation.Lazy SamlService saml) {
        this.saml = saml;
    }

    /** The OpenID Connect provider with its endpoints read from the issuer's discovery document (cached). */
    private OAuthProvider resolveOidc(OAuthProvider configured) {
        OAuthProvider resolved = oidcResolved;
        if (resolved != null) {
            return resolved;
        }
        try {
            JsonNode discovery = get(oidcIssuer + "/.well-known/openid-configuration", null);
            if (!oidcIssuer.equals(discovery.path("issuer").asText().replaceAll("/+$", ""))) {
                throw new IOException("The discovery document is for a different issuer");
            }
            resolved = new OAuthProvider("oidc", configured.label(), configured.clientId(), configured.clientSecret(),
                    discovery.path("authorization_endpoint").asText(), discovery.path("token_endpoint").asText(),
                    discovery.path("userinfo_endpoint").asText(), null, configured.scope());
            if (resolved.authorizeUrl().isEmpty() || resolved.tokenUrl().isEmpty() || resolved.userUrl().isEmpty()) {
                throw new IOException("The discovery document is missing endpoints");
            }
            oidcResolved = resolved;
            return resolved;
        } catch (IOException | RuntimeException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Could not reach the sign-in provider. Please try again later.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Sign-in was interrupted.");
        }
    }

    private void register(Environment env, String id, String label, String authorize, String token, String user,
                          String emails, String scope) {
        String prefix = "app.oauth." + id + ".";
        providers.put(id, new OAuthProvider(id, label,
                env.getProperty(prefix + "client-id", ""), env.getProperty(prefix + "client-secret", ""),
                env.getProperty(prefix + "authorize-url", authorize), env.getProperty(prefix + "token-url", token),
                env.getProperty(prefix + "user-url", user),
                emails == null ? null : env.getProperty(prefix + "emails-url", emails), scope));
    }

    /** Providers with credentials configured, for the sign-in page. */
    public List<Map<String, String>> enabled() {
        List<Map<String, String>> list = new java.util.ArrayList<>(providers.values().stream().filter(OAuthProvider::enabled)
                .map(p -> Map.of("id", p.id(), "label", p.label())).toList());
        if (saml != null && saml.enabled()) {
            list.add(Map.of("id", "saml", "label", saml.label()));
        }
        return list;
    }

    /** True when a company sign-in (OpenID Connect or SAML) is set up; "require single sign-on" needs one. */
    public boolean ssoEnabled() {
        OAuthProvider oidc = providers.get("oidc");
        return oidc != null && oidc.enabled() || saml != null && saml.enabled();
    }

    private OAuthProvider provider(String id) {
        if (id.equals("saml") && saml != null && saml.enabled()) {
            return new OAuthProvider("saml", saml.label(), "saml", "saml", null, null, null, null, null);
        }
        OAuthProvider provider = providers.get(id);
        if (provider == null || !provider.enabled()) {
            throw ApiException.notFound("Sign-in with " + id + " is not set up on this server.");
        }
        return id.equals("oidc") ? resolveOidc(provider) : provider;
    }

    public String redirectUri(String providerId) {
        return site.link("/api/auth/oauth/" + providerId + "/callback");
    }

    /**
     * The provider's consent page URL. {@code linkUser} is set when a signed-in user connects an account;
     * {@code nonce} is also stored in a cookie so the callback only completes in the same browser.
     */
    public String authorizationUrl(String providerId, User linkUser, String inviteCode, String nonce) {
        OAuthProvider provider = provider(providerId);
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("fake-jira").issuedAt(now).expiresAt(now.plus(STATE_VALIDITY))
                .claim(TokenService.PURPOSE, "oauth-state")
                .claim("provider", providerId)
                .claim("nonce", nonce);
        if (linkUser != null) {
            claims.claim("link", String.valueOf(linkUser.getId()));
        }
        if (inviteCode != null && !inviteCode.isBlank()) {
            claims.claim("invite", inviteCode.trim());
        }
        String requestId = null;
        if (providerId.equals("saml")) {
            requestId = "_" + newNonce().replace('-', 'a').replace('_', 'b');
            claims.claim("request", requestId);
        }
        String state = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
        if (providerId.equals("saml")) {
            return saml.authnRequestUrl(requestId, state);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", provider.clientId());
        params.put("redirect_uri", redirectUri(providerId));
        params.put("response_type", "code");
        params.put("scope", provider.scope());
        params.put("state", state);
        if (providerId.equals("google")) {
            params.put("prompt", "select_account");
        }
        return provider.authorizeUrl() + "?" + form(params);
    }

    public String newNonce() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Handles the provider's redirect back to us. */
    @Transactional
    public Outcome callback(String providerId, String code, String state, String cookieNonce) {
        Jwt stateJwt = readState(state);
        if (stateJwt == null) {
            return error("The sign-in link expired. Please try again.");
        }
        if (!"oauth-state".equals(stateJwt.getClaimAsString(TokenService.PURPOSE))
                || !providerId.equals(stateJwt.getClaimAsString("provider"))) {
            return error("The sign-in link is not valid. Please try again.");
        }
        if (cookieNonce == null || !cookieNonce.equals(stateJwt.getClaimAsString("nonce"))) {
            return error("Please start the sign-in again in this browser.");
        }
        if (code == null || code.isBlank()) {
            return error("Sign-in was cancelled.");
        }
        OAuthProvider provider = provider(providerId);
        Profile profile;
        try {
            profile = providerId.equals("saml") ? saml.redeem(code, state) : fetchProfile(provider, code);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return error("Could not reach " + provider.label() + ". Please try again.");
        }

        String link = stateJwt.getClaimAsString("link");
        if (link != null) {
            return linkAccount(provider, profile, Long.valueOf(link));
        }
        return signIn(provider, profile, stateJwt.getClaimAsString("invite"));
    }

    /** The signed state (also SAML's RelayState), or null when it is invalid or expired. */
    Jwt readState(String state) {
        try {
            return decoder.decode(state == null ? "" : state);
        } catch (JwtException e) {
            return null;
        }
    }

    private Outcome linkAccount(OAuthProvider provider, Profile profile, Long userId) {
        User user = users.findById(userId).filter(u -> u.getStatus() == AccountStatus.ACTIVE).orElse(null);
        if (user == null) {
            return error("Please sign in again.");
        }
        var existing = identities.findByProviderAndSubject(provider.id(), profile.subject());
        if (existing.isPresent() && !existing.get().getUser().getId().equals(userId)) {
            return error("That " + provider.label() + " account is already connected to another FakeJIRA user.");
        }
        if (existing.isEmpty()) {
            identities.save(new UserIdentity(user, provider.id(), profile.subject(), profile.email()));
            audit.record(user, "identity.link", user.getUsername(), provider.id());
        }
        return new Outcome("linked=" + provider.id());
    }

    private Outcome signIn(OAuthProvider provider, Profile profile, String inviteCode) {
        User user = identities.findByProviderAndSubject(provider.id(), profile.subject())
                .map(UserIdentity::getUser).orElse(null);
        if (user == null && profile.email() != null && profile.emailVerified()) {
            // Same verified email as an existing account: connect it.
            user = users.findByEmailIgnoreCase(profile.email())
                    .filter(u -> u.getStatus() != AccountStatus.DELETED).orElse(null);
            if (user != null) {
                identities.save(new UserIdentity(user, provider.id(), profile.subject(), profile.email()));
                audit.record(user, "identity.link", user.getUsername(), provider.id() + " (matching email)");
            }
        }
        if (user == null) {
            if (profile.email() == null || !profile.emailVerified()) {
                return error("Your " + provider.label() + " account has no verified email address.");
            }
            byte[] secret = new byte[24];
            random.nextBytes(secret);
            AuthService.NewAccount account;
            try {
                account = auth.createAccount(uniqueUsername(profile), profile.email().toLowerCase(Locale.ROOT),
                        passwords.encode(Base64.getEncoder().encodeToString(secret)), false, inviteCode);
            } catch (ApiException e) {
                return error(e.getMessage());
            }
            user = account.user();
            if (profile.name() != null && !profile.name().isBlank()) {
                user.setDisplayName(profile.name().length() > 60 ? profile.name().substring(0, 60) : profile.name());
            }
            identities.save(new UserIdentity(user, provider.id(), profile.subject(), profile.email()));
            if (account.pending()) {
                return new Outcome("pending=1");
            }
        }
        try {
            AuthResponse response = auth.afterFirstStep(user, provider.id());
            return response.challenge() != null
                    ? new Outcome("challenge=" + response.challenge())
                    : new Outcome("token=" + response.token());
        } catch (ApiException e) {
            return error(e.getMessage());
        }
    }

    private String uniqueUsername(Profile profile) {
        String base = profile.login() != null ? profile.login() : profile.email().substring(0, profile.email().indexOf('@'));
        base = base.replaceAll("[^A-Za-z0-9._-]", "");
        if (base.length() > 32) {
            base = base.substring(0, 32);
        }
        if (base.length() < 4) {
            base = base + "user";
        }
        String candidate = base;
        for (int i = 2; users.existsByUsernameIgnoreCase(candidate); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    Profile fetchProfile(OAuthProvider provider, String code) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", provider.clientId());
        form.put("client_secret", provider.clientSecret());
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", redirectUri(provider.id()));
        HttpResponse<String> tokenResponse = http.send(HttpRequest.newBuilder(URI.create(provider.tokenUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form))).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode token = json.readTree(tokenResponse.body());
        String accessToken = token.path("access_token").asText("");
        if (tokenResponse.statusCode() >= 300 || accessToken.isEmpty()) {
            throw new IOException("Token exchange failed: " + tokenResponse.statusCode());
        }
        JsonNode user = get(provider.userUrl(), accessToken);
        if (provider.id().equals("github")) {
            String email = null;
            boolean verified = false;
            if (provider.emailsUrl() != null) {
                for (JsonNode entry : get(provider.emailsUrl(), accessToken)) {
                    if (entry.path("primary").asBoolean() && entry.path("verified").asBoolean()) {
                        email = entry.path("email").asText();
                        verified = true;
                    }
                }
            }
            return new Profile(user.path("id").asText(), email, verified, user.path("login").asText(null),
                    user.path("name").asText(null));
        }
        boolean verified = user.path("email_verified").asBoolean(false) || provider.id().equals("oidc") && oidcTrustEmail;
        return new Profile(user.path("sub").asText(), user.path("email").asText(null), verified,
                user.path("preferred_username").asText(null), user.path("name").asText(null));
    }

    private JsonNode get(String url, String accessToken) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "FakeJIRA");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException(url + " returned " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    /** Disconnects a provider; refused when it is the only way to sign in. */
    @Transactional
    public void unlink(User user, String providerId) {
        List<UserIdentity> linked = identities.findByUserId(user.getId());
        UserIdentity identity = linked.stream().filter(i -> i.getProvider().equals(providerId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That account is not connected."));
        if (!user.isPasswordSet() && linked.size() == 1) {
            throw ApiException.badRequest("Set a password first, or you could not sign in any more.");
        }
        identities.delete(identity);
        audit.record(user, "identity.unlink", user.getUsername(), providerId);
    }

    @EventListener
    public void onUserDeleting(UserDeleting event) {
        identities.deleteForUser(event.userId());
    }

    private static Outcome error(String message) {
        return new Outcome("error=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    // Visible for the status endpoint on the Admin page.
    public boolean anyEnabled() {
        return providers.values().stream().anyMatch(OAuthProvider::enabled) || saml != null && saml.enabled();
    }
}
