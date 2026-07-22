package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.model.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据库查询 MCP 工具服务。
 * <p>
 * 仅提供只读查询能力。所有 SQL 都会经过适配器层的安全校验，
 * 任何修改类操作（INSERT/UPDATE/DELETE/DDL 等）都会被拒绝。
 * 通过 {@link DataSourceRegistry} 实现多数据源路由。
 * </p>
 */
@Slf4j
@Service
public class DatabaseQueryService {

    /**
     * executeQuery 默认返回行数
     */
    private static final int DEFAULT_MAX_ROWS = 100;

    /**
     * executeQuery 最大返回行数上限
     */
    private static final int LIMIT_MAX_ROWS = 500;

    /**
     * getSampleData 默认采样行数
     */
    private static final int DEFAULT_SAMPLE_LIMIT = 10;

    /**
     * getSampleData 采样行数上限
     */
    private static final int LIMIT_SAMPLE_LIMIT = 50;

    private final DataSourceRegistry registry;

    public DatabaseQueryService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @Tool(description = "执行只读 SQL 查询语句。仅支持 SELECT 查询，禁止任何修改操作(INSERT/UPDATE/DELETE/DDL)。返回查询结果的格式化表格，包含列头、数据行、总行数和执行耗时。如果违反安全规则将返回拒绝信息。")
    public String executeQuery(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "要执行的 SQL 查询语句，仅支持 SELECT 语句") String sql,
            @ToolParam(description = "最大返回行数，默认 100，最大 500。传入 0 或负数将使用默认值。") int maxRows) {
        if (sql == null || sql.trim().isEmpty()) {
            return "参数错误：SQL 不能为空。";
        }
        int effectiveMaxRows = normalizeMaxRows(maxRows);
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            QueryResult result = adapter.executeQuery(sql, effectiveMaxRows);
            return formatQueryResult(datasource, result, effectiveMaxRows);
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (SecurityException se) {
            // 安全校验拒绝：明确告知调用方该 SQL 被禁止
            log.warn("SQL 被安全策略拒绝：{}", sql, se);
            return "操作被拒绝：" + safeMessage(se)
                    + "\n本服务的查询接口仅允许执行只读 SELECT 查询，禁止任何修改类操作（INSERT/UPDATE/DELETE/DDL 等）。";
        } catch (Exception e) {
            log.error("executeQuery 执行失败：datasource={}, sql={}", datasource, sql, e);
            return "SQL 执行失败：" + safeMessage(e);
        }
    }

    @Tool(description = "获取指定表的样本数据，用于快速预览表中的数据内容和格式。等价于执行 SELECT * FROM schema.table LIMIT N，结果以 Markdown 表格返回。")
    public String getSampleData(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "数据库模式名称，如 DMHR、SYSDBA") String schema,
            @ToolParam(description = "表名称") String table,
            @ToolParam(description = "返回的样本行数，默认 10，最大 50。传入 0 或负数将使用默认值。") int limit) {
        if (schema == null || schema.trim().isEmpty()) {
            return "参数错误：schema 不能为空。";
        }
        if (table == null || table.trim().isEmpty()) {
            return "参数错误：table 不能为空。";
        }
        int effectiveLimit = normalizeSampleLimit(limit);
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            List<Map<String, Object>> rows = adapter.executeSampleQuery(schema, table, effectiveLimit);
            return formatSampleRows(datasource, schema, table, rows, effectiveLimit);
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (Exception e) {
            log.error("getSampleData 执行失败：datasource={}, schema={}, table={}, limit={}",
                    datasource, schema, table, effectiveLimit, e);
            return "获取表 [" + schema + "." + table + "] 样本数据失败：" + safeMessage(e);
        }
    }

    /* ====================== 内部工具方法 ====================== */

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

    private int normalizeMaxRows(int maxRows) {
        if (maxRows <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(maxRows, LIMIT_MAX_ROWS);
    }

    private int normalizeSampleLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_SAMPLE_LIMIT;
        }
        return Math.min(limit, LIMIT_SAMPLE_LIMIT);
    }

    /**
     * 将 QueryResult 格式化为 Markdown 表格文本。
     */
    private String formatQueryResult(String datasource, QueryResult result, int effectiveMaxRows) {
        if (result == null) {
            return "查询完成，但未返回结果。";
        }

        List<String> columns = result.getColumns();
        List<Map<String, Object>> rows = result.getRows();

        StringBuilder sb = new StringBuilder();
        sb.append("查询执行成功。\n");
        sb.append("- 数据源：").append(displayDatasource(datasource)).append("\n");
        sb.append("- 返回行数：").append(result.getTotalRows()).append("\n");
        sb.append("- 行数上限：").append(effectiveMaxRows).append("\n");
        if (result.getExecutionTime() != null) {
            sb.append("- 执行耗时：").append(result.getExecutionTime()).append("\n");
        }
        sb.append("\n");

        if (columns == null || columns.isEmpty()) {
            sb.append("（无列信息）");
            return sb.toString();
        }

        // 表头
        sb.append("|");
        for (String col : columns) {
            sb.append(" ").append(safeText(col)).append(" |");
        }
        sb.append("\n|");
        for (int i = 0; i < columns.size(); i++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        // 数据行
        if (rows == null || rows.isEmpty()) {
            sb.append("（查询无数据）\n");
            return sb.toString();
        }
        for (Map<String, Object> row : rows) {
            sb.append("|");
            for (String col : columns) {
                sb.append(" ").append(safeText(stringify(row.get(col)))).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 将采样查询返回的行集合格式化为 Markdown 表格。
     */
    private String formatSampleRows(String datasource, String schema, String table,
                                    List<Map<String, Object>> rows, int effectiveLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据源 [").append(displayDatasource(datasource)).append("] 表 [")
                .append(schema).append(".").append(table).append("] 样本数据，请求行数 ")
                .append(effectiveLimit).append("，实际返回 ")
                .append(rows == null ? 0 : rows.size()).append(" 行。\n\n");

        if (rows == null || rows.isEmpty()) {
            sb.append("（表中无数据）");
            return sb.toString();
        }

        // 以首行的列顺序作为表头
        Map<String, Object> first = rows.get(0);
        List<String> columns = new java.util.ArrayList<>(first.keySet());

        sb.append("|");
        for (String col : columns) {
            sb.append(" ").append(safeText(col)).append(" |");
        }
        sb.append("\n|");
        for (int i = 0; i < columns.size(); i++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        for (Map<String, Object> row : rows) {
            sb.append("|");
            for (String col : columns) {
                sb.append(" ").append(safeText(stringify(row.get(col)))).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String stringify(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[]) {
            return "<binary:" + ((byte[]) value).length + " bytes>";
        }
        return String.valueOf(value);
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
