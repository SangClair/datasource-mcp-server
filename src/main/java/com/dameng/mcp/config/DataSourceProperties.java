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
     * 动态数据源持久化 H2 数据库文件基础路径（http 模式下 Web 新增的数据源写入此 H2 库）。
     * <p>
     * 为不含扩展名的文件路径，H2 会自动生成 {@code <path>.mv.db}。
     * 为空时默认位于 {@code ${user.home}/.dameng-mcp/datasources}。
     * </p>
     */
    private String dynamicDatasourceH2;

    /**
     * 持久化 H2 数据库用户名，默认 {@code sa}。
     */
    private String dynamicDatasourceH2Username = "sa";

    /**
     * 持久化 H2 数据库密码，默认空。
     * <p>
     * 注意：H2 文件库在首次创建时固化账户密码，若已存在的库使用了其它密码，
     * 修改此项后需保持与建库时一致，否则连接会报「Wrong user name or password」。
     * </p>
     */
    private String dynamicDatasourceH2Password = "";

    /**
     * 是否为动态数据源 H2 文件库启用 AUTO_SERVER 多进程共享模式，默认启用。
     * 测试或明确只有单进程访问时可关闭。
     */
    private boolean dynamicDatasourceH2AutoServer = true;

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
         * 数据库类型：dameng / oracle / mysql / elasticsearch / redis / kafka
         */
        private String type;

        /**
         * JDBC 连接 URL（关系型数据库）。
         * <p>
         * Elasticsearch 复用此字段作为节点地址（如 {@code http://host:9200}，
         * 多节点以逗号分隔）；Redis 可选填 {@code redis://host:port} 代替 host/port。
         * </p>
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

        // ---------- 连接失败熔断参数（防止密码错误时反复重试导致账号锁定）----------

        /**
         * 获取物理连接连续失败的最大重试次数。达到上限后连接池进入熔断状态，
         * 暂停获取新连接一段时间，避免用错误的账号密码反复连接数据库。
         */
        private int connectionErrorRetryAttempts = 3;

        /**
         * 获取连接连续失败达到 {@link #connectionErrorRetryAttempts} 次后是否熔断。
         * true 表示熔断，此后一段时间内不再发起新的连接，防止账号被锁定。
         */
        private boolean breakAfterAcquireFailure = true;

        /**
         * 熔断后暂停多久（毫秒）再允许尝试获取连接。
         */
        private long timeBetweenConnectErrorMillis = 3000;

        // ---------- Elasticsearch / Redis 专用可选字段 ----------

        /**
         * 主机地址（Redis 必填，除非使用 {@code url} 形式）。
         */
        private String host;

        /**
         * 端口（Redis，默认 6379）。为 null 时使用默认值。
         */
        private Integer port;

        /**
         * Redis 数据库索引（默认 0）。为 null 时使用默认值。
         */
        private Integer database;

        /**
         * Elasticsearch API Key（可选，与 username/password 二选一）。
         */
        private String apiKey;

        // ---------- Kafka 专用可选字段 ----------

        /**
         * Kafka 安全协议：PLAINTEXT / SASL_PLAINTEXT / SASL_SSL / SSL（默认 PLAINTEXT，为空时不设置）。
         */
        private String securityProtocol;

        /**
         * Kafka SASL 机制：PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512（配合 username/password 使用）。
         */
        private String saslMechanism;
    }
}
