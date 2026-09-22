package io.github.lxyang01.agent.contract;

import java.util.List;

/** 输出章节契约:主名 + 可识别别名(Markdown 标题或 JSON 键)。 */
public record OutputSection(String name, List<String> labels) {
}
