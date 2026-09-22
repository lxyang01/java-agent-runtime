package io.github.lxyang01.billguard.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.policy.ToolPolicy;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.types.ChatMessage;
import io.github.lxyang01.agent.types.Conversation;
import io.github.lxyang01.billguard.PgTestBase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PgStoresTest extends PgTestBase {

    PgConversationStore conversations;
    PgApprovalStore approvals;
    PgTraceWriter traces;

    @org.junit.jupiter.api.BeforeAll
    void initStores() {
        conversations = new PgConversationStore(jdbc);
        approvals = new PgApprovalStore(jdbc);
        traces = new PgTraceWriter(jdbc, new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)));
    }

    ToolPolicy highWrite = ToolPolicy.of(RiskLevel.HIGH_WRITE, false, "高风险写");

    @Test
    void conversation_roundtrip_and_upsert() {
        Conversation conversation = Conversation.newConversation("s-1");
        conversation.addMessage(ChatMessage.user("你好"));
        conversation.addMessage(ChatMessage.tool("bill.query", "{\"a\":1}", "c1"));
        conversations.save(conversation);

        Conversation loaded = conversations.load("s-1");
        assertThat(loaded.messages()).hasSize(2);
        assertThat(loaded.messages().get(1).name()).isEqualTo("bill.query");
        assertThat(loaded.messages().get(1).toolCallId()).isEqualTo("c1");

        loaded.setSummary("- 问:旧 答:旧答");
        conversations.save(loaded);
        assertThat(conversations.load("s-1").getSummary()).isEqualTo("- 问:旧 答:旧答");
        assertThat(conversations.exists("s-1")).isTrue();
    }

    @Test
    void missing_conversation_returns_empty() {
        assertThat(conversations.load("never").messages()).isEmpty();
        assertThat(conversations.exists("never")).isFalse();
    }

    @Test
    void invalid_session_id_rejected() {
        assertThatThrownBy(() -> conversations.load(""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid session id");
        assertThatThrownBy(() -> conversations.load("x".repeat(201)))
            .hasMessage("invalid session id");
    }

    @Test
    void approval_lifecycle_in_pg() {
        var record = approvals.request("s-1", "t-1", 3, "demo.write",
            Map.of("value", "数据"), highWrite, Map.of("schema_version", 2, "step", 3));
        assertThat(record.id()).startsWith("POL-");

        var loaded = approvals.get(record.id());
        assertThat(loaded.arguments()).containsEntry("value", "数据");
        assertThat(loaded.checkpoint()).containsEntry("schema_version", 2);
        assertThat(loaded.riskLevel()).isEqualTo("high_write");
        assertThat(loaded.status()).isEqualTo("pending");

        var approved = approvals.decide(record.id(), true, "admin", "同意");
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.decidedBy()).isEqualTo("admin");

        var executed = approvals.markExecution(record.id(), true, "");
        assertThat(executed.status()).isEqualTo("executed");
        // execution_result JSONB 记录执行时刻
        String raw = jdbc.queryForObject(
            "SELECT execution_result::text FROM approvals WHERE id = ?", String.class,
            record.id());
        assertThat(raw).contains("succeeded").contains("executed_at");
    }

    @Test
    void concurrent_decide_exactly_once() throws Exception {
        var record = approvals.request("s-1", "t-1", 1, "demo.write",
            Map.of(), highWrite, Map.of());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            boolean vote = i == 0;
            String voter = "user-" + i;
            futures.add(pool.submit((Callable<Void>) () -> {
                barrier.await();
                try {
                    approvals.decide(record.id(), vote, voter, "");
                    successes.incrementAndGet();
                } catch (PolicyException e) {
                    conflicts.incrementAndGet();
                    assertThat(e.getMessage()).isEqualTo(
                        "approval is already decided: " + record.id());
                }
                return null;
            }));
        }
        for (Future<Void> future : futures) {
            future.get();
        }
        pool.shutdown();
        // 恰好一次:一个成功,另一个被拒
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        var finalRecord = approvals.get(record.id());
        assertThat(finalRecord.status()).isIn("approved", "rejected");
    }

    @Test
    void list_filters_by_session_and_status() {
        approvals.request("s-1", "t-1", 1, "demo.write", Map.of(), highWrite, Map.of());
        approvals.request("s-2", "t-2", 1, "demo.write", Map.of(), highWrite, Map.of());
        assertThat(approvals.list("s-1", null, 100)).hasSize(1);
        assertThat(approvals.list(null, List.of("pending"), 100)).hasSize(2);
        assertThatThrownBy(() -> approvals.list(null, null, 501))
            .hasMessage("approval list limit must be between 1 and 500");
    }

    @Test
    void trace_appends_events_into_single_row() {
        for (int i = 0; i < 3; i++) {
            traces.appendEvent("s-1", "t-1", "demo",
                Map.of("event", "step_" + i, "i", i));
        }
        traces.appendEvent("s-1", "t-2", "demo", Map.of("event", "other"));
        Integer count = jdbc.queryForObject(
            "SELECT jsonb_array_length(events) FROM traces "
                + "WHERE session_id = ? AND trace_id = ?", Integer.class, "s-1", "t-1");
        assertThat(count).isEqualTo(3);
        String agent = jdbc.queryForObject(
            "SELECT agent FROM traces WHERE session_id = ? AND trace_id = ?",
            String.class, "s-1", "t-1");
        assertThat(agent).isEqualTo("demo");
    }

    @Test
    void delete_for_conversation() {
        var record = approvals.request("s-1", "t-1", 1, "demo.write",
            Map.of(), highWrite, Map.of());
        assertThat(approvals.deleteForConversation("s-1")).isEqualTo(1);
        assertThatThrownBy(() -> approvals.get(record.id()))
            .hasMessage("approval not found: " + record.id());
    }
}
