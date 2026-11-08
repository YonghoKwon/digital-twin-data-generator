package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionException;
import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.logic.DuplicateTaskException;
import com.dt.digitaltwinsimulator.logic.MessageBodyLogger;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RealtimeService {
    private final RealtimeProperties properties;
    private final DtMessageFactory factory;
    private final RealtimeFileSource files;
    private final BrokerConnectionService connections;
    private final MessagePublisher publisher;
    private final MessageBodyLogger bodyLogger;
    private final Map<String, RealtimeStream> streams = new LinkedHashMap<>();
    private boolean closed;

    public RealtimeService(RealtimeProperties properties, DtMessageFactory factory, RealtimeFileSource files,
                           BrokerConnectionService connections, MessagePublisher publisher, MessageBodyLogger bodyLogger) {
        this.properties = properties;
        this.factory = factory;
        this.files = files;
        this.connections = connections;
        this.publisher = publisher;
        this.bodyLogger = bodyLogger;
    }

    public RealtimeResponses.Publish publish(RealtimeRequest request) {
        var spec = validatePublish(request, false);
        var broker = broker(request);
        var tokens = new DtMessageFactory.TokenState();
        // Generate every message before broker I/O so a later token failure is still a 400
        // with no publication side effects, rather than being mistaken for a broker error.
        List<DtMessageFactory.Message> messages = new ArrayList<>();
        for (int i = 1; i <= request.getCount(); i++) {
            messages.add(factory.create(spec, request.getPayload(), i, tokens));
        }
        var first = messages.getFirst();
        long started = System.nanoTime();
        long sent = 0;
        // Probe validates endpoint/authentication using the common timeout policy.
        connections.verifyConnection(broker);
        try (var publication = publisher.open(broker)) {
            for (int i = 0; i < request.getCount(); i++) {
                if (i > 0 && request.getIntervalMs() > 0) Thread.sleep(request.getIntervalMs());
                var message = messages.get(i);
                publication.publish(broker.topic(), message.body(), message.headers());
                bodyLogger.logMessage("dt-publish", ++sent, message.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(e);
        } catch (RuntimeException e) {
            if (sent == 0) throw new BrokerConnectionException(e);
            return new RealtimeResponses.Publish(sent, 1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    broker.topic(), first.object(), "BROKER_ERROR");
        }
        return new RealtimeResponses.Publish(sent, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), broker.topic(), first.object(), null);
    }

    public RealtimeResponses.Preview preview(RealtimeRequest request) {
        var spec = validatePublish(request, true);
        List<JsonNode> messages = new ArrayList<>();
        var tokens = new DtMessageFactory.TokenState();
        for (int i = 1; i <= request.getCount(); i++) messages.add(factory.create(spec, request.getPayload(), i, tokens).object());
        return new RealtimeResponses.Preview(messages.size(), broker(request).topic(), List.copyOf(messages));
    }

    private DtMessageFactory.Spec validatePublish(RealtimeRequest request, boolean preview) {
        if (request.getSource() != null) throw new IllegalArgumentException("source is available only for streams.");
        int max = preview ? Math.min(10, properties.getMaxPublishCount()) : properties.getMaxPublishCount();
        if (request.getCount() < 1 || request.getCount() > max) throw new IllegalArgumentException("count must be between 1 and " + max + ".");
        if (request.getIntervalMs() < 0) throw new IllegalArgumentException("intervalMs must be nonnegative.");
        var spec = factory.specification(request);
        factory.validatePayload(spec, request.getPayload());
        return spec;
    }

    public RealtimeResponses.Stream create(RealtimeRequest request) throws IOException {
        var spec = factory.specification(request);
        validateRate(request.getRateTps());
        if (!Double.isFinite(request.getDurationSec()) || request.getDurationSec() < 0 || request.getDurationSec() > Long.MAX_VALUE / 1_000_000_000d || request.getMaxMessages() < 0) {
            throw new IllegalArgumentException("durationSec and maxMessages must be nonnegative and within supported range.");
        }
        String id = request.getStreamId() == null ? UUID.randomUUID().toString() : request.getStreamId();
        if (!id.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("streamId must use 1..128 letters, digits, underscore, dot or hyphen.");
        if (request.getSource() != null && request.getPayload() != null) throw new IllegalArgumentException("Use either source or payload.");
        List<JsonNode> rows = request.getSource() == null ? Collections.singletonList(request.getPayload()) : files.load(request.getSource());
        for (JsonNode row : rows) {
            if (request.getSource() == null) factory.validatePayload(spec, row);
            else factory.validateShape(spec, row);
        }
        rows = rows.stream().map(JsonNode::deepCopy).toList();
        var broker = broker(request);
        // Invalid URLs are input errors; no synchronous connection probe for reconnecting streams.
        try (var ignored = connections.createConnectionFactory(broker)) { /* validate provider configuration */ }
        RealtimeStream stream;
        synchronized (streams) {
            if (closed) throw new RealtimeHttpException(503, "SHUTTING_DOWN", "Application is shutting down.");
            var existing = streams.get(id);
            if (existing != null && !existing.finished()) throw new DuplicateTaskException(id);
            if (streams.values().stream().filter(s -> !s.finished()).count() >= properties.getMaxStreams()) {
                throw new RealtimeHttpException(429, "STREAM_LIMIT_EXCEEDED", "Maximum concurrent streams reached.");
            }
            stream = new RealtimeStream(id, broker, spec, rows, request, factory, publisher, bodyLogger, this::prune);
            streams.put(id, stream);
            stream.start();
        }
        return stream.snapshot();
    }

    public List<RealtimeResponses.Stream> list() {
        List<RealtimeStream> copy;
        synchronized (streams) { copy = List.copyOf(streams.values()); }
        return copy.stream().map(RealtimeStream::snapshot).toList();
    }

    public RealtimeResponses.Stream get(String id) { return find(id).snapshot(); }

    public RealtimeResponses.Stream patch(String id, RealtimeResponses.Patch patch) {
        var stream = find(id);
        if (patch.rateTps() != null) validateRate(patch.rateTps());
        if (patch.payload() != null) {
            // Validate against the original envelope before changing any live field.
            var status = stream.snapshot();
            factory.validatePayload(new DtMessageFactory.Spec(status.messageId(), stream.envelope(), Map.of(), false), patch.payload());
        }
        stream.patch(patch);
        return stream.snapshot();
    }

    public RealtimeResponses.Stream stop(String id) { var stream = find(id); stream.stop(); return stream.snapshot(); }
    public List<RealtimeResponses.Stream> stopAll() {
        List<RealtimeStream> copy;
        synchronized (streams) { copy = List.copyOf(streams.values()); }
        copy.forEach(RealtimeStream::stop);
        return copy.stream().map(RealtimeStream::snapshot).toList();
    }

    private RealtimeStream find(String id) {
        synchronized (streams) {
            var stream = streams.get(id);
            if (stream == null) throw new RealtimeHttpException(404, "STREAM_NOT_FOUND", "Stream was not found.");
            return stream;
        }
    }

    private void prune() {
        synchronized (streams) {
            var ended = streams.entrySet().stream().filter(e -> e.getValue().finished())
                    .sorted(Comparator.comparingLong(e -> e.getValue().endedNanos())).toList();
            for (int i = 0; i < ended.size() - 100; i++) streams.remove(ended.get(i).getKey());
        }
    }

    private BrokerSettings broker(RealtimeRequest request) {
        return connections.resolve(request.getActiveMQIp(), request.getId(), request.getPw(), request.getTopic());
    }

    private void validateRate(double rate) {
        if (!Double.isFinite(rate) || rate < 1_000_000_000d / Long.MAX_VALUE || rate > properties.getMaxRateTps()) {
            throw new IllegalArgumentException("rateTps must be positive, representable in nanoseconds and at most " + properties.getMaxRateTps() + ".");
        }
    }

    @PreDestroy
    public void shutdown() {
        List<RealtimeStream> copy;
        synchronized (streams) { closed = true; copy = List.copyOf(streams.values()); }
        copy.forEach(RealtimeStream::stop);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (RealtimeStream stream : copy) {
            try {
                long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (millis > 0) stream.join(millis);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (copy.stream().anyMatch(s -> !s.finished())) log.warn("Some realtime stream scopes have not closed within shutdown timeout.");
    }
}
