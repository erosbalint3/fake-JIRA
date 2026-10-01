package com.fakejira.integration;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The project's GitHub repository: create a branch or a pull request from a task, import issues, see CI results.
 * Uses a fine-grained personal access token (contents, pull requests and issues: read and write).
 */
@RestController
@Transactional
public class GithubRepoController {

    private final GithubRepoLinkRepository repos;
    private final IssueLinkRepository issues;
    private final DevLinkRepository links;
    private final BuildStatusRepository builds;
    private final GithubApi github;
    private final IssueSyncService sync;
    private final TaskSupport taskSupport;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public GithubRepoController(GithubRepoLinkRepository repos, IssueLinkRepository issues, DevLinkRepository links,
                                BuildStatusRepository builds, GithubApi github, IssueSyncService sync, TaskSupport taskSupport,
                                ProjectAccess access, CurrentUser currentUser, LiveEvents live) {
        this.repos = repos;
        this.issues = issues;
        this.links = links;
        this.builds = builds;
        this.github = github;
        this.sync = sync;
        this.taskSupport = taskSupport;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record RepoSettings(String repo, boolean hasToken, String defaultBranch, boolean issueSync) {
    }

    /** {@code token}: null keeps the current one, "" removes it. */
    public record RepoRequest(@Pattern(regexp = "^[A-Za-z0-9_.-]{1,39}/[A-Za-z0-9_.-]{1,100}$", message = "Use the owner/name form, e.g. acme/website")
                              String repo,
                              @Size(max = 300, message = "That token is too long") String token,
                              boolean issueSync) {
    }

    @GetMapping("/api/projects/{key}/github/repo")
    @Transactional(readOnly = true)
    public ResponseEntity<RepoSettings> repo(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return repos.findByProjectId(project.getId()).map(r -> ResponseEntity.ok(settings(r)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/projects/{key}/github/repo")
    public RepoSettings saveRepo(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                 @jakarta.validation.Valid @RequestBody RepoRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        if (request.repo() == null) {
            throw ApiException.field("repo", "Enter the repository as owner/name.");
        }
        GithubRepoLink link = repos.findByProjectId(project.getId()).orElseGet(() -> new GithubRepoLink(project.getId()));
        String token = request.token() == null ? link.getToken() : request.token().isBlank() ? null : request.token().trim();
        if (request.issueSync() && token == null) {
            throw ApiException.field("token", "Syncing issues needs an access token.");
        }
        // Check the repository and the token right away, so mistakes show up here rather than later.
        JsonNode info = github.get(token, GithubApi.repoPath(request.repo()));
        link.update(info.path("full_name").asText(request.repo()), token, info.path("default_branch").asText("main"),
                request.issueSync());
        return settings(repos.save(link));
    }

    @DeleteMapping("/api/projects/{key}/github/repo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRepo(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        repos.deleteForProject(project.getId());
    }

    private static RepoSettings settings(GithubRepoLink link) {
        return new RepoSettings(link.getRepo(), link.getToken() != null, link.getDefaultBranch(), link.isIssueSync());
    }

    // ---- Branches and pull requests --------------------------------------------------------------------------------

    public record BranchRequest(@Size(max = 100) String base, @Size(max = 100) String name) {
    }

    public record BranchResult(String branch, String url) {
    }

    @PostMapping("/api/tasks/{id}/github/branch")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResult createBranch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestBody(required = false) BranchRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        GithubRepoLink repo = linked(task.getProject());
        String base = request != null && request.base() != null && !request.base().isBlank() ? request.base().trim() : repo.getDefaultBranch();
        String branch = request != null && request.name() != null && !request.name().isBlank() ? request.name().trim() : branchName(task);
        JsonNode ref = github.get(repo.getToken(), GithubApi.repoPath(repo.getRepo()) + "/git/ref/heads/" + GithubApi.enc(base));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", "refs/heads/" + branch);
        body.put("sha", ref.path("object").path("sha").asText());
        github.post(repo.getToken(), GithubApi.repoPath(repo.getRepo()) + "/git/refs", body);
        String url = "https://github.com/" + repo.getRepo() + "/tree/" + branch;
        DevLink link = links.findByTaskIdAndKindAndExternalId(task.getId(), DevLink.Kind.BRANCH, repo.getRepo() + ":" + branch)
                .orElseGet(() -> new DevLink(task, DevLink.Kind.BRANCH, repo.getRepo() + ":" + branch));
        link.update(url, branch, "open", user.getUsername());
        links.save(link);
        taskSupport.record(task, user, "created branch " + branch + " on GitHub");
        live.taskChanged(task);
        return new BranchResult(branch, url);
    }

    public record PullRequestRequest(@Size(max = 100) String head, @Size(max = 100) String base, boolean draft) {
    }

    public record PullRequestResult(int number, String url) {
    }

    @PostMapping("/api/tasks/{id}/github/pull-request")
    @ResponseStatus(HttpStatus.CREATED)
    public PullRequestResult createPullRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                               @RequestBody(required = false) PullRequestRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        GithubRepoLink repo = linked(task.getProject());
        String head = request != null && request.head() != null && !request.head().isBlank() ? request.head().trim()
                : links.findByTaskIdOrderByUpdatedAtDesc(task.getId()).stream().filter(l -> l.getKind() == DevLink.Kind.BRANCH)
                .map(DevLink::getTitle).findFirst().orElse(branchName(task));
        String base = request != null && request.base() != null && !request.base().isBlank() ? request.base().trim() : repo.getDefaultBranch();
        String description = task.getDescription().length() > 3000 ? task.getDescription().substring(0, 3000) + "…" : task.getDescription();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", task.getKey() + ": " + task.getTitle());
        body.put("head", head);
        body.put("base", base);
        body.put("draft", request != null && request.draft());
        body.put("body", "Resolves " + task.getKey() + (description.isBlank() ? "" : "\n\n" + description));
        JsonNode pr = github.post(repo.getToken(), GithubApi.repoPath(repo.getRepo()) + "/pulls", body);
        int number = pr.path("number").asInt();
        String url = pr.path("html_url").asText("https://github.com/" + repo.getRepo() + "/pull/" + number);
        DevLink link = links.findByTaskIdAndKindAndExternalId(task.getId(), DevLink.Kind.PULL_REQUEST, repo.getRepo() + "#" + number)
                .orElseGet(() -> new DevLink(task, DevLink.Kind.PULL_REQUEST, repo.getRepo() + "#" + number));
        link.update(url, task.getKey() + ": " + task.getTitle(), "open", user.getUsername());
        links.save(link);
        taskSupport.record(task, user, "opened pull request #" + number + " on GitHub");
        live.taskChanged(task);
        return new PullRequestResult(number, url);
    }

    /** Same rule as the web app's "Copy branch name": KEY-12-short-title. */
    static String branchName(Task task) {
        String slug = Normalizer.normalize(task.getTitle().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? task.getKey() : task.getKey() + "-" + slug;
    }

    private GithubRepoLink linked(Project project) {
        GithubRepoLink repo = repos.findByProjectId(project.getId())
                .orElseThrow(() -> ApiException.badRequest("Connect a GitHub repository in the project settings first."));
        if (repo.getToken() == null) {
            throw ApiException.badRequest("Add a GitHub access token in the project settings first.");
        }
        return repo;
    }

    // ---- Issues -------------------------------------------------------------------------------------------------------

    public record ImportRequest(@Pattern(regexp = "open|all", message = "Choose open or all issues") String state) {
    }

    @PostMapping("/api/projects/{key}/github/import")
    public IssueSyncService.ImportResult importIssues(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                                      @jakarta.validation.Valid @RequestBody(required = false) ImportRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        GithubRepoLink repo = linked(project);
        return sync.importIssues(project, repo, user, request == null || request.state() == null ? "open" : request.state());
    }

    // ---- What the task page shows --------------------------------------------------------------------------------------

    public record BuildResponse(String source, String name, String state, String url, String ref, java.time.Instant updatedAt) {
        static BuildResponse of(BuildStatus b) {
            return new BuildResponse(b.getSource(), b.getName(), b.getState(), b.getUrl(), b.getRef(), b.getUpdatedAt());
        }
    }

    public record TaskGithub(String repo, boolean canCreate, String issueUrl, Integer issueNumber, List<BuildResponse> builds) {
    }

    @GetMapping("/api/tasks/{id}/github")
    @Transactional(readOnly = true)
    public TaskGithub taskGithub(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        GithubRepoLink repo = repos.findByProjectId(task.getProject().getId()).orElse(null);
        IssueLink issue = issues.findByTaskId(id).orElse(null);
        return new TaskGithub(repo == null ? null : repo.getRepo(), repo != null && repo.getToken() != null && task.getProject().canEdit(user),
                issue == null ? null : issue.getUrl(), issue == null ? null : issue.getNumber(),
                builds.findByTaskIdOrderByUpdatedAtDesc(id).stream().map(BuildResponse::of).toList());
    }

    /** The newest build state per task, for board badges. */
    @GetMapping("/api/projects/{key}/builds")
    @Transactional(readOnly = true)
    public Map<Long, BuildResponse> projectBuilds(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        Map<Long, BuildResponse> latest = new LinkedHashMap<>();
        for (BuildStatus build : builds.forProject(project.getId())) {
            latest.putIfAbsent(build.getTask().getId(), BuildResponse.of(build));
        }
        return latest;
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        issues.deleteForTask(event.taskId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        repos.deleteForProject(event.projectId());
    }
}
