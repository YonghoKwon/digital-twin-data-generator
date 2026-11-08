package com.dt.digitaltwinsimulator.subscribe;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SubscriptionRequest(String subscriptionId, String topic, String activeMQIp, String id,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String pw, Integer bufferSize, Integer maxBodyBytes) {
    @Override public String toString() { return "SubscriptionRequest[redacted]"; }
}

