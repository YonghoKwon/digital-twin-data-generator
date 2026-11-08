package com.dt.digitaltwinsimulator.controller;

import com.dt.digitaltwinsimulator.broker.BrokerConnectionException;
import com.dt.digitaltwinsimulator.logic.DuplicateTaskException;
import com.dt.digitaltwinsimulator.logic.SensitiveLogMasker;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

// controller 패키지의 모든 API(전송·Task 등)에 같은 오류 형식을 적용한다. REST Mock은 자체 handler를 쓴다.
@RestControllerAdvice(basePackageClasses = ActiveMQRequestController.class)
public class RequestExceptionAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException e) {
        // 검증 메시지는 어떤 파일·행·필드를 고쳐야 하는지 알려주므로 그대로 돌려준다.
        String message = e.getMessage() == null || e.getMessage().isBlank() ? "Invalid input." : e.getMessage();
        return error(400, "INVALID_REQUEST", SensitiveLogMasker.mask(message));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unreadableFile(IOException e) {
        return error(400, "INVALID_REQUEST", "File is missing or unreadable.");
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
