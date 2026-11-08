package com.dt.digitaltwinsimulator.embedded;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.security.CheckType;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LogLevel;

import java.nio.file.*;
import java.util.*;

@Service
public class EmbeddedBrokerService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedBrokerService.class);
    private final EmbeddedBrokerProperties properties;
    private EmbeddedActiveMQ broker;
    private Path storage;
    private volatile String lastError;
    private final Map<String, LogLevel> auditLevels = new HashMap<>();

    public EmbeddedBrokerService(EmbeddedBrokerProperties properties) { this.properties = properties; }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled() || isRunning()) return;
        lastError = null;
        try {
            // Artemis audit records include raw JMS properties. Keep credentials out of logs.
            var logging = LoggingSystem.get(getClass().getClassLoader());
            for (String logger : List.of("org.apache.activemq.audit.base", "org.apache.activemq.audit.resource",
                    "org.apache.activemq.audit.message")) {
                var existing = logging.getLoggerConfiguration(logger);
                auditLevels.put(logger, existing == null ? null : existing.getConfiguredLevel());
                logging.setLogLevel(logger, LogLevel.OFF);
            }
            if (properties.getUsername() == null || properties.getUsername().isBlank()
                    || properties.getPassword() == null || properties.getPassword().isBlank())
                throw new IllegalArgumentException("Embedded broker credentials must be configured.");
            storage = Files.createTempDirectory("dts-artemis-");
            var config = new ConfigurationImpl();
            config.setName("dts-embedded");
            config.setPersistenceEnabled(false);
            config.setSecurityEnabled(true);
            config.setJMXManagementEnabled(false);
            config.setThreadPoolMaxSize(4);
            config.setScheduledThreadPoolMaxSize(2);
            config.setJournalDirectory(storage.resolve("journal").toString());
            config.setBindingsDirectory(storage.resolve("bindings").toString());
            config.setPagingDirectory(storage.resolve("paging").toString());
            config.setLargeMessagesDirectory(storage.resolve("large").toString());
            config.addAddressSetting("#", new AddressSettings()
                    .setAutoCreateAddresses(true).setAutoCreateQueues(true)
                    .setDefaultAddressRoutingType(RoutingType.MULTICAST)
                    .setDefaultQueueRoutingType(RoutingType.MULTICAST)
                    .setMaxSizeBytes(properties.getMaxSizeBytes())
                    .setAddressFullMessagePolicy(properties.getAddressFullPolicy()));
            config.addAcceptorConfiguration("core", properties.endpoint("tcp", properties.getHost(), properties.getCorePort())
                    + "?protocols=CORE;nioRemotingThreads=2");
            config.addAcceptorConfiguration("stomp", properties.endpoint("tcp", properties.getHost(), properties.getStompPort())
                    + "?protocols=STOMP;nioRemotingThreads=2;webSocketMaxFramePayloadLength=" + properties.getMaxFrameBytes()
                    + ";stompMinLargeMessageSize=" + properties.getStompMinLargeMessageSize());
            broker = new EmbeddedActiveMQ().setConfiguration(config).setSecurityManager(new ActiveMQSecurityManager() {
                @Override
                public boolean validateUser(String user, String password) {
                    return properties.getUsername().equals(user) && properties.getPassword().equals(password);
                }
                @Override
                public boolean validateUserAndRole(String user, String password, Set<Role> roles, CheckType checkType) {
                    return validateUser(user, password);
                }
            });
            broker.start();
            // Some acceptor failures are recorded without failing ActiveMQServer.start().
            var acceptors = broker.getActiveMQServer().getRemotingService().getAcceptors();
            if (!broker.getActiveMQServer().isActive() || acceptors.size() != 2
                    || acceptors.values().stream().anyMatch(a -> !a.isStarted()))
                throw new IllegalStateException("An embedded broker acceptor did not start.");
            log.info("Embedded Artemis 2.44.0 started (core port {}, STOMP/WebSocket port {}).",
                    properties.getCorePort(), properties.getStompPort());
        } catch (Exception e) {
            // Never expose credentials or arbitrary provider exception strings.
            lastError = "Embedded broker startup failed (" + e.getClass().getSimpleName() + "). Check ports and settings.";
            log.warn("{}", lastError);
            cleanup();
        }
    }

    @Override
    public synchronized void stop() { cleanup(); }

    private void cleanup() {
        if (broker != null) {
            try { broker.stop(); } catch (Exception e) { log.warn("Embedded broker shutdown failed ({}).", e.getClass().getSimpleName()); }
            broker = null;
        }
        if (storage != null) {
            try (var paths = Files.walk(storage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (Exception e) { log.warn("Could not remove temporary embedded broker storage ({}).", e.getClass().getSimpleName()); }
            storage = null;
        }
        var logging = LoggingSystem.get(getClass().getClassLoader());
        auditLevels.forEach(logging::setLogLevel);
        auditLevels.clear();
    }

    @Override
    public synchronized boolean isRunning() { return broker != null && broker.getActiveMQServer() != null && broker.getActiveMQServer().isStarted(); }

    public synchronized boolean isDefaultTarget() {
        return properties.isEnabled() && properties.isUseAsDefault() && isRunning();
    }

    @Override
    public int getPhase() { return 0; }

    public synchronized Map<String, Object> status(String requestHost) {
        String host = "0.0.0.0".equals(properties.getHost()) || "::".equals(properties.getHost()) ? requestHost : properties.getHost();
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", properties.isEnabled());
        result.put("running", isRunning());
        result.put("defaultTarget", isDefaultTarget() ? "EMBEDDED" : "EXTERNAL");
        result.put("version", "2.44.0");
        result.put("coreUrl", properties.endpoint("tcp", host, properties.getCorePort()));
        result.put("stompUrl", properties.endpoint("tcp", host, properties.getStompPort()));
        String ws = properties.endpoint("ws", host, properties.getStompPort());
        result.put("websocketUrl", ws);
        result.put("dtcoreSettingsHint", "WebSocketUrl=" + ws + "\nLogin=<설정값>\nPasscode=<설정값>");
        result.put("lastError", lastError);
        var addresses = new ArrayList<Map<String, Object>>();
        if (isRunning()) {
            var server = broker.getActiveMQServer();
            for (var name : server.getPostOffice().getAddresses()) {
                var info = server.getAddressInfo(name);
                long count = 0, consumers = 0;
                for (var queueName : server.getPostOffice().getAllBindings()
                        .filter(b -> b.getAddress().equals(name))
                        .map(b -> b.getBindable()).filter(org.apache.activemq.artemis.core.server.Queue.class::isInstance)
                        .map(org.apache.activemq.artemis.core.server.Queue.class::cast).toList()) {
                    count += queueName.getMessageCount();
                    consumers += queueName.getConsumerCount();
                }
                addresses.add(Map.of("name", name.toString(), "routingTypes", info.getRoutingTypes(),
                        "messageCount", count, "consumerCount", consumers));
            }
        }
        result.put("addresses", addresses);
        result.put("settings", Map.of("host", properties.getHost(), "corePort", properties.getCorePort(),
                "stompPort", properties.getStompPort(), "useAsDefault", properties.isUseAsDefault(),
                "maxFrameBytes", properties.getMaxFrameBytes(), "stompMinLargeMessageSize", properties.getStompMinLargeMessageSize(),
                "maxSizeBytes", properties.getMaxSizeBytes(), "addressFullPolicy", properties.getAddressFullPolicy()));
        return result;
    }
}

