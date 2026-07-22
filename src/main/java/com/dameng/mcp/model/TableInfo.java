package com.dameng.mcp.model;

import lombok.Data;

/**
 * 表信息
 */
@Data
public class TableInfo {

    private String schemaName;

    private String tableName;

    /**
     * 表注释
     */
    private String comments;
}
