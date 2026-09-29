package com.fakejira.integration;

import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns GitHub push and pull_request events into links (and optionally status changes) on tasks. */
@Service
@Transactional
public class GithubWebhookService {

    private final TaskRepository tasks;
    private final DevLinkRepository links;
    private final TaskSupport support;
    private final LiveEvents live;

    public GithubWebhookService(TaskRepository tasks, DevLinkRepository links, TaskSupport support, LiveEvents live) {
        this.tasks = tasks;
        this.links = links;
        this.support = support;
        this.live = live;
    }

    public record Result(int linked, int completed) {
    }

    public Result handle(Project project, String event, JsonNode payload) {
        return switch (event) {
            case "push" -> push(project, payload);
            case "pull_request" -> pullRequest(project, payload);
            default -> new Result(0, 0);
        };
    }

    private Result push(Project project, JsonNode payload) {
        int linked = 0;
        for (JsonNode commit : payload.path("commits")) {
            String sha = commit.path("id").asText();
            String message = commit.path("message").asText();
            String author = firstNonBlank(commit.path("author").path("username").asText(),
                    commit.path("author").path("name").asText());
            for (Task task : mentionedTasks(project, message)) {
                boolean created = upsert(task, DevLink.Kind.COMMIT, sha, commit.path("url").asText(),
                        message.lines().findFirst().orElse(sha), null, author);
                if (created) {
                    support.record(task, project.getOwner(),
                            "(via GitHub) " + author + " referenced the task in commit " + shortSha(sha));
                    live.taskChanged(task);
                    linked++;
                }
            }
        }
        return new Result(linked, 0);
    }

    private Result pullRequest(Project project, JsonNode payload) {
        JsonNode pr = payload.path("pull_request");
        String action = payload.path("action").asText();
        String id = payload.path("repository").path("full_name").asText() + "#" + pr.path("number").asText();
        boolean merged = pr.path("merged").asBoolean(false);
        String state = merged ? "merged" : pr.path("state").asText("open");
        String author = pr.path("user").path("login").asText();
        String text = pr.path("title").asText() + "\n" + pr.path("body").asText("") + "\n" + pr.path("head").path("ref").asText();
        int linked = 0;
        int completed = 0;
        for (Task task : mentionedTasks(project, text)) {
            boolean created = upsert(task, DevLink.Kind.PULL_REQUEST, id, pr.path("html_url").asText(),
                    pr.path("title").asText(), state, author);
            if (created) {
                support.record(task, project.getOwner(), "(via GitHub) " + author + " linked pull request #"
                        + pr.path("number").asText());
                linked++;
            }
            if ("closed".equals(action) && merged && project.isGithubAutoDone() && task.getStatus() != TaskStatus.DONE) {
                support.record(task, project.getOwner(), "(via GitHub) changed status from " + task.getStatus().label()
                        + " to Done because pull request #" + pr.path("number").asText() + " was merged");
                task.setStatus(TaskStatus.DONE);
                task.setBoardColumn(null);
                support.notifyParticipants(task, project.getOwner(), "moved to done (pull request merged)");
                completed++;
            }
            live.taskChanged(task);
        }
        return new Result(linked, completed);
    }

    /** Returns true when a new link was created (as opposed to an update). */
    private boolean upsert(Task task, DevLink.Kind kind, String externalId, String url, String title, String state,
                           String author) {
        Optional<DevLink> existing = links.findByTaskIdAndKindAndExternalId(task.getId(), kind, externalId);
        DevLink link = existing.orElseGet(() -> new DevLink(task, kind, externalId));
        link.update(url.isBlank() ? "https://github.com" : url, title.isBlank() ? externalId : title, state,
                author.length() > 80 ? author.substring(0, 80) : author);
        links.save(link);
        return existing.isEmpty();
    }

    /** Tasks of this project whose keys (e.g. WR-12, case-insensitive) appear in the text. */
    Set<Task> mentionedTasks(Project project, String text) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(project.getKey()) + "-(\\d{1,9})(?![0-9])",
                Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text);
        Set<Task> found = new LinkedHashSet<>();
        while (matcher.find()) {
            tasks.findByProjectIdAndNumber(project.getId(), Integer.valueOf(matcher.group(1))).ifPresent(found::add);
        }
        return found;
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? (b == null || b.isBlank() ? "someone" : b) : a;
    }
}
