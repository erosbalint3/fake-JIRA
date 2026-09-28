package com.fakejira.task;

import com.fakejira.sprint.Sprint;
import com.fakejira.sprint.SprintState;
import com.fakejira.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
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
            List<@Pattern(regexp = "^[^,]{1,30}$", message = "Labels are 1-30 characters without commas") String> labels,

            Long assigneeId,

            Long sprintId) {
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
            List<@Pattern(regexp = "^[^,]{1,30}$", message = "Labels are 1-30 characters without commas") String> labels) {
    }

    public record StatusRequest(@NotNull(message = "Status is required") TaskStatus status) {
    }

    /** {@code assigneeId} null means unassign. */
    public record AssigneeRequest(Long assigneeId) {
    }

    /** {@code sprintId} null means move to the backlog. */
    public record SprintRequest(Long sprintId) {
    }

    public record CommentRequest(
            @NotBlank(message = "Comment cannot be empty")
            @Size(max = 2000, message = "Comment must be at most 2000 characters")
            String body) {
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

    public record SprintRef(Long id, String name, SprintState state) {
        static SprintRef of(Sprint sprint) {
            return sprint == null ? null : new SprintRef(sprint.getId(), sprint.getName(), sprint.getState());
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
            UserSummary reporter,
            UserSummary assignee,
            SprintRef sprint,
            LocalDate dueDate,
            List<String> labels,
            int checklistTotal,
            int checklistDone,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static TaskResponse of(Task task, int checklistTotal, int checklistDone) {
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
                    UserSummary.of(task.getReporter()),
                    UserSummary.of(task.getAssignee()),
                    SprintRef.of(task.getSprint()),
                    task.getDueDate(),
                    List.copyOf(task.getLabels()),
                    checklistTotal,
                    checklistDone,
                    task.getCreatedAt(),
                    task.getUpdatedAt(),
                    task.getCompletedAt());
        }
    }

    public record CommentResponse(Long id, UserSummary author, String body, Instant createdAt) {

        public static CommentResponse of(Comment comment) {
            return new CommentResponse(
                    comment.getId(), UserSummary.of(comment.getAuthor()), comment.getBody(), comment.getCreatedAt());
        }
    }

    public record ChecklistItemResponse(Long id, String text, boolean done) {
        public static ChecklistItemResponse of(ChecklistItem item) {
            return new ChecklistItemResponse(item.getId(), item.getText(), item.isDone());
        }
    }

    public record ActivityResponse(Long id, UserSummary actor, String message, Instant createdAt) {
        public static ActivityResponse of(TaskActivity activity) {
            return new ActivityResponse(
                    activity.getId(), UserSummary.of(activity.getActor()), activity.getMessage(), activity.getCreatedAt());
        }
    }

    public record AttachmentResponse(Long id, String filename, String contentType, long size,
                                     UserSummary uploader, Instant createdAt) {
        public static AttachmentResponse of(Attachment attachment) {
            return new AttachmentResponse(attachment.getId(), attachment.getFilename(), attachment.getContentType(),
                    attachment.getSize(), UserSummary.of(attachment.getUploader()), attachment.getCreatedAt());
        }
    }
}
