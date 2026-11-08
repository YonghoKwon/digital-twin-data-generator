package com.dt.digitaltwinsimulator.scenario;

import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.config.*;
import com.dt.digitaltwinsimulator.logic.MessageBodyLogger;
import com.dt.digitaltwinsimulator.mock.MockRouteControl;
import com.dt.digitaltwinsimulator.realtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static com.dt.digitaltwinsimulator.scenario.ScenarioResponses.State.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioServiceTest {
    @TempDir Path base;
    final JsonMapper mapper = JsonMapper.builder().build();
    final ScenarioProperties properties = new ScenarioProperties();
    final RecordingPublisher publisher = new RecordingPublisher();
    final MockRouteControl mocks = mock(MockRouteControl.class);
    ScenarioService service;
    @BeforeEach void setup() {
        properties.setDir(base.toString());
        var files = new SimulatorFileProperties(); files.setBaseDir(base.toString());
        var factory = new DtMessageFactory(mapper, new RealtimeProperties());
        var connections = spy(new BrokerConnectionService(new ActiveMqBrokerProperties()));
        doAnswer(invocation -> {
            publisher.probes.add(System.nanoTime());
            if (System.nanoTime() < publisher.failUntil) throw new IllegalStateException("password=secret");
            return null;
        }).when(connections).verifyConnection(any());
        var compiler = new ScenarioCompiler(properties, factory, mocks, connections, mapper, files);
        var library = new ScenarioLibrary(properties, compiler, mapper);
        service = new ScenarioService(properties, library, compiler, factory, connections, publisher, mocks, new MessageBodyLogger(0));
    }
    @AfterEach void cleanup() { service.shutdown(); }
    JsonNode json(String text) { return mapper.readTree(text); }
    JsonNode request(String id, String options, String events) {
        return json("{\"runId\":\"" + id + "\"," + options + "\"scenario\":{\"defaults\":{\"topic\":\"dt.test\",\"messageId\":\"T\"},\"events\":[" + events + "]}}");
    }
    String repeat(int count, int interval) { return "{\"at\":0,\"payload\":{\"n\":\"{{seq}}\",\"w\":\"{{walk:20:10..30:0.5}}\"},\"repeat\":{\"count\":" + count + ",\"everyMs\":" + interval + "}}"; }
    static void await(BooleanSupplier condition, long timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout * 1_000_000;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).as("condition within %d ms", timeout).isTrue();
    }
    void complete(String id) throws Exception { await(() -> service.get(id).state() == COMPLETED, 4000); }
    @Test void twoTimesSpeedUsesAbsoluteTimingWithinOneHundredMilliseconds() throws Exception {
        service.create(request("timing", "\"speed\":2,", repeat(5, 200))); complete("timing");
        long elapsed = (publisher.times.getLast() - publisher.times.getFirst()) / 1_000_000;
        assertThat(elapsed).isBetween(300L, 500L); // ideal 400 ms; +/-100 ms scheduler tolerance
        assertThat(service.get("timing").eventsDone()).isEqualTo(5);
        assertThat(service.get("timing").byTopic()).containsEntry("dt.test", 5L);
        assertThat(service.get("timing").progress()).isEqualTo(1);
        assertThat(publisher.virtual).isTrue();
        System.out.printf("S5 speed 2x: five events/800 scenario ms in %d real ms (300..500 accepted)%n", elapsed);
    }
    @Test void pauseResumeFreezesPositionAndRetainsRemainingDelay() throws Exception {
        service.create(request("pause", "\"startPaused\":true,", repeat(2, 400)));
        Thread.sleep(80); assertThat(publisher.times).isEmpty(); assertThat(service.get("pause").positionMs()).isZero();
        service.patch("pause", new ScenarioResponses.Patch(null, false, null));
        await(() -> service.get("pause").sent() == 1, 1000); Thread.sleep(80);
        var paused = service.patch("pause", new ScenarioResponses.Patch(null, true, null));
        Thread.sleep(150); assertThat(service.get("pause").positionMs()).isEqualTo(paused.positionMs()); assertThat(publisher.times).hasSize(1);
        long resumed = System.nanoTime(); service.patch("pause", new ScenarioResponses.Patch(null, false, null)); complete("pause");
        assertThat((publisher.times.getLast() - resumed) / 1_000_000).isBetween(180L, 420L);
    }
    @Test void seekForwardSkipsWithoutCountingAndBackwardReplaysWithContinuousSequence() throws Exception {
        service.create(request("seek", "\"startPaused\":true,", repeat(4, 100)));
        service.patch("seek", new ScenarioResponses.Patch(null, null, 200L));
        service.patch("seek", new ScenarioResponses.Patch(null, false, null));
        await(() -> service.get("seek").sent() == 1, 1000);
        service.patch("seek", new ScenarioResponses.Patch(null, true, 0L));
        assertThat(service.get("seek").skipped()).isZero();
        service.patch("seek", new ScenarioResponses.Patch(10d, false, null)); complete("seek");
        assertThat(service.get("seek").sent()).isEqualTo(5);
        assertThat(service.events("seek", 50)).extracting(ScenarioResponses.EventLog::atMs).containsExactly(200L, 0L, 100L, 200L, 300L);
        assertThat(payload(publisher.bodies.getLast()).get("n").asInt()).isEqualTo(5);
    }
    @Test void loopMaintainsSequenceAndWalkStateAcrossPasses() throws Exception {
        service.create(request("loop", "\"loop\":true,", repeat(2, 50)));
        await(() -> service.get("loop").loopCount() >= 2, 1500); service.stop("loop");
        await(() -> publisher.closed.get() == 1, 1000);
        for (int i = 0; i < publisher.bodies.size(); i++) {
            var p = payload(publisher.bodies.get(i)); assertThat(p.get("n").asInt()).isEqualTo(i + 1);
            if (i > 0) assertThat(Math.abs(p.get("w").asDouble() - payload(publisher.bodies.get(i - 1)).get("w").asDouble())).isLessThanOrEqualTo(0.5);
        }
        // A reset would make every loop's first walk exactly 20.
        assertThat(payload(publisher.bodies.get(2)).get("w").asDouble()).isNotEqualTo(20d);
    }
    @Test void changingSpeedReanchorsPositionWithoutSendingImmediately() throws Exception {
        service.create(request("speed", "", repeat(2, 1000)));
        await(() -> service.get("speed").sent() == 1, 1000); Thread.sleep(100);
        long changed = System.nanoTime(); service.patch("speed", new ScenarioResponses.Patch(2d, null, null));
        complete("speed"); assertThat((publisher.times.getLast() - changed) / 1_000_000).isBetween(330L, 550L);
    }
    @Test void walksOnDifferentTopicsAreIndependentWhileSequenceIsRunWide() throws Exception {
        service.create(request("topics", "", repeat(2, 10) + ",{\"at\":20,\"topic\":\"dt.other\",\"payload\":{\"n\":\"{{seq}}\",\"w\":\"{{walk:20:10..30:0.5}}\"}}"));
        complete("topics");
        assertThat(payload(publisher.bodies.getLast()).get("w").asDouble()).isEqualTo(20);
        assertThat(payload(publisher.bodies.getLast()).get("n").asInt()).isEqualTo(3);
    }
    @Test void slowPublicationCatchesUpInOrderAndReportsMaximumLag() throws Exception {
        publisher.delayMs = 40;
        service.create(request("lag", "", repeat(5, 10))); complete("lag");
        assertThat(service.get("lag").sent()).isEqualTo(5); assertThat(service.get("lag").lagMs()).isGreaterThanOrEqualTo(20);
        assertThat(service.events("lag", 50)).extracting(ScenarioResponses.EventLog::atMs).containsExactly(0L, 10L, 20L, 30L, 40L);
    }
    @Test void skipContinuesMarksAndSkipsDuePublicationsUntilReconnect() throws Exception {
        publisher.failUntil = System.nanoTime() + 700_000_000;
        var request = (tools.jackson.databind.node.ObjectNode) request("skip", "", repeat(5, 400) + ",{\"at\":200,\"type\":\"MARK\",\"label\":\"offline mark\"}");
        ((tools.jackson.databind.node.ObjectNode) request.get("scenario")).put("onDisconnect", "SKIP");
        service.create(request); await(() -> service.get("skip").state() == RECONNECTING, 1000);
        await(() -> "offline mark".equals(service.get("skip").lastMark()), 700);
        complete("skip"); var status = service.get("skip");
        assertThat(status.failed()).isEqualTo(1); assertThat(status.skipped()).isEqualTo(2); assertThat(status.sent()).isEqualTo(2);
        assertThat(status.eventsDone()).isEqualTo(6); assertThat(status.lastError()).doesNotContain("secret");
        assertThat(publisher.probes.getFirst() - publisher.attempts.getFirst()).isGreaterThanOrEqualTo(950_000_000);
    }
    @Test void waitFreezesPositionAndRetriesSameEventWithoutChangingTokens() throws Exception {
        publisher.failUntil = System.nanoTime() + 700_000_000;
        var request = (tools.jackson.databind.node.ObjectNode) request("wait", "", repeat(3, 100));
        ((tools.jackson.databind.node.ObjectNode) request.get("scenario")).put("onDisconnect", "WAIT");
        service.create(request); await(() -> service.get("wait").state() == RECONNECTING, 1000);
        long frozen = service.get("wait").positionMs(); Thread.sleep(150);
        assertThat(service.get("wait").positionMs()).isEqualTo(frozen); complete("wait");
        var status = service.get("wait"); assertThat(status.sent()).isEqualTo(3); assertThat(status.skipped()).isZero(); assertThat(status.failed()).isEqualTo(1);
        assertThat(payload(publisher.bodies.getFirst()).get("n").asInt()).isEqualTo(1);
        assertThat(payload(publisher.bodies.getFirst()).get("w").asDouble()).isEqualTo(20d);
    }
    @Test void failedReconnectUsesOneThenTwoSecondBackoff() throws Exception {
        publisher.failUntil = System.nanoTime() + 2200_000_000L;
        var request = (tools.jackson.databind.node.ObjectNode) request("backoff", "", repeat(2, 10));
        ((tools.jackson.databind.node.ObjectNode) request.get("scenario")).put("onDisconnect", "WAIT");
        service.create(request); complete("backoff");
        assertThat(publisher.probes).hasSize(2);
        assertThat(publisher.probes.get(1) - publisher.probes.get(0)).isGreaterThanOrEqualTo(1950_000_000L);
        assertThat(service.get("backoff").failed()).isEqualTo(2);
    }
    @Test void duplicateCapacityTerminalPatchAndMissingRunUseContractErrors() throws Exception {
        properties.setMaxRuns(1); service.create(request("one", "\"startPaused\":true,", repeat(2, 100)));
        assertThatThrownBy(() -> service.create(request("one", "", repeat(2, 100)))).isInstanceOfSatisfying(ScenarioException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(() -> service.create(request("two", "", repeat(2, 100)))).isInstanceOfSatisfying(ScenarioException.class, e -> assertThat(e.status()).isEqualTo(429));
        service.stop("one");
        assertThatThrownBy(() -> service.patch("one", new ScenarioResponses.Patch(2d, null, null))).isInstanceOfSatisfying(ScenarioException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(() -> service.get("missing")).isInstanceOfSatisfying(ScenarioException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
    @Test void patchValidationIsAtomicAndStartPositionExcludesEarlierEvents() throws Exception {
        service.create(request("atomic", "\"startPaused\":true,\"startAtMs\":100,", repeat(3, 100)));
        assertThatThrownBy(() -> service.patch("atomic", new ScenarioResponses.Patch(2d, false, 500L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get("atomic").state()).isEqualTo(PAUSED); assertThat(service.get("atomic").speed()).isEqualTo(1);
        service.patch("atomic", new ScenarioResponses.Patch(10d, false, null)); complete("atomic");
        assertThat(service.get("atomic").sent()).isEqualTo(2); assertThat(service.get("atomic").skipped()).isZero();
    }
    @Test void failedMockEventContinuesToMarkAndKeepsBodyOutOfLog() throws Exception {
        doThrow(new IllegalStateException("private body")).when(mocks).enabled("missing", true);
        service.create(request("mock", "", "{\"at\":0,\"type\":\"MOCK_ENABLE\",\"routeId\":\"missing\"},{\"at\":0,\"type\":\"MARK\",\"label\":\"after\"}")); complete("mock");
        assertThat(service.get("mock").failed()).isEqualTo(1); assertThat(service.get("mock").lastMark()).isEqualTo("after");
        assertThat(service.events("mock", 50)).extracting(ScenarioResponses.EventLog::result).containsExactly("MOCK_FAILED", "MARK");
        assertThat(mapper.writeValueAsString(service.events("mock", 50))).doesNotContain("private body", "payload");
    }
    @Test void eventHistoryIsBoundedAtFiveHundred() throws Exception {
        service.create(request("logs", "", "{\"at\":0,\"type\":\"MARK\",\"label\":\"x\",\"repeat\":{\"count\":600,\"everyMs\":0}}")); complete("logs");
        var logs = service.events("logs", 500); assertThat(logs).hasSize(500); assertThat(logs.getFirst().seq()).isEqualTo(101);
        assertThat(service.events("logs", 50)).hasSize(50);
        assertThatThrownBy(() -> service.events("logs", 501)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retainsLatestFiftyFinishedRunsAndReleasesScopes() throws Exception {
        for (int i = 0; i < 53; i++) {
            String id = "end-" + i; service.create(request(id, "", repeat(1, 0))); complete(id);
            int closed = i + 1; await(() -> publisher.closed.get() == closed, 1000);
        }
        await(() -> service.list().size() == 50, 1000);
        assertThatThrownBy(() -> service.get("end-0")).isInstanceOf(ScenarioException.class);
        service.create(request("active", "", repeat(100, 100))); await(() -> service.get("active").sent() > 0, 1000);
        service.shutdown(); assertThat(service.get("active").state()).isEqualTo(STOPPED);
        assertThat(publisher.closed.get()).isEqualTo(publisher.opened.get());
    }
    JsonNode payload(String body) { return mapper.readTree(body).get("DATA_MAP").iterator().next(); }
    static class RecordingPublisher implements MessagePublisher {
        final List<Long> times = new CopyOnWriteArrayList<>(), attempts = new CopyOnWriteArrayList<>(), probes = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        volatile long failUntil, delayMs;
        volatile boolean virtual = true;
        public Publication open(BrokerSettings settings) {
            opened.incrementAndGet();
            return new Publication() {
                public void publish(String topic, String body, Map<String, String> headers) {
                    virtual &= Thread.currentThread().isVirtual(); attempts.add(System.nanoTime());
                    if (System.nanoTime() < failUntil) throw new IllegalStateException("password=secret");
                    if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    times.add(System.nanoTime()); bodies.add(body);
                }
                public void close() { closed.incrementAndGet(); }
            };
        }
    }
}
