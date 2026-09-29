package com.fakejira.dashboard;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskSupport;
import com.fakejira.task.TaskType;
import com.fakejira.user.AccountService.UserDeleting;
import com.fakejira.user.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Personal dashboards and the "recently viewed" list. */
@RestController
public class DashboardController {

    static final Set<String> WIDGET_TYPES = Set.of("filter", "chart", "counter", "activity", "recent", "calendar", "sprint");
    static final int MAX_WIDGETS = 24;
    static final int MAX_DASHBOARDS = 20;
    static final int RECENT_LIMIT = 20;

    /** What a new dashboard starts with. */
    static final String DEFAULT_WIDGETS = """
            [{"type":"filter","title":"Assigned to me","query":"assignee = me AND status != done ORDER BY priority DESC"},
             {"type":"filter","title":"Due in the next 7 days","query":"assignee = me AND status != done AND due <= +7d ORDER BY due"},
             {"type":"chart","title":"My work by status","query":"assignee = me","groupBy":"status"},
             {"type":"recent","title":"Recently viewed"},
             {"type":"activity","title":"Latest activity"}]""";

    private final DashboardRepository dashboards;
    private final RecentViewRepository recent;
    private final TaskRepository tasks;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    public DashboardController(DashboardRepository dashboards, RecentViewRepository recent, TaskRepository tasks,
                               TaskSupport taskSupport, CurrentUser currentUser, ObjectMapper json) {
        this.dashboards = dashboards;
        this.recent = recent;
        this.tasks = tasks;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.json = json;
    }

    public record DashboardRequest(
            @NotBlank(message = "Name is required") @Size(max = 60, message = "Name must be at most 60 characters") String name,
            @NotNull(message = "Widgets are required") JsonNode widgets) {
    }

    public record DashboardResponse(Long id, String name, JsonNode widgets) {
    }

    public record RecentTask(Long id, String key, String title, TaskStatus status, TaskType type, String projectKey,
                             Instant viewedAt) {
    }

    /** The caller's dashboards; the first visit creates a starter one. */
    @GetMapping("/api/dashboards")
    @Transactional
    public List<DashboardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        List<Dashboard> list = dashboards.findByOwnerIdOrderByIdAsc(user.getId());
        if (list.isEmpty()) {
            list = List.of(dashboards.save(new Dashboard(user, "My dashboard", compact(DEFAULT_WIDGETS))));
        }
        return list.stream().map(this::response).toList();
    }

    @PostMapping("/api/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DashboardResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DashboardRequest request) {
        User user = currentUser.from(jwt);
        if (dashboards.findByOwnerIdOrderByIdAsc(user.getId()).size() >= MAX_DASHBOARDS) {
            throw ApiException.badRequest("You can have at most " + MAX_DASHBOARDS + " dashboards.");
        }
        return response(dashboards.save(new Dashboard(user, request.name().trim(), widgets(request.widgets()))));
    }

    @PutMapping("/api/dashboards/{id}")
    @Transactional
    public DashboardResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody DashboardRequest request) {
        Dashboard dashboard = own(id, currentUser.from(jwt));
        dashboard.setName(request.name().trim());
        dashboard.setWidgets(widgets(request.widgets()));
        return response(dashboard);
    }

    @DeleteMapping("/api/dashboards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        dashboards.delete(own(id, currentUser.from(jwt)));
    }

    @PostMapping("/api/recent/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void viewed(@AuthenticationPrincipal Jwt jwt, @PathVariable Long taskId) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(taskId, user);
        recent.findByUserIdAndTaskId(user.getId(), task.getId())
                .ifPresentOrElse(RecentView::touch, () -> recent.save(new RecentView(user.getId(), task.getId())));
        List<RecentView> all = recent.findByUserIdOrderByViewedAtDesc(user.getId(), PageRequest.of(0, 200));
        if (all.size() > RECENT_LIMIT) {
            recent.deleteAll(all.subList(RECENT_LIMIT, all.size()));
        }
    }

    /** Tasks the caller opened lately that are still visible to them. */
    @GetMapping("/api/recent")
    @Transactional(readOnly = true)
    public List<RecentTask> recent(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        List<RecentView> views = recent.findByUserIdOrderByViewedAtDesc(user.getId(), PageRequest.of(0, RECENT_LIMIT));
        Map<Long, Task> byId = new HashMap<>();
        tasks.findAllById(views.stream().map(RecentView::getTaskId).toList()).forEach(t -> byId.put(t.getId(), t));
        List<RecentTask> out = new ArrayList<>();
        for (RecentView view : views) {
            Task task = byId.get(view.getTaskId());
            if (task != null && task.getProject().hasMember(user)) {
                out.add(new RecentTask(task.getId(), task.getKey(), task.getTitle(), task.getStatus(), task.getType(),
                        task.getProject().getKey(), view.getViewedAt()));
            }
        }
        return out;
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        recent.deleteForTask(event.taskId());
    }

    @EventListener
    public void onUserDeleting(UserDeleting event) {
        recent.deleteForUser(event.userId());
        dashboards.deleteAll(dashboards.findByOwnerIdOrderByIdAsc(event.userId()));
    }

    private Dashboard own(Long id, User user) {
        return dashboards.findById(id).filter(d -> d.getOwner().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("Dashboard not found."));
    }

    /** Checks the widget list's shape and returns it as compact JSON. */
    private String widgets(JsonNode widgets) {
        if (!widgets.isArray()) {
            throw ApiException.badRequest("Widgets must be a list.");
        }
        if (widgets.size() > MAX_WIDGETS) {
            throw ApiException.badRequest("A dashboard can have at most " + MAX_WIDGETS + " widgets.");
        }
        for (JsonNode widget : widgets) {
            if (!widget.isObject() || !WIDGET_TYPES.contains(widget.path("type").asText())) {
                throw ApiException.badRequest("Unknown widget type: " + widget.path("type").asText("?") + ".");
            }
        }
        String text = widgets.toString();
        if (text.length() > 20000) {
            throw ApiException.badRequest("This dashboard is too large.");
        }
        return text;
    }

    private String compact(String text) {
        try {
            return json.readTree(text).toString();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private DashboardResponse response(Dashboard dashboard) {
        try {
            return new DashboardResponse(dashboard.getId(), dashboard.getName(), json.readTree(dashboard.getWidgets()));
        } catch (JsonProcessingException e) {
            return new DashboardResponse(dashboard.getId(), dashboard.getName(), json.createArrayNode());
        }
    }
}
