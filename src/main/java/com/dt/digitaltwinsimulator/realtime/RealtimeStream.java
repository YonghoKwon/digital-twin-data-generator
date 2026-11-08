package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.logic.MessageBodyLogger;
import com.dt.digitaltwinsimulator.logic.SensitiveLogMasker;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.dt.digitaltwinsimulator.realtime.RealtimeResponses.State.*;

/** One virtual thread owns the JMS scope. HTTP operations never wait for broker I/O. */
@Slf4j
final class RealtimeStream implements Runnable {
    private final Object lock = new Object();
    private final String id;
    private final BrokerSettings broker;
    private final DtMessageFactory.Spec spec;
    private final DtMessageFactory factory;
    private final MessagePublisher publisher;
    private final MessageBodyLogger bodyLogger;
    private final Runnable terminated;
    private final long startedNanos = System.nanoTime();
    private final Instant startedAt = Instant.now();
    private final long durationNanos;
    private final long maxMessages;
    private boolean loop;
    private boolean expandTokens;
    private final ArrayDeque<Long> successes = new ArrayDeque<>();
    private List<JsonNode> rows;
    private int rowIndex;
    private DtMessageFactory.TokenState tokens = new DtMessageFactory.TokenState();
    private RealtimeResponses.State state;
    private double rate;
    private boolean paused, disconnected;
    private long sent, failed, lagResets, skipped, revision, payloadRevision;
    private double skippedFraction;
    private long disconnectedTick, reconnectAt, nextDue = startedNanos, endedNanos, lastSentNanos;
    private Instant lastSentAt;
    private String lastError;
    private volatile boolean finished;
    private Thread thread;

    RealtimeStream(String id, BrokerSettings broker, DtMessageFactory.Spec spec, List<JsonNode> rows,
                   RealtimeRequest request, DtMessageFactory factory, MessagePublisher publisher,
                   MessageBodyLogger bodyLogger, Runnable terminated) {
        this.id = id;
        this.broker = broker;
        this.spec = spec;
        this.rows = rows;
        this.factory = factory;
        this.publisher = publisher;
        this.bodyLogger = bodyLogger;
        this.terminated = terminated;
        this.rate = request.getRateTps();
        this.durationNanos = request.getDurationSec() == 0 ? 0 : Math.max(1, (long) (request.getDurationSec() * 1_000_000_000d));
        this.maxMessages = request.getMaxMessages();
        this.loop = request.getSource() == null || request.getSource().isLoop();
        this.expandTokens = request.getSource() == null;
        this.paused = request.isStartPaused();
        this.state = paused ? PAUSED : RUNNING;
    }

    void start() {
        thread = Thread.ofVirtual().name("realtime-" + id).unstarted(this);
        log.info("stream started streamId={} rateTps={} state={}", safeId(), rate, state);
        thread.start();
    }

    boolean finished() { return finished; }
    RealtimeRequest.Envelope envelope() { return spec.envelope(); }
    long endedNanos() { synchronized (lock) { return endedNanos; } }

    RealtimeResponses.Stream snapshot() {
        synchronized (lock) {
            long now = System.nanoTime();
            accountDisconnected(now);
            while (!successes.isEmpty() && now - successes.getFirst() >= TimeUnit.SECONDS.toNanos(10)) successes.removeFirst();
            // Startup uses elapsed time until a complete ten-second window is available.
            double window = Math.min(10d, Math.max(0.001, (now - startedNanos) / 1_000_000_000d));
            return new RealtimeResponses.Stream(id, state, broker.topic(), spec.messageId(), rate, sent, failed,
                    successes.size() / window, startedAt, lastSentAt, lastError,
                    TimeUnit.NANOSECONDS.toMillis((endedNanos == 0 ? now : endedNanos) - startedNanos), lagResets, skipped);
        }
    }

    void patch(RealtimeResponses.Patch patch) {
        synchronized (lock) {
            if (state.terminal()) throw new RealtimeHttpException(409, "STREAM_NOT_ACTIVE", "Stream has already ended.");
            long now = System.nanoTime();
            accountDisconnected(now);
            if (patch.rateTps() != null) {
                rate = patch.rateTps();
                nextDue = sent == 0 ? now : lastSentNanos + periodNanos();
                if (nextDue - now < 0) nextDue = now;
                revision++;
            }
            if (patch.payload() != null) {
                rows = List.of(patch.payload().deepCopy());
                rowIndex = 0;
                loop = true;
                expandTokens = true;
                tokens = new DtMessageFactory.TokenState();
                payloadRevision++;
            }
            if (patch.paused() != null) {
                if (paused && !patch.paused()) {
                    nextDue = now;
                    revision++;
                }
                paused = patch.paused();
            }
            state = paused ? PAUSED : disconnected ? RECONNECTING : RUNNING;
            if (disconnected) disconnectedTick = now;
            lock.notifyAll();
            log.info("stream changed streamId={} rateTps={} state={}", safeId(), rate, state);
        }
    }

    void stop() {
        boolean interrupted;
        synchronized (lock) {
            interrupted = !state.terminal();
            if (interrupted) end(STOPPED);
            lock.notifyAll();
        }
        if (interrupted && thread != null) thread.interrupt();
    }

    void join(long millis) throws InterruptedException { if (thread != null) thread.join(millis); }

    @Override
    public void run() {
        MessagePublisher.Publication publication = null;
        int backoffSeconds = 1;
        try {
            while (true) {
                DtMessageFactory.Message message;
                DtMessageFactory.TokenState candidateTokens;
                long version, payloadVersion;
                synchronized (lock) {
                    while (true) {
                        long now = System.nanoTime();
                        if (state.terminal()) return;
                        if ((durationNanos > 0 && now - startedNanos >= durationNanos) ||
                                (maxMessages > 0 && sent >= maxMessages) || rowIndex >= rows.size()) {
                            end(COMPLETED);
                            return;
                        }
                        long remaining = durationNanos > 0 ? durationNanos - (now - startedNanos) : Long.MAX_VALUE;
                        long due = disconnected ? reconnectAt : nextDue;
                        if (paused || due - now > 0) {
                            long wait = paused ? remaining : Math.min(remaining, due - now);
                            TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, wait));
                            continue;
                        }
                        // Generation errors end the stream; only broker operations below trigger reconnect.
                        candidateTokens = tokens.copy();
                        message = expandTokens ? factory.create(spec, rows.get(rowIndex), sent + 1, candidateTokens)
                                : factory.createLiteral(spec, rows.get(rowIndex));
                        version = revision;
                        payloadVersion = payloadRevision;
                        break;
                    }
                }
                try {
                    if (publication == null) publication = publisher.open(broker);
                    publication.publish(broker.topic(), message.body(), message.headers());
                } catch (RuntimeException e) {
                    synchronized (lock) {
                        if (state.terminal()) return;
                        long now = System.nanoTime();
                        accountDisconnected(now);
                        failed++;
                        // Keep endpoint credentials and provider exception text out of status/logging.
                        lastError = SensitiveLogMasker.mask("Broker publication failed (" + e.getClass().getSimpleName() + ").");
                        disconnected = true;
                        disconnectedTick = now;
                        reconnectAt = now + TimeUnit.SECONDS.toNanos(backoffSeconds);
                        state = paused ? PAUSED : RECONNECTING;
                        backoffSeconds = Math.min(30, backoffSeconds * 2);
                    }
                    close(publication);
                    publication = null;
                    continue;
                }
                synchronized (lock) {
                    long now = System.nanoTime();
                    accountDisconnected(now);
                    disconnected = false;
                    backoffSeconds = 1;
                    sent++;
                    lastSentNanos = now;
                    lastSentAt = Instant.now();
                    successes.addLast(now);
                    while (!successes.isEmpty() && now - successes.getFirst() >= TimeUnit.SECONDS.toNanos(10)) successes.removeFirst();
                    if (payloadRevision == payloadVersion) {
                        tokens = candidateTokens;
                        rowIndex++;
                        if (loop && rowIndex == rows.size()) rowIndex = 0;
                    }
                    if (revision == version) {
                        long period = periodNanos();
                        nextDue += period;
                        if (disconnectedTick != 0 || now > nextDue) {
                            if (now > nextDue) lagResets++;
                            nextDue = now + period;
                        }
                    } else {
                        // A rate/resume PATCH during broker I/O must also respect this
                        // just-completed send, rather than leave an already-expired due time.
                        nextDue = now + periodNanos();
                    }
                    disconnectedTick = 0;
                    if (!state.terminal()) state = paused ? PAUSED : RUNNING;
                }
                bodyLogger.logMessage(id, sent, message.body());
            }
        } catch (InterruptedException e) {
            synchronized (lock) { if (!state.terminal()) end(STOPPED); }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            synchronized (lock) {
                lastError = SensitiveLogMasker.mask("Message generation failed (" + e.getClass().getSimpleName() + ").");
                if (!state.terminal()) end(FAILED);
            }
        } finally {
            close(publication);
            synchronized (lock) {
                if (!state.terminal()) end(STOPPED);
                finished = true;
                // Release potentially large source rows when the worker exits.
                rows = List.of();
                tokens = new DtMessageFactory.TokenState();
            }
            var status = snapshot();
            log.info("stream ended streamId={} sent={} failed={} elapsedMillis={} state={}",
                    safeId(), status.sent(), status.failed(), status.elapsedMillis(), status.state());
            terminated.run();
        }
    }

    private long periodNanos() { return Math.max(1, (long) (1_000_000_000d / rate)); }

    private void accountDisconnected(long now) {
        if (!disconnected || paused || state.terminal()) return;
        double missed = skippedFraction + Math.max(0, now - disconnectedTick) / 1_000_000_000d * rate;
        long whole = (long) missed;
        skipped += whole;
        skippedFraction = missed - whole;
        disconnectedTick = now;
    }

    private void end(RealtimeResponses.State result) {
        accountDisconnected(System.nanoTime());
        state = result;
        endedNanos = System.nanoTime();
    }

    private String safeId() { return SensitiveLogMasker.mask(id); }

    private void close(MessagePublisher.Publication publication) {
        if (publication == null) return;
        // Interrupted broker calls must still release their connection/session cache.
        boolean interrupted = Thread.interrupted();
        try { publication.close(); }
        catch (RuntimeException e) { log.warn("stream scope close failed streamId={} type={}", safeId(), e.getClass().getSimpleName()); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
