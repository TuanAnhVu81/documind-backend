package com.documind.chat;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Emits only temporary provider tokens until a fenced canonical message is committed. */
@Service
public class ChatStreamService {
    private final ChatTurnService turns;
    private final GenerationLifecycle lifecycle;
    private final long heartbeatMs;
    private final long deadlineMs;
    private final tools.jackson.databind.json.JsonMapper json;
    private final java.util.function.LongFunction<SseEmitter> emitterFactory;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "chat-stream-timer"); thread.setDaemon(true); return thread;
    });
    private final java.util.concurrent.ConcurrentMap<UUID, Session> active = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ChatStreamService(ChatTurnService turns, GenerationLifecycle lifecycle,
            @org.springframework.beans.factory.annotation.Value("${documind.chat.heartbeat-ms:15000}") long heartbeatMs,
            @org.springframework.beans.factory.annotation.Value("${documind.chat.deadline-ms:180000}") long deadlineMs,
            tools.jackson.databind.json.JsonMapper json) {
        this(turns, lifecycle, heartbeatMs, deadlineMs, json, SseEmitter::new);
    }

    ChatStreamService(ChatTurnService turns, GenerationLifecycle lifecycle, long heartbeatMs, long deadlineMs,
            tools.jackson.databind.json.JsonMapper json, java.util.function.LongFunction<SseEmitter> emitterFactory) {
        if (heartbeatMs <= 0 || deadlineMs <= 0) throw new IllegalArgumentException("Invalid chat timing");
        this.turns = turns; this.lifecycle = lifecycle; this.heartbeatMs = heartbeatMs; this.deadlineMs = deadlineMs; this.json = json;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter open(UUID owner, UUID conversation, ChatRequest request, ChatAdmissionService.Admission admission, String requestId) {
        var emitter = emitterFactory.apply(deadlineMs);
        if (admission.replay() != null) throw new IllegalArgumentException("Replay requires a writable response stream");
        var session = new Session(owner, conversation, request, admission.started(), requestId, emitter);
        active.put(session.started.generation(), session);
        emitter.onTimeout(() -> session.cancel("TIMEOUT"));
        emitter.onError(error -> session.cancel("CLIENT_DISCONNECTED"));
        emitter.onCompletion(() -> { if (!session.finished.get()) session.cancel("CLIENT_DISCONNECTED"); });
        try {
            send(emitter, "start", new Start(session.started.generation(), session.started.userMessage(), session.started.assistant(), false));
            session.heartbeat = timer.scheduleAtFixedRate(session::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            session.deadline = timer.schedule(() -> session.cancel("TIMEOUT"), deadlineMs, TimeUnit.MILLISECONDS);
            if (!session.finished.get()) session.future = workers.submit(session::run);
            if (session.finished.get()) {
                session.stopTimers();
                if (session.future != null) session.future.cancel(true);
            }
        } catch (IOException | RuntimeException failure) {
            session.cancel("CLIENT_DISCONNECTED");
        }
        return emitter;
    }

    public void replay(UUID owner, UUID conversation, ChatAdmissionService.Previous previous, String requestId,
            java.io.OutputStream output) throws IOException {
        boolean[] doneSent = {false};
        try {
            lifecycle.writeReplay(owner, conversation, previous.assistant(), snapshot -> {
                writeEvent(output, "start", new Start(previous.generation(), previous.userMessage(), previous.assistant(), true));
                if (!snapshot.message().content().isEmpty()) writeEvent(output, "token", Map.of("text", snapshot.message().content()));
                writeEvent(output, "sources", Map.of("sources", snapshot.message().sources()));
                writeEvent(output, "done", new Done(snapshot.message(), snapshot.usage()));
                output.flush();
                doneSent[0] = true;
            });
        } catch (RuntimeException failure) {
            if (doneSent[0]) {
                org.slf4j.LoggerFactory.getLogger(ChatStreamService.class).warn("Replay completed but its read transaction failed to close cleanly");
                return;
            }
            writeEvent(output, "error", new ErrorEvent("PERSISTENCE_ERROR", "Không thể phát lại lượt trả lời.", previous.generation(),
                    previous.assistant(), "COMPLETE", false, requestId));
            output.flush();
        }
    }

    private void writeEvent(java.io.OutputStream output, String name, Object data) throws IOException {
        output.write(("event:" + name + "\ndata:").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(json.writeValueAsBytes(data));
        output.write('\n'); output.write('\n');
    }

    @org.springframework.context.event.EventListener
    public void sourceDeleted(com.documind.document.SourceGenerationsCancelled event) {
        for (UUID id : event.generationIds()) {
            var session = active.get(id);
            if (session != null) session.sourceDeleted();
        }
    }

    private record Start(UUID generationId, UUID userMessageId, UUID assistantMessageId, boolean replayed) {}
    private record Done(ConversationService.MessageDto message, GenerationLifecycle.Usage usage) {}
    private record ErrorEvent(String code, String message, UUID generationId, UUID assistantMessageId,
                              String status, boolean discardPartial, String requestId) {}
    private static final class InterruptedStream extends RuntimeException {}

    private static void send(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private final class Session {
        final UUID owner, conversation;
        final ChatRequest request;
        final ChatTurnService.Started started;
        final String requestId;
        final SseEmitter emitter;
        final AtomicBoolean finished = new AtomicBoolean();
        final Object deliveryLock = new Object();
        final StringBuilder partial = new StringBuilder();
        final StringBuilder undecided = new StringBuilder();
        boolean forwarding;
        volatile Future<?> future;
        volatile Thread worker;
        volatile ScheduledFuture<?> heartbeat, deadline;

        Session(UUID owner, UUID conversation, ChatRequest request, ChatTurnService.Started started, String requestId, SseEmitter emitter) {
            this.owner = owner; this.conversation = conversation; this.request = request; this.started = started;
            this.requestId = requestId; this.emitter = emitter;
        }

        void run() {
            if (finished.get()) return;
            worker = Thread.currentThread();
            try {
                turns.execute(owner, started, request.content(), request.scope(), request.documentIds(), this::token);
                if (finished.get()) return;
                var state = lifecycle.state(started.generation());
                if (state == null || !"COMPLETE".equals(state.status())) { failFromState(state); return; }
                synchronized (deliveryLock) {
                    if (!finished.compareAndSet(false, true)) return;
                    stopTimers();
                    lifecycle.writeReplay(owner, conversation, started.assistant(), snapshot -> {
                        send(emitter, "sources", Map.of("sources", snapshot.message().sources()));
                        send(emitter, "done", new Done(snapshot.message(), snapshot.usage()));
                    });
                    emitter.complete();
                }
            } catch (IOException | RuntimeException failure) {
                if (!finished.get()) {
                    GenerationLifecycle.State state = null;
                    try { state = lifecycle.state(started.generation()); } catch (RuntimeException ignored) { }
                    failFromState(state);
                }
            }
        }

        void token(String text) {
            if (finished.get()) throw new InterruptedStream();
            var state = lifecycle.state(started.generation());
            if (state == null || !"GENERATING".equals(state.status())) { failFromState(state); throw new InterruptedStream(); }
            try {
                String outgoing = text;
                synchronized (partial) {
                    if (!forwarding) {
                        undecided.append(text);
                        if ("NO_EVIDENCE".startsWith(undecided.toString().strip())) return;
                        outgoing = undecided.toString(); undecided.setLength(0); forwarding = true;
                    }
                    partial.append(outgoing);
                }
                synchronized (deliveryLock) {
                    if (finished.get()) throw new InterruptedStream();
                    send(emitter, "token", Map.of("text", outgoing));
                }
            } catch (IOException | IllegalStateException disconnected) {
                cancel("CLIENT_DISCONNECTED"); throw new InterruptedStream();
            }
        }

        void heartbeat() {
            if (finished.get()) return;
            try {
                if (!lifecycle.heartbeat(started.generation(), 0)) {
                    var state = lifecycle.state(started.generation());
                    if (state == null || !"COMPLETE".equals(state.status())) failFromState(state);
                    return;
                }
                synchronized (deliveryLock) {
                    if (!finished.get()) send(emitter, "heartbeat", Map.of());
                }
            } catch (IOException | IllegalStateException disconnected) { cancel("CLIENT_DISCONNECTED"); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(ChatStreamService.class)
                        .warn("Unable to update chat heartbeat; recovery will check the persisted lease");
            }
        }

        void cancel(String outcome) {
            synchronized (deliveryLock) {
            if (!finished.compareAndSet(false, true)) return;
            stopTimers();
            String content; synchronized (partial) { content = partial.toString(); }
            boolean saved = false;
            try { saved = lifecycle.terminate(started.generation(), 0, outcome, content); } catch (RuntimeException ignored) { }
            Future<?> task = future; if (task != null && worker != Thread.currentThread()) task.cancel(true);
            if (!"CLIENT_DISCONNECTED".equals(outcome)) {
                GenerationLifecycle.State current = null;
                if (!saved) try { current = lifecycle.state(started.generation()); } catch (RuntimeException ignored) { }
                if (current != null && "COMPLETE".equals(current.status())) {
                    try {
                        lifecycle.writeReplay(owner, conversation, started.assistant(), snapshot -> {
                            send(emitter, "sources", Map.of("sources", snapshot.message().sources()));
                            send(emitter, "done", new Done(snapshot.message(), snapshot.usage()));
                        });
                    } catch (IOException | RuntimeException failure) { sendError("PERSISTENCE_ERROR", "COMPLETE"); }
                } else if (saved) sendError(outcome, "INCOMPLETE");
                else if (current != null && current.outcome() != null) sendError(current.outcome(), current.status());
                else sendError("PERSISTENCE_ERROR", current == null ? null : current.status());
            }
            emitter.complete();
            }
        }

        void sourceDeleted() {
            synchronized (deliveryLock) {
            if (!finished.compareAndSet(false, true)) return;
            stopTimers();
            Future<?> task = future; if (task != null && worker != Thread.currentThread()) task.cancel(true);
            sendError("SOURCE_DELETED", "INCOMPLETE");
            emitter.complete();
            }
        }

        void failFromState(GenerationLifecycle.State state) {
            synchronized (deliveryLock) {
            if (!finished.compareAndSet(false, true)) return;
            stopTimers();
            String code = state == null || state.outcome() == null || "COMPLETE".equals(state.status())
                    ? "PERSISTENCE_ERROR" : state.outcome();
            sendError(code, state == null ? "ERROR" : state.status());
            Future<?> task = future; if (task != null && worker != Thread.currentThread()) task.cancel(true);
            emitter.complete();
            }
        }

        void sendError(String code, String status) {
            try { send(emitter, "error", new ErrorEvent(code, "Không thể hoàn tất lượt trả lời.", started.generation(),
                    started.assistant(), status, "SOURCE_DELETED".equals(code), requestId)); }
            catch (IOException | IllegalStateException ignored) { }
        }

        void stopTimers() {
            if (heartbeat != null) heartbeat.cancel(false);
            if (deadline != null) deadline.cancel(false);
            active.remove(started.generation(), this);
        }
    }

    @PreDestroy public void shutdown() { timer.shutdownNow(); workers.shutdownNow(); }
}
