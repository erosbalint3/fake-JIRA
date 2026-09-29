package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SavedFilterController {

    private final SavedFilterRepository filters;
    private final ProjectAccess access;
    private final CurrentUser currentUser;

    public SavedFilterController(SavedFilterRepository filters, ProjectAccess access, CurrentUser currentUser) {
        this.filters = filters;
        this.access = access;
        this.currentUser = currentUser;
    }

    public record FilterRequest(
            @NotBlank(message = "Name is required") @Size(max = 60, message = "Name must be at most 60 characters") String name,
            @NotBlank(message = "The filter is empty") @Size(max = 500) String query,
            boolean shared) {
    }

    public record FilterResponse(Long id, String name, String query, boolean shared, String owner, boolean mine) {
    }

    @GetMapping("/api/projects/{key}/filters")
    @Transactional(readOnly = true)
    public List<FilterResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        return filters.visibleIn(project.getId(), user.getId()).stream().map(f -> response(f, user)).toList();
    }

    @PostMapping("/api/projects/{key}/filters")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public FilterResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                 @Valid @RequestBody FilterRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        String query = request.query().trim().replaceFirst("^\\?", "");
        return response(filters.save(new SavedFilter(user, project, request.name().trim(), query, request.shared())), user);
    }

    @DeleteMapping("/api/filters/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        SavedFilter filter = filters.findById(id)
                .filter(f -> f.getOwner().getId().equals(user.getId()) || f.getProject().isOwner(user))
                .orElseThrow(() -> ApiException.notFound("Filter not found."));
        filters.delete(filter);
    }

    private static FilterResponse response(SavedFilter filter, User user) {
        return new FilterResponse(filter.getId(), filter.getName(), filter.getQuery(), filter.isShared(),
                filter.getOwner().getUsername(), filter.getOwner().getId().equals(user.getId()));
    }
}
