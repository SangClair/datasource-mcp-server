package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.model.ColumnInfo;
import com.dameng.mcp.model.DataSourceInfo;
import com.dameng.mcp.model.TableDefinition;
import com.dameng.mcp.model.TableInfo;
import com.dameng.mcp.util.ConnectionError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据库元数据 MCP 工具服务。
 * <p>
 * 提供数据源列表、schema 列表、表列表、表结构等元数据查询能力，
 * 所有方法返回格式化的字符串以便 LLM 阅读。
 * 通过 {@link DataSourceRegistry} 实现多数据源路由。
 * </p>
 */
@Slf4j
@Service
public class DatabaseMetadataService {

    private final DataSourceRegistry registry;

    public DatabaseMetadataService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @Tool(description = "列出所有可用的数据源连接，包含名称、类型和用途描述。在执行其他数据库操作前，建议先调用此工具了解可用的数据源。")
    public String listDatasources() {
        try {
            List<DataSourceInfo> infos = registry.listDataSources();
            if (infos == null || infos.isEmpty()) {
                return "当前未配置任何数据源。";
            }
            String defaultName = registry.getDefaultName();
            StringBuilder sb = new StringBuilder();
            sb.append("当前共配置 ").append(infos.size()).append(" 个数据源");
            if (defaultName != null && !defaultName.isEmpty()) {
                sb.append("，默认数据源：").append(defaultName);
            }
            sb.append("。\n\n");
            sb.append("| 名称 | 类型 | 只读 | 描述 |\n");
            sb.append("| ---- | ---- | ---- | ---- |\n");
            for (DataSourceInfo info : infos) {
                String name = info.getName();
                boolean isDefault = name != null && name.equals(defaultName);
                sb.append("| ")
                        .append(safeText(name))
                        .append(isDefault ? " (默认)" : "")
                        .append(" | ")
                        .append(safeText(info.getType())).append(" | ")
                        .append(info.isReadonly() ? "是" : "否").append(" | ")
                        .append(safeText(info.getDescription())).append(" |\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("listDatasources 执行失败", e);
            return "查询数据源列表失败：" + safeMessage(e);
        }
    }

    @Tool(description = "列出指定数据源中所有可用的模式(Schema)名称。返回所有可访问的 schema 名称列表，便于后续选择目标 schema 进行表结构查询或数据查询。")
    public String listSchemas(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource) {
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            List<String> schemas = adapter.listSchemas();
            if (schemas == null || schemas.isEmpty()) {
                return "未找到任何可用的数据库模式(Schema)。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("数据源 [").append(displayDatasource(datasource)).append("] 可用模式(Schema)列表，共 ")
                    .append(schemas.size()).append(" 个：\n");
            for (int i = 0; i < schemas.size(); i++) {
                sb.append(i + 1).append(". ").append(schemas.get(i)).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (Exception e) {
            log.error("listSchemas 执行失败：datasource={}", datasource, e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return "查询数据库模式列表失败：" + safeMessage(e);
        }
    }

    @Tool(description = "列出指定数据源中指定模式(Schema)下的所有表，包含表名和表注释。结果以 Markdown 表格形式返回，便于快速了解 schema 中包含哪些业务表。")
    public String listTables(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "数据库模式名称，如 DMHR、SYSDBA") String schema) {
        if (schema == null || schema.trim().isEmpty()) {
            return "参数错误：schema 不能为空。";
        }
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            List<TableInfo> tables = adapter.listTables(schema);
            if (tables == null || tables.isEmpty()) {
                return "数据源 [" + displayDatasource(datasource) + "] 模式 [" + schema + "] 下未找到任何表。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("数据源 [").append(displayDatasource(datasource)).append("] 模式 [")
                    .append(schema).append("] 下的表列表，共 ").append(tables.size()).append(" 张表：\n\n");
            sb.append("| 序号 | 表名 | 表注释 |\n");
            sb.append("| ---- | ---- | ------ |\n");
            int idx = 1;
            for (TableInfo info : tables) {
                sb.append("| ").append(idx++).append(" | ")
                        .append(safeText(info.getTableName())).append(" | ")
                        .append(safeText(info.getComments())).append(" |\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (Exception e) {
            log.error("listTables 执行失败：datasource={}, schema={}", datasource, schema, e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return "查询模式 [" + schema + "] 下的表列表失败：" + safeMessage(e);
        }
    }

    @Tool(description = "获取指定表的详细结构信息，包括列名、数据类型、是否可空、默认值和列注释。结果以 Markdown 表格形式返回，用于理解表的字段定义，辅助编写 SQL 查询。")
    public String describeTable(
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
            TableDefinition definition = adapter.describeTable(schema, table);
            if (definition == null || definition.getColumns() == null || definition.getColumns().isEmpty()) {
                return "表 [" + schema + "." + table + "] 不存在或没有列信息。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("数据源：").append(displayDatasource(datasource)).append("\n");
            sb.append("表结构：").append(schema).append(".").append(table).append("\n");
            if (definition.getComments() != null && !definition.getComments().isEmpty()) {
                sb.append("表注释：").append(definition.getComments()).append("\n");
            }
            sb.append("列数：").append(definition.getColumns().size()).append("\n\n");

            sb.append("| 序号 | 列名 | 数据类型 | 可空 | 默认值 | 注释 |\n");
            sb.append("| ---- | ---- | -------- | ---- | ------ | ---- |\n");
            int idx = 1;
            for (ColumnInfo col : definition.getColumns()) {
                sb.append("| ").append(idx++).append(" | ")
                        .append(safeText(col.getColumnName())).append(" | ")
                        .append(formatDataType(col)).append(" | ")
                        .append(col.isNullable() ? "是" : "否").append(" | ")
                        .append(safeText(col.getDefaultValue())).append(" | ")
                        .append(safeText(col.getComments())).append(" |\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (Exception e) {
            log.error("describeTable 执行失败：datasource={}, schema={}, table={}", datasource, schema, table, e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return "查询表 [" + schema + "." + table + "] 结构失败：" + safeMessage(e);
        }
    }

    public List<DataSourceInfo> listDatasourceRecords() {
        return registry.listDataSources();
    }

    public List<String> listSchemaRecords(String datasource) {
        return resolveAdapter(datasource).listSchemas();
    }

    public List<TableInfo> listTableRecords(String datasource, String schema) {
        requireText(schema, "schema");
        return resolveAdapter(datasource).listTables(schema);
    }

    public TableDefinition describeTableRecord(String datasource, String schema, String table) {
        requireText(schema, "schema");
        requireText(table, "table");
        return resolveAdapter(datasource).describeTable(schema, table);
    }

    public String resolvedDatasource(String datasource) {
        return displayDatasource(datasource);
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
     * 用于日志/输出的数据源显示名：为空时显示默认数据源名称。
     */
    private String displayDatasource(String datasource) {
        if (datasource == null || datasource.trim().isEmpty()) {
            String def = registry.getDefaultName();
            return def == null || def.isEmpty() ? "default" : def;
        }
        return datasource;
    }

    /**
     * 将列的数据类型与精度信息组合成可读字符串。
     * 例如：VARCHAR(64)、NUMBER(10,2)、DATE。
     */
    private String formatDataType(ColumnInfo col) {
        String type = col.getDataType() == null ? "" : col.getDataType();
        Integer size = col.getColumnSize();
        Integer scale = col.getDecimalDigits();
        if (size == null || size <= 0) {
            return type;
        }
        if (scale != null && scale > 0) {
            return type + "(" + size + "," + scale + ")";
        }
        return type + "(" + size + ")";
    }

    private String safeText(String s) {
        if (s == null) {
            return "";
        }
        // 替换会破坏 Markdown 表格的字符
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
