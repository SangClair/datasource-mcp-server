package com.dameng.mcp.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 查询结果
 */
@Data
public class QueryResult {

    /**
     * 列名列表
     */
    private List<String> columns;

    /**
     * 数据行
     */
    private List<Map<String, Object>> rows;

    /**
     * 返回行数
     */
    private int totalRows;

    /**
     * 执行耗时
     */
    private String executionTime;
}
