package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.config.*;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileAndDataDto;
import com.dt.digitaltwinsimulator.logic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeFileSourceTest {
    @TempDir Path base;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private SimulatorFileProperties properties;
    private RealtimeFileSource files;

    @BeforeEach void setup() {
        properties = new SimulatorFileProperties(); properties.setBaseDir(base.toString());
        files = new RealtimeFileSource(properties, mapper);
    }

    private RealtimeRequest.Source source() {
        var source = new RealtimeRequest.Source();
        source.setType("FILE_DATA"); source.setFilePath("."); source.setFormatFileName("format.txt"); source.setDataFileName("data.csv");
        return source;
    }

    @Test
    void headerQuotedCsvEscapingAndDateNormalizationMatchS1PayloadFields() throws Exception {
        Files.writeString(base.resolve("format.txt"), "\"n\":{{n}},\"text\":\"{{text}}\",\"date\":\"{{date}}\"");
        String quotedCsv = "\"hello, \"\"world\"\" " + '\\' + '"';
        Files.writeString(base.resolve("data.csv"), "\ufefftext,date,n\n" + quotedCsv + ",2026-05-04 19:27:00.000,42\n");
        var actual = files.load(source()).getFirst();
        var legacy = new ActiveMQRequestLogic(mock(TaskCancellationLogic.class), mock(TaskExecutionStatusLogic.class),
                mock(BrokerConnectionService.class), mock(MessagePublisher.class), properties, new MessageProperties(), new MessageBodyLogger(0), mapper);
        var dto = new ActiveMQRequestFileAndDataDto();
        dto.setTcName("TC"); dto.setFilePath("."); dto.setFormatFileName("format.txt"); dto.setDataFileName("data.csv"); dto.setMessageCount(1);
        var legacyPayload = legacy.dryRunFileAndDataTopic(dto, 10).getMessages().getFirst().get("DATA_MAP").iterator().next();
        assertThat(actual.get("n")).isEqualTo(legacyPayload.get("n"));
        assertThat(actual.get("text")).isEqualTo(legacyPayload.get("text"));
        assertThat(actual.get("date")).isEqualTo(legacyPayload.get("date"));
        assertThat(actual.get("text").asString()).isEqualTo("hello, \"world\" \\");
        assertThat(actual.get("date").asString()).isEqualTo("20260504192700000");
    }

    @Test
    void positionalRowsDuplicatePlaceholdersCommentsAndMoreThanHundredRows() throws Exception {
        Files.writeString(base.resolve("format.txt"), "{\"a\":\"{{a}}\",\"b\":{{b}}}");
        var csv = new StringBuilder("# ignored\n\n");
        for (int i = 0; i < 130; i++) csv.append("v").append(i).append(',').append(i).append('\n');
        Files.writeString(base.resolve("data.csv"), csv);
        var rows = files.load(source());
        assertThat(rows).hasSize(130);
        assertThat(rows.getLast().get("b").asInt()).isEqualTo(129);
        Files.writeString(base.resolve("format.txt"), "\"a\":\"{{same}}\",\"b\":\"{{same}}\"");
        Files.writeString(base.resolve("data.csv"), "same\nvalue\n");
        assertThat(files.load(source()).getFirst().get("b").asString()).isEqualTo("value");
    }

    @Test
    void fileStreamsLoopInOrderAndNonLoopCompletes() throws Exception {
        Files.writeString(base.resolve("format.txt"), "\"n\":{{n}},\"literal\":\"{{literal}}\"");
        Files.writeString(base.resolve("data.csv"), "n,literal\n10,{{unchanged}}\n20,{{seq}}\n");
        var broker = spy(new BrokerConnectionService(new ActiveMqBrokerProperties()));
        var publisher = new RealtimeServiceTest.RecordingPublisher();
        var service = new RealtimeService(new RealtimeProperties(), new DtMessageFactory(mapper, new RealtimeProperties()), files, broker, publisher, new MessageBodyLogger(0));
        try {
            var request = new RealtimeRequest(); request.setStreamId("loop"); request.setMessageId("TC");
            request.setSource(source()); request.setRateTps(100); request.setMaxMessages(5);
            service.create(request);
            RealtimeServiceTest.await(() -> service.get("loop").state() == RealtimeResponses.State.COMPLETED, 2000);
            assertThat(publisher.bodies).extracting(s -> mapper.readTree(s).get("DATA_MAP").iterator().next().get("n").asInt()).containsExactly(10, 20, 10, 20, 10);
            assertThat(publisher.bodies.getFirst()).contains("{{unchanged}}");
            request.setStreamId("no-loop"); request.getSource().setLoop(false); request.setMaxMessages(0);
            service.create(request);
            RealtimeServiceTest.await(() -> service.get("no-loop").state() == RealtimeResponses.State.COMPLETED, 2000);
            assertThat(service.get("no-loop").sent()).isEqualTo(2);
        } finally { service.shutdown(); }
    }

    @Test
    void rejectsTraversalExternalMissingAndMalformedRows() throws Exception {
        Files.writeString(base.resolve("format.txt"), "\"n\":{{n}},\"s\":\"{{s}}\"");
        Files.writeString(base.resolve("data.csv"), "n,s\n1,ok\n");
        var source = source(); source.setFilePath("..");
        assertThatThrownBy(() -> files.load(source)).isInstanceOf(IllegalArgumentException.class);
        source.setFilePath(base.getParent().toString());
        assertThatThrownBy(() -> files.load(source)).isInstanceOf(IllegalArgumentException.class);
        source.setFilePath("."); source.setDataFileName("absent.csv");
        assertThatThrownBy(() -> files.load(source)).isInstanceOf(java.io.IOException.class);
        source.setDataFileName("data.csv");
        for (String csv : new String[]{"n\n1\n", "n,s\n1\n", "n,s\nnot-json,ok\n", "n,s\n1,\"unclosed\n", "n,s\n"}) {
            Files.writeString(base.resolve("data.csv"), csv);
            assertThatThrownBy(() -> files.load(source)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsExternalSymlinkWhenOsPermitsCreatingLinks() throws Exception {
        Path outside = Files.createTempFile("s2-outside-", ".csv");
        try {
            Files.writeString(outside, "1\n");
            try { Files.createSymbolicLink(base.resolve("data.csv"), outside); }
            catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
                Assumptions.abort("Windows symlink creation unavailable: " + e.getClass().getSimpleName());
            }
            Files.writeString(base.resolve("format.txt"), "\"n\":{{n}}");
            assertThatThrownBy(() -> files.load(source())).isInstanceOf(IllegalArgumentException.class);
        } finally { Files.deleteIfExists(outside); }
    }
}
