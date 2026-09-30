package com.fakejira.meeting;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.MemberRemoved;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintDeleting;
import com.fakejira.sprint.SprintRepository;
import com.fakejira.sprint.SprintState;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskDtos.CreateTaskRequest;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskService;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Meeting notes per project (optionally per sprint) with action items that turn into tasks. */
@RestController
@Transactional
public class MeetingController {

    /** "- [ ] Update the docs @anna" lines in the notes. */
    private static final Pattern ACTION_LINE = Pattern.compile("^\\s*[-*]\\s+\\[ ]\\s+(.+?)(?:\\s+@([A-Za-z0-9_.-]+))?\\s*$");

    private final MeetingNoteRepository notes;
    private final ActionItemRepository actions;
    private final SprintRepository sprints;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final ProjectAccess access;
    private final CurrentUser currentUser;
    private final LiveEvents live;
    private final JdbcTemplate jdbc;

    public MeetingController(MeetingNoteRepository notes, ActionItemRepository actions, SprintRepository sprints,
                             TaskRepository tasks, TaskService taskService, ProjectAccess access, CurrentUser currentUser,
                             LiveEvents live, JdbcTemplate jdbc) {
        this.notes = notes;
        this.actions = actions;
        this.sprints = sprints;
        this.tasks = tasks;
        this.taskService = taskService;
        this.access = access;
        this.currentUser = currentUser;
        this.live = live;
        this.jdbc = jdbc;
    }

    public record NoteRequest(@NotNull MeetingNote.Kind kind,
                              @NotBlank(message = "Give the notes a title") @Size(max = 120) String title,
                              LocalDate date,
                              @Size(max = 20000, message = "Notes are at most 20,000 characters") String body,
                              Long sprintId) {
    }

    public record ActionRequest(@NotBlank(message = "Describe the action") @Size(max = 200) String text, Long assigneeId) {
    }

    public record ActionResponse(Long id, String text, UserSummary assignee, TaskRef task) {
        static ActionResponse of(ActionItem a) {
            return new ActionResponse(a.getId(), a.getText(), UserSummary.of(a.getAssignee()), TaskRef.of(a.getTask()));
        }
    }

    public record NoteResponse(Long id, MeetingNote.Kind kind, String title, LocalDate date, String body, Long sprintId,
                               String sprintName, UserSummary createdBy, Instant updatedAt, List<ActionResponse> actions) {
    }

    @GetMapping("/api/projects/{key}/meetings")
    @Transactional(readOnly = true)
    public List<NoteResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                   @RequestParam(required = false) Long sprint) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        List<MeetingNote> list = sprint == null ? notes.findByProjectIdOrderByMeetingDateDescIdDesc(project.getId())
                : notes.findBySprintIdOrderByMeetingDateDescIdDesc(sprint).stream()
                        .filter(n -> n.getProject().getId().equals(project.getId())).toList();
        return list.stream().map(this::response).toList();
    }

    @PostMapping("/api/projects/{key}/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody NoteRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        MeetingNote note = new MeetingNote(project, request.kind(), request.title().trim(),
                request.date() == null ? LocalDate.now() : request.date(), user);
        note.setBody(request.body());
        note.setSprint(sprint(project, request.sprintId()));
        notes.save(note);
        live.projectChanged(project);
        return response(note);
    }

    @GetMapping("/api/meetings/{id}")
    @Transactional(readOnly = true)
    public NoteResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return response(visible(id, currentUser.from(jwt)));
    }

    @PutMapping("/api/meetings/{id}")
    public NoteResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody NoteRequest request) {
        MeetingNote note = editable(id, currentUser.from(jwt));
        note.setKind(request.kind());
        note.setTitle(request.title().trim());
        if (request.date() != null) {
            note.setMeetingDate(request.date());
        }
        note.setBody(request.body());
        note.setSprint(sprint(note.getProject(), request.sprintId()));
        note.touch();
        live.projectChanged(note.getProject());
        return response(note);
    }

    @DeleteMapping("/api/meetings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        MeetingNote note = editable(id, currentUser.from(jwt));
        actions.deleteForNote(note.getId());
        notes.delete(note);
        live.projectChanged(note.getProject());
    }

    @PostMapping("/api/meetings/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse addAction(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody ActionRequest request) {
        MeetingNote note = editable(id, currentUser.from(jwt));
        if (actions.findByNoteIdOrderByIdAsc(note.getId()).size() >= 50) {
            throw ApiException.badRequest("A meeting can have at most 50 action items.");
        }
        actions.save(new ActionItem(note, request.text().trim(), assignee(note.getProject(), request.assigneeId())));
        note.touch();
        live.projectChanged(note.getProject());
        return response(note);
    }

    /** Picks up "- [ ] text @username" lines from the notes that are not action items yet. */
    @PostMapping("/api/meetings/{id}/actions/from-notes")
    public NoteResponse actionsFromNotes(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        MeetingNote note = editable(id, currentUser.from(jwt));
        List<ActionItem> existing = actions.findByNoteIdOrderByIdAsc(note.getId());
        for (String line : note.getBody().split("\n")) {
            Matcher m = ACTION_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String text = m.group(1).trim();
            if (text.length() > 200) {
                text = text.substring(0, 200);
            }
            String finalText = text;
            if (existing.stream().anyMatch(a -> a.getText().equalsIgnoreCase(finalText)) || existing.size() >= 50) {
                continue;
            }
            User who = m.group(2) == null ? null : note.getProject().getMembers().stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(m.group(2)) && !note.getProject().isViewer(u))
                    .findFirst().orElse(null);
            existing = new java.util.ArrayList<>(existing);
            existing.add(actions.save(new ActionItem(note, finalText, who)));
        }
        note.touch();
        live.projectChanged(note.getProject());
        return response(note);
    }

    @DeleteMapping("/api/meeting-actions/{id}")
    public NoteResponse deleteAction(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        ActionItem item = actions.findById(id).orElseThrow(() -> ApiException.notFound("Action item not found."));
        MeetingNote note = editable(item.getNote().getId(), currentUser.from(jwt));
        actions.delete(item);
        actions.flush();
        return response(note);
    }

    /** Creates tasks for the note's action items that do not have one yet (or just {@code only}). */
    @PostMapping("/api/meetings/{id}/tasks")
    public NoteResponse createTasks(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @RequestParam(required = false) Long only) {
        User user = currentUser.from(jwt);
        MeetingNote note = editable(id, user);
        Sprint sprint = note.getSprint() != null && note.getSprint().getState() != SprintState.COMPLETED ? note.getSprint() : null;
        for (ActionItem item : actions.findByNoteIdOrderByIdAsc(note.getId())) {
            if (item.getTask() != null || (only != null && !only.equals(item.getId()))) {
                continue;
            }
            CreateTaskRequest request = CreateTaskRequest.of(note.getProject().getKey(), item.getText(),
                    "From meeting notes: " + note.getTitle() + " (" + note.getMeetingDate() + ")", TaskPriority.MEDIUM, TaskType.TASK)
                    .withAssignee(item.getAssignee() == null ? null : item.getAssignee().getId())
                    .withDetails(null, null, null, sprint == null ? null : sprint.getId(), List.of());
            Long taskId = taskService.create(user, request).id();
            item.setTask(tasks.getReferenceById(taskId));
        }
        note.touch();
        return response(note);
    }

    private NoteResponse response(MeetingNote note) {
        List<ActionResponse> list = actions.findByNoteIdOrderByIdAsc(note.getId()).stream().map(ActionResponse::of).toList();
        return new NoteResponse(note.getId(), note.getKind(), note.getTitle(), note.getMeetingDate(), note.getBody(),
                note.getSprint() == null ? null : note.getSprint().getId(),
                note.getSprint() == null ? null : note.getSprint().getName(), UserSummary.of(note.getCreatedBy()),
                note.getUpdatedAt(), list);
    }

    private Sprint sprint(Project project, Long sprintId) {
        if (sprintId == null) {
            return null;
        }
        return sprints.findById(sprintId).filter(s -> s.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> ApiException.badRequest("That sprint is not in " + project.getKey() + "."));
    }

    private static User assignee(Project project, Long userId) {
        if (userId == null) {
            return null;
        }
        User user = project.getMembers().stream().filter(m -> m.getId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Assign action items to members of " + project.getKey() + "."));
        if (project.isViewer(user)) {
            throw ApiException.badRequest(user.getUsername() + " has read-only access and cannot own action items.");
        }
        return user;
    }

    private MeetingNote visible(Long id, User user) {
        MeetingNote note = notes.findById(id).orElseThrow(() -> ApiException.notFound("Meeting notes not found."));
        if (!note.getProject().hasMember(user)) {
            throw ApiException.notFound("Meeting notes not found.");
        }
        return note;
    }

    private MeetingNote editable(Long id, User user) {
        MeetingNote note = visible(id, user);
        access.requireEditor(note.getProject(), user);
        return note;
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        actions.detachTask(event.taskId());
    }

    @EventListener
    public void onSprintDeleting(SprintDeleting event) {
        jdbc.update("update meeting_notes set sprint_id = null where sprint_id = ?", event.sprintId());
    }

    @EventListener
    public void onMemberRemoved(MemberRemoved event) {
        jdbc.update("update meeting_actions set assignee_id = null where assignee_id = ? and note_id in "
                + "(select id from meeting_notes where project_id = ?)", event.userId(), event.projectId());
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        jdbc.update("delete from meeting_actions where note_id in (select id from meeting_notes where project_id = ?)",
                event.projectId());
        jdbc.update("delete from meeting_notes where project_id = ?", event.projectId());
    }
}
