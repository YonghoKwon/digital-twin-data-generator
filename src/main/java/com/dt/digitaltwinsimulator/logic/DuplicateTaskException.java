package com.dt.digitaltwinsimulator.logic;

public class DuplicateTaskException extends RuntimeException {
    public DuplicateTaskException(String taskId) {
        super("Task is already running: " + taskId);
    }
}
