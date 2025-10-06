package cn.junlaile.msg.stream.relay.multi.support;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueOptionsTest {

    @Test
    void dynamicOptionsMirrorConfiguration() {
        AmqpRelayConfig config = mock(AmqpRelayConfig.class);
        when(config.dynamicQueuePrefix()).thenReturn("dyn-");
        when(config.dynamicQueueDurable()).thenReturn(true);
        when(config.dynamicQueueExclusive()).thenReturn(false);
        when(config.dynamicQueueAutoDelete()).thenReturn(true);
        when(config.dynamicQueueIdleTtlSeconds()).thenReturn(120);

        QueueOptions options = QueueOptions.dynamic(config);

        assertEquals("dyn-", options.queuePrefix());
        assertTrue(options.durable());
        assertFalse(options.exclusive());
        assertTrue(options.autoDelete());
        assertEquals(120_000L, options.idleTimeoutMs());
        assertEquals(QueueOptions.QueueType.DYNAMIC, options.type());
    }

    @Test
    void sharedOptionsApplySharedConfiguration() {
        AmqpRelayConfig config = mock(AmqpRelayConfig.class);
        when(config.sharedQueuePrefix()).thenReturn("shrd.");
        when(config.sharedQueueDurable()).thenReturn(false);
        when(config.autoDeleteShared()).thenReturn(false);
        when(config.sharedIdleTtlSeconds()).thenReturn(45);

        QueueOptions options = QueueOptions.shared(config);

        assertEquals("shrd.", options.queuePrefix());
        assertFalse(options.durable());
        assertFalse(options.autoDelete());
        assertEquals(45_000L, options.idleTimeoutMs());
        assertEquals(QueueOptions.QueueType.SHARED, options.type());
    }
}

