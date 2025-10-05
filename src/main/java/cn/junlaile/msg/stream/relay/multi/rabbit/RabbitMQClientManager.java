package cn.junlaile.msg.stream.relay.multi.rabbit;

import cn.junlaile.msg.stream.relay.multi.config.RabbitMQConfig;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理 RabbitMQ 客户端的生命周期和连接
 * 提供连接健康监控和自动重连功能
 */
@ApplicationScoped
public class RabbitMQClientManager {

    private static final Logger LOG = Logger.getLogger(RabbitMQClientManager.class);

    private final RabbitMQClient client;
    private final RabbitMQConfig config;
    private final AtomicBoolean connectionHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulOperation = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong reconnectAttempts = new AtomicLong(0);

    /**
     * 构造 RabbitMQ 客户端管理器，初始化连接配置
     *
     * @param vertx Vert.x 实例，用于创建异步客户端
     * @param config RabbitMQ 连接配置对象
     */
    @Inject
    public RabbitMQClientManager(Vertx vertx, RabbitMQConfig config) {
        this.config = config;
        RabbitMQOptions options = new RabbitMQOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setUser(config.username())
                .setPassword(config.password())
                .setVirtualHost(config.virtualHost())
                .setAutomaticRecoveryEnabled(config.automaticRecovery())
                .setRequestedHeartbeat(config.requestedHeartbeatSeconds())
                .setNetworkRecoveryInterval(config.networkRecoveryIntervalSeconds() * 1000L)
                .setSsl(config.ssl());
        options.setReconnectAttempts(Integer.MAX_VALUE);
        options.setReconnectInterval(2000);
        this.client = RabbitMQClient.create(vertx, options);
    }

    /**
     * 应用启动时自动执行，建立与 RabbitMQ 的连接
     * 连接成功时记录日志，失败时记录错误
     */
    @PostConstruct
    void start() {
        client.start()
            .onSuccess(ignored -> {
                connectionHealthy.set(true);
                lastSuccessfulOperation.set(System.currentTimeMillis());
                reconnectAttempts.set(0);
                LOG.infof("Connected to RabbitMQ at %s:%d", config.host(), config.port());
            })
            .onFailure(err -> {
                connectionHealthy.set(false);
                reconnectAttempts.incrementAndGet();
                LOG.errorf(err, "Unable to connect to RabbitMQ at %s:%d (attempt %d)",
                    config.host(), config.port(), reconnectAttempts.get());
            });
    }

    /**
     * 应用关闭时自动执行，断开与 RabbitMQ 的连接
     * 释放相关资源
     */
    @PreDestroy
    void stop() {
        if (client != null && client.isConnected()) {
            client.stop();
        }
    }

    /**
     * 获取底层的 RabbitMQ 客户端实例
     *
     * @return RabbitMQ 客户端对象
     */
    public RabbitMQClient getClient() {
        return client;
    }

    /**
     * 确保 RabbitMQ 客户端已连接
     * 如果已连接则立即返回，否则尝试建立连接
     *
     * @return 异步操作结果，连接成功时完成
     */
    public CompletionStage<Void> ensureConnected() {
        if (client.isConnected()) {
            lastSuccessfulOperation.set(System.currentTimeMillis());
            return CompletableFuture.completedFuture(null);
        }

        connectionHealthy.set(false);
        LOG.warnf("RabbitMQ connection lost, attempting to reconnect (attempt %d)", reconnectAttempts.incrementAndGet());

        return client.start()
            .toCompletionStage()
            .whenComplete((ignored, err) -> {
                if (err == null) {
                    connectionHealthy.set(true);
                    lastSuccessfulOperation.set(System.currentTimeMillis());
                    LOG.infof("Successfully reconnected to RabbitMQ after %d attempts", reconnectAttempts.get());
                    reconnectAttempts.set(0);
                } else {
                    connectionHealthy.set(false);
                    LOG.errorf(err, "Failed to reconnect to RabbitMQ (attempt %d)", reconnectAttempts.get());
                }
            });
    }

    /**
     * 为指定队列创建消费者
     *
     * @param queue 队列名称，不能为 null
     * @return 异步操作结果，成功时返回消费者对象
     * @throws NullPointerException 如果 queue 参数为 null
     */
    public CompletionStage<RabbitMQConsumer> createQueueConsumer(String queue) {
        Objects.requireNonNull(queue, "queue");
        Promise<RabbitMQConsumer> promise = Promise.promise();
        client.basicConsumer(queue, promise);
        return promise.future()
            .toCompletionStage()
            .whenComplete((consumer, err) -> {
                if (err == null) {
                    lastSuccessfulOperation.set(System.currentTimeMillis());
                    LOG.debugf("Created consumer for queue: %s", queue);
                } else {
                    LOG.errorf(err, "Failed to create consumer for queue: %s", queue);
                }
            });
    }

    /**
     * 向指定交换机发布消息
     *
     * @param exchange 交换机名称，空字符串表示默认交换机
     * @param routingKey 路由键，用于消息路由
     * @param body 消息体内容
     * @return 异步操作结果，发布成功时完成
     */
    public CompletionStage<Void> publish(String exchange, String routingKey, Buffer body) {
        Promise<Void> promise = Promise.promise();
        client.basicPublish(exchange, routingKey, body, promise);
        return promise.future()
            .toCompletionStage()
            .whenComplete((ignored, err) -> {
                if (err == null) {
                    lastSuccessfulOperation.set(System.currentTimeMillis());
                } else {
                    LOG.errorf(err, "Failed to publish to exchange: %s, routing key: %s", exchange, routingKey);
                }
            });
    }

    /**
     * 声明一个队列
     *
     * @param queue 队列名称，不能为 null
     * @param durable 是否持久化，true 表示队列在 broker 重启后仍然存在
     * @param exclusive 是否独占，true 表示只有创建连接可以使用此队列
     * @param autoDelete 是否自动删除，true 表示最后一个消费者断开后自动删除
     * @return 异步操作结果，声明成功时完成
     * @throws NullPointerException 如果 queue 参数为 null
     */
    public CompletionStage<Void> declareQueue(String queue, boolean durable, boolean exclusive, boolean autoDelete) {
        Objects.requireNonNull(queue, "queue");
        return client.queueDeclare(queue, durable, exclusive, autoDelete)
                .toCompletionStage()
                .thenApply(json -> null);
    }

    /**
     * 将队列绑定到指定的交换机
     *
     * @param queue 队列名称，不能为 null
     * @param exchange 交换机名称，不能为 null
     * @param routingKey 绑定的路由键，可以为 null（将被转换为空字符串）
     * @return 异步操作结果，绑定成功时完成
     * @throws NullPointerException 如果 queue 或 exchange 参数为 null
     */
    public CompletionStage<Void> bindQueue(String queue, String exchange, String routingKey) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(exchange, "exchange");
        return client.queueBind(queue, exchange, Objects.requireNonNullElse(routingKey, ""))
                .toCompletionStage()
                .thenApply(json -> null);
    }

    /**
     * 删除指定的队列
     *
     * @param queue 队列名称，不能为 null
     * @return 异步操作结果，删除成功时完成
     * @throws NullPointerException 如果 queue 参数为 null
     */
    public CompletionStage<Void> deleteQueue(String queue) {
        Objects.requireNonNull(queue, "queue");
        return client.queueDelete(queue)
                .toCompletionStage()
                .thenApply(json -> {
                    lastSuccessfulOperation.set(System.currentTimeMillis());
                    return (Void) null;
                })
                .exceptionally(err -> {
                    LOG.errorf(err, "Failed to delete queue: %s", queue);
                    return (Void) null;
                });
    }

    /**
     * 检查连接健康状态
     *
     * @return true 如果连接健康
     */
    public boolean isConnectionHealthy() {
        return connectionHealthy.get();
    }

    /**
     * 获取最后一次成功操作的时间戳
     *
     * @return 时间戳（毫秒）
     */
    public long getLastSuccessfulOperationTime() {
        return lastSuccessfulOperation.get();
    }

    /**
     * 获取重连尝试次数
     *
     * @return 重连次数
     */
    public long getReconnectAttempts() {
        return reconnectAttempts.get();
    }
}
