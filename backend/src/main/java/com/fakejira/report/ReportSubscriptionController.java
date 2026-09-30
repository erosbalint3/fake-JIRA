package com.fakejira.report;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.dashboard.Dashboard;
import com.fakejira.dashboard.DashboardRepository;
import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.project.ProjectRepository;
import com.fakejira.search.SearchService;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.AccountService;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Reports emailed daily or weekly: a saved query, a project summary, or a dashboard. */
@RestController
@Transactional
public class ReportSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(ReportSubscriptionController.class);
    static final int MAX_PER_USER = 20;
    static final int LIST_LIMIT = 50;

    private final ReportSubscriptionRepository subscriptions;
    private final SearchService search;
    private final ProjectRepository projects;
    private final DashboardRepository dashboards;
    private final TaskRepository tasks;
    private final InsightsController insights;
    private final SlaController sla;
    private final MailService mail;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    public ReportSubscriptionController(ReportSubscriptionRepository subscriptions, SearchService search,
                                        ProjectRepository projects, DashboardRepository dashboards, TaskRepository tasks,
                                        InsightsController insights, SlaController sla, MailService mail,
                                        CurrentUser currentUser, ObjectMapper json) {
        this.subscriptions = subscriptions;
        this.search = search;
        this.projects = projects;
        this.dashboards = dashboards;
        this.tasks = tasks;
        this.insights = insights;
        this.sla = sla;
        this.mail = mail;
        this.currentUser = currentUser;
        this.json = json;
    }

    public record SubscriptionRequest(
            @Pattern(regexp = "filter|project|dashboard", message = "Choose what to send") String kind,
            @NotBlank(message = "Choose what to send") @Size(max = 1000, message = "At most 1000 characters") String target,
            @Size(max = 120, message = "At most 120 characters") String title,
            @Pattern(regexp = "DAILY|WEEKLY", message = "Choose daily or weekly") String frequency,
            @Min(1) @Max(7) Integer weekday,
            @Min(0) @Max(23) Integer hour) {
    }

    public record SubscriptionResponse(Long id, String kind, String target, String title, String frequency, int weekday,
                                       int hour, Instant lastSentAt, Instant nextSendAt) {
    }

    public record Preview(String subject, String body, boolean sent, boolean mailEnabled) {
    }

    @GetMapping("/api/report-subscriptions")
    @Transactional(readOnly = true)
    public List<SubscriptionResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return subscriptions.findByUserIdOrderByIdAsc(user.getId()).stream().map(this::response).toList();
    }

    @PostMapping("/api/report-subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubscriptionRequest request) {
        User user = currentUser.from(jwt);
        if (subscriptions.findByUserIdOrderByIdAsc(user.getId()).size() >= MAX_PER_USER) {
            throw ApiException.badRequest("You can have at most " + MAX_PER_USER + " scheduled reports.");
        }
        ReportSubscription subscription = new ReportSubscription(user, kind(request), target(request));
        apply(subscription, request, user);
        return response(subscriptions.save(subscription));
    }

    @PutMapping("/api/report-subscriptions/{id}")
    public SubscriptionResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                       @Valid @RequestBody SubscriptionRequest request) {
        User user = currentUser.from(jwt);
        ReportSubscription existing = own(user, id);
        if (!existing.getKind().equals(kind(request)) || !existing.getTarget().equals(target(request))) {
            // The target is fixed; a new one means a new subscription.
            subscriptions.delete(existing);
            subscriptions.flush();
            ReportSubscription replacement = new ReportSubscription(user, kind(request), target(request));
            apply(replacement, request, user);
            return response(subscriptions.save(replacement));
        }
        apply(existing, request, user);
        return response(existing);
    }

    @DeleteMapping("/api/report-subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        subscriptions.delete(own(currentUser.from(jwt), id));
    }

    /** What the next email will say. */
    @GetMapping("/api/report-subscriptions/{id}/preview")
    @Transactional(readOnly = true)
    public Preview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        ReportSubscription subscription = own(currentUser.from(jwt), id);
        String[] content = build(subscription).orElseThrow(() -> ApiException.notFound("The report's source no longer exists."));
        return new Preview(content[0], content[1], false, mail.isEnabled());
    }

    /** Sends the report right away (when email is set up) and returns what was sent. */
    @PostMapping("/api/report-subscriptions/{id}/send")
    public Preview sendNow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        ReportSubscription subscription = own(currentUser.from(jwt), id);
        String[] content = build(subscription).orElseThrow(() -> ApiException.notFound("The report's source no longer exists."));
        boolean sent = false;
        if (mail.isEnabled()) {
            mail.send(subscription.getUser().getEmail(), content[0], content[1]);
            subscription.setLastSentAt(Instant.now());
            sent = true;
        }
        return new Preview(content[0], content[1], sent, mail.isEnabled());
    }

    @Scheduled(fixedDelayString = "${app.reports.check-ms:600000}", initialDelay = 90_000)
    public void sendDue() {
        if (!mail.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (ReportSubscription subscription : subscriptions.findAllWithUser()) {
            if (!isDue(subscription, now)) {
                continue;
            }
            subscription.setLastSentAt(now);
            try {
                Optional<String[]> content = build(subscription);
                if (content.isEmpty()) {
                    // The project or dashboard is gone, or the owner lost access: stop sending.
                    subscriptions.delete(subscription);
                    continue;
                }
                mail.send(subscription.getUser().getEmail(), content.get()[0], content.get()[1]);
            } catch (RuntimeException e) {
                log.warn("Could not send scheduled report {}: {}", subscription.getId(), e.getMessage());
            }
        }
    }

    // ---- Scheduling ----------------------------------------------------------------------------------------------

    /** The latest scheduled time at or before {@code now}, in the owner's zone. */
    public static ZonedDateTime lastOccurrence(ReportSubscription s, ZoneId zone, Instant now) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = local.withHour(s.getHour()).withMinute(0).withSecond(0).withNano(0);
        if ("WEEKLY".equals(s.getFrequency())) {
            candidate = candidate.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(s.getWeekday())));
            if (candidate.isAfter(local)) {
                candidate = candidate.minusWeeks(1);
            }
        } else if (candidate.isAfter(local)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    public static boolean isDue(ReportSubscription s, Instant now) {
        Instant occurrence = lastOccurrence(s, s.getUser().zone(), now).toInstant();
        Instant since = s.getLastSentAt() != null && s.getLastSentAt().isAfter(s.getCreatedAt()) ? s.getLastSentAt() : s.getCreatedAt();
        return since.isBefore(occurrence);
    }

    private Instant nextSend(ReportSubscription s) {
        ZoneId zone = s.getUser().zone();
        ZonedDateTime last = lastOccurrence(s, zone, Instant.now());
        return ("WEEKLY".equals(s.getFrequency()) ? last.plusWeeks(1) : last.plusDays(1)).toInstant();
    }

    // ---- Content ---------------------------------------------------------------------------------------------------

    /** Subject and body, or empty when the source no longer exists or is no longer visible to the owner. */
    Optional<String[]> build(ReportSubscription s) {
        User user = s.getUser();
        String link;
        StringBuilder body = new StringBuilder();
        String subject;
        switch (s.getKind()) {
            case "filter" -> {
                List<Task> found = search.run(user, s.getTarget(), SearchService.MAX_RESULTS).tasks();
                subject = s.getTitle() + ": " + found.size() + (found.size() == 1 ? " task" : " tasks");
                body.append(found.size()).append(found.size() == 1 ? " task matches " : " tasks match ")
                        .append(s.getTarget()).append("\n\n");
                taskLines(body, found, LIST_LIMIT);
                link = mail.link("/search?q=" + URLEncoder.encode(s.getTarget(), StandardCharsets.UTF_8));
            }
            case "project" -> {
                Optional<Project> project = projects.findByKey(s.getTarget()).filter(p -> p.hasMember(user));
                if (project.isEmpty()) {
                    return Optional.empty();
                }
                subject = s.getTitle();
                projectSummary(body, project.get());
                link = mail.link("/p/" + project.get().getKey() + "/reports");
            }
            case "dashboard" -> {
                Optional<Dashboard> dashboard = parseId(s.getTarget()).flatMap(dashboards::findById)
                        .filter(d -> d.getOwner().getId().equals(user.getId()));
                if (dashboard.isEmpty()) {
                    return Optional.empty();
                }
                subject = s.getTitle();
                dashboardSummary(body, dashboard.get(), user);
                link = mail.link("/dashboard");
            }
            default -> {
                return Optional.empty();
            }
        }
        body.append("\nOpen in FakeJIRA: ").append(link)
                .append("\n\nYou get this ").append("WEEKLY".equals(s.getFrequency()) ? "weekly" : "daily")
                .append(" report because you scheduled it. Change or stop it in your FakeJIRA profile under Scheduled reports.");
        return Optional.of(new String[]{"[FakeJIRA] " + subject, body.toString()});
    }

    private static void taskLines(StringBuilder body, List<Task> list, int limit) {
        if (list.isEmpty()) {
            body.append("  (none)\n");
        }
        list.stream().limit(limit).forEach(t -> body.append("- ").append(t.getKey()).append(' ').append(t.getTitle())
                .append(" (").append(t.getStatus().label())
                .append(t.getAssignee() == null ? "" : ", " + t.getAssignee().getUsername())
                .append(t.getDueDate() == null ? "" : ", due " + t.getDueDate())
                .append(")\n"));
        if (list.size() > limit) {
            body.append("… and ").append(list.size() - limit).append(" more\n");
        }
    }

    private void projectSummary(StringBuilder body, Project project) {
        List<Task> all = tasks.findByProjectId(project.getId()).stream().filter(t -> !t.isArchived()).toList();
        Map<TaskStatus, Long> byStatus = all.stream().collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
        Instant weekAgo = Instant.now().minusSeconds(7 * 86400L);
        long finished = all.stream().filter(t -> t.getStatus() == TaskStatus.DONE && t.getCompletedAt() != null
                && t.getCompletedAt().isAfter(weekAgo)).count();
        LocalDate today = LocalDate.now();
        List<Task> overdue = all.stream().filter(t -> t.getStatus() != TaskStatus.DONE && t.getDueDate() != null
                && t.getDueDate().isBefore(today)).toList();
        body.append(project.getName()).append(" (").append(project.getKey()).append(")\n\n");
        body.append("Open: ").append(byStatus.getOrDefault(TaskStatus.TODO, 0L)).append(" to do · ")
                .append(byStatus.getOrDefault(TaskStatus.IN_PROGRESS, 0L)).append(" in progress · ")
                .append(byStatus.getOrDefault(TaskStatus.IN_REVIEW, 0L)).append(" in review\n");
        body.append("Finished in the last 7 days: ").append(finished).append('\n');
        body.append("Overdue: ").append(overdue.size()).append('\n');
        taskLines(body, overdue, 10);

        InsightsController.AgingWip aging = insights.agingFor(project);
        List<InsightsController.AgingItem> late = aging.items().stream().filter(i -> "late".equals(i.level())).toList();
        body.append("\nIn progress longer than usual: ").append(late.size()).append('\n');
        late.stream().limit(10).forEach(i -> body.append("- ").append(i.task().key()).append(' ').append(i.task().title())
                .append(" (").append(i.ageDays()).append(" days)\n"));

        SlaController.SlaReport slaReport = sla.reportFor(project, 30);
        long breached = slaReport.attention().stream().filter(i -> "breached".equals(i.state())).count();
        if (slaReport.priorities().stream().anyMatch(p -> p.responseHours() != null || p.resolveHours() != null)) {
            body.append("\nSLA (30 days): responses met ")
                    .append(slaReport.responseMetPercent() == null ? "–" : slaReport.responseMetPercent() + "%")
                    .append(", resolutions met ")
                    .append(slaReport.resolveMetPercent() == null ? "–" : slaReport.resolveMetPercent() + "%")
                    .append("; open tasks past a target: ").append(breached).append('\n');
        }

        InsightsController.Forecast forecast = insights.forecastFor(project, null, null, null, null, null, 12);
        forecast.completion().stream().filter(e -> e.confidence() == 85).findFirst().ifPresent(e ->
                body.append("\nForecast: the ").append(forecast.remaining()).append(" open tasks are done by ")
                        .append(e.date()).append(" (85% confidence)\n"));
    }

    private void dashboardSummary(StringBuilder body, Dashboard dashboard, User user) {
        body.append(dashboard.getName()).append("\n");
        JsonNode widgets;
        try {
            widgets = json.readTree(dashboard.getWidgets());
        } catch (Exception e) {
            return;
        }
        for (JsonNode widget : widgets) {
            String type = widget.path("type").asText();
            String title = widget.path("title").asText(type);
            String query = widget.path("query").asText("");
            switch (type) {
                case "filter" -> {
                    List<Task> found = search.run(user, query, 10).tasks();
                    body.append("\n").append(title).append('\n');
                    taskLines(body, found, 10);
                }
                case "counter" -> body.append("\n").append(title).append(": ")
                        .append(search.run(user, query, SearchService.MAX_RESULTS).total()).append('\n');
                case "chart" -> {
                    List<Task> found = search.run(user, query, SearchService.MAX_RESULTS).tasks();
                    Map<TaskStatus, Long> byStatus = found.stream()
                            .collect(Collectors.groupingBy(Task::getStatus, Collectors.counting()));
                    body.append("\n").append(title).append(": ").append(found.size()).append(" tasks (")
                            .append(byStatus.entrySet().stream().sorted(Map.Entry.comparingByKey())
                                    .map(e -> e.getKey().label() + " " + e.getValue()).collect(Collectors.joining(", ")))
                            .append(")\n");
                }
                case "report" -> {
                    Optional<Project> project = projects.findByKey(widget.path("project").asText("")).filter(p -> p.hasMember(user));
                    project.ifPresent(p -> {
                        body.append("\n").append(title).append("\n");
                        projectSummary(body, p);
                    });
                }
                default -> {
                }
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private static String target(SubscriptionRequest request) {
        String target = request.target().trim();
        return "project".equals(kind(request)) ? target.toUpperCase() : target;
    }

    private static String kind(SubscriptionRequest request) {
        return request.kind() == null ? "filter" : request.kind();
    }

    private void apply(ReportSubscription s, SubscriptionRequest request, User user) {
        String title = request.title() == null || request.title().isBlank() ? null : request.title().trim();
        switch (s.getKind()) {
            case "filter" -> {
                search.run(user, s.getTarget(), 1); // Rejects an invalid query.
                title = title != null ? title : "Tasks: " + (s.getTarget().length() > 60 ? s.getTarget().substring(0, 59) + "…" : s.getTarget());
            }
            case "project" -> {
                Project project = projects.findByKey(s.getTarget().toUpperCase()).filter(p -> p.hasMember(user))
                        .orElseThrow(() -> ApiException.field("target", "Choose one of your projects."));
                title = title != null ? title : project.getName() + " summary";
            }
            case "dashboard" -> {
                Dashboard dashboard = parseId(s.getTarget()).flatMap(dashboards::findById)
                        .filter(d -> d.getOwner().getId().equals(user.getId()))
                        .orElseThrow(() -> ApiException.field("target", "Choose one of your dashboards."));
                title = title != null ? title : dashboard.getName();
            }
            default -> throw ApiException.badRequest("Unknown report.");
        }
        String frequency = request.frequency() == null ? "WEEKLY" : request.frequency();
        s.setSchedule(title, frequency, request.weekday() == null ? 1 : request.weekday(),
                request.hour() == null ? 8 : request.hour());
    }

    private static Optional<Long> parseId(String text) {
        try {
            return Optional.of(Long.valueOf(text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private ReportSubscription own(User user, Long id) {
        return subscriptions.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Scheduled report not found."));
    }

    private SubscriptionResponse response(ReportSubscription s) {
        return new SubscriptionResponse(s.getId(), s.getKind(), s.getTarget(), s.getTitle(), s.getFrequency(),
                s.getWeekday(), s.getHour(), s.getLastSentAt(), nextSend(s));
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        projects.findById(event.projectId()).ifPresent(p ->
                subscriptions.deleteAll(subscriptions.findByKindAndTarget("project", p.getKey())));
    }

    @EventListener
    public void onUserDeleting(AccountService.UserDeleting event) {
        subscriptions.deleteAll(subscriptions.findByUserIdOrderByIdAsc(event.userId()));
    }
}
