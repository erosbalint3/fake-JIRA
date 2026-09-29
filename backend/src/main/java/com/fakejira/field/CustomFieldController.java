package com.fakejira.field;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskEvent;
import com.fakejira.task.TaskEvents;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
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

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Custom fields: project editors define them, and set their values on tasks. */
@RestController
public class CustomFieldController {

    static final int MAX_FIELDS = 20;

    private final CustomFieldRepository fields;
    private final CustomFieldValueRepository values;
    private final ProjectAccess access;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final LiveEvents live;
    private final TaskEvents taskEvents;

    public CustomFieldController(CustomFieldRepository fields, CustomFieldValueRepository values, ProjectAccess access,
                                 TaskSupport taskSupport, CurrentUser currentUser, LiveEvents live, TaskEvents taskEvents) {
        this.fields = fields;
        this.values = values;
        this.access = access;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.live = live;
        this.taskEvents = taskEvents;
    }

    public record FieldRequest(
            @NotBlank(message = "Name is required") @Size(max = 40, message = "Name must be at most 40 characters") String name,
            @NotNull(message = "Pick a type") CustomField.Type type,
            @Size(max = 30, message = "At most 30 choices") List<@Size(max = 60, message = "Choices are at most 60 characters") String> options) {
    }

    public record FieldResponse(Long id, String name, CustomField.Type type, List<String> options, int position) {
        static FieldResponse of(CustomField f) {
            return new FieldResponse(f.getId(), f.getName(), f.getType(), f.getOptions(), f.getPosition());
        }
    }

    /** A field with the task's value (null when not set). */
    public record FieldValue(Long fieldId, String name, CustomField.Type type, List<String> options, String value) {
    }

    public record ValueRequest(@Size(max = 500, message = "At most 500 characters") String value) {
    }

    @GetMapping("/api/projects/{key}/fields")
    @Transactional(readOnly = true)
    public List<FieldResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return fields.findByProjectIdOrderByPositionAscIdAsc(project.getId()).stream().map(FieldResponse::of).toList();
    }

    @PostMapping("/api/projects/{key}/fields")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public FieldResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody FieldRequest request) {
        Project project = access.editorProject(key, currentUser.from(jwt));
        List<CustomField> existing = fields.findByProjectIdOrderByPositionAscIdAsc(project.getId());
        if (existing.size() >= MAX_FIELDS) {
            throw ApiException.badRequest("A project can have at most " + MAX_FIELDS + " custom fields.");
        }
        String name = request.name().trim();
        checkName(existing, name, null);
        checkOptions(request);
        CustomField field = fields.save(new CustomField(project, name, request.type(), request.options(), existing.size()));
        live.projectChanged(project);
        return FieldResponse.of(field);
    }

    /** The type cannot change (existing values would not fit); name and choices can. */
    @PutMapping("/api/fields/{id}")
    @Transactional
    public FieldResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody FieldRequest request) {
        CustomField field = editable(id, currentUser.from(jwt));
        String name = request.name().trim();
        checkName(fields.findByProjectIdOrderByPositionAscIdAsc(field.getProject().getId()), name, field.getId());
        if (request.type() != field.getType()) {
            throw ApiException.badRequest("A field's type cannot be changed. Create a new field instead.");
        }
        checkOptions(request);
        field.setName(name);
        field.setOptions(request.options());
        live.projectChanged(field.getProject());
        return FieldResponse.of(field);
    }

    @DeleteMapping("/api/fields/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        CustomField field = editable(id, currentUser.from(jwt));
        values.deleteForField(field.getId());
        live.projectChanged(field.getProject());
        fields.delete(field);
    }

    @GetMapping("/api/tasks/{id}/fields")
    @Transactional(readOnly = true)
    public List<FieldValue> taskFields(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Task task = taskSupport.memberTask(id, currentUser.from(jwt));
        Map<Long, String> current = new HashMap<>();
        values.findForTask(task.getId()).forEach(v -> current.put(v.getField().getId(), v.getValue()));
        return fields.findByProjectIdOrderByPositionAscIdAsc(task.getProject().getId()).stream()
                .map(f -> new FieldValue(f.getId(), f.getName(), f.getType(), f.getOptions(), current.get(f.getId())))
                .toList();
    }

    /** Sets (or with an empty value, clears) one field on a task. */
    @PutMapping("/api/tasks/{id}/fields/{fieldId}")
    @Transactional
    public FieldValue setValue(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long fieldId,
                               @Valid @RequestBody ValueRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        CustomField field = fields.findById(fieldId).filter(f -> f.getProject().getId().equals(task.getProject().getId()))
                .orElseThrow(() -> ApiException.notFound("That field does not belong to " + task.getProject().getKey() + "."));
        String value = normalize(field, request.value());
        var existing = values.findByTaskIdAndFieldId(task.getId(), field.getId());
        String before = existing.map(CustomFieldValue::getValue).orElse(null);
        if (java.util.Objects.equals(before, value)) {
            return new FieldValue(field.getId(), field.getName(), field.getType(), field.getOptions(), value);
        }
        if (value == null) {
            existing.ifPresent(values::delete);
            taskSupport.record(task, user, "cleared " + field.getName());
        } else {
            existing.ifPresentOrElse(v -> v.setValue(value), () -> values.save(new CustomFieldValue(task, field, value)));
            taskSupport.record(task, user, "set " + field.getName() + " to " + display(field, value));
        }
        task.touch();
        live.taskChanged(task);
        taskEvents.publish(TaskEvent.Kind.UPDATED, task, user, "changes", field.getName() + " → " + (value == null ? "empty" : value));
        return new FieldValue(field.getId(), field.getName(), field.getType(), field.getOptions(), value);
    }

    /** Validates and normalises a value for the field's type; null clears it. */
    static String normalize(CustomField field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        switch (field.getType()) {
            case NUMBER -> {
                try {
                    return new BigDecimal(value.replace(',', '.')).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw ApiException.field("value", field.getName() + " must be a number.");
                }
            }
            case DATE -> {
                try {
                    return LocalDate.parse(value).toString();
                } catch (DateTimeParseException e) {
                    throw ApiException.field("value", field.getName() + " must be a date like 2026-10-31.");
                }
            }
            case CHECKBOX -> {
                String v = value.toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("yes") || v.equals("1")) {
                    return "true";
                }
                if (v.equals("false") || v.equals("no") || v.equals("0")) {
                    return null;
                }
                throw ApiException.field("value", field.getName() + " is a yes/no field.");
            }
            case SELECT -> {
                return field.getOptions().stream().filter(o -> o.equalsIgnoreCase(value)).findFirst()
                        .orElseThrow(() -> ApiException.field("value", "Pick one of: " + String.join(", ", field.getOptions()) + "."));
            }
            case URL -> {
                try {
                    URI uri = URI.create(value);
                    if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                        throw new IllegalArgumentException();
                    }
                    return value;
                } catch (IllegalArgumentException e) {
                    throw ApiException.field("value", field.getName() + " must be an http(s) link.");
                }
            }
            default -> {
                return value;
            }
        }
    }

    private static String display(CustomField field, String value) {
        return field.getType() == CustomField.Type.CHECKBOX ? "yes" : value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }

    private static void checkName(List<CustomField> existing, String name, Long self) {
        if (existing.stream().anyMatch(f -> f.getName().equalsIgnoreCase(name) && !f.getId().equals(self))) {
            throw ApiException.field("name", "There is already a field called " + name + ".");
        }
    }

    private static void checkOptions(FieldRequest request) {
        if (request.type() == CustomField.Type.SELECT
                && (request.options() == null || request.options().stream().noneMatch(o -> o != null && !o.isBlank()))) {
            throw ApiException.field("options", "Add at least one choice.");
        }
    }

    private CustomField editable(Long id, User user) {
        CustomField field = fields.findById(id).orElseThrow(() -> ApiException.notFound("Field not found."));
        access.requireEditor(field.getProject(), user);
        return field;
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        values.deleteForTask(event.taskId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        for (CustomField f : fields.findByProjectIdOrderByPositionAscIdAsc(event.projectId())) {
            values.deleteForField(f.getId());
            fields.delete(f);
        }
    }
}
