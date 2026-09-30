package com.fakejira.report;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Team health checks. Votes are anonymous: only totals are ever returned, and they appear once you have voted
 * (so earlier answers do not sway yours) or the check is closed.
 */
@RestController
@Transactional
public class HealthCheckController {

    static final int MAX_CATEGORIES = 12;

    private final HealthCheckRepository checks;
    private final HealthVoteRepository votes;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public HealthCheckController(HealthCheckRepository checks, HealthVoteRepository votes, ProjectAccess access,
                                 CurrentUser currentUser, LiveEvents live) {
        this.checks = checks;
        this.votes = votes;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record CreateRequest(@NotBlank(message = "Give the health check a title")
                                @Size(max = 120, message = "At most 120 characters") String title,
                                @Size(max = MAX_CATEGORIES, message = "At most 12 areas")
                                List<@NotBlank @Size(max = 60, message = "Area names are at most 60 characters") String> categories) {
    }

    public record Vote(@NotBlank String category,
                       @NotNull @Min(value = 1, message = "Rate red, amber or green") @Max(value = 3, message = "Rate red, amber or green") Integer score,
                       @Min(-1) @Max(1) Integer trend) {
    }

    public record VotesRequest(@NotNull @Size(max = MAX_CATEGORIES) List<@Valid Vote> votes) {
    }

    /** Totals for one area; {@code average} runs from 1 (red) to 3 (green). */
    public record CategoryResult(String category, int red, int amber, int green, int worse, int stable, int better,
                                 Double average) {
    }

    public record MyVote(String category, int score, int trend) {
    }

    public record CheckSummary(Long id, String title, UserSummary createdBy, Instant createdAt, boolean closed,
                               int voters, List<String> categories, Map<String, Double> averages) {
    }

    public record CheckDetail(CheckSummary check, boolean resultsVisible, List<CategoryResult> results,
                              List<MyVote> mine, boolean canManage, int members) {
    }

    @GetMapping("/api/projects/{key}/health-checks")
    @Transactional(readOnly = true)
    public List<CheckSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        List<HealthCheck> list = checks.findByProjectIdOrderByCreatedAtDesc(project.getId());
        Map<Long, List<HealthVote>> byCheck = new HashMap<>();
        if (!list.isEmpty()) {
            votes.forChecks(list.stream().map(HealthCheck::getId).toList())
                    .forEach(v -> byCheck.computeIfAbsent(v.getCheck().getId(), id -> new ArrayList<>()).add(v));
        }
        return list.stream().map(check -> {
            List<HealthVote> checkVotes = byCheck.getOrDefault(check.getId(), List.of());
            boolean visible = check.isClosed() || checkVotes.stream().anyMatch(v -> v.getUser().getId().equals(user.getId()));
            return summary(check, checkVotes, visible);
        }).toList();
    }

    @PostMapping("/api/projects/{key}/health-checks")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckDetail create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                              @Valid @RequestBody CreateRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        List<String> categories = request.categories() == null || request.categories().isEmpty()
                ? HealthCheck.DEFAULT_CATEGORIES
                : new ArrayList<>(new LinkedHashSet<>(request.categories().stream().map(String::trim).toList()));
        HealthCheck check = checks.save(new HealthCheck(project, request.title().trim(), categories, user));
        live.projectChanged(project);
        return detail(check, user);
    }

    @GetMapping("/api/health-checks/{id}")
    @Transactional(readOnly = true)
    public CheckDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        return detail(visible(id, user), user);
    }

    /** Saves the caller's ratings (replacing earlier ones) while the check is open. */
    @PutMapping("/api/health-checks/{id}/votes")
    public CheckDetail vote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody VotesRequest request) {
        User user = currentUser.from(jwt);
        HealthCheck check = visible(id, user);
        if (!check.getProject().canEdit(user)) {
            throw ApiException.forbidden("Only team members can take part in the health check.");
        }
        if (check.isClosed()) {
            throw ApiException.conflict("This health check is closed.");
        }
        Set<String> allowed = new HashSet<>(check.getCategories());
        Map<String, HealthVote> existing = new HashMap<>();
        votes.forCheck(id).stream().filter(v -> v.getUser().getId().equals(user.getId()))
                .forEach(v -> existing.put(v.getCategory(), v));
        for (Vote vote : request.votes()) {
            if (!allowed.contains(vote.category())) {
                throw ApiException.badRequest("Unknown area: " + vote.category());
            }
            HealthVote row = existing.computeIfAbsent(vote.category(), c -> new HealthVote(check, user, c));
            row.set(vote.score(), vote.trend() == null ? 0 : vote.trend());
            votes.save(row);
        }
        votes.flush();
        live.projectChanged(check.getProject());
        return detail(check, user);
    }

    @PostMapping("/api/health-checks/{id}/close")
    public CheckDetail close(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        HealthCheck check = managed(id, user);
        check.setClosedAt(check.isClosed() ? null : Instant.now());
        live.projectChanged(check.getProject());
        return detail(check, user);
    }

    @DeleteMapping("/api/health-checks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        HealthCheck check = managed(id, currentUser.from(jwt));
        votes.deleteForCheck(id);
        checks.delete(check);
        live.projectChanged(check.getProject());
    }

    private HealthCheck visible(Long id, User user) {
        return checks.findById(id).filter(c -> c.getProject().hasMember(user))
                .orElseThrow(() -> ApiException.notFound("Health check not found."));
    }

    private HealthCheck managed(Long id, User user) {
        HealthCheck check = visible(id, user);
        if (!canManage(check, user)) {
            throw ApiException.forbidden("Only whoever started the health check or the project owner can do that.");
        }
        return check;
    }

    private static boolean canManage(HealthCheck check, User user) {
        return check.getCreatedBy().getId().equals(user.getId()) || check.getProject().isOwner(user);
    }

    private CheckDetail detail(HealthCheck check, User user) {
        List<HealthVote> all = votes.forCheck(check.getId());
        List<MyVote> mine = all.stream().filter(v -> v.getUser().getId().equals(user.getId()))
                .map(v -> new MyVote(v.getCategory(), v.getScore(), v.getTrend())).toList();
        boolean visible = check.isClosed() || !mine.isEmpty();
        List<CategoryResult> results = new ArrayList<>();
        if (visible) {
            for (String category : check.getCategories()) {
                int[] c = new int[6];
                int sum = 0;
                int n = 0;
                for (HealthVote v : all) {
                    if (!v.getCategory().equals(category)) continue;
                    c[v.getScore() - 1]++;
                    c[4 + v.getTrend()]++;
                    sum += v.getScore();
                    n++;
                }
                results.add(new CategoryResult(category, c[0], c[1], c[2], c[3], c[4], c[5],
                        n == 0 ? null : Math.round(sum * 100.0 / n) / 100.0));
            }
        }
        long members = check.getProject().getMembers().stream().filter(m -> check.getProject().canEdit(m)).count();
        return new CheckDetail(summary(check, all, visible), visible, results, mine, canManage(check, user), (int) members);
    }

    private static CheckSummary summary(HealthCheck check, List<HealthVote> all, boolean visible) {
        Set<Long> voters = new HashSet<>();
        Map<String, int[]> sums = new HashMap<>();
        for (HealthVote v : all) {
            voters.add(v.getUser().getId());
            int[] s = sums.computeIfAbsent(v.getCategory(), c -> new int[2]);
            s[0] += v.getScore();
            s[1]++;
        }
        Map<String, Double> averages = new java.util.LinkedHashMap<>();
        if (visible) {
            for (String category : check.getCategories()) {
                int[] s = sums.get(category);
                averages.put(category, s == null ? null : Math.round(s[0] * 100.0 / s[1]) / 100.0);
            }
        }
        return new CheckSummary(check.getId(), check.getTitle(), UserSummary.of(check.getCreatedBy()), check.getCreatedAt(),
                check.isClosed(), voters.size(), check.getCategories(), averages);
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        votes.deleteForProject(event.projectId());
        checks.deleteForProject(event.projectId());
    }
}
