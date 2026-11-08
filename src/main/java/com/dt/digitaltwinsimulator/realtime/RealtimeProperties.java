package com.dt.digitaltwinsimulator.realtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("simulator.realtime")
public class RealtimeProperties {
    private String schemaVersion = "1";
    private int maxPublishCount = 1000;
    private double maxRateTps = 5000;
    private int maxStreams = 50;
}
