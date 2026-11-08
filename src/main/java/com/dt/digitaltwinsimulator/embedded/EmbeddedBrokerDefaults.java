package com.dt.digitaltwinsimulator.embedded;

import com.dt.digitaltwinsimulator.broker.BrokerDefaultsProvider;
import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import org.springframework.stereotype.Component;

@Component
public class EmbeddedBrokerDefaults implements BrokerDefaultsProvider {
    private final EmbeddedBrokerProperties properties;

    public EmbeddedBrokerDefaults(EmbeddedBrokerProperties properties) { this.properties = properties; }

    @Override
    public BrokerSettings defaults(BrokerSettings external) {
        if (!properties.isEnabled() || !properties.isUseAsDefault()) return external;
        return new BrokerSettings(properties.endpoint("tcp", properties.clientHost(), properties.getCorePort()),
                properties.getUsername(), properties.getPassword(), external.topic());
    }
}

