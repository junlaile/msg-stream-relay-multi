package cn.junlaile.msg.stream.relay.multi.support;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayConfig;

import java.util.Objects;

/**
 * Queue declaration/runtime options derived from configuration or
 * link capabilities.
 */
public record QueueOptions(
    String queuePrefix,
    boolean durable,
    boolean exclusive,
    boolean autoDelete,
    long idleTimeoutMs,
    QueueType type
) {

    public QueueOptions {
        Objects.requireNonNull(queuePrefix, "queuePrefix");
        Objects.requireNonNull(type, "type");
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be >= 0");
        }
    }

    public enum QueueType {
        DYNAMIC,
        SHARED
    }

    public String identityTag() {
        return type.name() + ':' + queuePrefix + ':' + durable + ':' + exclusive + ':' + autoDelete + ':' + idleTimeoutMs;
    }

    public static QueueOptions dynamic(AmqpRelayConfig config) {
        long idleMs = Math.max(0, config.dynamicQueueIdleTtlSeconds()) * 1000L;
        return new QueueOptions(
            config.dynamicQueuePrefix(),
            config.dynamicQueueDurable(),
            config.dynamicQueueExclusive(),
            config.dynamicQueueAutoDelete(),
            idleMs,
            QueueType.DYNAMIC
        );
    }

    public static QueueOptions shared(AmqpRelayConfig config) {
        long idleMs = Math.max(0, config.sharedIdleTtlSeconds()) * 1000L;
        return new QueueOptions(
            config.sharedQueuePrefix(),
            config.sharedQueueDurable(),
            false,
            config.autoDeleteShared(),
            idleMs,
            QueueType.SHARED
        );
    }
}

