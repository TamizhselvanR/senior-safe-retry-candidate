package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.complyance.assignment.SafeRetryApplication;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = SafeRetryApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WorkflowFreezeContractTest {

    private static final String ALPHA_AUTH = "Bearer tenant-alpha-token";
    private static final String BETA_AUTH = "Bearer tenant-beta-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void validFreezeReturns204NoContent() throws Exception {
        freeze(ALPHA_AUTH, "workflow-alpha")
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from workflow_freezes where workflow_id = 'workflow-alpha'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void callingFreezeRepeatedlyIsIdempotentAndReturns204() throws Exception {
        freeze(ALPHA_AUTH, "workflow-alpha").andExpect(status().isNoContent());
        freeze(ALPHA_AUTH, "workflow-alpha").andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from workflow_freezes where workflow_id = 'workflow-alpha'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void unknownOrCrossTenantWorkflowFreezeReturns404NotFound() throws Exception {
        freeze(ALPHA_AUTH, "unknown-workflow")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        freeze(BETA_AUTH, "workflow-alpha")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void retryOnFrozenWorkflowReturns409WorkflowFrozenWithoutWrites() throws Exception {
        freeze(ALPHA_AUTH, "workflow-alpha").andExpect(status().isNoContent());

        retry(ALPHA_AUTH, "task-alpha-retryable", "frozen-test-key", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_FROZEN"));

        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isZero();
    }

    @Test
    void retriesAcceptedBeforeFreezeRemainUnchanged() throws Exception {
        retry(ALPHA_AUTH, "task-alpha-retryable", "pre-freeze-key", 0)
                .andExpect(status().isAccepted());

        freeze(ALPHA_AUTH, "workflow-alpha").andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select status from tasks where id = 'task-alpha-retryable'", String.class))
                .isEqualTo("RETRY_QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentFreezeAndRetryResolvesDeterministically() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var freezeTask = pool.submit(() -> {
                ready.countDown();
                start.await();
                return freeze(ALPHA_AUTH, "workflow-alpha").andReturn();
            });

            var retryTask = pool.submit(() -> {
                ready.countDown();
                start.await();
                return retry(ALPHA_AUTH, "task-alpha-retryable", "concurrent-freeze-key", 0).andReturn();
            });

            ready.await();
            start.countDown();

            var freezeStatus = freezeTask.get().getResponse().getStatus();
            var retryStatus = retryTask.get().getResponse().getStatus();

            assertThat(freezeStatus).isEqualTo(204);
            assertThat(retryStatus).isIn(202, 409);

            if (retryStatus == 202) {
                assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(1);
            } else {
                assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isZero();
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions freeze(
            String authorization, String workflowId) throws Exception {
        return mockMvc.perform(put("/api/workflows/" + workflowId + "/freeze")
                .header("Authorization", authorization));
    }

    private org.springframework.test.web.servlet.ResultActions retry(
            String authorization, String taskId, String key, long expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/" + taskId + "/retry")
                .header("Authorization", authorization)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + "}"));
    }
}
