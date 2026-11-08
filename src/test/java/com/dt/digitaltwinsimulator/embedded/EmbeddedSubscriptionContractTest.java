package com.dt.digitaltwinsimulator.embedded;

import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.subscribe.*;
import jakarta.jms.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.com.dt.digitaltwinsimulator=INFO", "simulator.http-logging.enabled=true",
                "simulator.http-logging.include-request-body=true", "simulator.http-logging.include-response-body=true",
                "simulator.subscribe.max-subscriptions=2",
                "simulator.subscribe.max-buffer=1000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmbeddedSubscriptionContractTest {
    static final int CORE = freePort(), STOMP = differentPort(CORE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("simulator.embedded-broker.enabled", () -> true);
        registry.add("simulator.embedded-broker.core-port", () -> CORE);
        registry.add("simulator.embedded-broker.stomp-port", () -> STOMP);
    }

    @LocalServerPort int port;
    @Autowired BrokerConnectionService connections;
    @Autowired JmsMessagePublisher publisher;
    @Autowired SubscriptionService subscriptions;
    @Autowired EmbeddedBrokerService broker;
    @Autowired JsonMapper mapper;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach void clear() {
        subscriptions.list().forEach(s -> subscriptions.delete((String)s.get("subscriptionId")));
    }

    @Test void jmsEnvelopeAndUnderscoreHeaderReachStompWebSocketUnchanged() throws Exception {
        String topic = "s4.envelope";
        String envelope = "{\"MESSAGE_ID\":\"KE2D1Z11\",\"DATA_MAP\":{\"temperature\":25.1}}";
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected(); client.subscribe(topic);
            publisher.publish(connections.resolve(null, null, null, topic), topic, envelope,
                    Map.of("dx_schema_version", "2"));
            String received = client.await("MESSAGE");
            assertThat(StompWebSocketClient.body(received)).isEqualTo(envelope);
            assertThat(StompWebSocketClient.headers(received)).containsEntry("destination", topic)
                    .containsEntry("dx_schema_version", "2");
        }
    }

    @Test void jmsRejectsHyphenatedPropertyBeforeItCanReachStomp() {
        var settings = connections.resolve(null, null, null, "s4.invalid-header");
        assertThatThrownBy(() -> publisher.publish(settings, settings.topic(), "{}", Map.of("dx-schema-version", "1")))
                .isInstanceOf(JMSRuntimeException.class).hasMessageContaining("AMQ139012")
                .hasMessageContaining("not a valid java identifier");
    }

    @Test void stompTextAndBinaryAreRecordedWithTheirJmsTypesViaApi() throws Exception {
        String id = create("types", "s4.types", 10, 65536);
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected();
            client.sendText("s4.types", "{\"MESSAGE_ID\":\"UE-1\",\"DATA_MAP\":{}}");
            client.sendBytes("s4.types", new byte[]{0, 1, 2, (byte)255});
            JsonNode result = waitMessages(id, 2);
            assertThat(result.get(0).get("type").asText()).isEqualTo("TEXT");
            assertThat(result.get(0).get("bodyJson").isObject()).isTrue();
            assertThat(result.get(0).get("messageId").asText()).isEqualTo("UE-1");
            assertThat(result.get(1).get("type").asText()).isEqualTo("BYTES");
            assertThat(result.get(1).get("sizeBytes").asLong()).isEqualTo(4);
            assertThat(result.get(1).get("bodyBase64Head").asText()).isEqualTo("AAEC/w==");
        }
    }

    @Test void oneMiBBinaryInOneWebSocketFrameReachesSubscriptionWithoutDisconnect() throws Exception {
        String id = create("large", "s4.large", 10, 65536);
        byte[] binary = new byte[1024 * 1024];
        new Random(44).nextBytes(binary);
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected(); client.sendBytes("s4.large", binary);
            JsonNode message = waitMessages(id, 1).get(0);
            assertThat(message.get("sizeBytes").asLong()).isEqualTo(binary.length);
            assertThat(message.get("type").asText()).isEqualTo("BYTES");
            assertThat(message.get("encoding").asText()).isEqualTo("BINARY");
            assertThat(message.get("bodyBase64Head").asText()).isEqualTo(
                    Base64.getEncoder().encodeToString(Arrays.copyOf(binary, 256)));
            assertThat(message.get("truncated").asBoolean()).isTrue();
            client.sendText("s4.large", "still-connected");
            assertThat(waitMessages(id, 2).get(1).get("body").asText()).isEqualTo("still-connected");
        }
    }

    @Test void contentLengthStompJsonIsUtf8TextAndQueryableByMessageId() throws Exception {
        String id = create("bytes-json", "s4.bytes-json", 10, 65536);
        String body = "{\"MESSAGE_ID\":\"UE-BYTES-1\",\"DATA_MAP\":{\"label\":\"센서😀\"}}";
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected();
            client.sendBytes("s4.bytes-json", body.getBytes(StandardCharsets.UTF_8));
            waitCount(id, 1);
            JsonNode result = json(get("/dt/subscriptions/" + id + "/messages?messageId=UE-BYTES-1"));
            assertThat(result.size()).isEqualTo(1);
            JsonNode message = result.get(0);
            assertThat(message.get("type").asText()).isEqualTo("BYTES");
            assertThat(message.get("encoding").asText()).isEqualTo("UTF8_TEXT");
            assertThat(message.get("body").asText()).isEqualTo(body);
            assertThat(message.get("bodyJson")).isEqualTo(mapper.readTree(body));
            assertThat(message.get("messageId").asText()).isEqualTo("UE-BYTES-1");
            assertThat(message.get("sizeBytes").asInt()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
            assertThat(message.get("truncated").asBoolean()).isFalse();
            assertThat(message.get("bodyBase64Head").isNull()).isTrue();
        }
    }

    @Test void invalidUtf8BytesRemainBinaryWithoutTextOrJson() throws Exception {
        String id = create("bytes-invalid", "s4.bytes-invalid", 10, 64);
        byte[] binary = new byte[512];
        new Random(444).nextBytes(binary);
        binary[0] = (byte)0xff;
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected(); client.sendBytes("s4.bytes-invalid", binary);
            JsonNode message = waitMessages(id, 1).get(0);
            assertThat(message.get("encoding").asText()).isEqualTo("BINARY");
            assertThat(message.get("body").isNull()).isTrue();
            assertThat(message.get("bodyJson").isNull()).isTrue();
            assertThat(message.get("messageId").isNull()).isTrue();
            assertThat(message.get("bodyBase64Head").asText()).isEqualTo(
                    Base64.getEncoder().encodeToString(Arrays.copyOf(binary, 256)));
        }
    }

    @Test void truncatedUtf8BytesDropOnlyIncompleteFinalCodePointAndDoNotParseJson() throws Exception {
        String id = create("bytes-truncated", "s4.bytes-truncated", 10, 3);
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected(); client.sendBytes("s4.bytes-truncated", "a한글".getBytes(StandardCharsets.UTF_8));
            JsonNode message = waitMessages(id, 1).get(0);
            assertThat(message.get("encoding").asText()).isEqualTo("UTF8_TEXT");
            assertThat(message.get("body").asText()).isEqualTo("a");
            assertThat(message.get("bodyJson").isNull()).isTrue();
            assertThat(message.get("messageId").isNull()).isTrue();
            assertThat(message.get("truncated").asBoolean()).isTrue();
        }
    }

    @Test void completeBytesWithIncompleteUtf8SequenceAreBinary() throws Exception {
        String id = create("bytes-incomplete", "s4.bytes-incomplete", 10, 100);
        try (var client = new StompWebSocketClient(STOMP)) {
            client.connected(); client.sendBytes("s4.bytes-incomplete", new byte[]{(byte)0xe3, (byte)0x81});
            JsonNode message = waitMessages(id, 1).get(0);
            assertThat(message.get("encoding").asText()).isEqualTo("BINARY");
            assertThat(message.get("body").isNull()).isTrue();
            assertThat(message.get("bodyBase64Head").asText()).isEqualTo("44E=");
        }
    }

    @Test void subscriptionCrudFilteringRingBufferTruncationAndMasking() throws Exception {
        String id = create("crud", "s4.crud", 2, 35);
        var settings = connections.resolve(null, null, null, "s4.crud");
        publisher.publish(settings, settings.topic(), "{\"MESSAGE_ID\":\"old\"}", Map.of());
        publisher.publish(settings, settings.topic(), "{\"MESSAGE_ID\":\"keep\"}", Map.of("authorization", "private-token", "trace", "visible"));
        publisher.publish(settings, settings.topic(), "{\"MESSAGE_ID\":\"tail\",\"value\":\"한글한글한글한글\"}", Map.of());
        waitCount(id, 3);
        JsonNode result = json(get("/dt/subscriptions/" + id + "/messages"));
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).get("seq").asLong()).isEqualTo(2);
        assertThat(result.get(0).get("properties").get("authorization").asText()).isEqualTo("****");
        assertThat(result.get(0).get("properties").get("trace").asText()).isEqualTo("visible");
        assertThat(result.get(1).get("body").asText().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(35);
        assertThat(result.get(1).get("body").asText()).doesNotContain("\ufffd");
        assertThat(result.get(1).get("truncated").asBoolean()).isTrue();
        assertThat(result.get(1).get("bodyJson").isNull()).isTrue();
        assertThat(json(get("/dt/subscriptions/" + id + "/messages?afterSeq=2")).size()).isEqualTo(1);
        assertThat(json(get("/dt/subscriptions/" + id + "/messages?messageId=keep")).size()).isEqualTo(1);
        assertThat(request("DELETE", "/dt/subscriptions/" + id + "/messages", null).statusCode()).isEqualTo(204);
        assertThat(json(get("/dt/subscriptions/" + id + "/messages")).isEmpty()).isTrue();
        publisher.publish(settings, settings.topic(), "next", Map.of());
        assertThat(waitMessages(id, 1).get(0).get("seq").asLong()).isEqualTo(4);
        assertThat(request("DELETE", "/dt/subscriptions/" + id, null).statusCode()).isEqualTo(204);
        var missing = get("/dt/subscriptions/" + id + "/messages");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("SUBSCRIPTION_NOT_FOUND");
    }

    @Test void latestQueriesAndCursorQueriesApplyMessageIdBeforeLimit() throws Exception {
        String id = create("latest", "s4.latest", 10, 100);
        var settings = connections.resolve(null, null, null, "s4.latest");
        for (int i = 1; i <= 6; i++) {
            publisher.publish(settings, settings.topic(),
                    "{\"MESSAGE_ID\":\"" + (i % 2 == 0 ? "match" : "other") + "\",\"index\":" + i + "}", Map.of());
        }
        waitCount(id, 6);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=2")), 5, 6);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=2&afterSeq=0")), 1, 2);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=2&afterSeq=2")), 3, 4);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=2&messageId=match")), 4, 6);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=2&afterSeq=0&messageId=match")), 2, 4);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=2&afterSeq=2&messageId=match")), 4, 6);
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?messageId=missing")));
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?afterSeq=6")));
        assertSeqs(json(get("/dt/subscriptions/" + id + "/messages?limit=10")), 1, 2, 3, 4, 5, 6);
    }

    private void assertSeqs(JsonNode messages, long... expected) {
        var actual = new ArrayList<Long>();
        messages.forEach(message -> actual.add(message.get("seq").asLong()));
        assertThat(actual).containsExactly(Arrays.stream(expected).boxed().toArray(Long[]::new));
    }

    @Test void apiEnforcesValidationDuplicateAndCapacityWithErrorEnvelope() throws Exception {
        var invalid = request("POST", "/dt/subscriptions", "{\"topic\":\" \"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json(invalid).get("error").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(request("POST", "/dt/subscriptions", "{\"topic\":\"x\",\"bufferSize\":1001}").statusCode()).isEqualTo(400);
        create("one", "s4.one", 1, 100);
        assertThat(request("POST", "/dt/subscriptions", "{\"subscriptionId\":\"one\",\"topic\":\"x\"}").statusCode()).isEqualTo(409);
        create("two", "s4.two", 1, 100);
        var full = request("POST", "/dt/subscriptions", "{\"topic\":\"x\"}");
        assertThat(full.statusCode()).isEqualTo(429);
        assertThat(json(full).get("error").asText()).isEqualTo("SUBSCRIPTION_LIMIT_EXCEEDED");
        assertThat(get("/dt/subscriptions/one/messages?limit=0").statusCode()).isEqualTo(400);
        var query = get("/dt/subscriptions/one/messages?limit=invalid");
        assertThat(query.statusCode()).isEqualTo(400);
        assertThat(json(query).get("error").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test void brokerStatusIncludesSafeSettingsAndAddressMetrics() throws Exception {
        create("metrics", "s4.metrics", 10, 100);
        JsonNode status = json(get("/dt/broker"));
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("running").asBoolean()).isTrue();
        assertThat(status.get("version").asText()).isEqualTo("2.44.0");
        assertThat(status.get("websocketUrl").asText()).endsWith(":" + STOMP);
        assertThat(status.get("settings").get("maxFrameBytes").asInt()).isEqualTo(2097152);
        assertThat(status.get("settings").has("password")).isFalse();
        assertThat(status.get("dtcoreSettingsHint").asText()).contains("Login=<설정값>", "Passcode=<설정값>");
        assertThat(status.get("addresses").toString()).contains("s4.metrics", "MULTICAST", "consumerCount");
    }

    @Test void sameAcceptorAlsoAcceptsPlainStompTcp() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", STOMP)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("CONNECT\naccept-version:1.2\nhost:localhost\nlogin:artemis\npasscode:artemis\n\n\0"
                    .getBytes(StandardCharsets.UTF_8));
            var received = new ByteArrayOutputStream();
            for (int next; (next = socket.getInputStream().read()) > 0;) received.write(next);
            assertThat(received.toString(StandardCharsets.UTF_8)).startsWith("CONNECTED");
            socket.getOutputStream().write("DISCONNECT\n\n\0".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void wrongBrokerCredentialsFailWithoutCreatingSubscription() throws Exception {
        var result = request("POST", "/dt/subscriptions", "{\"topic\":\"s4.auth\",\"pw\":\"incorrect-private-password\"}");
        assertThat(result.statusCode()).isEqualTo(502);
        assertThat(result.body()).doesNotContain("incorrect-private-password");
        assertThat(subscriptions.list()).isEmpty();
    }

    @Test void existingSendAndJmsLoadApisUseEmbeddedDefaultsWhenAddressIsOmitted() throws Exception {
        String id = create("existing", "s4.existing", 10, 65536);
        var body = Map.of("tcName", "S4", "topic", "s4.existing", "messageCount", 1,
                "format", List.of(Map.of("dataId", "value", "dataType", "String", "randomBoolean", "1", "randomCondition", "4")));
        var sent = request("POST", "/activemq/request/s4-embedded-default", mapper.writeValueAsString(body));
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
        waitMessages(id, 1);
        var loaded = request("POST", "/activemq/request/jms-template-load/s4-load-default",
                "{\"tcName\":\"S4\",\"topic\":\"s4.existing\",\"messageCount\":1,\"workerCount\":1,\"payload\":\"{}\"}");
        assertThat(loaded.statusCode()).as(loaded.body()).isEqualTo(200);
        assertThat(json(loaded).get("successCount").asInt()).isEqualTo(1);
        assertThat(waitMessages(id, 2).size()).isEqualTo(2);
    }

    @Test void wildcardSubscriptionUsesArtemisDotDelimitedWords() throws Exception {
        String id = create("wildcard", "s4.sensor.*", 10, 100);
        publisher.publish(connections.resolve(null, null, null, "s4.sensor.temp"), "s4.sensor.temp", "temperature", Map.of());
        assertThat(waitMessages(id, 1).get(0).get("body").asText()).isEqualTo("temperature");
        publisher.publish(connections.resolve(null, null, null, "s4.sensor.pressure"), "s4.sensor.pressure", "pressure", Map.of());
        assertThat(waitMessages(id, 2).get(1).get("body").asText()).isEqualTo("pressure");
    }

    @Test void sseDeliversOneLiveMessageWithHttpBodyLoggingEnabled() throws Exception {
        String id = create("sse", "s4.sse", 10, 65536);
        var response = http.send(HttpRequest.newBuilder(uri("/dt/subscriptions/" + id + "/stream"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var input = response.body()) {
            var received = CompletableFuture.supplyAsync(() -> {
                try {
                    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                    StringBuilder event = new StringBuilder();
                    for (String line; (line = reader.readLine()) != null;) {
                        event.append(line).append('\n');
                        if (line.startsWith("data:") && line.contains("SSE-1")) return event.toString();
                    }
                    throw new AssertionError("Stream ended before message");
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            publisher.publish(connections.resolve(null, null, null, "s4.sse"), "s4.sse", "{\"MESSAGE_ID\":\"SSE-1\"}", Map.of());
            assertThat(received.get(10, TimeUnit.SECONDS)).contains("event:message", "SSE-1", "id:1");
            subscriptions.delete(id);
        }
    }

    @Test void lostConnectionReentersConnectedAndResumesReceiving() throws Exception {
        String id = create("reconnect", "s4.reconnect", 10, 100);
        broker.stop();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!subscriptions.list().getFirst().get("status").equals("RECONNECTING") && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(subscriptions.list().getFirst().get("status")).isEqualTo("RECONNECTING");
        assertThat(subscriptions.list().getFirst().get("lastError")).isNotNull();
        broker.start();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!subscriptions.list().getFirst().get("status").equals("CONNECTED") && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(subscriptions.list().getFirst().get("status")).isEqualTo("CONNECTED");
        publisher.publish(connections.resolve(null, null, null, "s4.reconnect"), "s4.reconnect", "resumed", Map.of());
        assertThat(waitMessages(id, 1).get(0).get("body").asText()).isEqualTo("resumed");
    }

    private String create(String id, String topic, int buffer, int body) throws Exception {
        var result = request("POST", "/dt/subscriptions", mapper.writeValueAsString(
                Map.of("subscriptionId", id, "topic", topic, "bufferSize", buffer, "maxBodyBytes", body)));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        assertThat(result.headers().firstValue("location")).hasValue("/dt/subscriptions/" + id);
        return id;
    }
    private void waitCount(String id, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (subscriptions.list().stream().anyMatch(s -> id.equals(s.get("subscriptionId"))
                    && ((Number)s.get("receivedCount")).intValue() >= count)) return;
            Thread.sleep(25);
        }
        fail("Expected " + count + " received messages");
    }
    private JsonNode waitMessages(String id, int count) throws Exception {
        waitCount(id, count);
        return json(get("/dt/subscriptions/" + id + "/messages"));
    }
    private HttpResponse<String> get(String path) throws Exception { return request("GET", path, null); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static int differentPort(int other) { int port; do { port = freePort(); } while (port == other); return port; }
}

