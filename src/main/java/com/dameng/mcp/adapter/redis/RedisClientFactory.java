package com.dameng.mcp.adapter.redis;

import com.dameng.mcp.config.DataSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;
import java.time.Duration;

/**
 * Redis 客户端工厂。
 * <p>
 * 根据数据源配置构建 {@link JedisPool} 并封装为 {@link RedisConnection}。
 * 支持 host/port/password/database 显式配置，或通过 {@code url}（redis://）形式配置。
 * </p>
 */
@Slf4j
@Component
public class RedisClientFactory {

    private static final int DEFAULT_PORT = 6379;
    private static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    /**
     * 创建 Redis 连接封装。内部会做一次 PING 以校验连通性。
     */
    public RedisConnection create(DataSourceProperties.DataSourceItem config) {
        JedisPool pool = buildPool(config);
        RedisConnection connection = new RedisConnection(pool, config.isReadonly());
        try {
            connection.ping();
        } catch (RuntimeException e) {
            connection.close();
            throw new RuntimeException("Redis 连接失败: " + e.getMessage(), e);
        }
        log.info("Redis 数据源 [{}] 初始化成功: host={}, port={}, db={}",
                config.getName(), resolveHost(config), resolvePort(config), resolveDatabase(config));
        return connection;
    }

    /**
     * 仅测试连接（不复用），本方法内部创建并关闭连接。
     */
    public void testConnection(DataSourceProperties.DataSourceItem config) {
        JedisPool pool = buildPool(config);
        RedisConnection connection = new RedisConnection(pool, config.isReadonly());
        try {
            connection.ping();
        } finally {
            connection.close();
        }
    }

    /**
     * 依据配置构建 JedisPool。
     */
    private JedisPool buildPool(DataSourceProperties.DataSourceItem config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getMaxActive() > 0 ? config.getMaxActive() : 20);
        poolConfig.setMaxIdle(config.getMinIdle() > 0 ? config.getMinIdle() : 5);
        poolConfig.setMinIdle(0);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setMaxWait(Duration.ofMillis(config.getMaxWait() > 0 ? config.getMaxWait() : DEFAULT_TIMEOUT_MILLIS));

        String host = resolveHost(config);
        int port = resolvePort(config);
        int database = resolveDatabase(config);
        String password = StringUtils.hasText(config.getPassword()) ? config.getPassword() : null;
        String user = StringUtils.hasText(config.getUsername()) ? config.getUsername() : null;

        if (user != null) {
            return new JedisPool(poolConfig, host, port, DEFAULT_TIMEOUT_MILLIS, user, password, database);
        }
        return new JedisPool(poolConfig, host, port, DEFAULT_TIMEOUT_MILLIS, password, database);
    }

    /**
     * 解析主机：优先取 host，其次从 url（redis://host:port）解析。
     */
    private String resolveHost(DataSourceProperties.DataSourceItem config) {
        if (StringUtils.hasText(config.getHost())) {
            return config.getHost().trim();
        }
        URI uri = parseUri(config.getUrl());
        if (uri != null && uri.getHost() != null) {
            return uri.getHost();
        }
        throw new IllegalArgumentException("Redis 主机地址(host)不能为空");
    }

    /**
     * 解析端口：优先取 port，其次从 url 解析，最后回退默认端口。
     */
    private int resolvePort(DataSourceProperties.DataSourceItem config) {
        if (config.getPort() != null && config.getPort() > 0) {
            return config.getPort();
        }
        URI uri = parseUri(config.getUrl());
        if (uri != null && uri.getPort() > 0) {
            return uri.getPort();
        }
        return DEFAULT_PORT;
    }

    /**
     * 解析数据库索引：优先取 database 字段，其次从 url 路径解析，最后回退 0。
     */
    private int resolveDatabase(DataSourceProperties.DataSourceItem config) {
        if (config.getDatabase() != null && config.getDatabase() >= 0) {
            return config.getDatabase();
        }
        URI uri = parseUri(config.getUrl());
        if (uri != null && uri.getPath() != null && uri.getPath().length() > 1) {
            try {
                return Integer.parseInt(uri.getPath().substring(1));
            } catch (NumberFormatException ignore) {
                // 忽略非法路径，回退默认
            }
        }
        return 0;
    }

    private URI parseUri(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
