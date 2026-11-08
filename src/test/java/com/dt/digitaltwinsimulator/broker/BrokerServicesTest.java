package com.dt.digitaltwinsimulator.broker;

import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrokerServicesTest {
    @Test
    void resolvesOverridesAndBlankFieldsFromDefaultsWithoutExposingSecrets() {
        var defaults = new ActiveMqBrokerProperties();
        var settings = BrokerSettings.resolve(defaults, "tcp://example:1234", "user", "private", "destination");
        assertThat(settings.url()).isEqualTo("tcp://example:1234");
        assertThat(settings.user()).isEqualTo("user");
        assertThat(settings.pw()).isEqualTo("private");
        assertThat(settings.topic()).isEqualTo("destination");
        assertThat(settings.toString()).doesNotContain("private", "example", "user=");
        assertThat(BrokerSettings.resolve(defaults, null, " ", null, "")).isEqualTo(new BrokerSettings(
                defaults.getBrokerUrl(), defaults.getUsername(), defaults.getPassword(), defaults.getTopic()));
    }

    @Test
    void appliesTimeoutToTransportAndProtocolAndDisablesRetries() {
        var properties = new ActiveMqBrokerProperties();
        properties.setConnectTimeoutMs(456);
        var service = new BrokerConnectionService(properties);
        try (var factory = service.createConnectionFactory(service.resolve("tcp://127.0.0.1:1", null, null, null))) {
            assertThat(factory.getCallTimeout()).isEqualTo(456);
            assertThat(factory.getCallFailoverTimeout()).isEqualTo(456);
            assertThat(factory.getInitialConnectAttempts()).isZero();
            assertThat(factory.getReconnectAttempts()).isZero();
            assertThat(factory.getStaticConnectors()[0].getParams()).containsEntry("connect-timeout-millis", 456);
        }
    }

    @Test
    void connectionProbeStartsAndClosesConnectionAndFactory() throws Exception {
        var service = spy(new BrokerConnectionService(new ActiveMqBrokerProperties()));
        var factory = mock(ActiveMQConnectionFactory.class);
        var connection = mock(Connection.class);
        doReturn(factory).when(service).createConnectionFactory(any());
        when(factory.createConnection()).thenReturn(connection);
        service.verifyConnection(service.resolve(null, null, null, null));
        verify(connection).start();
        verify(connection).close();
        verify(factory).close();
    }

    @Test
    void failedProbeClosesFactoryAndWrapsConnectionFailure() throws Exception {
        var service = spy(new BrokerConnectionService(new ActiveMqBrokerProperties()));
        var factory = mock(ActiveMQConnectionFactory.class);
        doReturn(factory).when(service).createConnectionFactory(any());
        when(factory.createConnection()).thenThrow(new JMSException("not authorized"));
        assertThatThrownBy(() -> service.verifyConnection(service.resolve(null, null, null, null)))
                .isInstanceOf(BrokerConnectionException.class).hasCauseInstanceOf(JMSException.class);
        verify(factory).close();
    }

    @Test
    void publisherSendsTextAndStringHeadersAndClosesResources() throws Exception {
        var connections = mock(BrokerConnectionService.class);
        var factory = mock(ActiveMQConnectionFactory.class);
        var connection = mock(Connection.class);
        var session = mock(Session.class);
        var topic = mock(Topic.class);
        var producer = mock(MessageProducer.class);
        var text = mock(TextMessage.class);
        when(connections.createConnectionFactory(any())).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(session.createTopic("topic.test")).thenReturn(topic);
        when(session.createProducer(topic)).thenReturn(producer);
        when(session.createTextMessage("body")).thenReturn(text);
        when(producer.getDeliveryMode()).thenReturn(DeliveryMode.PERSISTENT);
        when(producer.getPriority()).thenReturn(4);

        new JmsMessagePublisher(connections, new ActiveMqBrokerProperties()).publish(
                new BrokerSettings("tcp://unused:1", "u", "p", "topic.test"), "topic.test", "body", Map.of("traceId", "trace-1"));

        verify(text).setStringProperty("traceId", "trace-1");
        verify(producer).send(text, DeliveryMode.PERSISTENT, 4, 0L);
        verify(connection).close();
        verify(factory).close();
    }
}
