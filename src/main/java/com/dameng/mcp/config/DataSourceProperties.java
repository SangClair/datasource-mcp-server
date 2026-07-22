package com.dameng.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 多数据源配置属性，绑定 application.yml 中 {@code mcp.datasources} 配置。
 */
@Data
@ConfigurationProperties(prefix = "mcp")
public class DataSourceProperties {

    /**
     * 已配置的数据源列表
     */
    private List<DataSourceItem> datasources = new ArrayList<>();

    /**
     * 动态数据源持久化文件路径（http 模式下 Web 新增的数据源写入此文件）。
     */
    private String dynamicDatasourceFile;

    /**
     * Web 管理接口相关配置
     */
    private Web web = new Web();

    /**
     * Web 管理接口配置
     */
    @Data
    public static class Web {
        /**
         * 访问令牌，非空时 REST API 需携带请求头 {@code X-Access-Token} 校验。
         */
        private String accessToken = "";
    }

    /**
     * 单个数据源配置项
     */
    @Data
    public static class DataSourceItem {

        /**
         * 数据源名称（全局唯一，用于 MCP 调用时定位）
         */
        private String name;

        /**
         * 数据源用途描述
         */
        private String description;

        /**
         * 数据库类型：dameng / oracle / mysql
         */
        private String type;

        /**
         * JDBC 连接 URL
         */
        private String url;

        /**
         * 数据库用户名
         */
        private String username;

        /**
         * 数据库密码
         */
        private String password;

        /**
         * 是否为只读数据源，默认 false。
         * 只读数据源不允许执行 INSERT/UPDATE/DELETE 等写操作。
         */
        private boolean readonly = false;

        /**
         * 是否为运行时动态添加的数据源（区别于 application.yml 内置数据源）。
         * 仅动态数据源允许通过 Web 接口删除，且会被持久化到 JSON 文件。
         */
        private boolean dynamic = false;

        // ---------- Druid 连接池参数（可选覆盖） ----------

        /**
         * 初始化连接数
         */
        private int initialSize = 5;

        /**
         * 最小空闲连接数
         */
        private int minIdle = 5;

        /**
         * 最大活跃连接数
         */
        private int maxActive = 20;

        /**
         * 获取连接最大等待时间（毫秒）
         */
        private long maxWait = 60000;
    }
}
