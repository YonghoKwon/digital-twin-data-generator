package com.dt.digitaltwinsimulator.subscribe;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("simulator.subscribe")
public class SubscribeProperties {
    private int maxBuffer = 10000;
    private int maxSubscriptions = 20;
}

