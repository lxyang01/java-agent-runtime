package io.github.lxyang01.agent.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PolicyGatewayTest {

    @Test
    void read_only_allows() {
        assertThat(PolicyGateway.enforce(ToolPolicy.read(), "t"))
            .isSameAs(Allowed.INSTANCE);
    }

    @Test
    void low_write_allows_by_default() {
        var policy = new ToolPolicy(RiskLevel.LOW_WRITE, false, "草稿操作");
        assertThat(PolicyGateway.enforce(policy, "t")).isSameAs(Allowed.INSTANCE);
    }

    @Test
    void high_write_requires_approval() {
        var policy = new ToolPolicy(RiskLevel.HIGH_WRITE, false, "高风险写");
        assertThat(PolicyGateway.enforce(policy, "t")).isSameAs(ApprovalRequired.INSTANCE);
    }

    @Test
    void requires_approval_flag_alone_triggers_approval() {
        var policy = new ToolPolicy(RiskLevel.LOW_WRITE, true, "需人工把关");
        assertThat(PolicyGateway.enforce(policy, "t")).isSameAs(ApprovalRequired.INSTANCE);
    }

    @Test
    void forbidden_throws() {
        var policy = new ToolPolicy(RiskLevel.FORBIDDEN, false, "禁用");
        assertThatThrownBy(() -> PolicyGateway.enforce(policy, "secret"))
            .isInstanceOf(PolicyException.class)
            .hasMessage("tool is forbidden by policy: secret");
    }

    @Test
    void forbidden_forces_requires_approval() {
        var policy = new ToolPolicy(RiskLevel.FORBIDDEN, false, "禁用");
        assertThat(policy.requiresApproval()).isTrue();
    }

    @Test
    void risk_wire_values_match_wire_contract() {
        assertThat(RiskLevel.READ.wire()).isEqualTo("read");
        assertThat(RiskLevel.LOW_WRITE.wire()).isEqualTo("low_write");
        assertThat(RiskLevel.HIGH_WRITE.wire()).isEqualTo("high_write");
        assertThat(RiskLevel.FORBIDDEN.wire()).isEqualTo("forbidden");
    }
}
