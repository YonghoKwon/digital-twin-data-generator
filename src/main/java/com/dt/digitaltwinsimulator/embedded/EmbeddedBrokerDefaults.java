package com.dt.digitaltwinsimulator.embedded;

import com.dt.digitaltwinsimulator.broker.BrokerDefaultsProvider;
import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import org.springframework.stereotype.Component;

@Component
public class EmbeddedBrokerDefaults implements BrokerDefaultsProvider {
    private final EmbeddedBrokerProperties properties;
    private final EmbeddedBrokerService broker;

    public EmbeddedBrokerDefaults(EmbeddedBrokerProperties properties, EmbeddedBrokerService broker) {
        this.properties = properties;
        this.broker = broker;
    }

    @Override
    public BrokerSettings defaults(BrokerSettings external) {
        if (!broker.isDefaultTarget()) return external;
        return new BrokerSettings(properties.endpoint("tcp", properties.clientHost(), properties.getCorePort()),
                properties.getUsername(), properties.getPassword(), external.topic());
    }
}

