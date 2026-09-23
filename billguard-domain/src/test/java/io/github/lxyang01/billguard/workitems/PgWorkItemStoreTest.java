package io.github.lxyang01.billguard.workitems;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.lxyang01.billguard.PgTestBase;
import io.github.lxyang01.billguard.storage.PgWorkItemStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 工单三阶段状态机:恰好一次 decide / FOR UPDATE 幂等 commit / 过期语义。 */
class PgWorkItemStoreTest extends PgTestBase {

    PgWorkItemStore store;

    @BeforeEach
    void init() {
        store = new PgWorkItemStore(jdbc, new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)));
    }

    @Test
    void prepare_decide_commit_full_flow() {
        var prepared = store.prepareIssue("取消视频会员", "用户要求退订", "high",
            List.of("tx-1", "tx-1", " tx-2 "));
        assertThat(prepared.get("approval_id")).asString().startsWith("APR-").hasSize(14);
        assertThat(prepared.get("status")).isEqualTo("pending");
        assertThat(prepared.get("action")).isEqualTo("issue.create");
        Map<?, ?> payloadView = (Map<?, ?>) prepared.get("payload");
        assertThat(payloadView.get("title")).isEqualTo("取消视频会员");
        assertThat(payloadView.get("priority")).isEqualTo("high");
        // evidence_refs 去重保序
        assertThat((List<String>) (List<?>) payloadView.get("evidence_refs"))
            .containsExactly("tx-1", "tx-2");
        assertThat(prepared.get("next_step")).asString()
            .startsWith("工单草稿已创建(approval_id=")
            .contains("必须紧接着调用 commit_issue");

        var decided = store.decide((String) prepared.get("approval_id"), true, "admin");
        assertThat(decided.get("status")).isEqualTo("approved");
        assertThat(decided.get("decided_by")).isEqualTo("admin");

        var committed = store.commitIssue((String) prepared.get("approval_id"));
        assertThat(committed.get("idempotent_replay")).isEqualTo(false);
        Map<?, ?> issue = (Map<?, ?>) committed.get("created");
        assertThat(issue.get("id")).isEqualTo("ISS-0001");
        assertThat(issue.get("title")).isEqualTo("取消视频会员");
        assertThat(issue.get("status")).isEqualTo("open");
        assertThat(issue.get("created_by")).isEqualTo("admin");

        // 二次 commit 幂等回放(FOR UPDATE + consumed)
        var replay = store.commitIssue((String) prepared.get("approval_id"));
        assertThat(replay.get("idempotent_replay")).isEqualTo(true);
        assertThat(((Map<?, ?>) replay.get("created")).get("id")).isEqualTo("ISS-0001");
    }

    @Test
    void decide_is_exactly_once() {
        String id = (String) store.prepareIssue("t", "d", "low", null).get("approval_id");
        store.decide(id, false, "bob");
        assertThatThrownBy(() -> store.decide(id, true, "alice"))
            .isInstanceOf(WorkItemException.class)
            .hasMessage("approval is already rejected: " + id);
    }

    @Test
    void commit_requires_approved() {
        String id = (String) store.prepareIssue("t", "d", "low", null).get("approval_id");
        assertThatThrownBy(() -> store.commitIssue(id))
            .hasMessage("approval must be approved before commit: pending");
        store.decide(id, false, "bob");
        assertThatThrownBy(() -> store.commitIssue(id))
            .hasMessage("approval must be approved before commit: rejected");
    }

    @Test
    void expired_prepare_rejected_and_marked() {
        String id = (String) store.prepareIssue("t", "d", "low", null).get("approval_id");
        jdbc.update("UPDATE wi_approvals SET expires_at = '2000-01-01T00:00:00+00:00' "
            + "WHERE id = ?", id);
        assertThatThrownBy(() -> store.decide(id, true, "admin"))
            .hasMessage("approval has expired: " + id);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM wi_approvals WHERE id = ?", String.class, id))
            .isEqualTo("expired");
    }

    @Test
    void validation_messages_exact() {
        assertThatThrownBy(() -> store.prepareIssue("", "d", "low", null))
            .hasMessage("title must contain between 1 and 200 characters");
        assertThatThrownBy(() -> store.prepareIssue("t", " ", "low", null))
            .hasMessage("description must contain between 1 and 5000 characters");
        assertThatThrownBy(() -> store.prepareIssue("t", "d", "urgent2", null))
            .hasMessage("priority must be low, medium, high, or urgent");
        assertThatThrownBy(() -> store.prepareIssue("t", "d", "low",
            java.util.stream.IntStream.rangeClosed(1, 51).mapToObj(i -> "r" + i).toList()))
            .hasMessage("evidence_refs cannot exceed 50 items");
        assertThatThrownBy(() -> store.decide("APR-NOPE", true, ""))
            .hasMessage("decided_by is required");
    }

    @Test
    void list_get_pending() {
        String id = (String) store.prepareIssue("t1", "d1", "medium", null).get("approval_id");
        assertThat(store.pendingApprovals()).hasSize(1);
        store.decide(id, true, "admin");
        String issueId = (String) ((Map<?, ?>) store.commitIssue(id).get("created")).get("id");
        store.prepareIssue("t2", "d2", "low", null);

        var listed = store.listIssues(null, 50);
        assertThat(listed.get("count")).isEqualTo(1);
        assertThat(listed.get("items")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .singleElement().satisfies(item ->
                assertThat(((Map<?, ?>) item).get("title")).isEqualTo("t1"));

        Map<?, ?> issue = store.getIssue(issueId);
        assertThat(issue.get("title")).isEqualTo("t1");
        assertThatThrownBy(() -> store.getIssue("ISS-9999"))
            .hasMessage("issue not found: ISS-9999");

        var pending = store.pendingApprovals();
        assertThat(((Map<?, ?>) pending.get(0)).get("status")).isEqualTo("pending");
    }
}
