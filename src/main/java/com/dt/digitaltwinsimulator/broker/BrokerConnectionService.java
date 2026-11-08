package com.dt.digitaltwinsimulator.broker;

import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import jakarta.jms.Connection;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.stereotype.Service;

@Service
public class BrokerConnectionService {
    private final ActiveMqBrokerProperties properties;

    public BrokerConnectionService(ActiveMqBrokerProperties properties) {
        this.properties = properties;
    }

    public BrokerSettings resolve(String url, String user, String pw, String topic) {
        return BrokerSettings.resolve(properties, url, user, pw, topic);
    }

    public ActiveMQConnectionFactory createConnectionFactory(BrokerSettings settings) {
        ActiveMQConnectionFactory factory;
        try {
            factory = new ActiveMQConnectionFactory(settings.url(), settings.user(), settings.pw());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid broker configuration.", e);
        }
        int timeout = Math.max(1, properties.getConnectTimeoutMs());
        factory.setCallTimeout(timeout);
        factory.setCallFailoverTimeout(timeout);
        factory.setInitialConnectAttempts(0);
        factory.setReconnectAttempts(0);
        var connectors = factory.getStaticConnectors();
        if (connectors != null) {
            for (var connector : connectors) {
                connector.getParams().put(TransportConstants.NETTY_CONNECT_TIMEOUT, timeout);
            }
        }
        return factory;
    }

    /** A request-thread connect + close, including authentication and protocol handshake. */
    public void verifyConnection(BrokerSettings settings) {
        try (ActiveMQConnectionFactory factory = createConnectionFactory(settings);
             Connection connection = factory.createConnection()) {
            connection.start();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerConnectionException(e);
        }
    }
}
