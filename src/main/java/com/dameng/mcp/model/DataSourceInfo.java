package com.dameng.mcp.model;

import lombok.Data;

/**
 * 数据源元信息，用于对外列出已配置的数据源。
 */
@Data
public class DataSourceInfo {

    /**
     * 数据源名称
     */
    private String name;

    /**
     * 用途描述
     */
    private String description;

    /**
     * 数据库类型 (dameng/oracle/mysql)
     */
    private String type;

    /**
     * 是否只读
     */
    private boolean readonly;

    /**
     * 是否为运行时动态添加的数据源（true=可通过 Web 删除，false=application.yml 内置）
     */
    private boolean dynamic;
}
