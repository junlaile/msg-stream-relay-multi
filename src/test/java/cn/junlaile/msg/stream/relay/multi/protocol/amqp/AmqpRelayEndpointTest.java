package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayEndpointConfig;
import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import cn.junlaile.msg.stream.relay.multi.support.QueueMappingManager;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQMessage;
import jakarta.inject.Inject;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试 AmqpRelayEndpoint，验证发送与接收路径。
 */
@QuarkusTest
class AmqpRelayEndpointTest {

    @Inject
    Vertx vertx;

    @Inject
    AmqpRelayEndpointConfig config;

    @InjectMock
    RabbitMQClientManager clientManager;

    @InjectMock
    QueueMappingManager queueMappingManager;

    @BeforeEach
    void setupMocks() {
        when(clientManager.ensureConnected())
            .thenAnswer(inv -> CompletableFuture.completedFuture(null));
        doNothing().when(queueMappingManager).incrementConsumer(anyString());
        doNothing().when(queueMappingManager).decrementConsumer(anyString());
    }

    @Test
    void sendMessageToQueuePublishesToRabbit() throws Exception {
        when(clientManager.publish(eq(""), anyString(), any()))
            .thenAnswer(inv -> CompletableFuture.completedFuture(null));

        ProtonClient client = ProtonClient.create(vertx);
        CompletableFuture<Void> sendFuture = new CompletableFuture<>();
        AtomicReference<ProtonConnection> connectionRef = new AtomicReference<>();
        AtomicReference<ProtonSender> senderRef = new AtomicReference<>();

        client.connect("localhost", config.port(), result -> {
            if (result.failed()) {
                sendFuture.completeExceptionally(result.cause());
                return;
            }
            ProtonConnection connection = result.result();
            connectionRef.set(connection);
            connection.open();

            ProtonSender sender = connection.createSender("/queue/test.queue");
            senderRef.set(sender);
            sender.openHandler(v -> {
                Message message = Message.Factory.create();
                message.setAddress("/queue/test.queue");
                message.setBody(new org.apache.qpid.proton.amqp.messaging.Data(new Binary("payload".getBytes(StandardCharsets.UTF_8))));
                sender.send(message);
                sendFuture.complete(null);
            });
            sender.open();
        });

        sendFuture.get(5, TimeUnit.SECONDS);

        if (senderRef.get() != null || connectionRef.get() != null) {
            vertx.runOnContext(v -> {
                ProtonSender sender = senderRef.getAndSet(null);
                if (sender != null) {
                    sender.close();
                }
                ProtonConnection connection = connectionRef.getAndSet(null);
                if (connection != null) {
                    connection.close();
                    connection.disconnect();
                }
            });
        }

        verify(clientManager, timeout(2000)).publish(eq(""), eq("test.queue"),
            argThat(buffer -> "payload".equals(buffer.toString("UTF-8"))));
    }

    @Test
    void rabbitMessageForwardedToAmqpClient() throws Exception {
        RabbitMQConsumer consumer = mock(RabbitMQConsumer.class);
        AtomicReference<Handler<RabbitMQMessage>> consumerHandler = new AtomicReference<>();

        when(clientManager.createQueueConsumer(eq("out.queue")))
            .thenReturn(CompletableFuture.completedFuture(consumer));
        when(consumer.handler(any()))
            .thenAnswer(inv -> {
                Handler<RabbitMQMessage> handler = inv.getArgument(0);
                consumerHandler.set(handler);
                return consumer;
            });
        when(consumer.exceptionHandler(any()))
            .thenReturn(consumer);

        ProtonClient client = ProtonClient.create(vertx);
        CompletableFuture<Message> receiveFuture = new CompletableFuture<>();
        AtomicReference<ProtonConnection> connectionRef = new AtomicReference<>();
        AtomicReference<ProtonReceiver> receiverRef = new AtomicReference<>();

        client.connect("localhost", config.port(), result -> {
            if (result.failed()) {
                receiveFuture.completeExceptionally(result.cause());
                return;
            }
            ProtonConnection connection = result.result();
            connectionRef.set(connection);
            connection.open();

            ProtonReceiver receiver = connection.createReceiver("/queue/out.queue");
            receiverRef.set(receiver);
            receiver.openHandler(v -> {});
            receiver.handler((delivery, message) -> {
                receiveFuture.complete(message);
                delivery.disposition(Accepted.getInstance(), true);
            });
            receiver.open();
        });

        verify(clientManager, timeout(2000)).createQueueConsumer("out.queue");

        Handler<RabbitMQMessage> handler = consumerHandler.get();
        assertNotNull(handler);

        RabbitMQMessage rabbitMessage = mock(RabbitMQMessage.class);
        when(rabbitMessage.body()).thenReturn(Buffer.buffer("from-rabbit"));
        com.rabbitmq.client.BasicProperties props = new com.rabbitmq.client.BasicProperties() {
            @Override public String getContentType() { return "text/plain"; }
            @Override public String getContentEncoding() { return null; }
            @Override public java.util.Map<String,Object> getHeaders() { return null; }
            @Override public Integer getDeliveryMode() { return null; }
            @Override public Integer getPriority() { return null; }
            @Override public String getCorrelationId() { return null; }
            @Override public String getReplyTo() { return null; }
            @Override public String getExpiration() { return null; }
            @Override public String getMessageId() { return null; }
            @Override public java.util.Date getTimestamp() { return null; }
            @Override public String getType() { return null; }
            @Override public String getUserId() { return null; }
            @Override public String getAppId() { return null; }
        };
        when(rabbitMessage.properties()).thenReturn(props);

        handler.handle(rabbitMessage);

        Message received = receiveFuture.get(5, TimeUnit.SECONDS);
        assertTrue(received.getBody() instanceof Data);
        Data data = (Data) received.getBody();
        String text = new String(data.getValue().getArray(), data.getValue().getArrayOffset(), data.getValue().getLength(), StandardCharsets.UTF_8);
        assertEquals("from-rabbit", text);

        if (receiverRef.get() != null || connectionRef.get() != null) {
            vertx.runOnContext(v -> {
                ProtonReceiver receiver = receiverRef.getAndSet(null);
                if (receiver != null) {
                    receiver.close();
                }
                ProtonConnection connection = connectionRef.getAndSet(null);
                if (connection != null) {
                    connection.close();
                    connection.disconnect();
                }
            });
        }
    }
}
