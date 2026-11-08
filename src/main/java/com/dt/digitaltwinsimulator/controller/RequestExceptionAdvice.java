package com.dt.digitaltwinsimulator.controller;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionException;
import com.dt.digitaltwinsimulator.logic.DuplicateTaskException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = ActiveMQRequestController.class)
public class RequestExceptionAdvice {
    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<Map<String, String>> invalidInput(Exception e) {
        return error(400, "INVALID_REQUEST", "Invalid input or file content.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidBody(Exception e) {
        return error(400, "INVALID_REQUEST", "Invalid request body.");
    }

    @ExceptionHandler(BrokerConnectionException.class)
    public ResponseEntity<Map<String, String>> brokerUnavailable(BrokerConnectionException e) {
        return error(502, "BROKER_CONNECTION_FAILED", e.getMessage());
    }

    @ExceptionHandler(DuplicateTaskException.class)
    public ResponseEntity<Map<String, String>> duplicateTask(DuplicateTaskException e) {
        return error(409, "DUPLICATE_TASK_ID", "Task is already running.");
    }

    private ResponseEntity<Map<String, String>> error(int status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of("error", error, "message", message));
    }
}
