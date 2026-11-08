package com.dt.digitaltwinsimulator.logic;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBodyLoggerTest {
    @Test
    void logsBodiesAtDebugAndOnlyConfiguredSamplesAtInfoWithMasking() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageBodyLogger.class);
        Level previous = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        try {
            new MessageBodyLogger(0).logMessage("task", 1, "{\"password\":\"secret\"}");
            var sampled = new MessageBodyLogger(2);
            sampled.logMessage("task", 1, "{\"pw\":\"secret\"}");
            sampled.logMessage("task", 2, "{\"pw\":\"secret\"}");
            assertThat(events.list).filteredOn(event -> event.getLevel() == Level.DEBUG).hasSize(3);
            assertThat(events.list).filteredOn(event -> event.getLevel() == Level.INFO).hasSize(1);
            assertThat(events.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("secret"));
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previous);
            events.stop();
        }
    }
}
