package com.fakejira.sprint;

import com.fakejira.common.CurrentUser;
import com.fakejira.sprint.SprintDtos.Burndown;
import com.fakejira.sprint.SprintDtos.SprintRequest;
import com.fakejira.sprint.SprintDtos.SprintResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SprintController {

    private final SprintService sprintService;
    private final CurrentUser currentUser;

    public SprintController(SprintService sprintService, CurrentUser currentUser) {
        this.sprintService = sprintService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/projects/{key}/sprints")
    public List<SprintResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return sprintService.list(currentUser.from(jwt), key);
    }

    @PostMapping("/api/projects/{key}/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    public SprintResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                 @Valid @RequestBody SprintRequest request) {
        return sprintService.create(currentUser.from(jwt), key, request);
    }

    @PutMapping("/api/sprints/{id}")
    public SprintResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                 @Valid @RequestBody SprintRequest request) {
        return sprintService.update(currentUser.from(jwt), id, request);
    }

    @PostMapping("/api/sprints/{id}/start")
    public SprintResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                @Valid @RequestBody(required = false) SprintRequest request) {
        return sprintService.start(currentUser.from(jwt), id, request);
    }

    @PostMapping("/api/sprints/{id}/complete")
    public SprintResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return sprintService.complete(currentUser.from(jwt), id);
    }

    @DeleteMapping("/api/sprints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        sprintService.delete(currentUser.from(jwt), id);
    }

    @GetMapping("/api/sprints/{id}/burndown")
    public Burndown burndown(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return sprintService.burndown(currentUser.from(jwt), id);
    }
}
