package com.dt.digitaltwinsimulator.mock;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "simulator.mock")
public class MockProperties {
    @NotBlank
    private String basePath = "/mock";
    @NotBlank
    private String routesDir = "./mock-routes";
    @Min(1)
    private int journalSize = 1000;
    @Min(0)
    @Max(Long.MAX_VALUE - 1)
    private long maxDelayMs = 60000;

    @PostConstruct
    void validateBasePath() {
        if (!basePath.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
                || basePath.equals("/mock-admin") || basePath.startsWith("/mock-admin/")) {
            throw new IllegalArgumentException("simulator.mock.base-path must be a non-root literal path outside /mock-admin");
        }
    }
}
