package com.dt.digitaltwinsimulator.subscribe;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

public record ReceivedMessage(long seq, Instant receivedAt, String topic, String type, String encoding, long sizeBytes,
        String body, JsonNode bodyJson, String bodyBase64Head, String messageId,
        Map<String, Object> properties, boolean truncated) {}

