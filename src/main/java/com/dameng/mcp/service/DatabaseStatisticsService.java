package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.model.ColumnStatistics;
import com.dameng.mcp.model.TableStatistics;
import com.dameng.mcp.util.ConnectionError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 数据库统计信息 MCP 工具服务。
 * <p>
 * 提供表级与列级的统计指标查询能力，结果以可读字符串返回供 LLM 分析。
 * 通过 {@link DataSourceRegistry} 实现多数据源路由。
 * </p>
 */
@Slf4j
@Service
public class DatabaseStatisticsService {

    private final DataSourceRegistry registry;

    public DatabaseStatisticsService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @Tool(description = "获取指定表的统计概览信息，包括总行数等基本指标。用于了解表的数据规模，辅助判断查询性能或数据完整性。")
    public String getTableStatistics(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "数据库模式名称，如 DMHR、SYSDBA") String schema,
            @ToolParam(description = "表名称") String table) {
        if (schema == null || schema.trim().isEmpty()) {
            return "参数错误：schema 不能为空。";
        }
        if (table == null || table.trim().isEmpty()) {
            return "参数错误：table 不能为空。";
        }
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            TableStatistics stats = adapter.getTableStatistics(schema, table);
            if (stats == null) {
                return "未获取到表 [" + schema + "." + table + "] 的统计信息。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("数据源：").append(displayDatasource(datasource)).append("\n");
            sb.append("表统计信息：").append(schema).append(".").append(table).append("\n\n");
            sb.append("| 指标 | 值 |\n");
            sb.append("| ---- | -- |\n");
            sb.append("| 模式名 | ").append(safeText(stats.getSchemaName())).append(" |\n");
            sb.append("| 表名 | ").append(safeText(stats.getTableName())).append(" |\n");
            sb.append("| 行数 | ").append(stats.getRowCount()).append(" |\n");
            if (stats.getTableSize() != null && !stats.getTableSize().isEmpty()) {
                sb.append("| 表大小 | ").append(safeText(stats.getTableSize())).append(" |\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (Exception e) {
            log.error("getTableStatistics 执行失败：datasource={}, schema={}, table={}", datasource, schema, table, e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return "查询表 [" + schema + "." + table + "] 统计信息失败：" + safeMessage(e);
        }
    }

    @Tool(description = "获取指定列的统计信息，包括去重数(distinct count)、空值数(null count)、最小值和最大值，用于数据分布分析与数据质量评估。")
    public String getColumnStatistics(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "数据库模式名称，如 DMHR、SYSDBA") String schema,
            @ToolParam(description = "表名称") String table,
            @ToolParam(description = "列名称") String column) {
        if (schema == null || schema.trim().isEmpty()) {
            return "参数错误：schema 不能为空。";
        }
        if (table == null || table.trim().isEmpty()) {
            return "参数错误：table 不能为空。";
        }
        if (column == null || column.trim().isEmpty()) {
            return "参数错误：column 不能为空。";
        }
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            ColumnStatistics stats = adapter.getColumnStatistics(schema, table, column);
            if (stats == null) {
                return "未获取到列 [" + schema + "." + table + "." + column + "] 的统计信息。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("数据源：").append(displayDatasource(datasource)).append("\n");
            sb.append("列统计信息：").append(schema).append(".").append(table).append(".").append(column).append("\n\n");
            sb.append("| 指标 | 值 |\n");
            sb.append("| ---- | -- |\n");
            sb.append("| 列名 | ").append(safeText(stats.getColumnName())).append(" |\n");
            sb.append("| 去重数(distinct) | ").append(stats.getDistinctCount()).append(" |\n");
            sb.append("| 空值数(null) | ").append(stats.getNullCount()).append(" |\n");
            sb.append("| 最小值 | ").append(safeText(formatValue(stats.getMinValue()))).append(" |\n");
            sb.append("| 最大值 | ").append(safeText(formatValue(stats.getMaxValue()))).append(" |\n");
            return sb.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (Exception e) {
            log.error("getColumnStatistics 执行失败：datasource={}, schema={}, table={}, column={}",
                    datasource, schema, table, column, e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return "查询列 [" + schema + "." + table + "." + column + "] 统计信息失败：" + safeMessage(e);
        }
    }

    public TableStatistics tableStatisticsRecord(String datasource, String schema, String table) {
        requireText(schema, "schema");
        requireText(table, "table");
        return resolveAdapter(datasource).getTableStatistics(schema, table);
    }

    public ColumnStatistics columnStatisticsRecord(
            String datasource, String schema, String table, String column) {
        requireText(schema, "schema");
        requireText(table, "table");
        requireText(column, "column");
        return resolveAdapter(datasource).getColumnStatistics(schema, table, column);
    }

    private void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /**
     * 根据传入的数据源名称解析出适配器；为空时返回默认适配器。
     */
    private DatabaseAdapter resolveAdapter(String datasource) {
        if (datasource == null || datasource.trim().isEmpty()) {
            return registry.getDefaultAdapter();
        }
        return registry.getAdapter(datasource);
    }

    /**
     * 用于结果显示的数据源名：为空时显示默认数据源名称。
     */
    private String displayDatasource(String datasource) {
        if (datasource == null || datasource.trim().isEmpty()) {
            String def = registry.getDefaultName();
            return def == null || def.isEmpty() ? "default" : def;
        }
        return datasource;
    }

    private String formatValue(String value) {
        return value == null ? "NULL" : value;
    }

    private String safeText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
