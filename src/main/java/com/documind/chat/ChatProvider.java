package com.documind.chat;

/** Provider calls are performed outside database transactions. */
public interface ChatProvider {
    record Reply(String text, Long promptTokens, Long completionTokens) {}
    Reply complete(String system, String input);
    default Reply complete(String system, String input, com.documind.common.DispatchFence fence) {
        return submitFallback(fence, () -> complete(system, input));
    }
    default Reply stream(String system, String input, java.util.function.Consumer<String> tokens) {
        Reply result = complete(system, input);
        if (!result.text().isEmpty()) tokens.accept(result.text());
        return result;
    }
    default Reply stream(String system, String input, java.util.function.Consumer<String> tokens,
                         java.util.function.Consumer<Reply> usage) {
        Reply result = stream(system, input, tokens);
        usage.accept(result);
        return result;
    }
    default Reply stream(String system, String input, java.util.function.Consumer<String> tokens,
                         java.util.function.Consumer<Reply> usage, com.documind.common.DispatchFence fence) {
        return submitFallback(fence, () -> stream(system, input, tokens, usage));
    }

    private static Reply submitFallback(com.documind.common.DispatchFence fence, java.util.concurrent.Callable<Reply> call) {
        var task = new java.util.concurrent.FutureTask<>(call);
        fence.submit(() -> { Thread.startVirtualThread(task); return null; });
        try { return task.get(); }
        catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider call interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Provider call failed", failure.getCause());
        }
    }
}
