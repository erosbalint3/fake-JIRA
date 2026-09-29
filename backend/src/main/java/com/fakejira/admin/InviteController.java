package com.fakejira.admin;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** Invites can be created by admins (optionally for a project) and by project owners (for their project). */
@RestController
@RequestMapping("/api/invites")
public class InviteController {

    static final Duration VALIDITY = Duration.ofDays(7);

    private final InviteRepository invites;
    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final MailService mail;
    private final SecureRandom random = new SecureRandom();

    public InviteController(InviteRepository invites, CurrentUser currentUser, ProjectAccess access, MailService mail) {
        this.invites = invites;
        this.currentUser = currentUser;
        this.access = access;
        this.mail = mail;
    }

    public record InviteRequest(@Email(message = "Enter a valid email address") @Size(max = 120) String email,
                                String projectKey) {
    }

    public record InviteResponse(Long id, String code, String link, String email, String projectKey,
                                 String createdBy, Instant expiresAt, Instant usedAt, String usedBy) {
        static InviteResponse of(Invite invite, MailService mail) {
            return new InviteResponse(invite.getId(), invite.getCode(), mail.link("/register?invite=" + invite.getCode()),
                    invite.getEmail(), invite.getProject() == null ? null : invite.getProject().getKey(),
                    invite.getCreatedBy().getUsername(), invite.getExpiresAt(), invite.getUsedAt(),
                    invite.getUsedBy() == null ? null : invite.getUsedBy().getUsername());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<InviteResponse> list(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        List<Invite> list = user.isAdmin()
                ? invites.findAllByOrderByCreatedAtDesc()
                : invites.findByCreatedByIdOrderByCreatedAtDesc(user.getId());
        return list.stream().map(invite -> InviteResponse.of(invite, mail)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public InviteResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteRequest request) {
        User user = currentUser.from(jwt);
        Project project = null;
        if (request.projectKey() != null && !request.projectKey().isBlank()) {
            project = access.memberProject(request.projectKey(), user);
            if (!user.isAdmin()) {
                access.requireOwner(project, user);
            }
        } else if (!user.isAdmin()) {
            throw ApiException.forbidden("Only admins can create invites without a project.");
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String email = request.email() == null || request.email().isBlank() ? null : request.email().trim().toLowerCase();
        Invite invite = invites.save(new Invite(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                email, project, user, Instant.now().plus(VALIDITY)));
        if (email != null && mail.isEnabled()) {
            mail.send(email, "[FakeJIRA] " + user.getName() + " invited you",
                    user.getName() + " invited you to FakeJIRA"
                            + (project == null ? "" : " to work on " + project.getName()) + ".\n\nCreate your account: "
                            + mail.link("/register?invite=" + invite.getCode()) + "\n\nThe link is valid for 7 days.");
        }
        return InviteResponse.of(invite, mail);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Invite invite = invites.findById(id).orElseThrow(() -> ApiException.notFound("Invite not found."));
        if (!user.isAdmin() && !invite.getCreatedBy().getId().equals(user.getId())) {
            throw ApiException.notFound("Invite not found.");
        }
        invites.delete(invite);
    }
}
