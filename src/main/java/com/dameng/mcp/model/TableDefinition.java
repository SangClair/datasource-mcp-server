package com.dameng.mcp.model;

import lombok.Data;

import java.util.List;

/**
 * 表定义（包含列信息）
 */
@Data
public class TableDefinition {

    private String schemaName;

    private String tableName;

    private String comments;

    private List<ColumnInfo> columns;
}
