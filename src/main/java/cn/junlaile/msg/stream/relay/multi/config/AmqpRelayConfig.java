package cn.junlaile.msg.stream.relay.multi.config;

import cn.junlaile.msg.stream.relay.multi.protocol.amqp.SemanticMode;
import cn.junlaile.msg.stream.relay.multi.protocol.amqp.SharedDetectionStrategy;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;

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

    @WithName("shared-detection")
    SharedDetectionConfig sharedDetection();

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

    @WithName("persistent")
    PersistentQueueConfig persistent();

    interface SharedDetectionConfig {

        @WithName("enabled")
        @WithDefault("true")
        boolean enabled();

        @WithName("strategy")
        @WithDefault("auto")
        SharedDetectionStrategy strategy();

        @WithName("address-hints")
        @WithDefault("shared:,broadcast:,relay.shared:,amq.relay.shared")
        List<String> addressHints();

        @WithName("capability-symbols")
        @WithDefault("queue-shared,shared,shared-subscription")
        List<String> capabilitySymbols();

        @WithName("distribution-modes")
        @WithDefault("copy,shared")
        List<String> distributionModes();
    }

    interface PersistentQueueConfig {

        @WithName("enabled")
        @WithDefault("true")
        boolean enabled();

        @WithName("name-patterns")
        @WithDefault("*")
        List<String> namePatterns();

        @WithName("default-durable")
        @WithDefault("true")
        boolean defaultDurable();

        @WithName("default-exclusive")
        @WithDefault("false")
        boolean defaultExclusive();

        @WithName("default-auto-delete")
        @WithDefault("false")
        boolean defaultAutoDelete();

        @WithName("allow-attribute-override")
        @WithDefault("true")
        boolean allowAttributeOverride();
    }
}
