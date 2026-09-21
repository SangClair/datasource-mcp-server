package com.dameng.mcp.config;

import com.dameng.mcp.service.DatabaseMetadataService;
import com.dameng.mcp.service.DatabaseQueryService;
import com.dameng.mcp.service.DatabaseStatisticsService;
import com.dameng.mcp.service.DatabaseWriteService;
import com.dameng.mcp.service.ElasticsearchToolService;
import com.dameng.mcp.service.KafkaToolService;
import com.dameng.mcp.service.RedisToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class McpToolConfigTest {

    @Test
    void rejectsNonPositivePageLimits() {
        McpToolProperties properties = new McpToolProperties();

        assertThatThrownBy(() -> properties.normalizeLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void registersOnlyTheTwentyEightV3ToolsWithSchemasAndAnnotations() {
        McpToolConfig config = new McpToolConfig();
        McpToolProperties properties = new McpToolProperties();
        ObjectMapper mapper = new ObjectMapper();
        McpToolSpecFactory factory = new McpToolSpecFactory(mapper, properties);

        List<McpServerFeatures.SyncToolSpecification> tools = config.databaseTools(
                factory, properties, mapper,
                mock(DatabaseMetadataService.class), mock(DatabaseQueryService.class),
                mock(DatabaseStatisticsService.class), mock(DatabaseWriteService.class),
                mock(ElasticsearchToolService.class), mock(RedisToolService.class),
                mock(KafkaToolService.class));

        Set<String> names = tools.stream().map(tool -> tool.tool().name()).collect(Collectors.toSet());
        assertThat(tools).hasSize(28);
        assertThat(names).containsExactlyInAnyOrder(
                "datasource_list", "db_list_schemas", "db_list_tables", "db_describe_table",
                "db_query", "db_sample_rows", "db_table_statistics", "db_column_statistics",
                "db_insert", "db_update", "db_delete", "db_execute_sql",
                "es_list_indices", "es_get_mapping", "es_count_documents", "es_search",
                "es_index_document", "es_delete_document", "redis_scan_keys", "redis_get_key_info",
                "redis_get_value", "redis_set_value", "redis_delete_key", "redis_set_expiry",
                "kafka_list_topics", "kafka_describe_topic", "kafka_list_consumer_groups",
                "kafka_peek_messages");
        assertThat(names).doesNotContain("executeUpdate", "execute_update", "redisGetKey");
        assertThat(tools).allSatisfy(spec -> {
            assertThat(spec.tool().inputSchema()).isNotNull();
            assertThat(spec.tool().outputSchema()).isNotNull();
            assertThat(spec.tool().annotations()).isNotNull();
            assertThat(spec.tool().annotations().openWorldHint()).isTrue();
        });
        assertThat(tool(tools, "db_query").tool().annotations().readOnlyHint()).isTrue();
        assertThat(tool(tools, "db_query").tool().annotations().destructiveHint()).isFalse();
        assertThat(tool(tools, "db_delete").tool().annotations().readOnlyHint()).isFalse();
        assertThat(tool(tools, "db_delete").tool().annotations().destructiveHint()).isTrue();
        assertDataProperty(tool(tools, "datasource_list"), "items");
        assertDataProperty(tool(tools, "db_query"), "rows");
        assertDataProperty(tool(tools, "db_update"), "affected_rows");
        assertDataProperty(tool(tools, "es_search"), "hits");
        assertDataProperty(tool(tools, "redis_get_value"), "value");
        assertDataProperty(tool(tools, "kafka_peek_messages"), "messages");
    }

    @SuppressWarnings("unchecked")
    private void assertDataProperty(McpServerFeatures.SyncToolSpecification spec, String property) {
        Map<String, Object> outputProperties =
                (Map<String, Object>) spec.tool().outputSchema().get("properties");
        Map<String, Object> data = (Map<String, Object>) outputProperties.get("data");
        List<Map<String, Object>> dataVariants = (List<Map<String, Object>>) data.get("anyOf");
        data = dataVariants.get(0);
        Map<String, Object> dataProperties = (Map<String, Object>) data.get("properties");
        assertThat(dataProperties).containsKey(property);
    }

    private McpServerFeatures.SyncToolSpecification tool(
            List<McpServerFeatures.SyncToolSpecification> tools, String name) {
        return tools.stream().filter(spec -> name.equals(spec.tool().name())).findFirst().orElseThrow();
    }
}
