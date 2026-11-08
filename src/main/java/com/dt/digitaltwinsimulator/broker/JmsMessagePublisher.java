package com.dt.digitaltwinsimulator.broker;

import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class JmsMessagePublisher implements MessagePublisher {
    private final BrokerConnectionService connections;
    private final ActiveMqBrokerProperties properties;

    public JmsMessagePublisher(BrokerConnectionService connections, ActiveMqBrokerProperties properties) {
        this.connections = connections;
        this.properties = properties;
    }

    @Override
    public Publication open(BrokerSettings settings) {
        var target = connections.createConnectionFactory(settings);
        var cache = new CachingConnectionFactory(target);
        cache.setSessionCacheSize(Math.max(1, properties.getSessionCacheSize()));
        cache.setReconnectOnException(false);
        var template = new JmsTemplate(cache);
        template.setPubSubDomain(true);
        return new Publication() {
            @Override
            public void publish(String destination, String body, java.util.Map<String, String> headers) {
                template.send(destination, session -> {
                    var message = session.createTextMessage(body);
                    if (headers != null) {
                        for (var header : headers.entrySet()) {
                            message.setStringProperty(header.getKey(), header.getValue());
                        }
                    }
                    return message;
                });
            }

            @Override
            public void close() {
                try {
                    cache.destroy();
                } finally {
                    target.close();
                }
            }
        };
    }
}
