package com.dt.digitaltwinsimulator.subscribe;

import com.dt.digitaltwinsimulator.broker.*;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SubscriptionService implements SmartLifecycle {
    private final BrokerConnectionService connections;
    private final SubscribeProperties properties;
    private final JsonMapper mapper;
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private volatile boolean running;

    public SubscriptionService(BrokerConnectionService connections, SubscribeProperties properties, JsonMapper mapper) {
        this.connections = connections; this.properties = properties; this.mapper = mapper;
    }

    public synchronized Map<String, Object> create(SubscriptionRequest request) {
        if (request == null || request.topic() == null || request.topic().isBlank())
            throw new IllegalArgumentException("topic is required.");
        String id = request.subscriptionId() == null ? UUID.randomUUID().toString() : request.subscriptionId();
        if (!id.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("subscriptionId must contain 1-128 letters, numbers, dots, underscores or hyphens.");
        if (subscriptions.containsKey(id))
            throw new SubscriptionException(409, "DUPLICATE_SUBSCRIPTION_ID", "Subscription already exists.");
        if (subscriptions.size() >= properties.getMaxSubscriptions())
            throw new SubscriptionException(429, "SUBSCRIPTION_LIMIT_EXCEEDED", "Maximum subscriptions reached.");
        int bufferSize = request.bufferSize() == null ? 1000 : request.bufferSize();
        int maxBodyBytes = request.maxBodyBytes() == null ? 65536 : request.maxBodyBytes();
        if (bufferSize < 1 || bufferSize > properties.getMaxBuffer())
            throw new IllegalArgumentException("bufferSize must be between 1 and " + properties.getMaxBuffer() + ".");
        if (maxBodyBytes < 1) throw new IllegalArgumentException("maxBodyBytes must be positive.");
        BrokerSettings settings = connections.resolve(request.activeMQIp(), request.id(), request.pw(), request.topic());
        var subscription = new Subscription(id, settings, bufferSize, maxBodyBytes);
        try { subscription.connect(); }
        catch (Exception e) { subscription.closeConnection(); throw new BrokerConnectionException(e); }
        subscriptions.put(id, subscription);
        subscription.worker = Thread.ofVirtual().name("subscription-" + id).start(subscription::receive);
        return subscription.summary();
    }

    public synchronized List<Map<String, Object>> list() {
        return subscriptions.values().stream().map(Subscription::summary).toList();
    }

    private synchronized Subscription find(String id) {
        Subscription subscription = subscriptions.get(id);
        if (subscription == null) throw new SubscriptionException(404, "SUBSCRIPTION_NOT_FOUND", "Subscription not found.");
        return subscription;
    }

    public List<ReceivedMessage> messages(String id, int limit, Long afterSeq, String messageId) {
        if (limit < 1 || limit > properties.getMaxBuffer())
            throw new IllegalArgumentException("limit must be between 1 and " + properties.getMaxBuffer() + ".");
        if (afterSeq != null && afterSeq < 0) throw new IllegalArgumentException("afterSeq must be nonnegative.");
        return find(id).messages(limit, afterSeq, messageId);
    }

    public void clear(String id) { find(id).clear(); }
    public SseEmitter stream(String id) { return find(id).stream(); }

    public void delete(String id) {
        Subscription subscription;
        synchronized (this) { subscription = find(id); subscriptions.remove(id); }
        subscription.stop();
    }

    @Override public void start() { running = true; }
    @Override public boolean isRunning() { return running; }
    // Close live SSE requests before the web server begins graceful shutdown.
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop() {
        List<Subscription> closing;
        synchronized (this) { running = false; closing = new ArrayList<>(subscriptions.values()); subscriptions.clear(); }
        closing.forEach(Subscription::stop);
    }

    private final class Subscription {
        private final String id;
        private final BrokerSettings settings;
        private final int bufferSize, maxBodyBytes;
        private final Deque<ReceivedMessage> buffer = new ArrayDeque<>();
        private final Set<StreamClient> streams = ConcurrentHashMap.newKeySet();
        private volatile boolean stopped;
        private volatile String state = "CONNECTED", lastError;
        private Instant lastReceivedAt;
        private long receivedCount;
        private ActiveMQConnectionFactory factory;
        private Connection connection;
        private Session session;
        private MessageConsumer consumer;
        private Thread worker;
        private volatile boolean connectionFailed;

        private Subscription(String id, BrokerSettings settings, int bufferSize, int maxBodyBytes) {
            this.id = id; this.settings = settings; this.bufferSize = bufferSize; this.maxBodyBytes = maxBodyBytes;
        }

        private synchronized void connect() throws JMSException {
            connectionFailed = false;
            factory = connections.createConnectionFactory(settings);
            connection = factory.createConnection();
            connection.setExceptionListener(error -> connectionFailed = true);
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            consumer = session.createConsumer(session.createTopic(settings.topic()));
            connection.start();
            state = "CONNECTED"; lastError = null;
        }

        private void receive() {
            long backoff = 1000;
            try {
                while (!stopped) {
                    try {
                        if (connectionFailed) throw new JMSException("Broker connection lost.");
                        Message message = consumer.receive(500);
                        if (message != null) record(message);
                        backoff = 1000;
                    } catch (Exception e) {
                        if (stopped) break;
                        state = "RECONNECTING";
                        lastError = "Broker connection or receive failed (" + e.getClass().getSimpleName() + ").";
                        closeConnection();
                        while (!stopped) {
                            try { Thread.sleep(backoff); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                            if (stopped) break;
                            try { connect(); break; }
                            catch (Exception reconnectError) {
                                closeConnection();
                                backoff = Math.min(30000, backoff * 2);
                            }
                        }
                    }
                }
            } finally {
                closeConnection();
                if (stopped) state = "STOPPED";
            }
        }

        private void record(Message message) throws JMSException {
            String type = "OTHER", encoding = "BINARY", body = null, base64 = null, messageId = null;
            long size = 0;
            boolean truncated = false;
            JsonNode json = null;
            if (message instanceof TextMessage text) {
                type = "TEXT";
                encoding = "UTF8_TEXT";
                String value = text.getText() == null ? "" : text.getText();
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                size = bytes.length;
                truncated = size > maxBodyBytes;
                body = utf8Head(bytes, maxBodyBytes);
                try {
                    JsonNode parsed = mapper.readTree(value);
                    if (parsed != null && parsed.isObject() && parsed.hasNonNull("MESSAGE_ID"))
                        messageId = parsed.get("MESSAGE_ID").asText();
                    if (!truncated) json = parsed;
                } catch (RuntimeException ignored) { /* Non-JSON text is still captured. */ }
            } else if (message instanceof BytesMessage bytes) {
                type = "BYTES";
                size = bytes.getBodyLength();
                // Keep the existing binary head even when maxBodyBytes is smaller than 256.
                byte[] head = new byte[(int)Math.min(Math.max(maxBodyBytes, 256), size)];
                int offset = 0;
                while (offset < head.length) {
                    byte[] chunk = new byte[head.length - offset];
                    int read = bytes.readBytes(chunk);
                    if (read <= 0) throw new JMSException("Incomplete BytesMessage body.");
                    System.arraycopy(chunk, 0, head, offset, read);
                    offset += read;
                }
                int textLength = Math.min(head.length, maxBodyBytes);
                truncated = size > textLength;
                try {
                    body = strictUtf8Head(head, textLength, !truncated);
                    encoding = "UTF8_TEXT";
                    if (!truncated) {
                        try {
                            json = mapper.readTree(body);
                            if (json != null && json.isObject() && json.hasNonNull("MESSAGE_ID"))
                                messageId = json.get("MESSAGE_ID").asText();
                        } catch (RuntimeException ignored) { /* Valid UTF-8 need not be JSON. */ }
                    }
                } catch (CharacterCodingException invalidUtf8) {
                    byte[] binaryHead = Arrays.copyOf(head, Math.min(256, head.length));
                    base64 = Base64.getEncoder().encodeToString(binaryHead);
                    truncated = size > binaryHead.length;
                }
            }
            var headers = new LinkedHashMap<String, Object>();
            for (Enumeration<?> names = message.getPropertyNames(); names.hasMoreElements();) {
                String key = names.nextElement().toString();
                headers.put(key, sensitive(key) ? "****" : message.getObjectProperty(key));
            }
            String topic = message.getJMSDestination() instanceof Topic destination ? destination.getTopicName() : settings.topic();
            ReceivedMessage received;
            synchronized (this) {
                received = new ReceivedMessage(++receivedCount, Instant.now(), topic, type, encoding, size, body, json,
                        base64, messageId, Collections.unmodifiableMap(headers), truncated);
                lastReceivedAt = received.receivedAt();
                if (buffer.size() >= bufferSize) buffer.removeFirst();
                buffer.addLast(received);
            }
            for (StreamClient stream : streams) stream.offer(received);
        }

        private synchronized Map<String, Object> summary() {
            var summary = new LinkedHashMap<String, Object>();
            summary.put("subscriptionId", id); summary.put("topic", settings.topic()); summary.put("status", state);
            summary.put("receivedCount", receivedCount); summary.put("lastReceivedAt", lastReceivedAt);
            summary.put("lastError", lastError); summary.put("bufferSize", bufferSize);
            summary.put("bufferedCount", buffer.size()); summary.put("maxBodyBytes", maxBodyBytes);
            return summary;
        }

        private synchronized List<ReceivedMessage> messages(int limit, Long afterSeq, String messageId) {
            return buffer.stream().filter(m -> afterSeq == null || m.seq() > afterSeq)
                    .filter(m -> messageId == null || messageId.equals(m.messageId())).limit(limit).toList();
        }

        private synchronized void clear() { buffer.clear(); }

        private synchronized SseEmitter stream() {
            if (stopped) throw new SubscriptionException(404, "SUBSCRIPTION_NOT_FOUND", "Subscription not found.");
            var stream = new StreamClient();
            streams.add(stream);
            stream.start();
            return stream.emitter;
        }

        private void stop() {
            stopped = true; state = "STOPPED";
            if (worker != null) worker.interrupt();
            closeConnection();
            if (worker != null) try { worker.join(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            streams.forEach(StreamClient::close);
        }

        private synchronized void closeConnection() {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            if (factory != null) try { factory.close(); } catch (Exception ignored) {}
            consumer = null; session = null; connection = null; factory = null;
        }

        // Slow/disconnected SSE readers never hold up the JMS consumer or grow an unbounded queue.
        private final class StreamClient {
            private final SseEmitter emitter = new SseEmitter(0L);
            private final BlockingQueue<ReceivedMessage> pending = new ArrayBlockingQueue<>(Math.min(bufferSize, 256));
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            private Thread sender;
            private void start() {
                emitter.onCompletion(this::close);
                emitter.onTimeout(this::close);
                emitter.onError(e -> close());
                sender = Thread.ofVirtual().name("subscription-sse-" + id).start(() -> {
                    try {
                        emitter.send(SseEmitter.event().name("ready").data(Map.of("subscriptionId", id)));
                        while (!closed.get()) {
                            var next = pending.poll(15, TimeUnit.SECONDS);
                            if (next == null) emitter.send(SseEmitter.event().comment("keepalive"));
                            else emitter.send(SseEmitter.event().id(Long.toString(next.seq())).name("message").data(next));
                        }
                    } catch (IOException | java.lang.IllegalStateException e) { close(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { close(); }
                });
            }
            private void offer(ReceivedMessage message) { if (!closed.get() && !pending.offer(message)) close(); }
            private void close() {
                if (!closed.compareAndSet(false, true)) return;
                streams.remove(this);
                if (sender != null) sender.interrupt();
                // complete() can wait on an in-flight socket write. Keep it off the JMS receiver.
                Thread.ofVirtual().name("subscription-sse-close-" + id).start(emitter::complete);
            }
        }
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.contains("passcode") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("authorization") || normalized.contains("credential")
                || normalized.contains("apikey") || normalized.equals("pw") || normalized.equals("id")
                || normalized.equals("user") || normalized.equals("username") || normalized.equals("login");
    }

    private static String utf8Head(byte[] bytes, int max) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(bytes, 0, Math.min(bytes.length, max))).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8 text.", e); }
    }

    private static String strictUtf8Head(byte[] bytes, int length, boolean complete) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder(); // REPORT invalid input, never insert replacement characters.
        var output = CharBuffer.allocate(length);
        var result = decoder.decode(ByteBuffer.wrap(bytes, 0, length), output, complete);
        if (result.isError()) result.throwException();
        // An incomplete final code point is allowed only at a truncated preview boundary.
        return output.flip().toString();
    }
}

