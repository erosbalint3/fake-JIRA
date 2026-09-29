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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** A commit from any git host. */
    public record Commit(String sha, String message, String url, String author) {
    }

    /** A pull/merge request from any git host; {@code mergedNow} is true for the "merged" event itself. */
    public record PullRequest(String id, String number, String title, String text, String url, String state,
                              String author, boolean mergedNow) {
    }

    public Result handle(Project project, String event, JsonNode payload) {
        return switch (event) {
            case "push" -> push(project, "GitHub", githubCommits(payload));
            case "pull_request" -> pullRequest(project, "GitHub", githubPullRequest(payload));
            default -> new Result(0, 0);
        };
    }

    /** Gitea and Forgejo send GitHub-style payloads. */
    public Result handleGitea(Project project, String event, JsonNode payload) {
        return switch (event) {
            case "push" -> push(project, "Gitea", githubCommits(payload));
            case "pull_request" -> pullRequest(project, "Gitea", githubPullRequest(payload));
            default -> new Result(0, 0);
        };
    }

    public Result handleGitlab(Project project, String event, JsonNode payload) {
        switch (event) {
            case "Push Hook" -> {
                List<Commit> commits = new ArrayList<>();
                for (JsonNode c : payload.path("commits")) {
                    commits.add(new Commit(c.path("id").asText(), c.path("message").asText(), c.path("url").asText(),
                            firstNonBlank(c.path("author").path("name").asText(), payload.path("user_username").asText())));
                }
                return push(project, "GitLab", commits);
            }
            case "Merge Request Hook" -> {
                JsonNode mr = payload.path("object_attributes");
                String state = switch (mr.path("state").asText("opened")) {
                    case "merged" -> "merged";
                    case "closed", "locked" -> "closed";
                    default -> "open";
                };
                String text = mr.path("title").asText() + "\n" + mr.path("description").asText("") + "\n"
                        + mr.path("source_branch").asText();
                return pullRequest(project, "GitLab", new PullRequest(
                        payload.path("project").path("path_with_namespace").asText() + "!" + mr.path("iid").asText(),
                        "!" + mr.path("iid").asText(), mr.path("title").asText(), text, mr.path("url").asText(), state,
                        payload.path("user").path("username").asText(), "merge".equals(mr.path("action").asText())));
            }
            default -> {
                return new Result(0, 0);
            }
        }
    }

    private static List<Commit> githubCommits(JsonNode payload) {
        List<Commit> commits = new ArrayList<>();
        for (JsonNode commit : payload.path("commits")) {
            commits.add(new Commit(commit.path("id").asText(), commit.path("message").asText(), commit.path("url").asText(),
                    firstNonBlank(commit.path("author").path("username").asText(), commit.path("author").path("name").asText())));
        }
        return commits;
    }

    private static PullRequest githubPullRequest(JsonNode payload) {
        JsonNode pr = payload.path("pull_request");
        String action = payload.path("action").asText();
        boolean merged = pr.path("merged").asBoolean(false);
        String text = pr.path("title").asText() + "\n" + pr.path("body").asText("") + "\n" + pr.path("head").path("ref").asText();
        return new PullRequest(payload.path("repository").path("full_name").asText() + "#" + pr.path("number").asText(),
                "#" + pr.path("number").asText(), pr.path("title").asText(), text, pr.path("html_url").asText(),
                merged ? "merged" : pr.path("state").asText("open"), pr.path("user").path("login").asText(),
                "closed".equals(action) && merged);
    }

    private Result push(Project project, String source, List<Commit> commits) {
        int linked = 0;
        for (Commit commit : commits) {
            for (Task task : mentionedTasks(project, commit.message())) {
                boolean created = upsert(task, DevLink.Kind.COMMIT, commit.sha(), commit.url(),
                        commit.message().lines().findFirst().orElse(commit.sha()), null, commit.author(), source);
                if (created) {
                    support.record(task, project.getOwner(),
                            "(via " + source + ") " + commit.author() + " referenced the task in commit " + shortSha(commit.sha()));
                    live.taskChanged(task);
                    linked++;
                }
            }
        }
        return new Result(linked, 0);
    }

    private Result pullRequest(Project project, String source, PullRequest pr) {
        int linked = 0;
        int completed = 0;
        for (Task task : mentionedTasks(project, pr.text())) {
            boolean created = upsert(task, DevLink.Kind.PULL_REQUEST, pr.id(), pr.url(), pr.title(), pr.state(), pr.author(), source);
            if (created) {
                support.record(task, project.getOwner(), "(via " + source + ") " + pr.author() + " linked "
                        + (source.equals("GitLab") ? "merge request " : "pull request ") + pr.number());
                linked++;
            }
            if (pr.mergedNow() && project.isGithubAutoDone() && task.getStatus() != TaskStatus.DONE) {
                support.record(task, project.getOwner(), "(via " + source + ") changed status from " + task.getStatus().label()
                        + " to Done because " + pr.number() + " was merged");
                task.setStatus(TaskStatus.DONE);
                task.setBoardColumn(null);
                support.notifyParticipants(task, project.getOwner(), "moved to done (" + pr.number() + " merged)");
                completed++;
            }
            live.taskChanged(task);
        }
        return new Result(linked, completed);
    }

    /** Returns true when a new link was created (as opposed to an update). */
    private boolean upsert(Task task, DevLink.Kind kind, String externalId, String url, String title, String state,
                           String author, String source) {
        Optional<DevLink> existing = links.findByTaskIdAndKindAndExternalId(task.getId(), kind, externalId);
        DevLink link = existing.orElseGet(() -> new DevLink(task, kind, externalId));
        link.update(url.isBlank() ? "https://" + source.toLowerCase() + ".com" : url, title.isBlank() ? externalId : title, state,
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
