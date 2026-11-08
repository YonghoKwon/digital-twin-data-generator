package com.dt.digitaltwinsimulator.mock;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
class MockTomcatConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> mockTraceSupport(MockProperties properties) {
        return factory -> {
            // Tomcat otherwise rejects TRACE before any application filter can match a route.
            factory.addConnectorCustomizers(connector -> connector.setAllowTrace(true));
            factory.addContextValves(new ValveBase(true) {
                @Override
                public void invoke(Request request, Response response) throws IOException, ServletException {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    boolean mock = path.equals(properties.getBasePath()) || path.startsWith(properties.getBasePath() + "/");
                    if (request.getMethod().equals("TRACE") && !mock) {
                        response.setStatus(405);
                        return;
                    }
                    getNext().invoke(request, response);
                }
            });
        };
    }
}
