package com.dameng.mcp.adapter;

import com.alibaba.druid.pool.DruidDataSource;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchRestClient;
import com.dameng.mcp.adapter.kafka.KafkaConnection;
import com.dameng.mcp.adapter.redis.RedisConnection;
import com.dameng.mcp.config.DataSourceProperties;
import com.dameng.mcp.model.DataSourceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数据源注册表。
 * <p>
 * 持有所有已配置数据源对应的 {@link DatabaseAdapter} 实例、底层 {@link DataSource}
 * 及其原始配置，按名称建立索引，供上层服务在运行时根据数据源名称路由请求。
 * 第一个注册的数据源被视为「默认数据源」。
 * </p>
 * <p>
 * http 模式下支持运行时动态增删数据源，因此对增删操作使用 {@code synchronized}
 * 保证线程安全，同时保留 {@link LinkedHashMap} 的插入顺序以确定默认数据源。
 * </p>
 */
@Slf4j
@Component
public class DataSourceRegistry {

    /**
     * 数据源名称 -> 适配器实例
     */
    private final Map<String, DatabaseAdapter> adapters = new LinkedHashMap<>();

    /**
     * 数据源名称 -> 底层数据源（用于删除时关闭连接池）
     */
    private final Map<String, DataSource> dataSources = new LinkedHashMap<>();

    /**
     * 数据源名称 -> 原始配置项
     */
    private final Map<String, DataSourceProperties.DataSourceItem> configs = new LinkedHashMap<>();

    /**
     * 数据源名称 -> Elasticsearch 客户端封装
     */
    private final Map<String, ElasticsearchRestClient> esClients = new LinkedHashMap<>();

    /**
     * 数据源名称 -> Redis 连接封装
     */
    private final Map<String, RedisConnection> redisClients = new LinkedHashMap<>();

    /**
     * 数据源名称 -> Kafka 连接封装
     */
    private final Map<String, KafkaConnection> kafkaClients = new LinkedHashMap<>();

    /**
     * 数据源名称 -> 资源关闭动作（注销时统一执行，屏蔽不同类型资源的关闭差异）
     */
    private final Map<String, Runnable> closers = new LinkedHashMap<>();

    /**
     * 注册一个数据源适配器。
     *
     * @param name       数据源名称
     * @param adapter    已构建好的 {@link DatabaseAdapter}
     * @param dataSource 底层数据源（Druid），用于删除时关闭
     * @param config     对应的原始配置项
     */
    public synchronized void register(String name, DatabaseAdapter adapter, DataSource dataSource,
                                      DataSourceProperties.DataSourceItem config) {
        adapters.put(name, adapter);
        dataSources.put(name, dataSource);
        configs.put(name, config);
        final DataSource ds = dataSource;
        closers.put(name, () -> {
            if (ds instanceof DruidDataSource) {
                try {
                    ((DruidDataSource) ds).close();
                } catch (Exception e) {
                    log.warn("关闭数据源 [{}] 连接池时出错: {}", name, e.getMessage());
                }
            }
        });
    }

    /**
     * 注册一个 Elasticsearch 数据源。
     */
    public synchronized void registerElasticsearch(String name, ElasticsearchRestClient client,
                                                    DataSourceProperties.DataSourceItem config) {
        esClients.put(name, client);
        configs.put(name, config);
        closers.put(name, client::close);
    }

    /**
     * 注册一个 Redis 数据源。
     */
    public synchronized void registerRedis(String name, RedisConnection connection,
                                           DataSourceProperties.DataSourceItem config) {
        redisClients.put(name, connection);
        configs.put(name, config);
        closers.put(name, connection::close);
    }

    /**
     * 注册一个 Kafka 数据源。
     */
    public synchronized void registerKafka(String name, KafkaConnection connection,
                                           DataSourceProperties.DataSourceItem config) {
        kafkaClients.put(name, connection);
        configs.put(name, config);
        closers.put(name, connection::close);
    }

    /**
     * 注销并移除一个数据源，同时关闭其底层连接池。
     *
     * @param name 数据源名称
     */
    public synchronized void unregister(String name) {
        adapters.remove(name);
        esClients.remove(name);
        redisClients.remove(name);
        kafkaClients.remove(name);
        configs.remove(name);
        dataSources.remove(name);
        Runnable closer = closers.remove(name);
        if (closer != null) {
            try {
                closer.run();
            } catch (Exception e) {
                log.warn("关闭数据源 [{}] 资源时出错: {}", name, e.getMessage());
            }
        }
    }

    /**
     * 按名称获取适配器；当 name 为空时返回默认适配器；找不到时抛出异常。
     */
    public DatabaseAdapter getAdapter(String name) {
        if (name == null || name.trim().isEmpty()) {
            return getDefaultAdapter();
        }
        DatabaseAdapter adapter = adapters.get(name);
        if (adapter == null) {
            throw new IllegalArgumentException("未找到数据源: " + name + "。可用数据源: " + adapters.keySet());
        }
        return adapter;
    }

    /**
     * 获取默认适配器（即第一个注册的关系型数据源）。
     */
    public DatabaseAdapter getDefaultAdapter() {
        if (adapters.isEmpty()) {
            throw new IllegalStateException("没有配置任何关系型数据源");
        }
        return adapters.values().iterator().next();
    }

    /**
     * 按名称获取 Elasticsearch 客户端；name 为空时返回第一个注册的 ES 数据源。
     */
    public ElasticsearchRestClient getElasticsearch(String name) {
        if (name == null || name.trim().isEmpty()) {
            if (esClients.isEmpty()) {
                throw new IllegalStateException("没有配置任何 Elasticsearch 数据源");
            }
            return esClients.values().iterator().next();
        }
        ElasticsearchRestClient client = esClients.get(name);
        if (client == null) {
            throw new IllegalArgumentException("未找到 Elasticsearch 数据源: " + name + "。可用: " + esClients.keySet());
        }
        return client;
    }

    /**
     * 按名称获取 Redis 连接；name 为空时返回第一个注册的 Redis 数据源。
     */
    public RedisConnection getRedis(String name) {
        if (name == null || name.trim().isEmpty()) {
            if (redisClients.isEmpty()) {
                throw new IllegalStateException("没有配置任何 Redis 数据源");
            }
            return redisClients.values().iterator().next();
        }
        RedisConnection connection = redisClients.get(name);
        if (connection == null) {
            throw new IllegalArgumentException("未找到 Redis 数据源: " + name + "。可用: " + redisClients.keySet());
        }
        return connection;
    }

    /**
     * 按名称获取 Kafka 连接；name 为空时返回第一个注册的 Kafka 数据源。
     */
    public KafkaConnection getKafka(String name) {
        if (name == null || name.trim().isEmpty()) {
            if (kafkaClients.isEmpty()) {
                throw new IllegalStateException("没有配置任何 Kafka 数据源");
            }
            return kafkaClients.values().iterator().next();
        }
        KafkaConnection connection = kafkaClients.get(name);
        if (connection == null) {
            throw new IllegalArgumentException("未找到 Kafka 数据源: " + name + "。可用: " + kafkaClients.keySet());
        }
        return connection;
    }

    /**
     * 获取默认（关系型）数据源名称，即第一个注册的关系型数据源名称。
     * <p>供关系型查询/写入工具在 datasource 为空时用于展示与只读判断，
     * 避免在混合注册 ES/Redis 时误取到非关系型数据源。</p>
     */
    public String getDefaultName() {
        if (adapters.isEmpty()) {
            return "";
        }
        return adapters.keySet().iterator().next();
    }

    /**
     * 列出所有已注册数据源的元信息。
     */
    public synchronized List<DataSourceInfo> listDataSources() {
        List<DataSourceInfo> list = new ArrayList<>(configs.size());
        for (Map.Entry<String, DataSourceProperties.DataSourceItem> entry : configs.entrySet()) {
            DataSourceInfo info = new DataSourceInfo();
            info.setName(entry.getKey());
            info.setDescription(entry.getValue().getDescription());
            info.setType(entry.getValue().getType());
            info.setReadonly(entry.getValue().isReadonly());
            info.setDynamic(entry.getValue().isDynamic());
            list.add(info);
        }
        return list;
    }

    /**
     * 列出所有动态（运行时添加）数据源的原始配置，用于持久化。
     */
    public synchronized List<DataSourceProperties.DataSourceItem> listDynamicConfigs() {
        List<DataSourceProperties.DataSourceItem> list = new ArrayList<>();
        for (DataSourceProperties.DataSourceItem item : configs.values()) {
            if (item.isDynamic()) {
                list.add(item);
            }
        }
        return list;
    }

    /**
     * 获取指定数据源的原始配置，不存在时返回 null。
     */
    public synchronized DataSourceProperties.DataSourceItem getConfig(String name) {
        return configs.get(name);
    }

    /**
     * 判断指定名称的数据源是否存在（任意类型）
     */
    public boolean exists(String name) {
        return configs.containsKey(name);
    }

    /**
     * 判断指定数据源是否为只读
     */
    public boolean isReadonly(String name) {
        DataSourceProperties.DataSourceItem config = configs.get(name);
        if (config == null) {
            // 名称为空时取默认
            if (name == null || name.trim().isEmpty()) {
                config = configs.values().iterator().next();
            } else {
                throw new IllegalArgumentException("未找到数据源: " + name);
            }
        }
        return config.isReadonly();
    }

    /**
     * 已注册数据源数量（含关系型 / ES / Redis）
     */
    public synchronized int size() {
        return configs.size();
    }
}
