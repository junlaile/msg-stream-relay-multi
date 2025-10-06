package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

import cn.junlaile.msg.stream.relay.multi.rabbit.QueueAttributes;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistentQueueSupportTest {

    @Test
    void extractQueueAttributesReadsDynamicNodeProperties() {
        Source source = new Source();
        Map<Symbol, Object> properties = new HashMap<>();
        properties.put(Symbol.getSymbol("x-message-ttl"), 60000);
        properties.put(Symbol.getSymbol("x-max-length"), 120L);
        properties.put(Symbol.getSymbol("x-max-length-bytes"), "1024");
        properties.put(Symbol.getSymbol("x-overflow"), "reject-publish");
        properties.put(Symbol.getSymbol("x-dead-letter-exchange"), "dlx.exchange");
        properties.put(Symbol.getSymbol("x-dead-letter-routing-key"), "dlq");
        properties.put(Symbol.getSymbol("x-max-priority"), 5);
        properties.put(Symbol.getSymbol("x-queue-mode"), "lazy");
        source.setDynamicNodeProperties(properties);

        QueueAttributes attributes = PersistentQueueSupport.extractQueueAttributes(source);

        assertFalse(attributes.isEmpty());
        assertEquals(60000L, attributes.messageTtl());
        assertEquals(120L, attributes.maxLength());
        assertEquals(1024L, attributes.maxLengthBytes());
        assertEquals("reject-publish", attributes.overflowMode());
        assertEquals("dlx.exchange", attributes.deadLetterExchange());
        assertEquals("dlq", attributes.deadLetterRoutingKey());
        assertEquals(5, attributes.maxPriority());
        assertTrue(attributes.lazyMode());
    }

    @Test
    void extractQueueAttributesReturnsEmptyWhenNoProperties() {
        Source source = new Source();
        QueueAttributes attributes = PersistentQueueSupport.extractQueueAttributes(source);
        assertTrue(attributes.isEmpty());
    }

    @Test
    void matchesPersistentPatternSupportsWildcards() {
        assertTrue(PersistentQueueSupport.matchesPersistentPattern("orders", List.of("*")));
        assertTrue(PersistentQueueSupport.matchesPersistentPattern("orders.eu", List.of("orders.*")));
        assertTrue(PersistentQueueSupport.matchesPersistentPattern("orders.eu", List.of("orders*")));
        assertFalse(PersistentQueueSupport.matchesPersistentPattern("orders", List.of("inventory*", "log")));
    }

    @Test
    void matchesPersistentPatternTreatsEmptyPatternsAsMatchAll() {
        assertTrue(PersistentQueueSupport.matchesPersistentPattern("anything", List.of()));
        assertTrue(PersistentQueueSupport.matchesPersistentPattern("anything", null));
    }
}

