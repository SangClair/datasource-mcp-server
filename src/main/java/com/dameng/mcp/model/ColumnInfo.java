package com.dameng.mcp.model;

import lombok.Data;

/**
 * 列信息
 */
@Data
public class ColumnInfo {

    private String columnName;

    private String dataType;

    private Integer columnSize;

    private Integer decimalDigits;

    private boolean nullable;

    private String defaultValue;

    /**
     * 列注释
     */
    private String comments;
}
