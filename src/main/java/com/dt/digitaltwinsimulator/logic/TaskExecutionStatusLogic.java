package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.entity.TaskExecutionStatus;
import com.dt.digitaltwinsimulator.entity.dto.TaskExecutionInfoDto;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TaskExecutionStatusLogic {
    private final ConcurrentHashMap<String, TaskExecutionRecord> records = new ConcurrentHashMap<>();

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${simulator.task.max-history:10000}")
    private int maxHistory;

    public void markRunning(String taskId) {
        log.info("taskId={} sentCount=0 elapsedMillis=0 result=RUNNING", taskId);
        records.put(taskId, new TaskExecutionRecord(taskId, TaskExecutionStatus.RUNNING, LocalDateTime.now(), null, new AtomicLong(0), ""));
        trimHistoryIfNeeded();
    }

    public void incrementSentCount(String taskId) {
        TaskExecutionRecord record = records.get(taskId);
        if (record != null) {
            record.sentCount().incrementAndGet();
        }
    }

    public void markSuccess(String taskId) {
        finish(taskId, TaskExecutionStatus.SUCCESS, "success");
    }

    public void markCancelled(String taskId) {
        finish(taskId, TaskExecutionStatus.CANCELLED, "cancelled");
    }

    public void markFailed(String taskId, Throwable throwable) {
        String message = throwable == null ? "failed" : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        finish(taskId, TaskExecutionStatus.FAILED, message);
    }

    public List<TaskExecutionInfoDto> findAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(TaskExecutionRecord::startedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    public List<TaskExecutionInfoDto> findRunning() {
        return records.values().stream()
                .filter(record -> record.status() == TaskExecutionStatus.RUNNING)
                .sorted(Comparator.comparing(TaskExecutionRecord::startedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    public void clearFinished() {
        records.entrySet().removeIf(entry -> entry.getValue().status() != TaskExecutionStatus.RUNNING);
    }

    private void finish(String taskId, TaskExecutionStatus status, String message) {
        records.compute(taskId, (key, oldRecord) -> {
            if (oldRecord == null) {
                return new TaskExecutionRecord(taskId, status, LocalDateTime.now(), LocalDateTime.now(), new AtomicLong(0), message);
            }
            return new TaskExecutionRecord(taskId, status, oldRecord.startedAt(), LocalDateTime.now(), oldRecord.sentCount(), message);
        });
        var record = records.get(taskId);
        log.info("taskId={} sentCount={} elapsedMillis={} result={}", taskId, record.sentCount().get(),
                Duration.between(record.startedAt(), record.finishedAt()).toMillis(), status);
        trimHistoryIfNeeded();
    }

    private void trimHistoryIfNeeded() {
        int safeMaxHistory = Math.max(100, maxHistory);
        if (records.size() <= safeMaxHistory) {
            return;
        }

        int removeCount = records.size() - safeMaxHistory;
        records.entrySet().stream()
                .filter(entry -> entry.getValue().status() != TaskExecutionStatus.RUNNING)
                .sorted(Comparator.comparing(entry -> entry.getValue().startedAt()))
                .limit(removeCount)
                .map(entry -> entry.getKey())
                .toList()
                .forEach(records::remove);
    }

    private TaskExecutionInfoDto toDto(TaskExecutionRecord record) {
        String cancelUrl = record.status() == TaskExecutionStatus.RUNNING
                ? "http://localhost:" + serverPort + "/activemq/task/cancel-task/" + record.taskId()
                : null;
        return new TaskExecutionInfoDto(
                record.taskId(),
                record.status(),
                cancelUrl,
                record.startedAt(),
                record.finishedAt(),
                record.sentCount().get(),
                record.message()
        );
    }

    private record TaskExecutionRecord(
            String taskId,
            TaskExecutionStatus status,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            AtomicLong sentCount,
            String message
    ) {
    }
}
