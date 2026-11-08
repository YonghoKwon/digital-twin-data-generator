package com.dt.digitaltwinsimulator.subscribe;

import com.dt.digitaltwinsimulator.config.HttpLoggingProperties;
import com.dt.digitaltwinsimulator.config.RequestResponseLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SubscriptionStreamLoggingConfiguration {
    @Bean
    static BeanPostProcessor subscriptionStreamLogging(ObjectProvider<HttpLoggingProperties> properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof RequestResponseLoggingFilter)) return bean;
                // ContentCachingResponseWrapper buffers flushes, which prevents live SSE delivery.
                // Use the filter's standard exclusion hook, keeping existing request logging elsewhere.
                return new RequestResponseLoggingFilter(properties.getObject()) {
                    @Override
                    protected boolean shouldNotFilter(HttpServletRequest request) {
                        String path = request.getServletPath();
                        return "GET".equals(request.getMethod()) && path.matches("/dt/subscriptions/[^/]+/stream");
                    }
                };
            }
        };
    }
}

