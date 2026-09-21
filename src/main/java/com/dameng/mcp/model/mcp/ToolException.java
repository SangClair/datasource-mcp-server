package com.dameng.mcp.model.mcp;

public class ToolException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public ToolException(String code, String message) {
        this(code, message, false, null);
    }

    public ToolException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
