package com.fakejira.report;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.search.SearchService;
import com.fakejira.task.Task;
import com.fakejira.user.User;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Excel (.xlsx) and PDF downloads of search results and project reports. */
@RestController
@Transactional(readOnly = true)
public class ExportController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SearchService search;
    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final InsightsController insights;
    private final SlaController sla;

    public ExportController(SearchService search, CurrentUser currentUser, ProjectAccess access,
                            InsightsController insights, SlaController sla) {
        this.search = search;
        this.currentUser = currentUser;
        this.access = access;
        this.insights = insights;
        this.sla = sla;
    }

    static final List<Object> TASK_HEADER = List.of("Key", "Title", "Type", "Status", "Resolution", "Priority", "Assignee",
            "Reporter", "Sprint", "Epic", "Release", "Labels", "Points", "Due", "Created", "Updated");

    static List<Object> taskRow(Task t) {
        List<Object> row = new ArrayList<>();
        row.add(t.getKey());
        row.add(t.getTitle());
        row.add(t.getType().label());
        row.add(t.getStatus().label());
        row.add(t.getResolution() == null ? "" : t.getResolution().name());
        row.add(t.getPriority().label());
        row.add(t.getAssignee() == null ? "" : t.getAssignee().getUsername());
        row.add(t.getReporter().getUsername());
        row.add(t.getSprint() == null ? "" : t.getSprint().getName());
        row.add(t.getEpic() == null ? "" : t.getEpic().getName());
        row.add(t.getRelease() == null ? "" : t.getRelease().getName());
        row.add(String.join(", ", t.getLabels()));
        row.add(t.getStoryPoints());
        row.add(t.getDueDate() == null ? "" : t.getDueDate().toString());
        row.add(t.getCreatedAt().toString().substring(0, 10));
        row.add(t.getUpdatedAt().toString().substring(0, 10));
        return row;
    }

    /** The tasks of an FQL query as a spreadsheet or a PDF table. */
    @GetMapping("/api/search/export")
    public ResponseEntity<byte[]> exportSearch(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q,
                                               @RequestParam(defaultValue = "xlsx") String format) {
        User user = currentUser.from(jwt);
        List<Task> found = search.run(user, q, SearchService.MAX_RESULTS).tasks();
        String name = "fakejira-tasks-" + LocalDate.now();
        if ("pdf".equals(format)) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("Key", "Title", "Status", "Priority", "Assignee", "Due"));
            for (Task t : found) {
                rows.add(List.of(t.getKey(), t.getTitle(), t.getStatus().label(), t.getPriority().label(),
                        t.getAssignee() == null ? "" : t.getAssignee().getUsername(),
                        t.getDueDate() == null ? "" : t.getDueDate().toString()));
            }
            List<PdfWriter.Block> blocks = new ArrayList<>();
            blocks.add(new PdfWriter.Paragraph((q.isBlank() ? "All tasks" : "Query: " + q) + " · " + found.size()
                    + (found.size() == 1 ? " task" : " tasks") + " · exported " + LocalDate.now()));
            blocks.add(new PdfWriter.Table(rows, new float[]{1.2f, 5, 1.5f, 1.2f, 1.6f, 1.3f}));
            return file(PdfWriter.write("FakeJIRA tasks", blocks), MediaType.APPLICATION_PDF, name + ".pdf");
        }
        if (!"xlsx".equals(format)) {
            throw ApiException.badRequest("Format must be xlsx or pdf.");
        }
        List<List<Object>> rows = new ArrayList<>();
        rows.add(TASK_HEADER);
        found.forEach(t -> rows.add(taskRow(t)));
        return file(XlsxWriter.write(List.of(new XlsxWriter.Sheet("Tasks", rows))), XLSX, name + ".xlsx");
    }

    /** The project's reports (forecast, aging work, bug trends, SLA) as a workbook or a PDF. */
    @GetMapping("/api/projects/{key}/reports/export")
    public ResponseEntity<byte[]> exportReports(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                                @RequestParam(defaultValue = "xlsx") String format) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        InsightsController.Forecast forecast = insights.forecast(jwt, key, null, null, null, null, null, 12);
        InsightsController.AgingWip aging = insights.agingWip(jwt, key);
        InsightsController.BugTrends bugs = insights.bugTrends(jwt, key, 12);
        SlaController.SlaReport slaReport = sla.report(jwt, key, 30);

        List<List<Object>> forecastRows = new ArrayList<>();
        forecastRows.add(List.of("Confidence", "Weeks", "Done by"));
        forecast.completion().forEach(e -> forecastRows.add(List.of(e.confidence() + "%", e.weeks(), e.date().toString())));
        forecastRows.add(List.of());
        forecastRows.add(List.of("Remaining items", forecast.remaining()));
        forecastRows.add(List.of("Weekly throughput (oldest first)",
                forecast.weeklyThroughput().stream().map(String::valueOf).collect(Collectors.joining(", "))));

        List<List<Object>> agingRows = new ArrayList<>();
        agingRows.add(List.of("Key", "Title", "Status", "Assignee", "Days in progress", "Level"));
        aging.items().forEach(i -> agingRows.add(List.of(i.task().key(), i.task().title(), i.status().label(),
                i.assignee() == null ? "" : i.assignee().username(), i.ageDays(), i.level())));

        List<List<Object>> bugRows = new ArrayList<>();
        bugRows.add(List.of("Week", "Created", "Resolved", "Open at week end"));
        bugs.weeks().forEach(w -> bugRows.add(List.of(w.weekStart().toString(), w.created(), w.resolved(), w.open())));

        List<List<Object>> slaRows = new ArrayList<>();
        slaRows.add(List.of("Priority", "Response target (h)", "Resolution target (h)", "Tasks", "Responses met",
                "Responses breached", "Resolutions met", "Resolutions breached", "Avg response (h)", "Avg resolution (h)"));
        slaReport.priorities().forEach(p -> slaRows.add(java.util.Arrays.asList(p.priority().label(), p.responseHours(),
                p.resolveHours(), p.tasks(), p.responseMet(), p.responseBreached(), p.resolveMet(), p.resolveBreached(),
                p.averageResponseHours(), p.averageResolveHours())));

        String name = project.getKey().toLowerCase() + "-reports-" + LocalDate.now();
        if ("pdf".equals(format)) {
            List<PdfWriter.Block> blocks = new ArrayList<>();
            blocks.add(new PdfWriter.Paragraph(project.getName() + " · generated " + LocalDate.now()));
            blocks.add(new PdfWriter.Heading("Forecast: " + forecast.scope()));
            blocks.add(forecast.completion().isEmpty()
                    ? new PdfWriter.Paragraph("Not enough finished work yet to forecast (" + forecast.remaining() + " items remaining).")
                    : new PdfWriter.Table(strings(forecastRows.subList(0, forecast.completion().size() + 1)), null));
            blocks.add(new PdfWriter.Heading("Aging work in progress"));
            blocks.add(agingRows.size() == 1 ? new PdfWriter.Paragraph("Nothing in progress.")
                    : new PdfWriter.Table(strings(agingRows), new float[]{1.2f, 5, 1.5f, 1.5f, 1.4f, 1}));
            blocks.add(new PdfWriter.Heading("Bug trends (weekly)"));
            blocks.add(new PdfWriter.Table(strings(bugRows), null));
            blocks.add(new PdfWriter.Heading("SLA, last 30 days"));
            blocks.add(new PdfWriter.Table(strings(slaRows), null));
            return file(PdfWriter.write(project.getName() + " reports", blocks), MediaType.APPLICATION_PDF, name + ".pdf");
        }
        return file(XlsxWriter.write(List.of(new XlsxWriter.Sheet("Forecast", forecastRows),
                new XlsxWriter.Sheet("Aging WIP", agingRows), new XlsxWriter.Sheet("Bug trends", bugRows),
                new XlsxWriter.Sheet("SLA", slaRows))), XLSX, name + ".xlsx");
    }

    private static List<List<String>> strings(List<List<Object>> rows) {
        return rows.stream().map(r -> r.stream().map(v -> v == null ? "" : String.valueOf(v)).toList()).toList();
    }

    static ResponseEntity<byte[]> file(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
