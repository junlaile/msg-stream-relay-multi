package cn.junlaile.msg.stream.relay.multi.protocol.common;

import java.util.Objects;

/**
 * 通用的目标地址表示，支持队列和交换机两种类型
 * 用于统一处理不同协议的消息路由目标
 */
public class Destination {

    private final Type type;
    private final String original;
    private final String queue;
    private final String exchange;
    private final String routingKey;

    /**
     * 目标类型枚举
     */
    public enum Type {
        /** 队列类型 */
        QUEUE,
        /** 交换机类型 */
        EXCHANGE
    }

    private Destination(Type type, String original, String queue, String exchange, String routingKey) {
        this.type = Objects.requireNonNull(type, "type");
        this.original = original;
        this.queue = queue;
        this.exchange = exchange;
        this.routingKey = routingKey == null ? "" : routingKey;
    }

    /**
     * 创建队列类型的目标地址
     *
     * @param original 原始地址字符串
     * @param queue 队列名称
     * @return 目标地址对象
     */
    public static Destination forQueue(String original, String queue) {
        Objects.requireNonNull(queue, "queue");
        return new Destination(Type.QUEUE, original, queue, null, "");
    }

    /**
     * 创建交换机类型的目标地址
     *
     * @param original 原始地址字符串
     * @param exchange 交换机名称
     * @param routingKey 路由键
     * @return 目标地址对象
     */
    public static Destination forExchange(String original, String exchange, String routingKey) {
        Objects.requireNonNull(exchange, "exchange");
        return new Destination(Type.EXCHANGE, original, null, exchange,
                routingKey == null ? "" : routingKey);
    }

    /**
     * 获取目标类型
     *
     * @return 目标类型
     */
    public Type type() {
        return type;
    }

    /**
     * 获取原始地址字符串
     *
     * @return 原始地址
     */
    public String original() {
        return original;
    }

    /**
     * 获取队列名称（仅当类型为 QUEUE 时有效）
     *
     * @return 队列名称，如果不是队列类型则返回 null
     */
    public String queue() {
        return queue;
    }

    /**
     * 获取交换机名称（仅当类型为 EXCHANGE 时有效）
     *
     * @return 交换机名称，如果不是交换机类型则返回 null
     */
    public String exchange() {
        return exchange;
    }

    /**
     * 获取路由键（仅当类型为 EXCHANGE 时有效）
     *
     * @return 路由键，如果不是交换机类型则返回空字符串
     */
    public String routingKey() {
        return routingKey;
    }

    /**
     * 判断是否为队列类型
     *
     * @return true 如果是队列类型
     */
    public boolean isQueue() {
        return type == Type.QUEUE;
    }

    /**
     * 判断是否为交换机类型
     *
     * @return true 如果是交换机类型
     */
    public boolean isExchange() {
        return type == Type.EXCHANGE;
    }

    /**
     * 创建一个新的 Destination，替换队列名称
     * 如果当前是交换机类型，则设置队列名称；如果是队列类型，则保持不变
     *
     * @param queueName 新的队列名称
     * @return 新的 Destination 对象
     */
    public Destination withQueue(String queueName) {
        if (type == Type.EXCHANGE) {
            return new Destination(Type.EXCHANGE, original, queueName, exchange, routingKey);
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Destination that = (Destination) o;
        return type == that.type &&
                Objects.equals(original, that.original) &&
                Objects.equals(queue, that.queue) &&
                Objects.equals(exchange, that.exchange) &&
                Objects.equals(routingKey, that.routingKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, original, queue, exchange, routingKey);
    }

    @Override
    public String toString() {
        return "Destination{" +
                "type=" + type +
                ", original='" + original + '\'' +
                ", queue='" + queue + '\'' +
                ", exchange='" + exchange + '\'' +
                ", routingKey='" + routingKey + '\'' +
                '}';
    }
}
