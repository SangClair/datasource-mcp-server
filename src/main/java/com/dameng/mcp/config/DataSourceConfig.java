package com.dameng.mcp.config;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.service.DataSourceManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 多数据源初始化配置。
 * <p>
 * 应用启动时依次注册两类数据源：
 * <ol>
 *     <li>{@link DataSourceProperties#getDatasources()} 中的 application.yml 内置数据源；</li>
 *     <li>{@link DataSourcePersistence#load()} 加载的、运行时通过 Web 持久化的动态数据源。</li>
 * </ol>
 * 均通过 {@link DataSourceManager} 创建适配器并注册到 {@link DataSourceRegistry}。
 * </p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {

    private final DataSourceProperties properties;
    private final DataSourceManager manager;
    private final DataSourceRegistry registry;
    private final DataSourcePersistence persistence;
    private final Environment environment;

    public DataSourceConfig(DataSourceProperties properties,
                            DataSourceManager manager,
                            DataSourceRegistry registry,
                            DataSourcePersistence persistence,
                            Environment environment) {
        this.properties = properties;
        this.manager = manager;
        this.registry = registry;
        this.persistence = persistence;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        // 1. 注册 application.yml 内置数据源
        if (properties.getDatasources() != null) {
            for (DataSourceProperties.DataSourceItem item : properties.getDatasources()) {
                if (item.getName() == null || item.getName().trim().isEmpty()) {
                    throw new IllegalStateException("数据源配置缺少 name 属性");
                }
                if (item.getType() == null || item.getType().trim().isEmpty()) {
                    throw new IllegalStateException("数据源 [" + item.getName() + "] 缺少 type 属性");
                }
                item.setDynamic(false);
                manager.registerStartup(item);
                log.info("注册内置数据源: name={}, type={}, description={}",
                        item.getName(), item.getType(), item.getDescription());
            }
        }

        // 2. 注册持久化的动态数据源（名称与内置冲突时以内置为准并跳过）
        List<DataSourceProperties.DataSourceItem> dynamicItems = persistence.load();
        for (DataSourceProperties.DataSourceItem item : dynamicItems) {
            if (item.getName() == null || item.getName().trim().isEmpty()) {
                continue;
            }
            if (registry.exists(item.getName())) {
                log.warn("动态数据源 [{}] 与内置数据源名称冲突，已跳过", item.getName());
                continue;
            }
            try {
                item.setDynamic(true);
                manager.registerStartup(item);
                log.info("恢复动态数据源: name={}, type={}", item.getName(), item.getType());
            } catch (Exception e) {
                manager.recordUnavailableDynamic(item, e);
                log.error("恢复动态数据源 [{}] 失败，已标记为恢复失败: {}", item.getName(), e.getMessage());
            }
        }

        // 3. 空校验：http 模式允许零数据源（可后续通过 Web 添加），stdio 模式必须至少一个
        if (registry.size() == 0) {
            if (isHttpMode()) {
                log.warn("当前未配置任何数据源，可通过 Web 管理页面(http 模式)动态添加。");
            } else {
                throw new IllegalStateException("未配置任何数据源，请在 application.yml 中配置 mcp.datasources");
            }
        }

        log.info("数据源初始化完成，共注册 {} 个数据源", registry.size());
    }

    /**
     * 判断当前是否为 http 运行模式。
     */
    private boolean isHttpMode() {
        for (String profile : environment.getActiveProfiles()) {
            if ("http".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
