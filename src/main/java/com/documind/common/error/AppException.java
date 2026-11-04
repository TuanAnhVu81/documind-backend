package com.documind.common.error;

import java.util.Objects;

/** A known application failure, backed by a public-safe error definition. */
public class AppException extends RuntimeException {
    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).detail());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
