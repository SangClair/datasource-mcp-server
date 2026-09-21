package com.dameng.mcp.model.mcp;

public record ToolError(String code, String message, boolean retryable) {
}
