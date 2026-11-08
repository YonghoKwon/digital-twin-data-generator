package com.dt.digitaltwinsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "simulator.http-logging")
public class HttpLoggingProperties {
    private boolean enabled;
    private boolean includeRequestBody;
    private boolean includeResponseBody;
    private int maxPayloadLength = 2000;
    private List<String> excludePaths = new ArrayList<>(List.of("/dt/subscriptions/*/stream"));

    public List<String> getExcludePaths() { return excludePaths; }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths == null ? new ArrayList<>() : new ArrayList<>(excludePaths);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIncludeRequestBody() {
        return includeRequestBody;
    }

    public void setIncludeRequestBody(boolean includeRequestBody) {
        this.includeRequestBody = includeRequestBody;
    }

    public boolean isIncludeResponseBody() {
        return includeResponseBody;
    }

    public void setIncludeResponseBody(boolean includeResponseBody) {
        this.includeResponseBody = includeResponseBody;
    }

    public int getMaxPayloadLength() {
        return maxPayloadLength;
    }

    public void setMaxPayloadLength(int maxPayloadLength) {
        this.maxPayloadLength = maxPayloadLength;
    }
}
