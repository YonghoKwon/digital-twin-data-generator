package com.dt.digitaltwinsimulator.scenario;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("simulator.scenario")
public class ScenarioProperties {
    private String dir = "./scenarios";
    private int maxRuns = 10;
    private int maxTrackRows = 1_000_000;
}
