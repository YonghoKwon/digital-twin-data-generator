package com.dt.digitaltwinsimulator.mock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = MockAdminController.class)
class MockExceptionHandler {
    @ExceptionHandler(MockInputException.class)
    ResponseEntity<Map<String, String>> invalid(MockInputException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_MOCK_ROUTE", "message", exception.getMessage()));
    }

    @ExceptionHandler(MockNotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(404).body(Map.of("error", "MOCK_ROUTE_NOT_FOUND"));
    }
}
