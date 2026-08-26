package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "workflow_freezes")
@IdClass(WorkflowFreezeId.class)
public class WorkflowFreezeEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 120)
    private String tenantId;

    @Id
    @Column(name = "workflow_id", nullable = false, length = 120)
    private String workflowId;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;

    protected WorkflowFreezeEntity() {}

    public WorkflowFreezeEntity(String tenantId, String workflowId, Instant frozenAt) {
        this.tenantId = tenantId;
        this.workflowId = workflowId;
        this.frozenAt = frozenAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }
}
