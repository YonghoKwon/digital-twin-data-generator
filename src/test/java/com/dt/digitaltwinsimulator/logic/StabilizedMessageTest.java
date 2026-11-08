package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import com.dt.digitaltwinsimulator.config.MessageProperties;
import com.dt.digitaltwinsimulator.config.SimulatorFileProperties;
import com.dt.digitaltwinsimulator.entity.TaskExecutionStatus;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestDto;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileAndDataDto;
import com.dt.digitaltwinsimulator.entity.dto.ActiveMQRequestFileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class StabilizedMessageTest {
    @TempDir Path temp;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MessagePublisher publisher = mock(MessagePublisher.class);
    private final MessagePublisher.Publication publication = mock(MessagePublisher.Publication.class);
    private final MessageProperties messageProperties = new MessageProperties();
    private final TaskExecutionStatusLogic statuses = new TaskExecutionStatusLogic();
    private final TaskCancellationLogic tasks = new TaskCancellationLogic();
    private ActiveMQRequestLogic logic;
    private Path base;

    @BeforeEach
    void setup() throws Exception {
        base = Files.createDirectory(temp.resolve("base"));
        var files = new SimulatorFileProperties();
        files.setBaseDir(base.toString());
        logic = new ActiveMQRequestLogic(tasks, statuses, new BrokerConnectionService(new ActiveMqBrokerProperties()),
                publisher, files, messageProperties, new MessageBodyLogger(0), mapper);
        when(publisher.open(any())).thenReturn(publication);
        Files.writeString(base.resolve("message.json"), "\"ok\":true");
    }

    @Test
    void acceptsRelativeAndInternalAbsolutePaths() throws Exception {
        assertThat(logic.dryRunFileTopic(file(".", "message.json"), 1).getReturnedCount()).isEqualTo(1);
        assertThat(logic.dryRunFileTopic(file(base.toString(), "message.json"), 1).getReturnedCount()).isEqualTo(1);
    }

    @Test
    void rejectsTraversalAndExternalAbsolutePathsIncludingOtherDrive() throws Exception {
        Files.writeString(temp.resolve("outside.json"), "\"outside\":true");
        for (var dto : List.of(file("..", "outside.json"), file(temp.toString(), "outside.json"),
                file(".", "../outside.json"), file("Z:/outside", "outside.json"),
                file(".", temp.resolve("outside.json").toString()))) {
            assertThatThrownBy(() -> logic.dryRunFileTopic(dto, 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsSymlinkFileAndDirectoryEscapes() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("message.json"), "\"secret\":true");
        try {
            Files.createSymbolicLink(base.resolve("link.json"), outside.resolve("message.json"));
            Files.createSymbolicLink(base.resolve("link-dir"), outside);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        assertThatThrownBy(() -> logic.dryRunFileTopic(file(".", "link.json"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logic.dryRunFileTopic(file("link-dir", "message.json"), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escapesQuotesBackslashesAndAllControlCharactersInsideStringsOnly() throws Exception {
        var method = ActiveMQRequestLogic.class.getDeclaredMethod("renderTemplate", String.class, String[].class);
        method.setAccessible(true);
        String text = "quote\" backslash\\ newline\n\t\r\b\f\u0001";
        String json = (String) method.invoke(logic, "{\"text\":\"prefix {{x}} suffix\",\"n\":{{n}},\"b\":{{b}}}",
                new String[]{text, "123", "true"});
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("text").asString()).isEqualTo("prefix " + text + " suffix");
        assertThat(node.get("n").isNumber()).isTrue();
        assertThat(node.get("b").booleanValue()).isTrue();
    }

    @Test
    void defaultExtraFieldsRetainExactOriginalBytesAndOrder() throws Exception {
        var method = ActiveMQRequestLogic.class.getDeclaredMethod("createFileDataMessage", String.class, String.class);
        method.setAccessible(true);
        String body = (String) method.invoke(logic, "TC", "\"temperature\":\"25.1\"");
        String timestamp = mapper.readTree(body).get("CREATE_TIMESTAMP").asString();
        String expected = "{\"CREATE_TIMESTAMP\":\"" + timestamp + "\",\"MESSAGE_ID\":\"TC\",\"DATA_MAP\":{\"" + timestamp
                + "\":{\"transaction_code\":\"TC\",\"works_code\":\"K\",\"sndr_inform_edit_pgm_id\":\"\","
                + "\"eai_interface_id\":\"\",\"interface_data_dir_actual_type\":\"\",\"interface_data_ocr_res_flag\":\"\","
                + "\"interface_data_send_seq\":\"0\",\"interface_data_upd_tp\":\"\",\"interface_data_t_len\":\"760\","
                + "\"attribute\":\" \",\"bsc_gw_data_attr\":\" \",\"it_com_eai_ifc_var_item_usg_f\":\" \",\"temperature\":\"25.1\"}}}";
        assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extraFieldsCanBeOverriddenAndAddedInOrder() throws Exception {
        messageProperties.getFileDataExtraFields().put("works_code", "CUSTOM");
        messageProperties.getFileDataExtraFields().put("custom", "a\"b");
        Files.writeString(base.resolve("format.txt"), "\"n\":{{n}}");
        Files.writeString(base.resolve("data.csv"), "n\n1");
        JsonNode node = payload(logic.dryRunFileAndDataTopic(fileData(), 1).getMessages().getFirst());
        assertThat(node.get("works_code").asString()).isEqualTo("CUSTOM");
        assertThat(node.get("custom").asString()).isEqualTo("a\"b");
    }

    @Test
    void malformedFileMessageFailsTaskBeforePublishingAndReleasesId() throws Exception {
        Files.writeString(base.resolve("message.json"), "\"bad\": nope");
        assertThatThrownBy(() -> logic.sendFileTopic("file", file(".", "message.json"))).isInstanceOf(RuntimeException.class);
        assertThat(statuses.findAll().getFirst().getStatus()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(tasks.getRunningTaskIds()).isEmpty();
        verify(publication, never()).publish(any(), any(), any());
        verify(publication).close();
    }

    @Test
    void invalidLaterFileDataRowFailsTaskWithoutSendingInvalidJson() throws Exception {
        Files.writeString(base.resolve("format.txt"), "\"n\":{{n}}");
        Files.writeString(base.resolve("data.csv"), "n\n1\ninvalid");
        assertThat(logic.dryRunFileAndDataTopic(fileData(), 1).getReturnedCount()).isEqualTo(1);
        assertThatThrownBy(() -> logic.sendFileAndDataTopic("data", fileData())).isInstanceOf(RuntimeException.class);
        assertThat(statuses.findAll().getFirst().getStatus()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(statuses.findAll().getFirst().getSentCount()).isEqualTo(1);
        verify(publication, times(1)).publish(any(), any(), any());
        assertThat(tasks.getRunningTaskIds()).isEmpty();
    }

    @Test
    void typedValuesAreOptInForValueRows() {
        var dto = structured();
        dto.setValue(List.of(Map.of(0, "12,2.5,true,text,20261108")));
        JsonNode legacy = payload(logic.dryRunTopic(dto, 1).getMessages().getFirst());
        for (JsonNode value : legacy) assertThat(value.isString()).isTrue();
        dto.setTypedValues(true);
        JsonNode typed = payload(logic.dryRunTopic(dto, 1).getMessages().getFirst());
        assertThat(typed.get("i").longValue()).isEqualTo(12);
        assertThat(typed.get("d").doubleValue()).isEqualTo(2.5);
        assertThat(typed.get("b").booleanValue()).isTrue();
        assertThat(typed.get("s").isString()).isTrue();
        assertThat(typed.get("date").isString()).isTrue();
    }

    @Test
    void typedRandomValuesHaveJsonTypesAndInvalidTypedValuesAreRejected() {
        var dto = structured();
        dto.setTypedValues(true);
        JsonNode typed = payload(logic.dryRunTopic(dto, 1).getMessages().getFirst());
        assertThat(typed.get("i").isIntegralNumber()).isTrue();
        assertThat(typed.get("d").isNumber()).isTrue();
        assertThat(typed.get("b").isBoolean()).isTrue();
        dto.setValue(List.of(Map.of(0, "12,NaN,true,text,20261108")));
        assertThatThrownBy(() -> logic.dryRunTopic(dto, 1)).isInstanceOf(IllegalArgumentException.class);
        dto.setValue(List.of(Map.of(0, "12,2.5,not-a-boolean,text,20261108")));
        assertThatThrownBy(() -> logic.dryRunTopic(dto, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private ActiveMQRequestDto structured() {
        var dto = new ActiveMQRequestDto();
        dto.setTcName("TC");
        dto.setFormat(List.of(Map.of("dataId", "i,d,b,s,date", "dataType", "Integer,Double,Boolean,String,Date",
                "randomBoolean", "0,0,0,0,0", "randomCondition", "1..3,1..3,,3,yyyyMMdd")));
        return dto;
    }

    private ActiveMQRequestFileDto file(String directory, String name) {
        var dto = new ActiveMQRequestFileDto();
        dto.setTcName("TC");
        dto.setFilePath(directory);
        dto.setFileName(name);
        return dto;
    }

    private ActiveMQRequestFileAndDataDto fileData() {
        var dto = new ActiveMQRequestFileAndDataDto();
        dto.setTcName("TC");
        dto.setFilePath(".");
        dto.setFormatFileName("format.txt");
        dto.setDataFileName("data.csv");
        return dto;
    }

    private JsonNode payload(JsonNode message) {
        return message.get("DATA_MAP").properties().iterator().next().getValue();
    }
}
