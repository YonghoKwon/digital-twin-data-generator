package com.dt.digitaltwinsimulator.realtime;

import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.apache.activemq.artemis.core.client.impl.ClientMessageImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DtMessageFactoryTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RealtimeProperties properties = new RealtimeProperties();
    private final DtMessageFactory factory = new DtMessageFactory(mapper, properties);

    private RealtimeRequest raw(String json) {
        var request = new RealtimeRequest();
        request.setEnvelope(RealtimeRequest.Envelope.RAW);
        request.setPayload(mapper.readTree(json));
        return request;
    }

    private JsonNode render(String json, long seq) {
        var request = raw(json);
        var spec = factory.specification(request);
        factory.validatePayload(spec, request.getPayload());
        return factory.create(spec, request.getPayload(), seq, new DtMessageFactory.TokenState()).object();
    }

    @Test
    void dtcoreEnvelopeSharesTimestampAndPreservesObject() {
        var request = new RealtimeRequest();
        request.setMessageId("TC-SENSOR");
        request.setPayload(mapper.readTree("{\"temperature\":21,\"nested\":{\"ok\":true}}"));
        var result = factory.create(factory.specification(request), request.getPayload(), 1, new DtMessageFactory.TokenState());
        var node = mapper.readTree(result.body());
        assertThat(node.get("MESSAGE_ID").asString()).isEqualTo("TC-SENSOR");
        String timestamp = node.get("CREATE_TIMESTAMP").asString();
        assertThat(timestamp).matches("\\d{17}");
        assertThat(node.get("DATA_MAP").get(timestamp)).isEqualTo(request.getPayload());
    }

    @Test
    void rawSupportsArbitraryJsonAndLiteralText() {
        for (String input : new String[]{"[1,true,null]", "42", "true", "null", "{\"x\":1}"}) {
            var request = raw(input);
            factory.validatePayload(factory.specification(request), request.getPayload());
            assertThat(factory.create(factory.specification(request), request.getPayload(), 1, new DtMessageFactory.TokenState()).body()).isEqualTo(input);
        }
        var request = raw("\"hello {{seq}}\"");
        assertThat(factory.create(factory.specification(request), request.getPayload(), 2, new DtMessageFactory.TokenState()).body()).isEqualTo("hello 2");
    }

    @Test
    void sequenceIsTypedAndRecursiveIncludingArraysAndMixedStrings() {
        var result = render("{\"n\":\"{{seq}}\",\"array\":[{\"n\":\"{{seq}}\"}],\"text\":\"s={{seq}} {{cycle:A|B}}\"}", 2);
        assertThat(result.get("n").isIntegralNumber()).isTrue();
        assertThat(result.at("/array/0/n").asLong()).isEqualTo(2);
        assertThat(result.get("text").asString()).isEqualTo("s=2 B");
    }

    @Test
    void nowAndUuidSupportPatternsContainingColons() {
        assertThat(render("\"{{now}}\"", 1).asString()).matches("\\d{17}");
        assertThat(render("\"{{now:yyyy-MM-dd'T'HH:mm:ss}}\"", 1).asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThatCode(() -> UUID.fromString(render("\"{{uuid}}\"", 1).asString())).doesNotThrowAnyException();
    }

    @Test
    void randomUsesLegacySyntaxAndReturnsTypedValues() {
        var node = render("{\"i\":\"{{random:Integer:3..3}}\",\"d\":\"{{random:Double:2.5..2.5}}\",\"b\":\"{{random:Boolean:}}\",\"s\":\"{{random:String:8}}\",\"date\":\"{{random:Date:yyyy}}\"}", 1);
        assertThat(node.get("i").asInt()).isEqualTo(3);
        assertThat(node.get("i").isIntegralNumber()).isTrue();
        assertThat(node.get("d").asDouble()).isEqualTo(2.5);
        assertThat(node.get("b").isBoolean()).isTrue();
        assertThat(node.get("s").asString()).hasSize(8);
        assertThat(node.get("date").asString()).matches("\\d{4}");
    }

    @Test
    void walkIsBoundedSmoothIndependentPerPathAndStartsAtInitialValue() {
        var request = raw("{\"a\":\"{{walk:20:10..30:0.5}}\",\"b\":\"{{walk:20:10..30:0.5}}\"}");
        var spec = factory.specification(request);
        factory.validatePayload(spec, request.getPayload());
        var state = new DtMessageFactory.TokenState();
        double previous = 20;
        for (int i = 1; i <= 200; i++) {
            var node = factory.create(spec, request.getPayload(), i, state).object();
            double value = node.get("a").asDouble();
            assertThat(value).isBetween(10d, 30d).isCloseTo(previous, within(0.500001));
            if (i == 1) assertThat(value).isEqualTo(20);
            previous = value;
        }
        assertThat(factory.create(spec, request.getPayload(), 1, new DtMessageFactory.TokenState()).object().get("a").asDouble()).isEqualTo(20);
    }

    @Test
    void cycleRepeatsAndPickStaysInChoices() {
        for (int i = 1; i <= 12; i++) {
            assertThat(render("\"{{cycle:A|B|C}}\"", i).asString()).isEqualTo(new String[]{"A", "B", "C"}[(i - 1) % 3]);
            assertThat(render("\"{{pick:A|B|C}}\"", i).asString()).isIn("A", "B", "C");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{unknown}}", "{{random:Unknown:1}}", "{{random:Integer:bad}}", "{{now:bad pattern}}", "{{walk:20:0..10:1}}", "{{walk:2:0..10:-1}}", "{{walk:NaN:0..10:1}}", "{{cycle:A|}}", "{{pick:}}", "x {{seq", "{{}}"})
    void badTokensFailDuringValidation(String token) {
        var request = raw(mapper.writeValueAsString(token));
        assertThatThrownBy(() -> factory.validatePayload(factory.specification(request), request.getPayload())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresObjectAndMessageIdForDtcore() {
        var request = new RealtimeRequest();
        assertThatThrownBy(() -> factory.specification(request)).isInstanceOf(IllegalArgumentException.class);
        request.setMessageId("TC");
        assertThatThrownBy(() -> factory.validatePayload(factory.specification(request), mapper.readTree("[]"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void artemisRejectsHyphensAndFallbackHeadersAreAcceptedByActualClient() throws Exception {
        var jms = new ActiveMQMessage(new ClientMessageImpl(), null);
        jms.clearProperties(); // received-message constructor starts with read-only properties
        for (String name : new String[]{"dx-schema-version", "dx-message-id", "dx-sent-at"}) {
            assertThatThrownBy(() -> jms.setStringProperty(name, "test")).isInstanceOf(jakarta.jms.JMSRuntimeException.class)
                    .hasMessageContaining("not a valid java identifier");
        }
        var request = raw("{}");
        request.setStandardHeaders(true);
        request.setHeaders(Map.of("traceId", "trace-1"));
        properties.setSchemaVersion("2");
        var message = factory.create(factory.specification(request), request.getPayload(), 1, new DtMessageFactory.TokenState());
        for (var header : message.headers().entrySet()) jms.setStringProperty(header.getKey(), header.getValue());
        assertThat(jms.getStringProperty("dx_schema_version")).isEqualTo("2");
        assertThat(jms.getStringProperty("traceId")).isEqualTo("trace-1");
        assertThatCode(() -> UUID.fromString(jms.getStringProperty("dx_message_id"))).doesNotThrowAnyException();
        assertThatCode(() -> Instant.parse(jms.getStringProperty("dx_sent_at"))).doesNotThrowAnyException();
        request.setStandardHeaders(false);
        request.setHeaders(Map.of("dx-message-id", "manual"));
        assertThat(factory.specification(request).headers()).containsEntry("dx_message_id", "manual");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-header", "1invalid", "has space", "AND", "JMSXDeliveryCount"})
    void rejectsInvalidOrReservedHeaders(String name) {
        var request = raw("{}");
        request.setHeaders(Map.of(name, "value"));
        assertThatThrownBy(() -> factory.specification(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standardHeadersCannotBeOverriddenAndAliasesCannotCollide() {
        var request = raw("{}");
        request.setStandardHeaders(true);
        request.setHeaders(Map.of("dx_message_id", "spoof"));
        assertThatThrownBy(() -> factory.specification(request)).isInstanceOf(IllegalArgumentException.class);
        request.setStandardHeaders(false);
        request.setHeaders(Map.of("dx-message-id", "a", "dx_message_id", "b"));
        assertThatThrownBy(() -> factory.specification(request)).isInstanceOf(IllegalArgumentException.class);
    }
}
