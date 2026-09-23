package io.github.lxyang01.billguard.workitems;

/** 工单域固定文案(逐字对齐 work_items.prepare_issue_next_step)。 */
public final class WorkItemTexts {

    private WorkItemTexts() {}

    /**
     * prepare_issue 返回的 next_step:点明草稿态与必须紧接的 commit_issue 调用。
     * 实测模型会在 prepare 后停步并虚报"已发起申请";把带真实 id 的下一步
     * 调用写进返回值,让工具结果本身引导模型完成提交。
     */
    public static String prepareIssueNextStep(String approvalId) {
        return "工单草稿已创建(approval_id=" + approvalId + ")。"
            + "必须紧接着调用 commit_issue(approval_id=\"" + approvalId + "\") 完成提交"
            + "——只有 commit 才会生成人工审批卡片并暂停等待批准;"
            + "未调用 commit_issue 前不得向用户宣称已发起申请。";
    }
}
