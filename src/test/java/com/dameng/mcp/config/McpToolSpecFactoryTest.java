package com.dameng.mcp.config;

import com.dameng.mcp.model.mcp.ToolException;
import com.dameng.mcp.model.mcp.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolSpecFactoryTest {

    @Test
    void returnsMatchingTextAndStructuredContent() throws Exception {
        McpToolSpecFactory factory = new McpToolSpecFactory(new ObjectMapper(), new McpToolProperties());
        var spec = factory.create("test_read", "Test", "test",
                McpToolSpecFactory.inputSchema(Map.of()), McpToolSpecFactory.objectData(Map.of()), true,
                args -> ToolResponse.ok(Map.of("value", 42), List.of(), Map.of()));

        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("test_read", Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        ObjectMapper mapper = new ObjectMapper();
        assertThat(text).isEqualTo(mapper.writeValueAsString(result.structuredContent()));
    }

    @Test
    void mapsToolErrorsToIsErrorAndStableCode() {
        McpToolSpecFactory factory = new McpToolSpecFactory(new ObjectMapper(), new McpToolProperties());
        var spec = factory.create("test_error", "Test", "test",
                McpToolSpecFactory.inputSchema(Map.of()), McpToolSpecFactory.objectData(Map.of()), true,
                args -> { throw new ToolException("INVALID_ARGUMENT", "bad input"); });

        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("test_error", Map.of()));

        assertThat(result.isError()).isTrue();
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured).containsEntry("status", "error");
        assertThat((Map<String, Object>) structured.get("error"))
                .containsEntry("code", "INVALID_ARGUMENT");
    }

    @Test
    void replacesOversizedResultsWithBoundedError() {
        McpToolProperties properties = new McpToolProperties();
        properties.setMaxOutputBytes(300);
        McpToolSpecFactory factory = new McpToolSpecFactory(new ObjectMapper(), properties);
        var spec = factory.create("test_large", "Test", "test",
                McpToolSpecFactory.inputSchema(Map.of()), McpToolSpecFactory.objectData(Map.of()), true,
                args -> ToolResponse.ok(Map.of("value", "x".repeat(1000)), List.of(), Map.of()));

        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("test_large", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("RESULT_TOO_LARGE");
    }

    @Test
    void mapsAllStandardFailureCategories() {
        McpToolSpecFactory factory = new McpToolSpecFactory(new ObjectMapper(), new McpToolProperties());

        assertErrorCode(factory, new IllegalArgumentException("limit 必须为正整数"),
                "INVALID_ARGUMENT", false);
        assertErrorCode(factory, new IllegalArgumentException("未找到数据源 missing"),
                "DATASOURCE_NOT_FOUND", false);
        assertErrorCode(factory, new SecurityException("数据源为只读模式"),
                "READ_ONLY", false);
        assertErrorCode(factory, new SecurityException("命中 SQL 黑名单"),
                "SECURITY_REJECTED", false);
        assertErrorCode(factory, new RuntimeException("connection refused"),
                "CONNECTION_FAILED", true);
        assertErrorCode(factory, new RuntimeException("unexpected adapter failure"),
                "EXECUTION_FAILED", false);
    }

    @SuppressWarnings("unchecked")
    private void assertErrorCode(McpToolSpecFactory factory, RuntimeException failure,
                                 String expectedCode, boolean retryable) {
        var spec = factory.create("test_error", "Test", "test",
                McpToolSpecFactory.inputSchema(Map.of()), McpToolSpecFactory.objectData(Map.of()), true,
                args -> { throw failure; });

        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("test_error", Map.of()));

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = (Map<String, Object>)
                ((Map<String, Object>) result.structuredContent()).get("error");
        assertThat(error).containsEntry("code", expectedCode).containsEntry("retryable", retryable);
        assertThat(((Map<String, Object>) result.structuredContent()).get("data"))
                .isEqualTo(Map.of());
    }
}
