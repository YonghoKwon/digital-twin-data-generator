package com.dt.digitaltwinsimulator.scenario;

import java.time.Instant;
import java.util.Map;

public final class ScenarioResponses {
    private ScenarioResponses() { }
    public enum State {
        RUNNING, PAUSED, RECONNECTING, COMPLETED, STOPPED, FAILED;
        public boolean terminal() { return this == COMPLETED || this == STOPPED || this == FAILED; }
    }
    public record Summary(String file, String scenarioId, String name, String description, long eventCount,
                          int trackCount, long durationMs, boolean valid, String error) { }
    public record Status(String runId, String scenarioId, String name, State state, double speed, boolean loop,
                         long positionMs, long durationMs, double progress, long loopCount, long eventsTotal,
                         long eventsDone, long sent, long failed, long skipped, long mockOps, Map<String, Long> byTopic,
                         String lastMark, String lastError, Instant startedAt, Instant lastEventAt, long lagMs) { }
    public record EventLog(long seq, long atMs, String type, String topic, String messageId, String result, String error) { }
    public record Patch(Double speed, Boolean paused, Long seekMs) { }
}
