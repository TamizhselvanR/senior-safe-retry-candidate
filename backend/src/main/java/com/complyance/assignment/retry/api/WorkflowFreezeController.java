package com.complyance.assignment.retry.api;

import com.complyance.assignment.retry.application.WorkflowFreezeService;
import com.complyance.assignment.security.TenantPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows/{workflowId}/freeze")
public class WorkflowFreezeController {

    private final WorkflowFreezeService workflowFreezeService;

    public WorkflowFreezeController(WorkflowFreezeService workflowFreezeService) {
        this.workflowFreezeService = workflowFreezeService;
    }

    @PutMapping
    public ResponseEntity<Void> freeze(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String workflowId) {
        workflowFreezeService.freeze(principal.tenantId(), workflowId);
        return ResponseEntity.noContent().build();
    }
}
