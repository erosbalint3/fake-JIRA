package com.fakejira.integration;

import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskEvent;
import com.fakejira.task.TaskEvents;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps GitHub issues and their tasks in sync: import, then issue events update tasks (webhook) and task changes
 * update issues (API). Changes made by the sync itself are marked as automated, so they do not bounce back.
 */
@Service
public class IssueSyncService {

    private static final Logger log = LoggerFactory.getLogger(IssueSyncService.class);
    static final String RULE = "GitHub issue sync";
    static final int MAX_IMPORT = 500;

    private final GithubApi github;
    private final GithubRepoLinkRepository repos;
    private final IssueLinkRepository issues;
    private final TaskService taskService;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final LiveEvents live;

    public IssueSyncService(GithubApi github, GithubRepoLinkRepository repos, IssueLinkRepository issues,
                            TaskService taskService, TaskRepository tasks, TaskSupport taskSupport, LiveEvents live) {
        this.github = github;
        this.repos = repos;
        this.issues = issues;
        this.taskService = taskService;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.live = live;
    }

    public record ImportResult(int imported, int alreadyLinked, int pullRequestsSkipped) {
    }

    public ImportResult importIssues(Project project, GithubRepoLink repo, User user, String state) {
        int imported = 0;
        int linked = 0;
        int pulls = 0;
        for (int page = 1; page <= MAX_IMPORT / 100; page++) {
            JsonNode list = github.get(repo.getToken(), GithubApi.repoPath(repo.getRepo())
                    + "/issues?state=" + state + "&per_page=100&page=" + page + "&direction=asc");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode issue : list) {
                if (issue.has("pull_request")) {
                    pulls++;
                    continue;
                }
                if (issues.findByRepoIgnoreCaseAndNumber(repo.getRepo(), issue.path("number").asInt()).isPresent()) {
                    linked++;
                    continue;
                }
                TaskEvents.asRule(RULE, () -> createFromIssue(project, repo, user, issue));
                imported++;
            }
            if (list.size() < 100) {
                break;
            }
        }
        return new ImportResult(imported, linked, pulls);
    }

    private Task createFromIssue(Project project, GithubRepoLink repo, User user, JsonNode issue) {
        List<String> labels = new ArrayList<>();
        boolean bug = false;
        for (JsonNode label : issue.path("labels")) {
            String name = label.path("name").asText("").trim();
            bug |= name.equalsIgnoreCase("bug");
            String clean = name.replaceAll("[,;|]", " ").trim();
            if (!clean.isEmpty() && labels.size() < 9) {
                labels.add(clean.length() > 30 ? clean.substring(0, 30) : clean);
            }
        }
        labels.add("github");
        String title = issue.path("title").asText("Untitled issue");
        String body = issue.path("body").asText("");
        String url = issue.path("html_url").asText();
        String description = (body.length() > 4700 ? body.substring(0, 4700) + "…" : body)
                + (body.isBlank() ? "" : "\n\n") + "_Imported from [" + repo.getRepo() + "#" + issue.path("number").asInt() + "](" + url + ")_";
        Long assignee = null;
        String login = issue.path("assignee").path("login").asText("");
        if (!login.isEmpty()) {
            assignee = project.getMembers().stream().filter(m -> m.getUsername().equalsIgnoreCase(login) && project.canEdit(m))
                    .map(User::getId).findFirst().orElse(null);
        }
        var created = taskService.create(user, new CreateTaskRequest(project.getKey(), title.length() > 120 ? title.substring(0, 119) + "…" : title,
                description, TaskPriority.MEDIUM, null, labels, assignee, null, null, null, null, bug ? TaskType.BUG : TaskType.TASK, null));
        Task task = tasks.findById(created.id()).orElseThrow();
        issues.save(new IssueLink(task, repo.getRepo(), issue.path("number").asInt(), url));
        if ("closed".equals(issue.path("state").asText())) {
            taskService.changeStatus(user, task.getId(), TaskStatus.DONE);
        }
        return task;
    }

    /** An "issues" webhook event from the connected repository. */
    public int onIssueEvent(Project project, JsonNode payload) {
        GithubRepoLink repo = repos.findByProjectId(project.getId()).orElse(null);
        String fullName = payload.path("repository").path("full_name").asText("");
        if (repo == null || !repo.isIssueSync() || !repo.getRepo().equalsIgnoreCase(fullName)) {
            return 0;
        }
        JsonNode issue = payload.path("issue");
        String action = payload.path("action").asText();
        User owner = project.getOwner();
        IssueLink link = issues.findByRepoIgnoreCaseAndNumber(repo.getRepo(), issue.path("number").asInt()).orElse(null);
        if (link == null) {
            if ("opened".equals(action)) {
                TaskEvents.asRule(RULE, () -> createFromIssue(project, repo, owner, issue));
                return 1;
            }
            return 0;
        }
        Task task = link.getTask();
        TaskEvents.asRule(RULE, () -> {
            switch (action) {
                case "closed" -> {
                    if (task.getStatus() != TaskStatus.DONE) taskService.changeStatus(owner, task.getId(), TaskStatus.DONE);
                }
                case "reopened" -> {
                    if (task.getStatus() == TaskStatus.DONE) taskService.changeStatus(owner, task.getId(), TaskStatus.TODO);
                }
                case "edited" -> {
                    String title = issue.path("title").asText(task.getTitle());
                    if (!title.equals(task.getTitle())) {
                        taskSupport.record(task, owner, "renamed to “" + title + "” on GitHub");
                        task.setTitle(title.length() > 120 ? title.substring(0, 119) + "…" : title);
                        live.taskChanged(task);
                    }
                }
                default -> {
                }
            }
        });
        return 1;
    }

    /** Task changes made in FakeJIRA go back to the linked issue. */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskEvent(TaskEvent event) {
        if (event.automated() || event.kind() != TaskEvent.Kind.STATUS_CHANGED && event.kind() != TaskEvent.Kind.UPDATED) {
            return;
        }
        IssueLink link = issues.findByTaskId(event.taskId()).orElse(null);
        if (link == null) {
            return;
        }
        GithubRepoLink repo = repos.findByProjectId(link.getTask().getProject().getId()).orElse(null);
        if (repo == null || !repo.isIssueSync() || repo.getToken() == null || !repo.getRepo().equalsIgnoreCase(link.getRepo())) {
            return;
        }
        Task task = link.getTask();
        Map<String, Object> change;
        if (event.kind() == TaskEvent.Kind.STATUS_CHANGED) {
            boolean done = TaskStatus.DONE.name().equals(event.details().get("to"));
            boolean wasDone = TaskStatus.DONE.name().equals(event.details().get("from"));
            if (done == wasDone) {
                return;
            }
            change = done ? Map.of("state", "closed", "state_reason",
                    task.getResolution() != null && task.getResolution().name().equals("DONE") || task.getResolution() != null
                            && task.getResolution().name().equals("FIXED") ? "completed" : "not_planned")
                    : Map.of("state", "open");
        } else {
            change = Map.of("title", task.getTitle());
        }
        try {
            github.patch(repo.getToken(), GithubApi.repoPath(link.getRepo()) + "/issues/" + link.getNumber(), change);
        } catch (RuntimeException e) {
            log.warn("Could not update GitHub issue {}#{}: {}", link.getRepo(), link.getNumber(), e.getMessage());
        }
    }
}
