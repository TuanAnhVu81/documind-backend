package com.documind.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.documind.retrieval.service.RetrievalService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class ChatStreamServiceTest {
    static final class BlockingEmitter extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch tokenEntered = new CountDownLatch(1);
        final CountDownLatch releaseToken = new CountDownLatch(1);
        @Override public void send(SseEventBuilder builder) throws java.io.IOException {
            String value = builder.build().stream().map(item -> String.valueOf(item.getData())).collect(java.util.stream.Collectors.joining());
            String name = value.contains("event:token") ? "token" : value.contains("event:error") ? "error" : "start";
            if ("token".equals(name)) {
                tokenEntered.countDown();
                try { if (!releaseToken.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
            }
            events.add(name);
        }
    }

    @Test void transientHeartbeatFailureDoesNotTerminateActiveGeneration() throws Exception {
        var turns = mock(ChatTurnService.class);
        var lifecycle = mock(GenerationLifecycle.class);
        UUID owner = UUID.randomUUID(), conversation = UUID.randomUUID();
        var started = new ChatTurnService.Started(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());
        var request = new ChatRequest("Question?", RetrievalService.Scope.ALL_READY, null, "hash");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(lifecycle.heartbeat(started.generation(), 0)).thenThrow(new DataAccessResourceFailureException("simulated"))
                .thenReturn(true);
        when(lifecycle.state(started.generation())).thenReturn(new GenerationLifecycle.State("GENERATING", null));
        when(turns.execute(eq(owner), eq(started), eq(request.content()), eq(request.scope()), isNull(), any()))
                .thenAnswer(invocation -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; });
        var service = new ChatStreamService(turns, lifecycle, 30, 3_000, JsonMapper.builder().build());
        try {
            service.open(owner, conversation, request, new ChatAdmissionService.Admission(started, null), "test");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            verify(lifecycle, timeout(1_500).atLeast(2)).heartbeat(started.generation(), 0);
            verify(lifecycle, never()).terminate(any(), anyLong(), anyString(), anyString());
        } finally { release.countDown(); service.shutdown(); }
    }

    @Test void terminalErrorCannotBeFollowedByAnAlreadyPendingToken() throws Exception {
        var turns = mock(ChatTurnService.class);
        var lifecycle = mock(GenerationLifecycle.class);
        UUID owner = UUID.randomUUID(), conversation = UUID.randomUUID();
        var started = new ChatTurnService.Started(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());
        var request = new ChatRequest("Question?", RetrievalService.Scope.ALL_READY, null, "hash");
        var holdProvider = new CountDownLatch(1);
        var emitter = new BlockingEmitter();
        when(lifecycle.state(started.generation())).thenReturn(new GenerationLifecycle.State("GENERATING", null));
        when(turns.execute(eq(owner), eq(started), eq(request.content()), eq(request.scope()), isNull(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> sink = invocation.getArgument(5);
                    sink.accept("temporary"); holdProvider.await(5, TimeUnit.SECONDS); return null;
                });
        var service = new ChatStreamService(turns, lifecycle, 15_000, 10_000, JsonMapper.builder().build(), ignored -> emitter);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            service.open(owner, conversation, request, new ChatAdmissionService.Admission(started, null), "test");
            assertThat(emitter.tokenEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> service.sourceDeleted(new com.documind.document.service.SourceGenerationsCancelled(List.of(started.generation()))));
            assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            emitter.releaseToken.countDown();
            deletion.get(2, TimeUnit.SECONDS);
            assertThat(emitter.events).containsExactly("start", "token", "error");
        } finally { emitter.releaseToken.countDown(); holdProvider.countDown(); service.shutdown(); }
    }
}
