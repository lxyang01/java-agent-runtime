package io.github.lxyang01.agent.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.agent.llm.LlmRequest;
import io.github.lxyang01.agent.policy.PolicyException;
import io.github.lxyang01.agent.policy.RiskLevel;
import io.github.lxyang01.agent.policy.ToolPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DoublesTest {

    InMemoryApprovalStore store = new InMemoryApprovalStore();
    ToolPolicy highWrite = ToolPolicy.of(RiskLevel.HIGH_WRITE, false, "高风险写");

    private ApprovalRecordLike request() {
        var record = store.request("s1", "t1", 2, "demo.write",
            Map.of("x", 1), highWrite, Map.of("schema_version", 2));
        return new ApprovalRecordLike(record);
    }

    record ApprovalRecordLike(io.github.lxyang01.agent.store.ApprovalRecord raw) {}

    @Test
    void request_generates_pol_id_and_pending_status() {
        var like = request();
        assertThat(like.raw.id()).startsWith("POL-").hasSize(16);
        assertThat(like.raw.status()).isEqualTo("pending");
        assertThat(like.raw.riskLevel()).isEqualTo("high_write");
    }

    @Test
    void forbidden_cannot_request_approval() {
        assertThatThrownBy(() -> store.request("s1", "t1", 1, "x", Map.of(),
            ToolPolicy.of(RiskLevel.FORBIDDEN, true, "禁用"), Map.of()))
            .isInstanceOf(PolicyException.class)
            .hasMessage("forbidden tool cannot request approval: x");
    }

    @Test
    void decide_then_mark_execution_lifecycle() {
        var like = request();
        var approved = store.decide(like.raw.id(), true, "admin", " 同意 ");
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.decidedBy()).isEqualTo("admin");
        assertThat(approved.decisionNote()).isEqualTo("同意");

        var executed = store.markExecution(like.raw.id(), true, "");
        assertThat(executed.status()).isEqualTo("executed");
        assertThat(executed.executedAt()).isNotBlank();
    }

    @Test
    void decide_twice_rejected_with_message() {
        var like = request();
        store.decide(like.raw.id(), false, "a", "");
        assertThatThrownBy(() -> store.decide(like.raw.id(), true, "b", ""))
            .isInstanceOf(PolicyException.class)
            .hasMessage("approval is already rejected: " + like.raw.id());
    }

    @Test
    void mark_execution_requires_approved() {
        var like = request();
        assertThatThrownBy(() -> store.markExecution(like.raw.id(), true, ""))
            .isInstanceOf(PolicyException.class)
            .hasMessage("only approved requests can be executed: pending");
    }

    @Test
    void decided_by_required() {
        var like = request();
        assertThatThrownBy(() -> store.decide(like.raw.id(), true, "  ", ""))
            .isInstanceOf(PolicyException.class)
            .hasMessage("decided_by is required");
    }

    @Test
    void as_map_shape_matches_python_dict() {
        var like = request();
        var map = like.raw.asMap();
        assertThat(map).containsOnlyKeys("id", "session_id", "trace_id", "step", "tool_name",
            "arguments", "risk_level", "reason", "status", "requested_at", "decided_at",
            "decided_by", "decision_note", "executed_at", "execution_error");
        assertThat(like.raw.asMap(true)).containsKey("checkpoint");
    }

    @Test
    void list_filters_by_session_and_status() {
        request();
        store.request("s2", "t2", 1, "demo.write", Map.of(), highWrite, Map.of());
        assertThat(store.list("s1", null, 100)).hasSize(1);
        assertThat(store.list(null, List.of("pending"), 100)).hasSize(2);
        assertThatThrownBy(() -> store.list(null, null, 0))
            .hasMessage("approval list limit must be between 1 and 500");
    }

    @Test
    void scripted_llm_pops_and_serializes() {
        var llm = new ScriptedLlm("{\"final\":\"a\"}", Map.of("final", "b"));
        var request = new LlmRequest(List.of(), List.of());
        assertThat(llm.complete(request).raw()).isEqualTo("{\"final\":\"a\"}");
        assertThat(llm.complete(request).raw()).isEqualTo("{\"final\":\"b\"}");
        assertThatThrownBy(() -> llm.complete(request))
            .isInstanceOf(AssertionError.class)
            .hasMessage("script exhausted");
    }

    @Test
    void final_llm_constant() {
        var llm = new FinalLlm("好的");
        assertThat(llm.complete(new LlmRequest(List.of(), List.of())).raw())
            .isEqualTo("{\"final\":\"好的\"}");
    }
}
