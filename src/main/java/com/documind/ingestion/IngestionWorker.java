package com.documind.ingestion;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The processor must cooperate with interruption; unresponsive tasks trigger fail-stop. */
@Component
@ConditionalOnProperty(name = "documind.ingestion.worker-enabled", havingValue = "true")
public class IngestionWorker {
    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    private final IngestionQueue queue;
    private final IngestionProcessor processor;
    private final ExecutorService workers, transitions;
    private final ScheduledExecutorService heartbeats, timers;
    private final Semaphore capacity;
    private final Duration lease, heartbeat, deadline, stopGrace;
    private final Runnable failStop;
    private final Set<Task> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final String workerId = UUID.randomUUID().toString();

    @Autowired
    public IngestionWorker(IngestionQueue queue, ObjectProvider<IngestionProcessor> processor,
            ConfigurableApplicationContext context,
            @Value("${documind.ingestion.concurrency:2}") int concurrency,
            @Value("${documind.ingestion.lease-seconds:120}") long leaseSeconds,
            @Value("${documind.ingestion.heartbeat-seconds:20}") long heartbeatSeconds,
            @Value("${documind.ingestion.deadline-seconds:600}") long deadlineSeconds,
            @Value("${documind.ingestion.stop-grace-seconds:10}") long graceSeconds) {
        this(queue, processor.getIfAvailable(), concurrency, leaseSeconds, heartbeatSeconds, deadlineSeconds,
                graceSeconds, () -> {
                    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
                    // Close on a separate daemon thread: shutdown must not wait for its timer callback.
                    Thread.ofPlatform().daemon().name("ingestion-fail-stop").start(context::close);
                });
    }

    IngestionWorker(IngestionQueue queue, IngestionProcessor processor, int concurrency,
                    long leaseSeconds, long heartbeatSeconds, long deadlineSeconds) {
        this(queue, processor, concurrency, leaseSeconds, heartbeatSeconds, deadlineSeconds, 10, () -> {});
    }

    IngestionWorker(IngestionQueue queue, IngestionProcessor processor, int concurrency,
                    long leaseSeconds, long heartbeatSeconds, long deadlineSeconds, long graceSeconds, Runnable failStop) {
        if (concurrency < 1 || leaseSeconds < 1 || heartbeatSeconds < 1 || heartbeatSeconds >= leaseSeconds
                || deadlineSeconds < 1 || graceSeconds < 1) throw new IllegalArgumentException("Invalid ingestion worker settings");
        this.queue = queue; this.processor = processor; this.failStop = failStop;
        lease = Duration.ofSeconds(leaseSeconds); heartbeat = Duration.ofSeconds(heartbeatSeconds);
        deadline = Duration.ofSeconds(deadlineSeconds); stopGrace = Duration.ofSeconds(graceSeconds);
        capacity = new Semaphore(concurrency);
        workers = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().daemon().name("ingestion-worker-", 0).factory());
        heartbeats = Executors.newScheduledThreadPool(concurrency, Thread.ofPlatform().daemon().name("ingestion-heartbeat-", 0).factory());
        timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ingestion-timer-", 0).factory());
        transitions = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency), Thread.ofPlatform().daemon().name("ingestion-transition-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${documind.ingestion.poll-ms:2000}")
    public void poll() {
        if (processor == null || stopping.get()) return;
        while (!stopping.get() && capacity.tryAcquire()) {
            try {
                var claim = queue.claim(workerId, lease);
                if (claim.isEmpty()) { capacity.release(); return; }
                launch(claim.orElseThrow());
            } catch (RuntimeException failure) {
                capacity.release(); log.error("Ingestion claim failed type={}", failure.getClass().getName()); return;
            }
        }
    }

    private void launch(JobClaimStore.Claim claim) {
        Task task = new Task(claim);
        active.add(task);
        try {
            task.work = workers.submit(() -> process(task));
            task.heartbeatTask = heartbeats.scheduleAtFixedRate(() -> heartbeat(task),
                    heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
            task.deadlineTask = timers.schedule(() -> cancel(task), deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (task.state.get() == 2) task.cancelTimers();
        } catch (RejectedExecutionException failure) {
            cancel(task);
            log.warn("Ingestion worker cannot schedule task; lease recovery will retry");
        }
    }

    private void process(Task task) {
        if (!task.state.compareAndSet(0, 1)) return;
        try {
            var result = processor.process(task.claim);
            if (task.allowed()) {
                boolean completed = queue.complete(task.claim, result, task::allowed);
                if (!completed && !task.allowed()) cancel(task);
            } else cancel(task);
        } catch (IngestionProcessor.ProcessingFailure failure) {
            if (task.allowed()) queue.fail(task.claim, failure.retryable(), failure.retryAfter(), failure.code());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            log.warn("Ingestion processing failed type={}", failure.getClass().getName());
            if (task.allowed()) queue.fail(task.claim, true, null);
        } finally {
            task.state.set(2);
            task.cancelTimers();
            active.remove(task);
            capacity.release();
        }
    }

    private void heartbeat(Task task) {
        if (!task.allowed()) { cancel(task); return; }
        try {
            if (!queue.heartbeat(task.claim, lease)) cancel(task);
        } catch (RuntimeException failure) {
            log.warn("Ingestion heartbeat failed type={}", failure.getClass().getName());
            cancel(task);
        }
    }

    private void cancel(Task task) {
        if (!task.cancelled.compareAndSet(false, true)) return;
        Future<?> work = task.work;
        if (work != null) work.cancel(true);
        ScheduledFuture<?> heartbeatTask = task.heartbeatTask;
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (task.state.compareAndSet(0, 2)) {
            active.remove(task);
            capacity.release();
        }
        try {
            transitions.execute(() -> {
                try { queue.fail(task.claim, true, null); }
                catch (RuntimeException failure) { log.warn("Ingestion cancellation transition failed type={}", failure.getClass().getName()); }
            });
        } catch (RejectedExecutionException failure) {
            log.warn("Ingestion cancellation transition unavailable; lease recovery will retry");
        }
        try {
            timers.schedule(() -> {
                if (task.state.get() != 2 && stopping.compareAndSet(false, true)) {
                    log.error("Ingestion task did not stop after cancellation; stopping application for supervised restart");
                    failStop.run();
                }
            }, stopGrace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { /* already stopping */ }
    }

    @PreDestroy
    void stop() {
        stopping.set(true);
        for (Task task : active) cancel(task);
        timers.shutdownNow(); heartbeats.shutdownNow(); transitions.shutdownNow(); workers.shutdownNow();
        try { workers.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private final class Task {
        private final JobClaimStore.Claim claim;
        private final long startedAt = System.nanoTime();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        // 0 queued, 1 running, 2 finished/cancelled before start.
        private final AtomicInteger state = new AtomicInteger();
        private volatile Future<?> work;
        private volatile ScheduledFuture<?> heartbeatTask, deadlineTask;

        private Task(JobClaimStore.Claim claim) { this.claim = claim; }
        private boolean allowed() {
            return !cancelled.get() && !Thread.currentThread().isInterrupted()
                    && System.nanoTime() - startedAt < deadline.toNanos();
        }
        private void cancelTimers() {
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            if (deadlineTask != null) deadlineTask.cancel(false);
        }
    }
}
