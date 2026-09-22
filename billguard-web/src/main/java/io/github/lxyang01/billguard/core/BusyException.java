package io.github.lxyang01.billguard.core;

/** 429:全集群模型并发槽位已满,稍后重试。 */
public class BusyException extends RuntimeException {

    public BusyException(String message) {
        super(message);
    }
}
