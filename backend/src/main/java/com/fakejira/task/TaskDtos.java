package com.fakejira.task;

import com.fakejira.epic.Epic;
import com.fakejira.integration.DevLink;
import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintState;
import com.fakejira.user.UserSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TaskDtos {

    private TaskDtos() {
    }

    public record CreateTaskRequest(
            @NotBlank(message = "Choose a project") String projectKey,

            @NotBlank(message = "Title is required")
            @Size(max = 120, message = "Title must be at most 120 characters")
            String title,

            @Size(max = 5000, message = "Description must be at most 5000 characters")
            String description,

            @NotNull(message = "Priority is required")
            TaskPriority priority,

            LocalDate dueDate,

            @Size(max = 10, message = "At most 10 labels")
            List<@Pattern(regexp = "^[^,;|]{1,30}$", message = "Labels are 1-30 characters without , ; or |") String> labels,

            Long assigneeId,

            Long sprintId,

            @Min(value = 0, message = "Story points cannot be negative")
            @Max(value = 100, message = "At most 100 story points")
            Integer storyPoints,

            Long epicId,

            /** Makes the new task a subtask of this task (same project). */
            Long parentId,

            /** Defaults to TASK. */
            TaskType type,

            /** Checklist items to start with (e.g. from a template). */
            @Size(max = 50, message = "At most 50 checklist items")
            List<@Size(max = 200, message = "Checklist items are at most 200 characters") String> checklist) {

        /** A plain task with defaults, for tasks created by the app itself (templates, recurring, email…). */
        public static CreateTaskRequest of(String projectKey, String title, String description, TaskPriority priority,
                                           TaskType type) {
            return new CreateTaskRequest(projectKey, title, description, priority == null ? TaskPriority.MEDIUM : priority,
                    null, List.of(), null, null, null, null, null, type, List.of());
        }

        public CreateTaskRequest withLabels(List<String> labels) {
            return new CreateTaskRequest(projectKey, title, description, priority, dueDate, labels, assigneeId, sprintId,
                    storyPoints, epicId, parentId, type, checklist);
        }

        public CreateTaskRequest withAssignee(Long id) {
            return new CreateTaskRequest(projectKey, title, description, priority, dueDate, labels, id, sprintId,
                    storyPoints, epicId, parentId, type, checklist);
        }

        public CreateTaskRequest withParent(Long id) {
            return new CreateTaskRequest(projectKey, title, description, priority, dueDate, labels, assigneeId, sprintId,
                    storyPoints, epicId, id, type, checklist);
        }

        public CreateTaskRequest withDetails(LocalDate due, Integer points, Long epic, Long sprint, List<String> items) {
            return new CreateTaskRequest(projectKey, title, description, priority, due, labels, assigneeId, sprint,
                    points, epic, parentId, type, items);
        }
    }

    public record UpdateTaskRequest(
            @NotBlank(message = "Title is required")
            @Size(max = 120, message = "Title must be at most 120 characters")
            String title,

            @Size(max = 5000, message = "Description must be at most 5000 characters")
            String description,

            @NotNull(message = "Priority is required")
            TaskPriority priority,

            LocalDate dueDate,

            @Size(max = 10, message = "At most 10 labels")
            List<@Pattern(regexp = "^[^,;|]{1,30}$", message = "Labels are 1-30 characters without , ; or |") String> labels,

            @Min(value = 0, message = "Story points cannot be negative")
            @Max(value = 100, message = "At most 100 story points")
            Integer storyPoints,

            Long epicId,

            /** Unchanged when null. */
            TaskType type) {
    }

    public record StatusRequest(@NotNull(message = "Status is required") TaskStatus status) {
    }

    /** {@code assigneeId} null means unassign. */
    public record AssigneeRequest(Long assigneeId) {
    }

    /** {@code sprintId} null means move to the backlog. */
    public record SprintRequest(Long sprintId) {
    }

    public record ColumnRequest(@NotNull(message = "Column is required") Long columnId) {
    }

    /**
     * Applies the same change to many tasks. Only non-null fields are applied; the
     * {@code clear*} / {@code unassign} flags remove a value.
     */
    public record BulkRequest(
            @NotEmpty(message = "Select at least one task") @Size(max = 200) List<Long> taskIds,
            TaskStatus status,
            TaskPriority priority,
            Long assigneeId,
            boolean unassign,
            Long sprintId,
            boolean clearSprint,
            Long epicId,
            boolean clearEpic,
            TaskType type,
            List<String> addLabels,
            List<String> removeLabels,
            boolean delete) {
    }

    public record CommentRequest(
            @NotBlank(message = "Comment cannot be empty")
            @Size(max = 2000, message = "Comment must be at most 2000 characters")
            String body,

            /** Replies to this comment (replies are one level deep). */
            Long parentId) {
    }

    public record ReactionRequest(@NotBlank String emoji) {
    }

    /** One emoji on a comment: how many, whether the viewer is one of them, and who. */
    public record ReactionSummary(String emoji, int count, boolean mine, List<String> users) {
    }

    public record ChecklistItemRequest(
            @NotBlank(message = "Item cannot be empty")
            @Size(max = 200, message = "Item must be at most 200 characters")
            String text) {
    }

    public record ChecklistUpdateRequest(
            @Size(min = 1, max = 200, message = "Item must be 1-200 characters") String text,
            Boolean done) {
    }

    /** Link this task to another by key ("WR-4") or id. */
    public record LinkRequest(@NotNull(message = "Choose a link type") LinkType type, String targetKey, Long targetId) {
    }

    public record TimeRequest(
            @NotNull(message = "Enter the time spent")
            @Min(value = 1, message = "Log at least one minute")
            @Max(value = 1440, message = "At most 24 hours per entry")
            Integer minutes,
            LocalDate date,
            @Size(max = 200, message = "Note must be at most 200 characters") String note) {
    }

    public record SprintRef(Long id, String name, SprintState state) {
        static SprintRef of(Sprint sprint) {
            return sprint == null ? null : new SprintRef(sprint.getId(), sprint.getName(), sprint.getState());
        }
    }

    public record EpicRef(Long id, String name, int colorIndex) {
        public static EpicRef of(Epic epic) {
            return epic == null ? null : new EpicRef(epic.getId(), epic.getName(), epic.getColorIndex());
        }
    }

    public record TaskRef(Long id, String key, String title, TaskStatus status, UserSummary assignee) {
        public static TaskRef of(Task task) {
            return task == null ? null
                    : new TaskRef(task.getId(), task.getKey(), task.getTitle(), task.getStatus(),
                    UserSummary.of(task.getAssignee()));
        }
    }

    public record TaskResponse(
            Long id,
            String key,
            Long projectId,
            String projectKey,
            String projectName,
            String title,
            String description,
            TaskPriority priority,
            TaskStatus status,
            TaskType type,
            UserSummary reporter,
            UserSummary assignee,
            SprintRef sprint,
            EpicRef epic,
            TaskRef parent,
            Long columnId,
            LocalDate dueDate,
            List<String> labels,
            Integer storyPoints,
            int checklistTotal,
            int checklistDone,
            int subtaskTotal,
            int subtaskDone,
            int timeSpentMinutes,
            boolean blocked,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static TaskResponse of(Task task, int checklistTotal, int checklistDone, int subtaskTotal,
                                      int subtaskDone, int timeSpentMinutes, boolean blocked) {
            return new TaskResponse(
                    task.getId(),
                    task.getKey(),
                    task.getProject().getId(),
                    task.getProject().getKey(),
                    task.getProject().getName(),
                    task.getTitle(),
                    task.getDescription(),
                    task.getPriority(),
                    task.getStatus(),
                    task.getType(),
                    UserSummary.of(task.getReporter()),
                    UserSummary.of(task.getAssignee()),
                    SprintRef.of(task.getSprint()),
                    EpicRef.of(task.getEpic()),
                    TaskRef.of(task.getParent()),
                    task.getBoardColumn() == null ? null : task.getBoardColumn().getId(),
                    task.getDueDate(),
                    List.copyOf(task.getLabels()),
                    task.getStoryPoints(),
                    checklistTotal,
                    checklistDone,
                    subtaskTotal,
                    subtaskDone,
                    timeSpentMinutes,
                    blocked,
                    task.getCreatedAt(),
                    task.getUpdatedAt(),
                    task.getCompletedAt());
        }
    }

    public record CommentResponse(Long id, UserSummary author, String body, Instant createdAt, Instant editedAt,
                                  Long parentId, List<ReactionSummary> reactions) {

        public static CommentResponse of(Comment comment) {
            return of(comment, List.of());
        }

        public static CommentResponse of(Comment comment, List<ReactionSummary> reactions) {
            return new CommentResponse(comment.getId(), UserSummary.of(comment.getAuthor()), comment.getBody(),
                    comment.getCreatedAt(), comment.getEditedAt(),
                    comment.getParent() == null ? null : comment.getParent().getId(), reactions);
        }
    }

    public record ChecklistItemResponse(Long id, String text, boolean done) {
        public static ChecklistItemResponse of(ChecklistItem item) {
            return new ChecklistItemResponse(item.getId(), item.getText(), item.isDone());
        }
    }

    /** {@code before}/{@code after}: the text around a description change, else null. */
    public record ActivityResponse(Long id, UserSummary actor, String message, Instant createdAt, String before,
                                   String after) {
        public static ActivityResponse of(TaskActivity activity) {
            return new ActivityResponse(activity.getId(), UserSummary.of(activity.getActor()), activity.getMessage(),
                    activity.getCreatedAt(), activity.getBeforeText(), activity.getAfterText());
        }
    }

    public record AttachmentResponse(Long id, String filename, String contentType, long size,
                                     UserSummary uploader, Instant createdAt) {
        public static AttachmentResponse of(Attachment attachment) {
            return new AttachmentResponse(attachment.getId(), attachment.getFilename(), attachment.getContentType(),
                    attachment.getSize(), UserSummary.of(attachment.getUploader()), attachment.getCreatedAt());
        }
    }

    /** A link as seen from one task: {@code label} reads "blocks" or "is blocked by", etc. */
    public record LinkResponse(Long id, LinkType type, String label, TaskRef task) {
    }

    public record TimeEntryResponse(Long id, UserSummary user, int minutes, LocalDate date, String note, Instant createdAt) {
        public static TimeEntryResponse of(TimeEntry entry) {
            return new TimeEntryResponse(entry.getId(), UserSummary.of(entry.getUser()), entry.getMinutes(),
                    entry.getWorkDate(), entry.getNote(), entry.getCreatedAt());
        }
    }

    public record DevLinkResponse(Long id, DevLink.Kind kind, String url, String title, String state, String author,
                                  Instant updatedAt) {
        public static DevLinkResponse of(DevLink link) {
            return new DevLinkResponse(link.getId(), link.getKind(), link.getUrl(), link.getTitle(), link.getState(),
                    link.getAuthor(), link.getUpdatedAt());
        }
    }

    public record WatchersResponse(boolean watching, List<UserSummary> watchers) {
    }
}
