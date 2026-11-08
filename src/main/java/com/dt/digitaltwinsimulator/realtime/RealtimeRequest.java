package com.dt.digitaltwinsimulator.realtime;

import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable HTTP input only; workers receive an independent, validated snapshot. */
@Getter
@Setter
public class RealtimeRequest {
    public enum Envelope { DTCORE, RAW }
    private String topic;
    private String messageId;
    private Envelope envelope = Envelope.DTCORE;
    private JsonNode payload;
    private Map<String, String> headers = new LinkedHashMap<>();
    private boolean standardHeaders;
    private int count = 1;
    private long intervalMs;
    private String activeMQIp;
    private String id;
    private String pw;
    private String streamId;
    private double rateTps = 1;
    private double durationSec;
    private long maxMessages;
    private boolean startPaused;
    private Source source;

    @Getter
    @Setter
    public static class Source {
        private String type;
        private String filePath;
        private String formatFileName;
        private String dataFileName;
        private boolean loop = true;
    }
}
