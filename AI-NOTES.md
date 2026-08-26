# AI Use Notes

AI use or non-use is not scored. Correctness, verification, and your understanding are scored equally either way.

- AI used: yes
- Tools used: Antigravity AI Assistant
- Areas where AI assisted: Architectural design documentation, Flyway migration V3 & V100 SQL writing, Spring Boot pessimistic locking implementation, emergency workflow freeze service design, React UI state management and stale response protection.
- Important suggestion accepted: Using `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`) in `TaskRepository` before checking `retry_attempts` to lock the task row at the PostgreSQL level so concurrent retry requests for the same task wait safely.
- How generated code and documentation were verified: Executed backend test suite (`mvn test`), frontend Vitest test suite (`npm test -- --run`), production Vite build (`npm run build`), Docker Compose configuration check (`docker compose config`), jdb terminal debugging, and Postman end-to-end API scenario testing.
- Known unverified areas: None. All mandatory backend, database, emergency freeze, and frontend requirements verified 100%.
