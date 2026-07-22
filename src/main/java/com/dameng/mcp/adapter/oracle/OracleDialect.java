package com.dameng.mcp.adapter.oracle;

import com.dameng.mcp.adapter.DatabaseDialect;

/**
 * Oracle 数据库方言实现。
 * <p>
 * Oracle 不支持 LIMIT，分页通过 ROWNUM 实现；统计与系统视图与达梦基本一致。
 * 本类不使用 {@code @Component}，由 {@code DatabaseAdapterFactory} 手动实例化。
 * </p>
 */
public class OracleDialect implements DatabaseDialect {

    private static final String DATABASE_TYPE = "oracle";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    /**
     * 通过 ROWNUM 子查询包装方式实现行数限制，避免破坏原 SQL 中的 ORDER BY / GROUP BY 等结构。
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
        return "SELECT * FROM (" + trimmed + ") WHERE ROWNUM <= " + maxRows;
    }

    @Override
    public String getValidationQuery() {
        return "SELECT 1 FROM DUAL";
    }

    @Override
    public String getSampleDataQuery(String schema, String table, int limit) {
        int safeLimit = limit <= 0 ? 100 : limit;
        return "SELECT * FROM " + qualifyName(schema, table) + " WHERE ROWNUM <= " + safeLimit;
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
        return "SELECT DISTINCT OWNER AS SCHEMA_NAME FROM ALL_TABLES ORDER BY OWNER";
    }

    @Override
    public String getListTablesQuery(String schema) {
        // schema 已由调用方校验合法性，直接拼接
        return "SELECT t.OWNER AS SCHEMA_NAME, "
                + "       t.TABLE_NAME AS TABLE_NAME, "
                + "       c.COMMENTS AS COMMENTS "
                + "FROM ALL_TABLES t "
                + "LEFT JOIN ALL_TAB_COMMENTS c "
                + "  ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME "
                + "WHERE t.OWNER = '" + schema + "' "
                + "ORDER BY t.TABLE_NAME";
    }

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
     * 引用标识符。当前默认不加双引号以保持与 Oracle 默认大写策略一致。
     */
    private String quoteIdentifier(String identifier) {
        return identifier;
    }
}
