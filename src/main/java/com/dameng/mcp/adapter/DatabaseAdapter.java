package com.dameng.mcp.adapter;

import com.dameng.mcp.model.*;

import java.util.List;
import java.util.Map;

/**
 * 数据库适配器接口 - 定义数据库操作契约
 * 后续扩展其他数据库（如 Oracle、MySQL）时只需实现此接口
 */
public interface DatabaseAdapter {

    List<String> listSchemas();

    List<TableInfo> listTables(String schema);

    TableDefinition describeTable(String schema, String table);

    QueryResult executeQuery(String sql, int maxRows);

    List<Map<String, Object>> executeSampleQuery(String schema, String table, int limit);

    TableStatistics getTableStatistics(String schema, String table);

    ColumnStatistics getColumnStatistics(String schema, String table, String column);

    /**
     * 执行写操作（INSERT/UPDATE/DELETE）。
     * <p>
     * 实现类必须先通过 {@code SqlSecurityValidator#validateWrite(String)} 进行写白名单校验，
     * 再交由底层 JDBC 执行。
     * </p>
     *
     * @param sql 已验证的写 SQL
     * @return 影响的行数
     */
    int executeUpdate(String sql);
}
