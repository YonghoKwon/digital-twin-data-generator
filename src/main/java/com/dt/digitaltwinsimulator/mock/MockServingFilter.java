package com.dt.digitaltwinsimulator.mock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Handles mock traffic before MVC's automatic OPTIONS and request body logging. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class MockServingFilter extends OncePerRequestFilter {
    private final MockProperties properties;
    private final MockServingEngine engine;

    MockServingFilter(MockProperties properties, MockServingEngine engine) {
        this.properties = properties;
        this.engine = engine;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(properties.getBasePath()) && !path.startsWith(properties.getBasePath() + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getMethod().equals("TRACE")) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        var result = engine.serve(request);
        int status = result.getStatusCode().value();
        response.setStatus(status);
        result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        byte[] body = result.getBody();
        if (body != null && status != 204 && status != 205 && status != 304) {
            response.setContentLength(body.length);
            if (!request.getMethod().equals("HEAD")) response.getOutputStream().write(body);
        }
    }
}
