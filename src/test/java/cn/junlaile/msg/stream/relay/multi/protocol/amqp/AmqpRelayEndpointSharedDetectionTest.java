package cn.junlaile.msg.stream.relay.multi.protocol.amqp;

import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayConfig;
import cn.junlaile.msg.stream.relay.multi.config.AmqpRelayEndpointConfig;
import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import cn.junlaile.msg.stream.relay.multi.support.QueueMappingManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.Source;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AmqpRelayEndpointSharedDetectionTest {

    @Test
    void detectsSharedUsingDistributionMode() throws Exception {
        EndpointWithRegistry endpoint = newEndpoint(SharedDetectionStrategy.AUTO, true,
            List.of("shared:"),
            List.of("queue-shared", "shared"),
            List.of("copy"));

        org.apache.qpid.proton.amqp.messaging.Source source = new org.apache.qpid.proton.amqp.messaging.Source();
        source.setDistributionMode(Symbol.getSymbol("copy"));

        try {
            assertTrue(invokeIsShared(endpoint.endpoint(), source));
        } finally {
            endpoint.registry().close();
        }
    }

    @Test
    void detectsSharedUsingCapabilitiesSymbol() throws Exception {
        EndpointWithRegistry endpoint = newEndpoint(SharedDetectionStrategy.CAPABILITIES, true,
            List.of("shared:"),
            List.of("shared-subscription"),
            List.of());

        org.apache.qpid.proton.amqp.messaging.Source source = new org.apache.qpid.proton.amqp.messaging.Source();
        source.setCapabilities(Symbol.getSymbol("shared-subscription"));

        try {
            assertTrue(invokeIsShared(endpoint.endpoint(), source));
        } finally {
            endpoint.registry().close();
        }
    }

    @Test
    void fallsBackToAddressHintWhenCapabilitiesMissing() throws Exception {
        EndpointWithRegistry endpoint = newEndpoint(SharedDetectionStrategy.AUTO, true,
            List.of("shared:"),
            List.of(),
            List.of());

        org.apache.qpid.proton.amqp.messaging.Source source = new org.apache.qpid.proton.amqp.messaging.Source();
        source.setAddress("shared:/exchange/demo/demo");

        try {
            assertTrue(invokeIsShared(endpoint.endpoint(), source));
        } finally {
            endpoint.registry().close();
        }
    }

    @Test
    void returnsFalseWhenDetectionDisabled() throws Exception {
        EndpointWithRegistry endpoint = newEndpoint(SharedDetectionStrategy.AUTO, false,
            List.of("shared:"),
            List.of("shared"),
            List.of("copy"));

        org.apache.qpid.proton.amqp.messaging.Source source = new org.apache.qpid.proton.amqp.messaging.Source();
        source.setDistributionMode(Symbol.getSymbol("copy"));
        source.setCapabilities(Symbol.getSymbol("shared"));
        source.setAddress("shared:/exchange/demo/demo");

        try {
            assertFalse(invokeIsShared(endpoint.endpoint(), source));
        } finally {
            endpoint.registry().close();
        }
    }

    private boolean invokeIsShared(AmqpRelayEndpoint endpoint, Source source) throws Exception {
        Method method = AmqpRelayEndpoint.class.getDeclaredMethod("isShared", Source.class);
        method.setAccessible(true);
        return (boolean) method.invoke(endpoint, source);
    }

    private EndpointWithRegistry newEndpoint(SharedDetectionStrategy strategy,
                                             boolean enabled,
                                             List<String> hints,
                                             List<String> capabilitySymbols,
                                             List<String> distributionModes) {
        Vertx vertx = mock(Vertx.class);
        RabbitMQClientManager clientManager = mock(RabbitMQClientManager.class);
        QueueMappingManager queueMappingManager = mock(QueueMappingManager.class);
        when(queueMappingManager.getStats()).thenReturn(new QueueMappingManager.CacheStats(0, 0, 0, 0, 0));

        AmqpRelayEndpointConfig endpointConfig = mock(AmqpRelayEndpointConfig.class);
        when(endpointConfig.enabled()).thenReturn(true);
        when(endpointConfig.host()).thenReturn("localhost");
        when(endpointConfig.port()).thenReturn(5673);
        when(endpointConfig.idleTimeoutSeconds()).thenReturn(60);
        when(endpointConfig.initialCredits()).thenReturn(50);

        AmqpRelayConfig relayConfig = mock(AmqpRelayConfig.class);
        when(relayConfig.semanticMode()).thenReturn(SemanticMode.LEGACY);
        when(relayConfig.dynamicQueuePrefix()).thenReturn("queue-");
        when(relayConfig.dynamicQueueDurable()).thenReturn(false);
        when(relayConfig.dynamicQueueExclusive()).thenReturn(false);
        when(relayConfig.dynamicQueueAutoDelete()).thenReturn(true);
        when(relayConfig.dynamicQueueIdleTtlSeconds()).thenReturn(1800);
        when(relayConfig.sharedQueuePrefix()).thenReturn("shared");
        when(relayConfig.sharedQueueDurable()).thenReturn(false);
        when(relayConfig.autoDeleteShared()).thenReturn(true);
        when(relayConfig.sharedIdleTtlSeconds()).thenReturn(60);
        when(relayConfig.flowMinCreditThreshold()).thenReturn(5);
        when(relayConfig.flowResumeCreditThreshold()).thenReturn(20);
        when(relayConfig.flowBufferSize()).thenReturn(1000);
        when(relayConfig.flowBufferOverflowPolicy()).thenReturn("drop-oldest");

        AmqpRelayConfig.SharedDetectionConfig detectionConfig = mock(AmqpRelayConfig.SharedDetectionConfig.class);
        when(detectionConfig.enabled()).thenReturn(enabled);
        when(detectionConfig.strategy()).thenReturn(strategy);
        when(detectionConfig.addressHints()).thenReturn(hints);
        when(detectionConfig.capabilitySymbols()).thenReturn(capabilitySymbols);
        when(detectionConfig.distributionModes()).thenReturn(distributionModes);
        when(relayConfig.sharedDetection()).thenReturn(detectionConfig);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AmqpRelayEndpoint endpoint = new AmqpRelayEndpoint(vertx, clientManager, queueMappingManager, endpointConfig, relayConfig, meterRegistry);
        return new EndpointWithRegistry(endpoint, meterRegistry);
    }

    private record EndpointWithRegistry(AmqpRelayEndpoint endpoint,
                                        SimpleMeterRegistry registry) {
    }
}
