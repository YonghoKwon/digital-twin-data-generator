package com.dt.digitaltwinsimulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "simulator.http-logging.enabled=true",
        "simulator.http-logging.include-request-body=true",
        "simulator.http-logging.include-response-body=true"
})
@AutoConfigureMockMvc
class DryRunJsonContractTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @TempDir
    Path tempDir;

    @Test
    void randomDryRunReturnsJsonObjectsWithTheExistingEnvelope() throws Exception {
        JsonNode root = dryRun("", Map.of(
                "tcName", "TC001",
                "repeatBoolean", true,
                "messageCount", 3,
                "format", List.of(Map.of(
                        "dataId", "temperature,status",
                        "dataType", "Double,String",
                        "randomBoolean", "1,1",
                        "randomCondition", "20.0..35.0,8"
                )),
                "value", List.of(),
                "unknownField", "ignored as in Boot 3"
        ));

        assertEnvelope(root, "RANDOM_FORMAT", 3, 1, 3);
        JsonNode payload = payload(root, 0);
        assertThat(Double.parseDouble(payload.get("temperature").asString())).isBetween(20.0, 35.0);
        assertThat(payload.get("status").asString()).hasSize(8);
    }

    @Test
    void valueRowsRetainIntegerKeyMappingAndRepeatBooleanBinding() throws Exception {
        JsonNode root = dryRun("", Map.of(
                "tcName", "TC001",
                "repeatBoolean", false,
                "repeatTime", 10000,
                "delayTime", 1000,
                "format", List.of(Map.of(
                        "dataId", "temperature,status",
                        "dataType", "Double,String",
                        "randomBoolean", "0,0"
                )),
                "value", List.of(Map.of(0, "25.1,OK", 1, "25.2,WARN"))
        ));

        assertEnvelope(root, "VALUE_ROWS", 1, 2, 2);
        assertThat(payload(root, 0).get("temperature").asString()).isEqualTo("25.1");
        assertThat(payload(root, 1).get("status").asString()).isEqualTo("WARN");
    }

    @Test
    void fileDryRunReturnsObjectsInsteadOfEscapedJsonStrings() throws Exception {
        Files.writeString(tempDir.resolve("message.json"), "\"temperature\": \"25.1\", \"status\": \"OK\"");

        JsonNode root = dryRun("/file", Map.of(
                "tcName", "TC001",
                "repeatBoolean", true,
                "messageCount", 2,
                "filePath", tempDir.toString(),
                "fileName", "message.json"
        ));

        assertEnvelope(root, "FILE_REPEAT", 2, 1, 2);
        assertThat(payload(root, 0).get("temperature").asString()).isEqualTo("25.1");
        assertThat(payload(root, 0).get("USER_ID").asString()).isEqualTo("dry-run");
    }

    @Test
    void fileDataDryRunKeepsHeaderMappingAndQuotedCsvCommas() throws Exception {
        Files.writeString(tempDir.resolve("format.txt"), "\"temperature\": \"{{temperature}}\", \"status\": \"{{status}}\"");
        Files.writeString(tempDir.resolve("data.csv"), "status,temperature\n\"OK, ready\",25.1\n\"WARN\",25.2\n");

        JsonNode root = dryRun("/file-data", Map.of(
                "tcName", "TC001",
                "repeatBoolean", true,
                "messageCount", 1,
                "filePath", tempDir.toString(),
                "formatFileName", "format.txt",
                "dataFileName", "data.csv"
        ));

        assertEnvelope(root, "FILE_DATA", 1, 2, 2);
        assertThat(payload(root, 0).get("temperature").asString()).isEqualTo("25.1");
        assertThat(payload(root, 0).get("status").asString()).isEqualTo("OK, ready");
        assertThat(payload(root, 1).get("status").asString()).isEqualTo("WARN");
    }

    @Test
    void fileDataDryRunRetainsExistingErrorForUnescapedQuotesInJsonTemplate() throws Exception {
        // Also reproduced on bc3f0ca: template rendering does not JSON-escape CSV values.
        Files.writeString(tempDir.resolve("format.txt"), "\"status\": \"{{status}}\"");
        Files.writeString(tempDir.resolve("data.csv"), "status\n\"quoted \"\"text\"\"\"\n");

        assertThatThrownBy(() -> dryRun("/file-data", Map.of(
                "tcName", "TC001",
                "messageCount", 1,
                "filePath", tempDir.toString(),
                "formatFileName", "format.txt",
                "dataFileName", "data.csv"
        )))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause().hasMessage("Generated message is not valid JSON.");
    }

    private JsonNode dryRun(String suffix, Map<String, Object> request) throws Exception {
        String response = mockMvc.perform(post("/activemq/request" + suffix + "/dry-run?limit=2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(response);
    }

    private void assertEnvelope(JsonNode root, String mode, int repeats, int rows, long total) {
        assertThat(root.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "requestedLimit", "returnedCount", "estimatedRepeatCount", "estimatedSourceRowCount",
                "estimatedTotalMessagesPerTask", "generationMode", "messages"
        );
        assertThat(root.get("requestedLimit").intValue()).isEqualTo(2);
        assertThat(root.get("returnedCount").intValue()).isEqualTo(2);
        assertThat(root.get("estimatedRepeatCount").intValue()).isEqualTo(repeats);
        assertThat(root.get("estimatedSourceRowCount").intValue()).isEqualTo(rows);
        assertThat(root.get("estimatedTotalMessagesPerTask").longValue()).isEqualTo(total);
        assertThat(root.get("generationMode").asString()).isEqualTo(mode);
        assertThat(root.get("messages").isArray()).isTrue();
        assertThat(root.get("messages").size()).isEqualTo(2);
        for (JsonNode message : root.get("messages")) {
            assertThat(message.isObject()).isTrue();
            assertThat(message.properties()).extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("CREATE_TIMESTAMP", "MESSAGE_ID", "DATA_MAP");
            assertThat(message.get("CREATE_TIMESTAMP").asString()).matches("\\d{17}");
            assertThat(message.get("MESSAGE_ID").asString()).isEqualTo("TC001");
            assertThat(message.get("DATA_MAP").isObject()).isTrue();
        }
    }

    private JsonNode payload(JsonNode root, int index) {
        return root.get("messages").get(index).get("DATA_MAP").properties().iterator().next().getValue();
    }
}
