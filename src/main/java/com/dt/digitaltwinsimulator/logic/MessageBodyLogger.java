package com.dt.digitaltwinsimulator.logic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MessageBodyLogger {
    private final long sampleEvery;

    public MessageBodyLogger(@Value("${simulator.message-log.sample-every:0}") long sampleEvery) {
        this.sampleEvery = sampleEvery;
    }

    public void logMessage(String taskId, long sentCount, String body) {
        if (log.isDebugEnabled()) {
            log.debug("taskId={} message[{}]: {}", taskId, sentCount, SensitiveLogMasker.mask(body));
        }
        if (sampleEvery > 0 && sentCount % sampleEvery == 0) {
            log.info("taskId={} message sample[{}]: {}", taskId, sentCount, SensitiveLogMasker.mask(body));
        }
    }
}
