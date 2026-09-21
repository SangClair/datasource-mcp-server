package com.dameng.mcp.adapter.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Elasticsearch 低级 REST 客户端封装。
 * <p>
 * 直接对 ES 稳定的 REST 接口（{@code _cat}/{@code _mapping}/{@code _search}/{@code _count}/{@code _doc}）
 * 发起 HTTP 请求并返回原始 JSON（{@link JsonNode}），因此不与 ES 具体版本强耦合，
 * 同一实现即可兼容 7.x 与 8.x 集群。所有写操作在 {@code readonly} 数据源上会直接抛出
 * {@link SecurityException} 作为客户端层硬拦截。
 * </p>
 */
@Slf4j
public class ElasticsearchRestClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final boolean readonly;

    public ElasticsearchRestClient(RestClient restClient, boolean readonly) {
        this.restClient = restClient;
        this.readonly = readonly;
    }

    /**
     * 连通性探测：GET / 。失败抛出异常。
     */
    public JsonNode ping() {
        return performRequest("GET", "/", null);
    }

    /**
     * 列出所有索引：GET /_cat/indices?format=json 。
     */
    public JsonNode listIndices() {
        return performRequest("GET", "/_cat/indices?format=json&s=index", null);
    }

    /**
     * 获取索引 mapping：GET /{index}/_mapping 。
     */
    public JsonNode getMapping(String index) {
        return performRequest("GET", "/" + encodePathSegment(index) + "/_mapping", null);
    }

    /**
     * 统计文档数：GET/POST /{index}/_count 。jsonQuery 为可选查询体。
     */
    public JsonNode count(String index, String jsonQuery) {
        String endpoint = "/" + encodePathSegment(index) + "/_count";
        if (jsonQuery == null || jsonQuery.trim().isEmpty()) {
            return performRequest("GET", endpoint, null);
        }
        return performRequest("POST", endpoint, jsonQuery);
    }

    /**
     * 执行查询：POST /{index}/_search 。jsonDsl 为可选查询体，size 为返回条数上限。
     */
    public JsonNode search(String index, String jsonDsl, int size) {
        return search(index, jsonDsl, 0, size);
    }

    public JsonNode search(String index, String jsonDsl, int from, int size) {
        StringBuilder endpoint = new StringBuilder("/").append(encodePathSegment(index)).append("/_search");
        if (size > 0) {
            endpoint.append("?from=").append(Math.max(from, 0)).append("&size=").append(size);
        }
        String body = (jsonDsl == null || jsonDsl.trim().isEmpty())
                ? "{\"query\":{\"match_all\":{}}}" : jsonDsl;
        return performRequest("POST", endpoint.toString(), body);
    }

    /**
     * 写入/更新文档。id 为空时由 ES 自动生成（POST /{index}/_doc）。
     */
    public JsonNode indexDocument(String index, String id, String jsonDoc) {
        ensureWritable();
        if (id == null || id.trim().isEmpty()) {
            return performRequest("POST", "/" + encodePathSegment(index) + "/_doc", jsonDoc);
        }
        return performRequest("PUT", "/" + encodePathSegment(index) + "/_doc/" + encodePathSegment(id), jsonDoc);
    }

    /**
     * 删除文档：DELETE /{index}/_doc/{id} 。
     */
    public JsonNode deleteDocument(String index, String id) {
        ensureWritable();
        return performRequest("DELETE", "/" + encodePathSegment(index) + "/_doc/" + encodePathSegment(id), null);
    }

    /**
     * 通用请求执行：发起 HTTP 请求并将响应体解析为 JsonNode。
     */
    private JsonNode performRequest(String method, String endpoint, String jsonBody) {
        Request request = new Request(method, endpoint);
        if (jsonBody != null && !jsonBody.trim().isEmpty()) {
            request.setJsonEntity(jsonBody);
        }
        try {
            Response response = restClient.performRequest(request);
            try (InputStream is = response.getEntity() == null ? null : response.getEntity().getContent()) {
                if (is == null) {
                    return MAPPER.createObjectNode();
                }
                byte[] bytes = is.readAllBytes();
                if (bytes.length == 0) {
                    return MAPPER.createObjectNode();
                }
                return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.error("Elasticsearch 请求失败：{} {}", method, endpoint, e);
            throw new RuntimeException("Elasticsearch 请求失败：" + e.getMessage(), e);
        }
    }

    /**
     * 只读硬拦截：只读数据源禁止任何写操作。
     */
    private void ensureWritable() {
        if (readonly) {
            throw new SecurityException("当前 Elasticsearch 数据源为只读模式，禁止执行任何写操作");
        }
    }

    /**
     * 对路径片段做最小化转义，避免索引名/文档 id 中的空格等字符破坏 URL。
     */
    private String encodePathSegment(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.trim().replace(" ", "%20");
    }

    @Override
    public void close() {
        try {
            restClient.close();
        } catch (IOException e) {
            log.warn("关闭 Elasticsearch RestClient 失败: {}", e.getMessage());
        }
    }
}
