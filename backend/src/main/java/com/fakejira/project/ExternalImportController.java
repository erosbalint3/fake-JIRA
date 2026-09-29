package com.fakejira.project;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.project.ProjectDataController.ImportError;
import com.fakejira.project.ProjectDataController.ImportResult;
import com.fakejira.task.ChecklistItem;
import com.fakejira.task.ChecklistItemRepository;
import com.fakejira.task.TaskDetailsService;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Moves work over from Jira (CSV export) and Trello (JSON export). */
@RestController
public class ExternalImportController {

    static final int MAX_ITEMS = 2000;

    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final TaskService taskService;
    private final TaskDetailsService details;
    private final EpicRepository epics;
    private final ChecklistItemRepository checklist;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public ExternalImportController(ProjectAccess access, CurrentUser currentUser, TaskService taskService,
                                    TaskDetailsService details, EpicRepository epics, ChecklistItemRepository checklist,
                                    TransactionTemplate tx, ObjectMapper json) {
        this.access = access;
        this.currentUser = currentUser;
        this.taskService = taskService;
        this.details = details;
        this.epics = epics;
        this.checklist = checklist;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ Jira

    /**
     * Jira: Filters → Export → CSV (all fields). Epics become epics, sub-tasks stay sub-tasks, statuses and priorities
     * are mapped to the closest match, and people are matched to project members by username, email or name.
     */
    @PostMapping("/api/projects/{key}/import/jira")
    public ImportResult jira(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                             @RequestParam("file") MultipartFile file) throws IOException {
        User user = currentUser.from(jwt);
        Context ctx = context(key, user);
        List<List<String>> rows = Csv.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        if (rows.size() < 2) {
            throw ApiException.badRequest("The file has no issues.");
        }
        if (rows.size() - 1 > MAX_ITEMS) {
            throw ApiException.badRequest("At most " + MAX_ITEMS + " issues can be imported at once.");
        }
        // Jira repeats headers for multi-value fields (Labels, Sprint…).
        Map<String, List<Integer>> header = new HashMap<>();
        List<String> names = rows.get(0);
        for (int i = 0; i < names.size(); i++) {
            header.computeIfAbsent(names.get(i).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(i);
        }
        if (!header.containsKey("summary")) {
            throw ApiException.badRequest("This does not look like a Jira CSV export (no Summary column).");
        }
        List<String> keys = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        Map<String, Long> epicByJiraKey = new HashMap<>();
        Map<String, Long> taskByJiraId = new HashMap<>();

        // Pass 1: epics.
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (!first(row, header, "issue type").equalsIgnoreCase("epic")) {
                continue;
            }
            String name = clip(first(row, header, "summary"), 80);
            Long epicId = tx.execute(s -> epics.save(new Epic(access.memberProjectById(ctx.projectId), name,
                    clip(jiraMarkup(first(row, header, "description")), 1000),
                    (int) (epics.countByProjectId(ctx.projectId) % 8), null, date(first(row, header, "due date")))).getId());
            epicByJiraKey.put(first(row, header, "issue key").toUpperCase(Locale.ROOT), epicId);
            epicByJiraKey.put(first(row, header, "issue id"), epicId);
        }
        // Pass 2: tasks, parents before sub-tasks.
        List<Integer> order = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            if (!isSubtask(rows.get(r), header) && !first(rows.get(r), header, "issue type").equalsIgnoreCase("epic")) {
                order.add(r);
            }
        }
        for (int r = 1; r < rows.size(); r++) {
            if (isSubtask(rows.get(r), header)) {
                order.add(r);
            }
        }
        for (int r : order) {
            List<String> row = rows.get(r);
            try {
                String title = first(row, header, "summary");
                if (title.isBlank()) {
                    throw ApiException.badRequest("Summary is empty.");
                }
                String jiraKey = first(row, header, "issue key");
                String description = jiraMarkup(first(row, header, "description"));
                if (!jiraKey.isBlank()) {
                    description = (description.isBlank() ? "" : description + "\n\n") + "_Imported from Jira " + jiraKey + "_";
                }
                Long parentId = null;
                Long epicId = null;
                String parent = firstNonBlank(first(row, header, "parent"), first(row, header, "parent id"));
                if (isSubtask(row, header)) {
                    parentId = taskByJiraId.get(parent);
                    if (parentId == null) {
                        throw ApiException.badRequest("Its parent issue was not imported.");
                    }
                } else {
                    String epicLink = firstNonBlank(first(row, header, "custom field (epic link)"), parent);
                    epicId = epicByJiraKey.get(epicLink.toUpperCase(Locale.ROOT));
                }
                Set<String> labels = new LinkedHashSet<>();
                for (String label : all(row, header, "labels")) {
                    String clean = label.trim().toLowerCase(Locale.ROOT).replaceAll("[,;|]", "-");
                    if (!clean.isEmpty() && labels.size() < 9) {
                        labels.add(clip(clean, 30));
                    }
                }
                labels.add("jira");
                Integer points = points(firstNonBlank(first(row, header, "custom field (story points)"),
                        first(row, header, "custom field (story point estimate)")));
                CreateTaskRequest request = new CreateTaskRequest(ctx.key, clip(title, 120), clip(description, 5000),
                        jiraPriority(first(row, header, "priority")), date(first(row, header, "due date")),
                        new ArrayList<>(labels), ctx.person(first(row, header, "assignee")), null, points, epicId, parentId,
                        jiraType(first(row, header, "issue type")), List.of());
                var created = taskService.create(user, request);
                TaskStatus status = jiraStatus(first(row, header, "status"));
                if (status != TaskStatus.TODO) {
                    taskService.changeStatus(user, created.id(), status);
                }
                taskByJiraId.put(first(row, header, "issue id"), created.id());
                keys.add(created.key());
            } catch (ApiException e) {
                errors.add(new ImportError(r + 1, e.getMessage()));
            }
        }
        return new ImportResult(keys.size(), keys, errors);
    }

    private static boolean isSubtask(List<String> row, Map<String, List<Integer>> header) {
        String type = first(row, header, "issue type").toLowerCase(Locale.ROOT).replace("-", "");
        return type.equals("subtask") || type.equals("sub task");
    }

    static TaskStatus jiraStatus(String status) {
        String s = status.trim().toLowerCase(Locale.ROOT);
        if (s.matches("done|closed|resolved|complete|completed|released")) {
            return TaskStatus.DONE;
        }
        if (s.matches(".*(review|qa|test).*")) {
            return TaskStatus.IN_REVIEW;
        }
        if (s.matches(".*(progress|doing|development|started).*") && !s.contains("selected")) {
            return TaskStatus.IN_PROGRESS;
        }
        return TaskStatus.TODO;
    }

    static TaskPriority jiraPriority(String priority) {
        return switch (priority.trim().toLowerCase(Locale.ROOT)) {
            case "highest", "blocker", "critical", "urgent" -> TaskPriority.CRITICAL;
            case "high", "major" -> TaskPriority.HIGH;
            case "low", "lowest", "minor", "trivial" -> TaskPriority.LOW;
            default -> TaskPriority.MEDIUM;
        };
    }

    static TaskType jiraType(String type) {
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "bug", "defect", "incident" -> TaskType.BUG;
            case "story", "user story", "feature" -> TaskType.STORY;
            case "spike", "research" -> TaskType.SPIKE;
            default -> TaskType.TASK;
        };
    }

    /** A little of Jira's wiki markup in Markdown: headings, bold, code blocks, links. */
    static String jiraMarkup(String text) {
        // Jira "# item" is a numbered list, not a heading.
        return text.replaceAll("(?m)^#\\s", "1. ").replaceAll("(?m)^h1\\.\\s*", "# ").replaceAll("(?m)^h2\\.\\s*", "## ")
                .replaceAll("(?m)^h3\\.\\s*", "### ").replaceAll("(?m)^h[4-6]\\.\\s*", "#### ")
                .replaceAll("\\{code(:[^}]*)?}", "```").replaceAll("\\{noformat}", "```")
                .replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])", "**$1**")
                .replaceAll("\\[([^|\\]\\n]+)\\|(https?://[^\\]\\s]+)]", "[$1]($2)")
                .replace("\r\n", "\n").strip();
    }

    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/MMM/yy[ h:mm a]").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/MMM/yyyy[ h:mm a]").toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"),
            DateTimeFormatter.ofPattern("M/d/yyyy[ H:mm]"));

    static LocalDate date(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
        }
        try {
            return OffsetDateTime.parse(t).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer points(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int value = (int) Math.round(Double.parseDouble(text.trim()));
            return value < 0 || value > 100 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String first(List<String> row, Map<String, List<Integer>> header, String name) {
        List<Integer> columns = header.get(name);
        if (columns == null) {
            return "";
        }
        for (int c : columns) {
            if (c < row.size() && !row.get(c).isBlank()) {
                return row.get(c).trim();
            }
        }
        return "";
    }

    private static List<String> all(List<String> row, Map<String, List<Integer>> header, String name) {
        List<String> values = new ArrayList<>();
        for (int c : header.getOrDefault(name, List.of())) {
            if (c < row.size() && !row.get(c).isBlank()) {
                values.add(row.get(c));
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ Trello

    /**
     * Trello: board menu → Print, export and share → Export as JSON. Open cards become tasks; the list decides the
     * status (Done / Doing / Review / other) and is kept as a label; checklists and comments come along.
     */
    @PostMapping("/api/projects/{key}/import/trello")
    public ImportResult trello(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                               @RequestParam("file") MultipartFile file) throws IOException {
        User user = currentUser.from(jwt);
        Context ctx = context(key, user);
        JsonNode board;
        try {
            board = json.readTree(file.getBytes());
        } catch (IOException e) {
            throw ApiException.badRequest("This is not a Trello JSON export.");
        }
        if (!board.path("cards").isArray() || !board.path("lists").isArray()) {
            throw ApiException.badRequest("This is not a Trello JSON export (no cards or lists).");
        }
        if (board.path("cards").size() > MAX_ITEMS) {
            throw ApiException.badRequest("At most " + MAX_ITEMS + " cards can be imported at once.");
        }
        Map<String, JsonNode> lists = new HashMap<>();
        board.path("lists").forEach(l -> lists.put(l.path("id").asText(), l));
        Map<String, List<JsonNode>> checklistsByCard = new HashMap<>();
        board.path("checklists").forEach(c -> checklistsByCard.computeIfAbsent(c.path("idCard").asText(), k -> new ArrayList<>()).add(c));
        Map<String, List<JsonNode>> commentsByCard = new HashMap<>();
        board.path("actions").forEach(a -> {
            if ("commentCard".equals(a.path("type").asText())) {
                commentsByCard.computeIfAbsent(a.path("data").path("card").path("id").asText(), k -> new ArrayList<>()).add(a);
            }
        });
        Map<String, String> memberNames = new HashMap<>();
        board.path("members").forEach(m -> memberNames.put(m.path("id").asText(), m.path("username").asText()));

        List<String> keys = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        int index = 0;
        for (JsonNode card : board.path("cards")) {
            index++;
            JsonNode list = lists.get(card.path("idList").asText());
            if (card.path("closed").asBoolean(false) || list == null || list.path("closed").asBoolean(false)) {
                continue;
            }
            try {
                String title = card.path("name").asText().trim();
                if (title.isEmpty()) {
                    throw ApiException.badRequest("Card has no name.");
                }
                String listName = list.path("name").asText();
                Set<String> labels = new LinkedHashSet<>();
                for (JsonNode label : card.path("labels")) {
                    String name = firstNonBlank(label.path("name").asText(), label.path("color").asText());
                    if (!name.isBlank() && labels.size() < 8) {
                        labels.add(clip(name.toLowerCase(Locale.ROOT).replaceAll("[,;|]", "-"), 30));
                    }
                }
                labels.add(clip("list-" + listName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""), 30));
                labels.add("trello");
                List<String> items = new ArrayList<>();
                List<Boolean> itemDone = new ArrayList<>();
                for (JsonNode cl : checklistsByCard.getOrDefault(card.path("id").asText(), List.of())) {
                    for (JsonNode item : cl.path("checkItems")) {
                        if (items.size() < 50) {
                            items.add(clip(item.path("name").asText(), 200));
                            itemDone.add("complete".equals(item.path("state").asText()));
                        }
                    }
                }
                Long assignee = null;
                for (JsonNode member : card.path("idMembers")) {
                    assignee = ctx.person(memberNames.getOrDefault(member.asText(), ""));
                    if (assignee != null) {
                        break;
                    }
                }
                TaskStatus status = card.path("dueComplete").asBoolean(false) ? TaskStatus.DONE : jiraStatus(listName);
                String description = card.path("desc").asText("");
                var created = taskService.create(user, new CreateTaskRequest(ctx.key, clip(title, 120), clip(description, 5000),
                        TaskPriority.MEDIUM, date(card.path("due").asText("")), new ArrayList<>(labels), assignee, null, null,
                        null, null, TaskType.TASK, items));
                if (status != TaskStatus.TODO) {
                    taskService.changeStatus(user, created.id(), status);
                }
                if (itemDone.contains(true)) {
                    tx.executeWithoutResult(s -> {
                        List<ChecklistItem> saved = checklist.findByTaskIdOrderByPositionAscIdAsc(created.id());
                        for (int i = 0; i < saved.size() && i < itemDone.size(); i++) {
                            saved.get(i).setDone(itemDone.get(i));
                        }
                    });
                }
                List<JsonNode> cardComments = new ArrayList<>(commentsByCard.getOrDefault(card.path("id").asText(), List.of()));
                java.util.Collections.reverse(cardComments);
                for (JsonNode comment : cardComments) {
                    String author = comment.path("memberCreator").path("fullName").asText("Someone");
                    details.addComment(user, created.id(), clip("**" + author + "** (Trello): " + comment.path("data").path("text").asText(), 2000));
                }
                keys.add(created.key());
            } catch (ApiException e) {
                errors.add(new ImportError(index, e.getMessage()));
            }
        }
        return new ImportResult(keys.size(), keys, errors);
    }

    // ------------------------------------------------------------------ shared

    /** People matched by username, email or display name (case-insensitive). */
    private record Context(Long projectId, String key, Map<String, Long> people) {
        Long person(String name) {
            return name == null || name.isBlank() ? null : people.get(name.trim().toLowerCase(Locale.ROOT));
        }
    }

    private Context context(String key, User user) {
        return tx.execute(s -> {
            Project project = access.editorProject(key, user);
            Map<String, Long> people = new HashMap<>();
            for (User m : project.getMembers()) {
                if (project.canEdit(m)) {
                    people.put(m.getUsername().toLowerCase(Locale.ROOT), m.getId());
                    people.put(m.getEmail().toLowerCase(Locale.ROOT), m.getId());
                    people.putIfAbsent(m.getName().toLowerCase(Locale.ROOT), m.getId());
                }
            }
            return new Context(project.getId(), project.getKey(), people);
        });
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? (b == null ? "" : b) : a;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
