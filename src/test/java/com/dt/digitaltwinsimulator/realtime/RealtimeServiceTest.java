package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import com.dt.digitaltwinsimulator.logic.DuplicateTaskException;
import com.dt.digitaltwinsimulator.logic.MessageBodyLogger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.dt.digitaltwinsimulator.realtime.RealtimeResponses.State.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeServiceTest {
    @TempDir Path base;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RealtimeProperties properties = new RealtimeProperties();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private RealtimeService service;

    @BeforeEach
    void setup() {
        var fileProperties = new SimulatorFileProperties();
        fileProperties.setBaseDir(base.toString());
        var connections = spy(new BrokerConnectionService(new ActiveMqBrokerProperties()));
        doNothing().when(connections).verifyConnection(any());
        service = new RealtimeService(properties, new DtMessageFactory(mapper, properties),
                new RealtimeFileSource(fileProperties, mapper), connections, publisher, new MessageBodyLogger(0));
    }

    @AfterEach void cleanup() { service.shutdown(); }

    private RealtimeRequest request(String id) {
        var request = new RealtimeRequest();
        request.setStreamId(id);
        request.setTopic("topic.sensor");
        request.setMessageId("TC");
        request.setPayload(mapper.readTree("{\"n\":\"{{seq}}\"}"));
        request.setRateTps(100);
        return request;
    }

    static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).as("condition within %d ms", timeoutMs).isTrue();
    }

    @Test
    void publishHonorsCountIntervalsAndClosesOnePublication() {
        var request = request("once");
        request.setCount(3);
        request.setIntervalMs(40);
        var result = service.publish(request);
        assertThat(result.sent()).isEqualTo(3);
        assertThat(result.failed()).isZero();
        assertThat(result.firstMessage().get("DATA_MAP").iterator().next().get("n").asInt()).isEqualTo(1);
        assertThat(publisher.times).hasSize(3);
        for (int i = 1; i < 3; i++) assertThat(publisher.times.get(i) - publisher.times.get(i - 1)).isGreaterThanOrEqualTo(35_000_000);
        assertThat(publisher.closed.get()).isEqualTo(1);
    }

    @Test
    void publishGeneratesTimestampsAtSendTimeAcrossIntervals() {
        var request = request("timed");
        request.setPayload(mapper.readTree("{\"at\":\"{{now:HH:mm:ss.SSS}}\",\"n\":\"{{seq}}\"}"));
        request.setCount(2);
        request.setIntervalMs(60);
        service.publish(request);
        assertThat(publisher.bodies).hasSize(2);
        var first = mapper.readTree(publisher.bodies.get(0)).get("CREATE_TIMESTAMP").asString();
        var second = mapper.readTree(publisher.bodies.get(1)).get("CREATE_TIMESTAMP").asString();
        // 메시지를 미리 만들어 두면 두 시각이 같아진다. 전송 시점 생성이면 intervalMs만큼 벌어진다.
        assertThat(Long.parseLong(second) - Long.parseLong(first)).isGreaterThanOrEqualTo(50);
    }

    @Test
    void previewHasNoBrokerSideEffectsAndEnforcesTen() {
        var request = request("preview");
        request.setCount(10);
        assertThat(service.preview(request).messages()).hasSize(10);
        assertThat(publisher.opened.get()).isZero();
        request.setCount(11);
        assertThatThrownBy(() -> service.preview(request)).isInstanceOf(IllegalArgumentException.class);
        request.setCount(1001);
        assertThatThrownBy(() -> service.publish(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishFailureIs502ExceptionAndClosesScope() {
        publisher.failUntil = Long.MAX_VALUE;
        assertThatThrownBy(() -> service.publish(request("fail"))).isInstanceOf(BrokerConnectionException.class);
        assertThat(publisher.closed.get()).isEqualTo(1);
    }

    @Test
    void streamsPatchRatePausePayloadResumeAndStop() throws Exception {
        var request = request("lifecycle");
        request.setStartPaused(true);
        assertThat(service.create(request).state()).isEqualTo(PAUSED);
        Thread.sleep(60);
        assertThat(publisher.times).isEmpty();
        service.patch("lifecycle", new RealtimeResponses.Patch(50d, false, null));
        await(() -> service.get("lifecycle").sent() >= 3, 2000);
        assertThat(service.get("lifecycle").rateTps()).isEqualTo(50);
        service.patch("lifecycle", new RealtimeResponses.Patch(null, true, null));
        Thread.sleep(40); // allow the one possible in-flight publication to finish
        long paused = service.get("lifecycle").sent();
        Thread.sleep(100);
        assertThat(service.get("lifecycle").sent()).isEqualTo(paused);
        service.patch("lifecycle", new RealtimeResponses.Patch(100d, false, mapper.readTree("{\"updated\":true}")));
        await(() -> publisher.bodies.stream().anyMatch(s -> s.contains("updated")), 2000);
        assertThat(service.stop("lifecycle").state()).isEqualTo(STOPPED);
        await(() -> publisher.closed.get() == 1, 2000);
        assertThat(service.list()).hasSize(1);
        assertThat(service.get("lifecycle").elapsedMillis()).isPositive();
    }

    @Test
    void countAndDurationAutoCompleteIncludingPausedDuration() throws Exception {
        var count = request("count");
        count.setMaxMessages(4);
        service.create(count);
        await(() -> service.get("count").state() == COMPLETED, 2000);
        assertThat(service.get("count").sent()).isEqualTo(4);
        var duration = request("duration");
        duration.setDurationSec(0.2);
        service.create(duration);
        await(() -> service.get("duration").state() == COMPLETED, 2000);
        assertThat(service.get("duration").sent()).isBetween(15L, 25L);
        var paused = request("paused-duration");
        paused.setDurationSec(0.1);
        paused.setStartPaused(true);
        service.create(paused);
        await(() -> service.get("paused-duration").state() == COMPLETED, 2000);
        assertThat(service.get("paused-duration").sent()).isZero();
    }

    @Test
    void duplicateAndConcurrencyLimitsAndStopAll() throws Exception {
        properties.setMaxStreams(2);
        var first = request("one"); first.setStartPaused(true);
        var second = request("two"); second.setStartPaused(true);
        service.create(first);
        assertThatThrownBy(() -> service.create(first)).isInstanceOf(DuplicateTaskException.class);
        service.create(second);
        assertThatThrownBy(() -> service.create(request("three"))).isInstanceOfSatisfying(RealtimeHttpException.class, e -> assertThat(e.status()).isEqualTo(429));
        assertThat(service.stopAll()).allMatch(s -> s.state() == STOPPED);
        service.shutdown();
        assertThat(publisher.opened.get()).isZero();
    }

    @Test
    void reconnectsWithBackoffAndSeparatesFailedAttemptsFromSkippedSlots() throws Exception {
        publisher.failUntil = System.nanoTime() + 650_000_000;
        service.create(request("reconnect"));
        await(() -> service.get("reconnect").state() == RECONNECTING, 1000);
        assertThat(service.get("reconnect").lastError()).doesNotContain("secret", "private-user");
        await(() -> service.get("reconnect").state() == RUNNING && service.get("reconnect").sent() >= 2, 3000);
        var result = service.get("reconnect");
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skippedWhileDisconnected()).isGreaterThanOrEqualTo(90);
        assertThat(publisher.attemptTimes.get(1) - publisher.attemptTimes.get(0)).isGreaterThanOrEqualTo(950_000_000);
        assertThat(publisher.closed.get()).isEqualTo(1); // failed scope released before retry
    }

    @Test
    void hundredTpsForTwoSecondsIsWithinTenPercent() throws Exception {
        var request = request("accuracy");
        request.setDurationSec(2);
        service.create(request);
        await(() -> service.get("accuracy").state() == COMPLETED, 3500);
        var status = service.get("accuracy");
        assertThat(status.sent()).isBetween(180L, 220L);
        assertThat(status.actualTps()).isBetween(88d, 112d);
        assertThat(status.elapsedMillis()).isBetween(1950L, 2400L);
        System.out.printf("S2 pacing evidence: sent=%d elapsedMillis=%d actualTps=%.3f lagResets=%d%n", status.sent(), status.elapsedMillis(), status.actualTps(), status.lagResets());
    }

    @Test
    void fractionalRateDoesNotSendEarlyAndRateChangeWakesImmediately() throws Exception {
        var request = request("slow");
        request.setRateTps(0.2);
        service.create(request);
        await(() -> service.get("slow").sent() == 1, 1000);
        Thread.sleep(150);
        assertThat(service.get("slow").sent()).isEqualTo(1);
        long change = System.nanoTime();
        service.patch("slow", new RealtimeResponses.Patch(100d, null, null));
        await(() -> service.get("slow").sent() >= 3, 1000);
        assertThat(System.nanoTime() - change).isLessThan(500_000_000);
    }

    @Test
    void slowingFractionalRateDoesNotSendImmediatelyAfterPatch() throws Exception {
        var request = request("slower"); request.setRateTps(0.2);
        service.create(request);
        await(() -> service.get("slower").sent() == 1, 1000);
        service.patch("slower", new RealtimeResponses.Patch(0.1, null, null));
        Thread.sleep(250);
        assertThat(service.get("slower").sent()).isEqualTo(1);
    }

    @Test
    void rateChangeSchedulesFromLastSuccessfulSendRatherThanPatchTime() throws Exception {
        var request = request("rate-anchor"); request.setRateTps(0.2);
        service.create(request);
        await(() -> service.get("rate-anchor").sent() == 1, 1000);
        Thread.sleep(400);
        service.patch("rate-anchor", new RealtimeResponses.Patch(1d, null, null));
        await(() -> service.get("rate-anchor").sent() == 2, 1500);
        long gap = publisher.times.get(1) - publisher.times.get(0);
        assertThat(gap).isBetween(980_000_000L, 1_250_000_000L);
    }

    @Test
    void payloadReplacementKeepsScheduleAndAnUnpausedPatchDoesNotSend() throws Exception {
        var request = request("payload-schedule"); request.setRateTps(2);
        service.create(request);
        await(() -> service.get("payload-schedule").sent() == 1, 1000);
        Thread.sleep(150);
        service.patch("payload-schedule", new RealtimeResponses.Patch(null, null, mapper.readTree("{\"updated\":true}")));
        service.patch("payload-schedule", new RealtimeResponses.Patch(null, false, null));
        await(() -> service.get("payload-schedule").sent() >= 2, 1000);
        long gap = publisher.times.get(1) - publisher.times.get(0);
        assertThat(gap).isBetween(480_000_000L, 625_000_000L);
        assertThat(publisher.bodies.get(1)).contains("updated");
    }

    @Test
    void pauseDoesNotSendAndResumeSendsOneImmediatelyThenWaitsForPeriod() throws Exception {
        var request = request("resume-schedule"); request.setRateTps(0.2);
        service.create(request);
        await(() -> service.get("resume-schedule").sent() == 1, 1000);
        service.patch("resume-schedule", new RealtimeResponses.Patch(null, true, null));
        Thread.sleep(150);
        assertThat(service.get("resume-schedule").sent()).isEqualTo(1);
        long resumed = System.nanoTime();
        service.patch("resume-schedule", new RealtimeResponses.Patch(null, false, null));
        await(() -> service.get("resume-schedule").sent() == 2, 1000);
        assertThat(publisher.times.get(1) - resumed).isLessThan(250_000_000L);
        Thread.sleep(150);
        assertThat(service.get("resume-schedule").sent()).isEqualTo(2);
    }

    @Test
    void ratePatchDuringPublicationWaitsForNewPeriodAfterItsCompletion() throws Exception {
        publisher.delayMs = 200;
        var request = request("inflight-rate"); request.setRateTps(100);
        service.create(request);
        await(() -> publisher.attemptTimes.size() == 1, 1000);
        service.patch("inflight-rate", new RealtimeResponses.Patch(0.1, null, null));
        await(() -> service.get("inflight-rate").sent() == 1, 1000);
        Thread.sleep(250);
        assertThat(publisher.attemptTimes).hasSize(1);
        assertThat(service.get("inflight-rate").sent()).isEqualTo(1);
    }

    @Test
    void slowPublisherResetsLagAndNeverCatchesUpInBursts() throws Exception {
        publisher.delayMs = 25;
        var request = request("lag"); request.setMaxMessages(5);
        service.create(request);
        await(() -> service.get("lag").state() == COMPLETED, 2000);
        assertThat(service.get("lag").lagResets()).isGreaterThanOrEqualTo(4);
        for (int i = 1; i < publisher.attemptTimes.size(); i++) {
            assertThat(publisher.attemptTimes.get(i) - publisher.attemptTimes.get(i - 1)).isGreaterThanOrEqualTo(33_000_000);
        }
    }

    @Test
    void invalidPatchIsAtomicAndDoesNotChangeRateOrPayload() throws Exception {
        var request = request("atomic"); request.setStartPaused(true);
        service.create(request);
        assertThatThrownBy(() -> service.patch("atomic", new RealtimeResponses.Patch(20d, false, mapper.readTree("{\"bad\":\"{{unknown}}\"}")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get("atomic").rateTps()).isEqualTo(100);
        assertThat(service.get("atomic").state()).isEqualTo(PAUSED);
    }

    @Test
    void retainsLatestHundredEndedStreamsAndShutdownClosesActiveScopes() throws Exception {
        for (int i = 0; i < 103; i++) {
            var request = request("ended-" + i); request.setMaxMessages(1);
            service.create(request);
            int expected = i + 1;
            await(() -> publisher.closed.get() >= expected, 2000);
        }
        await(() -> service.list().size() == 100, 2000);
        assertThatThrownBy(() -> service.get("ended-0")).isInstanceOf(RealtimeHttpException.class);
        service.create(request("active"));
        await(() -> service.get("active").sent() > 0, 1000);
        service.shutdown();
        assertThat(service.get("active").state()).isEqualTo(STOPPED);
        assertThat(publisher.closed.get()).isEqualTo(publisher.opened.get());
    }

    static final class RecordingPublisher implements MessagePublisher {
        final List<Long> times = new CopyOnWriteArrayList<>();
        final List<Long> attemptTimes = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        volatile long failUntil;
        volatile long delayMs;
        @Override public Publication open(BrokerSettings settings) {
            opened.incrementAndGet();
            return new Publication() {
                @Override public void publish(String destination, String body, Map<String, String> headers) {
                    attemptTimes.add(System.nanoTime());
                    if (System.nanoTime() < failUntil) throw new IllegalStateException("password=secret username=private-user");
                    if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    times.add(System.nanoTime()); bodies.add(body);
                    assertThat(Thread.currentThread().isVirtual() || !Thread.currentThread().getName().startsWith("realtime-")).isTrue();
                }
                @Override public void close() { closed.incrementAndGet(); }
            };
        }
    }
}
