package com.dameng.mcp.adapter;

/**
 * 数据库方言接口 - 处理各数据库 SQL 语法差异
 * 不同数据库分页、统计等 SQL 语法不同，通过此接口屏蔽差异
 */
public interface DatabaseDialect {

    String getDatabaseType();

    String wrapLimitQuery(String sql, int maxRows);

    String getValidationQuery();

    String getSampleDataQuery(String schema, String table, int limit);

    String getTableStatisticsQuery(String schema, String table);

    String getColumnStatisticsQuery(String schema, String table, String column);

    String getListSchemasQuery();

    String getListTablesQuery(String schema);

    String getDescribeTableQuery(String schema, String table);
}
