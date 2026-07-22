package com.dameng.mcp.adapter.dameng;

import com.dameng.mcp.adapter.DatabaseDialect;

/**
 * 达梦数据库方言实现。
 * <p>
 * 达梦兼容 Oracle，多数系统视图与 Oracle 相同（ALL_TABLES、ALL_TAB_COLUMNS 等）。
 * 同时也支持类似 MySQL 的 LIMIT 语法，本实现优先使用 LIMIT，便于行数限制包装。
 * </p>
 * <p>
 * 注意：本类不再使用 {@code @Component}，由 {@code DatabaseAdapterFactory} 手动实例化。
 * </p>
 */
public class DamengDialect implements DatabaseDialect {

    private static final String DATABASE_TYPE = "dameng";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    /**
     * 使用达梦兼容的 LIMIT 语法对原 SQL 包装行数限制。
     * 通过子查询包装的方式避免破坏原 SQL 的结构（例如 ORDER BY、GROUP BY 等）。
     */
    @Override
    public String wrapLimitQuery(String sql, int maxRows) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (maxRows <= 0) {
            return sql;
        }
        // 去除尾部分号，避免子查询语法错误
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // 优先使用 LIMIT（达梦支持），通过子查询包装保持原 SQL 完整性
        return "SELECT * FROM (" + trimmed + ") __limited__ LIMIT " + maxRows;
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

    /**
     * 表统计信息查询：以 COUNT(*) 作为基础统计。
     */
    @Override
    public String getTableStatisticsQuery(String schema, String table) {
        return "SELECT COUNT(*) AS ROW_COUNT FROM " + qualifyName(schema, table);
    }

    /**
     * 列统计信息查询：包括去重数、空值数、最小值、最大值。
     */
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

    /**
     * 列出所有 schema：达梦兼容 Oracle，使用 ALL_TABLES 的 OWNER 字段去重。
     */
    @Override
    public String getListSchemasQuery() {
        return "SELECT DISTINCT OWNER AS SCHEMA_NAME FROM ALL_TABLES ORDER BY OWNER";
    }

    /**
     * 列出指定 schema 下的所有表，并 left join 表注释。
     */
    @Override
    public String getListTablesQuery(String schema) {
        // 注意：schema 参数已由调用方校验合法性，这里直接拼接（达梦标识符大小写敏感策略与 Oracle 一致）
        return "SELECT t.OWNER AS SCHEMA_NAME, "
                + "       t.TABLE_NAME AS TABLE_NAME, "
                + "       c.COMMENTS AS COMMENTS "
                + "FROM ALL_TABLES t "
                + "LEFT JOIN ALL_TAB_COMMENTS c "
                + "  ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME "
                + "WHERE t.OWNER = '" + schema + "' "
                + "ORDER BY t.TABLE_NAME";
    }

    /**
     * 描述指定表的列结构，包含列注释。
     */
    @Override
    public String getDescribeTableQuery(String schema, String table) {
        return "SELECT col.COLUMN_NAME AS COLUMN_NAME, "
                + "       col.DATA_TYPE AS DATA_TYPE, "
                + "       col.DATA_LENGTH AS COLUMN_SIZE, "
                + "       col.DATA_SCALE AS DECIMAL_DIGITS, "
                + "       col.NULLABLE AS NULLABLE, "
                + "       col.DATA_DEFAULT AS DEFAULT_VALUE, "
                + "       cmt.COMMENTS AS COMMENTS "
                + "FROM ALL_TAB_COLUMNS col "
                + "LEFT JOIN ALL_COL_COMMENTS cmt "
                + "  ON cmt.OWNER = col.OWNER "
                + " AND cmt.TABLE_NAME = col.TABLE_NAME "
                + " AND cmt.COLUMN_NAME = col.COLUMN_NAME "
                + "WHERE col.OWNER = '" + schema + "' "
                + "  AND col.TABLE_NAME = '" + table + "' "
                + "ORDER BY col.COLUMN_ID";
    }

    /**
     * 拼接 schema.table 全限定名，避免空 schema 时产生前导点
     */
    private String qualifyName(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            return quoteIdentifier(table);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    /**
     * 引用标识符。当前默认不加双引号以保持与 Oracle/达梦默认大写策略一致；
     * 如需保留原大小写，可在此处统一加双引号。
     */
    private String quoteIdentifier(String identifier) {
        return identifier;
    }
}
