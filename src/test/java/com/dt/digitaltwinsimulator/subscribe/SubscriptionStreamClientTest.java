package com.dt.digitaltwinsimulator.subscribe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class SubscriptionStreamClientTest {
    @Test void overflowAttemptsReasonAndDroppedCountBeforeCompletionWithoutBlockingProducer() throws Exception {
        var emitter = new SlowEmitter(false);
        var removed = new AtomicInteger();
        var client = new SubscriptionStreamClient("slow-reader", 2, ignored -> removed.incrementAndGet(), emitter);
        var logger = (Logger) LoggerFactory.getLogger(SubscriptionStreamClient.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            client.start();
            assertThat(emitter.ready.await(2, TimeUnit.SECONDS)).isTrue();
            client.offer(message(1)); client.offer(message(2));
            CompletableFuture.runAsync(() -> client.offer(message(3))).get(1, TimeUnit.SECONDS);
            emitter.completed.get(2, TimeUnit.SECONDS);
            var event = emitter.overflow.get(2, TimeUnit.SECONDS);
            assertThat(event.stream().map(SseEmitter.DataWithMediaType::getData))
                    .anySatisfy(data -> assertThat(data.toString()).contains("event:overflow"))
                    .contains(Map.of("reason", "SLOW_CONSUMER", "dropped", 3));
            assertThat(emitter.overflowBeforeComplete).isTrue();
            client.offer(message(4)); client.close();
            assertThat(removed).hasValue(1);
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(logs.list.getFirst().getFormattedMessage()).isEqualTo("Closing slow SSE subscriptionId=slow-reader");
        } finally {
            emitter.release.countDown(); client.close(); logger.detachAppender(logs); logs.stop();
        }
    }

    @Test void overflowStillCompletesWhenBestEffortEventCannotBeWritten() throws Exception {
        var emitter = new SlowEmitter(true);
        var removed = new AtomicInteger();
        var client = new SubscriptionStreamClient("broken-reader", 1, ignored -> removed.incrementAndGet(), emitter);
        try {
            client.start();
            assertThat(emitter.ready.await(2, TimeUnit.SECONDS)).isTrue();
            client.offer(message(1));
            CompletableFuture.runAsync(() -> client.offer(message(2))).get(1, TimeUnit.SECONDS);
            emitter.completed.get(2, TimeUnit.SECONDS);
            assertThat(emitter.overflow.isDone()).isTrue();
            assertThat(removed).hasValue(1);
        } finally { emitter.release.countDown(); client.close(); }
    }

    private ReceivedMessage message(long seq) {
        return new ReceivedMessage(seq, Instant.now(), "s4.slow", "TEXT", "UTF8_TEXT", 12,
                "private-body", null, null, null, Map.of(), false);
    }

    private static final class SlowEmitter extends SseEmitter {
        final CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        final CompletableFuture<Set<DataWithMediaType>> overflow = new CompletableFuture<>();
        final CompletableFuture<Void> completed = new CompletableFuture<>();
        final boolean failOverflow;
        volatile boolean overflowBeforeComplete;

        SlowEmitter(boolean failOverflow) { this.failOverflow = failOverflow; }

        @Override public void send(SseEventBuilder event) throws IOException {
            var data = event.build();
            if (data.stream().anyMatch(item -> item.getData().toString().contains("event:ready"))) {
                ready.countDown();
                boolean interrupted = false;
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            } else {
                overflow.complete(data);
                if (failOverflow) throw new IOException("socket unavailable");
            }
        }

        @Override public void complete() {
            overflowBeforeComplete = overflow.isDone();
            completed.complete(null);
        }
    }
}
