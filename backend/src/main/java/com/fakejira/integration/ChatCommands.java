package com.fakejira.integration;

import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.task.TaskEvents;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slash command language, shared by Slack, Mattermost and Discord:
 * {@code help}, {@code KEY-12} (or just {@code 12}), {@code search words}, {@code create Title}.
 */
@Component
public class ChatCommands {

    public enum Style { SLACK, MARKDOWN }

    /** {@code visible}: post in the channel (true) or only to the person who typed it. */
    public record Reply(String text, boolean visible) {
    }

    private final TaskRepository tasks;
    private final TaskService taskService;
    private final MailService mail;

    public ChatCommands(TaskRepository tasks, TaskService taskService, MailService mail) {
        this.tasks = tasks;
        this.taskService = taskService;
        this.mail = mail;
    }

    public Reply run(Project project, String input, String who, String platform, Style style) {
        String text = input == null ? "" : input.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.isEmpty() || lower.equals("help")) {
            return new Reply(help(project, style), false);
        }
        Matcher key = Pattern.compile("^(?:" + Pattern.quote(project.getKey()) + "-)?(\\d{1,9})$", Pattern.CASE_INSENSITIVE).matcher(text);
        if (key.matches()) {
            return tasks.findByProjectIdAndNumber(project.getId(), Integer.parseInt(key.group(1)))
                    .map(task -> new Reply(summary(task, style), true))
                    .orElse(new Reply("There is no " + project.getKey() + "-" + key.group(1) + ".", false));
        }
        if (lower.startsWith("search ")) {
            String q = text.substring(7).trim().toLowerCase(Locale.ROOT);
            List<Task> found = tasks.findByProjectId(project.getId()).stream()
                    .filter(t -> !t.isArchived() && (t.getTitle().toLowerCase(Locale.ROOT).contains(q) || t.getKey().equalsIgnoreCase(q)))
                    .sorted(Comparator.comparing((Task t) -> t.getStatus() == TaskStatus.DONE).thenComparing(Task::getUpdatedAt, Comparator.reverseOrder()))
                    .limit(5).toList();
            if (found.isEmpty()) {
                return new Reply("Nothing in " + project.getKey() + " matches “" + q + "”.", false);
            }
            StringBuilder out = new StringBuilder("Tasks matching “" + q + "”:\n");
            found.forEach(t -> out.append("• ").append(link(t, style)).append(" — ").append(t.getStatus().label()).append('\n'));
            return new Reply(out.toString().trim(), false);
        }
        if (lower.startsWith("create ")) {
            String title = text.substring(7).trim();
            if (title.isEmpty()) {
                return new Reply("Give the task a title: create Fix the login page", false);
            }
            if (title.length() > 120) {
                title = title.substring(0, 119) + "…";
            }
            String finalTitle = title;
            var created = new Object() { Task task; };
            TaskEvents.asRule(platform + " command", () -> {
                var response = taskService.create(project.getOwner(), new CreateTaskRequest(project.getKey(), finalTitle,
                        "Created from " + platform + " by " + (who == null || who.isBlank() ? "someone" : who) + ".",
                        TaskPriority.MEDIUM, null, List.of(platform.toLowerCase(Locale.ROOT)), null, null, null, null, null,
                        TaskType.TASK, null));
                created.task = tasks.findById(response.id()).orElseThrow();
            });
            return new Reply((who == null ? "Someone" : who) + " created " + link(created.task, style), true);
        }
        return new Reply("I did not understand that. " + help(project, style), false);
    }

    public String summary(Task task, Style style) {
        return (style == Style.SLACK ? "*" : "**") + task.getKey() + (style == Style.SLACK ? "*" : "**") + " " + escape(task.getTitle(), style)
                + "\n" + task.getStatus().label() + " · " + task.getPriority().label() + " priority · "
                + (task.getAssignee() == null ? "unassigned" : "assigned to " + task.getAssignee().getName())
                + (task.getDueDate() == null ? "" : " · due " + task.getDueDate())
                + "\n" + url(task);
    }

    public String url(Task task) {
        return mail.link("/tasks/" + task.getId());
    }

    private String link(Task task, Style style) {
        return style == Style.SLACK ? "<" + url(task) + "|" + task.getKey() + " " + escape(task.getTitle(), style) + ">"
                : "[" + task.getKey() + " " + escape(task.getTitle(), style) + "](" + url(task) + ")";
    }

    private static String escape(String text, Style style) {
        return style == Style.SLACK ? text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                : text.replaceAll("([\\\\*_`\\[\\]])", "\\\\$1");
    }

    private static String help(Project project, Style style) {
        String code = style == Style.SLACK ? "`" : "`";
        return "Commands for " + project.getName() + ":\n"
                + "• " + code + project.getKey() + "-12" + code + " — show a task\n"
                + "• " + code + "search checkout" + code + " — find tasks\n"
                + "• " + code + "create Fix the login page" + code + " — create a task";
    }
}
