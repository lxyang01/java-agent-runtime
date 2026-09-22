package io.github.lxyang01.agent.contract;

import java.util.List;

/** 从用户原话编译出的参数硬约束(如 limit ≤ N),程序性拦截。 */
public record ArgumentConstraint(
    List<String> toolRoles,
    String path,
    String operator,
    int value,
    String source) {
}
