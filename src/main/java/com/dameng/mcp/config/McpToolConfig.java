package com.dameng.mcp.config;

import com.dameng.mcp.service.DatabaseMetadataService;
import com.dameng.mcp.service.DatabaseQueryService;
import com.dameng.mcp.service.DatabaseStatisticsService;
import com.dameng.mcp.service.DatabaseWriteService;
import com.dameng.mcp.service.ElasticsearchToolService;
import com.dameng.mcp.service.RedisToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置类
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider databaseTools(
            DatabaseMetadataService metadataService,
            DatabaseQueryService queryService,
            DatabaseStatisticsService statisticsService,
            DatabaseWriteService writeService,
            ElasticsearchToolService elasticsearchService,
            RedisToolService redisService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(metadataService, queryService, statisticsService, writeService,
                        elasticsearchService, redisService)
                .build();
    }
}
