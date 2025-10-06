// AMQP 1.0 Relay Endpoint - 启用测试 v2
package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayConfig;
import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayEndpointConfig;
import cn.junlaile.msg.stream.relay.multi.protocol.common.Destination;
import cn.junlaile.msg.stream.relay.multi.protocol.common.DestinationParser;
import cn.junlaile.msg.stream.relay.multi.protocol.common.MessageConverter;
import cn.junlaile.msg.stream.relay.multi.protocol.amqp.LinkRole;
import cn.junlaile.msg.stream.relay.multi.protocol.amqp.SemanticMode;
import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import cn.junlaile.msg.stream.relay.multi.support.QueueMappingManager;
import cn.junlaile.msg.stream.relay.multi.support.QueueOptions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.amqp.transport.Source;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.message.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final Symbol ERROR_NOT_FOUND = Symbol.getSymbol("amqp:not-found");
    private static final Symbol ERROR_UNAUTHORIZED = Symbol.getSymbol("amqp:unauthorized-access");
    private static final Symbol ERROR_QUEUE_BIND_FAILED = Symbol.getSymbol("relay:queue-bind-failed");
    private static final Symbol ERROR_UNSUPPORTED_LINK_ROLE = Symbol.getSymbol("relay:unsupported-link-role");
    private static final String CONNECTION_CONTAINER = "amqp-relay";

    private final Vertx vertx;
    private final RabbitMQClientManager clientManager;
    private final QueueMappingManager queueMappingManager;
    private final AmqpRelayEndpointConfig config;
    private final AmqpRelayConfig relayConfig;
    private final FlowSettings flowSettings;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, DestinationMetrics> destinationMetrics = new ConcurrentHashMap<>();
    private final ConcurrentMap<ProtonConnection, ConnectionContext> connections = new ConcurrentHashMap<>();
    private final AtomicLong outboundSequence = new AtomicLong();

    private volatile ProtonServer server;

    @Inject
    public AmqpRelayEndpoint(Vertx vertx,
                             RabbitMQClientManager clientManager,
                             QueueMappingManager queueMappingManager,
                             AmqpRelayEndpointConfig config,
                             AmqpRelayConfig relayConfig,
                             MeterRegistry meterRegistry) {
        LOG.info("🔧 AmqpRelayEndpoint constructor called - CDI injection working!");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.clientManager = Objects.requireNonNull(clientManager, "clientManager");
        this.queueMappingManager = Objects.requireNonNull(queueMappingManager, "queueMappingManager");
        this.config = Objects.requireNonNull(config, "config");
        this.relayConfig = Objects.requireNonNull(relayConfig, "relayConfig");
        this.flowSettings = FlowSettings.from(relayConfig);
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        registerGauges();
        LOG.infof("🔧 AmqpRelayEndpoint initialized with config: enabled=%s, host=%s, port=%d",
                config.enabled(), config.host(), config.port());
        LOG.infof("🔧 AMQP relay semantic-mode=%s, flow[min=%d,resume=%d,buffer=%d,overflow=%s]",
                relayConfig.semanticMode(),
                flowSettings.minCreditThreshold,
                flowSettings.resumeCreditThreshold,
                flowSettings.bufferSize,
                flowSettings.overflowPolicy);
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
        SemanticMode semanticMode = relayConfig.semanticMode();
        LinkRole role = LinkRole.INBOUND_PUBLISH;
        // Receiver link = 客户端发送（上行），标准语义下仍用于上行 publish
        receiver.setAutoAccept(false);
        receiver.setTarget(receiver.getRemoteTarget());
        int credits = Math.max(1, config.initialCredits());
        receiver.setPrefetch(credits);
        receiver.flow(credits);
        receiver.open();

        LOG.debugf("[AMQP][mode=%s][role=%s] receiverOpen local=%s remoteTarget=%s",
            semanticMode,
            role,
            receiver.getName(),
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
                rejectDelivery(delivery, mapPublishError(err));
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
        SemanticMode semanticMode = relayConfig.semanticMode();
        LinkRole role = LinkRole.OUTBOUND_SUBSCRIBE;
        LOG.debugf("[AMQP][mode=%s][role=%s] senderOpen link=%s remoteSource=%s parsed=%s", semanticMode,
            role,
            sender.getName(), remoteSource == null ? null : remoteSource.getAddress(),
            destination == null ? null : destination.original());
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
                ErrorCondition condition = mapConsumerBindingError(err);
                sender.setCondition(condition);
                sender.open();
                sender.close();
                return;
            }

            if (binding.mapping() != null) {
                queueMappingManager.incrementConsumer(binding.mapping().cacheKey());
            }
            FlowController flowController = new FlowController(sender, binding);
            context.addSender(sender, new OutboundLink(binding, flowController));

            if (remoteSource != null) {
                sender.setSource(remoteSource);
            }
            sender.sendQueueDrainHandler(v -> flowController.onDrain());
            flowController.onCredit(sender.getCredit());
            sender.open();

            sender.closeHandler(closed -> context.removeSender(sender, queueMappingManager));
            sender.detachHandler(detached -> context.removeSender(sender, queueMappingManager));

            binding.consumer().handler(message -> flowController.onBrokerMessage(message));
            binding.consumer().exceptionHandler(t -> LOG.errorf(t,
                "Rabbit consumer error for %s (queue=%s)",
                binding.destination().original(),
                binding.destination().queue()));
        });
    }

    private CompletionStage<ConsumerBinding> prepareConsumer(Destination destination,
                                                             ProtonConnection connection,
                                                             ProtonSender sender) {
        if (destination.isQueue()) {
            return clientManager.ensureConnected()
                .thenCompose(ignored -> clientManager.createQueueConsumer(destination.queue()))
                .thenApply(consumer -> new ConsumerBinding(destination, consumer, null));
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

        QueueOptions options = sharedQueue
            ? queueMappingManager.defaultSharedQueueOptions()
            : queueMappingManager.defaultDynamicQueueOptions();
        String mappingKey = sharedQueue
            ? destination.original()
            : destination.original() + ":" + uniqueKey;

        QueueMappingManager.QueueMapping mapping = queueMappingManager.resolveQueue(
            mappingKey,
            destination.exchange(),
            destination.routingKey(),
            options
        );

        Destination finalDestination = destination.withQueue(mapping.queueName());

        CompletionStage<Void> readyStage;
        if (mapping.isNew()) {
            QueueOptions queueOptions = mapping.options();
            readyStage = clientManager.ensureConnected()
                .thenCompose(ignored -> clientManager.declareQueue(
                    mapping.queueName(),
                    queueOptions.durable(),
                    queueOptions.exclusive(),
                    queueOptions.autoDelete()))
                .thenCompose(ignored -> clientManager.bindQueue(
                    mapping.queueName(),
                    destination.exchange(),
                    destination.routingKey()));
        } else {
            readyStage = clientManager.ensureConnected();
        }

        return readyStage.thenCompose(ignored -> clientManager.createQueueConsumer(mapping.queueName()))
            .thenApply(consumer -> new ConsumerBinding(finalDestination, consumer, mapping));
    }

    private boolean isShared(Source source) {
        if (source == null) {
            return false;
        }

        // In Proton-J 0.34.1, the transport.Source class doesn't have
        // getDistributionMode(), getCapabilities(), and getProperties() methods.
        // These methods were added in later versions.
        //
        // For now, we'll use a simplified implementation that can be enhanced
        // later based on actual requirements and testing with AMQP 1.0 clients.

        // TODO: Implement proper shared detection based on:
        // 1. Upgrade to newer Proton-J version that supports these methods
        // 2. Or use alternative approach like parsing link names or addresses
        // 3. Or use Vert.x Proton's higher-level APIs if available

        return false; // Default to non-shared for now
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

    private Message buildOutboundMessage(Destination destination, RabbitMQMessage message) {
        Message amqp = Message.Factory.create();
        amqp.setMessageId("relay-" + outboundSequence.incrementAndGet());
        amqp.setAddress(destination.original());
        amqp.setBody(new Data(new Binary(MessageConverter.toByteArray(message.body()))));

        if (message.properties() != null && message.properties().getContentType() != null) {
            amqp.setContentType(message.properties().getContentType());
        }
        return amqp;
    }

    private void forwardToSender(ProtonSender sender, Destination destination, RabbitMQMessage message) {
        if (!sender.isOpen()) {
            return;
        }
        sender.send(buildOutboundMessage(destination, message));
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

    private void rejectDelivery(ProtonDelivery delivery, ErrorCondition condition) {
        if (condition == null) {
            condition = new ErrorCondition(ERROR_INTERNAL, null);
        }
        rejectDelivery(delivery, condition.getCondition(), condition.getDescription());
    }

    private ErrorCondition mapConsumerBindingError(Throwable err) {
        Symbol symbol = resolveErrorSymbol(err, ERROR_QUEUE_BIND_FAILED);
        return new ErrorCondition(symbol, safeErrorDescription(err));
    }

    private ErrorCondition mapPublishError(Throwable err) {
        Symbol symbol = resolveErrorSymbol(err, ERROR_INTERNAL);
        return new ErrorCondition(symbol, safeErrorDescription(err));
    }

    private Symbol resolveErrorSymbol(Throwable err, Symbol defaultSymbol) {
        if (err == null) {
            return defaultSymbol;
        }
        Throwable root = rootCause(err);
        String message = root.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("not found")
                || normalized.contains("no queue")
                || normalized.contains("no exchange")
                || normalized.contains("404")) {
                return ERROR_NOT_FOUND;
            }
            if (normalized.contains("access refused")
                || normalized.contains("access-refused")
                || normalized.contains("unauthorized")
                || normalized.contains("permission")) {
                return ERROR_UNAUTHORIZED;
            }
            if (normalized.contains("bind") && normalized.contains("fail")) {
                return ERROR_QUEUE_BIND_FAILED;
            }
        }
        return defaultSymbol;
    }

    private String safeErrorDescription(Throwable err) {
        Throwable root = rootCause(err);
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    private Throwable rootCause(Throwable err) {
        Throwable current = err;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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

    private void registerGauges() {
        meterRegistry.gauge("amqp_relay_active_subscriptions", this, endpoint -> endpoint.computeActiveSubscriptions());
        meterRegistry.gauge("amqp_relay_rabbitmq_bindings", queueMappingManager,
            manager -> {
                QueueMappingManager.CacheStats stats = manager.getStats();
                return (double) stats.totalQueues();
            });
    }

    private double computeActiveSubscriptions() {
        return connections.values().stream()
            .mapToInt(ConnectionContext::subscriptionCount)
            .sum();
    }

    private DestinationMetrics resolveMetrics(Destination destination) {
        String key = metricKey(destination);
        return destinationMetrics.computeIfAbsent(key, k -> createMetrics(destination));
    }

    private DestinationMetrics createMetrics(Destination destination) {
        Tags tags = metricTagsFor(destination);
        Counter outbound = meterRegistry.counter("amqp_relay_outbound_messages_total", tags);
        Counter dropped = meterRegistry.counter("amqp_relay_dropped_messages_total", tags);
        Counter backpressure = meterRegistry.counter("amqp_relay_backpressure_events_total", tags);
        return new DestinationMetrics(outbound, dropped, backpressure);
    }

    private Tags metricTagsFor(Destination destination) {
        String mode = relayConfig.semanticMode().name().toLowerCase(Locale.ROOT);
        if (destination.isQueue()) {
            return Tags.of(
                "mode", mode,
                "type", "queue",
                "queue", safeTagValue(destination.queue()),
                "exchange", "",
                "routingKey", ""
            );
        }
        return Tags.of(
            "mode", mode,
            "type", "exchange",
            "queue", "",
            "exchange", safeTagValue(destination.exchange()),
            "routingKey", safeTagValue(destination.routingKey())
        );
    }

    private String metricKey(Destination destination) {
        if (destination.isQueue()) {
            return "queue:" + safeTagValue(destination.queue());
        }
        return "exchange:" + safeTagValue(destination.exchange()) + ':' + safeTagValue(destination.routingKey());
    }

    private String safeTagValue(String value) {
        return value == null ? "" : value;
    }

    private record ConsumerBinding(Destination destination,
                                   RabbitMQConsumer consumer,
                                   QueueMappingManager.QueueMapping mapping) {
    }

    private static final class OutboundLink {
        private final ConsumerBinding binding;
        private final FlowController flowController;

        OutboundLink(ConsumerBinding binding, FlowController flowController) {
            this.binding = binding;
            this.flowController = flowController;
        }

        void close(QueueMappingManager queueMappingManager) {
            flowController.close();
            try {
                binding.consumer().cancel();
            } catch (Exception e) {
                LOG.warnf(e, "Failed to cancel consumer for %s", binding.destination().original());
            }
            if (binding.mapping() != null) {
                queueMappingManager.decrementConsumer(binding.mapping().cacheKey());
            }
        }
    }

    private static final class ConnectionContext {
        private final ConcurrentMap<ProtonSender, OutboundLink> outboundLinks = new ConcurrentHashMap<>();

        void addSender(ProtonSender sender, OutboundLink outboundLink) {
            outboundLinks.put(sender, outboundLink);
        }

        void removeSender(ProtonSender sender, QueueMappingManager queueMappingManager) {
            OutboundLink link = outboundLinks.remove(sender);
            if (link != null) {
                link.close(queueMappingManager);
            }
        }

        void close(QueueMappingManager queueMappingManager) {
            outboundLinks.keySet().forEach(sender -> removeSender(sender, queueMappingManager));
            outboundLinks.clear();
        }

        int subscriptionCount() {
            return outboundLinks.size();
        }
    }

    private enum BufferOverflowPolicy {
        DROP_OLDEST,
        DROP_NEW,
        BLOCK;

        static BufferOverflowPolicy from(String value) {
            if (value == null) {
                return DROP_OLDEST;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "drop-new" -> DROP_NEW;
                case "block" -> BLOCK;
                default -> DROP_OLDEST;
            };
        }
    }

    private static final class FlowSettings {
        final int minCreditThreshold;
        final int resumeCreditThreshold;
        final int bufferSize;
        final BufferOverflowPolicy overflowPolicy;

        private FlowSettings(int minCreditThreshold,
                             int resumeCreditThreshold,
                             int bufferSize,
                             BufferOverflowPolicy overflowPolicy) {
            this.minCreditThreshold = minCreditThreshold;
            this.resumeCreditThreshold = resumeCreditThreshold;
            this.bufferSize = bufferSize;
            this.overflowPolicy = overflowPolicy;
        }

        static FlowSettings from(AmqpRelayConfig config) {
            int min = Math.max(0, config.flowMinCreditThreshold());
            int resume = Math.max(min + 1, config.flowResumeCreditThreshold());
            int buffer = Math.max(1, config.flowBufferSize());
            BufferOverflowPolicy policy = BufferOverflowPolicy.from(config.flowBufferOverflowPolicy());
            return new FlowSettings(min, resume, buffer, policy);
        }
    }

    private static final class DestinationMetrics {
        final Counter outbound;
        final Counter dropped;
        final Counter backpressure;

        DestinationMetrics(Counter outbound, Counter dropped, Counter backpressure) {
            this.outbound = outbound;
            this.dropped = dropped;
            this.backpressure = backpressure;
        }
    }

    private final class FlowController {
        private final ProtonSender sender;
        private final ConsumerBinding binding;
        private final RabbitMQConsumer consumer;
        private final Destination destination;
        private final QueueMappingManager.QueueMapping mapping;
        private final DestinationMetrics metrics;
        private final Deque<RabbitMQMessage> buffer = new ArrayDeque<>();
        private final AtomicBoolean consumerPaused = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private long droppedMessages;
        private long deliveredMessages;
        private long backpressureEvents;

        FlowController(ProtonSender sender, ConsumerBinding binding) {
            this.sender = sender;
            this.binding = binding;
            this.consumer = binding.consumer();
            this.destination = binding.destination();
            this.mapping = binding.mapping();
            this.metrics = resolveMetrics(this.destination);
        }

        void onBrokerMessage(RabbitMQMessage message) {
            if (closed.get()) {
                return;
            }

            boolean added = false;
            RabbitMQMessage dropped = null;
            synchronized (buffer) {
                if (buffer.size() >= flowSettings.bufferSize) {
                    dropped = handleOverflow(message);
                    added = flowSettings.overflowPolicy == BufferOverflowPolicy.DROP_OLDEST;
                } else {
                    buffer.addLast(message);
                    added = true;
                }
            }

            if (dropped != null) {
                LOG.warnf("Dropping message for %s due to buffer overflow (policy=%s, size=%d)",
                        destination.original(), flowSettings.overflowPolicy, flowSettings.bufferSize);
                droppedMessages++;
                metrics.dropped.increment();
            }

            if (added) {
                flush();
            } else {
                adjustConsumerState();
            }
        }

        void onCredit(int credit) {
            if (closed.get()) {
                return;
            }
            flush();
        }

        void onDrain() {
            if (closed.get()) {
                return;
            }
            flush();
        }

        private RabbitMQMessage handleOverflow(RabbitMQMessage incoming) {
            return switch (flowSettings.overflowPolicy) {
                case DROP_OLDEST -> {
                    RabbitMQMessage removed = buffer.pollFirst();
                    buffer.addLast(incoming);
                    yield removed;
                }
                case DROP_NEW -> incoming;
                case BLOCK -> {
                    pauseConsumer("buffer overflow");
                    yield incoming;
                }
            };
        }

        private void flush() {
            if (closed.get()) {
                return;
            }

            synchronized (buffer) {
                while (!buffer.isEmpty() && sender.isOpen() && !sender.sendQueueFull() && sender.getCredit() > 0) {
                    RabbitMQMessage next = buffer.pollFirst();
                    try {
                        sender.send(buildOutboundMessage(destination, next));
                        deliveredMessages++;
                        metrics.outbound.increment();
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to forward message for %s", destination.original());
                        buffer.addFirst(next);
                        break;
                    }
                }
            }

            adjustConsumerState();
        }

        private void adjustConsumerState() {
            if (closed.get()) {
                return;
            }
            int credit = sender.getCredit();
            boolean queueFull = sender.sendQueueFull();
            if (!consumerPaused.get() && (credit <= flowSettings.minCreditThreshold || queueFull)) {
                pauseConsumer(String.format("credit=%d,queueFull=%s", credit, queueFull));
            } else if (consumerPaused.get()
                    && credit >= flowSettings.resumeCreditThreshold
                    && !queueFull
                    && buffer.isEmpty()) {
                resumeConsumer(String.format("credit=%d", credit));
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (buffer) {
                    buffer.clear();
                }
                resumeConsumer("close");
            }
        }

        private void pauseConsumer(String reason) {
            if (consumerPaused.compareAndSet(false, true)) {
                try {
                    consumer.pause();
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to pause RabbitMQ consumer for %s", destination.original());
                }
                backpressureEvents++;
                metrics.backpressure.increment();
                LOG.debugf("Paused RabbitMQ consumer for %s (%s)", destination.original(), reason);
            }
        }

        private void resumeConsumer(String reason) {
            if (consumerPaused.compareAndSet(true, false)) {
                try {
                    consumer.resume();
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to resume RabbitMQ consumer for %s", destination.original());
                }
                LOG.debugf("Resumed RabbitMQ consumer for %s (%s)", destination.original(), reason);
            }
        }

        long droppedMessages() {
            return droppedMessages;
        }

        long deliveredMessages() {
            return deliveredMessages;
        }

        long backpressureEvents() {
            return backpressureEvents;
        }

        QueueMappingManager.QueueMapping mapping() {
            return mapping;
        }
    }
}
