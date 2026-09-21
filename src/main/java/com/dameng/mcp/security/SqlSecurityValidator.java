package com.dameng.mcp.security;

import com.dameng.mcp.adapter.DatabaseDialect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全验证器
 * <p>
 * 整个服务的安全核心，负责对外部传入的 SQL 进行严格的安全审查。
 * 采用「白名单 + 黑名单」双重验证策略，仅允许只读型 SQL 通过：
 * <ol>
 *   <li>剥离注释，避免攻击者通过注释绕过关键字检测；</li>
 *   <li>白名单：必须以 SELECT / WITH / EXPLAIN / SHOW 开头；</li>
 *   <li>黑名单：禁止包含 DDL / DML 写入 / DCL / 危险操作关键字；</li>
 *   <li>多语句注入检测：剥离字符串字面量后禁止出现分号；</li>
 *   <li>子查询中的危险操作检测。</li>
 * </ol>
 */
@Slf4j
@Component
public class SqlSecurityValidator {

    /**
     * 允许的 SQL 起始关键字（白名单）
     */
    private static final Pattern ALLOWED_STATEMENT_PATTERN =
            Pattern.compile("^\\s*(SELECT|WITH|EXPLAIN|SHOW)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 写操作允许的起始关键字（白名单）：仅 INSERT / UPDATE / DELETE
     */
    private static final Pattern ALLOWED_WRITE_STATEMENT_PATTERN =
            Pattern.compile("^\\s*(INSERT|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 写操作场景下的黑名单：禁止 DDL / DCL / 危险操作。
     * 注意：此处不再禁止 INSERT/UPDATE/DELETE 自身。
     */
    private static final Pattern WRITE_BLACKLIST_KEYWORDS_PATTERN = Pattern.compile(
            "\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME|" +
                    "GRANT|REVOKE|" +
                    "EXEC|EXECUTE|CALL)\\b" +
                    "|\\bINTO\\s+OUTFILE\\b" +
                    "|\\bLOAD\\s+DATA\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * DDL / 通用 SQL 场景下的黑名单：仅禁止高危操作。
     * 允许 DDL（CREATE/ALTER/DROP/TRUNCATE/RENAME）及 DML 写入，
     * 但禁止存储过程执行、文件读写等高危操作。
     */
    private static final Pattern DDL_BLACKLIST_KEYWORDS_PATTERN = Pattern.compile(
            "\\b(EXEC|EXECUTE|CALL)\\b" +
                    "|\\bINTO\\s+OUTFILE\\b" +
                    "|\\bLOAD\\s+DATA\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * WHERE 子句检测：使用单词边界，避免与列名（如 WHEREVER）误判
     */
    private static final Pattern WHERE_CLAUSE_PATTERN =
            Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * DELETE 语句开头检测
     */
    private static final Pattern DELETE_STATEMENT_PATTERN =
            Pattern.compile("^\\s*DELETE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 禁止的关键字（黑名单），使用 \b 确保独立单词匹配，避免误判列名
     */
    private static final Pattern BLACKLIST_KEYWORDS_PATTERN = Pattern.compile(
            "\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME|" +
                    "INSERT|UPDATE|DELETE|MERGE|UPSERT|" +
                    "GRANT|REVOKE|" +
                    "EXEC|EXECUTE|CALL)\\b" +
                    "|\\bINTO\\s+OUTFILE\\b" +
                    "|\\bLOAD\\s+DATA\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 单行注释正则：-- 直至行尾
     */
    private static final Pattern LINE_COMMENT_PATTERN = Pattern.compile("--[^\\n\\r]*");

    /**
     * 多行注释正则：/ * ... * / （非贪婪匹配）
     */
    private static final Pattern BLOCK_COMMENT_PATTERN = Pattern.compile("/\\*[\\s\\S]*?\\*/");

    /**
     * 字符串字面量正则：单引号或双引号字符串（支持转义的双引号）
     */
    private static final Pattern STRING_LITERAL_PATTERN =
            Pattern.compile("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"");

    /**
     * 合法标识符（schema/table/column 名称）正则：仅允许字母、数字、下划线、点号
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_.]+$");

    /**
     * 验证 SQL 安全性。
     *
     * @param sql 待验证的 SQL
     * @return 验证通过的清理后 SQL（已剥离注释）
     * @throws SecurityException 验证失败时抛出
     */
    public String validate(String sql) {
        // 1. 空值检查
        if (sql == null || sql.trim().isEmpty()) {
            throw new SecurityException("SQL 不能为空");
        }

        // 2. 剥离注释，得到用于检测的「干净」SQL
        String cleanedSql = stripComments(sql).trim();
        if (cleanedSql.isEmpty()) {
            throw new SecurityException("SQL 在剥离注释后为空");
        }

        // 3. 白名单检查
        if (!ALLOWED_STATEMENT_PATTERN.matcher(cleanedSql).find()) {
            log.warn("SQL 安全检查失败：未通过白名单。SQL = {}", abbreviate(cleanedSql));
            throw new SecurityException("仅允许 SELECT / WITH / EXPLAIN / SHOW 语句");
        }

        // 4. 黑名单关键字检测
        Matcher blacklistMatcher = BLACKLIST_KEYWORDS_PATTERN.matcher(cleanedSql);
        if (blacklistMatcher.find()) {
            String keyword = blacklistMatcher.group();
            log.warn("SQL 安全检查失败：命中黑名单关键字 [{}]。SQL = {}", keyword, abbreviate(cleanedSql));
            throw new SecurityException("SQL 中包含禁止使用的关键字：" + keyword);
        }

        // 5. 多语句注入检测：剥离字符串字面量后禁止出现分号
        String literalStripped = stripStringLiterals(cleanedSql);
        // 允许末尾恰好一个分号（部分客户端会自动追加），但不能存在多个语句
        String trimmed = literalStripped.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains(";")) {
            log.warn("SQL 安全检查失败：检测到多语句注入。SQL = {}", abbreviate(cleanedSql));
            throw new SecurityException("禁止在 SQL 中使用分号执行多条语句");
        }

        // 6. 子查询中的危险操作再次检测（已在第 4 步全文匹配，这里保留显式日志便于审计）
        if (BLACKLIST_KEYWORDS_PATTERN.matcher(literalStripped).find()) {
            throw new SecurityException("SQL 子查询中包含禁止使用的关键字");
        }

        return cleanedSql;
    }

    /**
     * 验证写 SQL 安全性（INSERT / UPDATE / DELETE）。
     * <p>
     * 流程与 {@link #validate(String)} 类似，但白名单为写操作，黑名单中不再包含
     * INSERT/UPDATE/DELETE 自身（仍禁止 DDL/DCL/危险操作）。当 DELETE 语句不带
     * WHERE 条件时不抛异常，但会在日志中输出告警，由上层将告警附加到结果中返回。
     * </p>
     *
     * @param sql 待验证的写 SQL
     * @return 验证通过的清理后 SQL（已剥离注释）
     * @throws SecurityException 验证失败时抛出
     */
    public String validateWrite(String sql) {
        // 1. 空值检查
        if (sql == null || sql.trim().isEmpty()) {
            throw new SecurityException("SQL 不能为空");
        }

        // 2. 剥离注释
        String cleanedSql = stripComments(sql).trim();
        if (cleanedSql.isEmpty()) {
            throw new SecurityException("SQL 在剥离注释后为空");
        }

        // 3. 白名单：必须是 INSERT / UPDATE / DELETE
        if (!ALLOWED_WRITE_STATEMENT_PATTERN.matcher(cleanedSql).find()) {
            log.warn("写 SQL 安全检查失败：未通过白名单。SQL = {}", abbreviate(cleanedSql));
            throw new SecurityException("仅允许 INSERT / UPDATE / DELETE 语句");
        }

        // 4. 黑名单：DDL / DCL / 危险操作
        Matcher blacklistMatcher = WRITE_BLACKLIST_KEYWORDS_PATTERN.matcher(cleanedSql);
        if (blacklistMatcher.find()) {
            String keyword = blacklistMatcher.group();
            log.warn("写 SQL 安全检查失败：命中黑名单关键字 [{}]。SQL = {}", keyword, abbreviate(cleanedSql));
            throw new SecurityException("SQL 中包含禁止使用的关键字：" + keyword);
        }

        // 5. 多语句注入检测：剥离字符串字面量后禁止出现额外分号
        String literalStripped = stripStringLiterals(cleanedSql);
        String trimmed = literalStripped.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains(";")) {
            log.warn("写 SQL 安全检查失败：检测到多语句注入。SQL = {}", abbreviate(cleanedSql));
            throw new SecurityException("禁止在 SQL 中使用分号执行多条语句");
        }

        // 6. 子查询中的危险操作检测
        if (WRITE_BLACKLIST_KEYWORDS_PATTERN.matcher(literalStripped).find()) {
            throw new SecurityException("SQL 子查询中包含禁止使用的关键字");
        }

        // 7. DELETE 无 WHERE 仅打印告警，由上层决定是否拦截
        if (DELETE_STATEMENT_PATTERN.matcher(cleanedSql).find() && !hasWhereClause(cleanedSql)) {
            log.warn("DELETE 语句未包含 WHERE 条件，将影响全表数据；SQL 正文由上层审计日志以哈希记录");
        }

        return cleanedSql;
    }

    /**
     * 验证 DDL / 通用 SQL 安全性。
     * <p>
     * 允许 DDL（CREATE / ALTER / DROP / TRUNCATE / RENAME）以及 INSERT / UPDATE / DELETE
     * 等任意 SQL 语句，但黑名单中仍禁止存储过程执行（EXEC/EXECUTE/CALL）及文件读写
     * （INTO OUTFILE / LOAD DATA）等高危操作。多语句注入检测同样生效。
     * </p>
     * <p>此方法不检查只读，由上层 Service 负责只读拦截。</p>
     *
     * @param sql 待验证的 SQL
     * @return 验证通过的清理后 SQL（已剥离注释）
     * @throws SecurityException 验证失败时抛出
     */
    public String validateDdl(String sql) {
        // 1. 空值检查
        if (sql == null || sql.trim().isEmpty()) {
            throw new SecurityException("SQL 不能为空");
        }

        // 2. 剥离注释
        String cleanedSql = stripComments(sql).trim();
        if (cleanedSql.isEmpty()) {
            throw new SecurityException("SQL 在剥离注释后为空");
        }

        // 3. 黑名单：仅禁止高危操作（EXEC/CALL/文件读写），不限制 DDL/DML
        Matcher blacklistMatcher = DDL_BLACKLIST_KEYWORDS_PATTERN.matcher(cleanedSql);
        if (blacklistMatcher.find()) {
            String keyword = blacklistMatcher.group();
            log.warn("DDL SQL 安全检查失败：命中黑名单关键字 [{}]。SQL = {}", keyword, abbreviate(cleanedSql));
            throw new SecurityException("SQL 中包含禁止使用的关键字：" + keyword);
        }

        // 4. 多语句注入检测：剥离字符串字面量后禁止出现额外分号
        String literalStripped = stripStringLiterals(cleanedSql);
        String trimmed = literalStripped.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains(";")) {
            log.warn("DDL SQL 安全检查失败：检测到多语句注入。SQL = {}", abbreviate(cleanedSql));
            throw new SecurityException("禁止在 SQL 中使用分号执行多条语句");
        }

        // 5. 子查询中的危险操作检测
        if (DDL_BLACKLIST_KEYWORDS_PATTERN.matcher(literalStripped).find()) {
            throw new SecurityException("SQL 子查询中包含禁止使用的关键字");
        }

        return cleanedSql;
    }

    /**
     * 判断 SQL 是否包含 WHERE 子句。
     * 检测前会先剥离注释与字符串字面量，避免字符串中的 WHERE 干扰。
     */
    public boolean hasWhereClause(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        String cleaned = stripStringLiterals(stripComments(sql));
        return WHERE_CLAUSE_PATTERN.matcher(cleaned).find();
    }

    /**
     * 如果 SQL 没有显式的行数限制，则使用方言为其包装行数限制。
     *
     * @param sql      原始 SQL
     * @param maxRows  最大返回行数
     * @param dialect  数据库方言
     * @return 包装后的 SQL
     */
    public String applyRowLimit(String sql, int maxRows, DatabaseDialect dialect) {
        if (sql == null) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (maxRows <= 0) {
            return sql;
        }
        if (dialect == null) {
            throw new IllegalArgumentException("DatabaseDialect 不能为空");
        }

        String detection = stripStringLiterals(stripComments(sql)).toUpperCase();

        // 简单启发式：已显式包含 LIMIT / FETCH FIRST / ROWNUM 限制时不再包装
        boolean hasLimit = detection.matches("(?s).*\\bLIMIT\\s+\\d+.*")
                || detection.matches("(?s).*\\bFETCH\\s+(FIRST|NEXT)\\s+\\d+.*")
                || detection.matches("(?s).*\\bROWNUM\\s*<=?\\s*\\d+.*")
                || detection.matches("(?s).*\\bTOP\\s+\\d+.*");

        if (hasLimit) {
            return sql;
        }
        return dialect.wrapLimitQuery(sql, maxRows);
    }

    /**
     * 校验 schema/table/column 标识符的合法性。
     * 仅允许字母、数字、下划线、点号，避免拼接 SQL 时被注入。
     *
     * @param identifier 待校验的标识符
     * @param fieldName  字段名称（用于错误信息）
     */
    public void validateIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new SecurityException(fieldName + " 不能为空");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new SecurityException(fieldName + " 包含非法字符：" + identifier);
        }
    }

    /**
     * 移除 SQL 中的注释（单行 -- 与多行 /* ... *&#47;）
     */
    private String stripComments(String sql) {
        if (sql == null) {
            return "";
        }
        String result = BLOCK_COMMENT_PATTERN.matcher(sql).replaceAll(" ");
        result = LINE_COMMENT_PATTERN.matcher(result).replaceAll(" ");
        return result;
    }

    /**
     * 移除 SQL 中的字符串字面量。
     * 用于分号 / 关键字检测，避免字符串内容干扰判断。
     */
    private String stripStringLiterals(String sql) {
        if (sql == null) {
            return "";
        }
        return STRING_LITERAL_PATTERN.matcher(sql).replaceAll("''");
    }

    /**
     * 截断过长的 SQL，便于打印日志
     */
    private String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        int max = 200;
        return sql.length() <= max ? sql : sql.substring(0, max) + "...";
    }
}
