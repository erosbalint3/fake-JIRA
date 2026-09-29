package com.fakejira.mail;

import com.fakejira.admin.AppSettings;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDetailsService;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskType;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incoming email. Notification emails carry a signed Reply-To address ({@code jira+t42.7.sig@example.com}):
 * replying adds a comment to task 42 as user 7. Mail to {@code jira+WEB@example.com} creates a task in WEB.
 * Senders must be project members, matched by their From address.
 */
@Service
public class InboundMail {

    private static final Logger log = LoggerFactory.getLogger(InboundMail.class);
    private static final String KEY_SETTING = "mail.inbound.key";
    private static final Pattern ADDRESS = Pattern.compile("([A-Za-z0-9._%+=-]+)@([A-Za-z0-9.-]+)");
    private static final Pattern REPLY_TAG = Pattern.compile("^t(\\d+)\\.(\\d+)\\.([0-9a-f]{12})$");
    private static final Pattern PROJECT_TAG = Pattern.compile("^[A-Za-z][A-Za-z0-9]{1,9}$");
    private static final int MAX_BODY = 2000;

    public enum Outcome { COMMENTED, CREATED, IGNORED }

    public record Result(Outcome outcome, String detail) {
    }

    private final String local;
    private final String domain;
    private final String secret;
    private final AppSettings settings;
    private final UserRepository users;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final TaskDetailsService details;
    private volatile byte[] key;

    public InboundMail(@Value("${app.mail.inbound.address:}") String address,
                       @Value("${app.mail.inbound.secret:}") String secret,
                       AppSettings settings, UserRepository users, ProjectRepository projects, TaskRepository tasks,
                       TaskService taskService, TaskDetailsService details) {
        Matcher m = ADDRESS.matcher(address.trim());
        if (m.matches()) {
            this.local = m.group(1).toLowerCase(Locale.ROOT);
            this.domain = m.group(2).toLowerCase(Locale.ROOT);
        } else {
            this.local = null;
            this.domain = null;
        }
        this.secret = secret.trim();
        this.settings = settings;
        this.users = users;
        this.projects = projects;
        this.tasks = tasks;
        this.taskService = taskService;
        this.details = details;
    }

    public boolean isEnabled() {
        return local != null;
    }

    public boolean acceptsWebhook(String presented) {
        return isEnabled() && !secret.isEmpty() && presented != null
                && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Reply-To for a notification about {@code taskId} sent to {@code userId}; empty when inbound mail is off. */
    public Optional<String> replyAddress(Long taskId, Long userId) {
        if (!isEnabled() || taskId == null || userId == null) {
            return Optional.empty();
        }
        return Optional.of(local + "+t" + taskId + "." + userId + "." + sign(taskId + ":" + userId) + "@" + domain);
    }

    /** The address that creates tasks in a project. */
    public Optional<String> projectAddress(String projectKey) {
        return isEnabled() ? Optional.of(local + "+" + projectKey.toLowerCase(Locale.ROOT) + "@" + domain) : Optional.empty();
    }

    /** Handles one message; recipients may include other addresses (Cc, other lists). */
    @Transactional
    public Result process(String from, List<String> recipients, String subject, String text) {
        if (!isEnabled()) {
            return new Result(Outcome.IGNORED, "Inbound email is not configured.");
        }
        String sender = address(from);
        User user = sender == null ? null : users.findByEmailIgnoreCase(sender)
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE).orElse(null);
        if (user == null) {
            return ignored("Unknown sender " + from + ".");
        }
        for (String recipient : recipients) {
            String tag = tag(recipient);
            if (tag == null) {
                continue;
            }
            Matcher reply = REPLY_TAG.matcher(tag);
            if (reply.matches()) {
                return reply(user, Long.valueOf(reply.group(1)), Long.valueOf(reply.group(2)), reply.group(3), text);
            }
            if (PROJECT_TAG.matcher(tag).matches()) {
                return createTask(user, tag.toUpperCase(Locale.ROOT), subject, text);
            }
        }
        return ignored("No FakeJIRA address among the recipients.");
    }

    private Result reply(User sender, Long taskId, Long userId, String signature, String text) {
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                sign(taskId + ":" + userId).getBytes(StandardCharsets.UTF_8))) {
            return ignored("Bad reply signature.");
        }
        // The signed address was sent to one person; only they may reply through it.
        if (!sender.getId().equals(userId)) {
            return ignored("Reply from a different person than the notification was sent to.");
        }
        Optional<Task> task = tasks.findById(taskId);
        if (task.isEmpty() || !task.get().getProject().canEdit(sender)) {
            return ignored("Task " + taskId + " is gone or read-only for " + sender.getUsername() + ".");
        }
        String body = stripQuoted(text);
        if (body.isBlank()) {
            return ignored("Empty reply.");
        }
        details.addComment(sender, taskId, clip(body, MAX_BODY));
        log.info("Email reply from {} added a comment to {}", sender.getUsername(), task.get().getKey());
        return new Result(Outcome.COMMENTED, task.get().getKey());
    }

    private Result createTask(User sender, String projectKey, String subject, String text) {
        Optional<Project> project = projects.findByKey(projectKey);
        if (project.isEmpty() || !project.get().canEdit(sender)) {
            return ignored(sender.getUsername() + " cannot create tasks in " + projectKey + ".");
        }
        String title = subject == null || subject.isBlank() ? "Task from email"
                : subject.replaceFirst("(?i)^\\s*(fwd?|re|aw|wg):\\s*", "").trim();
        String description = clip(stripQuoted(text == null ? "" : text), 5000);
        var created = taskService.create(sender, CreateTaskRequest.of(projectKey, clip(title, 120), description,
                TaskPriority.MEDIUM, TaskType.TASK).withLabels(List.of("email")));
        log.info("Email from {} created {}", sender.getUsername(), created.key());
        return new Result(Outcome.CREATED, created.key());
    }

    private static Result ignored(String reason) {
        log.info("Ignored inbound email: {}", reason);
        return new Result(Outcome.IGNORED, reason);
    }

    /** The part after "+" when {@code recipient} is our address with a tag. */
    String tag(String recipient) {
        String address = address(recipient);
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        String localPart = address.substring(0, at);
        if (!address.substring(at + 1).equals(domain) || !localPart.startsWith(local + "+")) {
            return null;
        }
        return localPart.substring(local.length() + 1);
    }

    /** The bare lower-case address in "Name <a@b>" or "a@b". */
    static String address(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = ADDRESS.matcher(value);
        String found = null;
        while (m.find()) {
            found = m.group();
        }
        return found == null ? null : found.toLowerCase(Locale.ROOT);
    }

    /** Drops quoted history and signatures from a reply. */
    static String stripQuoted(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^on .+wrote:$") || trimmed.matches("(?i)^am .+schrieb.*:$")
                    || trimmed.matches("(?i)^-+\\s*original message\\s*-+$") || trimmed.equals("--")
                    || trimmed.matches("^_{5,}$") || trimmed.matches("(?i)^from: .+") && !kept.isEmpty()) {
                break;
            }
            if (trimmed.startsWith(">")) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).strip();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A random signing key kept in the database, so reply addresses keep working across restarts. */
    private byte[] key() {
        byte[] current = key;
        if (current == null) {
            synchronized (this) {
                String stored = settings.get(KEY_SETTING).orElse(null);
                if (stored == null) {
                    byte[] fresh = new byte[32];
                    new SecureRandom().nextBytes(fresh);
                    stored = HexFormat.of().formatHex(fresh);
                    settings.put(KEY_SETTING, stored);
                }
                current = HexFormat.of().parseHex(stored);
                key = current;
            }
        }
        return current;
    }
}
