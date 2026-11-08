package com.dt.digitaltwinsimulator.config;

import com.dt.digitaltwinsimulator.logic.SensitiveLogMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

@Slf4j
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {
    private final HttpLoggingProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RequestResponseLoggingFilter(HttpLoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Response caching buffers flushes and prevents live SSE delivery, regardless of the endpoint.
        if (Collections.list(request.getHeaders("Accept")).stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains("text/event-stream"))) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return properties.getExcludePaths().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 0);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        long startedAt = System.currentTimeMillis();

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            StringBuilder message = new StringBuilder();
            message.append("HTTP ")
                    .append(request.getMethod())
                    .append(' ')
                    .append(request.getRequestURI());
            if (request.getQueryString() != null) {
                message.append('?').append(request.getQueryString());
            }
            message.append(" -> ").append(responseWrapper.getStatus()).append(" in ").append(elapsedMillis).append("ms");

            if (properties.isIncludeRequestBody()) {
                message.append(" requestBody=").append(readBody(requestWrapper.getContentAsByteArray()));
            }
            if (properties.isIncludeResponseBody()) {
                message.append(" responseBody=").append(readBody(responseWrapper.getContentAsByteArray()));
            }

            log.info("{}", SensitiveLogMasker.mask(truncate(message.toString())));
            responseWrapper.copyBodyToResponse();
        }
    }

    private String readBody(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value) {
        int maxLength = Math.max(100, properties.getMaxPayloadLength());
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }
}
