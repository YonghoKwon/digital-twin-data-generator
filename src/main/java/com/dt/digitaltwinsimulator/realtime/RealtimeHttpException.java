package com.dt.digitaltwinsimulator.realtime;

/** Additional stream lifecycle errors, rendered by RealtimeController in the shared error shape. */
public class RealtimeHttpException extends RuntimeException {
    private final int status;
    private final String error;
    public RealtimeHttpException(int status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }
    public int status() { return status; }
    public String error() { return error; }
}
