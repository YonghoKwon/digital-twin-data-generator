package com.dt.digitaltwinsimulator.embedded;

import com.dt.digitaltwinsimulator.DigitalTwinSimulatorApplication;
import com.dt.digitaltwinsimulator.broker.*;
import com.dt.digitaltwinsimulator.config.ActiveMqBrokerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EmbeddedBrokerLifecycleTest {
    @Test void disabledBrokerDoesNotOpenAcceptorsAndKeepsExternalDefaults() {
        var properties = new EmbeddedBrokerProperties();
        var broker = new EmbeddedBrokerService(properties);
        try {
            broker.start();
            assertThat(broker.isRunning()).isFalse();
            assertThat(broker.status("localhost")).containsEntry("enabled", false).containsEntry("lastError", null);
            var external = new BrokerSettings("tcp://external:61616", "outside", "private", "topic.default");
            assertThat(new EmbeddedBrokerDefaults(properties).defaults(external)).isEqualTo(external);
        } finally { broker.stop(); }
    }

    @Test void sharedResolvePreservesSignatureAndOverridesAndSelectsEmbeddedDefaultsOnlyWithoutUrl() {
        var properties = new EmbeddedBrokerProperties();
        properties.setEnabled(true);
        properties.setUsername("embedded-user"); properties.setPassword("private-embedded");
        properties.setHost("0.0.0.0"); properties.setCorePort(61726);
        var beans = new StaticListableBeanFactory(Map.of("defaults", new EmbeddedBrokerDefaults(properties)));
        var external = new ActiveMqBrokerProperties();
        external.setBrokerUrl("tcp://external:61616");
        external.setUsername("outside"); external.setPassword("private-external");
        var connections = new BrokerConnectionService(external, beans.getBeanProvider(BrokerDefaultsProvider.class));
        assertThat(connections.resolve(" ", null, " ", null)).isEqualTo(
                new BrokerSettings("tcp://127.0.0.1:61726", "embedded-user", "private-embedded", external.getTopic()));
        assertThat(connections.resolve(null, "override", "override-password", "override.topic")).isEqualTo(
                new BrokerSettings("tcp://127.0.0.1:61726", "override", "override-password", "override.topic"));
        assertThat(connections.resolve("tcp://explicit:61616", null, null, null)).isEqualTo(
                new BrokerSettings("tcp://explicit:61616", "outside", "private-external", external.getTopic()));
        properties.setUseAsDefault(false);
        assertThat(connections.resolve(null, null, null, null).url()).isEqualTo("tcp://external:61616");
    }

    @Test void occupiedStompPortLeavesApplicationAndBrokerStatusApiAvailable() throws Exception {
        try (var occupied = new ServerSocket(0);
             var context = new SpringApplicationBuilder(DigitalTwinSimulatorApplication.class).run(
                     "--server.port=0", "--simulator.embedded-broker.enabled=true",
                     "--simulator.embedded-broker.core-port=" + EmbeddedSubscriptionContractTest.freePort(),
                     "--simulator.embedded-broker.stomp-port=" + occupied.getLocalPort(),
                     "--logging.level.com.dt.digitaltwinsimulator=INFO")) {
            assertThat(context.isActive()).isTrue();
            var broker = context.getBean(EmbeddedBrokerService.class);
            assertThat(broker.isRunning()).isFalse();
            assertThat(broker.status("localhost").get("lastError")).asString().contains("startup failed");
            int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext)context).getWebServer().getPort();
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/dt/broker")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"running\":false", "\"lastError\":");
        }
    }

    @Test void occupiedCorePortAlsoFailsSafelyAndReleasesOtherAcceptor() throws Exception {
        var properties = new EmbeddedBrokerProperties();
        properties.setEnabled(true);
        int stomp = EmbeddedSubscriptionContractTest.freePort();
        properties.setStompPort(stomp);
        try (var occupied = new ServerSocket(0)) {
            properties.setCorePort(occupied.getLocalPort());
            var broker = new EmbeddedBrokerService(properties);
            try {
                broker.start();
                assertThat(broker.isRunning()).isFalse();
                assertThat(broker.status("localhost").get("lastError")).isNotNull();
                try (var available = new ServerSocket(stomp)) { assertThat(available.isBound()).isTrue(); }
            } finally { broker.stop(); }
        }
    }

    @Test void shutdownClosesLiveSubscriptionAndSseBeforeStoppingBroker() throws Exception {
        int core = EmbeddedSubscriptionContractTest.freePort();
        int stomp = EmbeddedSubscriptionContractTest.freePort();
        try (var context = new SpringApplicationBuilder(DigitalTwinSimulatorApplication.class).run(
                "--server.port=0", "--simulator.embedded-broker.enabled=true",
                "--simulator.embedded-broker.core-port=" + core,
                "--simulator.embedded-broker.stomp-port=" + stomp,
                "--simulator.http-logging.enabled=true",
                "--logging.level.com.dt.digitaltwinsimulator=INFO")) {
            var subscriptions = context.getBean(com.dt.digitaltwinsimulator.subscribe.SubscriptionService.class);
            var broker = context.getBean(EmbeddedBrokerService.class);
            subscriptions.create(new com.dt.digitaltwinsimulator.subscribe.SubscriptionRequest(
                    "shutdown", "s4.shutdown", null, null, null, null, null));
            int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext)context).getWebServer().getPort();
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "http://127.0.0.1:" + port + "/dt/subscriptions/shutdown/stream"))
                            .timeout(java.time.Duration.ofSeconds(5)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var input = response.body()) {
                long start = System.nanoTime();
                context.close();
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(10));
                assertThat(subscriptions.list()).isEmpty();
                assertThat(subscriptions.isRunning()).isFalse();
                assertThat(broker.isRunning()).isFalse();
                // A live SSE response must finish during shutdown instead of delaying the web server.
                assertThat(input.readAllBytes().length).isPositive();
            }
            try (var releasedCore = new ServerSocket(core); var releasedStomp = new ServerSocket(stomp)) {
                assertThat(releasedCore.isBound()).isTrue();
                assertThat(releasedStomp.isBound()).isTrue();
            }
        }
    }

    @Test void artemisDefault64KiBFrameLimitRejectsOneMiBFrame() throws Exception {
        var properties = new EmbeddedBrokerProperties();
        properties.setEnabled(true);
        properties.setCorePort(EmbeddedSubscriptionContractTest.freePort());
        properties.setStompPort(EmbeddedSubscriptionContractTest.freePort());
        properties.setMaxFrameBytes(65536);
        var broker = new EmbeddedBrokerService(properties);
        try {
            broker.start();
            assertThat(broker.isRunning()).isTrue();
            try (var client = new StompWebSocketClient(properties.getStompPort())) {
                client.connected();
                assertThatThrownBy(() -> client.sendBytes("s4.limit", new byte[1024 * 1024]))
                        .isInstanceOfAny(AssertionError.class, java.util.concurrent.ExecutionException.class);
            }
        } finally { broker.stop(); }
    }
}

