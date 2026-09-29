package com.fakejira.project;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDetailsService;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TimeEntry;
import com.fakejira.task.TimeEntryRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Time report and CSV export/import for a project. */
@RestController
public class ProjectDataController {

    static final int MAX_IMPORT_ROWS = 1000;

    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final TimeEntryRepository time;
    private final EpicRepository epics;
    private final TransactionTemplate transactions;

    public ProjectDataController(ProjectAccess access, CurrentUser currentUser, TaskRepository tasks,
                                 TaskService taskService, TimeEntryRepository time, EpicRepository epics,
                                 TransactionTemplate transactions) {
        this.access = access;
        this.currentUser = currentUser;
        this.tasks = tasks;
        this.taskService = taskService;
        this.time = time;
        this.epics = epics;
        this.transactions = transactions;
    }

    // ---------------------------------------------------------------- time report

    public record UserTime(UserSummary user, int minutes) {
    }

    public record TaskTime(TaskRef task, int minutes) {
    }

    public record TimeReport(LocalDate from, LocalDate to, int totalMinutes, int entries, List<UserTime> byUser,
                             List<TaskTime> byTask) {
    }

    @GetMapping("/api/projects/{key}/time")
    @Transactional(readOnly = true)
    public TimeReport timeReport(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                 @RequestParam(required = false) LocalDate from,
                                 @RequestParam(required = false) LocalDate to) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (end.isBefore(start)) {
            throw ApiException.badRequest("'to' must be on or after 'from'.");
        }
        List<TimeEntry> entries = time.findInProject(project.getId(), start, end);
        Map<Long, User> users = new HashMap<>();
        Map<Long, Integer> perUser = new HashMap<>();
        Map<Long, Task> taskById = new HashMap<>();
        Map<Long, Integer> perTask = new HashMap<>();
        int total = 0;
        for (TimeEntry entry : entries) {
            users.put(entry.getUser().getId(), entry.getUser());
            perUser.merge(entry.getUser().getId(), entry.getMinutes(), Integer::sum);
            taskById.put(entry.getTask().getId(), entry.getTask());
            perTask.merge(entry.getTask().getId(), entry.getMinutes(), Integer::sum);
            total += entry.getMinutes();
        }
        List<UserTime> byUser = perUser.entrySet().stream()
                .map(e -> new UserTime(UserSummary.of(users.get(e.getKey())), e.getValue()))
                .sorted(Comparator.comparingInt(UserTime::minutes).reversed()).toList();
        List<TaskTime> byTask = perTask.entrySet().stream()
                .map(e -> new TaskTime(TaskRef.of(taskById.get(e.getKey())), e.getValue()))
                .sorted(Comparator.comparingInt(TaskTime::minutes).reversed()).toList();
        return new TimeReport(start, end, total, entries.size(), byUser, byTask);
    }

    // ---------------------------------------------------------------- CSV export

    static final List<String> COLUMNS = List.of("Key", "Title", "Description", "Status", "Priority", "Assignee",
            "Reporter", "Labels", "Due date", "Story points", "Sprint", "Epic", "Parent", "Time spent (minutes)",
            "Created", "Updated");

    @GetMapping("/api/projects/{key}/export.csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        List<Task> list = new ArrayList<>(tasks.findByProjectId(project.getId()));
        list.sort(Comparator.comparing(Task::getNumber));
        Map<Long, Integer> minutes = new HashMap<>();
        if (!list.isEmpty()) {
            for (Object[] row : time.totalsFor(list.stream().map(Task::getId).toList())) {
                minutes.put((Long) row[0], ((Number) row[1]).intValue());
            }
        }
        StringBuilder csv = new StringBuilder("﻿").append(Csv.row(COLUMNS));
        for (Task task : list) {
            csv.append(Csv.row(Arrays.asList(
                    task.getKey(), task.getTitle(), task.getDescription(), task.getStatus().label(),
                    task.getPriority().label(),
                    task.getAssignee() == null ? "" : task.getAssignee().getUsername(),
                    task.getReporter().getUsername(),
                    String.join(";", task.getLabels()),
                    task.getDueDate() == null ? "" : task.getDueDate().toString(),
                    task.getStoryPoints() == null ? "" : task.getStoryPoints().toString(),
                    task.getSprint() == null ? "" : task.getSprint().getName(),
                    task.getEpic() == null ? "" : task.getEpic().getName(),
                    task.getParent() == null ? "" : task.getParent().getKey(),
                    String.valueOf(minutes.getOrDefault(task.getId(), 0)),
                    task.getCreatedAt().toString(),
                    task.getUpdatedAt().toString())));
        }
        String filename = project.getKey().toLowerCase(Locale.ROOT) + "-tasks-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- CSV import

    /** {@code row} is the spreadsheet row number (the header is row 1). */
    public record ImportError(int row, String message) {
    }

    public record ImportResult(int created, List<String> keys, List<ImportError> errors) {
    }

    /**
     * Creates one task per row. Recognised headers (case-insensitive): Title (required), Description,
     * Status, Priority, Assignee (username), Labels (separated by ; or |), Due date (yyyy-mm-dd),
     * Story points, Epic (created if missing). Rows are imported independently; bad rows are reported.
     */
    @PostMapping("/api/projects/{key}/import")
    public ImportResult importCsv(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                  @RequestParam("file") MultipartFile file) throws IOException {
        User user = currentUser.from(jwt);
        Project project = transactions.execute(status -> access.editorProject(key, user));
        List<List<String>> rows = Csv.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The file is empty.");
        }
        Map<String, Integer> header = new LinkedHashMap<>();
        List<String> names = rows.get(0);
        for (int i = 0; i < names.size(); i++) {
            header.put(names.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        if (!header.containsKey("title")) {
            throw ApiException.badRequest("The first row must be a header with at least a \"Title\" column.");
        }
        if (rows.size() - 1 > MAX_IMPORT_ROWS) {
            throw ApiException.badRequest("At most " + MAX_IMPORT_ROWS + " rows can be imported at once.");
        }
        Map<String, Long> members = transactions.execute(status -> {
            Map<String, Long> byName = new HashMap<>();
            Project p = access.memberProject(key, user);
            p.getMembers().forEach(m -> byName.put(m.getUsername().toLowerCase(Locale.ROOT), m.getId()));
            return byName;
        });
        Map<String, Long> epicIds = new HashMap<>();
        List<String> keys = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int rowNumber = r + 1;
            try {
                String title = cell(row, header, "title");
                if (title.isBlank()) {
                    throw ApiException.badRequest("Title is empty.");
                }
                if (title.length() > 120) {
                    throw ApiException.badRequest("Title is longer than 120 characters.");
                }
                String description = cell(row, header, "description");
                if (description.length() > 5000) {
                    throw ApiException.badRequest("Description is longer than 5000 characters.");
                }
                TaskPriority priority = parseEnum(TaskPriority.class, cell(row, header, "priority"), TaskPriority.MEDIUM, "priority");
                TaskStatus status = parseEnum(TaskStatus.class, cell(row, header, "status"), TaskStatus.TODO, "status");
                Long assigneeId = null;
                String assignee = cell(row, header, "assignee");
                if (!assignee.isBlank()) {
                    assigneeId = members.get(assignee.toLowerCase(Locale.ROOT));
                    if (assigneeId == null) {
                        throw ApiException.badRequest("Assignee " + assignee + " is not a member of " + project.getKey() + ".");
                    }
                }
                LocalDate due = null;
                String dueText = firstNonBlank(cell(row, header, "due date"), cell(row, header, "due"));
                if (!dueText.isBlank()) {
                    try {
                        due = LocalDate.parse(dueText.trim());
                    } catch (DateTimeParseException e) {
                        throw ApiException.badRequest("Due date must look like 2026-10-31.");
                    }
                }
                Integer points = null;
                String pointsText = firstNonBlank(cell(row, header, "story points"), cell(row, header, "points"));
                if (!pointsText.isBlank()) {
                    try {
                        points = Integer.valueOf(pointsText.trim());
                    } catch (NumberFormatException e) {
                        throw ApiException.badRequest("Story points must be a whole number.");
                    }
                    if (points < 0 || points > 100) {
                        throw ApiException.badRequest("Story points must be between 0 and 100.");
                    }
                }
                List<String> labels = Arrays.stream(cell(row, header, "labels").split("[;|]"))
                        .map(String::trim).filter(l -> !l.isEmpty()).toList();
                Long epicId = null;
                String epicName = cell(row, header, "epic").trim();
                if (!epicName.isEmpty()) {
                    epicId = epicIds.computeIfAbsent(epicName.toLowerCase(Locale.ROOT), n -> findOrCreateEpic(project.getId(), epicName));
                }
                var created = taskService.create(user, new CreateTaskRequest(project.getKey(), title.trim(), description,
                        priority, due, labels.size() > 10 ? labels.subList(0, 10) : labels, assigneeId, null, points,
                        epicId, null));
                if (status != TaskStatus.TODO) {
                    taskService.changeStatus(user, created.id(), status);
                }
                keys.add(created.key());
            } catch (ApiException e) {
                errors.add(new ImportError(rowNumber, e.getMessage()));
            }
        }
        return new ImportResult(keys.size(), keys, errors);
    }

    private Long findOrCreateEpic(Long projectId, String name) {
        return transactions.execute(status -> {
            for (Epic epic : epics.findByProjectIdOrderByCreatedAtAsc(projectId)) {
                if (epic.getName().equalsIgnoreCase(name)) {
                    return epic.getId();
                }
            }
            Project project = access.memberProjectById(projectId);
            int color = (int) (epics.countByProjectId(projectId) % 8);
            return epics.save(new Epic(project, name.length() > 80 ? name.substring(0, 80) : name, "", color, null, null))
                    .getId();
        });
    }

    private static String cell(List<String> row, Map<String, Integer> header, String name) {
        Integer index = header.get(name);
        return index == null || index >= row.size() ? "" : row.get(index);
    }

    private static String firstNonBlank(String a, String b) {
        return a.isBlank() ? b : a;
    }

    /** Accepts enum names (IN_PROGRESS) and labels ("In progress"), case-insensitively. */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback, String what) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        throw ApiException.badRequest("Unknown " + what + " \"" + value.trim() + "\".");
    }
}
