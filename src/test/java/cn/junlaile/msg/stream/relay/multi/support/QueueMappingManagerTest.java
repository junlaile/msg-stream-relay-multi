package cn.junlaile.msg.stream.relay.multi.support;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayConfig;
import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import cn.junlaile.msg.stream.relay.multi.protocol.amqp.SemanticMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueMappingManagerTest {

    private RabbitMQClientManager clientManager;
    private AmqpRelayConfig config;
    private QueueMappingManager manager;

    @BeforeEach
    void setUp() {
        clientManager = mock(RabbitMQClientManager.class);
        config = mock(AmqpRelayConfig.class);

        when(config.dynamicQueuePrefix()).thenReturn("dyn-");
        when(config.dynamicQueueDurable()).thenReturn(false);
        when(config.dynamicQueueExclusive()).thenReturn(false);
        when(config.dynamicQueueAutoDelete()).thenReturn(true);
        when(config.dynamicQueueIdleTtlSeconds()).thenReturn(1800);

        when(config.sharedQueuePrefix()).thenReturn("shared.");
        when(config.sharedQueueDurable()).thenReturn(false);
        when(config.autoDeleteShared()).thenReturn(false);
        when(config.sharedIdleTtlSeconds()).thenReturn(60);

        when(config.flowMinCreditThreshold()).thenReturn(5);
        when(config.flowResumeCreditThreshold()).thenReturn(20);
        when(config.flowBufferSize()).thenReturn(1000);
        when(config.flowBufferOverflowPolicy()).thenReturn("drop-oldest");

        when(config.semanticMode()).thenReturn(SemanticMode.LEGACY);

        manager = new QueueMappingManager(clientManager, config);
    }

    @Test
    void resolveQueueWithSharedOptionsReusesQueueName() {
        QueueOptions shared = QueueOptions.shared(config);

        QueueMappingManager.QueueMapping first = manager.resolveQueue("dest", "ex", "key", shared);
        assertTrue(first.isNew());
        assertTrue(first.queueName().startsWith("shared."));
        assertEquals(shared.queuePrefix(), first.options().queuePrefix());

        QueueMappingManager.QueueMapping second = manager.resolveQueue("dest", "ex", "key", shared);
        assertFalse(second.isNew());
        assertEquals(first.queueName(), second.queueName());
    }

    @Test
    void resolveQueueDefaultUsesDynamicOptions() {
        QueueMappingManager.QueueMapping mapping = manager.resolveQueue("destA", "exA", "rkA");
        assertTrue(mapping.isNew());
        assertTrue(mapping.queueName().startsWith("dyn-"));
        assertEquals(QueueOptions.QueueType.DYNAMIC, mapping.options().type());

        QueueMappingManager.QueueMapping reuse = manager.resolveQueue("destA", "exA", "rkA");
        assertFalse(reuse.isNew());
        assertEquals(mapping.queueName(), reuse.queueName());
    }
}
