package com.dameng.mcp.model;

import lombok.Data;

/**
 * 表统计信息
 */
@Data
public class TableStatistics {

    private String schemaName;

    private String tableName;

    private long rowCount;

    /**
     * 表大小（如有）
     */
    private String tableSize;
}
