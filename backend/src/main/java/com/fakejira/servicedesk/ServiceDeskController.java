package com.fakejira.servicedesk;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskEvent;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The team's side of the service desk: settings, request types and their forms, the requester conversation. */
@RestController
@Transactional
public class ServiceDeskController {

    private static final Logger log = LoggerFactory.getLogger(ServiceDeskController.class);
    static final Set<String> FIELD_KINDS = Set.of("text", "textarea", "select", "number", "date", "checkbox", "url");
    static final int MAX_TYPES = 20;
    static final int MAX_FIELDS = 20;

    private final ServiceDeskRepository desks;
    private final RequestTypeRepository types;
    private final PortalRequestRepository requests;
    private final PortalMessageRepository messages;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final MailService mail;
    private final LiveEvents live;
    private final ObjectMapper json;

    public ServiceDeskController(ServiceDeskRepository desks, RequestTypeRepository types, PortalRequestRepository requests,
                                 PortalMessageRepository messages, TaskRepository tasks, TaskSupport taskSupport,
                                 ProjectAccess access, CurrentUser currentUser, MailService mail, LiveEvents live,
                                 ObjectMapper json) {
        this.desks = desks;
        this.types = types;
        this.requests = requests;
        this.messages = messages;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.access = access;
        this.currentUser = currentUser;
        this.mail = mail;
        this.live = live;
        this.json = json;
    }

    // ---- Settings -----------------------------------------------------------------------------------------------

    public record SettingsRequest(boolean portalEnabled,
                                  @Size(max = ServiceDesk.MAX_INTRO, message = "The introduction is at most 4000 characters") String intro,
                                  boolean roadmapPublic, boolean changelogPublic) {
    }

    public record RequestTypeResponse(Long id, String name, String description, TaskType taskType, TaskPriority priority,
                                      JsonNode fields) {
    }

    public record Settings(boolean portalEnabled, String intro, boolean roadmapPublic, boolean changelogPublic,
                           String portalUrl, String roadmapUrl, String changelogUrl, String widgetSnippet,
                           List<RequestTypeResponse> requestTypes) {
    }

    @GetMapping("/api/projects/{key}/service-desk")
    @Transactional(readOnly = true)
    public Settings settings(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return settings(project);
    }

    @PutMapping("/api/projects/{key}/service-desk")
    public Settings save(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody SettingsRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        ServiceDesk desk = desk(project);
        desk.update(request.portalEnabled(), request.intro() == null ? "" : request.intro().trim(), request.roadmapPublic(),
                request.changelogPublic());
        desks.save(desk);
        if (request.portalEnabled() && types.findByProjectIdOrderByPositionAscIdAsc(project.getId()).isEmpty()) {
            // A useful starting point: one general request type.
            RequestType general = new RequestType(project.getId(), 0);
            general.update("Get help", "Ask a question or report a problem.", TaskType.TASK, TaskPriority.MEDIUM, "[]");
            types.save(general);
        }
        return settings(project);
    }

    private Settings settings(Project project) {
        ServiceDesk desk = desks.findByProjectId(project.getId()).orElse(new ServiceDesk(project.getId()));
        String portal = mail.link("/portal/" + project.getKey());
        String snippet = "<script src=\"" + mail.link("/widget.js") + "\" data-project=\"" + project.getKey()
                + "\" async></script>";
        return new Settings(desk.isPortalEnabled(), desk.getIntro(), desk.isRoadmapPublic(), desk.isChangelogPublic(),
                portal, mail.link("/public/" + project.getKey() + "/roadmap"), mail.link("/public/" + project.getKey() + "/changelog"),
                snippet, types.findByProjectIdOrderByPositionAscIdAsc(project.getId()).stream().map(this::typeResponse).toList());
    }

    ServiceDesk desk(Project project) {
        return desks.findByProjectId(project.getId()).orElseGet(() -> new ServiceDesk(project.getId()));
    }

    // ---- Request types and forms ----------------------------------------------------------------------------------

    public record RequestTypeRequest(@NotBlank(message = "Name the request type")
                                     @Size(max = 80, message = "At most 80 characters") String name,
                                     @Size(max = 300, message = "At most 300 characters") String description,
                                     TaskType taskType, TaskPriority priority, JsonNode fields) {
    }

    @PostMapping("/api/projects/{key}/request-types")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestTypeResponse createType(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                          @Valid @RequestBody RequestTypeRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        List<RequestType> existing = types.findByProjectIdOrderByPositionAscIdAsc(project.getId());
        if (existing.size() >= MAX_TYPES) {
            throw ApiException.badRequest("A portal can have at most " + MAX_TYPES + " request types.");
        }
        RequestType type = new RequestType(project.getId(), existing.size());
        apply(type, request);
        return typeResponse(types.save(type));
    }

    @PutMapping("/api/request-types/{id}")
    public RequestTypeResponse updateType(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                          @Valid @RequestBody RequestTypeRequest request) {
        RequestType type = ownedType(id, currentUser.from(jwt));
        apply(type, request);
        return typeResponse(type);
    }

    @DeleteMapping("/api/request-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteType(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        types.delete(ownedType(id, currentUser.from(jwt)));
    }

    @PutMapping("/api/projects/{key}/request-types/order")
    public List<RequestTypeResponse> reorder(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                             @RequestBody @NotNull List<Long> ids) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        access.requireOwner(project, user);
        List<RequestType> list = types.findByProjectIdOrderByPositionAscIdAsc(project.getId());
        for (RequestType type : list) {
            int index = ids.indexOf(type.getId());
            type.setPosition(index < 0 ? ids.size() + type.getPosition() : index);
        }
        types.flush();
        return types.findByProjectIdOrderByPositionAscIdAsc(project.getId()).stream().map(this::typeResponse).toList();
    }

    private RequestType ownedType(Long id, User user) {
        RequestType type = types.findById(id).orElseThrow(() -> ApiException.notFound("Request type not found."));
        Project project = access.memberProjectById(type.getProjectId());
        if (!project.hasMember(user)) {
            throw ApiException.notFound("Request type not found.");
        }
        access.requireOwner(project, user);
        return type;
    }

    private void apply(RequestType type, RequestTypeRequest request) {
        type.update(request.name().trim(), request.description() == null ? "" : request.description().trim(),
                request.taskType() == null ? TaskType.TASK : request.taskType(),
                request.priority() == null ? TaskPriority.MEDIUM : request.priority(), fields(request.fields()));
    }

    /** Checks and normalizes a form definition; returns it as compact JSON. */
    String fields(JsonNode fields) {
        ArrayNode out = json.createArrayNode();
        if (fields == null || fields.isNull()) {
            return "[]";
        }
        if (!fields.isArray()) {
            throw ApiException.field("fields", "Fields must be a list.");
        }
        if (fields.size() > MAX_FIELDS) {
            throw ApiException.field("fields", "A form can have at most " + MAX_FIELDS + " fields.");
        }
        Set<String> ids = new HashSet<>();
        int n = 0;
        for (JsonNode field : fields) {
            n++;
            String label = field.path("label").asText("").trim();
            String kind = field.path("kind").asText("text");
            if (label.isEmpty() || label.length() > 80) {
                throw ApiException.field("fields", "Field " + n + " needs a label of at most 80 characters.");
            }
            if (!FIELD_KINDS.contains(kind)) {
                throw ApiException.field("fields", "Field " + n + " has an unknown kind: " + kind + ".");
            }
            String id = field.path("id").asText("").trim();
            if (id.isEmpty() || !id.matches("[a-z0-9_-]{1,40}")) {
                id = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                if (id.isEmpty() || id.length() > 40) id = "field-" + n;
            }
            while (!ids.add(id)) {
                id = id + "-" + n;
            }
            ObjectNode clean = json.createObjectNode();
            clean.put("id", id);
            clean.put("label", label);
            clean.put("kind", kind);
            clean.put("required", field.path("required").asBoolean(false));
            String help = field.path("help").asText("").trim();
            if (help.length() > 200) {
                throw ApiException.field("fields", "Help text of field " + n + " is at most 200 characters.");
            }
            clean.put("help", help);
            if ("select".equals(kind)) {
                ArrayNode options = clean.putArray("options");
                for (JsonNode option : field.path("options")) {
                    String text = option.asText("").trim();
                    if (!text.isEmpty() && text.length() <= 80 && options.size() < 30) {
                        options.add(text);
                    }
                }
                if (options.isEmpty()) {
                    throw ApiException.field("fields", "The choice field “" + label + "” needs at least one option.");
                }
            }
            out.add(clean);
        }
        return out.toString();
    }

    RequestTypeResponse typeResponse(RequestType type) {
        return new RequestTypeResponse(type.getId(), type.getName(), type.getDescription(), type.getTaskType(),
                type.getPriority(), parse(type.getFields()));
    }

    JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return json.createArrayNode();
        }
    }

    // ---- Conversation with the requester ------------------------------------------------------------------------

    public record MessageResponse(Long id, boolean fromRequester, UserSummary author, String body, Instant createdAt) {
        static MessageResponse of(PortalMessage m) {
            return new MessageResponse(m.getId(), m.isFromRequester(), UserSummary.of(m.getAuthor()), m.getBody(), m.getCreatedAt());
        }
    }

    public record PortalInfo(String requesterName, String requesterEmail, String requestType, String channel,
                             JsonNode answers, String trackingUrl, Instant createdAt, List<MessageResponse> messages,
                             boolean mailEnabled) {
    }

    public record MessageRequest(@NotBlank(message = "Write a reply")
                                 @Size(max = PortalMessage.MAX_BODY, message = "At most 5000 characters") String body) {
    }

    @GetMapping("/api/tasks/{id}/portal")
    @Transactional(readOnly = true)
    public ResponseEntity<PortalInfo> portal(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        if (task.getProject().isGuest(user)) {
            return ResponseEntity.noContent().build();
        }
        return requests.findByTaskId(id).map(r -> ResponseEntity.ok(info(r))).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/tasks/{id}/portal/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public PortalInfo reply(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody MessageRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        PortalRequest portal = requests.findByTaskId(id)
                .orElseThrow(() -> ApiException.notFound("This task did not come from the portal."));
        messages.save(new PortalMessage(portal, user, request.body().trim()));
        taskSupport.record(task, user, "replied to the requester");
        live.taskChanged(task);
        sendToRequester(portal, "[" + task.getKey() + "] " + task.getTitle(),
                user.getName() + " replied to your request:\n\n" + request.body().trim());
        return info(portal);
    }

    PortalInfo info(PortalRequest r) {
        return new PortalInfo(r.getRequesterName(), r.getRequesterEmail(), r.getRequestType(), r.getChannel(),
                parse(r.getAnswers()), trackingUrl(r), r.getCreatedAt(),
                messages.forRequest(r.getId()).stream().map(MessageResponse::of).toList(), mail.isEnabled());
    }

    String trackingUrl(PortalRequest r) {
        return mail.link("/portal/requests/" + r.getToken());
    }

    void sendToRequester(PortalRequest portal, String subject, String body) {
        if (!mail.isEnabled()) {
            return;
        }
        try {
            mail.send(portal.getRequesterEmail(), subject, body + "\n\nSee your request and reply: " + trackingUrl(portal)
                    + "\n\nYou are getting this because you sent a request to "
                    + portal.getTask().getProject().getName() + ".");
        } catch (RuntimeException e) {
            log.warn("Could not email requester of {}: {}", portal.getTask().getKey(), e.getMessage());
        }
    }

    /** Tells the requester when their request is done (or reopened). */
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onStatusChanged(TaskEvent event) {
        if (event.kind() != TaskEvent.Kind.STATUS_CHANGED || !mail.isEnabled()) {
            return;
        }
        String to = event.details().get("to");
        String from = event.details().get("from");
        if (!TaskStatus.DONE.name().equals(to) && !TaskStatus.DONE.name().equals(from)) {
            return;
        }
        requests.findByTaskId(event.taskId()).ifPresent(portal -> {
            Task task = portal.getTask();
            sendToRequester(portal, "[" + task.getKey() + "] " + task.getTitle(), TaskStatus.DONE.name().equals(to)
                    ? "Your request has been resolved. If something is still not right, just reply on the request page."
                    : "Your request has been reopened and the team is looking at it again.");
        });
    }

    // ---- Duplicate detection ------------------------------------------------------------------------------------------

    public record SimilarTask(TaskRef task, double score, boolean done) {
    }

    /** Tasks in the project that look like {@code q}: open ones and those finished in the last 90 days. */
    @GetMapping("/api/projects/{key}/similar")
    @Transactional(readOnly = true)
    public List<SimilarTask> similar(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                     @RequestParam(defaultValue = "") String q, @RequestParam(required = false) Long exclude) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        if (q.isBlank() || q.length() > 5000) {
            return List.of();
        }
        Instant cutoff = Instant.now().minusSeconds(90L * 86400);
        List<Task> candidates = tasks.findByProjectId(project.getId()).stream()
                .filter(t -> !t.getId().equals(exclude) && !t.isArchived())
                .filter(t -> t.getStatus() != TaskStatus.DONE || t.getCompletedAt() != null && t.getCompletedAt().isAfter(cutoff))
                .toList();
        return Similarity.rank(q, candidates, t -> t.getTitle() + " " + t.getTitle() + " "
                        + (t.getDescription().length() > 600 ? t.getDescription().substring(0, 600) : t.getDescription()), 0.2, 5)
                .stream().map(m -> new SimilarTask(TaskRef.of(m.item()), m.score(), m.item().getStatus() == TaskStatus.DONE))
                .toList();
    }

    // ---- Cleanup -------------------------------------------------------------------------------------------------------

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        requests.findByTaskId(event.taskId()).ifPresent(r -> {
            messages.deleteForRequest(r.getId());
            requests.delete(r);
            requests.flush();
        });
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        types.deleteForProject(event.projectId());
        desks.deleteForProject(event.projectId());
    }
}
