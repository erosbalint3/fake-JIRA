package com.fakejira.mail;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class InboundController {

    private final InboundMail inbound;
    private final ProjectAccess access;
    private final CurrentUser currentUser;

    public InboundController(InboundMail inbound, ProjectAccess access, CurrentUser currentUser) {
        this.inbound = inbound;
        this.access = access;
        this.currentUser = currentUser;
    }

    public record ProjectAddress(String address) {
    }

    /**
     * For inbound-mail services (Mailgun routes, Postmark, CloudMailin…): POST JSON
     * {@code {"from": "...", "to": "..." or [...], "subject": "...", "text": "..."}} with header X-Inbound-Secret.
     */
    @PostMapping("/api/inbound/email")
    public ResponseEntity<InboundMail.Result> receive(@RequestHeader(value = "X-Inbound-Secret", required = false) String secret,
                                                      @RequestBody JsonNode body) {
        if (!inbound.acceptsWebhook(secret)) {
            return ResponseEntity.status(404).build();
        }
        List<String> to = new ArrayList<>();
        for (String field : List.of("to", "recipient", "cc")) {
            JsonNode value = body.path(field);
            if (value.isArray()) {
                value.forEach(v -> to.add(v.asText()));
            } else if (value.isTextual()) {
                for (String part : value.asText().split(",")) {
                    to.add(part.trim());
                }
            }
        }
        String text = body.hasNonNull("text") ? body.path("text").asText() : body.path("body-plain").asText("");
        return ResponseEntity.ok(inbound.process(body.path("from").asText(""), to, body.path("subject").asText(""), text));
    }

    /** Where to send mail to create tasks in this project (null when inbound mail is off). */
    @GetMapping("/api/projects/{key}/email-address")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ProjectAddress address(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        var project = access.memberProject(key, currentUser.from(jwt));
        if (project == null) {
            throw ApiException.notFound("Project not found.");
        }
        return new ProjectAddress(inbound.projectAddress(project.getKey()).orElse(null));
    }
}
