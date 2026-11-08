package com.dt.digitaltwinsimulator.entity.dto;

import tools.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class DryRunResponseDto {
    private int requestedLimit;
    private int returnedCount;
    private int estimatedRepeatCount;
    private int estimatedSourceRowCount;
    private long estimatedTotalMessagesPerTask;
    private String generationMode;
    private List<JsonNode> messages;

    public DryRunResponseDto(
            int requestedLimit,
            int returnedCount,
            int estimatedRepeatCount,
            int estimatedSourceRowCount,
            long estimatedTotalMessagesPerTask,
            String generationMode,
            List<JsonNode> messages
    ) {
        this.requestedLimit = requestedLimit;
        this.returnedCount = returnedCount;
        this.estimatedRepeatCount = estimatedRepeatCount;
        this.estimatedSourceRowCount = estimatedSourceRowCount;
        this.estimatedTotalMessagesPerTask = estimatedTotalMessagesPerTask;
        this.generationMode = generationMode;
        this.messages = messages;
    }
}
