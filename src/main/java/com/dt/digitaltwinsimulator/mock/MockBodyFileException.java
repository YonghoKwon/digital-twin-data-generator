package com.dt.digitaltwinsimulator.mock;

class MockBodyFileException extends RuntimeException {
    MockBodyFileException(MockInputException cause) {
        super("Registered mock bodyFile is no longer readable within routes-dir", cause);
    }
}
