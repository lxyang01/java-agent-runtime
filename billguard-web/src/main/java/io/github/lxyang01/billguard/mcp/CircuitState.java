package io.github.lxyang01.billguard.mcp;

/**
 * 单服务器熔断状态机:closed → open(冷却)→ half_open(一次探测)→ closed。
 * 状态迁移只在持锁临界区内发生;重连期间的 I/O 与退避睡眠在锁外执行
 * (recovering 标志让并发调用者快速失败而非阻塞等待)。对齐 Python _CircuitState。
 */
final class CircuitState {

    enum Phase { CLOSED, OPEN, HALF_OPEN }

    volatile Phase phase = Phase.CLOSED;
    volatile double openedAt;      // open 起始时刻(秒,单调)
    volatile double halfOpenAt;    // 进入 half_open 的时刻(过期兜底用)
    volatile boolean recovering;   // 有界重连或半开探测正在进行
}
