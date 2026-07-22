package com.dameng.mcp.adapter.mysql;

import com.dameng.mcp.adapter.DatabaseDialect;

/**
 * MySQL 数据库方言实现。
 * <p>
 * MySQL 中 schema 即 database，元数据通过 INFORMATION_SCHEMA 视图查询。
 * 本类不使用 {@code @Component}，由 {@code DatabaseAdapterFactory} 手动实例化。
 * </p>
 */
public class MysqlDialect implements DatabaseDialect {

    private static final String DATABASE_TYPE = "mysql";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    /**
     * 通过子查询包装并使用 LIMIT 实现行数限制。
     */
    @Override
    public String wrapLimitQuery(String sql, int maxRows) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (maxRows <= 0) {
            return sql;
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return "SELECT * FROM (" + trimmed + ") AS __t LIMIT " + maxRows;
    }

    @Override
    public String getValidationQuery() {
        return "SELECT 1";
    }

    @Override
    public String getSampleDataQuery(String schema, String table, int limit) {
        int safeLimit = limit <= 0 ? 100 : limit;
        return "SELECT * FROM " + qualifyName(schema, table) + " LIMIT " + safeLimit;
    }

    @Override
    public String getTableStatisticsQuery(String schema, String table) {
        return "SELECT COUNT(*) AS ROW_COUNT FROM " + qualifyName(schema, table);
    }

    @Override
    public String getColumnStatisticsQuery(String schema, String table, String column) {
        String fullName = qualifyName(schema, table);
        String col = quoteIdentifier(column);
        return "SELECT "
                + "COUNT(DISTINCT " + col + ") AS DISTINCT_COUNT, "
                + "COUNT(*) - COUNT(" + col + ") AS NULL_COUNT, "
                + "MIN(" + col + ") AS MIN_VALUE, "
                + "MAX(" + col + ") AS MAX_VALUE "
                + "FROM " + fullName;
    }

    @Override
    public String getListSchemasQuery() {
        return "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY SCHEMA_NAME";
    }

    @Override
    public String getListTablesQuery(String schema) {
        // schema 已由调用方校验合法性
        return "SELECT t.TABLE_NAME AS TABLE_NAME, "
                + "       t.TABLE_SCHEMA AS SCHEMA_NAME, "
                + "       t.TABLE_COMMENT AS COMMENTS "
                + "FROM INFORMATION_SCHEMA.TABLES t "
                + "WHERE t.TABLE_SCHEMA = '" + schema + "' "
                + "  AND t.TABLE_TYPE = 'BASE TABLE' "
                + "ORDER BY t.TABLE_NAME";
    }

    @Override
    public String getDescribeTableQuery(String schema, String table) {
        return "SELECT COLUMN_NAME, "
                + "       DATA_TYPE, "
                + "       CHARACTER_MAXIMUM_LENGTH AS COLUMN_SIZE, "
                + "       NUMERIC_SCALE AS DECIMAL_DIGITS, "
                + "       IS_NULLABLE AS NULLABLE, "
                + "       COLUMN_DEFAULT AS DEFAULT_VALUE, "
                + "       COLUMN_COMMENT AS COMMENTS "
                + "FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = '" + schema + "' "
                + "  AND TABLE_NAME = '" + table + "' "
                + "ORDER BY ORDINAL_POSITION";
    }

    /**
     * 拼接 schema.table 全限定名（MySQL 中 schema 即 database）。
     */
    private String qualifyName(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            return quoteIdentifier(table);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    /**
     * 引用标识符。当前默认不加反引号，由调用方保证标识符合法性。
     */
    private String quoteIdentifier(String identifier) {
        return identifier;
    }
}
