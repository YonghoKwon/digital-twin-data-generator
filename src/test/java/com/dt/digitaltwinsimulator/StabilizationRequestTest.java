package com.dt.digitaltwinsimulator;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionException;
import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import com.dt.digitaltwinsimulator.logic.TaskCancellationLogic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"simulator.activemq.connect-timeout-ms=300", "simulator.http-logging.enabled=false"})
@AutoConfigureMockMvc
class StabilizationRequestTest {
    @TempDir static Path base;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired TaskCancellationLogic tasks;
    @MockitoSpyBean BrokerConnectionService connections;
    @MockitoBean MessagePublisher publisher;

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) {
        registry.add("simulator.files.base-dir", () -> base.toString());
    }

    @BeforeEach
    void files() throws Exception {
        Files.writeString(base.resolve("format.txt"), "\"n\":{{n}}");
        Files.writeString(base.resolve("data.csv"), "n\n42\n");
        Files.writeString(base.resolve("message.json"), "\"n\":42");
    }

    @Test
    void closedPortReturns502JsonOnEachAsyncRouteWithoutStartingTasks() throws Exception {
        long start = System.nanoTime();
        for (String mode : List.of("", "/file", "/file-data")) {
            var body = request(mode);
            body.put("activeMQIp", "tcp://127.0.0.1:1");
            mvc.perform(post("/activemq/request" + mode + "/t1").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadGateway())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").value("BROKER_CONNECTION_FAILED"))
                    .andExpect(jsonPath("$.message").isString());
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(tasks.getRunningTaskIds()).isEmpty();
        verifyNoInteractions(publisher);
    }

    @Test
    void malformedInputsReturn400BeforeCheckingBrokerOnEachRoute() throws Exception {
        Files.writeString(base.resolve("message.json"), "not json");
        Files.writeString(base.resolve("data.csv"), "n\nnot-a-number\n");
        for (String mode : List.of("", "/file", "/file-data")) {
            var body = request(mode);
            if (mode.isEmpty()) body.put("format", List.of(Map.of("dataType", "String")));
            mvc.perform(post("/activemq/request" + mode + "/bad").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
        verify(connections, never()).verifyConnection(any());
        verifyNoInteractions(publisher);
        assertThat(tasks.getRunningTaskIds()).isEmpty();
    }

    @Test
    void missingFilesAndExternalPathsReturn400() throws Exception {
        for (String directory : List.of("..", base.getParent().toString(), "Z:/outside")) {
            var body = request("/file");
            body.put("filePath", directory);
            mvc.perform(post("/activemq/request/file/path").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    // "Z:/outside"는 Linux에서 base-dir 하위 상대 경로가 되어 "missing"으로 거부된다. 어느 쪽이든 원인이 보여야 한다.
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.containsString("outside the allowed base directory"),
                            org.hamcrest.Matchers.containsString("missing or unreadable"))));
        }
        var body = request("/file");
        body.put("fileName", "missing.json");
        mvc.perform(post("/activemq/request/file/path").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verify(connections, never()).verifyConnection(any());
    }

    @Test
    void beanValidationAndUnreadableBodyUseSameErrorShape() throws Exception {
        for (String body : List.of("{}", "{broken", "{\"tcName\":\"TC\",\"format\":[{}],\"messageCount\":-1}")) {
            String json = mvc.perform(post("/activemq/request/t1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(json).properties()).extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("error", "message");
        }
    }

    @Test
    void mockedBrokerFailureIs502AndDoesNotStartAsyncSend() throws Exception {
        doThrow(new BrokerConnectionException(new IllegalStateException("private credentials"))).when(connections).verifyConnection(any());
        mvc.perform(post("/activemq/request/t1").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(""))))
                .andExpect(status().isBadGateway()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private credentials"))));
        verifyNoInteractions(publisher);
    }

    @Test
    void invalidBrokerUrlIsAnInputError() throws Exception {
        var body = request("");
        body.put("activeMQIp", "unsupported://invalid");
        mvc.perform(post("/activemq/request/bad-url").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        verifyNoInteractions(publisher);
    }

    @Test
    void successfulPreflightKeepsExistingStringResponse() throws Exception {
        doNothing().when(connections).verifyConnection(any());
        var publication = mock(MessagePublisher.Publication.class);
        when(publisher.open(any())).thenReturn(publication);
        mvc.perform(post("/activemq/request/t1").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(""))))
                .andExpect(status().isOk()).andExpect(content().string("success : Started 1 tasks with base ID t1"));
        verify(publication, timeout(3000)).publish(any(), any(), any());
        verify(publication, timeout(3000)).close();
    }

    @Test
    void duplicateLoadTaskReturns409AndPreservesOriginalCancellationToken() throws Exception {
        tasks.registerTask("busy");
        tasks.requestCancellation("busy");
        try {
            mvc.perform(post("/activemq/request/jms-template-load/busy").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tcName\":\"TC\",\"messageCount\":1}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_TASK_ID"));
            assertThat(tasks.getRunningTaskIds()).contains("busy");
            assertThat(tasks.isCancellationRequested("busy")).isTrue();
            verifyNoInteractions(publisher);
        } finally {
            tasks.removeTask("busy");
        }
    }

    @Test
    void typedValuesBindOverHttpAndDryRunStillReturnsObjects() throws Exception {
        var body = request("");
        body.put("typedValues", true);
        body.put("format", List.of(Map.of("dataId", "i,b", "dataType", "Integer,Boolean")));
        body.put("value", List.of(Map.of("0", "123,true")));
        mvc.perform(post("/activemq/request/dry-run").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0]").isMap())
                .andExpect(jsonPath("$.messages[0].DATA_MAP.*.i").value(org.hamcrest.Matchers.contains(123)))
                .andExpect(jsonPath("$.messages[0].DATA_MAP.*.b").value(org.hamcrest.Matchers.contains(true)));
        verify(connections, never()).verifyConnection(any());
    }

    private java.util.HashMap<String, Object> request(String mode) {
        var body = new java.util.HashMap<String, Object>();
        body.put("tcName", "TC");
        body.put("messageCount", 1);
        if (mode.isEmpty()) body.put("format", List.of(Map.of("dataId", "n", "dataType", "Integer")));
        else {
            body.put("filePath", ".");
            body.put("fileName", "message.json");
            body.put("formatFileName", "format.txt");
            body.put("dataFileName", "data.csv");
        }
        return body;
    }
}
