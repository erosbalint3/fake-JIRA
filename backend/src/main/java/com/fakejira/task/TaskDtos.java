package com.fakejira.task;

import com.fakejira.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class TaskDtos {

    private TaskDtos() {
    }

    public record TaskRequest(
            @NotBlank(message = "Title is required")
            @Size(max = 120, message = "Title must be at most 120 characters")
            String title,

            @Size(max = 5000, message = "Description must be at most 5000 characters")
            String description,

            @NotNull(message = "Priority is required")
            TaskPriority priority) {
    }

    public record StatusRequest(@NotNull(message = "Status is required") TaskStatus status) {
    }

    public record CommentRequest(
            @NotBlank(message = "Comment cannot be empty")
            @Size(max = 2000, message = "Comment must be at most 2000 characters")
            String body) {
    }

    public record TaskResponse(
            Long id,
            String key,
            String title,
            String description,
            TaskPriority priority,
            TaskStatus status,
            UserSummary reporter,
            UserSummary assignee,
            Instant createdAt,
            Instant updatedAt) {

        public static TaskResponse of(Task task) {
            return new TaskResponse(
                    task.getId(),
                    task.getKey(),
                    task.getTitle(),
                    task.getDescription(),
                    task.getPriority(),
                    task.getStatus(),
                    UserSummary.of(task.getReporter()),
                    UserSummary.of(task.getAssignee()),
                    task.getCreatedAt(),
                    task.getUpdatedAt());
        }
    }

    public record CommentResponse(Long id, UserSummary author, String body, Instant createdAt) {

        public static CommentResponse of(Comment comment) {
            return new CommentResponse(
                    comment.getId(), UserSummary.of(comment.getAuthor()), comment.getBody(), comment.getCreatedAt());
        }
    }
}
