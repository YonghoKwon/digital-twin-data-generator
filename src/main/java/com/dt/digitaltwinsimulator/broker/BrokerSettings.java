package com.dt.digitaltwinsimulator.broker;

import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;

/** Resolved per-request broker settings. Never include credentials in logs. */
public record BrokerSettings(String url, String user, String pw, String topic) {
    public static BrokerSettings resolve(ActiveMqBrokerProperties defaults, String url, String user, String pw, String topic) {
        return new BrokerSettings(orDefault(url, defaults.getBrokerUrl()), orDefault(user, defaults.getUsername()),
                orDefault(pw, defaults.getPassword()), orDefault(topic, defaults.getTopic()));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String toString() {
        return "BrokerSettings[credentials and endpoint redacted]";
    }
}
