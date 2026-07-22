package com.dameng.mcp.model;

import lombok.Data;

/**
 * 列统计信息
 */
@Data
public class ColumnStatistics {

    private String schemaName;

    private String tableName;

    private String columnName;

    /**
     * 去重数
     */
    private long distinctCount;

    /**
     * 空值数
     */
    private long nullCount;

    private String minValue;

    private String maxValue;
}
