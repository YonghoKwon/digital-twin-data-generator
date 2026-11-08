package com.dt.digitaltwinsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "simulator.activemq")
public class ActiveMqBrokerProperties {
    private String brokerUrl = "tcp://localhost:61616";
    private String username = "artemis";
    private String password = "artemis";
    private String topic = "topic.cep.output.0";
    private int sessionCacheSize = 100;
    private int connectTimeoutMs = 3000;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { this.connectTimeoutMs = value; }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public void setBrokerUrl(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getSessionCacheSize() {
        return sessionCacheSize;
    }

    public void setSessionCacheSize(int sessionCacheSize) {
        this.sessionCacheSize = sessionCacheSize;
    }
}
