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

    /**
     * 执行 DDL 或通用 SQL（CREATE / ALTER / DROP / TRUNCATE 等）。
     * <p>
     * 实现类必须先通过 {@code SqlSecurityValidator#validateDdl(String)} 进行安全校验，
     * 只读数据源必须拒绝执行。底层通过 JDBC {@code execute()} 执行，
     * 不返回结果集，仅返回是否成功执行。
     * </p>
     *
     * @param sql 已验证的 DDL / 通用 SQL
     * @return true 表示执行成功
     */
    boolean executeRaw(String sql);
}
