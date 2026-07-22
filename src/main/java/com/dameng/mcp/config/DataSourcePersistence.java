package com.dameng.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态数据源持久化组件。
 * <p>
 * 将运行时通过 Web 接口新增的数据源配置以 JSON 文件形式持久化到本地，
 * 应用重启时自动加载并重新注册，保证配置不丢失。
 * 文件路径由 {@code mcp.dynamic-datasource-file} 配置，默认位于
 * {@code ${user.home}/.dameng-mcp/datasources.json}。
 * </p>
 */
@Slf4j
@Component
public class DataSourcePersistence {

    private final ObjectMapper objectMapper;
    private final Path filePath;

    public DataSourcePersistence(DataSourceProperties properties) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String configured = properties.getDynamicDatasourceFile();
        if (StringUtils.hasText(configured)) {
            this.filePath = Paths.get(configured);
        } else {
            this.filePath = Paths.get(System.getProperty("user.home"), ".dameng-mcp", "datasources.json");
        }
    }

    /**
     * 加载已持久化的动态数据源配置。文件不存在时返回空列表。
     */
    public synchronized List<DataSourceProperties.DataSourceItem> load() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            if (bytes.length == 0) {
                return new ArrayList<>();
            }
            List<DataSourceProperties.DataSourceItem> items = objectMapper.readValue(
                    bytes, new TypeReference<List<DataSourceProperties.DataSourceItem>>() {
                    });
            // 从文件加载的数据源统一标记为动态
            for (DataSourceProperties.DataSourceItem item : items) {
                item.setDynamic(true);
            }
            return items;
        } catch (IOException e) {
            log.error("加载动态数据源配置失败: {}", filePath, e);
            return new ArrayList<>();
        }
    }

    /**
     * 全量保存动态数据源配置到 JSON 文件。
     */
    public synchronized void saveAll(List<DataSourceProperties.DataSourceItem> items) {
        try {
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            byte[] bytes = objectMapper.writeValueAsBytes(items == null ? new ArrayList<>() : items);
            Files.write(filePath, bytes);
            log.info("已持久化 {} 个动态数据源到 {}", items == null ? 0 : items.size(), filePath);
        } catch (IOException e) {
            log.error("持久化动态数据源配置失败: {}", filePath, e);
            throw new RuntimeException("持久化数据源配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 返回持久化文件路径（用于日志/展示）。
     */
    public Path getFilePath() {
        return filePath;
    }
}
