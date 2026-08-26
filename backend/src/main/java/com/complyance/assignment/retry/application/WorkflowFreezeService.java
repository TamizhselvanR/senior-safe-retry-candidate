package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.TaskNotFoundException;
import com.complyance.assignment.retry.domain.TaskRepository;
import com.complyance.assignment.retry.domain.WorkflowFreezeEntity;
import com.complyance.assignment.retry.domain.WorkflowFreezeRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowFreezeService {

    private final TaskRepository taskRepository;
    private final WorkflowFreezeRepository workflowFreezeRepository;

    public WorkflowFreezeService(
            TaskRepository taskRepository,
            WorkflowFreezeRepository workflowFreezeRepository) {
        this.taskRepository = taskRepository;
        this.workflowFreezeRepository = workflowFreezeRepository;
    }

    @Transactional
    public void freeze(String tenantId, String workflowId) {
        // Validate that workflow exists for this tenant; throw 404 if missing or cross-tenant
        if (!taskRepository.existsByTenantIdAndWorkflowId(tenantId, workflowId)) {
            throw new TaskNotFoundException();
        }

        // Acquire pessimistic write lock on workflow task rows to synchronize against concurrent retry calls
        taskRepository.findByTenantIdAndWorkflowId(tenantId, workflowId);

        // Idempotent freeze: save freeze entity if not already present
        if (!workflowFreezeRepository.existsByTenantIdAndWorkflowId(tenantId, workflowId)) {
            WorkflowFreezeEntity freezeEntity = new WorkflowFreezeEntity(tenantId, workflowId, Instant.now());
            workflowFreezeRepository.save(freezeEntity);
        }
    }
}
