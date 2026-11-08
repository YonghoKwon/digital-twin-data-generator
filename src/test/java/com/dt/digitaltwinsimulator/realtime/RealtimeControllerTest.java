package com.dt.digitaltwinsimulator.realtime;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "simulator.activemq.connect-timeout-ms=200", "simulator.http-logging.enabled=false", "simulator.realtime.max-streams=1"})
@AutoConfigureMockMvc
class RealtimeControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired RealtimeService service;
    @MockitoBean MessagePublisher publisher;
    @MockitoSpyBean BrokerConnectionService connections;
    @MockitoSpyBean DtMessageFactory factory;
    private MessagePublisher.Publication publication;

    @BeforeEach void setup() {
        publication = mock(MessagePublisher.Publication.class);
        when(publisher.open(any())).thenReturn(publication);
    }

    @AfterEach void cleanup() throws Exception {
        service.stopAll();
        Thread.sleep(30);
    }

    private String body(String extra) { return "{\"topic\":\"topic.test\",\"messageId\":\"TC\",\"payload\":{\"n\":\"{{seq}}\"}" + extra + "}"; }

    @Test
    void previewReturnsObjectsAndNoBrokerCalls() throws Exception {
        mvc.perform(post("/dt/publish/preview").contentType(MediaType.APPLICATION_JSON).content(body(",\"count\":2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0]").isMap())
                .andExpect(jsonPath("$.messages[0].MESSAGE_ID").value("TC"))
                .andExpect(jsonPath("$.count").value(2));
        verifyNoInteractions(publisher);
        verify(connections, never()).verifyConnection(any());
    }

    @Test
    void synchronousPublishReturnsResultAndUsesBrokerOverrides() throws Exception {
        doNothing().when(connections).verifyConnection(any());
        mvc.perform(post("/dt/publish").contentType(MediaType.APPLICATION_JSON).content(body(",\"count\":3,\"activeMQIp\":\"tcp://example:61616\",\"id\":\"override-user\",\"pw\":\"override-secret\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(3))
                .andExpect(jsonPath("$.failed").value(0)).andExpect(jsonPath("$.firstMessage").isMap());
        verify(publisher).open(argThat(b -> b.url().equals("tcp://example:61616") && b.user().equals("override-user") && b.pw().equals("override-secret")));
        verify(publication, times(3)).publish(eq("topic.test"), anyString(), anyMap());
        verify(publication).close();
    }

    @Test
    void closedPortIs502WithSharedJsonErrorShape() throws Exception {
        mvc.perform(post("/dt/publish").contentType(MediaType.APPLICATION_JSON).content(body(",\"activeMQIp\":\"tcp://127.0.0.1:1\"")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("BROKER_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(publisher);
    }

    @Test
    void brokerFailureAfterSuccessfulMessagesReturns207AndPreservesProgress() throws Exception {
        doNothing().when(connections).verifyConnection(any());
        doNothing().doNothing().doThrow(new IllegalStateException("provider failure"))
                .when(publication).publish(any(), any(), any());
        mvc.perform(post("/dt/publish").contentType(MediaType.APPLICATION_JSON).content(body(",\"count\":5")))
                .andExpect(status().is(207)).andExpect(jsonPath("$.sent").value(2))
                .andExpect(jsonPath("$.failed").value(1)).andExpect(jsonPath("$.stoppedReason").value("BROKER_ERROR"))
                .andExpect(jsonPath("$.firstMessage.MESSAGE_ID").value("TC"))
                .andExpect(jsonPath("$.topic").value("topic.test")).andExpect(jsonPath("$.elapsedMillis").isNumber());
        verify(publication, times(3)).publish(any(), any(), any());
        verify(publication).close();
    }

    @Test
    void firstPublicationFailureStillReturns502AndClosesScope() throws Exception {
        doNothing().when(connections).verifyConnection(any());
        doThrow(new IllegalStateException("provider failure")).when(publication).publish(any(), any(), any());
        mvc.perform(post("/dt/publish").contentType(MediaType.APPLICATION_JSON).content(body(",\"count\":3")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("BROKER_CONNECTION_FAILED"));
        verify(publication).publish(any(), any(), any());
        verify(publication).close();
    }

    @Test
    void laterMessageGenerationFailureReturns400WithCauseBeforeAnyBrokerIo() throws Exception {
        doThrow(new IllegalArgumentException("Invalid value token (random)."))
                .when(factory).create(any(), any(), eq(3L), any());
        mvc.perform(post("/dt/publish").contentType(MediaType.APPLICATION_JSON).content(body(",\"count\":5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid value token (random)."));
        verifyNoInteractions(publisher);
        verify(connections, never()).verifyConnection(any());
    }

    @Test
    void validationAndUnknownTokensUseShared400BeforeBroker() throws Exception {
        for (String json : new String[]{"{broken", "{}", body(",\"count\":0"), body(",\"intervalMs\":-1"),
                body(",\"headers\":{\"bad-header\":\"x\"}"), "{\"messageId\":\"TC\",\"payload\":{\"x\":\"{{bad}}\"}}"}) {
            String result = mvc.perform(post("/dt/publish").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(result).properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("error", "message");
        }
        verifyNoInteractions(publisher);
        verify(connections, never()).verifyConnection(any());
    }

    @Test
    void completeStreamLifecycleAnd409429ViaHttp() throws Exception {
        mvc.perform(post("/dt/streams").contentType(MediaType.APPLICATION_JSON).content(body(",\"streamId\":\"http-stream\",\"startPaused\":true")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post("/dt/streams").contentType(MediaType.APPLICATION_JSON).content(body(",\"streamId\":\"http-stream\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DUPLICATE_TASK_ID"));
        mvc.perform(post("/dt/streams").contentType(MediaType.APPLICATION_JSON).content(body(",\"streamId\":\"over-limit\"")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("STREAM_LIMIT_EXCEEDED"));
        mvc.perform(get("/dt/streams")).andExpect(status().isOk()).andExpect(jsonPath("$[0].streamId").exists());
        mvc.perform(patch("/dt/streams/http-stream").contentType(MediaType.APPLICATION_JSON).content("{\"rateTps\":100,\"paused\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rateTps").value(100));
        verify(publication, timeout(2000).atLeastOnce()).publish(any(), any(), any());
        mvc.perform(patch("/dt/streams/http-stream").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true,\"payload\":{\"new\":\"{{seq}}\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(get("/dt/streams/http-stream")).andExpect(status().isOk()).andExpect(jsonPath("$.sent").isNumber());
        mvc.perform(delete("/dt/streams/http-stream")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"));
        mvc.perform(delete("/dt/streams")).andExpect(status().isOk());
        mvc.perform(patch("/dt/streams/http-stream").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("STREAM_NOT_ACTIVE"));
        mvc.perform(get("/dt/streams/absent")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("STREAM_NOT_FOUND"));
    }

    @Test
    void invalidRatesLimitsAndPatchTokensReturn400() throws Exception {
        for (String extra : new String[]{",\"rateTps\":0", ",\"rateTps\":5001", ",\"durationSec\":-1", ",\"maxMessages\":-1", ",\"streamId\":\"bad id\""}) {
            mvc.perform(post("/dt/streams").contentType(MediaType.APPLICATION_JSON).content(body(extra)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/dt/streams").contentType(MediaType.APPLICATION_JSON).content(body(",\"streamId\":\"patch-invalid\",\"startPaused\":true")))
                .andExpect(status().isCreated());
        mvc.perform(patch("/dt/streams/patch-invalid").contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{\"bad\":\"{{unknown}}\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
