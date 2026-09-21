package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.security.SqlSecurityValidator;
import com.dameng.mcp.util.ConnectionError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 数据库写入 MCP 工具服务。
 * <p>
 * 提供 INSERT / UPDATE / DELETE 三种受控的写入能力，所有 SQL 在执行前
 * 必须通过 {@link SqlSecurityValidator#validateWrite(String)} 的安全校验，
 * 严格区分操作类型避免 LLM 误用工具。
 * 通过 {@link DataSourceRegistry} 实现多数据源路由。
 * </p>
 */
@Slf4j
@Service
public class DatabaseWriteService {

    public record WriteOperationResult(String datasource, String operation, int affectedRows,
                                       long durationMs, boolean unsafeScope, List<String> warnings) {
    }

    public record SqlOperationResult(String datasource, String statementType, long durationMs,
                                     boolean unsafeScope, List<String> warnings) {
    }

    private final DataSourceRegistry registry;
    private final SqlSecurityValidator securityValidator;

    public DatabaseWriteService(DataSourceRegistry registry, SqlSecurityValidator securityValidator) {
        this.registry = registry;
        this.securityValidator = securityValidator;
    }

    @Tool(description = "执行数据插入操作(INSERT INTO)。【警告】此操作会向数据库写入新数据，执行前请确认 SQL 正确性。仅支持 INSERT 语句，禁止 DDL 和其他危险操作。")
    public String executeInsert(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "INSERT SQL 语句，如: INSERT INTO schema.table (col1, col2) VALUES (val1, val2)") String sql) {
        return doWrite(datasource, sql, "INSERT");
    }

    @Tool(description = "执行数据更新操作(UPDATE)。【警告】此操作会修改数据库中的现有数据，执行前请确认 SQL 正确性。强烈建议包含 WHERE 条件以避免全表更新。")
    public String executeUpdate(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "UPDATE SQL 语句，如: UPDATE schema.table SET col1=val1 WHERE condition") String sql) {
        return doWrite(datasource, sql, "UPDATE");
    }

    @Tool(description = "执行数据删除操作(DELETE)。【警告】此操作会永久删除数据库中的数据，不可恢复！执行前请务必确认 SQL 正确性。强烈建议包含 WHERE 条件以避免全表删除。")
    public String executeDelete(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源") String datasource,
            @ToolParam(description = "DELETE SQL 语句，如: DELETE FROM schema.table WHERE condition") String sql) {
        return doWrite(datasource, sql, "DELETE");
    }

    @Tool(description = "执行 DDL 或通用 SQL 语句（如 CREATE TABLE/INDEX/VIEW、ALTER TABLE、DROP TABLE/INDEX/VIEW、TRUNCATE TABLE、RENAME TABLE、CREATE SEQUENCE、GRANT、REVOKE 等）。【警告】此操作会修改数据库结构或权限，可能不可逆！仅可用于非只读数据源，禁止 EXEC/CALL/LOAD DATA 等高危操作。")
    public String executeDdl(
            @ToolParam(description = "数据源名称，通过 list_datasources 获取可用数据源。为空则使用默认数据源。必须为非只读数据源") String datasource,
            @ToolParam(description = "DDL 或通用 SQL 语句，如: CREATE TABLE schema.table_name (id INT PRIMARY KEY, name VARCHAR(100))") String sql) {
        // 1. 参数校验
        if (sql == null || sql.trim().isEmpty()) {
            return "参数错误：SQL 不能为空。";
        }

        // 2. 安全验证（黑名单 + 多语句注入检测）
        try {
            securityValidator.validateDdl(sql);
        } catch (SecurityException se) {
            log.warn("DDL SQL 被安全策略拒绝：sqlHash={}", sqlHash(sql), se);
            return "操作被安全策略拒绝：" + safeMessage(se);
        }

        // 3. 只读数据源拦截
        String resolvedName = (datasource == null || datasource.trim().isEmpty()) ? registry.getDefaultName() : datasource;
        if (registry.isReadonly(resolvedName)) {
            return "操作被拒绝：数据源 [" + resolvedName + "] 配置为只读模式，不允许执行 DDL 或通用 SQL。";
        }

        // 4. 路由到对应数据源并执行
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            long startTime = System.currentTimeMillis();
            adapter.executeRaw(sql);
            long elapsed = System.currentTimeMillis() - startTime;

            StringBuilder result = new StringBuilder();
            result.append("执行成功。\n");
            result.append("- 数据源：").append(displayDatasource(datasource)).append("\n");
            result.append("- 执行耗时：").append(elapsed).append("ms\n");
            return result.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (SecurityException se) {
            log.warn("DDL SQL 被安全策略拒绝：sqlHash={}", sqlHash(sql), se);
            return "操作被安全策略拒绝：" + safeMessage(se);
        } catch (Exception e) {
            log.error("DDL/通用 SQL 执行失败：datasource={}, sqlHash={}", datasource, sqlHash(sql), e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return "DDL/通用 SQL 执行失败：" + safeMessage(e);
        }
    }

    public WriteOperationResult executeWriteRecord(String datasource, String sql, String expectedType) {
        requireSql(sql);
        String operation = expectedType == null ? "" : expectedType.trim().toUpperCase();
        String cleaned = securityValidator.validateWrite(sql);
        if (!cleaned.toUpperCase().startsWith(operation)) {
            throw new IllegalArgumentException("当前工具仅支持 " + operation + " 语句");
        }
        String resolvedName = resolvedName(datasource);
        ensureWritable(resolvedName, "写操作");
        boolean unsafeScope = ("UPDATE".equals(operation) || "DELETE".equals(operation))
                && !securityValidator.hasWhereClause(cleaned);
        List<String> warnings = unsafeScope
                ? List.of(operation + " 语句未包含 WHERE 条件，将影响全表数据；本服务按配置继续执行。")
                : List.of();
        auditBefore(operation, resolvedName, cleaned, unsafeScope);
        long start = System.currentTimeMillis();
        try {
            int affectedRows = resolveAdapter(datasource).executeUpdate(cleaned);
            long duration = System.currentTimeMillis() - start;
            auditAfter(operation, resolvedName, cleaned, affectedRows, duration, true);
            return new WriteOperationResult(resolvedName, operation, affectedRows, duration, unsafeScope, warnings);
        } catch (RuntimeException e) {
            auditAfter(operation, resolvedName, cleaned, -1,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    public SqlOperationResult executeSqlRecord(String datasource, String sql) {
        requireSql(sql);
        String cleaned = securityValidator.validateDdl(sql);
        String statementType = firstKeyword(cleaned);
        String resolvedName = resolvedName(datasource);
        ensureWritable(resolvedName, "通用 SQL");
        boolean unsafeScope = ("UPDATE".equals(statementType) || "DELETE".equals(statementType))
                && !securityValidator.hasWhereClause(cleaned);
        List<String> warnings = unsafeScope
                ? List.of(statementType + " 语句未包含 WHERE 条件，将影响全表数据；本服务按配置继续执行。")
                : List.of("该工具会直接执行任意单条 SQL，客户端确认并非服务端强制安全边界。");
        auditBefore(statementType, resolvedName, cleaned, unsafeScope);
        long start = System.currentTimeMillis();
        try {
            resolveAdapter(datasource).executeRaw(cleaned);
            long duration = System.currentTimeMillis() - start;
            auditAfter(statementType, resolvedName, cleaned, -1, duration, true);
            return new SqlOperationResult(resolvedName, statementType, duration, unsafeScope, warnings);
        } catch (RuntimeException e) {
            auditAfter(statementType, resolvedName, cleaned, -1,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    private void requireSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
    }

    private String resolvedName(String datasource) {
        String resolved = datasource == null || datasource.trim().isEmpty() ? registry.getDefaultName() : datasource;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("未找到关系型数据源");
        }
        return resolved;
    }

    private void ensureWritable(String datasource, String operation) {
        if (registry.isReadonly(datasource)) {
            throw new SecurityException("数据源 [" + datasource + "] 配置为只读模式，不允许执行" + operation);
        }
    }

    private String firstKeyword(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? "UNKNOWN" : trimmed.substring(0, end).toUpperCase();
    }

    private void auditBefore(String operation, String datasource, String sql, boolean unsafeScope) {
        log.warn("SQL_AUDIT phase=before operation={} datasource={} sqlHash={} unsafeScope={}",
                operation, datasource, sqlHash(sql), unsafeScope);
    }

    private void auditAfter(String operation, String datasource, String sql,
                            int affectedRows, long durationMs, boolean success) {
        log.warn("SQL_AUDIT phase=after operation={} datasource={} sqlHash={} affectedRows={} durationMs={} success={}",
                operation, datasource, sqlHash(sql), affectedRows, durationMs, success);
    }

    private String sqlHash(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 执行写操作的统一流程：参数校验 → 操作类型校验 → 安全校验 → 警告生成 → JDBC 执行。
     *
     * @param datasource   数据源名称
     * @param sql          原始 SQL
     * @param expectedType 期望的操作类型（INSERT / UPDATE / DELETE）
     */
    private String doWrite(String datasource, String sql, String expectedType) {
        // 1. 参数校验
        if (sql == null || sql.trim().isEmpty()) {
            return "参数错误：SQL 不能为空。";
        }

        // 2. 验证 SQL 类型是否匹配（如 executeInsert 只允许 INSERT）
        String trimmedSql = sql.trim().toUpperCase();
        if (!trimmedSql.startsWith(expectedType)) {
            return "操作类型不匹配：当前工具仅支持 " + expectedType + " 语句，请使用对应的工具执行其他类型操作。";
        }

        // 3. 安全验证（白名单 + 黑名单 + 多语句注入检测）
        try {
            securityValidator.validateWrite(sql);
        } catch (SecurityException se) {
            log.warn("写 SQL 被安全策略拒绝：sqlHash={}", sqlHash(sql), se);
            return "操作被安全策略拒绝：" + safeMessage(se);
        }

        // 检查数据源是否为只读（Service 层早期友好拒绝，双重保险：Adapter 层也会硬拦截并抛 SecurityException）
        String resolvedName = (datasource == null || datasource.trim().isEmpty()) ? registry.getDefaultName() : datasource;
        if (registry.isReadonly(resolvedName)) {
            return "操作被拒绝：数据源 [" + resolvedName + "] 配置为只读模式，不允许执行写操作。";
        }

        // 4. 构建警告信息：UPDATE / DELETE 缺少 WHERE 时给出醒目提示
        StringBuilder result = new StringBuilder();
        if ("DELETE".equals(expectedType) && !securityValidator.hasWhereClause(sql)) {
            result.append("⚠️ 警告：DELETE 语句未包含 WHERE 条件，将删除全表数据！\n\n");
        }
        if ("UPDATE".equals(expectedType) && !securityValidator.hasWhereClause(sql)) {
            result.append("⚠️ 警告：UPDATE 语句未包含 WHERE 条件，将更新全表数据！\n\n");
        }

        // 5. 路由到对应数据源并执行
        try {
            DatabaseAdapter adapter = resolveAdapter(datasource);
            long startTime = System.currentTimeMillis();
            int affectedRows = adapter.executeUpdate(sql);
            long elapsed = System.currentTimeMillis() - startTime;

            result.append("执行成功。\n");
            result.append("- 操作类型：").append(expectedType).append("\n");
            result.append("- 数据源：").append(displayDatasource(datasource)).append("\n");
            result.append("- 影响行数：").append(affectedRows).append("\n");
            result.append("- 执行耗时：").append(elapsed).append("ms\n");
            return result.toString();
        } catch (IllegalArgumentException iae) {
            return "数据源不存在：" + safeMessage(iae);
        } catch (SecurityException se) {
            // Adapter 层只读硬拦截或二次安全校验可能抛出 SecurityException
            log.warn("写 SQL 被安全策略拒绝：sqlHash={}", sqlHash(sql), se);
            return "操作被安全策略拒绝：" + safeMessage(se);
        } catch (Exception e) {
            log.error("{} 执行失败：datasource={}, sqlHash={}",
                    expectedType, datasource, sqlHash(sql), e);
            if (ConnectionError.isConnectionFailure(e)) {
                return ConnectionError.describe(e, displayDatasource(datasource));
            }
            return expectedType + " 执行失败：" + safeMessage(e);
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

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
