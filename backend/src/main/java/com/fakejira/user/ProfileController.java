package com.fakejira.user;

import com.fakejira.auth.AuthDtos.ChangePasswordRequest;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final CurrentUser currentUser;
    private final TaskRepository tasks;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public ProfileController(CurrentUser currentUser, TaskRepository tasks, UserRepository users,
                             PasswordEncoder passwordEncoder) {
        this.currentUser = currentUser;
        this.tasks = tasks;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public record Stats(long assigned, long inProgress, long done, long reported) {
    }

    public record ProfileResponse(UserSummary user, Instant memberSince, Stats stats) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        Long id = user.getId();
        Stats stats = new Stats(
                tasks.countByAssigneeId(id),
                tasks.countByAssigneeIdAndStatus(id, TaskStatus.IN_PROGRESS),
                tasks.countByAssigneeIdAndStatus(id, TaskStatus.DONE),
                tasks.countByReporterId(id));
        return new ProfileResponse(UserSummary.of(user), user.getCreatedAt(), stats);
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        User user = currentUser.from(jwt);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("Your current password is incorrect.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.save(user);
    }
}
