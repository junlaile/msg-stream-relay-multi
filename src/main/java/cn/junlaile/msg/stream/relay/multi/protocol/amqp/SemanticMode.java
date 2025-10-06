package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

/**
 * Semantic switching for AMQP link interpretation. LEGACY keeps the
 * historical behaviour (client sender = subscribe), STANDARD aligns with
 * AMQP 1.0 expectations (client receiver = subscribe).
 */
public enum SemanticMode {
    LEGACY,
    STANDARD;

    public boolean isLegacy() {
        return this == LEGACY;
    }

    public boolean isStandard() {
        return this == STANDARD;
    }
}

