package com.dameng.mcp.config;

import com.dameng.mcp.model.mcp.ToolException;
import com.dameng.mcp.model.mcp.ToolResponse;
import com.dameng.mcp.util.ConnectionError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpToolSpecFactory {

    @FunctionalInterface
    public interface Handler {
        ToolResponse handle(Map<String, Object> arguments) throws Exception;
    }

    private final ObjectMapper objectMapper;
    private final McpToolProperties properties;

    public McpToolSpecFactory(ObjectMapper objectMapper, McpToolProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public McpServerFeatures.SyncToolSpecification create(
            String name,
            String title,
            String description,
            McpSchema.JsonSchema inputSchema,
            Map<String, Object> dataSchema,
            boolean readOnly,
            Handler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .outputSchema(outputSchema(dataSchema))
                .annotations(new McpSchema.ToolAnnotations(
                        title, readOnly, !readOnly, readOnly, true, false))
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(handler, request.arguments()))
                .build();
    }

    private McpSchema.CallToolResult invoke(Handler handler, Map<String, Object> arguments) {
        long startNanos = System.nanoTime();
        ToolResponse response;
        boolean error = false;
        try {
            response = handler.handle(arguments == null ? Map.of() : arguments);
        } catch (ToolException e) {
            error = true;
            response = ToolResponse.error(e.getCode(), safeMessage(e), e.isRetryable());
        } catch (SecurityException e) {
            error = true;
            String code = safeMessage(e).contains("只读") ? "READ_ONLY" : "SECURITY_REJECTED";
            response = ToolResponse.error(code, safeMessage(e), false);
        } catch (IllegalArgumentException e) {
            error = true;
            String message = safeMessage(e);
            String code = message.contains("未找到") && message.contains("数据源")
                    ? "DATASOURCE_NOT_FOUND" : "INVALID_ARGUMENT";
            response = ToolResponse.error(code, message, false);
        } catch (IllegalStateException e) {
            error = true;
            String message = safeMessage(e);
            String code = message.contains("数据源") ? "DATASOURCE_NOT_FOUND" : "EXECUTION_FAILED";
            response = ToolResponse.error(code, message, false);
        } catch (Exception e) {
            error = true;
            String code = ConnectionError.isConnectionFailure(e) ? "CONNECTION_FAILED" : "EXECUTION_FAILED";
            response = ToolResponse.error(code, safeMessage(e), "CONNECTION_FAILED".equals(code));
        }
        if ("ok".equals(response.status())) {
            Map<String, Object> meta = new LinkedHashMap<>(response.meta());
            meta.putIfAbsent("duration_ms", (System.nanoTime() - startNanos) / 1_000_000L);
            response = ToolResponse.ok(response.data(), response.warnings(), meta);
        }
        return serialize(response, error);
    }

    private McpSchema.CallToolResult serialize(ToolResponse response, boolean error) {
        try {
            Map<String, Object> structured = objectMapper.convertValue(response, Map.class);
            if (structured.get("error") == null) {
                structured.remove("error");
            }
            String json = objectMapper.writeValueAsString(structured);
            if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxOutputBytes()) {
                ToolResponse tooLarge = ToolResponse.error("RESULT_TOO_LARGE",
                        "工具结果超过 " + properties.getMaxOutputBytes()
                                + " 字节，请缩小 limit、收紧过滤条件或继续使用 next_cursor 分页。", false);
                structured = objectMapper.convertValue(tooLarge, Map.class);
                json = objectMapper.writeValueAsString(structured);
                error = true;
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .structuredContent(structured)
                    .isError(error || "error".equals(response.status()))
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to serialize tool result: " + safeMessage(e))
                    .isError(true)
                    .build();
        }
    }

    public static McpSchema.JsonSchema inputSchema(Map<String, Object> properties, String... required) {
        return new McpSchema.JsonSchema("object", properties, List.of(required), false, null, null);
    }

    public static Map<String, Object> property(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }

    public static Map<String, Object> objectData(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        schema.put("additionalProperties", true);
        return schema;
    }

    private Map<String, Object> outputSchema(Map<String, Object> dataSchema) {
        Map<String, Object> errorSchema = objectData(Map.of(
                "code", property("string", "Stable error code"),
                "message", property("string", "Actionable error message"),
                "retryable", property("boolean", "Whether retrying may succeed")),
                "code", "message", "retryable");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of("type", "string", "enum", List.of("ok", "error")));
        Map<String, Object> typedDataSchema = dataSchema == null ? objectData(Map.of()) : dataSchema;
        properties.put("data", Map.of("anyOf", List.of(
                typedDataSchema, Map.of("type", "object", "maxProperties", 0))));
        properties.put("warnings", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("meta", objectData(Map.ofEntries(
                Map.entry("datasource", Map.of("anyOf", List.of(
                        property("string", "Resolved data source name"), Map.of("type", "null")))),
                Map.entry("duration_ms", property("integer", "Server-side duration in milliseconds")),
                Map.entry("returned_count", property("integer", "Number of items returned")),
                Map.entry("limit", property("integer", "Effective page size limit")),
                Map.entry("has_more", property("boolean", "Whether another page is available")),
                Map.entry("next_cursor", Map.of("anyOf", List.of(
                        property("string", "Opaque cursor for the next page"), Map.of("type", "null")))),
                Map.entry("unsafe_scope", property("boolean", "Whether a write lacks a WHERE clause")),
                Map.entry("truncated", property("boolean", "Whether returned text was truncated")),
                Map.entry("truncated_cells", property("integer", "Number of truncated SQL cells")),
                Map.entry("truncated_messages", property("integer", "Number of truncated Kafka messages")))));
        properties.put("error", Map.of("anyOf", List.of(errorSchema, Map.of("type", "null"))));
        return objectData(properties, "status", "data", "warnings", "meta");
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "Unknown error" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }
}
