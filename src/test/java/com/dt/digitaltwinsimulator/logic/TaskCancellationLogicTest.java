package com.dt.digitaltwinsimulator.logic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCancellationLogicTest {

    @Test
    void concurrentRegistrationHasExactlyOneOwner() throws Exception {
        var logic = new TaskCancellationLogic();
        var start = new java.util.concurrent.CountDownLatch(1);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        logic.registerTask("same-id");
                        accepted.incrementAndGet();
                    } catch (DuplicateTaskException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(7);
        assertThat(logic.getRunningTaskIds()).containsExactly("same-id");
    }

    @Test
    void requestCancellationDoesNotCreateMissingTask() {
        TaskCancellationLogic logic = new TaskCancellationLogic();

        boolean requested = logic.requestCancellation("missing-task");

        assertThat(requested).isFalse();
        assertThat(logic.getRunningTaskIds()).isEmpty();
    }

    @Test
    void requestCancellationMarksExistingTask() {
        TaskCancellationLogic logic = new TaskCancellationLogic();
        logic.registerTask("task-1");

        boolean requested = logic.requestCancellation("task-1");

        assertThat(requested).isTrue();
        assertThat(logic.isCancellationRequested("task-1")).isTrue();
    }

    @Test
    void requestAllCancellationMarksAllTasks() {
        TaskCancellationLogic logic = new TaskCancellationLogic();
        logic.registerTask("task-1");
        logic.registerTask("task-2");

        int count = logic.requestAllCancellation();

        assertThat(count).isEqualTo(2);
        assertThat(logic.isCancellationRequested("task-1")).isTrue();
        assertThat(logic.isCancellationRequested("task-2")).isTrue();
    }
}
