package com.dameng.mcp.adapter.elasticsearch;

import com.dameng.mcp.config.DataSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.Header;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 客户端工厂。
 * <p>
 * 根据数据源配置构建低级 {@link RestClient}，支持 basic 认证（username/password）
 * 与 API Key 认证，并封装为 {@link ElasticsearchRestClient}。节点地址取自
 * {@link DataSourceProperties.DataSourceItem#getUrl()}，多节点以逗号分隔。
 * </p>
 */
@Slf4j
@Component
public class ElasticsearchClientFactory {

    /**
     * 创建 ES 客户端封装。内部会做一次 ping 以校验连通性。
     */
    public ElasticsearchRestClient create(DataSourceProperties.DataSourceItem config) {
        RestClient restClient = buildRestClient(config);
        ElasticsearchRestClient client = new ElasticsearchRestClient(restClient, config.isReadonly());
        try {
            client.ping();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        log.info("Elasticsearch 数据源 [{}] 初始化成功: url={}", config.getName(), config.getUrl());
        return client;
    }

    /**
     * 仅测试连接（不复用），调用方无需关闭：本方法内部创建并关闭客户端。
     */
    public void testConnection(DataSourceProperties.DataSourceItem config) {
        RestClient restClient = buildRestClient(config);
        ElasticsearchRestClient client = new ElasticsearchRestClient(restClient, config.isReadonly());
        try {
            client.ping();
        } finally {
            client.close();
        }
    }

    /**
     * 依据配置构建低级 RestClient。
     */
    private RestClient buildRestClient(DataSourceProperties.DataSourceItem config) {
        HttpHost[] hosts = parseHosts(config.getUrl());
        RestClientBuilder builder = RestClient.builder(hosts);

        boolean hasBasicAuth = StringUtils.hasText(config.getUsername());
        boolean hasApiKey = StringUtils.hasText(config.getApiKey());

        if (hasApiKey) {
            Header[] headers = {new BasicHeader("Authorization", "ApiKey " + config.getApiKey())};
            builder.setDefaultHeaders(headers);
        }
        if (hasBasicAuth) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(config.getUsername(),
                            config.getPassword() == null ? "" : config.getPassword()));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }
        return builder.build();
    }

    /**
     * 解析节点地址：支持逗号分隔的多节点，如 {@code http://a:9200,https://b:9200}。
     */
    private HttpHost[] parseHosts(String url) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("Elasticsearch 连接地址(url)不能为空");
        }
        List<HttpHost> hosts = new ArrayList<>();
        for (String node : url.split(",")) {
            String trimmed = node.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                URI uri = URI.create(trimmed);
                String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
                String host = uri.getHost();
                int port = uri.getPort() < 0 ? 9200 : uri.getPort();
                if (host == null) {
                    throw new IllegalArgumentException("无法解析 Elasticsearch 节点地址: " + trimmed);
                }
                hosts.add(new HttpHost(host, port, scheme));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("非法的 Elasticsearch 节点地址: " + trimmed, e);
            }
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("未解析到有效的 Elasticsearch 节点地址: " + url);
        }
        return hosts.toArray(new HttpHost[0]);
    }
}
