package com.fakejira.integration;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

@RestController
public class GithubController {

    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final GithubWebhookService webhooks;
    private final MailService mail;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public GithubController(ProjectRepository projects, ProjectAccess access, CurrentUser currentUser,
                            GithubWebhookService webhooks, MailService mail, ObjectMapper json) {
        this.projects = projects;
        this.access = access;
        this.currentUser = currentUser;
        this.webhooks = webhooks;
        this.mail = mail;
        this.json = json;
    }

    public record GithubSettings(boolean enabled, String webhookUrl, String secret, boolean autoDone) {
    }

    public record AutoDoneRequest(boolean autoDone) {
    }

    // ---------------------------------------------------------------- settings (project owner)

    @GetMapping("/api/projects/{key}/github")
    @Transactional(readOnly = true)
    public GithubSettings settings(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = owned(key, jwt);
        return settings(project);
    }

    /** Turns the integration on, or rotates the secret if it is already on. */
    @PostMapping("/api/projects/{key}/github")
    @Transactional
    public GithubSettings enable(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = owned(key, jwt);
        byte[] secret = new byte[24];
        random.nextBytes(secret);
        project.setGithubSecret(HexFormat.of().formatHex(secret));
        return settings(project);
    }

    @PutMapping("/api/projects/{key}/github")
    @Transactional
    public GithubSettings setAutoDone(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                      @RequestBody AutoDoneRequest request) {
        Project project = owned(key, jwt);
        project.setGithubAutoDone(request.autoDone());
        return settings(project);
    }

    @DeleteMapping("/api/projects/{key}/github")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void disable(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = owned(key, jwt);
        project.setGithubSecret(null);
        project.setGithubAutoDone(false);
    }

    private Project owned(String key, Jwt jwt) {
        var user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        return project;
    }

    private GithubSettings settings(Project project) {
        return new GithubSettings(project.getGithubSecret() != null,
                mail.link("/api/integrations/github/" + project.getKey()),
                project.getGithubSecret(), project.isGithubAutoDone());
    }

    // ---------------------------------------------------------------- webhook (called by GitHub)

    /** Authenticated by the X-Hub-Signature-256 HMAC of the raw body, not by a user token. */
    @PostMapping("/api/integrations/github/{key}")
    @Transactional
    public ResponseEntity<Map<String, Object>> webhook(@PathVariable String key,
                                                       @RequestHeader(value = "X-GitHub-Event", defaultValue = "") String event,
                                                       @RequestHeader(value = "X-Hub-Signature-256", defaultValue = "") String signature,
                                                       @RequestBody byte[] body) throws IOException {
        Project project = projects.findByKey(key.toUpperCase())
                .filter(p -> p.getGithubSecret() != null)
                .orElseThrow(() -> ApiException.notFound("No GitHub integration here."));
        if (!validSignature(project.getGithubSecret(), body, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid signature.");
        }
        if ("ping".equals(event)) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        JsonNode payload = json.readTree(body);
        GithubWebhookService.Result result = webhooks.handle(project, event, payload);
        return ResponseEntity.ok(Map.of("linked", result.linked(), "completed", result.completed()));
    }

    static boolean validSignature(String secret, byte[] body, String header) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = HexFormat.of().parseHex(header.substring("sha256=".length()));
            return MessageDigest.isEqual(expected, given);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
