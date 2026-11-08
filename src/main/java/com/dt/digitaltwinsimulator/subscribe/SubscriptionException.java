package com.dt.digitaltwinsimulator.subscribe;

public class SubscriptionException extends RuntimeException {
    private final int status;
    private final String error;
    public SubscriptionException(int status, String error, String message) {
        super(message); this.status = status; this.error = error;
    }
    public int status() { return status; }
    public String error() { return error; }
}

