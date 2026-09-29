package com.fakejira.team;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.user.AccountService.UserDeleting;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Teams (groups) of people. Anyone can create one; its members, its creator and admins can change it.
 * {@code @handle} in a comment mentions every member who is in the task's project.
 */
@RestController
public class TeamController {

    private final TeamRepository teams;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final AuditLog audit;

    public TeamController(TeamRepository teams, UserRepository users, CurrentUser currentUser, AuditLog audit) {
        this.teams = teams;
        this.users = users;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record TeamRequest(
            @NotBlank(message = "Name is required") @Size(max = 60, message = "Name must be at most 60 characters") String name,
            @NotBlank(message = "Handle is required")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,39}$", message = "Handles are 2-40 letters, digits, dots, dashes or underscores")
            String handle,
            @Size(max = 300, message = "Description must be at most 300 characters") String description) {
    }

    public record MemberRequest(@NotBlank(message = "Pick someone") String login) {
    }

    public record TeamResponse(Long id, String name, String handle, String description, List<UserSummary> members,
                               boolean member, boolean canEdit) {
    }

    @GetMapping("/api/teams")
    @Transactional(readOnly = true)
    public List<TeamResponse> list(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return teams.findAllByOrderByNameAsc().stream().map(t -> response(t, user)).toList();
    }

    @PostMapping("/api/teams")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TeamResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TeamRequest request) {
        User user = currentUser.from(jwt);
        String handle = handle(request.handle(), null);
        Team team = new Team(request.name().trim(), handle, trim(request.description()), user);
        team.getMembers().add(user);
        teams.save(team);
        audit.record(user, "team.create", "@" + handle, team.getName());
        return response(team, user);
    }

    @PutMapping("/api/teams/{id}")
    @Transactional
    public TeamResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody TeamRequest request) {
        User user = currentUser.from(jwt);
        Team team = editable(id, user);
        team.setName(request.name().trim());
        team.setHandle(handle(request.handle(), team));
        team.setDescription(trim(request.description()));
        return response(team, user);
    }

    @DeleteMapping("/api/teams/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Team team = editable(id, user);
        audit.record(user, "team.delete", "@" + team.getHandle(), team.getName());
        team.getMembers().clear();
        teams.delete(team);
    }

    @PostMapping("/api/teams/{id}/members")
    @Transactional
    public TeamResponse addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @Valid @RequestBody MemberRequest request) {
        User user = currentUser.from(jwt);
        Team team = editable(id, user);
        String login = request.login().trim();
        User member = (login.contains("@") ? users.findByEmailIgnoreCase(login) : users.findByUsernameIgnoreCase(login))
                .filter(u -> u.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("No user called " + login + "."));
        if (team.hasMember(member)) {
            throw ApiException.conflict(member.getUsername() + " is already in @" + team.getHandle() + ".");
        }
        team.getMembers().add(member);
        return response(team, user);
    }

    /** Members can leave; editors can remove anyone. */
    @DeleteMapping("/api/teams/{id}/members/{userId}")
    @Transactional
    public TeamResponse removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long userId) {
        User user = currentUser.from(jwt);
        Team team = teams.findById(id).orElseThrow(() -> ApiException.notFound("Team not found."));
        if (!user.getId().equals(userId)) {
            requireEditor(team, user);
        }
        if (!team.getMembers().removeIf(m -> m.getId().equals(userId))) {
            throw ApiException.notFound("That person is not in the team.");
        }
        return response(team, user);
    }

    @EventListener
    public void onUserDeleting(UserDeleting event) {
        teams.findForMember(event.userId()).forEach(t -> t.getMembers().removeIf(m -> m.getId().equals(event.userId())));
    }

    private String handle(String requested, Team current) {
        String handle = requested.trim().toLowerCase(Locale.ROOT);
        boolean taken = teams.findByHandleIgnoreCase(handle).filter(t -> current == null || !t.getId().equals(current.getId()))
                .isPresent();
        if (taken || users.findByUsernameIgnoreCase(handle).isPresent()) {
            throw ApiException.field("handle", "@" + handle + " is already taken by a " + (taken ? "team" : "person") + ".");
        }
        return handle;
    }

    private Team editable(Long id, User user) {
        Team team = teams.findById(id).orElseThrow(() -> ApiException.notFound("Team not found."));
        requireEditor(team, user);
        return team;
    }

    private static void requireEditor(Team team, User user) {
        if (!canEdit(team, user)) {
            throw ApiException.forbidden("Only team members can change @" + team.getHandle() + ".");
        }
    }

    private static boolean canEdit(Team team, User user) {
        return user.isAdmin() || team.hasMember(user)
                || team.getCreatedBy() != null && team.getCreatedBy().getId().equals(user.getId());
    }

    private static TeamResponse response(Team team, User user) {
        return new TeamResponse(team.getId(), team.getName(), team.getHandle(), team.getDescription(),
                team.getMembers().stream().sorted(Comparator.comparing(User::getUsername)).map(UserSummary::of).toList(),
                team.hasMember(user), canEdit(team, user));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
