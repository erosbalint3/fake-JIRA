package com.fakejira.workflow;

import com.fakejira.common.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowController {

    private final WorkflowService workflow;
    private final CurrentUser currentUser;

    public WorkflowController(WorkflowService workflow, CurrentUser currentUser) {
        this.workflow = workflow;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/projects/{key}/workflow")
    public WorkflowService.WorkflowResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return workflow.get(currentUser.from(jwt), key);
    }

    @PutMapping("/api/projects/{key}/workflow")
    public WorkflowService.WorkflowResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                                 @RequestBody WorkflowService.WorkflowRequest request) {
        return workflow.save(currentUser.from(jwt), key, request);
    }
}
