package cn.junlaile.msg.stream.relay.multi.protocol.common;

import java.util.Objects;

/**
 * 通用的目标地址解析器
 * 支持多种地址格式的解析，包括队列和交换机
 */
public class DestinationParser {

    /**
     * 解析目标地址字符串
     *
     * @param raw 原始地址字符串
     * @return 解析后的目标地址对象，如果无法解析则返回 null
     */
    public static Destination parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = normalizePrefix(value);

        // 解析 /queue/ 格式
        if (value.startsWith("/queue/")) {
            String queue = value.substring("/queue/".length());
            return queue.isEmpty() ? null : Destination.forQueue(raw, queue);
        }

        // 解析 /exchange/ 格式
        if (value.startsWith("/exchange/")) {
            String remainder = value.substring("/exchange/".length());
            if (remainder.isEmpty()) {
                return null;
            }
            int slash = remainder.indexOf('/');
            String exchange;
            String routingKey;
            if (slash >= 0) {
                exchange = remainder.substring(0, slash);
                routingKey = remainder.substring(slash + 1);
            } else {
                exchange = remainder;
                routingKey = "";
            }
            if (exchange.isEmpty()) {
                return null;
            }
            return Destination.forExchange(raw, exchange, routingKey);
        }

        // 默认作为队列处理
        String queue = value.startsWith("/") ? value.substring(1) : value;
        return queue.isEmpty() ? null : Destination.forQueue(raw, queue);
    }

    /**
     * 标准化地址前缀
     * 将各种协议前缀转换为统一的格式
     *
     * @param source 原始地址字符串
     * @return 标准化后的地址字符串
     */
    private static String normalizePrefix(String source) {
        String value = source;

        // 移除 AMQP 协议前缀
        if (value.startsWith("amqp://")) {
            value = value.substring("amqp://".length());
        }

        // 标准化队列前缀
        if (value.startsWith("queue://")) {
            value = "/queue/" + value.substring("queue://".length());
        } else if (value.startsWith("queue:/")) {
            value = "/queue/" + value.substring("queue:/".length());
        } else if (value.startsWith("queue:")) {
            value = "/queue/" + value.substring("queue:".length());
        }

        // 标准化交换机前缀
        if (value.startsWith("exchange://")) {
            value = "/exchange/" + value.substring("exchange://".length());
        } else if (value.startsWith("exchange:/")) {
            value = "/exchange/" + value.substring("exchange:/".length());
        } else if (value.startsWith("exchange:")) {
            value = "/exchange/" + value.substring("exchange:".length());
        }

        // 确保以 / 开头
        if (!value.startsWith("/")) {
            value = "/" + value;
        }

        return value;
    }

    /**
     * 从多个可能的地址字符串中选择第一个有效的
     *
     * @param addresses 地址字符串数组
     * @return 第一个成功解析的目标地址，如果都无法解析则返回 null
     */
    public static Destination parseFirst(String... addresses) {
        if (addresses == null) {
            return null;
        }
        for (String address : addresses) {
            Destination destination = parse(address);
            if (destination != null) {
                return destination;
            }
        }
        return null;
    }

    /**
     * 获取可选字符串值，优先返回主键的值
     *
     * @param primary 主键值
     * @param fallback 备用值
     * @return 非空且非空白的字符串，优先返回主键值
     */
    public static String optionalString(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private DestinationParser() {
        // 工具类，不允许实例化
    }
}
