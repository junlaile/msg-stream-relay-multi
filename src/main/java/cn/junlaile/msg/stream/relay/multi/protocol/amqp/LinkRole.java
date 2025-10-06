package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

/**
 * Logical role of an AMQP link within the relay once semantic mode is
 * accounted for.
 */
public enum LinkRole {
    /** Client sends messages into RabbitMQ through the relay. */
    INBOUND_PUBLISH,
    /** Client receives messages from RabbitMQ via the relay. */
    OUTBOUND_SUBSCRIBE;
}

