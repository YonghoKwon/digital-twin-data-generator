package com.dt.digitaltwinsimulator.scenario;

import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.logic.MessageBodyLogger;
import com.dt.digitaltwinsimulator.mock.MockRouteControl;
import com.dt.digitaltwinsimulator.realtime.DtMessageFactory;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.dt.digitaltwinsimulator.scenario.ScenarioResponses.State.*;

/** One virtual thread owns the cursor, token state and publication. Control never waits for broker I/O. */
@Slf4j
final class ScenarioRun implements Runnable {
    private final Object lock = new Object();
    private final String id, scenarioId, name;
    private ScenarioPlan plan;
    private final long duration, total;
    private final boolean loop, waitOnDisconnect;
    private final BrokerSettings broker;
    private final DtMessageFactory factory;
    private final BrokerConnectionService connections;
    private final MessagePublisher publisher;
    private final MockRouteControl mocks;
    private final MessageBodyLogger bodies;
    private final Runnable terminated;
    private final Instant startedAt = Instant.now();
    private final Map<String, Long> byTopic = new LinkedHashMap<>();
    private final ArrayDeque<ScenarioResponses.EventLog> history = new ArrayDeque<>();
    private ScenarioPlan.Cursor cursor;
    private DtMessageFactory.TokenState tokens = new DtMessageFactory.TokenState();
    private ScenarioResponses.State state;
    private double speed, anchorPosition, skipThrough = -1;
    private long anchorNanos = System.nanoTime(), reconnectAt, revision, loopCount, eventsDone, sent, failed, skipped, mockOps, logSeq, lagMs;
    private boolean paused, disconnected;
    private String lastMark, lastError;
    private Instant lastEventAt;
    private volatile boolean finished;
    private long endedNanos;
    private Thread worker;

    ScenarioRun(String id, ScenarioPlan plan, double speed, boolean loop, long startAt, boolean paused,
                DtMessageFactory factory, BrokerConnectionService connections, MessagePublisher publisher,
                MockRouteControl mocks, MessageBodyLogger bodies, Runnable terminated) {
        this.id = id; this.plan = plan; this.scenarioId = plan.scenarioId(); this.name = plan.name();
        this.speed = speed; this.loop = loop; this.duration = plan.durationMs(); this.total = plan.eventCount();
        this.waitOnDisconnect = plan.waitOnDisconnect(); this.broker = plan.broker();
        this.paused = paused; this.state = paused ? PAUSED : RUNNING; this.anchorPosition = startAt;
        this.cursor = plan.cursor(startAt); this.factory = factory; this.connections = connections;
        this.publisher = publisher; this.mocks = mocks; this.bodies = bodies; this.terminated = terminated;
    }
    void start() {
        worker = Thread.ofVirtual().name("scenario-" + id).unstarted(this);
        log.info("scenario started runId={} speed={} state={}", id, speed, state);
        worker.start();
    }
    boolean finished() { return finished; }
    long endedNanos() { synchronized (lock) { return endedNanos; } }
    private double timeline(long now) {
        return anchorPosition + (paused || state.terminal() || disconnected && waitOnDisconnect ? 0 : Math.max(0, now - anchorNanos) / 1_000_000d * speed);
    }
    private double position(long now) { return Math.min(duration, timeline(now)); }
    private void anchor(double position, long now) { anchorPosition = position; anchorNanos = now; }
    private void state() { if (!state.terminal()) state = paused ? PAUSED : disconnected ? RECONNECTING : RUNNING; }
    ScenarioResponses.Status snapshot() {
        synchronized (lock) {
            long position = (long) position(System.nanoTime());
            return new ScenarioResponses.Status(id, scenarioId, name, state, speed, loop, position, duration,
                    duration == 0 ? state == COMPLETED ? 1 : 0 : (double) position / duration,
                    loopCount, total, eventsDone, sent, failed, skipped, mockOps, Map.copyOf(byTopic), lastMark,
                    lastError, startedAt, lastEventAt, lagMs);
        }
    }
    List<ScenarioResponses.EventLog> events(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be in 1..500.");
        synchronized (lock) { return history.stream().skip(Math.max(0, history.size() - limit)).toList(); }
    }
    void patch(ScenarioResponses.Patch patch) {
        synchronized (lock) {
            if (state.terminal()) throw new ScenarioException(409, "RUN_NOT_ACTIVE", "Scenario run has ended.");
            if (patch.seekMs() != null && (patch.seekMs() < 0 || patch.seekMs() > duration)) throw new IllegalArgumentException("seekMs must be in 0..durationMs.");
            if (patch.speed() != null && (!Double.isFinite(patch.speed()) || patch.speed() < 0.1 || patch.speed() > 100)) throw new IllegalArgumentException("speed must be in 0.1..100.");
            long now = System.nanoTime();
            anchor(position(now), now);
            if (patch.speed() != null) speed = patch.speed();
            if (patch.seekMs() != null) {
                anchor(patch.seekMs(), now);
                cursor = plan.cursor(patch.seekMs());
                skipThrough = -1;
                revision++;
            }
            if (patch.paused() != null) paused = patch.paused();
            state(); lock.notifyAll();
            log.info("scenario changed runId={} speed={} positionMs={} state={}", id, speed, (long) anchorPosition, state);
        }
    }
    void stop() {
        synchronized (lock) { if (!state.terminal()) end(STOPPED); lock.notifyAll(); }
        if (worker != null && !finished) worker.interrupt();
    }
    /** DELETE is idempotent and always returns the contract's STOPPED state, including ended runs. */
    void stopRequested() {
        stop();
        synchronized (lock) {
            if (state != STOPPED) {
                state = STOPPED;
                log.info("scenario changed runId={} state={}", id, state);
            }
        }
    }
    void join(long millis) throws InterruptedException { if (worker != null) worker.join(millis); }
    private void end(ScenarioResponses.State result) {
        long now = System.nanoTime(); anchor(position(now), now); state = result; endedNanos = now;
    }
    private void record(ScenarioPlan.Event event, String result, String error) {
        var publish = event.publish();
        history.addLast(new ScenarioResponses.EventLog(++logSeq, event.at(), event.type(),
                publish == null ? null : publish.topic(), publish == null ? null : publish.spec().messageId(), result, error));
        if (history.size() > 500) history.removeFirst();
        lastEventAt = Instant.now();
    }

    @Override public void run() {
        MessagePublisher.Publication publication = null;
        int backoff = 1;
        try {
            while (true) {
                ScenarioPlan.Event event = null;
                long version = 0;
                boolean reconnect = false;
                synchronized (lock) {
                    while (true) {
                        if (state.terminal()) return;
                        long now = System.nanoTime();
                        if (paused) { lock.wait(1000); continue; }
                        if (cursor.empty()) {
                            if (!loop) { anchor(duration, now); end(COMPLETED); return; }
                            loopCount++; cursor = plan.cursor(0); anchor(0, now); skipThrough = -1;
                            if (duration == 0) { lock.wait(1); continue; }
                        }
                        if (disconnected && now >= reconnectAt) { reconnect = true; break; }
                        if (disconnected && waitOnDisconnect) {
                            TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, reconnectAt - now)); continue;
                        }
                        double remainingMs = (cursor.time() - timeline(now)) / speed;
                        if (remainingMs > 0) {
                            long wait = (long) Math.min(1_000_000_000d, remainingMs * 1_000_000d);
                            if (disconnected) wait = Math.min(wait, reconnectAt - now);
                            TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, wait)); continue;
                        }
                        event = cursor.event(); version = revision;
                        lagMs = Math.max(lagMs, (long) Math.max(0, (timeline(now) - event.at()) / speed));
                        if ((disconnected || event.at() <= skipThrough) && event.type().equals("PUBLISH")) {
                            skipped++; eventsDone++; record(event, "SKIPPED", null); cursor.advance(); continue;
                        }
                        break;
                    }
                }
                if (reconnect) {
                    try {
                        connections.verifyConnection(broker);
                        synchronized (lock) {
                            long now = System.nanoTime();
                            // A connection probe can block. Publications due during that probe were
                            // still disconnected and must not become a catch-up burst after recovery.
                            if (!waitOnDisconnect) skipThrough = Math.max(skipThrough, timeline(now));
                            anchor(position(now), now);
                            disconnected = false; state();
                        }
                    } catch (RuntimeException exception) {
                        synchronized (lock) { failed++; brokerFailure(backoff); }
                        backoff = Math.min(30, backoff * 2);
                    }
                    continue;
                }
                if (event.type().equals("PUBLISH")) {
                    var candidate = factory.copyTokenState(tokens);
                    var input = event.publish();
                    var message = input.literal() ? factory.createLiteralMessage(input.spec(), input.payload())
                            : factory.createForScope(input.spec(), input.payload(), sent + 1, candidate,
                                    input.topic().length() + ":" + input.topic() + ":" + input.spec().messageId());
                    try {
                        if (publication == null) publication = publisher.open(broker);
                        publication.publish(input.topic(), message.body(), message.headers());
                    } catch (RuntimeException exception) {
                        synchronized (lock) {
                            failed++; lastError = "Broker publication failed."; record(event, "FAILED", lastError);
                            if (!waitOnDisconnect) { eventsDone++; if (version == revision) cursor.advance(); }
                            if (!state.terminal()) brokerFailure(backoff);
                        }
                        backoff = Math.min(30, backoff * 2);
                        close(publication); publication = null;
                        continue;
                    }
                    synchronized (lock) {
                        tokens = candidate; sent++; eventsDone++; byTopic.merge(input.topic(), 1L, Long::sum);
                        record(event, "SENT", null); backoff = 1;
                        if (version == revision) cursor.advance();
                    }
                    bodies.logMessage(id, sent, message.body());
                } else {
                    String result = "MARK", error = null;
                    try {
                        switch (event.type()) {
                            case "MOCK_UPDATE" -> mocks.update(event.routeId(), event.route());
                            case "MOCK_ENABLE" -> mocks.enabled(event.routeId(), true);
                            case "MOCK_DISABLE" -> mocks.enabled(event.routeId(), false);
                            case "MARK" -> { }
                            default -> throw new IllegalStateException();
                        }
                        if (!event.type().equals("MARK")) result = "MOCK_OK";
                    } catch (RuntimeException exception) { result = "MOCK_FAILED"; error = "Mock route operation failed."; }
                    synchronized (lock) {
                        if (result.equals("MOCK_OK")) mockOps++;
                        if (result.equals("MOCK_FAILED")) { failed++; lastError = error; }
                        if (result.equals("MARK")) lastMark = event.label();
                        eventsDone++; record(event, result, error);
                        if (version == revision) cursor.advance();
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            synchronized (lock) { if (!state.terminal()) end(STOPPED); }
        } catch (RuntimeException exception) {
            synchronized (lock) {
                failed++; lastError = "Scenario event generation failed.";
                if (!state.terminal()) end(FAILED);
            }
        } finally {
            close(publication);
            synchronized (lock) {
                if (!state.terminal()) end(STOPPED);
                plan = null; cursor = null; tokens = null; finished = true;
            }
            log.info("scenario ended runId={} sent={} failed={} skipped={} state={}", id, sent, failed, skipped, state);
            terminated.run();
        }
    }
    private void brokerFailure(int backoff) {
        long now = System.nanoTime(); anchor(position(now), now); disconnected = true;
        lastError = "Broker connection or publication failed.";
        reconnectAt = now + TimeUnit.SECONDS.toNanos(backoff); state();
    }
    private void close(MessagePublisher.Publication publication) {
        if (publication == null) return;
        boolean interrupted = Thread.interrupted();
        try { publication.close(); }
        catch (RuntimeException exception) { log.warn("scenario scope close failed runId={}", id); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
