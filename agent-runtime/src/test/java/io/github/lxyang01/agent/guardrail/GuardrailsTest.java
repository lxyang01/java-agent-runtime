package io.github.lxyang01.agent.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardrailsTest {

    @Test
    void input_over_32k_code_points_rejected() {
        assertThatThrownBy(() -> Guardrails.validateUserInput("你".repeat(32_001)))
            .isInstanceOf(GuardrailException.class)
            .hasMessage("user input exceeds the 32,000 character limit");
    }

    @Test
    void input_at_limit_passes() {
        assertThatCode(() -> Guardrails.validateUserInput("你".repeat(32_000)))
            .doesNotThrowAnyException();
    }

    @Test
    void model_output_over_64k_rejected() {
        assertThatThrownBy(() -> Guardrails.validateModelOutput("a".repeat(64_001)))
            .isInstanceOf(GuardrailException.class)
            .hasMessage("model output exceeds the 64,000 character limit");
    }

    @Test
    void phone_redacted() {
        var r = Guardrails.redactPii("联系 13800138000 确认");
        assertThat(r.text()).isEqualTo("联系 [手机号] 确认");
        assertThat(r.counts()).containsEntry("phone", 1);
    }

    @Test
    void phone_with_boundaries_not_matched() {
        // 前后都是数字 → 不是孤立手机号
        assertThat(Guardrails.redactPii("9138001380001").counts()).doesNotContainKey("phone");
    }

    @Test
    void email_redacted() {
        var r = Guardrails.redactPii("发到 user.name@example.com 吗");
        assertThat(r.text()).isEqualTo("发到 [邮箱] 吗");
        assertThat(r.counts()).containsEntry("email", 1);
    }

    @Test
    void order_id_redacted_case_insensitive() {
        var r = Guardrails.redactPii("订单 ord-AB12cd99 请查");
        assertThat(r.text()).contains("[订单号]");
    }

    @Test
    void order_id_directly_after_chinese_is_not_boundary() {
        // Unicode 词边界含中文:"单ORD-1234567" 中"单O"之间无词边界 → 不匹配。
        // 断言该行为,防止 Java 默认 ASCII \b 语义漂移(必须 UNICODE_CHARACTER_CLASS)。
        var r = Guardrails.redactPii("订单ORD-1234567");
        assertThat(r.text()).isEqualTo("订单ORD-1234567");
        assertThat(r.counts()).doesNotContainKey("order_id");
    }

    @Test
    void grounded_number_passes() {
        assertThat(Guardrails.unsupportedNumericClaims("共 3 笔", List.of(Map.of("count", 3))))
            .isEmpty();
    }

    @Test
    void unsupported_number_reported_sorted() {
        assertThat(Guardrails.unsupportedNumericClaims("花了 999 和 12.5 元", List.of()))
            .containsExactly(12.5, 999.0);
    }

    @Test
    void evidence_from_json_serialized_objects() {
        // 证据对象序列化后其中的数字可支撑声明
        assertThat(Guardrails.unsupportedNumericClaims("合计 250 元", List.of(Map.of("total", 250))))
            .isEmpty();
    }

    @Test
    void list_numbering_not_a_claim() {
        assertThat(Guardrails.unsupportedNumericClaims("1. 第一条\n2. 第二条", List.of())).isEmpty();
    }

    @Test
    void sample_labels_not_a_claim() {
        assertThat(Guardrails.unsupportedNumericClaims("样本 3:某商户连续扣费", List.of())).isEmpty();
    }

    @Test
    void negative_and_decimal_numbers_are_claims() {
        assertThat(Guardrails.unsupportedNumericClaims("变化 -12.5%", List.of()))
            .containsExactly(-12.5);
    }

    @Test
    void rounding_to_6_digits_half_even() {
        // 1.2345675 → HALF_EVEN 6 位 → 1.234568(或 1.234568/1.234567 由偶位决定;断言集合成员一致性)
        var claimed = Guardrails.unsupportedNumericClaims("值 1.234568", List.of("值 1.2345675"));
        // 声明 1.234568 与证据 1.2345675(规整后 1.234568)一致 → 支持
        assertThat(claimed).isEmpty();
    }

    @Test
    void word_adjacent_digits_not_claims() {
        // 前邻字母/数字/下划线的数字是标识符的一部分,不算数字声明
        assertThat(Guardrails.unsupportedNumericClaims("编号 A100 与 x_99", List.of())).isEmpty();
    }
}
