package com.dt.digitaltwinsimulator.logic;

import com.dt.digitaltwinsimulator.entity.dto.JmsTemplateLoadTestRequestDto;
import com.dt.digitaltwinsimulator.entity.dto.LoadTestResultDto;
import com.dt.digitaltwinsimulator.broker.BrokerConnectionService;
import com.dt.digitaltwinsimulator.broker.MessagePublisher;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class JmsTemplateLoadTestLogic {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final BrokerConnectionService connections;
    private final MessagePublisher publisher;
    private final MessageBodyLogger messageLogger;
    private final TaskCancellationLogic taskCancellationLogic;

    public JmsTemplateLoadTestLogic(BrokerConnectionService connections, MessagePublisher publisher,
            MessageBodyLogger messageLogger, TaskCancellationLogic taskCancellationLogic) {
        this.connections = connections;
        this.publisher = publisher;
        this.messageLogger = messageLogger;
        this.taskCancellationLogic = taskCancellationLogic;
    }

    public LoadTestResultDto run(String taskId, JmsTemplateLoadTestRequestDto requestDto) {
        taskCancellationLogic.registerTask(taskId);
        MessagePublisher.Publication publication = null;
        ExecutorService executorService = null;
        long startedAt = System.nanoTime();
        AtomicLong successCount = new AtomicLong();
        AtomicLong failureCount = new AtomicLong();

        String outcome = "FAILED";
        log.info("taskId={} sentCount=0 elapsedMillis=0 result=RUNNING", taskId);
        try {
            var broker = connections.resolve(requestDto.getActiveMQIp(), requestDto.getId(), requestDto.getPw(), requestDto.getTopic());
            publication = publisher.open(broker);
            var activePublication = publication;
            String topic = broker.topic();
            int workerCount = Math.max(1, requestDto.getWorkerCount());
            int totalMessageCount = Math.max(1, requestDto.getMessageCount());
            AtomicInteger nextIndex = new AtomicInteger(0);
            executorService = Executors.newFixedThreadPool(workerCount);
            List<Future<Void>> futures = new ArrayList<>();
            TpsPacer pacer = new TpsPacer(requestDto.getTargetTps());

            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                int currentWorkerIndex = workerIndex;
                Callable<Void> task = () -> {
                    while (true) {
                        if (taskCancellationLogic.isCancellationRequested(taskId)) {
                            return null;
                        }

                        int messageIndex = nextIndex.getAndIncrement();
                        if (messageIndex >= totalMessageCount) {
                            return null;
                        }

                        pacer.awaitTurn(messageIndex);
                        String message = createMessage(requestDto.getTcName(), currentWorkerIndex, messageIndex, requestDto.getPayload());
                        try {
                            activePublication.publish(topic, message, Map.of());
                            messageLogger.logMessage(taskId, successCount.incrementAndGet(), message);
                        } catch (Exception sendError) {
                            failureCount.incrementAndGet();
                        }
                        sleep(requestDto.getDelayTime());
                    }
                };
                futures.add(executorService.submit(task));
            }

            for (Future<Void> future : futures) {
                future.get();
            }

            boolean cancelled = taskCancellationLogic.isCancellationRequested(taskId);
            outcome = cancelled ? "CANCELLED" : failureCount.get() > 0 ? "FAILED" : "SUCCESS";
            return result(taskId, totalMessageCount, successCount.get(), failureCount.get(), startedAt, workerCount, requestDto.getTargetTps(), cancelled, cancelled ? "cancelled" : "completed");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            long failed = Math.max(1, failureCount.get());
            return result(taskId, requestDto.getMessageCount(), successCount.get(), failed, startedAt, Math.max(1, requestDto.getWorkerCount()), requestDto.getTargetTps(), false, e.getClass().getSimpleName());
        } finally {
            if (executorService != null) {
                executorService.shutdownNow();
            }
            try {
                if (publication != null) publication.close();
            } finally {
                taskCancellationLogic.removeTask(taskId);
                log.info("taskId={} sentCount={} failureCount={} elapsedMillis={} result={}", taskId,
                        successCount.get(), failureCount.get(), (System.nanoTime() - startedAt) / 1_000_000, outcome);
            }
        }
    }

    private LoadTestResultDto result(String taskId, long requestedCount, long successCount, long failureCount, long startedAt, int workerCount, int targetTps, boolean cancelled, String message) {
        long elapsedMillis = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000L);
        double actualTps = successCount * 1000.0 / elapsedMillis;
        return new LoadTestResultDto(taskId, requestedCount, successCount, failureCount, elapsedMillis, round3(actualTps), workerCount, targetTps, cancelled, message);
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String createMessage(String tcName, int workerIndex, int messageIndex, String payload) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String normalizedPayload = normalizePayload(payload);

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"CREATE_TIMESTAMP\":\"").append(timestamp).append("\",");
        sb.append("\"MESSAGE_ID\":\"").append(escape(tcName)).append("\",");
        sb.append("\"DATA_MAP\":{");
        sb.append('\"').append(timestamp).append("\":{");
        sb.append("\"workerIndex\":").append(workerIndex).append(',');
        sb.append("\"messageIndex\":").append(messageIndex);
        if (!normalizedPayload.isBlank()) {
            sb.append(',').append(normalizedPayload);
        }
        sb.append("}}}");
        return sb.toString();
    }

    private String normalizePayload(String payload) {
        String value = payload == null ? "{}" : payload.trim();
        if (value.startsWith("{") && value.endsWith("}") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).trim();
        }
        return "\"payload\":\"" + escape(value) + "\"";
    }

    private void sleep(int delayTime) {
        if (delayTime <= 0) {
            return;
        }
        try {
            Thread.sleep(delayTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static class TpsPacer {
        private final int targetTps;
        private final long startedAt;

        private TpsPacer(int targetTps) {
            this.targetTps = targetTps;
            this.startedAt = System.nanoTime();
        }

        private void awaitTurn(int messageIndex) {
            if (targetTps <= 0) {
                return;
            }
            long targetNanos = startedAt + ((long) messageIndex * 1_000_000_000L / targetTps);
            long sleepNanos = targetNanos - System.nanoTime();
            if (sleepNanos <= 0) {
                return;
            }
            try {
                long millis = sleepNanos / 1_000_000L;
                int nanos = (int) (sleepNanos % 1_000_000L);
                Thread.sleep(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
