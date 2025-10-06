package cn.junlaile.msg.stream.relay.multi.rabbit;

import io.vertx.core.json.JsonObject;

/**
 * RabbitMQ 队列属性封装，便于在声明队列时传递扩展参数。
 */
public final class QueueAttributes {

    private static final QueueAttributes EMPTY = new QueueAttributes(null, null, null, null, null, null, null, null);

    private final Long messageTtl;
    private final Long maxLength;
    private final Long maxLengthBytes;
    private final String overflowMode;
    private final String deadLetterExchange;
    private final String deadLetterRoutingKey;
    private final Integer maxPriority;
    private final Boolean lazyMode;

    private QueueAttributes(Long messageTtl,
                             Long maxLength,
                             Long maxLengthBytes,
                             String overflowMode,
                             String deadLetterExchange,
                             String deadLetterRoutingKey,
                             Integer maxPriority,
                             Boolean lazyMode) {
        this.messageTtl = messageTtl;
        this.maxLength = maxLength;
        this.maxLengthBytes = maxLengthBytes;
        this.overflowMode = overflowMode;
        this.deadLetterExchange = deadLetterExchange;
        this.deadLetterRoutingKey = deadLetterRoutingKey;
        this.maxPriority = maxPriority;
        this.lazyMode = lazyMode;
    }

    public static QueueAttributes empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return this == EMPTY
            || (messageTtl == null
            && maxLength == null
            && maxLengthBytes == null
            && overflowMode == null
            && deadLetterExchange == null
            && deadLetterRoutingKey == null
            && maxPriority == null
            && (lazyMode == null || !lazyMode));
    }

    public Long messageTtl() {
        return messageTtl;
    }

    public Long maxLength() {
        return maxLength;
    }

    public Long maxLengthBytes() {
        return maxLengthBytes;
    }

    public String overflowMode() {
        return overflowMode;
    }

    public String deadLetterExchange() {
        return deadLetterExchange;
    }

    public String deadLetterRoutingKey() {
        return deadLetterRoutingKey;
    }

    public Integer maxPriority() {
        return maxPriority;
    }

    public Boolean lazyMode() {
        return lazyMode;
    }

    /**
     * 转换为 RabbitMQ `x-*` 参数表示。
     */
    public JsonObject toArguments() {
        JsonObject arguments = new JsonObject();
        if (messageTtl != null) {
            arguments.put("x-message-ttl", messageTtl);
        }
        if (maxLength != null) {
            arguments.put("x-max-length", maxLength);
        }
        if (maxLengthBytes != null) {
            arguments.put("x-max-length-bytes", maxLengthBytes);
        }
        if (overflowMode != null && !overflowMode.isBlank()) {
            arguments.put("x-overflow", overflowMode);
        }
        if (deadLetterExchange != null && !deadLetterExchange.isBlank()) {
            arguments.put("x-dead-letter-exchange", deadLetterExchange);
        }
        if (deadLetterRoutingKey != null && !deadLetterRoutingKey.isBlank()) {
            arguments.put("x-dead-letter-routing-key", deadLetterRoutingKey);
        }
        if (maxPriority != null) {
            arguments.put("x-max-priority", maxPriority);
        }
        if (Boolean.TRUE.equals(lazyMode)) {
            arguments.put("x-queue-mode", "lazy");
        }
        return arguments;
    }

    @Override
    public String toString() {
        return "QueueAttributes{" +
            "ttl=" + messageTtl +
            ", maxLength=" + maxLength +
            ", maxLengthBytes=" + maxLengthBytes +
            ", overflow='" + overflowMode + '\'' +
            ", dlx='" + deadLetterExchange + '\'' +
            ", dlrk='" + deadLetterRoutingKey + '\'' +
            ", maxPriority=" + maxPriority +
            ", lazy=" + lazyMode +
            '}';
    }

    public static final class Builder {
        private Long messageTtl;
        private Long maxLength;
        private Long maxLengthBytes;
        private String overflowMode;
        private String deadLetterExchange;
        private String deadLetterRoutingKey;
        private Integer maxPriority;
        private Boolean lazyMode;

        public Builder messageTtl(Long messageTtl) {
            this.messageTtl = messageTtl;
            return this;
        }

        public Builder maxLength(Long maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        public Builder maxLengthBytes(Long maxLengthBytes) {
            this.maxLengthBytes = maxLengthBytes;
            return this;
        }

        public Builder overflowMode(String overflowMode) {
            this.overflowMode = overflowMode;
            return this;
        }

        public Builder deadLetterExchange(String deadLetterExchange) {
            this.deadLetterExchange = deadLetterExchange;
            return this;
        }

        public Builder deadLetterRoutingKey(String deadLetterRoutingKey) {
            this.deadLetterRoutingKey = deadLetterRoutingKey;
            return this;
        }

        public Builder maxPriority(Integer maxPriority) {
            this.maxPriority = maxPriority;
            return this;
        }

        public Builder lazyMode(Boolean lazyMode) {
            this.lazyMode = lazyMode;
            return this;
        }

        public QueueAttributes build() {
            if (messageTtl == null
                && maxLength == null
                && maxLengthBytes == null
                && (overflowMode == null || overflowMode.isBlank())
                && (deadLetterExchange == null || deadLetterExchange.isBlank())
                && (deadLetterRoutingKey == null || deadLetterRoutingKey.isBlank())
                && maxPriority == null
                && (lazyMode == null || !lazyMode)) {
                return QueueAttributes.empty();
            }
            return new QueueAttributes(messageTtl,
                maxLength,
                maxLengthBytes,
                overflowMode,
                deadLetterExchange,
                deadLetterRoutingKey,
                maxPriority,
                lazyMode);
        }
    }
}
