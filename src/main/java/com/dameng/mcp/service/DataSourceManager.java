package com.dameng.mcp.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.dameng.mcp.adapter.DatabaseAdapterFactory;
import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchClientFactory;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchRestClient;
import com.dameng.mcp.adapter.redis.RedisClientFactory;
import com.dameng.mcp.adapter.redis.RedisConnection;
import com.dameng.mcp.config.DataSourcePersistence;
import com.dameng.mcp.config.DataSourceProperties;
import com.dameng.mcp.model.DataSourceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

/**
 * 数据源管理服务。
 * <p>
 * 统一编排数据源的注册（启动加载）、运行时动态新增/删除、连接测试与持久化，
 * 是 Web 管理接口与启动初始化共用的入口。所有会修改注册表的操作均加锁保证线程安全。
 * </p>
 */
@Slf4j
@Service
public class DataSourceManager {

    private static final Set<String> SUPPORTED_TYPES = Set.of("dameng", "oracle", "mysql", "elasticsearch", "redis");

    private final DatabaseAdapterFactory factory;
    private final ElasticsearchClientFactory esFactory;
    private final RedisClientFactory redisFactory;
    private final DataSourceRegistry registry;
    private final DataSourcePersistence persistence;

    public DataSourceManager(DatabaseAdapterFactory factory,
                             ElasticsearchClientFactory esFactory,
                             RedisClientFactory redisFactory,
                             DataSourceRegistry registry,
                             DataSourcePersistence persistence) {
        this.factory = factory;
        this.esFactory = esFactory;
        this.redisFactory = redisFactory;
        this.registry = registry;
        this.persistence = persistence;
    }

    /**
     * 启动阶段注册数据源（不触发持久化）。
     * 供 {@code DataSourceConfig} 加载 yml 内置及持久化的动态数据源使用。
     */
    public void registerStartup(DataSourceProperties.DataSourceItem item) {
        String type = item.getType() == null ? "" : item.getType().toLowerCase();
        switch (type) {
            case "elasticsearch": {
                ElasticsearchRestClient client = esFactory.create(item);
                registry.registerElasticsearch(item.getName(), client, item);
                break;
            }
            case "redis": {
                RedisConnection connection = redisFactory.create(item);
                registry.registerRedis(item.getName(), connection, item);
                break;
            }
            default: {
                DatabaseAdapterFactory.Registration reg = factory.create(item);
                registry.register(item.getName(), reg.adapter(), reg.dataSource(), item);
            }
        }
    }

    /**
     * 列出所有已注册数据源的元信息。
     */
    public List<DataSourceInfo> list() {
        return registry.listDataSources();
    }

    /**
     * 运行时动态新增数据源：校验 -> 去重 -> 建连测试 -> 注册 -> 持久化。
     *
     * @param item 数据源配置
     */
    public synchronized void add(DataSourceProperties.DataSourceItem item) {
        validate(item);
        if (registry.exists(item.getName())) {
            throw new IllegalArgumentException("数据源名称已存在: " + item.getName());
        }
        // 标记为动态数据源
        item.setDynamic(true);

        // 根据类型创建并注册（内部会做连通性校验，失败抛异常）
        registerStartup(item);

        // 持久化全部动态数据源
        persistence.saveAll(registry.listDynamicConfigs());
        log.info("动态新增数据源成功: name={}, type={}", item.getName(), item.getType());
    }

    /**
     * 运行时删除动态数据源：仅允许删除动态添加的数据源。
     *
     * @param name 数据源名称
     */
    public synchronized void remove(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        DataSourceProperties.DataSourceItem config = registry.getConfig(name);
        if (config == null) {
            throw new IllegalArgumentException("未找到数据源: " + name);
        }
        if (!config.isDynamic()) {
            throw new IllegalArgumentException("数据源 [" + name + "] 为内置(application.yml)数据源，不允许通过 Web 删除");
        }
        registry.unregister(name);
        persistence.saveAll(registry.listDynamicConfigs());
        log.info("删除动态数据源成功: name={}", name);
    }

    /**
     * 运行时修改动态数据源：仅允许修改动态添加的数据源，名称不可变（作为标识）。
     * <p>
     * 采用「先建连、后替换」策略：先用新配置建立并校验连接，成功后再注销旧实例并注册新实例，
     * 从而在新配置不可用时保留原有运行中的数据源不受影响。
     * 若提交的 password / apiKey 为空，则沿用原配置中的对应值（支持前端不回显密钥的编辑场景）。
     * </p>
     *
     * @param item 新的数据源配置（name 必须与已存在的动态数据源一致）
     */
    public synchronized void update(DataSourceProperties.DataSourceItem item) {
        validate(item);
        DataSourceProperties.DataSourceItem existing = registry.getConfig(item.getName());
        if (existing == null) {
            throw new IllegalArgumentException("未找到数据源: " + item.getName());
        }
        if (!existing.isDynamic()) {
            throw new IllegalArgumentException("数据源 [" + item.getName() + "] 为内置(application.yml)数据源，不允许通过 Web 修改");
        }
        // 密钥类字段留空时沿用原值（前端出于安全不回显）
        if (!StringUtils.hasText(item.getPassword())) {
            item.setPassword(existing.getPassword());
        }
        if (!StringUtils.hasText(item.getApiKey())) {
            item.setApiKey(existing.getApiKey());
        }
        item.setDynamic(true);

        String name = item.getName();
        String type = item.getType().toLowerCase();
        // 先用新配置建立资源（内部会校验连通性，失败抛异常，此时旧实例仍在），成功后再原子替换
        switch (type) {
            case "elasticsearch": {
                ElasticsearchRestClient client = esFactory.create(item);
                registry.unregister(name);
                registry.registerElasticsearch(name, client, item);
                break;
            }
            case "redis": {
                RedisConnection connection = redisFactory.create(item);
                registry.unregister(name);
                registry.registerRedis(name, connection, item);
                break;
            }
            default: {
                DatabaseAdapterFactory.Registration reg = factory.create(item);
                registry.unregister(name);
                registry.register(name, reg.adapter(), reg.dataSource(), item);
            }
        }
        persistence.saveAll(registry.listDynamicConfigs());
        log.info("修改动态数据源成功: name={}, type={}", name, item.getType());
    }

    /**
     * 获取用于编辑回显的数据源配置副本（脱敏：password/apiKey 置空，避免密钥回传浏览器）。
     *
     * @param name 数据源名称
     * @return 脱敏后的配置副本
     */
    public DataSourceProperties.DataSourceItem getForEdit(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        DataSourceProperties.DataSourceItem cfg = registry.getConfig(name);
        if (cfg == null) {
            throw new IllegalArgumentException("未找到数据源: " + name);
        }
        DataSourceProperties.DataSourceItem copy = new DataSourceProperties.DataSourceItem();
        copy.setName(cfg.getName());
        copy.setDescription(cfg.getDescription());
        copy.setType(cfg.getType());
        copy.setUrl(cfg.getUrl());
        copy.setUsername(cfg.getUsername());
        copy.setReadonly(cfg.isReadonly());
        copy.setDynamic(cfg.isDynamic());
        copy.setInitialSize(cfg.getInitialSize());
        copy.setMinIdle(cfg.getMinIdle());
        copy.setMaxActive(cfg.getMaxActive());
        copy.setMaxWait(cfg.getMaxWait());
        copy.setHost(cfg.getHost());
        copy.setPort(cfg.getPort());
        copy.setDatabase(cfg.getDatabase());
        // 脱敏：不回传密钥
        copy.setPassword("");
        copy.setApiKey("");
        return copy;
    }

    /**
     * 测试数据源连接（不注册、不持久化）。
     *
     * @param item 数据源配置
     */
    public void testConnection(DataSourceProperties.DataSourceItem item) {
        validate(item);
        String type = item.getType().toLowerCase();
        if ("elasticsearch".equals(type)) {
            esFactory.testConnection(item);
            return;
        }
        if ("redis".equals(type)) {
            redisFactory.testConnection(item);
            return;
        }
        DataSource ds = null;
        try {
            // createDataSource 内部执行 init，会真正建立连接并校验
            ds = factory.createDataSource(item);
        } finally {
            if (ds instanceof DruidDataSource) {
                try {
                    ((DruidDataSource) ds).close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /**
     * 校验数据源配置的必填字段与类型合法性（按类型区分必填项）。
     */
    private void validate(DataSourceProperties.DataSourceItem item) {
        if (item == null) {
            throw new IllegalArgumentException("数据源配置不能为空");
        }
        if (!StringUtils.hasText(item.getName())) {
            throw new IllegalArgumentException("数据源名称(name)不能为空");
        }
        if (!StringUtils.hasText(item.getType())) {
            throw new IllegalArgumentException("数据库类型(type)不能为空");
        }
        String type = item.getType().toLowerCase();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的数据源类型: " + item.getType()
                    + "。支持: dameng, oracle, mysql, elasticsearch, redis");
        }
        if ("elasticsearch".equals(type)) {
            if (!StringUtils.hasText(item.getUrl())) {
                throw new IllegalArgumentException("Elasticsearch 连接地址(url)不能为空，如 http://host:9200");
            }
            return;
        }
        if ("redis".equals(type)) {
            if (!StringUtils.hasText(item.getHost()) && !StringUtils.hasText(item.getUrl())) {
                throw new IllegalArgumentException("Redis 主机(host)不能为空（或使用 url: redis://host:port）");
            }
            return;
        }
        // 关系型数据源
        if (!StringUtils.hasText(item.getUrl())) {
            throw new IllegalArgumentException("连接 URL(url)不能为空");
        }
        if (!StringUtils.hasText(item.getUsername())) {
            throw new IllegalArgumentException("用户名(username)不能为空");
        }
    }
}
