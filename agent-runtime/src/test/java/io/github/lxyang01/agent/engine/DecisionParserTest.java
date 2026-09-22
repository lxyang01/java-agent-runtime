package io.github.lxyang01.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 逐分支对齐 Python parser.parse_decision 的语义。 */
class DecisionParserTest {

    @Test
    void plain_tool_call() {
        var d = DecisionParser.parse(
            "{\"thought\":\"查\",\"tool_call\":{\"name\":\"bill.query\",\"arguments\":{\"limit\":5}}}");
        assertThat(d).isInstanceOf(ToolCallDecision.class);
        var c = (ToolCallDecision) d;
        assertThat(c.thought()).isEqualTo("查");
        assertThat(c.tool()).isEqualTo("bill.query");
        assertThat(c.arguments()).containsEntry("limit", 5);
    }

    @Test
    void fenced_json_preferred_over_surrounding_noise() {
        var d = DecisionParser.parse("好的,结果如下:\n```json\n{\"final\":\"答案\"}\n```\n以上。");
        assertThat(d).isInstanceOf(FinalDecision.class);
        assertThat(((FinalDecision) d).answer()).isEqualTo("答案");
    }

    @Test
    void leading_noise_lenient_from_first_brace() {
        var d = DecisionParser.parse("我认为:{\"final\":\"答\"}");
        assertThat(((FinalDecision) d).answer()).isEqualTo("答");
    }

    @Test
    void tool_calls_array_takes_first() {
        var d = DecisionParser.parse(
            "{\"tool_calls\":[{\"name\":\"t1\",\"arguments\":{}},{\"name\":\"t2\",\"arguments\":{}}]}");
        assertThat(d).isInstanceOf(ToolCallDecision.class);
        assertThat(((ToolCallDecision) d).tool()).isEqualTo("t1");
    }

    @Test
    void openai_function_form_with_string_arguments() {
        var d = DecisionParser.parse(
            "{\"tool_call\":{\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"a\\\":1}\"}}}");
        var c = (ToolCallDecision) d;
        assertThat(c.tool()).isEqualTo("t");
        assertThat(c.arguments()).containsEntry("a", 1);
    }

    @Test
    void tool_call_precedence_over_final() {
        var d = DecisionParser.parse(
            "{\"tool_call\":{\"name\":\"t\",\"arguments\":{}},\"final\":\"early\"}");
        assertThat(d).isInstanceOf(ToolCallDecision.class);
    }

    @Test
    void answer_alias_treated_as_final() {
        var d = DecisionParser.parse("{\"thought\":\"\",\"answer\":\"好\"}");
        assertThat(((FinalDecision) d).answer()).isEqualTo("好");
    }

    @Test
    void non_string_final_dumped_as_pretty_json() {
        var d = DecisionParser.parse("{\"final\":{\"a\":1}}");
        assertThat(((FinalDecision) d).answer()).contains("\"a\" : 1").contains("{").contains("}");
    }

    @Test
    void bare_object_without_protocol_keys_is_final() {
        var d = DecisionParser.parse("{\"a\":1,\"b\":2}");
        assertThat(((FinalDecision) d).answer()).contains("\"a\"");
    }

    @Test
    void array_of_results_is_structured_final() {
        var d = DecisionParser.parse("[{\"a\":1},{\"b\":2}]");
        assertThat(((FinalDecision) d).answer()).contains("\"a\"").contains("\"b\"");
    }

    @Test
    void array_with_protocol_keys_takes_first() {
        var d = DecisionParser.parse("[{\"final\":\"x\"},{\"final\":\"y\"}]");
        assertThat(((FinalDecision) d).answer()).isEqualTo("x");
    }

    @Test
    void empty_array_is_invalid() {
        assertThatThrownBy(() -> DecisionParser.parse("[]"))
            .isInstanceOf(DecisionParseException.class);
    }

    @Test
    void thought_only_object_is_invalid() {
        // {"thought":"x"} 是协议控制字段对象,不能当裸 final
        assertThatThrownBy(() -> DecisionParser.parse("{\"thought\":\"x\"}"))
            .isInstanceOf(DecisionParseException.class);
    }

    @Test
    void garbage_throws_with_contract_message() {
        assertThatThrownBy(() -> DecisionParser.parse("完全不是 JSON"))
            .isInstanceOf(DecisionParseException.class)
            .hasMessage("LLM output must contain exactly one valid tool_call or final answer");
    }
}
