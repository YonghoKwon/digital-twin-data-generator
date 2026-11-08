package com.dt.digitaltwinsimulator.broker;

public class BrokerConnectionException extends RuntimeException {
    public BrokerConnectionException(Throwable cause) {
        super("Unable to connect to the broker within the configured timeout.", cause);
    }
}
