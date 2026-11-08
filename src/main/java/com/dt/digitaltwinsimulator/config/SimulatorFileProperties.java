package com.dt.digitaltwinsimulator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("simulator.files")
public class SimulatorFileProperties {
    private String baseDir = "./data";
}
