package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Elasticsearch MCP 工具服务。
 * <p>
 * 提供索引列表、mapping 查看、文档计数、DSL 查询等只读能力，以及文档写入/删除等写能力。
 * 写操作受数据源 {@code readonly} 标记控制，只读数据源会在客户端层被硬拦截。
 * 通过 {@link DataSourceRegistry} 按名称路由到对应的 Elasticsearch 数据源。
 * </p>
 */
@Slf4j
@Service
public class ElasticsearchToolService {

    private static final int DEFAULT_SEARCH_SIZE = 10;
    private static final int LIMIT_SEARCH_SIZE = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceRegistry registry;

    public ElasticsearchToolService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @Tool(description = "列出 Elasticsearch 数据源中的所有索引，包含索引名、健康状态、文档数与存储大小。等价于 GET /_cat/indices。")
    public String esListIndices(
            @ToolParam(description = "Elasticsearch 数据源名称，通过 list_datasources 获取。为空则使用第一个 ES 数据源") String datasource) {
        try {
            ElasticsearchRestClient client = registry.getElasticsearch(datasource);
            JsonNode result = client.listIndices();
            if (result == null || !result.isArray() || result.isEmpty()) {
                return "Elasticsearch 数据源 [" + display(datasource) + "] 中未找到任何索引。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Elasticsearch 数据源 [").append(display(datasource)).append("] 索引列表，共 ")
                    .append(result.size()).append(" 个：\n\n");
            sb.append("| 索引 | 健康 | 状态 | 文档数 | 存储大小 |\n");
            sb.append("| ---- | ---- | ---- | ------ | -------- |\n");
            for (JsonNode idx : result) {
                sb.append("| ").append(text(idx, "index")).append(" | ")
                        .append(text(idx, "health")).append(" | ")
                        .append(text(idx, "status")).append(" | ")
                        .append(text(idx, "docs.count")).append(" | ")
                        .append(text(idx, "store.size")).append(" |\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("esListIndices 执行失败：datasource={}", datasource, e);
            return "查询 Elasticsearch 索引列表失败：" + safeMessage(e);
        }
    }

    @Tool(description = "获取指定 Elasticsearch 索引的 mapping（字段定义）。等价于 GET /{index}/_mapping，返回原始 JSON 便于理解字段类型。")
    public String esGetMapping(
            @ToolParam(description = "Elasticsearch 数据源名称，为空则使用第一个 ES 数据源") String datasource,
            @ToolParam(description = "索引名称") String index) {
        if (isBlank(index)) {
            return "参数错误：index 不能为空。";
        }
        try {
            ElasticsearchRestClient client = registry.getElasticsearch(datasource);
            JsonNode result = client.getMapping(index);
            return "索引 [" + index + "] 的 mapping：\n\n```json\n" + pretty(result) + "\n```";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("esGetMapping 执行失败：datasource={}, index={}", datasource, index, e);
            return "获取索引 [" + index + "] mapping 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "统计指定 Elasticsearch 索引的文档数量。可选传入 Query DSL（JSON 字符串）按条件统计，等价于 GET/POST /{index}/_count。")
    public String esCount(
            @ToolParam(description = "Elasticsearch 数据源名称，为空则使用第一个 ES 数据源") String datasource,
            @ToolParam(description = "索引名称") String index,
            @ToolParam(description = "可选的 Query DSL（JSON 字符串），如 {\"query\":{\"term\":{\"status\":\"ok\"}}}。为空则统计全部文档。") String query) {
        if (isBlank(index)) {
            return "参数错误：index 不能为空。";
        }
        try {
            ElasticsearchRestClient client = registry.getElasticsearch(datasource);
            JsonNode result = client.count(index, query);
            long count = result != null && result.has("count") ? result.get("count").asLong() : 0L;
            return "索引 [" + index + "] 文档数：" + count;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("esCount 执行失败：datasource={}, index={}", datasource, index, e);
            return "统计索引 [" + index + "] 文档数失败：" + safeMessage(e);
        }
    }

    @Tool(description = "在指定 Elasticsearch 索引上执行查询（Query DSL）。等价于 POST /{index}/_search，返回命中总数与文档内容。为空 DSL 时执行 match_all。")
    public String esSearch(
            @ToolParam(description = "Elasticsearch 数据源名称，为空则使用第一个 ES 数据源") String datasource,
            @ToolParam(description = "索引名称") String index,
            @ToolParam(description = "Query DSL（JSON 字符串），如 {\"query\":{\"match\":{\"title\":\"news\"}}}。为空则查询全部。") String dsl,
            @ToolParam(description = "返回文档条数，默认 10，最大 100。传入 0 或负数使用默认值。") int size) {
        if (isBlank(index)) {
            return "参数错误：index 不能为空。";
        }
        int effectiveSize = normalizeSize(size);
        try {
            ElasticsearchRestClient client = registry.getElasticsearch(datasource);
            JsonNode result = client.search(index, dsl, effectiveSize);
            return formatSearch(index, result, effectiveSize);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("esSearch 执行失败：datasource={}, index={}", datasource, index, e);
            return "查询索引 [" + index + "] 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "向指定 Elasticsearch 索引写入或更新文档。【警告】此操作会修改数据。id 为空时由 ES 自动生成。只读数据源将拒绝执行。")
    public String esIndexDocument(
            @ToolParam(description = "Elasticsearch 数据源名称，为空则使用第一个 ES 数据源") String datasource,
            @ToolParam(description = "索引名称") String index,
            @ToolParam(description = "文档 ID，为空则由 ES 自动生成") String id,
            @ToolParam(description = "文档内容（JSON 字符串），如 {\"title\":\"hello\",\"views\":1}") String document) {
        if (isBlank(index)) {
            return "参数错误：index 不能为空。";
        }
        if (isBlank(document)) {
            return "参数错误：document 不能为空。";
        }
        try {
            ElasticsearchRestClient client = registry.getElasticsearch(datasource);
            JsonNode result = client.indexDocument(index, id, document);
            return "文档写入成功：\n\n```json\n" + pretty(result) + "\n```";
        } catch (SecurityException se) {
            return "操作被拒绝：" + safeMessage(se);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("esIndexDocument 执行失败：datasource={}, index={}", datasource, index, e);
            return "写入文档到索引 [" + index + "] 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "从指定 Elasticsearch 索引删除文档。【警告】此操作不可恢复！等价于 DELETE /{index}/_doc/{id}。只读数据源将拒绝执行。")
    public String esDeleteDocument(
            @ToolParam(description = "Elasticsearch 数据源名称，为空则使用第一个 ES 数据源") String datasource,
            @ToolParam(description = "索引名称") String index,
            @ToolParam(description = "要删除的文档 ID") String id) {
        if (isBlank(index)) {
            return "参数错误：index 不能为空。";
        }
        if (isBlank(id)) {
            return "参数错误：id 不能为空。";
        }
        try {
            ElasticsearchRestClient client = registry.getElasticsearch(datasource);
            JsonNode result = client.deleteDocument(index, id);
            return "文档删除结果：\n\n```json\n" + pretty(result) + "\n```";
        } catch (SecurityException se) {
            return "操作被拒绝：" + safeMessage(se);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("esDeleteDocument 执行失败：datasource={}, index={}, id={}", datasource, index, id, e);
            return "从索引 [" + index + "] 删除文档 [" + id + "] 失败：" + safeMessage(e);
        }
    }

    /* ====================== 内部工具方法 ====================== */

    private String formatSearch(String index, JsonNode result, int size) {
        if (result == null) {
            return "查询完成，但未返回结果。";
        }
        JsonNode hitsNode = result.path("hits");
        JsonNode total = hitsNode.path("total");
        long totalValue = total.isObject() ? total.path("value").asLong() : total.asLong();

        StringBuilder sb = new StringBuilder();
        sb.append("索引 [").append(index).append("] 查询成功。\n");
        sb.append("- 命中总数：").append(totalValue).append("\n");
        sb.append("- 返回条数上限：").append(size).append("\n");
        if (result.has("took")) {
            sb.append("- 耗时：").append(result.get("took").asLong()).append("ms\n");
        }
        sb.append("\n");

        JsonNode docs = hitsNode.path("hits");
        if (!docs.isArray() || docs.isEmpty()) {
            sb.append("（无命中文档）");
            return sb.toString();
        }
        int i = 1;
        for (JsonNode hit : docs) {
            sb.append("#### 文档 ").append(i++).append("（_id=").append(text(hit, "_id")).append("）\n");
            sb.append("```json\n").append(pretty(hit.path("_source"))).append("\n```\n\n");
        }
        return sb.toString();
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_SEARCH_SIZE;
        }
        return Math.min(size, LIMIT_SEARCH_SIZE);
    }

    private String display(String datasource) {
        return isBlank(datasource) ? "default" : datasource;
    }

    /**
     * 从 JSON 节点读取字段文本，支持带点号的 _cat 列名（如 docs.count），
     * 先按整键取值，取不到再按层级路径解析。
     */
    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode direct = node.get(field);
        if (direct != null && !direct.isNull()) {
            return direct.asText();
        }
        JsonNode cur = node;
        for (String part : field.split("\\.")) {
            if (cur == null) {
                return "";
            }
            cur = cur.get(part);
        }
        return cur == null || cur.isNull() ? "" : cur.asText();
    }

    private String pretty(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "{}";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
