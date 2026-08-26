-- Migration V100: Standardized 2:50 Timed Change Request Handout
-- Add workflow freeze tracking table for emergency workflow freezes

CREATE TABLE workflow_freezes (
    tenant_id VARCHAR(120) NOT NULL,
    workflow_id VARCHAR(120) NOT NULL,
    frozen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_workflow_freezes PRIMARY KEY (tenant_id, workflow_id)
);
