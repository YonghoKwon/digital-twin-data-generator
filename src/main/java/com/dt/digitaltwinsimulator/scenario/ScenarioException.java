package com.dt.digitaltwinsimulator.scenario;

public class ScenarioException extends RuntimeException {
    private final int status;
    private final String error;
    public ScenarioException(int status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }
    public int status() { return status; }
    public String error() { return error; }
}
