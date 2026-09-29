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

    public enum Role { OWNER, MEMBER, VIEWER }

    /** {@code role} defaults to MEMBER; VIEWER gives read-only access. */
    public record AddMemberRequest(@NotBlank(message = "Enter a username or email") String login, Role role) {
    }

    public record RoleRequest(Role role) {
    }

    public record MemberResponse(Long id, String username, String email, String displayName, String avatarUrl,
                                 Role role) {
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
            Instant createdAt) {

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
                                        summary.displayName(), summary.avatarUrl(), role);
                            })
                            .sorted(Comparator.comparing(MemberResponse::username, String.CASE_INSENSITIVE_ORDER))
                            .toList(),
                    project.getGithubSecret() != null,
                    project.isGithubAutoDone(),
                    project.getCreatedAt());
        }
    }
}
