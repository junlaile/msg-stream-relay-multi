package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

/**
 * Strategy for determining whether an AMQP link should be treated as
 * a shared subscription when creating the internal RabbitMQ bindings.
 */
public enum SharedDetectionStrategy {
    /**
     * Try capability-based detection first and fall back to address hints.
     */
    AUTO,
    /**
     * Only rely on AMQP link capabilities and distribution mode metadata.
     */
    CAPABILITIES,
    /**
     * Infer sharing from the address naming conventions.
     */
    ADDRESS_HINT
}
