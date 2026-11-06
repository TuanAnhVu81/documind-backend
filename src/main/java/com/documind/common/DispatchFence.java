package com.documind.common;

import java.util.function.Supplier;

/** Protects submission of an asynchronous provider request, not its execution. */
@FunctionalInterface
public interface DispatchFence {
    <T> T submit(Supplier<T> submission);
}
