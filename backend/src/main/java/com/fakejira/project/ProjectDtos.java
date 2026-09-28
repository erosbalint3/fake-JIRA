package com.fakejira.project;

import com.fakejira.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank(message = "Key is required")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{1,9}$",
                    message = "2-10 letters or digits, starting with a letter")
            String key,

            @NotBlank(message = "Name is required")
            @Size(max = 80, message = "Name must be at most 80 characters")
            String name,

            @Size(max = 1000, message = "Description must be at most 1000 characters")
            String description) {
    }

    public record UpdateProjectRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 80, message = "Name must be at most 80 characters")
            String name,

            @Size(max = 1000, message = "Description must be at most 1000 characters")
            String description) {
    }

    public record AddMemberRequest(@NotBlank(message = "Enter a username or email") String login) {
    }

    public record ProjectResponse(
            Long id,
            String key,
            String name,
            String description,
            UserSummary owner,
            List<UserSummary> members,
            Instant createdAt) {

        public static ProjectResponse of(Project project) {
            return new ProjectResponse(
                    project.getId(),
                    project.getKey(),
                    project.getName(),
                    project.getDescription(),
                    UserSummary.of(project.getOwner()),
                    project.getMembers().stream()
                            .map(UserSummary::of)
                            .sorted(Comparator.comparing(UserSummary::username, String.CASE_INSENSITIVE_ORDER))
                            .toList(),
                    project.getCreatedAt());
        }
    }
}
