package com.dt.digitaltwinsimulator.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RequestResponseLoggingFilterTest {
    @Test void sseAcceptOnAnyEndpointBypassesWrappersAndFlushesImmediately() throws Exception {
        var properties = enabled();
        properties.setExcludePaths(List.of());
        var request = new MockHttpServletRequest("GET", "/s2/live");
        request.addHeader("Accept", "application/json");
        request.addHeader("Accept", "TEXT/EVENT-STREAM; charset=UTF-8");
        assertBypassed(properties, request);
    }

    @Test void defaultStreamPathBypassesLoggingWithoutAcceptHeader() throws Exception {
        assertBypassed(enabled(), new MockHttpServletRequest("GET", "/dt/subscriptions/sensor-1/stream"));
    }

    @Test void antPatternListBindsAndMatchesPathsRelativeToContext() throws Exception {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "simulator.http-logging.enabled", "true",
                "simulator.http-logging.exclude-paths", "/s2/**,/events/*/live")))
                .bind("simulator.http-logging", Bindable.of(HttpLoggingProperties.class)).get();
        assertThat(properties.getExcludePaths()).containsExactly("/s2/**", "/events/*/live");
        var nested = new MockHttpServletRequest("GET", "/app/s2/nested/stream");
        nested.setContextPath("/app");
        assertBypassed(properties, nested);
        assertBypassed(properties, new MockHttpServletRequest("POST", "/events/one/live"));
    }

    @Test void ordinaryRequestsStillLogMaskedBodiesAndCopyResponse() throws Exception {
        var properties = enabled();
        properties.setIncludeRequestBody(true); properties.setIncludeResponseBody(true);
        var request = new MockHttpServletRequest("POST", "/dt/subscriptions/sensor/messages");
        request.setContent("{\"pw\":\"private-request\",\"value\":\"visible-request\"}".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        try (var logs = new Logs()) {
            new RequestResponseLoggingFilter(properties).doFilter(request, response, (req, res) -> {
                assertThat(req).isInstanceOf(ContentCachingRequestWrapper.class);
                assertThat(res).isInstanceOf(ContentCachingResponseWrapper.class);
                req.getInputStream().readAllBytes();
                res.getWriter().write("{\"pw\":\"private-response\",\"value\":\"visible-response\"}");
            });
            assertThat(response.getContentAsString()).contains("private-response", "visible-response");
            assertThat(logs.appender.list).hasSize(1);
            assertThat(logs.appender.list.getFirst().getFormattedMessage())
                    .contains("requestBody=", "responseBody=", "visible-request", "visible-response")
                    .doesNotContain("private-request", "private-response");
        }
    }

    @Test void bodyLoggingDisabledPreservesMetadataAndResponse() throws Exception {
        var response = new MockHttpServletResponse();
        try (var logs = new Logs()) {
            new RequestResponseLoggingFilter(enabled()).doFilter(new MockHttpServletRequest("GET", "/ordinary"),
                    response, (req, res) -> res.getWriter().write("response-content"));
            assertThat(response.getContentAsString()).isEqualTo("response-content");
            assertThat(logs.appender.list).hasSize(1);
            assertThat(logs.appender.list.getFirst().getFormattedMessage()).contains("HTTP GET /ordinary -> 200")
                    .doesNotContain("requestBody=", "responseBody=", "response-content");
        }
    }

    @Test void disabledLoggingStillPassesOriginalRequestAndResponse() throws Exception {
        assertBypassed(new HttpLoggingProperties(), new MockHttpServletRequest("POST", "/ordinary"));
    }

    private HttpLoggingProperties enabled() {
        var properties = new HttpLoggingProperties();
        properties.setEnabled(true); properties.setMaxPayloadLength(8000);
        return properties;
    }

    private void assertBypassed(HttpLoggingProperties properties, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        try (var logs = new Logs()) {
            new RequestResponseLoggingFilter(properties).doFilter(request, response, (req, res) -> {
                assertThat(req).isSameAs(request); assertThat(res).isSameAs(response);
                res.getWriter().write("event:ready\n\n"); res.flushBuffer();
                assertThat(response.getContentAsString()).isEqualTo("event:ready\n\n");
            });
            assertThat(logs.appender.list).isEmpty();
        }
    }

    private static final class Logs implements AutoCloseable {
        final Logger logger = (Logger) LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logs() { appender.start(); logger.addAppender(appender); }
        @Override public void close() { logger.detachAppender(appender); appender.stop(); }
    }
}
