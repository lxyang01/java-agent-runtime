package io.github.lxyang01.billguard.workitems;

/** 工单域错误(400;消息逐字对齐 Python WorkItemError)。 */
public class WorkItemException extends RuntimeException {

    public WorkItemException(String message) {
        super(message);
    }
}
