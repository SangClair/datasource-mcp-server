package com.dameng.mcp.adapter.oracle;

import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.model.ColumnInfo;
import com.dameng.mcp.model.ColumnStatistics;
import com.dameng.mcp.model.QueryResult;
import com.dameng.mcp.model.TableDefinition;
import com.dameng.mcp.model.TableInfo;
import com.dameng.mcp.model.TableStatistics;
import com.dameng.mcp.security.SqlSecurityValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle 数据库适配器实现。
 * <p>
 * 与 {@code DamengDatabaseAdapter} 结构一致，仅在方言层处理 SQL 差异。
 * 本类不使用 {@code @Component}，由 {@code DatabaseAdapterFactory} 手动实例化。
 * </p>
 */
@Slf4j
public class OracleDatabaseAdapter implements DatabaseAdapter {

    /**
     * 默认查询超时时间（秒）
     */
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final OracleDialect dialect;
    private final SqlSecurityValidator securityValidator;
    /**
     * 是否为只读数据源。只读模式下，{@link #executeUpdate(String)} 将直接抛出
     * {@link SecurityException}，作为 Adapter 层的硬拦截。
     */
    private final boolean readonly;

    public OracleDatabaseAdapter(JdbcTemplate jdbcTemplate,
                                 OracleDialect dialect,
                                 SqlSecurityValidator securityValidator,
                                 boolean readonly) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.securityValidator = securityValidator;
        this.readonly = readonly;
        this.jdbcTemplate.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    @Override
    public List<String> listSchemas() {
        String sql = dialect.getListSchemasQuery();
        log.debug("listSchemas SQL: {}", sql);
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1));
        } catch (DataAccessException e) {
            log.error("查询 schema 列表失败", e);
            throw new RuntimeException("查询 schema 列表失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        securityValidator.validateIdentifier(schema, "schema");
        String sql = dialect.getListTablesQuery(schema);
        log.debug("listTables SQL: {}", sql);
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                TableInfo info = new TableInfo();
                info.setSchemaName(rs.getString("SCHEMA_NAME"));
                info.setTableName(rs.getString("TABLE_NAME"));
                info.setComments(rs.getString("COMMENTS"));
                return info;
            });
        } catch (DataAccessException e) {
            log.error("查询表列表失败：schema={}", schema, e);
            throw new RuntimeException("查询表列表失败：" + e.getMessage(), e);
        }
    }

    @Override
    public TableDefinition describeTable(String schema, String table) {
        securityValidator.validateIdentifier(schema, "schema");
        securityValidator.validateIdentifier(table, "table");

        String sql = dialect.getDescribeTableQuery(schema, table);
        log.debug("describeTable SQL: {}", sql);

        try {
            List<ColumnInfo> columns = jdbcTemplate.query(sql, (rs, rowNum) -> {
                ColumnInfo column = new ColumnInfo();
                column.setColumnName(rs.getString("COLUMN_NAME"));
                column.setDataType(rs.getString("DATA_TYPE"));

                int columnSize = rs.getInt("COLUMN_SIZE");
                column.setColumnSize(rs.wasNull() ? null : columnSize);

                int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                column.setDecimalDigits(rs.wasNull() ? null : decimalDigits);

                String nullable = rs.getString("NULLABLE");
                // Oracle 中 NULLABLE 字段值为 'Y' / 'N'
                column.setNullable(nullable == null || "Y".equalsIgnoreCase(nullable));

                column.setDefaultValue(rs.getString("DEFAULT_VALUE"));
                column.setComments(rs.getString("COMMENTS"));
                return column;
            });

            TableDefinition definition = new TableDefinition();
            definition.setSchemaName(schema);
            definition.setTableName(table);
            definition.setColumns(columns);
            return definition;
        } catch (DataAccessException e) {
            log.error("查询表定义失败：schema={}, table={}", schema, table, e);
            throw new RuntimeException("查询表定义失败：" + e.getMessage(), e);
        }
    }

    @Override
    public QueryResult executeQuery(String sql, int maxRows) {
        String validatedSql = securityValidator.validate(sql);
        String finalSql = securityValidator.applyRowLimit(validatedSql, maxRows, dialect);
        log.debug("executeQuery final SQL: {}", finalSql);

        long startTime = System.currentTimeMillis();
        try {
            QueryResult result = jdbcTemplate.execute((ConnectionCallback<QueryResult>) connection -> {
                boolean originalReadOnly = connection.isReadOnly();
                try {
                    connection.setReadOnly(true);
                } catch (SQLException ignore) {
                    // 部分驱动可能不支持，忽略
                }

                try (PreparedStatement ps = connection.prepareStatement(finalSql)) {
                    ps.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
                    if (maxRows > 0) {
                        ps.setMaxRows(maxRows);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        return mapResultSet(rs);
                    }
                } finally {
                    try {
                        connection.setReadOnly(originalReadOnly);
                    } catch (SQLException ignore) {
                        // ignore
                    }
                }
            });

            long elapsed = System.currentTimeMillis() - startTime;
            if (result != null) {
                result.setExecutionTime(elapsed + "ms");
            }
            return result;
        } catch (DataAccessException e) {
            log.error("SQL 执行失败：{}", finalSql, e);
            throw new RuntimeException("SQL 执行失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> executeSampleQuery(String schema, String table, int limit) {
        securityValidator.validateIdentifier(schema, "schema");
        securityValidator.validateIdentifier(table, "table");

        String sql = dialect.getSampleDataQuery(schema, table, limit);
        log.debug("executeSampleQuery SQL: {}", sql);
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (DataAccessException e) {
            log.error("采样查询失败：schema={}, table={}", schema, table, e);
            throw new RuntimeException("采样查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public TableStatistics getTableStatistics(String schema, String table) {
        securityValidator.validateIdentifier(schema, "schema");
        securityValidator.validateIdentifier(table, "table");

        String sql = dialect.getTableStatisticsQuery(schema, table);
        log.debug("getTableStatistics SQL: {}", sql);
        try {
            Long rowCount = jdbcTemplate.queryForObject(sql, Long.class);
            TableStatistics statistics = new TableStatistics();
            statistics.setSchemaName(schema);
            statistics.setTableName(table);
            statistics.setRowCount(rowCount == null ? 0L : rowCount);
            return statistics;
        } catch (DataAccessException e) {
            log.error("查询表统计信息失败：schema={}, table={}", schema, table, e);
            throw new RuntimeException("查询表统计信息失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ColumnStatistics getColumnStatistics(String schema, String table, String column) {
        securityValidator.validateIdentifier(schema, "schema");
        securityValidator.validateIdentifier(table, "table");
        securityValidator.validateIdentifier(column, "column");

        String sql = dialect.getColumnStatisticsQuery(schema, table, column);
        log.debug("getColumnStatistics SQL: {}", sql);
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                ColumnStatistics stats = new ColumnStatistics();
                stats.setSchemaName(schema);
                stats.setTableName(table);
                stats.setColumnName(column);
                stats.setDistinctCount(rs.getLong("DISTINCT_COUNT"));
                stats.setNullCount(rs.getLong("NULL_COUNT"));

                Object min = rs.getObject("MIN_VALUE");
                stats.setMinValue(min == null ? null : String.valueOf(min));

                Object max = rs.getObject("MAX_VALUE");
                stats.setMaxValue(max == null ? null : String.valueOf(max));
                return stats;
            });
        } catch (DataAccessException e) {
            log.error("查询列统计信息失败：schema={}, table={}, column={}", schema, table, column, e);
            throw new RuntimeException("查询列统计信息失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int executeUpdate(String sql) {
        // 只读硬拦截 - 在执行层面直接阻止，不依赖上层调用方
        if (this.readonly) {
            throw new SecurityException("当前数据源为只读模式，禁止执行任何写操作");
        }
        String validatedSql = securityValidator.validateWrite(sql);
        log.debug("executeUpdate SQL: {}", validatedSql);
        try {
            return jdbcTemplate.update(validatedSql);
        } catch (DataAccessException e) {
            log.error("写操作执行失败：{}", validatedSql, e);
            throw new RuntimeException("写操作执行失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean executeRaw(String sql) {
        if (this.readonly) {
            throw new SecurityException("当前数据源为只读模式，禁止执行 DDL 或通用 SQL");
        }
        String validatedSql = securityValidator.validateDdl(sql);
        log.debug("executeRaw SQL: {}", validatedSql);
        try {
            Boolean result = jdbcTemplate.execute(validatedSql);
            return result == null || result;
        } catch (DataAccessException e) {
            log.error("DDL/通用 SQL 执行失败：{}", validatedSql, e);
            throw new RuntimeException("DDL/通用 SQL 执行失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将 ResultSet 转换为 QueryResult 数据结构。
     */
    private QueryResult mapResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }

        QueryResult result = new QueryResult();
        result.setColumns(columns);
        result.setRows(rows);
        result.setTotalRows(rows.size());
        return result;
    }
}
