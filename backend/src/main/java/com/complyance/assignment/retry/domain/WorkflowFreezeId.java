package com.complyance.assignment.retry.domain;

import java.io.Serializable;
import java.util.Objects;

public class WorkflowFreezeId implements Serializable {

    private String tenantId;
    private String workflowId;

    public WorkflowFreezeId() {}

    public WorkflowFreezeId(String tenantId, String workflowId) {
        this.tenantId = tenantId;
        this.workflowId = workflowId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowFreezeId that = (WorkflowFreezeId) o;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(workflowId, that.workflowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, workflowId);
    }
}
