package com.dt.digitaltwinsimulator.broker;

import java.util.Map;

public interface MessagePublisher {
    /** One task owns one publication scope; implementations may cache connections and sessions. */
    Publication open(BrokerSettings settings);

    default void publish(BrokerSettings settings, String destination, String body, Map<String, String> headers) {
        try (Publication publication = open(settings)) {
            publication.publish(destination, body, headers);
        }
    }

    interface Publication extends AutoCloseable {
        void publish(String destination, String body, Map<String, String> headers);

        @Override
        void close();
    }
}
