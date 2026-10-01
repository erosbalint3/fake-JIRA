package com.fakejira.servicedesk;

import com.fakejira.common.ApiException;
import com.fakejira.epic.Epic;
import com.fakejira.epic.EpicRepository;
import com.fakejira.events.LiveEvents;
import com.fakejira.notification.NotificationService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.release.Release;
import com.fakejira.release.ReleaseRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskResponse;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The public side of the service desk, used without an account: the request portal (and embedded widget),
 * the requester's tracking page, and the public roadmap and changelog. Nothing internal is exposed: requesters
 * see the status and their conversation with the team, never comments, people or other requests.
 */
@RestController
@Transactional
public class PortalController {

    static final String NOT_FOUND = "This page does not exist.";

    private final ProjectRepository projects;
    private final ServiceDeskRepository desks;
    private final RequestTypeRepository types;
    private final PortalRequestRepository requests;
    private final PortalMessageRepository messages;
    private final TaskService taskService;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final EpicRepository epics;
    private final ReleaseRepository releases;
    private final NotificationService notifications;
    private final ServiceDeskController desk;
    private final LiveEvents live;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public PortalController(ProjectRepository projects, ServiceDeskRepository desks, RequestTypeRepository types,
                            PortalRequestRepository requests, PortalMessageRepository messages, TaskService taskService,
                            TaskRepository tasks, TaskSupport taskSupport, EpicRepository epics, ReleaseRepository releases,
                            NotificationService notifications, ServiceDeskController desk, LiveEvents live, ObjectMapper json) {
        this.projects = projects;
        this.desks = desks;
        this.types = types;
        this.requests = requests;
        this.messages = messages;
        this.taskService = taskService;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.epics = epics;
        this.releases = releases;
        this.notifications = notifications;
        this.desk = desk;
        this.live = live;
        this.json = json;
    }

    // ---- Portal ------------------------------------------------------------------------------------------------

    public record PublicType(Long id, String name, String description, JsonNode fields) {
    }

    public record PortalPage(String projectKey, String projectName, String color, String intro, List<PublicType> requestTypes,
                             boolean roadmap, boolean changelog) {
    }

    @GetMapping("/api/public/portal/{key}")
    @Transactional(readOnly = true)
    public PortalPage portal(@PathVariable String key) {
        Project project = project(key);
        ServiceDesk d = portalDesk(project);
        return new PortalPage(project.getKey(), project.getName(), project.getColor(), d.getIntro(),
                types.findByProjectIdOrderByPositionAscIdAsc(project.getId()).stream()
                        .map(t -> new PublicType(t.getId(), t.getName(), t.getDescription(), desk.parse(t.getFields()))).toList(),
                d.isRoadmapPublic(), d.isChangelogPublic());
    }

    public record NewRequest(Long requestTypeId,
                             @NotBlank(message = "Tell us your name") @Size(max = 80, message = "At most 80 characters") String name,
                             @NotBlank(message = "Tell us your email address") @Email(message = "Enter a valid email address")
                             @Size(max = 254, message = "At most 254 characters") String email,
                             @NotBlank(message = "Summarize your request") @Size(max = 120, message = "At most 120 characters") String summary,
                             @Size(max = 3000, message = "At most 3000 characters") String description,
                             Map<String, Object> answers,
                             /** "widget" when sent from the embedded widget. */
                             String channel,
                             /** Honeypot: people never fill it in, bots do. */
                             String website) {
    }

    public record Created(String token, String reference) {
    }

    @PostMapping("/api/public/portal/{key}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Created submit(@PathVariable String key, @Valid @RequestBody NewRequest request) {
        Project project = project(key);
        portalDesk(project);
        if (request.website() != null && !request.website().isBlank()) {
            // Looks like a bot: pretend it worked.
            return new Created(token(), project.getKey());
        }
        List<RequestType> available = types.findByProjectIdOrderByPositionAscIdAsc(project.getId());
        RequestType type = request.requestTypeId() == null ? (available.isEmpty() ? null : available.get(0))
                : available.stream().filter(t -> t.getId().equals(request.requestTypeId())).findFirst()
                .orElseThrow(() -> ApiException.field("requestTypeId", "Choose what you need help with."));
        ArrayNode answers = answers(type, request.answers());

        StringBuilder description = new StringBuilder(request.description() == null ? "" : request.description().trim());
        if (!answers.isEmpty()) {
            description.append(description.length() > 0 ? "\n\n" : "").append("**Details**\n");
            answers.forEach(a -> description.append("- ").append(a.path("label").asText()).append(": ")
                    .append(a.path("value").asText()).append('\n'));
        }
        description.append(description.length() > 0 ? "\n" : "").append("_Requested by ").append(request.name().trim())
                .append(" (").append(request.email().trim()).append(") through the ")
                .append("widget".equals(request.channel()) ? "feedback widget" : "portal").append("._");
        String text = description.length() > 5000 ? description.substring(0, 4999) + "…" : description.toString();

        User owner = project.getOwner();
        CreateTaskRequest create = new CreateTaskRequest(project.getKey(), request.summary().trim(), text,
                type == null ? com.fakejira.task.TaskPriority.MEDIUM : type.getPriority(), null,
                List.of("widget".equals(request.channel()) ? "feedback" : "portal"), null, null, null, null, null,
                type == null ? TaskType.TASK : type.getTaskType(), null);
        TaskResponse created = taskService.create(owner, create);
        Task task = tasks.findById(created.id()).orElseThrow();
        PortalRequest portal = requests.save(new PortalRequest(task, request.name().trim(), request.email().trim().toLowerCase(),
                token(), type == null ? null : type.getName(), answers.toString(),
                "widget".equals(request.channel()) ? "widget" : "portal"));

        String message = "New request from " + request.name().trim() + ": " + task.getKey() + " · " + task.getTitle();
        project.getMembers().stream().filter(project::canEdit)
                .forEach(member -> notifications.notifyExternal(member, message, task.getId(), project.getId()));
        desk.sendToRequester(portal, "[" + task.getKey() + "] We got your request: " + task.getTitle(),
                "Thanks, " + request.name().trim() + "! Your request " + task.getKey() + " has reached the "
                        + project.getName() + " team.");
        return new Created(portal.getToken(), task.getKey());
    }

    /** Checks the answers against the form and returns them as [{label, value}] in form order. */
    private ArrayNode answers(RequestType type, Map<String, Object> given) {
        ArrayNode out = json.createArrayNode();
        if (type == null) {
            return out;
        }
        for (JsonNode field : desk.parse(type.getFields())) {
            String id = field.path("id").asText();
            String label = field.path("label").asText();
            Object raw = given == null ? null : given.get(id);
            String value = raw == null ? "" : String.valueOf(raw).trim();
            String kind = field.path("kind").asText();
            if ("checkbox".equals(kind)) {
                value = Boolean.parseBoolean(value) ? "Yes" : field.path("required").asBoolean() ? "" : "No";
            }
            if (value.isEmpty()) {
                if (field.path("required").asBoolean()) {
                    throw ApiException.field("answers." + id, label + " is required.");
                }
                continue;
            }
            if (value.length() > 2000) {
                throw ApiException.field("answers." + id, label + " is at most 2000 characters.");
            }
            switch (kind) {
                case "number" -> {
                    try {
                        Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        throw ApiException.field("answers." + id, label + " must be a number.");
                    }
                }
                case "date" -> {
                    try {
                        LocalDate.parse(value);
                    } catch (RuntimeException e) {
                        throw ApiException.field("answers." + id, label + " must be a date.");
                    }
                }
                case "url" -> {
                    if (!value.matches("(?i)https?://\\S+")) {
                        throw ApiException.field("answers." + id, label + " must be a web address (https://…).");
                    }
                }
                case "select" -> {
                    boolean known = false;
                    for (JsonNode option : field.path("options")) {
                        known |= option.asText().equals(value);
                    }
                    if (!known) {
                        throw ApiException.field("answers." + id, "Choose one of the options for " + label + ".");
                    }
                }
                default -> {
                }
            }
            out.addObject().put("label", label).put("value", value);
        }
        return out;
    }

    /** Public "is this already known?" check: matches the query against the public roadmap and changelog only. */
    public record PublicMatch(String title, String kind, String status) {
    }

    @GetMapping("/api/public/portal/{key}/similar")
    @Transactional(readOnly = true)
    public List<PublicMatch> similar(@PathVariable String key, @RequestParam(defaultValue = "") String q) {
        Project project = project(key);
        ServiceDesk d = portalDesk(project);
        if (q.isBlank() || q.length() > 500) {
            return List.of();
        }
        List<PublicMatch> candidates = new ArrayList<>();
        if (d.isRoadmapPublic()) {
            for (Epic epic : epics.findByProjectIdOrderByCreatedAtAsc(project.getId())) {
                candidates.add(new PublicMatch(epic.getName() + "\n" + epic.getDescription(), "roadmap", epicStage(epic)));
            }
        }
        if (d.isChangelogPublic()) {
            for (Release release : shipped(project)) {
                for (Task task : tasks.findByReleaseId(release.getId())) {
                    if (task.getStatus() == TaskStatus.DONE && task.getParent() == null) {
                        candidates.add(new PublicMatch(task.getTitle(), "changelog", release.getName()));
                    }
                }
            }
        }
        return Similarity.rank(q, candidates, PublicMatch::title, 0.3, 3).stream()
                .map(m -> new PublicMatch(m.item().title().split("\n")[0], m.item().kind(), m.item().status())).toList();
    }

    // ---- Tracking page ---------------------------------------------------------------------------------------------

    public record PublicMessage(boolean fromRequester, String author, String body, Instant createdAt) {
    }

    public record Tracking(String reference, String title, String projectName, String projectKey, String status,
                           boolean resolved, String requestType, Instant createdAt, List<PublicMessage> messages) {
    }

    @GetMapping("/api/public/requests/{token}")
    @Transactional(readOnly = true)
    public Tracking tracking(@PathVariable String token) {
        return tracking(byToken(token));
    }

    public record ReplyRequest(@NotBlank(message = "Write a message")
                               @Size(max = PortalMessage.MAX_BODY, message = "At most 5000 characters") String body) {
    }

    @PostMapping("/api/public/requests/{token}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public Tracking reply(@PathVariable String token, @Valid @RequestBody ReplyRequest request) {
        PortalRequest portal = byToken(token);
        Task task = portal.getTask();
        if (messages.forRequest(portal.getId()).size() >= 200) {
            throw ApiException.badRequest("This conversation is too long; please open a new request.");
        }
        messages.save(new PortalMessage(portal, null, request.body().trim()));
        String message = portal.getRequesterName() + " (requester) replied on " + task.getKey() + " · " + task.getTitle();
        taskSupport.participants(task).forEach(p -> notifications.notifyExternal(p, message, task.getId(), task.getProject().getId()));
        live.taskChanged(task);
        return tracking(portal);
    }

    private Tracking tracking(PortalRequest portal) {
        Task task = portal.getTask();
        String status = switch (task.getStatus()) {
            case TODO -> "Waiting for the team";
            case IN_PROGRESS, IN_REVIEW -> "In progress";
            case DONE -> "Resolved";
        };
        List<PublicMessage> conversation = messages.forRequest(portal.getId()).stream()
                .map(m -> new PublicMessage(m.isFromRequester(),
                        m.isFromRequester() ? portal.getRequesterName() : m.getAuthor().getName() + " (" + task.getProject().getName() + ")",
                        m.getBody(), m.getCreatedAt())).toList();
        return new Tracking(task.getKey(), task.getTitle(), task.getProject().getName(), task.getProject().getKey(), status,
                task.getStatus() == TaskStatus.DONE, portal.getRequestType(), portal.getCreatedAt(), conversation);
    }

    private PortalRequest byToken(String token) {
        if (token == null || token.length() < 20) {
            throw ApiException.notFound(NOT_FOUND);
        }
        return requests.findByToken(token).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    // ---- Public roadmap and changelog -------------------------------------------------------------------------------

    public record RoadmapItem(String name, String description, String stage, LocalDate startDate, LocalDate dueDate,
                              int percentDone, int colorIndex) {
    }

    public record Roadmap(String projectName, String projectKey, List<RoadmapItem> items, boolean portal) {
    }

    @GetMapping("/api/public/projects/{key}/roadmap")
    @Transactional(readOnly = true)
    public Roadmap roadmap(@PathVariable String key) {
        Project project = project(key);
        ServiceDesk d = desks.findByProjectId(project.getId()).filter(ServiceDesk::isRoadmapPublic)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        List<RoadmapItem> items = new ArrayList<>();
        for (Epic epic : epics.findByProjectIdOrderByCreatedAtAsc(project.getId())) {
            List<Task> epicTasks = tasks.findByEpicId(epic.getId()).stream().filter(t -> t.getParent() == null).toList();
            long done = epicTasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
            int percent = epicTasks.isEmpty() ? 0 : (int) Math.round(done * 100.0 / epicTasks.size());
            items.add(new RoadmapItem(epic.getName(), epic.getDescription(), epicStage(epic, epicTasks), epic.getStartDate(),
                    epic.getDueDate(), percent, epic.getColorIndex()));
        }
        items.sort(Comparator.comparing((RoadmapItem i) -> List.of("now", "next", "later", "done").indexOf(i.stage()))
                .thenComparing(i -> i.dueDate() == null ? LocalDate.MAX : i.dueDate()));
        return new Roadmap(project.getName(), project.getKey(), items, d.isPortalEnabled());
    }

    public record ChangelogEntry(String version, LocalDate date, String description, List<String> features,
                                 List<String> fixes, List<String> other) {
    }

    public record Changelog(String projectName, String projectKey, List<ChangelogEntry> releases, boolean portal) {
    }

    @GetMapping("/api/public/projects/{key}/changelog")
    @Transactional(readOnly = true)
    public Changelog changelog(@PathVariable String key) {
        Project project = project(key);
        ServiceDesk d = desks.findByProjectId(project.getId()).filter(ServiceDesk::isChangelogPublic)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        List<ChangelogEntry> entries = new ArrayList<>();
        for (Release release : shipped(project)) {
            List<String> features = new ArrayList<>();
            List<String> fixes = new ArrayList<>();
            List<String> other = new ArrayList<>();
            for (Task task : tasks.findByReleaseId(release.getId())) {
                if (task.getStatus() != TaskStatus.DONE || task.getParent() != null) {
                    continue;
                }
                (task.getType() == TaskType.BUG ? fixes : task.getType() == TaskType.STORY ? features : other).add(task.getTitle());
            }
            LocalDate date = release.getReleasedAt() != null
                    ? release.getReleasedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate() : release.getReleaseDate();
            entries.add(new ChangelogEntry(release.getName(), date, release.getDescription(), features, fixes, other));
        }
        return new Changelog(project.getName(), project.getKey(), entries, d.isPortalEnabled());
    }

    private List<Release> shipped(Project project) {
        return releases.findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                .filter(Release::isReleased)
                .sorted(Comparator.comparing((Release r) -> r.getReleasedAt() == null ? Instant.EPOCH : r.getReleasedAt()).reversed())
                .toList();
    }

    private String epicStage(Epic epic) {
        return epicStage(epic, tasks.findByEpicId(epic.getId()).stream().filter(t -> t.getParent() == null).toList());
    }

    /** now: work has started; next: starts within a month; later: the rest; done: everything finished. */
    static String epicStage(Epic epic, List<Task> epicTasks) {
        boolean anyTasks = !epicTasks.isEmpty();
        if (anyTasks && epicTasks.stream().allMatch(t -> t.getStatus() == TaskStatus.DONE)) {
            return "done";
        }
        LocalDate today = LocalDate.now();
        boolean started = epicTasks.stream().anyMatch(t -> t.getStatus() != TaskStatus.TODO)
                || epic.getStartDate() != null && !epic.getStartDate().isAfter(today);
        if (started) {
            return "now";
        }
        return epic.getStartDate() != null && epic.getStartDate().isBefore(today.plusMonths(1)) ? "next" : "later";
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private Project project(String key) {
        if (key == null || key.length() > 20) {
            throw ApiException.notFound(NOT_FOUND);
        }
        return projects.findByKey(key.toUpperCase()).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private ServiceDesk portalDesk(Project project) {
        return desks.findByProjectId(project.getId()).filter(ServiceDesk::isPortalEnabled)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private String token() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
