package com.fakejira.integration;

import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Records CI results from GitHub Actions/checks, commit statuses, GitLab pipelines and a generic endpoint. */
@Service
public class BuildService {

    private final BuildStatusRepository builds;
    private final GithubWebhookService git;
    private final LiveEvents live;

    public BuildService(BuildStatusRepository builds, GithubWebhookService git, LiveEvents live) {
        this.builds = builds;
        this.git = git;
        this.live = live;
    }

    /** A build to record: {@code text} is searched for task keys (branch, commit message, title). */
    public record Build(String source, String name, String state, String url, String ref, String text) {
    }

    /** Returns how many tasks it was recorded on. */
    public int record(Project project, Build build) {
        if (build.state() == null || build.name() == null || build.name().isBlank()) {
            return 0;
        }
        Set<Task> tasks = git.mentionedTasks(project, build.text() + "\n" + (build.ref() == null ? "" : build.ref()));
        String name = build.name().length() > 120 ? build.name().substring(0, 119) + "…" : build.name();
        String url = build.url() == null || build.url().length() > 500 || !build.url().matches("(?i)https?://.*") ? null : build.url();
        String ref = build.ref() == null ? null : build.ref().length() > 200 ? build.ref().substring(0, 200) : build.ref();
        for (Task task : tasks) {
            BuildStatus status = builds.findByTaskIdAndSourceAndName(task.getId(), build.source(), name)
                    .orElseGet(() -> new BuildStatus(task, build.source(), name));
            status.update(build.state(), url, ref);
            builds.save(status);
            live.taskChanged(task);
        }
        return tasks.size();
    }

    /** GitHub: check runs, workflow runs and commit statuses. Returns null for other events. */
    public Build fromGithub(String event, JsonNode p, String source) {
        return switch (event) {
            case "check_run" -> {
                JsonNode run = p.path("check_run");
                String branch = run.path("check_suite").path("head_branch").asText("");
                yield new Build(source, run.path("name").asText(), githubState(run.path("status").asText(), run.path("conclusion").asText("")),
                        run.path("html_url").asText(null), branch, branch + "\n" + run.path("output").path("title").asText(""));
            }
            case "workflow_run" -> {
                JsonNode run = p.path("workflow_run");
                yield new Build(source, run.path("name").asText(), githubState(run.path("status").asText(), run.path("conclusion").asText("")),
                        run.path("html_url").asText(null), run.path("head_branch").asText(""),
                        run.path("display_title").asText("") + "\n" + run.path("head_commit").path("message").asText(""));
            }
            case "status" -> {
                StringBuilder branches = new StringBuilder();
                p.path("branches").forEach(b -> branches.append(b.path("name").asText()).append('\n'));
                String state = switch (p.path("state").asText()) {
                    case "success" -> "success";
                    case "failure", "error" -> "failure";
                    default -> "pending";
                };
                yield new Build(source, p.path("context").asText("status"), state, p.path("target_url").asText(null),
                        branches.toString().lines().findFirst().orElse(""),
                        branches + p.path("commit").path("commit").path("message").asText(""));
            }
            default -> null;
        };
    }

    static String githubState(String status, String conclusion) {
        if (!"completed".equals(status)) {
            return "in_progress".equals(status) ? "running" : "pending";
        }
        return switch (conclusion) {
            case "success", "neutral", "skipped" -> "success";
            case "cancelled", "stale" -> "cancelled";
            default -> "failure";
        };
    }

    /** GitLab "Pipeline Hook". */
    public Build fromGitlab(JsonNode p) {
        JsonNode pipeline = p.path("object_attributes");
        String state = switch (pipeline.path("status").asText()) {
            case "success" -> "success";
            case "failed" -> "failure";
            case "canceled", "skipped" -> "cancelled";
            case "running" -> "running";
            default -> "pending";
        };
        String url = pipeline.path("url").asText(null);
        if (url == null) {
            url = p.path("project").path("web_url").asText("") + "/-/pipelines/" + pipeline.path("id").asText();
        }
        return new Build("GitLab", pipeline.path("name").asText("pipeline").isBlank() ? "pipeline" : pipeline.path("name").asText("pipeline"),
                state, url, pipeline.path("ref").asText(""), p.path("commit").path("message").asText(""));
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        builds.deleteForTask(event.taskId());
    }
}
