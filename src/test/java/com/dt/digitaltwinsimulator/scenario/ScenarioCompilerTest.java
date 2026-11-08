package com.dt.digitaltwinsimulator.scenario;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.config.*;
import com.dt.digitaltwinsimulator.mock.MockRouteControl;
import com.dt.digitaltwinsimulator.realtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioCompilerTest {
    @TempDir Path base;
    final JsonMapper mapper = JsonMapper.builder().build();
    final ScenarioProperties properties = new ScenarioProperties();
    ScenarioCompiler compiler;
    @BeforeEach void setup() {
        var files = new SimulatorFileProperties(); files.setBaseDir(base.toString());
        compiler = new ScenarioCompiler(properties, new DtMessageFactory(mapper, new RealtimeProperties()),
                mock(MockRouteControl.class), new BrokerConnectionService(new ActiveMqBrokerProperties()), mapper, files);
    }
    JsonNode json(String value) { return mapper.readTree(value); }
    JsonNode track(String extra) {
        return json("""
            {"defaults":{"messageId":"T"},"events":[{"at":100,"type":"MARK","label":"inline"}],
             "tracks":[{"trackId":"t","type":"CSV_TIMELINE","filePath":".","dataFileName":"data.csv",
                        "timeColumn":"time",%s"topic":"dt.csv"}]}
            """.formatted(extra));
    }
    @Test void parsesMillisecondsAndBothClockFormsStrictly() {
        assertThat(ScenarioJson.at(json("123"))).isEqualTo(123);
        assertThat(ScenarioJson.at(json("\"01:02:03\""))).isEqualTo(3_723_000);
        assertThat(ScenarioJson.at(json("\"00:00:01.500\""))).isEqualTo(1500);
        for (String value : List.of("-1", "1.5", "\"00:60:00\"", "\"00:00:01.5\"", "\"1\"", "null"))
            assertThatThrownBy(() -> ScenarioJson.at(json(value))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void lazilyMergesInlineRepeatsAndUnsortedTrackRowsWithStableTies() throws Exception {
        Files.writeString(base.resolve("data.csv"), "time,value\n200,c\n100,b\n0,a\n");
        var node = (tools.jackson.databind.node.ObjectNode) track("");
        node.withArray("events").add(json("{\"at\":0,\"type\":\"MARK\",\"label\":\"repeat\",\"repeat\":{\"count\":3,\"everyMs\":100}}"));
        var plan = compiler.require(node);
        assertThat(plan.eventCount()).isEqualTo(7); assertThat(plan.durationMs()).isEqualTo(200);
        var cursor = plan.cursor(0); List<String> order = new ArrayList<>();
        while (!cursor.empty()) {
            var event = cursor.event(); order.add(event.at() + ":" + (event.publish() == null ? event.label() : event.publish().payload().get("value").asString())); cursor.advance();
        }
        assertThat(order).containsExactly("0:repeat", "0:a", "100:inline", "100:repeat", "100:b", "200:repeat", "200:c");
    }
    @Test void aBillionRepeatsUsesOneSourceAndSeekBinarySearch() {
        var plan = compiler.require(json("{\"events\":[{\"at\":0,\"type\":\"MARK\",\"label\":\"big\",\"repeat\":{\"count\":1000000000,\"everyMs\":1}}]}"));
        assertThat(plan.sources()).hasSize(1); assertThat(plan.eventCount()).isEqualTo(1_000_000_000);
        var cursor = plan.cursor(999_999_998); assertThat(cursor.time()).isEqualTo(999_999_998);
        cursor.advance(); assertThat(cursor.time()).isEqualTo(999_999_999); cursor.advance(); assertThat(cursor.empty()).isTrue();
    }
    @Test void csvUsesHeaderMappingQuotedCellsJsonEscapeAndLiteralTokens() throws Exception {
        Files.writeString(base.resolve("data.csv"), "time,message,value\n0,\"hello, \"\"world\"\"\",{{seq}}\n");
        Files.writeString(base.resolve("format.txt"), "{\"v\":\"{{value}}\",\"m\":\"{{message}}\"}");
        var plan = compiler.require(track("\"formatFileName\":\"format.txt\","));
        // The CSV event is first at 0, preceding the inline mark at 100.
        var publication = plan.cursor(0).event().publish();
        assertThat(publication.literal()).isTrue();
        assertThat(publication.payload().get("v").asString()).isEqualTo("{{seq}}");
        assertThat(publication.payload().get("m").asString()).isEqualTo("hello, \"world\"");
    }
    @Test void supportsOffsetSecondsAndDatePatternsRelativeToFirstRow() throws Exception {
        Files.writeString(base.resolve("data.csv"), "time,value\n0.125,a\n1.500,b\n");
        assertThat(compiler.require(track("\"timeFormat\":\"OFFSET_SEC\"," )).durationMs()).isEqualTo(1500);
        Files.writeString(base.resolve("data.csv"), "time,value\n20260504192700000,a\n20260504192701500,b\n");
        assertThat(compiler.require(track("\"timeFormat\":\"yyyyMMddHHmmssSSS\"," )).durationMs()).isEqualTo(1500);
        Files.writeString(base.resolve("data.csv"), "time,value\n2026-05-04 19:27:00.000,a\n2026-05-04 19:27:01.500,b\n");
        assertThat(compiler.require(track("\"timeFormat\":\"yyyy-MM-dd HH:mm:ss.SSS\"," )).durationMs()).isEqualTo(1500);
    }
    @Test void validatesDynamicTopicAndMessageIdColumnsAndStartAt() throws Exception {
        Files.writeString(base.resolve("data.csv"), "time,topic,mid\n0,dt.one,ONE\n100,dt.two,TWO\n");
        var plan = compiler.require(track("\"topicColumn\":\"topic\",\"messageIdColumn\":\"mid\",\"startAt\":\"00:00:01.000\","));
        assertThat(plan.topics()).containsExactly("dt.one", "dt.two"); assertThat(plan.durationMs()).isEqualTo(1100);
        assertThat(plan.cursor(1000).event().publish().spec().messageId()).isEqualTo("ONE");
    }
    @Test void collectsIndependentRootEventAndTrackErrors() {
        var result = compiler.validate(json("""
            {"scenarioId":"bad/id","speed":101,"loop":"bad","onDisconnect":"BOGUS",
             "events":[{"at":"bad","type":"UNKNOWN"},{"at":-1,"payload":{"a":"{{unknown}}"},"messageId":"M"},
                       {"at":0,"envelope":"DTCORE","messageId":"M","payload":[]},
                       {"at":0,"type":"MOCK_ENABLE"}],
             "tracks":[{"trackId":"t","type":"BOGUS"}]}
            """));
        assertThat(result.valid()).isFalse(); assertThat(result.errors().size()).isGreaterThanOrEqualTo(10);
        assertThat(result.errors().toString()).contains("scenarioId", "speed", "events[0].at", "Unknown event type", "Unknown value token", "DTCORE", "routeId", "tracks[0]");
    }
    @Test void validatesRepeatOverflowPartialDefaultsAndJmsHeaders() {
        assertThat(compiler.validate(json("{\"defaults\":{\"topic\":\"dt.test\"},\"events\":[{\"at\":0,\"messageId\":\"T\",\"payload\":{}}]}")).valid()).isTrue();
        for (String bad : List.of(
                "{\"defaults\":{\"headers\":{\"bad-header\":\"x\"}},\"events\":[{\"at\":0,\"type\":\"MARK\",\"label\":\"x\"}]}",
                "{\"events\":[{\"at\":0,\"type\":\"MARK\",\"label\":\"x\",\"repeat\":{\"count\":9223372036854775807,\"everyMs\":2}}]}",
                "{\"events\":[{\"at\":0,\"type\":\"MARK\",\"label\":\"x\",\"repeat\":{\"count\":0,\"everyMs\":1}}]}"))
            assertThat(compiler.validate(json(bad)).valid()).isFalse();
    }
    @Test void rejectsTrackRowCapMissingColumnsAndOutsidePaths() throws Exception {
        Files.writeString(base.resolve("data.csv"), "time,value\n0,a\n1,b\n"); properties.setMaxTrackRows(1);
        assertThat(compiler.validate(track("")).errors().toString()).contains("max-track-rows");
        properties.setMaxTrackRows(1_000_000);
        assertThat(compiler.validate(track("\"topicColumn\":\"missing\"," )).errors().toString()).contains("missing column");
        var outside = (tools.jackson.databind.node.ObjectNode) track("");
        ((tools.jackson.databind.node.ObjectNode) outside.get("tracks").get(0)).put("filePath", "..");
        assertThat(compiler.validate(outside).valid()).isFalse();
    }
    @Test void changedTrackIsRejectedInsteadOfReadingAnInvalidIndex() throws Exception {
        Files.writeString(base.resolve("data.csv"), "time,value\n0,a\n"); var plan = compiler.require(track(""));
        Files.writeString(base.resolve("data.csv"), "time,value\n0,replaced\n");
        assertThatThrownBy(() -> plan.cursor(0).event()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
    }
    @Test void bufferedIndexUsesCorrectUtf8OffsetsAcrossCrLfCommentsAndBom() throws Exception {
        Files.writeString(base.resolve("data.csv"), "\ufefftime,value\r\n# comment\r\n0,첫째\r\n\r\n200,둘째\r300,셋째");
        var plan = compiler.require(track(""));
        assertThat(plan.cursor(200).event().publish().payload().get("value").asString()).isEqualTo("둘째");
        assertThat(plan.cursor(300).event().publish().payload().get("value").asString()).isEqualTo("셋째");
    }
    @Test void emptyAndMalformedDefinitionReturnInvalidWithoutThrowing() {
        for (String root : List.of("null", "[]", "{}", "{\"events\":{}}", "{\"tracks\":false}"))
            assertThat(compiler.validate(json(root)).valid()).isFalse();
    }
}
