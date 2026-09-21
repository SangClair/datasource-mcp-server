package com.dameng.mcp.model.mcp;

import java.util.List;
import java.util.Map;

public record ToolResponse(
        String status,
        Map<String, Object> data,
        List<String> warnings,
        Map<String, Object> meta,
        ToolError error) {

    public static ToolResponse ok(Map<String, Object> data, List<String> warnings, Map<String, Object> meta) {
        return new ToolResponse("ok", data == null ? Map.of() : data,
                warnings == null ? List.of() : List.copyOf(warnings),
                meta == null ? Map.of() : Map.copyOf(meta), null);
    }

    public static ToolResponse error(String code, String message, boolean retryable) {
        return new ToolResponse("error", Map.of(), List.of(), Map.of(),
                new ToolError(code, message, retryable));
    }
}
