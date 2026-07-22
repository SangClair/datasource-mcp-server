package com.dameng.mcp.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.dameng.mcp.adapter.DatabaseAdapterFactory;
import com.dameng.mcp.adapter.DataSourceRegistry;
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

    private static final Set<String> SUPPORTED_TYPES = Set.of("dameng", "oracle", "mysql");

    private final DatabaseAdapterFactory factory;
    private final DataSourceRegistry registry;
    private final DataSourcePersistence persistence;

    public DataSourceManager(DatabaseAdapterFactory factory,
                             DataSourceRegistry registry,
                             DataSourcePersistence persistence) {
        this.factory = factory;
        this.registry = registry;
        this.persistence = persistence;
    }

    /**
     * 启动阶段注册数据源（不触发持久化）。
     * 供 {@code DataSourceConfig} 加载 yml 内置及持久化的动态数据源使用。
     */
    public void registerStartup(DataSourceProperties.DataSourceItem item) {
        DatabaseAdapterFactory.Registration reg = factory.create(item);
        registry.register(item.getName(), reg.adapter(), reg.dataSource(), item);
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

        // 创建适配器（内部会 init 数据源并做连通性校验，失败抛异常）
        DatabaseAdapterFactory.Registration reg = factory.create(item);
        registry.register(item.getName(), reg.adapter(), reg.dataSource(), item);

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
     * 测试数据源连接（不注册、不持久化）。
     *
     * @param item 数据源配置
     */
    public void testConnection(DataSourceProperties.DataSourceItem item) {
        validate(item);
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
     * 校验数据源配置的必填字段与类型合法性。
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
        if (!SUPPORTED_TYPES.contains(item.getType().toLowerCase())) {
            throw new IllegalArgumentException("不支持的数据库类型: " + item.getType() + "。支持: dameng, oracle, mysql");
        }
        if (!StringUtils.hasText(item.getUrl())) {
            throw new IllegalArgumentException("连接 URL(url)不能为空");
        }
        if (!StringUtils.hasText(item.getUsername())) {
            throw new IllegalArgumentException("用户名(username)不能为空");
        }
    }
}
