package com.dt.digitaltwinsimulator.embedded;

import lombok.Getter;
import lombok.Setter;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("simulator.embedded-broker")
public class EmbeddedBrokerProperties {
    private boolean enabled = false;
    private boolean useAsDefault = true;
    private String host = "0.0.0.0";
    private int corePort = 61626;
    private int stompPort = 61623;
    private String username = "artemis";
    private String password = "artemis";
    // Includes STOMP headers as well as the body in a single WebSocket frame.
    private int maxFrameBytes = 2 * 1024 * 1024;
    private int stompMinLargeMessageSize = 102400;
    private long maxSizeBytes = 100 * 1024 * 1024;
    private AddressFullMessagePolicy addressFullPolicy = AddressFullMessagePolicy.PAGE;

    public String clientHost() {
        return "0.0.0.0".equals(host) || "::".equals(host) ? "127.0.0.1" : host;
    }

    public String endpoint(String scheme, String endpointHost, int port) {
        String h = endpointHost.contains(":") && !endpointHost.startsWith("[") ? "[" + endpointHost + "]" : endpointHost;
        return scheme + "://" + h + ":" + port;
    }
}

