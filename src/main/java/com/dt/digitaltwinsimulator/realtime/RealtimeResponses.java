package com.dt.digitaltwinsimulator.realtime;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public final class RealtimeResponses {
    private RealtimeResponses() {}
    public record Publish(long sent, long failed, long elapsedMillis, String topic, JsonNode firstMessage,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                          String stoppedReason) {}
    public record Preview(int count, String topic, List<JsonNode> messages) {}
    public enum State {
        RUNNING, PAUSED, RECONNECTING, COMPLETED, STOPPED, FAILED;
        public boolean terminal() { return this == COMPLETED || this == STOPPED || this == FAILED; }
    }
    public record Stream(String streamId, State state, String topic, String messageId, double rateTps,
                         long sent, long failed, double actualTps, Instant startedAt, Instant lastSentAt,
                         String lastError, long elapsedMillis, long lagResets, long skippedWhileDisconnected) {}
    public record Patch(Double rateTps, Boolean paused, JsonNode payload) {}
}
