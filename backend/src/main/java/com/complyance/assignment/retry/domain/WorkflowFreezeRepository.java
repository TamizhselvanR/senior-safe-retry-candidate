package com.complyance.assignment.retry.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowFreezeRepository extends JpaRepository<WorkflowFreezeEntity, WorkflowFreezeId> {

    boolean existsByTenantIdAndWorkflowId(String tenantId, String workflowId);
}
