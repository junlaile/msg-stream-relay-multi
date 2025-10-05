package cn.junlaile.msg.stream.relay.multi.stomp;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;
import www.junlaile.cn.msg.stream.rabbit.RabbitMQClientManager;
import www.junlaile.cn.msg.stream.support.QueueMappingManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 端点类，实现 STOMP 协议的中继功能，
 * 将客户端消息转发到 RabbitMQ 或其他通道，
 * 处理连接和消息路由
 */
@ServerEndpoint("/ws")
@ApplicationScoped
public class StompRelayEndpoint {

    private static final Logger LOG = Logger.getLogger(StompRelayEndpoint.class);
    private static final AtomicLong MESSAGE_SEQUENCE = new AtomicLong();

    private final ConcurrentMap<String, RelaySession> sessions = new ConcurrentHashMap<>();

    private final RabbitMQClientManager clientManager;
    private final QueueMappingManager queueMappingManager;

    /**
     * 构造 STOMP 中继端点
     *
     * @param clientManager RabbitMQ 客户端管理器，用于管理与 RabbitMQ 的连接和操作
     * @param queueMappingManager 队列映射管理器，用于管理队列映射关系
     */
    @Inject
    public StompRelayEndpoint(RabbitMQClientManager clientManager, QueueMappingManager queueMappingManager) {
        this.clientManager = clientManager;
        this.queueMappingManager = queueMappingManager;
    }

    /**
     * WebSocket 连接建立时的回调方法
     * 创建新的中继会话并记录日志
     *
     * @param session WebSocket 会话对象
     */
    @OnOpen
    public void onOpen(Session session) {
        sessions.put(session.getId(), new RelaySession(session));
        LOG.debugf("Opened STOMP relay session %s", session.getId());
    }

    /**
     * 接收 WebSocket 消息时的回调方法
     * 解析 STOMP 帧并分发到相应的处理器
     *
     * @param session WebSocket 会话对象
     * @param payload 接收到的文本消息内容
     */
    @OnMessage
    public void onMessage(Session session, String payload) {
        RelaySession state = sessions.get(session.getId());
        if (state == null) {
            LOG.warnf("Received message for unknown session %s", session.getId());
            return;
        }
        List<StompFrame> frames;
        try {
            frames = state.decoder.append(payload);
        } catch (IllegalArgumentException e) {
            sendError(state, "malformed frame", e.getMessage());
            closeSession(state);
            return;
        }
        for (StompFrame frame : frames) {
            handleFrame(state, frame);
        }
    }

    /**
     * WebSocket 连接关闭时的回调方法
     * 清理会话资源并记录日志
     *
     * @param session WebSocket 会话对象
     */
    @OnClose
    public void onClose(Session session) {
        RelaySession state = sessions.remove(session.getId());
        if (state != null) {
            state.cleanup(queueMappingManager);
        }
        LOG.debugf("Closed STOMP relay session %s", session.getId());
    }

    /**
     * WebSocket 发生错误时的回调方法
     * 发送错误消息给客户端并关闭会话
     *
     * @param session WebSocket 会话对象
     * @param error 发生的异常
     */
    @OnError
    public void onError(Session session, Throwable error) {
        RelaySession state = sessions.get(session.getId());
        if (state != null) {
            sendError(state, "internal-error", error.getMessage());
            closeSession(state);
        } else {
            LOG.error("WebSocket error without session", error);
        }
    }

    /**
     * 根据 STOMP 帧的命令类型分发到相应的处理方法
     *
     * @param state 中继会话状态对象
     * @param frame 解析后的 STOMP 帧
     */
    private void handleFrame(RelaySession state, StompFrame frame) {
        switch (frame.command().toUpperCase()) {
            case "CONNECT", "STOMP" -> handleConnect(state, frame);
            case "SUBSCRIBE" -> handleSubscribe(state, frame);
            case "UNSUBSCRIBE" -> handleUnsubscribe(state, frame);
            case "SEND" -> handleSend(state, frame);
            case "DISCONNECT" -> handleDisconnect(state, frame);
            default -> sendError(state, "unsupported-command", "Command " + frame.command() + " is not supported");
        }
    }

    /**
     * 处理 CONNECT/STOMP 命令
     * 建立与 RabbitMQ 的连接并向客户端发送 CONNECTED 帧
     *
     * @param state 中继会话状态对象
     * @param frame CONNECT 或 STOMP 命令帧
     */
    private void handleConnect(RelaySession state, StompFrame frame) {
        clientManager.ensureConnected().whenComplete((ignored, err) -> {
            if (err != null) {
                LOG.error("RabbitMQ connection failed", err);
                sendError(state, "broker-unavailable", "Unable to connect to RabbitMQ: " + err.getMessage());
                closeSession(state);
                return;
            }
            state.connected = true;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("version", Objects.requireNonNullElse(frame.header("accept-version"), "1.2"));
            headers.put("heart-beat", Objects.requireNonNullElse(frame.header("heart-beat"), "0,0"));
            headers.put("server", "quarkus-web-stomp-relay");
            sendFrame(state.session, "CONNECTED", headers, "");
            sendReceiptIfRequested(state, frame);
        });
    }

    /**
     * 处理 SUBSCRIBE 命令
     * 为客户端订阅 RabbitMQ 队列或交换机，并创建消费者
     *
     * @param state 中继会话状态对象
     * @param frame SUBSCRIBE 命令帧，包含 destination 和可选的 id 头部
     */
    private void handleSubscribe(RelaySession state, StompFrame frame) {
        if (!state.connected) {
            sendError(state, "not-connected", "CONNECT frame required before SUBSCRIBE");
            return;
        }
        Destination destination = parseDestination(frame.header("destination"));
        if (destination == null) {
            sendError(state, "invalid-destination", "Supported destinations: /queue/<name> or /exchange/<exchange>/<binding>");
            return;
        }
        final String subscriptionId = Objects.requireNonNullElse(frame.header("id"), destination.original);
        if (state.subscriptions.containsKey(subscriptionId)) {
            sendError(state, "duplicate-subscription", "Subscription id already in use: " + subscriptionId);
            return;
        }

        final Destination initialDestination = destination;
        CompletionStage<RabbitMQConsumer> stage;
        if (initialDestination.isQueue()) {
            stage = clientManager.ensureConnected()
                    .thenCompose(ignored -> clientManager.createQueueConsumer(initialDestination.queue));
        } else {
            // 检查队列模式：broadcast（广播，每个客户端独立队列）或 shared（共享，负载均衡）
            String queueMode = frame.header("x-queue-mode");
            if (queueMode == null) {
                queueMode = frame.header("queue-mode");
            }
            boolean sharedQueue = "shared".equalsIgnoreCase(queueMode) || "load-balance".equalsIgnoreCase(queueMode);

            QueueMappingManager.QueueMapping mapping;
            if (sharedQueue) {
                // 共享模式：多个客户端共享同一队列（负载均衡）
                mapping = queueMappingManager.resolveQueue(
                    initialDestination.original,
                    initialDestination.exchange,
                    initialDestination.routingKey
                );
            } else {
                // 广播模式（默认）：每个订阅独立队列（所有客户端都收到消息）
                String uniqueKey = state.session.getId() + ":" + subscriptionId;
                mapping = queueMappingManager.resolveQueue(
                    initialDestination.original + ":" + uniqueKey,
                    initialDestination.exchange,
                    initialDestination.routingKey
                );
            }

            stage = createExchangeSubscription(state, frame, subscriptionId, initialDestination, mapping);
            destination = initialDestination.withQueue(mapping.queueName());
        }

        Destination finalDestination = destination;
        stage.whenComplete((consumer, err) -> {
            if (err != null) {
                LOG.errorf(err, "Failed to prepare subscription %s", finalDestination.original);
                sendError(state, "subscription-failed", "Cannot subscribe to destination " + finalDestination.original + ": " + err.getMessage());
                return;
            }
            consumer.exceptionHandler(t -> {
                LOG.errorf(t, "Consumer error for subscription %s, queue %s", subscriptionId, finalDestination.queue);
                // 尝试重新创建消费者
                if (state.session.isOpen() && state.subscriptions.containsKey(subscriptionId)) {
                    LOG.infof("Attempting to recover subscription %s", subscriptionId);
                    recoverSubscription(state, subscriptionId, finalDestination);
                }
            });
            consumer.handler(msg -> {
                try {
                    forwardMessage(state, subscriptionId, finalDestination.original, msg);
                } catch (Exception e) {
                    LOG.errorf(e, "Error forwarding message for subscription %s", subscriptionId);
                }
            });
            state.subscriptions.put(subscriptionId, new Subscription(consumer, finalDestination, finalDestination.queue));

            // 增加消费者计数
            queueMappingManager.incrementConsumer(finalDestination.original);

            sendReceiptIfRequested(state, frame);
        });
    }

    /**
     * 为交换机订阅创建队列、绑定并创建消费者
     * 这是交换机订阅的完整流程：确保连接 → 声明队列 → 绑定队列 → 创建消费者
     *
     * @param state 中继会话状态对象
     * @param frame SUBSCRIBE 命令帧
     * @param subscriptionId 订阅标识符
     * @param initialDestination 目标地址对象
     * @param mapping 队列映射信息
     * @return 异步操作结果，成功时返回 RabbitMQ 消费者对象
     */
    private CompletionStage<RabbitMQConsumer> createExchangeSubscription(RelaySession state,
                                                                         StompFrame frame,
                                                                         String subscriptionId,
                                                                         Destination initialDestination,
                                                                         QueueMappingManager.QueueMapping mapping) {
        // 如果是新创建的队列，需要声明并绑定；否则直接创建消费者
        if (mapping.isNew()) {
            return clientManager.ensureConnected()
                    .thenCompose(ignored -> declareQueueForMapping(mapping.queueName()))
                    .thenCompose(ignored -> bindQueueToExchange(mapping.queueName(), initialDestination))
                    .thenCompose(ignored -> createQueueConsumer(mapping.queueName()));
        } else {
            return clientManager.ensureConnected()
                    .thenCompose(ignored -> createQueueConsumer(mapping.queueName()));
        }
    }

    /**
     * 为队列映射声明队列
     * 队列配置：durable=false, exclusive=false, autoDelete=true
     *
     * @param queueName 队列名称
     * @return 异步操作结果，声明成功时完成
     */
    private CompletionStage<Void> declareQueueForMapping(String queueName) {
        return clientManager.declareQueue(queueName, false, false, true);
    }

    /**
     * 将队列绑定到交换机，建立路由规则
     *
     * @param queueName 队列名称
     * @param destination 目标地址对象，包含交换机名称和路由键
     * @return 异步操作结果，绑定成功时完成
     */
    private CompletionStage<Void> bindQueueToExchange(String queueName, Destination destination) {
        return clientManager.bindQueue(
            queueName,
            destination.exchange,
            destination.routingKey
        );
    }

    /**
     * 为指定队列创建 RabbitMQ 消费者
     *
     * @param queueName 队列名称
     * @return 异步操作结果，成功时返回消费者对象
     */
    private CompletionStage<RabbitMQConsumer> createQueueConsumer(String queueName) {
        return clientManager.createQueueConsumer(queueName);
    }

    /**
     * 处理 UNSUBSCRIBE 命令
     * 取消客户端的订阅并停止消费消息
     *
     * @param state 中继会话状态对象
     * @param frame UNSUBSCRIBE 命令帧，包含 id 或 subscription 头部
     */
    private void handleUnsubscribe(RelaySession state, StompFrame frame) {
        String subscriptionId = frame.header("id");
        if (subscriptionId == null) {
            subscriptionId = frame.header("subscription");
        }
        if (subscriptionId == null) {
            sendError(state, "missing-subscription-id", "UNSUBSCRIBE requires id or subscription header");
            return;
        }
        Subscription subscription = state.subscriptions.remove(subscriptionId);
        if (subscription == null) {
            sendError(state, "unknown-subscription", "Subscription not found: " + subscriptionId);
            return;
        }
        subscription.consumer.cancel();

        // 减少消费者计数
        queueMappingManager.decrementConsumer(subscription.destination.original);

        sendReceiptIfRequested(state, frame);
    }

    /**
     * 处理 SEND 命令
     * 将客户端消息发布到 RabbitMQ 队列或交换机
     *
     * @param state 中继会话状态对象
     * @param frame SEND 命令帧，包含 destination 头部和消息体
     */
    private void handleSend(RelaySession state, StompFrame frame) {
        if (!state.connected) {
            sendError(state, "not-connected", "CONNECT frame required before SEND");
            return;
        }
        Destination destination = parseDestination(frame.header("destination"));
        if (destination == null) {
            sendError(state, "invalid-destination", "Supported destinations: /queue/<name> or /exchange/<exchange>/<routing>");
            return;
        }
        Buffer payload = Buffer.buffer(frame.body());
        CompletionStage<Void> publishStage = clientManager.ensureConnected().thenCompose(ignored -> {
            if (destination.isQueue()) {
                return clientManager.publish("", destination.queue, payload);
            }
            return clientManager.publish(destination.exchange, destination.routingKey, payload);
        });
        publishStage.whenComplete((ignored, err) -> {
            if (err != null) {
                LOG.errorf(err, "Failed to publish to %s", destination.original);
                sendError(state, "publish-failed", "Cannot publish to destination " + destination.original + ": " + err.getMessage());
                return;
            }
            sendReceiptIfRequested(state, frame);
        });
    }

    /**
     * 处理 DISCONNECT 命令
     * 断开客户端连接并清理资源
     *
     * @param state 中继会话状态对象
     * @param frame DISCONNECT 命令帧
     */
    private void handleDisconnect(RelaySession state, StompFrame frame) {
        sendReceiptIfRequested(state, frame);
        closeSession(state);
    }

    /**
     * 将 RabbitMQ 消息转发给 STOMP 客户端
     * 将 AMQP 消息转换为 STOMP MESSAGE 帧并发送
     *
     * @param state 中继会话状态对象
     * @param subscriptionId 订阅标识符
     * @param destination 目标地址字符串
     * @param message RabbitMQ 消息对象
     */
    private void forwardMessage(RelaySession state, String subscriptionId, String destination, RabbitMQMessage message) {
        if (!state.session.isOpen()) {
            return;
        }
        String body = Optional.ofNullable(message.body())
                .map(buffer -> buffer.toString(StandardCharsets.UTF_8))
                .orElse("");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("subscription", subscriptionId);
        headers.put("message-id", subscriptionId + "-" + MESSAGE_SEQUENCE.incrementAndGet());
        headers.put("destination", destination);
        if (message.properties() instanceof JsonObject properties && properties.containsKey("contentType")) {
            headers.put("content-type", properties.getString("contentType"));
        } else {
            headers.put("content-type", "text/plain");
        }
        sendFrame(state.session, "MESSAGE", headers, body);
    }

    /**
     * 向客户端发送 STOMP 协议帧
     * 按照 STOMP 协议格式组装帧并通过 WebSocket 发送
     *
     * @param session WebSocket 会话对象
     * @param command STOMP 命令（如 CONNECTED, MESSAGE, ERROR, RECEIPT）
     * @param headers 头部信息键值对，可以为 null
     * @param body 消息体内容，可以为空字符串
     */
    private void sendFrame(Session session, String command, Map<String, String> headers, String body) {
        if (!session.isOpen()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(command).append('\n');
        if (headers != null) {
            headers.forEach((key, value) -> builder.append(key).append(':').append(value).append('\n'));
        }
        builder.append('\n');
        if (!body.isEmpty()) {
            builder.append(body);
        }
        builder.append('\0');
        session.getAsyncRemote().sendText(builder.toString());
    }

    /**
     * 向客户端发送 ERROR 帧
     *
     * @param state 中继会话状态对象
     * @param message 错误消息简述
     * @param details 错误详细信息，可以为 null（将使用 message 作为消息体）
     */
    private void sendError(RelaySession state, String message, String details) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("message", message);
        String body = Objects.requireNonNullElse(details, message);
        sendFrame(state.session, "ERROR", headers, body);
    }

    /**
     * 如果客户端请求了回执，则发送 RECEIPT 帧
     * 检查帧中是否包含 receipt 头部，如果有则发送确认
     *
     * @param state 中继会话状态对象
     * @param frame 客户端发送的 STOMP 帧
     */
    private void sendReceiptIfRequested(RelaySession state, StompFrame frame) {
        String receiptId = frame.header("receipt");
        if (receiptId != null && !receiptId.isEmpty()) {
            Map<String, String> headers = Map.of("receipt-id", receiptId);
            sendFrame(state.session, "RECEIPT", headers, "");
        }
    }

    /**
     * 关闭中继会话并清理资源
     * 取消所有订阅并关闭 WebSocket 连接
     *
     * @param state 中继会话状态对象
     */
    private void closeSession(RelaySession state) {
        try {
            state.cleanup(queueMappingManager);
            if (state.session.isOpen()) {
                state.session.close();
            }
        } catch (IOException e) {
            LOG.warn("Failed to close session", e);
        }
    }

    /**
     * 恢复失效的订阅
     * 当 RabbitMQ 连接断开重连后，消费者会失效，需要重新创建
     *
     * @param state 中继会话状态对象
     * @param subscriptionId 订阅标识符
     * @param destination 目标地址对象
     */
    private void recoverSubscription(RelaySession state, String subscriptionId, Destination destination) {
        Subscription oldSubscription = state.subscriptions.get(subscriptionId);
        if (oldSubscription == null) {
            LOG.warnf("Cannot recover subscription %s: subscription not found", subscriptionId);
            return;
        }

        String queueName = oldSubscription.queueName;
        if (queueName == null || queueName.isEmpty()) {
            LOG.warnf("Cannot recover subscription %s: queue name is empty", subscriptionId);
            return;
        }

        LOG.infof("Recovering subscription %s for queue %s", subscriptionId, queueName);

        clientManager.createQueueConsumer(queueName).whenComplete((consumer, err) -> {
            if (err != null) {
                LOG.errorf(err, "Failed to recover subscription %s", subscriptionId);
                sendError(state, "subscription-recovery-failed",
                    "Failed to recover subscription " + subscriptionId + ": " + err.getMessage());
                return;
            }

            consumer.exceptionHandler(t -> {
                LOG.errorf(t, "Consumer error for recovered subscription %s, queue %s", subscriptionId, queueName);
                // 再次尝试恢复
                if (state.session.isOpen() && state.subscriptions.containsKey(subscriptionId)) {
                    LOG.infof("Re-attempting to recover subscription %s", subscriptionId);
                    recoverSubscription(state, subscriptionId, destination);
                }
            });

            consumer.handler(msg -> {
                try {
                    forwardMessage(state, subscriptionId, destination.original, msg);
                } catch (Exception e) {
                    LOG.errorf(e, "Error forwarding message for recovered subscription %s", subscriptionId);
                }
            });

            // 取消旧的消费者
            try {
                oldSubscription.consumer.cancel();
            } catch (Exception ignored) {
                // 旧消费者可能已经失效
            }

            // 更新订阅记录
            state.subscriptions.put(subscriptionId, new Subscription(consumer, destination, queueName));
            LOG.infof("Successfully recovered subscription %s", subscriptionId);
        });
    }

    /**
     * 解析 STOMP 目标地址字符串
     * 支持两种格式：/queue/name 和 /exchange/name/routingKey
     *
     * @param destination 目标地址字符串
     * @return 解析后的 Destination 对象，如果格式无效则返回 null
     */
    private Destination parseDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        if (destination.startsWith("/queue/")) {
            String queue = destination.substring("/queue/".length());
            return queue.isEmpty() ? null : Destination.forQueue(destination, queue);
        }
        if (destination.startsWith("/exchange/")) {
            String remainder = destination.substring("/exchange/".length());
            if (remainder.isEmpty()) {
                return null;
            }
            int slashIndex = remainder.indexOf('/');
            String exchange;
            String routingKey;
            if (slashIndex >= 0) {
                exchange = remainder.substring(0, slashIndex);
                routingKey = remainder.substring(slashIndex + 1);
            } else {
                exchange = remainder;
                routingKey = "";
            }
            if (exchange.isEmpty()) {
                return null;
            }
            return Destination.forExchange(destination, exchange, routingKey);
        }
        return null;
    }

    /**
     * 生成临时队列名称
     * 格式为 relay.{sessionId}.{subscriptionId}，非法字符替换为连字符
     *
     * @param session WebSocket 会话对象
     * @param subscriptionId 订阅标识符
     * @return 生成的临时队列名称
     */
    private String ephemeralQueueName(Session session, String subscriptionId) {
        String base = "relay." + session.getId() + "." + subscriptionId;
        return base.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    /**
     * 解析队列计划，决定队列的创建和配置策略
     * 根据客户端是否提供队列名称，以及头部参数来确定队列属性
     *
     * @param state 中继会话状态对象
     * @param frame SUBSCRIBE 命令帧
     * @param subscriptionId 订阅标识符
     * @return 队列计划对象，包含队列名称和配置参数
     */
    private QueuePlan resolveQueuePlan(RelaySession state, StompFrame frame, String subscriptionId) {
        String providedQueue = resolveQueueNameFromHeaders(frame);
        boolean userProvided = providedQueue != null;
        String queueName = userProvided ? providedQueue : ephemeralQueueName(state.session, subscriptionId);

        // 默认策略：
        // - 用户提供的队列：持久化，不需要声明（假设已存在），不独占，不自动删除
        // - 临时队列：非持久化，需要声明，独占，自动删除
        boolean declare = parseBooleanHeader(frame, "declare", !userProvided);
        boolean durable = parseBooleanHeader(frame, "durable", userProvided);
        boolean exclusive = parseBooleanHeader(frame, "exclusive", !userProvided);
        boolean autoDelete = parseBooleanHeader(frame, "auto-delete", !userProvided);
        autoDelete = parseBooleanHeader(frame, "auto_delete", autoDelete);

        return new QueuePlan(queueName, declare, durable, exclusive, autoDelete);
    }

    /**
     * 从 STOMP 帧头部中提取用户指定的队列名称
     * 依次尝试多个候选头部：queue, x-queue-name, x_queue_name, rabbitmq.queue
     *
     * @param frame STOMP 帧对象
     * @return 队列名称，如果未找到则返回 null
     */
    private String resolveQueueNameFromHeaders(StompFrame frame) {
        String[] candidateHeaders = {"queue", "x-queue-name", "x_queue_name", "rabbitmq.queue"};
        for (String header : candidateHeaders) {
            String value = frame.header(header);
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    /**
     * 从 STOMP 帧头部中解析布尔值
     * 支持多种布尔值表示：1/true/yes/on 为 true，0/false/no/off 为 false
     *
     * @param frame STOMP 帧对象
     * @param headerName 头部名称
     * @param defaultValue 默认值，当头部不存在或值无效时使用
     * @return 解析后的布尔值
     */
    private boolean parseBooleanHeader(StompFrame frame, String headerName, boolean defaultValue) {
        String value = frame.header(headerName);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    /**
     * 中继会话类
     * 封装单个 WebSocket 连接的状态信息，包括 STOMP 帧解码器、订阅管理和连接状态
     */
    private static final class RelaySession {
        private final Session session;
        private final StompFrameDecoder decoder = new StompFrameDecoder();
        private final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
        private volatile boolean connected;

        /**
         * 创建中继会话
         *
         * @param session WebSocket 会话对象
         */
        private RelaySession(Session session) {
            this.session = session;
        }

        /**
         * 清理会话资源
         * 取消所有订阅的消费者，清空订阅映射，并重置连接状态
         *
         * @param queueMappingManager 队列映射管理器，用于减少消费者计数
         */
        private void cleanup(QueueMappingManager queueMappingManager) {
            subscriptions.forEach((id, subscription) -> {
                try {
                    subscription.consumer.cancel();
                    // 减少消费者计数
                    queueMappingManager.decrementConsumer(subscription.destination.original);
                } catch (Exception ignored) {
                    // best effort
                }
            });
            subscriptions.clear();
            connected = false;
        }
    }

    /**
     * 订阅记录
     * 关联 RabbitMQ 消费者和目标地址，保存队列名称用于连接恢复
     *
     * @param consumer RabbitMQ 消费者对象
     * @param destination 目标地址对象
     * @param queueName RabbitMQ 队列名称，用于连接断开后重新订阅
     */
    private record Subscription(RabbitMQConsumer consumer, Destination destination, String queueName) {}

    /**
     * 队列计划记录
     * 定义队列的创建和配置策略
     *
     * @param queueName 队列名称
     * @param declare 是否需要声明队列（创建队列）
     * @param durable 是否持久化，true 表示队列在 broker 重启后仍然存在
     * @param exclusive 是否独占，true 表示只有创建连接可以使用此队列
     * @param autoDelete 是否自动删除，true 表示最后一个消费者断开后自动删除队列
     */
    private record QueuePlan(String queueName, boolean declare, boolean durable, boolean exclusive, boolean autoDelete) {}

    /**
     * 目标地址类
     * 表示 STOMP 消息的目标地址，可以是队列或交换机
     */
    private static final class Destination {
        enum Type { QUEUE, EXCHANGE }

        private final Type type;
        private final String original;
        private final String queue;
        private final String exchange;
        private final String routingKey;

        private Destination(Type type, String original, String queue, String exchange, String routingKey) {
            this.type = type;
            this.original = original;
            this.queue = queue;
            this.exchange = exchange;
            this.routingKey = Objects.requireNonNullElse(routingKey, "");
        }

        private static Destination forQueue(String original, String queue) {
            return new Destination(Type.QUEUE, original, queue, null, "");
        }

        private static Destination forExchange(String original, String exchange, String routingKey) {
            return new Destination(Type.EXCHANGE, original, null, exchange, routingKey);
        }

        private Destination withQueue(String queueName) {
            if (type == Type.EXCHANGE) {
                return new Destination(Type.EXCHANGE, original, queueName, exchange, routingKey);
            }
            return this;
        }

        private boolean isQueue() {
            return type == Type.QUEUE;
        }

        private boolean isExchange() {
            return type == Type.EXCHANGE;
        }
    }
}
