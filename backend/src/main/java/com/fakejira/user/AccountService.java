package com.fakejira.user;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.notification.Notification;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.project.ProjectService;
import com.fakejira.session.SessionService;
import com.fakejira.session.UserSession;
import com.fakejira.task.Comment;
import com.fakejira.task.Task;
import com.fakejira.task.TimeEntry;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Personal data export and account deletion (the account is anonymized so history keeps an author). */
@Service
public class AccountService {

    /** Published before an account is anonymized, inside the same transaction. */
    public record UserDeleting(Long userId) {
    }

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final SessionService sessions;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final Path avatarDir;
    private final SecureRandom random = new SecureRandom();

    public AccountService(UserRepository users, ProjectRepository projects, ProjectService projectService,
                          SessionService sessions, PasswordEncoder passwords, JdbcTemplate jdbc, EntityManager em,
                          ApplicationEventPublisher events, AuditLog audit,
                          @Value("${app.storage.dir:./data/attachments}") String storageDir) {
        this.users = users;
        this.projects = projects;
        this.projectService = projectService;
        this.sessions = sessions;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.em = em;
        this.events = events;
        this.audit = audit;
        this.avatarDir = Path.of(storageDir).toAbsolutePath().normalize().resolveSibling("avatars");
    }

    /** Everything FakeJIRA stores about the user, as a JSON-ready map. */
    @Transactional(readOnly = true)
    public Map<String, Object> export(User user) {
        Long id = user.getId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", Instant.now());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("displayName", user.getName());
        profile.put("createdAt", user.getCreatedAt());
        profile.put("admin", user.isAdmin());
        profile.put("emailFrequency", user.getEmailFrequency());
        profile.put("twoFactorEnabled", user.isTotpEnabled());
        data.put("profile", profile);
        data.put("projects", projects.findForMember(id).stream().map(p -> Map.of(
                "key", p.getKey(), "name", p.getName(),
                "role", p.getOwner().getId().equals(id) ? "OWNER" : p.isViewer(user) ? "VIEWER" : "MEMBER")).toList());
        data.put("tasksReported", tasks("select t from Task t where t.reporter.id = :id order by t.id", id));
        data.put("tasksAssigned", tasks("select t from Task t where t.assignee.id = :id order by t.id", id));
        data.put("comments", em.createQuery("select c from Comment c where c.author.id = :id order by c.createdAt", Comment.class)
                .setParameter("id", id).getResultList().stream().map(c -> Map.of(
                        "task", c.getTask().getKey(), "body", c.getBody(), "createdAt", c.getCreatedAt())).toList());
        data.put("timeEntries", em.createQuery("select e from TimeEntry e where e.user.id = :id order by e.workDate", TimeEntry.class)
                .setParameter("id", id).getResultList().stream().map(e -> Map.of(
                        "task", e.getTask().getKey(), "minutes", e.getMinutes(), "date", e.getWorkDate(),
                        "note", e.getNote() == null ? "" : e.getNote())).toList());
        data.put("notifications", em.createQuery("select n from Notification n where n.recipient.id = :id order by n.createdAt",
                        Notification.class)
                .setParameter("id", id).getResultList().stream().map(n -> Map.of(
                        "message", n.getMessage(), "createdAt", n.getCreatedAt())).toList());
        data.put("sessions", sessions.active(id).stream().map(AccountService::session).toList());
        return data;
    }

    private List<Map<String, Object>> tasks(String query, Long id) {
        return em.createQuery(query, Task.class).setParameter("id", id).getResultList().stream()
                .map(t -> Map.<String, Object>of("key", t.getKey(), "title", t.getTitle(), "status", t.getStatus(),
                        "createdAt", t.getCreatedAt()))
                .toList();
    }

    private static Map<String, Object> session(UserSession s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("createdAt", s.getCreatedAt());
        map.put("lastSeenAt", s.getLastSeenAt());
        map.put("ip", s.getIp());
        map.put("device", s.getUserAgent());
        return map;
    }

    /**
     * Deletes an account: projects it owns alone are deleted, it leaves other projects, personal data is
     * removed and the user row is anonymized. Owners of shared projects must hand them over first.
     */
    @Transactional
    public void delete(User actor, User target) {
        User user = users.findById(target.getId()).orElseThrow(() -> ApiException.notFound("User not found."));
        if (user.getStatus() == AccountStatus.DELETED) {
            return;
        }
        if (user.isAdmin() && users.countByAdminTrue() <= 1) {
            throw ApiException.badRequest("The last admin cannot be deleted. Make someone else an admin first.");
        }
        List<Project> shared = projects.findForMember(user.getId()).stream()
                .filter(p -> p.getOwner().getId().equals(user.getId()) && p.getMembers().size() > 1)
                .toList();
        if (!shared.isEmpty()) {
            throw ApiException.badRequest("Hand over these projects to another member first: "
                    + String.join(", ", shared.stream().map(Project::getKey).toList()) + ".");
        }
        String username = user.getUsername();
        for (Project project : projects.findForMember(user.getId())) {
            if (project.getOwner().getId().equals(user.getId())) {
                projectService.delete(user, project.getKey());
            } else {
                projectService.removeMember(user, project.getKey(), user.getId());
            }
        }
        events.publishEvent(new UserDeleting(user.getId()));
        Long id = user.getId();
        jdbc.update("delete from task_watchers where user_id = ?", id);
        jdbc.update("delete from notifications where recipient_id = ?", id);
        jdbc.update("delete from push_subscriptions where user_id = ?", id);
        jdbc.update("delete from saved_filters where owner_id = ?", id);
        jdbc.update("delete from password_reset_tokens where user_id = ?", id);
        sessions.revokeAll(id, null);
        if (user.getAvatarName() != null) {
            try {
                Files.deleteIfExists(avatarDir.resolve(user.getAvatarName()));
            } catch (IOException ignored) {
                // an orphaned picture file is harmless
            }
        }
        byte[] secret = new byte[24];
        random.nextBytes(secret);
        user.anonymize(passwords.encode(Base64.getEncoder().encodeToString(secret)));
        audit.record(actor.getId().equals(id) ? null : actor.getId(), actor.getId().equals(id) ? username : actor.getUsername(),
                "account.delete", username, actor.getId().equals(id) ? "by the user" : "by admin");
    }
}
