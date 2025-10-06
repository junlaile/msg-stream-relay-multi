package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

import cn.junlaile.msg.stream.relay.multi.rabbit.QueueAttributes;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 提供持久化队列检测与队列属性解析的通用辅助方法，便于在端点与测试复用。
 */
final class PersistentQueueSupport {

    private PersistentQueueSupport() {
    }

    @SuppressWarnings("unchecked")
    static QueueAttributes extractQueueAttributes(Source source) {
        if (source == null) {
            return QueueAttributes.empty();
        }
        Map<Symbol, Object> properties = (Map<Symbol, Object>) source.getDynamicNodeProperties();
        if (properties == null || properties.isEmpty()) {
            return QueueAttributes.empty();
        }

        QueueAttributes.Builder builder = QueueAttributes.builder();
        builder.messageTtl(coerceToLong(readProperty(properties, "x-message-ttl")));
        builder.maxLength(coerceToLong(readProperty(properties, "x-max-length")));
        builder.maxLengthBytes(coerceToLong(readProperty(properties, "x-max-length-bytes")));
        builder.overflowMode(coerceToString(readProperty(properties, "x-overflow")));
        builder.deadLetterExchange(coerceToString(readProperty(properties, "x-dead-letter-exchange")));
        builder.deadLetterRoutingKey(coerceToString(readProperty(properties, "x-dead-letter-routing-key")));
        builder.maxPriority(coerceToInteger(readProperty(properties, "x-max-priority")));

        Object queueMode = readProperty(properties, "x-queue-mode");
        if (queueMode instanceof CharSequence mode) {
            builder.lazyMode("lazy".equalsIgnoreCase(mode.toString()));
        } else if (queueMode instanceof Boolean bool) {
            builder.lazyMode(bool);
        }

        return builder.build();
    }

    static boolean matchesPersistentPattern(String queueName, List<String> patterns) {
        if (queueName == null || queueName.isBlank()) {
            return false;
        }
        List<String> effectivePatterns = patterns == null ? Collections.emptyList() : patterns;
        if (effectivePatterns.isEmpty()) {
            return true;
        }
        for (String pattern : effectivePatterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String trimmed = pattern.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (!trimmed.contains("*")) {
                if (queueName.equals(trimmed)) {
                    return true;
                }
                continue;
            }

            String regex = buildRegexFromWildcard(trimmed);
            if (Pattern.matches(regex, queueName)) {
                return true;
            }
        }
        return false;
    }

    private static Object readProperty(Map<Symbol, Object> properties, String key) {
        Symbol symbol = Symbol.getSymbol(key);
        Object value = properties.get(symbol);
        if (value != null) {
            return value;
        }
        for (Map.Entry<Symbol, Object> entry : properties.entrySet()) {
            if (entry.getKey() != null && Objects.equals(entry.getKey().toString(), key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Long coerceToLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence sequence) {
            try {
                return Long.parseLong(sequence.toString().trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Integer coerceToInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof CharSequence sequence) {
            try {
                return Integer.parseInt(sequence.toString().trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String coerceToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence sequence) {
            String str = sequence.toString().trim();
            return str.isEmpty() ? null : str;
        }
        return value.toString();
    }

    private static String buildRegexFromWildcard(String pattern) {
        StringBuilder regex = new StringBuilder();
        String[] parts = pattern.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            regex.append(Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                regex.append(".*");
            }
        }
        return regex.toString();
    }
}

