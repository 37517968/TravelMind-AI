package com.travelmind.aiagent.harness;

import lombok.Getter;

@Getter
public class HarnessException extends RuntimeException {
    private final boolean retryable;
    private final String errorCode;

    public HarnessException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public HarnessException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
}
