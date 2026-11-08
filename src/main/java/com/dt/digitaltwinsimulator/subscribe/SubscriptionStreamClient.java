package com.dt.digitaltwinsimulator.subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A bounded live stream; socket writes and completion never run on the JMS receiver. */
final class SubscriptionStreamClient {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionStreamClient.class);
    final SseEmitter emitter;
    private final String id;
    private final BlockingQueue<ReceivedMessage> pending;
    private final Consumer<SubscriptionStreamClient> onClose;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread sender;

    SubscriptionStreamClient(String id, int bufferSize, Consumer<SubscriptionStreamClient> onClose) {
        this(id, bufferSize, onClose, new SseEmitter(0L));
    }

    SubscriptionStreamClient(String id, int bufferSize, Consumer<SubscriptionStreamClient> onClose, SseEmitter emitter) {
        this.id = id;
        this.pending = new ArrayBlockingQueue<>(Math.min(bufferSize, 256));
        this.onClose = onClose;
        this.emitter = emitter;
    }

    void start() {
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(e -> close());
        sender = Thread.ofVirtual().name("subscription-sse-" + id).start(() -> {
            try {
                emitter.send(SseEmitter.event().name("ready").data(Map.of("subscriptionId", id)));
                while (!closed.get()) {
                    var next = pending.poll(15, TimeUnit.SECONDS);
                    if (closed.get()) break;
                    if (next == null) emitter.send(SseEmitter.event().comment("keepalive"));
                    else emitter.send(SseEmitter.event().id(Long.toString(next.seq())).name("message").data(next));
                }
            } catch (IOException | IllegalStateException e) { close(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { close(); }
        });
    }

    synchronized void offer(ReceivedMessage message) {
        if (!closed.get() && !pending.offer(message)) finish(true);
    }

    synchronized void close() { finish(false); }

    private void finish(boolean overflow) {
        if (!closed.compareAndSet(false, true)) return;
        // Count discarded queued records plus the record rejected by offer (exclude any in-flight write).
        int dropped = pending.size() + (overflow ? 1 : 0);
        pending.clear();
        onClose.accept(this);
        if (sender != null) sender.interrupt();
        if (overflow) log.warn("Closing slow SSE subscriptionId={}", id);
        Thread.ofVirtual().name("subscription-sse-close-" + id).start(() -> {
            try {
                if (overflow) emitter.send(SseEmitter.event().name("overflow")
                        .data(Map.of("reason", "SLOW_CONSUMER", "dropped", dropped)));
            } catch (IOException | IllegalStateException ignored) { /* Best effort on an already slow/broken socket. */ }
            finally { emitter.complete(); }
        });
    }
}
