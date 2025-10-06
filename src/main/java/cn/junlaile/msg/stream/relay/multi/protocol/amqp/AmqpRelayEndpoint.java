// AMQP 1.0 Relay Endpoint - 启用测试 v2
package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayEndpointConfig;
import cn.junlaile.msg.stream.relay.multi.protocol.common.Destination;
import cn.junlaile.msg.stream.relay.multi.protocol.common.DestinationParser;
import cn.junlaile.msg.stream.relay.multi.protocol.common.MessageConverter;
import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import cn.junlaile.msg.stream.relay.multi.support.QueueMappingManager;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.proton.*;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.AmqpSequence;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.amqp.transport.Source;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.message.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AMQP 1.0 端点，直接接收 AMQP 客户端消息并转发到 RabbitMQ，
 * 同时支持将 RabbitMQ 队列数据推送回 AMQP 客户端。
 */
@Startup
@ApplicationScoped
public class AmqpRelayEndpoint {

    private static final Logger LOG = Logger.getLogger(AmqpRelayEndpoint.class);
    private static final Symbol ERROR_INVALID_DESTINATION = Symbol.getSymbol("amqp:invalid-field");
    private static final Symbol ERROR_INTERNAL = Symbol.getSymbol("amqp:internal-error");
    private static final String CONNECTION_CONTAINER = "amqp-relay";

    private final Vertx vertx;
    private final RabbitMQClientManager clientManager;
    private final QueueMappingManager queueMappingManager;
    private final AmqpRelayEndpointConfig config;
    private final ConcurrentMap<ProtonConnection, ConnectionContext> connections = new ConcurrentHashMap<>();
    private final AtomicLong outboundSequence = new AtomicLong();

    private volatile ProtonServer server;

    @Inject
    public AmqpRelayEndpoint(Vertx vertx,
                             RabbitMQClientManager clientManager,
                             QueueMappingManager queueMappingManager,
                             AmqpRelayEndpointConfig config) {
        LOG.info("🔧 AmqpRelayEndpoint constructor called - CDI injection working!");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.clientManager = Objects.requireNonNull(clientManager, "clientManager");
        this.queueMappingManager = Objects.requireNonNull(queueMappingManager, "queueMappingManager");
        this.config = Objects.requireNonNull(config, "config");
        LOG.infof("🔧 AmqpRelayEndpoint initialized with config: enabled=%s, host=%s, port=%d",
                 config.enabled(), config.host(), config.port());
    }

    @PostConstruct
    void start() {
        LOG.infof("🚀 AmqpRelayEndpoint starting... enabled: %s, host: %s, port: %d",
                 config.enabled(), config.host(), config.port());

        if (!config.enabled()) {
            LOG.info("❌ AMQP relay endpoint disabled via configuration");
            return;
        }

        try {
            ProtonServer created = ProtonServer.create(vertx);
            created.connectHandler(this::handleConnection);
            created.listen(config.port(), config.host(), result -> {
                if (result.succeeded()) {
                    LOG.infof("✅ AmqpRelayEndpoint listening on %s:%d", config.host(), config.port());
                } else {
                    LOG.error("❌ Failed to start AmqpRelayEndpoint", result.cause());
                }
            });
            server = created;
            LOG.info("📝 AmqpRelayEndpoint server created successfully");
        } catch (Exception e) {
            LOG.error("❌ Exception starting AmqpRelayEndpoint", e);
        }
    }

    @PreDestroy
    void stop() {
        ProtonServer current = server;
        if (current != null) {
            current.close();
        }
        connections.keySet().forEach(this::cleanupConnection);
        connections.clear();
    }

    private void handleConnection(ProtonConnection connection) {
        connection.setContainer(CONNECTION_CONTAINER);
        // vertx-proton connection idle timeout API changed; no direct setter now

        ConnectionContext context = new ConnectionContext();
        connections.put(connection, context);

        connection.sessionOpenHandler(ProtonSession::open);
        connection.receiverOpenHandler(receiver -> onReceiverOpen(connection, context, receiver));
        connection.senderOpenHandler(sender -> onSenderOpen(connection, context, sender));
        connection.closeHandler(closed -> {
            cleanupConnection(connection);
            connection.close();
        });
        connection.disconnectHandler(v -> cleanupConnection(connection));
        connection.open();
    }

    private void onReceiverOpen(ProtonConnection connection,
                                ConnectionContext context,
                                ProtonReceiver receiver) {
        // 回退为：Receiver link = 客户端发送（上行），仅用于接收客户端消息
        receiver.setAutoAccept(false);
        receiver.setTarget(receiver.getRemoteTarget());
        int credits = Math.max(1, config.initialCredits());
        receiver.setPrefetch(credits);
        receiver.flow(credits);
        receiver.open();

        LOG.debugf("[AMQP] receiverOpen local=%s remoteTarget=%s", receiver.getName(),
            receiver.getRemoteTarget() == null ? null : receiver.getRemoteTarget().getAddress());

        receiver.handler((delivery, message) -> handleInboundMessage(receiver, delivery, message));
        receiver.closeHandler(closed -> LOG.debugf("Receiver link closed: %s", receiver.getName()));
        receiver.detachHandler(detached -> LOG.debugf("Receiver link detached: %s", receiver.getName()));
    }

    private void handleInboundMessage(ProtonReceiver receiver,
                                      ProtonDelivery delivery,
                                      Message message) {
        Destination destination = resolveDestination(receiver, message);
        if (destination == null) {
            LOG.warn("Rejecting message without valid destination");
            rejectDelivery(delivery, ERROR_INVALID_DESTINATION, "Missing or invalid destination");
            return;
        }

        Buffer payload = convertBody(message.getBody());
        CompletionStage<Void> publishStage = clientManager.ensureConnected().thenCompose(ignored -> {
            if (destination.isQueue()) {
                return clientManager.publish("", destination.queue(), payload);
            }
            return clientManager.publish(destination.exchange(), destination.routingKey(), payload);
        });

        publishStage.whenComplete((ignored, err) -> {
            if (err != null) {
                LOG.errorf(err, "Failed to publish AMQP message to %s", destination.original());
                rejectDelivery(delivery, ERROR_INTERNAL, err.getMessage());
                return;
            }
            delivery.disposition(Accepted.getInstance(), true);
            receiver.flow(1);
        });
    }

    private void onSenderOpen(ProtonConnection connection,
                              ConnectionContext context,
                              ProtonSender sender) {
        Source remoteSource = sender.getRemoteSource();
        Destination destination = resolveSourceDestination(connection, remoteSource, sender);
        LOG.debugf("[AMQP] senderOpen link=%s remoteSource=%s parsed=%s", sender.getName(),
            remoteSource == null ? null : remoteSource.getAddress(), destination == null ? null : destination.original());
        if (destination == null) {
            ErrorCondition condition = new ErrorCondition(ERROR_INVALID_DESTINATION, "Sender requires address");
            sender.setCondition(condition);
            sender.open();
            sender.close();
            return;
        }

        prepareConsumer(destination, connection, sender).whenComplete((binding, err) -> {
            if (err != null) {
                LOG.errorf(err, "Failed to create consumer for %s", destination.original());
                ErrorCondition condition = new ErrorCondition(ERROR_INTERNAL, err.getMessage());
                sender.setCondition(condition);
                sender.open();
                sender.close();
                return;
            }

            queueMappingManager.incrementConsumer(binding.destination().original());
            context.addSender(sender, binding.destination(), binding.consumer());

            if (remoteSource != null) {
                sender.setSource(remoteSource);
            }
            sender.open();

            sender.closeHandler(closed -> context.removeSender(sender, queueMappingManager));
            sender.detachHandler(detached -> context.removeSender(sender, queueMappingManager));

            binding.consumer().handler(message -> forwardToSender(sender, binding.destination(), message));
        });
    }

    private CompletionStage<ConsumerBinding> prepareConsumer(Destination destination,
                                                             ProtonConnection connection,
                                                             ProtonSender sender) {
        if (destination.isQueue()) {
            return clientManager.ensureConnected()
                .thenCompose(ignored -> clientManager.createQueueConsumer(destination.queue()))
                .thenApply(consumer -> new ConsumerBinding(destination, consumer));
        }

        String uniqueKey = connection.getRemoteContainer();
        if (uniqueKey == null || uniqueKey.isBlank()) {
            uniqueKey = connection.getHostname();
        }
        if (uniqueKey == null || uniqueKey.isBlank()) {
            uniqueKey = UUID.randomUUID().toString();
        }
        uniqueKey = uniqueKey + ":" + sender.getName();

        Source remoteSource = sender.getRemoteSource();
        boolean sharedQueue = isShared(remoteSource);

        QueueMappingManager.QueueMapping mapping;
        if (sharedQueue) {
            mapping = queueMappingManager.resolveQueue(destination.original(), destination.exchange(), destination.routingKey());
        } else {
            mapping = queueMappingManager.resolveQueue(destination.original() + ":" + uniqueKey,
                destination.exchange(), destination.routingKey());
        }

        Destination finalDestination = destination.withQueue(mapping.queueName());

        CompletionStage<Void> readyStage;
        if (mapping.isNew()) {
            readyStage = clientManager.ensureConnected()
                .thenCompose(ignored -> clientManager.declareQueue(mapping.queueName(), false, false, true))
                .thenCompose(ignored -> clientManager.bindQueue(mapping.queueName(), destination.exchange(), destination.routingKey()));
        } else {
            readyStage = clientManager.ensureConnected();
        }

        return readyStage.thenCompose(ignored -> clientManager.createQueueConsumer(mapping.queueName()))
            .thenApply(consumer -> new ConsumerBinding(finalDestination, consumer));
    }

    private boolean isShared(Source source) { // using transport.Source (limited info available)
        return false; // capability detection not supported with transport.Source abstraction currently
        /* if (source == null) {
            return false;
        }
        if (source.getDistributionMode() != null) {
            String mode = source.getDistributionMode().toString();
            if ("move".equalsIgnoreCase(mode)) {
                return true;
            }
        }
        Symbol[] capabilities = source.getCapabilities();
        if (capabilities != null) {
            for (Symbol capability : capabilities) {
                if (capability != null) {
                    String value = capability.toString();
                    if ("shared".equalsIgnoreCase(value) || "global".equalsIgnoreCase(value)) {
                        return true;
                    }
                }
            }
        }
        Map<Symbol, Object> properties = source.getProperties();
        if (properties != null) {
            Object shared = properties.get(Symbol.getSymbol("shared"));
            if (shared instanceof Boolean bool && bool) {
                return true;
            }
        }
        return false; */
    }

    private Destination resolveDestination(ProtonReceiver receiver, Message message) {
        String linkAddress = receiver.getRemoteTarget() != null ? receiver.getRemoteTarget().getAddress() : null;
        String messageAddress = message.getAddress();
        String subject = message.getSubject();
        String replyTo = message.getReplyTo();

        String propertiesAddress = null;
        String queueValue = null;
        String exchangeValue = null;
        String routingKey = null;

        if (message.getApplicationProperties() != null) {
            Map<String, Object> props = message.getApplicationProperties().getValue();
            propertiesAddress = firstString(props, "destination", "address", "to");
            queueValue = firstString(props, "queue", "queueName");
            exchangeValue = firstString(props, "exchange", "exchangeName");
            routingKey = firstString(props, "routingKey", "routing-key", "bindingKey");
        }

        Destination destination = DestinationParser.parseFirst(
            messageAddress,
            propertiesAddress,
            linkAddress,
            queueValue != null ? "/queue/" + queueValue : null,
            buildExchangeAddress(exchangeValue, routingKey),
            subject,
            replyTo
        );

        if (destination == null && queueValue != null) {
            destination = Destination.forQueue("/queue/" + queueValue, queueValue);
        } else if (destination == null && exchangeValue != null) {
            String original = "/exchange/" + exchangeValue;
            if (routingKey != null && !routingKey.isBlank()) {
                original = original + "/" + routingKey;
            }
            destination = Destination.forExchange(original,
                exchangeValue,
                routingKey == null ? "" : routingKey);
        }
        return destination;
    }

    private Destination resolveSourceDestination(ProtonConnection connection,
                                                 Source source,
                                                 ProtonSender sender) {
        if (source == null) {
            return null;
        }
        String address = source.getAddress();
        Destination destination = DestinationParser.parse(address);
        if (destination != null) {
            return destination;
        }
        String dynamicAddress = sender.getName();
        if (dynamicAddress == null || dynamicAddress.isBlank()) {
            dynamicAddress = connection.getRemoteContainer();
        }
        if (dynamicAddress == null || dynamicAddress.isBlank()) {
            dynamicAddress = UUID.randomUUID().toString();
        }
        return DestinationParser.parse(dynamicAddress);
    }

    private void forwardToSender(ProtonSender sender, Destination destination, RabbitMQMessage message) {
        if (!sender.isOpen()) {
            return;
        }
        Message amqp = Message.Factory.create();
        amqp.setMessageId("relay-" + outboundSequence.incrementAndGet());
        amqp.setAddress(destination.original());
        amqp.setBody(new Data(new Binary(MessageConverter.toByteArray(message.body()))));

        if (message.properties() != null && message.properties().getContentType() != null) {
            amqp.setContentType(message.properties().getContentType());
        }

        sender.send(amqp);
    }

    private Buffer convertBody(Section section) {
        switch (section) {
            case null -> {
                return Buffer.buffer();
            }
            case Data data -> {
                Binary binary = data.getValue();
                if (binary == null) {
                    return Buffer.buffer();
                }
                return Buffer.buffer().appendBytes(binary.getArray(), binary.getArrayOffset(), binary.getLength());
            }
            case AmqpValue value -> {
                Object payload = value.getValue();
                if (payload instanceof Binary binary) {
                    return Buffer.buffer().appendBytes(binary.getArray(), binary.getArrayOffset(), binary.getLength());
                }
                if (payload instanceof byte[] bytes) {
                    return Buffer.buffer().appendBytes(bytes);
                }
                if (payload instanceof String text) {
                    return Buffer.buffer(text, StandardCharsets.UTF_8.name());
                }
                if (payload instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) map;
                    return Buffer.buffer(new io.vertx.core.json.JsonObject(cast).encode());
                }
                if (payload instanceof List<?> list) {
                    return Buffer.buffer(new io.vertx.core.json.JsonArray(list).encode());
                }
                return MessageConverter.toVertxBuffer(payload);
            }
            case AmqpSequence sequence -> {
                return Buffer.buffer(new io.vertx.core.json.JsonArray(sequence.getValue()).encode());
            }
            default -> {
            }
        }
        return Buffer.buffer(section.toString());
    }

    private void rejectDelivery(ProtonDelivery delivery, Symbol error, String description) {
        ErrorCondition condition = new ErrorCondition(error, description);
        Rejected rejected = new Rejected();
        rejected.setError(condition);
        delivery.disposition(rejected, true);
    }

    private String buildExchangeAddress(String exchange, String routingKey) {
        if (exchange == null || exchange.isBlank()) {
            return null;
        }
        if (routingKey == null || routingKey.isBlank()) {
            return "/exchange/" + exchange;
        }
        return "/exchange/" + exchange + "/" + routingKey;
    }

    private String firstString(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String str && !str.isBlank()) {
                return str;
            }
        }
        return null;
    }

    private void cleanupConnection(ProtonConnection connection) {
        ConnectionContext context = connections.remove(connection);
        if (context != null) {
            context.close(queueMappingManager);
        }
    }

    private record ConsumerBinding(Destination destination, RabbitMQConsumer consumer) {
    }

    private static final class ConnectionContext {
        private final ConcurrentMap<ProtonSender, ConsumerBinding> outboundLinks = new ConcurrentHashMap<>();

        void addSender(ProtonSender sender, Destination destination, RabbitMQConsumer consumer) {
            outboundLinks.put(sender, new ConsumerBinding(destination, consumer));
        }

        void removeSender(ProtonSender sender, QueueMappingManager queueMappingManager) {
            ConsumerBinding binding = outboundLinks.remove(sender);
            if (binding != null) {
                try {
                    binding.consumer().cancel();
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to cancel consumer for %s", binding.destination().original());
                }
                queueMappingManager.decrementConsumer(binding.destination().original());
            }
        }

        void close(QueueMappingManager queueMappingManager) {
            outboundLinks.keySet().forEach(sender -> removeSender(sender, queueMappingManager));
            outboundLinks.clear();
        }
    }
}
