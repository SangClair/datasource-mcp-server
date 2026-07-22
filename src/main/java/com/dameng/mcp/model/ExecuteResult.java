package com.dameng.mcp.model;

import lombok.Data;

/**
 * 写操作（INSERT/UPDATE/DELETE）执行结果。
 */
@Data
public class ExecuteResult {

    /**
     * 执行的数据源名称
     */
    private String datasource;

    /**
     * 执行的 SQL（已通过安全校验）
     */
    private String sql;

    /**
     * 影响行数
     */
    private int affectedRows;

    /**
     * 执行耗时（毫秒），格式如 "12ms"
     */
    private String executionTime;

    /**
     * 警告信息（如 DELETE 无 WHERE 条件等）
     */
    private String warning;
}
