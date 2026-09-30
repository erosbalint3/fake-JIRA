package com.fakejira.admin;

import com.fakejira.audit.AuditLog;
import com.fakejira.auth.LdapDirectory;
import com.fakejira.auth.oauth.OAuthService;
import com.fakejira.auth.oauth.SamlService;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.config.RateLimitFilter;
import com.fakejira.mail.MailService;
import com.fakejira.scim.ScimController;
import com.fakejira.session.SessionService;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin settings for sign-in and access: SSO, LDAP, SCIM, session rules, the IP allowlist, and suspending accounts. */
@RestController
@RequestMapping("/api/admin")
public class SecurityAdminController {

    /** {@code signIn}: the company sign-in methods set up in the server configuration. */
    public record Overview(SecurityPolicy.Policy policy, List<String> signIn, boolean ssoAvailable, String samlMetadataUrl,
                           String samlAcsUrl, String oidcRedirectUrl, String scimUrl, boolean scimTokenSet, String yourIp) {
    }

    private final CurrentUser currentUser;
    private final SecurityPolicy policy;
    private final OAuthService oauth;
    private final SamlService saml;
    private final LdapDirectory ldap;
    private final ScimController scim;
    private final MailService site;
    private final RateLimitFilter clientIps;
    private final UserRepository users;
    private final SessionService sessions;
    private final AuditLog audit;

    public SecurityAdminController(CurrentUser currentUser, SecurityPolicy policy, OAuthService oauth, SamlService saml,
                                   LdapDirectory ldap, ScimController scim, MailService site, RateLimitFilter clientIps,
                                   UserRepository users, SessionService sessions, AuditLog audit) {
        this.currentUser = currentUser;
        this.policy = policy;
        this.oauth = oauth;
        this.saml = saml;
        this.ldap = ldap;
        this.scim = scim;
        this.site = site;
        this.clientIps = clientIps;
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
    }

    private boolean ssoAvailable() {
        return oauth.ssoEnabled() || ldap.enabled();
    }

    @GetMapping("/security")
    public Overview overview(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        currentUser.admin(jwt);
        List<String> methods = new ArrayList<>();
        oauth.enabled().stream().filter(p -> p.get("id").equals("oidc")).forEach(p -> methods.add("OpenID Connect (" + p.get("label") + ")"));
        if (saml.enabled()) methods.add("SAML (" + saml.label() + ")");
        if (ldap.enabled()) methods.add("LDAP (" + ldap.label() + ")");
        return new Overview(policy.get(), methods, ssoAvailable(), site.link("/api/auth/saml/metadata"), saml.acsUrl(),
                oauth.redirectUri("oidc"), site.link("/scim/v2"), scim.tokenSet(), clientIps.clientIp(request));
    }

    @PutMapping("/security")
    public SecurityPolicy.Policy save(@AuthenticationPrincipal Jwt jwt, @RequestBody SecurityPolicy.Policy body,
                                      HttpServletRequest request) {
        User admin = currentUser.admin(jwt);
        if (body.ssoRequired() && !ssoAvailable()) {
            throw ApiException.field("ssoRequired", "Set up OpenID Connect, SAML or LDAP sign-in first.");
        }
        SecurityPolicy.Policy before = policy.get();
        SecurityPolicy.Policy saved = policy.save(body, clientIps.clientIp(request));
        audit.record(admin, "admin.security_policy", null, describe(before, saved));
        return saved;
    }

    @PostMapping("/security/scim-token")
    public Map<String, String> newScimToken(@AuthenticationPrincipal Jwt jwt) {
        User admin = currentUser.admin(jwt);
        String token = scim.newToken();
        audit.record(admin, "admin.scim_token", null, "created");
        return Map.of("token", token);
    }

    @DeleteMapping("/security/scim-token")
    public void revokeScimToken(@AuthenticationPrincipal Jwt jwt) {
        User admin = currentUser.admin(jwt);
        scim.revokeToken();
        audit.record(admin, "admin.scim_token", null, "revoked");
    }

    @PostMapping("/users/{id}/suspend")
    @Transactional
    public void suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = users.findById(id).filter(u -> u.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        if (user.getId().equals(admin.getId())) {
            throw ApiException.badRequest("You cannot deactivate your own account.");
        }
        user.setStatus(AccountStatus.SUSPENDED);
        sessions.revokeAll(user.getId(), null);
        audit.record(admin, "admin.suspend", user.getUsername(), null);
    }

    @PostMapping("/users/{id}/reactivate")
    @Transactional
    public void reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User admin = currentUser.admin(jwt);
        User user = users.findById(id).filter(u -> u.getStatus() == AccountStatus.SUSPENDED)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        user.setStatus(AccountStatus.ACTIVE);
        audit.record(admin, "admin.reactivate", user.getUsername(), null);
    }

    private static String describe(SecurityPolicy.Policy before, SecurityPolicy.Policy after) {
        List<String> changes = new ArrayList<>();
        if (before.ssoRequired() != after.ssoRequired()) changes.add("SSO required: " + after.ssoRequired());
        if (before.sessionHours() != after.sessionHours()) changes.add("session " + after.sessionHours() + "h");
        if (before.idleMinutes() != after.idleMinutes()) changes.add("idle timeout " + after.idleMinutes() + " min");
        if (!before.ipAllowlist().equals(after.ipAllowlist())) changes.add("IP allowlist: " + String.join(", ", after.ipAllowlist()));
        if (before.loginAlerts() != after.loginAlerts()) changes.add("sign-in alerts: " + after.loginAlerts());
        return changes.isEmpty() ? "no changes" : String.join("; ", changes);
    }
}
