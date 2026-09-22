package io.github.lxyang01.billguard.core;

/** 423:另一会话操作正在进行(跨实例会话锁被占用)。 */
public class LockedException extends RuntimeException {

    public LockedException(String message) {
        super(message);
    }
}
