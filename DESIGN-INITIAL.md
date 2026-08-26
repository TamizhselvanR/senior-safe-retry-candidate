# Initial Design — Safe Retry

## 1. Scope and invariants

- Base scope: Implement safe, idempotent task retry for `FAILED_RETRYABLE` tasks. Atomic mutations across `tasks`, `retry_attempts`, `audit_events`, and `outbox_messages` via `POST /api/workflows/{workflowId}/tasks/{taskId}/retry`.
- Out of scope: Task execution background workers, outbox pollers, automatic retry loops, custom UI redesigns.
- Invariants that must always hold:
  1. Tenant boundary: All access filtered by server-derived `TenantPrincipal`.
  2. Single state transition: `FAILED_RETRYABLE` -> `RETRY_QUEUED` with version incremented (`v+1`).
  3. Idempotent replay: Identical request (same tenant, key, fingerprint) returns `200 OK` (`replayed: true`). Key reuse with different fingerprint returns `409 IDEMPOTENCY_KEY_REUSED`.
  4. Atomic DB effects: All 4 table mutations commit or roll back together under single `@Transactional` boundary.

## 2. Request-to-database design

```text
[Browser UI]
   | Bearer token + Idempotency-Key + expectedVersion
   v [Trust Boundary / Security Filter (Derives TenantPrincipal)]
[Spring Boot API / RetryController]
   v @Transactional
[RetryService]
   | 1. Lock task row (SELECT ... FOR UPDATE)
   | 2. Check retry_attempts for tenant idempotency key & fingerprint
   | 3. Validate status (FAILED_RETRYABLE) & version (expectedVersion)
   | 4. Update task (RETRY_QUEUED, v+1)
   | 5. Insert 1 attempt, 1 audit event, 1 outbox record
   ` 6. Call fault-injection hook afterOutboxInserted()
   v
[PostgreSQL Database] (Enforces atomic transaction & UNIQUE constraint)
```

## 3. API, data, and transaction choices

- Validation and safe error mapping: Validate header format `^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$`. `ApiExceptionHandler` maps exceptions to standard JSON error payloads without stack trace leaks.
- Idempotency scope, fingerprint, and replay behavior: Tenant-scoped `(tenant_id, idempotency_key)`. Fingerprint = SHA-256 hash of `tenant:workflow:task:version`.
- Task transition and concurrency control: Pessimistic write lock `TaskRepository.findByIdAndTenantIdAndWorkflowId` (`SELECT ... FOR UPDATE`).
- Tables, foreign keys, uniqueness, and supporting indexes: Flyway `V3` migration adds unique constraint `uk_retry_attempt_tenant_key` on `retry_attempts (tenant_id, idempotency_key)` and index `ix_retry_attempt_fingerprint`.
- Additive V3–V99 migration and canonical-table compatibility: Additive Flyway `V3` migration without altering canonical schema shapes or reserving `V100`.
- Transaction and rollback boundary: `@Transactional` covers lock acquisition, validation, 3 table inserts, and `afterOutboxInserted()` fault injection point.

## 4. Failure mitigation

| Failure | Invariant at risk | Prevention | Detection | Recovery | Planned evidence |
|---|---|---|---|---|---|
| Simultaneous retries, using either the same or different keys | Single transition / Idempotency | PostgreSQL `SELECT ... FOR UPDATE` & `uk_retry_attempt_tenant_key` | DB lock wait & constraint conflict | Contender receives 200 (same key) or 409 (different key) | `PublicContractTest.concurrent...` |
| Same tenant key reused with a different fingerprint | Idempotency integrity | SHA-256 fingerprint validation in `RetryService` | Fingerprint mismatch comparison | 409 `IDEMPOTENCY_KEY_REUSED` | `PublicContractTest.sameKeyWithDifferentExpectedVersion...` |
| Exception at `afterOutboxInserted()` | Atomic DB effects | Single Spring `@Transactional` boundary | Exception caught by transaction manager | 100% atomic DB rollback across all 4 tables | `PublicContractTest.failureAfterOutboxInsertionRollsBack...` |
| Cross-tenant or workflow/task mismatch | Tenant isolation | Server-derived tenant ID & strict SQL filter | Empty query result | 404 `TASK_NOT_FOUND` | `PublicContractTest.crossTenantRetry...` |
| Older task-list response arrives after a newer retry response | Version safety | Version comparison in `App.jsx` state update | Version check in React state updater | Ignore stale response, retain newest v1 task | Vitest test `does not let an older response overwrite...` |
| Migration succeeds but the application rollout fails | Database backward compatibility | Additive Flyway migration (`V3`) | Startup migration check | Safe backward-compatible rollback | `mvn verify` & `docker compose config` |

## 5. Verification and operations plan

- Focused tests and fault injection: `mvn test` running `PublicContractTest` and `StarterSmokeTest`.
- Safe structured-log fields and fields that must never be logged: Log `taskId`, `workflowId`, `tenantId`, `attemptId`. Never log `Authorization` bearer tokens.
- Health/smoke signals: `/actuator/health/readiness` and `StarterSmokeTest`.
- Migration compatibility and rollback approach: Additive schema additions cleanly compatible with `V1` and `V2`.
- Highest residual risk: Database lock contention under heavy simultaneous retries on the same task.

## Initial-design checkpoint

- Commit hash: `initial-design-checkpoint` (Commit 1)
- Checkpoint time: `2026-08-26T15:40:00+05:30` (0:15 cumulative active time)
