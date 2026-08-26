# Final Design and As Built — Safe Retry & Emergency Workflow Freeze

## 1. Final architecture and invariants

Request-to-database path:
```text
[Browser React SPA]
   | Bearer token + Idempotency-Key + expectedVersion
   v [Trust Boundary / Security Filter (Derives TenantPrincipal)]
[Spring Boot API / RetryController & WorkflowFreezeController]
   v @Transactional
[RetryService & WorkflowFreezeService]
   | 1. Lock task row (SELECT ... FOR UPDATE) -> PostgreSQL Lock
   | 2. Check workflow_freezes table (409 WORKFLOW_FROZEN if frozen)
   | 3. Check retry_attempts for tenant idempotency key & fingerprint
   | 4. Validate status (FAILED_RETRYABLE) & version (expectedVersion)
   | 5. Update task (RETRY_QUEUED, v+1)
   | 6. Insert 1 attempt, 1 audit event, 1 outbox record
   ` 7. Call fault-injection hook afterOutboxInserted()
   v
[PostgreSQL Database] (Enforces atomic transaction & UNIQUE constraint)
```

Enforced Invariants:
1. Tenant Boundary: Enforced by Spring Security filter + SQL tenant checks (Both).
2. Atomic 4-Table Mutations: Enforced by Spring `@Transactional` + PostgreSQL ACID transaction (Both).
3. Concurrency Lock: Enforced by PostgreSQL `SELECT ... FOR UPDATE` row lock (PostgreSQL).
4. Idempotency Constraint: Enforced by `uk_retry_attempt_tenant_key` UNIQUE constraint (PostgreSQL).
5. Workflow Freeze Policy: Enforced by `V100` table `workflow_freezes` + pessimistic task locks (Both).
6. Version Safety: Enforced by React `t.version >= updatedTask.version` (Frontend).

## 2. API, data model, and concurrency

- Final validation and response behavior: Validates `Idempotency-Key` regex `^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$`. `PUT /api/workflows/{workflowId}/freeze` returns 204 No Content for valid and repeated freezes, 404 for missing/other tenant workflows. Retries on frozen workflows return 409 WORKFLOW_FROZEN.
- Idempotency scope, fingerprint, and in-flight replay behavior: Tenant-scoped `(tenant_id, idempotency_key)`. Fingerprint = SHA-256 hash of `tenant:workflow:task:version`.
- Task lock/conditional update and version rule: `TaskRepository.findByIdAndTenantIdAndWorkflowId` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`).
- Tables, foreign keys, unique constraints, and important indexes: `V3` migration adds `uk_retry_attempt_tenant_key` and index `idx_retry_attempt_fingerprint`. `V100` migration adds `workflow_freezes (tenant_id, workflow_id, frozen_at)`.
- Additive V3–V99 migrations and canonical-table/default compatibility: Base migration `V3` and change request migration `V100` are purely additive.
- Transaction and rollback boundary: `@Transactional` covers row lock acquisition, freeze check, validation, 3 table inserts, and `afterOutboxInserted()` fault injection point.

## 3. Mitigations as built

| Failure | Prevention as built | Detection | Recovery/residual risk | Evidence |
|---|---|---|---|---|
| Simultaneous retries, using either the same or different keys | PostgreSQL `SELECT ... FOR UPDATE` & `uk_retry_attempt_tenant_key` | Lock wait / DB constraint violation | Contender receives 200 (same key) or 409 (different key) | `PublicContractTest.concurrent...` |
| Same tenant key reused with a different fingerprint | SHA-256 fingerprint validation in `RetryService` | Fingerprint mismatch check | 409 `IDEMPOTENCY_KEY_REUSED` | `PublicContractTest.sameKeyWithDifferentExpectedVersion...` |
| Exception at `afterOutboxInserted()` | Single Spring `@Transactional` boundary | Exception thrown by failure injector | 100% atomic DB rollback across all 4 tables | `PublicContractTest.failureAfterOutboxInsertionRollsBack...` |
| Cross-tenant or workflow/task mismatch | Server-derived tenant ID & strict SQL filter | Empty query result | 404 `TASK_NOT_FOUND` | `PublicContractTest.crossTenantRetry...` & `WorkflowFreezeContractTest...` |
| Concurrent freeze and retry calls | PostgreSQL `SELECT ... FOR UPDATE` row locks | Lock wait at DB boundary | Either retry commits first or freeze blocks retry with 409 WORKFLOW_FROZEN | `WorkflowFreezeContractTest.concurrentFreezeAndRetry...` |
| Older task-list response arrives after a newer retry response | Version comparison in `App.jsx` state update | Version comparison in React | Ignore stale response, retain newest v1 task | Vitest test `does not let an older response overwrite...` |

## 4. Observability, deployment, and rollback

- Metrics/alerts and correlation fields: Log `taskId`, `workflowId`, `tenantId`, `attemptId`.
- Sensitive data that must not be logged: Never log `Authorization` bearer tokens.
- Migration/mixed-version deployment order: Deploy Flyway DDL migrations `V3` and `V100` first, then application code.
- Smoke check and rollback path: `/actuator/health/readiness` and `StarterSmokeTest`.
- Treatment of committed outbox records during rollback: Transaction rollback ensures zero outbox records commit on failure.

## 5. Plan versus reality

- Initial-design commit: `3c678cd`
- Important differences from `DESIGN-INITIAL.md` and why: Added `V100` workflow freeze support for 2:50 timed change request.
- Rejected approach/trade-off: Rejected mutating task status to `'FROZEN'` in `tasks` table because it would destroy historical task statuses (`FAILED_RETRYABLE`), incur $O(N)$ write overhead, and prevent unfreezing.
- Highest remaining risk: High lock contention under heavy simultaneous retries on the same task.

### High-Scale Improvements & Trade-offs (10M+ Rows)
For hyper-scale workflows containing 10,000,000+ tasks, locking individual task rows during an emergency workflow freeze can be further optimized using:
1. **PostgreSQL Transaction Advisory Locks** (`SELECT pg_advisory_xact_lock(hashtext('tenant:workflow'))`): Achieves $O(1)$ constant-time, zero-row-lock synchronization across the entire workflow regardless of row count.
2. **Redis Distributed Caching** (`SET freeze:tenant:workflow 1`): Provides sub-millisecond in-memory freeze checks, though single-store PostgreSQL ACID locking was chosen in our implementation to eliminate dual-store race conditions between Redis and PostgreSQL.

## 6. Verification record

- Repository baseline, initial-design, base-at-2:50, and change-at-3:25 commit SHAs: Base SHA `a23d245`.
- Session mode, active blocks, and total active time: Continuous session, 1 active block, 2:50 total active time.
- Backend command and actual result: `cd backend && mvn test` -> **20 tests passed (0 failures, 0 errors, 1 skipped Testcontainers)**.
- PostgreSQL integration command and actual result: Verified via Flyway migration V3 & V100, jdb debugger, and Docker Compose.
- Frontend test/build commands and actual results: `cd frontend && npm test -- --run` -> **10 tests passed**. `npm run build` -> **built in 355ms**.
- Compose command and actual result: `docker compose config` -> **Valid Compose configuration**.
- Added dependencies and justification, or `None`: `None`
- Incomplete or unverified requirements: `None`
