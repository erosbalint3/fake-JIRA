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
            String description,

            /** blank (default), scrum, kanban, bugs or marketing; see ProjectTemplates. */
            String template) {

        public CreateProjectRequest(String key, String name, String description) {
            this(key, name, description, null);
        }
    }

    public record UpdateProjectRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 80, message = "Name must be at most 80 characters")
            String name,

            @Size(max = 1000, message = "Description must be at most 1000 characters")
            String description,

            /** Kanban projects have no sprints: a continuous board and flow reports. Unchanged when null. */
            Boolean kanban,

            /** Accent colour like #2a78d6; empty clears it. Unchanged when null. */
            @jakarta.validation.constraints.Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "Use a colour like #2a78d6")
            String color) {

        public UpdateProjectRequest(String name, String description) {
            this(name, description, null, null);
        }
    }

    public enum Role { OWNER, MEMBER, VIEWER }

    /** {@code role} defaults to MEMBER; VIEWER gives read-only access. */
    public record AddMemberRequest(@NotBlank(message = "Enter a username or email") String login, Role role) {
    }

    public record RoleRequest(Role role) {
    }

    public record OwnerRequest(@jakarta.validation.constraints.NotNull Long userId) {
    }

    public record MemberResponse(Long id, String username, String email, String displayName, String avatarUrl,
                                 Role role, java.time.LocalDate awayUntil) {
    }

    public record ProjectResponse(
            Long id,
            String key,
            String name,
            String description,
            UserSummary owner,
            List<MemberResponse> members,
            boolean githubEnabled,
            boolean githubAutoDone,
            Instant createdAt,
            boolean kanban,
            String color) {

        public static ProjectResponse of(Project project) {
            return new ProjectResponse(
                    project.getId(),
                    project.getKey(),
                    project.getName(),
                    project.getDescription(),
                    UserSummary.of(project.getOwner()),
                    project.getMembers().stream()
                            .map(member -> {
                                UserSummary summary = UserSummary.of(member);
                                Role role = project.isOwner(member) ? Role.OWNER
                                        : project.isViewer(member) ? Role.VIEWER : Role.MEMBER;
                                return new MemberResponse(summary.id(), summary.username(), summary.email(),
                                        summary.displayName(), summary.avatarUrl(), role, summary.awayUntil());
                            })
                            .sorted(Comparator.comparing(MemberResponse::username, String.CASE_INSENSITIVE_ORDER))
                            .toList(),
                    project.getGithubSecret() != null,
                    project.isGithubAutoDone(),
                    project.getCreatedAt(),
                    project.isKanban(),
                    project.getColor());
        }
    }
}
