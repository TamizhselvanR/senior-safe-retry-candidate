package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.AuditEventEntity;
import com.complyance.assignment.retry.domain.AuditEventRepository;
import com.complyance.assignment.retry.domain.InvalidRetryRequestException;
import com.complyance.assignment.retry.domain.OutboxMessageEntity;
import com.complyance.assignment.retry.domain.OutboxMessageRepository;
import com.complyance.assignment.retry.domain.RetryAttemptEntity;
import com.complyance.assignment.retry.domain.RetryAttemptRepository;
import com.complyance.assignment.retry.domain.RetryConflictException;
import com.complyance.assignment.retry.domain.TaskEntity;
import com.complyance.assignment.retry.domain.TaskNotFoundException;
import com.complyance.assignment.retry.domain.TaskRepository;
import com.complyance.assignment.retry.domain.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetryService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$");

    private final TaskRepository taskRepository;
    private final RetryAttemptRepository retryAttemptRepository;
    private final AuditEventRepository auditEventRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final RetryFailureInjector retryFailureInjector;

    public RetryService(
            TaskRepository taskRepository,
            RetryAttemptRepository retryAttemptRepository,
            AuditEventRepository auditEventRepository,
            OutboxMessageRepository outboxMessageRepository,
            RetryFailureInjector retryFailureInjector) {
        this.taskRepository = taskRepository;
        this.retryAttemptRepository = retryAttemptRepository;
        this.auditEventRepository = auditEventRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.retryFailureInjector = retryFailureInjector;
    }

    @Transactional
    public RetryOutcome retry(RetryCommand command) {
        // Validate Idempotency-Key header format (8-120 ASCII chars)
        if (command.idempotencyKey() == null || !IDEMPOTENCY_KEY_PATTERN.matcher(command.idempotencyKey()).matches()) {
            throw new InvalidRetryRequestException("Invalid Idempotency-Key header format");
        }

        // Compute SHA-256 fingerprint (tenant:workflow:task:expectedVersion)
        String fingerprint = computeFingerprint(
                command.tenantId(), command.workflowId(), command.taskId(), command.expectedVersion());

        // Acquire pessimistic write lock on target task row FIRST (SELECT ... FOR UPDATE)
        // Serializes concurrent HTTP requests at the PostgreSQL database boundary
        TaskEntity task = taskRepository
                .findByIdAndTenantIdAndWorkflowId(command.taskId(), command.tenantId(), command.workflowId())
                .orElseThrow(TaskNotFoundException::new);

        Optional<RetryAttemptEntity> existingAttempt =
                retryAttemptRepository.findByTenantIdAndIdempotencyKey(command.tenantId(), command.idempotencyKey());

        if (existingAttempt.isPresent()) {
            RetryAttemptEntity attempt = existingAttempt.get();
            // Re-used key with different fingerprint -> 409 IDEMPOTENCY_KEY_REUSED
            if (!attempt.getRequestFingerprint().equals(fingerprint)) {
                throw RetryConflictException.reusedKey();
            }
            // Exact replay -> Return original attempt ID with replayed=true (200 OK)
            return new RetryOutcome(
                    task.getId(),
                    task.getWorkflowId(),
                    task.getTitle(),
                    task.getStatus(),
                    task.getVersion(),
                    attempt.getId(),
                    true);
        }

        if (task.getStatus() != TaskStatus.FAILED_RETRYABLE) {
            throw RetryConflictException.notRetryable(task.getStatus());
        }

        if (task.getVersion() != command.expectedVersion()) {
            throw RetryConflictException.staleVersion(command.expectedVersion(), task.getVersion());
        }

        Instant now = Instant.now();
        task.queueRetry(now); // Mutate task status -> RETRY_QUEUED, version -> v+1
        long acceptedVersion = command.expectedVersion() + 1;
        String attemptId = UUID.randomUUID().toString();

        RetryAttemptEntity attempt = new RetryAttemptEntity(
                attemptId,
                command.tenantId(),
                command.workflowId(),
                command.taskId(),
                task.getTitle(),
                TaskStatus.RETRY_QUEUED,
                acceptedVersion,
                command.idempotencyKey(),
                fingerprint,
                now);
        retryAttemptRepository.save(attempt);

        AuditEventEntity auditEvent = new AuditEventEntity(
                UUID.randomUUID().toString(),
                command.tenantId(),
                command.taskId(),
                attemptId,
                "TASK_RETRY_QUEUED",
                now);
        auditEventRepository.save(auditEvent);

        String payload = String.format(
                "{\"taskId\":\"%s\",\"workflowId\":\"%s\",\"attemptId\":\"%s\",\"version\":%d}",
                command.taskId(), command.workflowId(), attemptId, acceptedVersion);
        OutboxMessageEntity outboxMessage = new OutboxMessageEntity(
                UUID.randomUUID().toString(),
                command.tenantId(),
                command.taskId(),
                attemptId,
                "TASK_RETRY_REQUESTED",
                payload,
                now);
        outboxMessageRepository.save(outboxMessage);

        // Trigger required fault-injection hook to verify rollback safety
        retryFailureInjector.afterOutboxInserted();

        return new RetryOutcome(
                task.getId(),
                task.getWorkflowId(),
                task.getTitle(),
                TaskStatus.RETRY_QUEUED,
                acceptedVersion,
                attemptId,
                false);
    }

    private static String computeFingerprint(
            String tenantId, String workflowId, String taskId, long expectedVersion) {
        String raw = tenantId + ":" + workflowId + ":" + taskId + ":" + expectedVersion;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}
