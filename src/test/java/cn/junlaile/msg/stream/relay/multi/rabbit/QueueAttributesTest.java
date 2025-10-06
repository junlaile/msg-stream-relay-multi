package cn.junlaile.msg.stream.relay.multi.rabbit;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueueAttributesTest {

    @Test
    void builderProducesExpectedArgumentMap() {
        QueueAttributes attributes = QueueAttributes.builder()
            .messageTtl(1000L)
            .maxLength(50L)
            .deadLetterExchange("dlx")
            .deadLetterRoutingKey("dlq")
            .lazyMode(true)
            .build();

        JsonObject arguments = attributes.toArguments();
        assertEquals(1000L, arguments.getLong("x-message-ttl"));
        assertEquals(50L, arguments.getLong("x-max-length"));
        assertEquals("dlx", arguments.getString("x-dead-letter-exchange"));
        assertEquals("dlq", arguments.getString("x-dead-letter-routing-key"));
        assertEquals("lazy", arguments.getString("x-queue-mode"));
        assertFalse(arguments.containsKey("x-max-length-bytes"));
    }

    @Test
    void emptyReturnsSharedInstance() {
        QueueAttributes empty = QueueAttributes.empty();
        assertTrue(empty.isEmpty());
        assertTrue(empty.toArguments().isEmpty());
        assertSame(empty, QueueAttributes.empty());
    }
}

