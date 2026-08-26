package com.complyance.assignment.retry.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByTenantIdOrderByIdAsc(String tenantId);

    Optional<TaskEntity> findByIdAndTenantId(String id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TaskEntity> findByIdAndTenantIdAndWorkflowId(String id, String tenantId, String workflowId);
}
