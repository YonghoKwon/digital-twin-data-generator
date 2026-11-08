package com.dt.digitaltwinsimulator.logic;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TaskCancellationLogic {
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationTokens = new ConcurrentHashMap<>();

    public Set<String> getRunningTaskIds() {
        return cancellationTokens.keySet();
    }

    public void registerTask(String taskId) {
        if (cancellationTokens.putIfAbsent(taskId, new AtomicBoolean(false)) != null) {
            throw new DuplicateTaskException(taskId);
        }
    }

    public boolean requestCancellation(String taskId) {
        AtomicBoolean token = cancellationTokens.get(taskId);
        if (token == null) {
            return false;
        }
        token.set(true);
        return true;
    }

    public boolean isCancellationRequested(String taskId) {
        AtomicBoolean token = cancellationTokens.get(taskId);
        return token != null && token.get();
    }

    public void removeTask(String taskId) {
        cancellationTokens.remove(taskId);
    }

    public int requestAllCancellation() {
        cancellationTokens.forEach((k, v) -> v.set(true));
        return cancellationTokens.size();
    }
}
