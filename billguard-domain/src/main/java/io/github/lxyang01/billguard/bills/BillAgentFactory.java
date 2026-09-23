package io.github.lxyang01.billguard.bills;

import io.github.lxyang01.agent.engine.AgentRuntime;
import io.github.lxyang01.agent.engine.AgentSpec;
import io.github.lxyang01.agent.llm.LlmClient;
import io.github.lxyang01.agent.skill.SkillRuntime;
import io.github.lxyang01.agent.store.ConversationStore;
import io.github.lxyang01.agent.store.TraceWriter;
import io.github.lxyang01.agent.tool.ToolRegistry;
import io.github.lxyang01.billguard.skills.ClasspathSkillSource;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import java.time.Duration;
import java.util.List;

/** 账单守卫 Agent 工厂:12 条系统指令逐字对齐 agents/bills.py BILL_AGENT_SPEC。 */
public final class BillAgentFactory {

    public static final String AGENT_NAME = "BillGuard 账单守卫助手";

    public static final String INSTRUCTIONS = """
        你是账单守卫助手。你的职责是基于工具返回的真实数据，帮助用户看清支出结构、发现异常扣费并给出可执行的处理建议。

        必须遵守：
        1. 所有金额、笔数、比例、趋势和排名必须来自工具结果，不得猜测。
        2. 分析异常先查总览了解支出结构，再调用异常检测定位；需要解释原因时，读取最多 20 条已脱敏样本。
        3. 回答要区分“数据事实”和“分析推测”，推测不能写成确定结论。
        4. 查询具体交易优先使用已知类别、商户和支付方式；全文检索使用能出现在原文中的短关键词，不要把“订阅涨价”等结论名称直接当成内容原句。
        5. 查询或样本结果为空时，根据工具的 retry_hint 改用类别、商户或更短关键词重试一次；仍为空才报告证据不足。
        6. 判断订阅是否涨价以 subscriptions.expected_amount 为基准；没有商户公告、账单明细或复现证据时禁止写“已确认根因”。
        7. 原始账单可能包含敏感信息，只能使用工具返回的脱敏样本。
        8. 给出简洁、可执行的处理建议并说明证据范围。
        9. 用户要求取消/退订/不续费订阅或退款时,这是你的本职能力:调用 work-items.prepare_issue 创建工单(标题写清目标订阅),再调用 work-items.commit_issue 提交;系统会自动暂停等待人工审批,批准后才会真正执行。prepare_issue 仅创建草稿,必须紧接着调用 commit_issue 提交(否则用户看不到审批卡片);在调用 commit_issue 之前,禁止对用户说“已发起申请/已提交/等待审批”一类话术。不要拒绝用户,也不要让用户自行联系客服或去商户设置操作——发起工单就是你处理这类请求的正确方式。若 work-items 工具不在可用列表,如实说明当前未接入工单服务、需以 MCP 模式启动并接入 Work Item 服务后才能发起审批,同样不要建议用户自行操作。
        10. 用户要求标记或更新交易核查状态时,先用搜索定位相关交易,再调用 bill.update_status(工具可用时)。
        11. 不执行任意 SQL、Shell、文件修改或外部网络请求。
        12. 时间表述必须以工具返回的日期区间与当前日期为基准;所查月份无数据时,用工具返回的数据覆盖范围(如 data_from/data_to)如实说明“数据只覆盖 X 到 Y”,禁止编造年份或区间,禁止把数据里的最新月份称作“本月”。""";

    private BillAgentFactory() {}

    /** MCP 模式:工具目录来自注入后的注册表(owner 身份已强制);审批走 PG 仓。 */
    public static AgentRuntime createMcpAgent(
        io.github.lxyang01.agent.llm.LlmClient llm, Duration runTimeout,
        io.github.lxyang01.agent.store.ConversationStore conversations,
        io.github.lxyang01.agent.store.TraceWriter traceWriter,
        io.github.lxyang01.agent.store.ApprovalStore approvals,
        io.github.lxyang01.agent.tool.ToolRegistry registry) {
        AgentSpec spec = AgentSpec.builder(AGENT_NAME, INSTRUCTIONS, registry.names())
            .maxSteps(8)
            .runTimeout(runTimeout)
            .build();
        return AgentRuntime.builder(spec, llm, registry, conversations, traceWriter)
            .skills(new SkillRuntime(new io.github.lxyang01.billguard.skills.ClasspathSkillSource()))
            .approvals(approvals)
            .build();
    }

    /** 本地模式:五工具按 owner 受限装配(查询自动过滤本人数据)。 */
    public static AgentRuntime createLocalAgent(LlmClient llm, Duration runTimeout,
                                                 ConversationStore conversations,
                                                 TraceWriter traceWriter,
                                                 BillRepository bills,
                                                 BillAnomalies anomalies, String owner) {
        ToolRegistry registry = BillTools.build(bills, anomalies, owner);
        AgentSpec spec = AgentSpec.builder(AGENT_NAME, INSTRUCTIONS,
                List.of("bill_overview", "bill_compare", "bill_anomalies", "bill_search",
                    "bill_samples"))
            .maxSteps(8)
            .runTimeout(runTimeout)
            .build();
        return AgentRuntime.builder(spec, llm, registry, conversations, traceWriter)
            .skills(new SkillRuntime(new ClasspathSkillSource()))
            .build();
    }
}
