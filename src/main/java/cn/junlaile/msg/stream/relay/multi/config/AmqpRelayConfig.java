package cn.junlaile.msg.stream.relay.multi.config;

import cn.junlaile.msg.stream.relay.multi.protocol.amqp.SemanticMode;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * High level AMQP relay configuration controlling link semantics,
 * queue lifecycle and flow control policies.
 */
@ConfigMapping(prefix = "relay.amqp")
public interface AmqpRelayConfig {

    @WithName("semantic-mode")
    @WithDefault("legacy")
    SemanticMode semanticMode();

    @WithName("dynamic-queue-prefix")
    @WithDefault("queue-")
    String dynamicQueuePrefix();

    @WithName("dynamic-queue-durable")
    @WithDefault("false")
    boolean dynamicQueueDurable();

    @WithName("dynamic-queue-exclusive")
    @WithDefault("false")
    boolean dynamicQueueExclusive();

    @WithName("dynamic-queue-auto-delete")
    @WithDefault("true")
    boolean dynamicQueueAutoDelete();

    @WithName("dynamic-queue-idle-ttl-seconds")
    @WithDefault("1800")
    int dynamicQueueIdleTtlSeconds();

    @WithName("shared-queue-prefix")
    @WithDefault("amq.relay.shared")
    String sharedQueuePrefix();

    @WithName("shared-queue-durable")
    @WithDefault("false")
    boolean sharedQueueDurable();

    @WithName("auto-delete-shared")
    @WithDefault("true")
    boolean autoDeleteShared();

    @WithName("shared-idle-ttl-seconds")
    @WithDefault("60")
    int sharedIdleTtlSeconds();

    @WithName("flow.min-credit-threshold")
    @WithDefault("5")
    int flowMinCreditThreshold();

    @WithName("flow.resume-credit-threshold")
    @WithDefault("20")
    int flowResumeCreditThreshold();

    @WithName("flow.buffer-size")
    @WithDefault("1000")
    int flowBufferSize();

    @WithName("flow.buffer-overflow-policy")
    @WithDefault("drop-oldest")
    String flowBufferOverflowPolicy();
}

