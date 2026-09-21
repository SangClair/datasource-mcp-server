package com.dameng.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态数据源持久化组件。
 * <p>
 * 将运行时通过 Web 接口新增的数据源配置持久化到嵌入式 H2 数据库，
 * 应用重启时自动加载并重新注册，保证配置不丢失。
 * H2 数据库文件基础路径由 {@code mcp.dynamic-datasource-h2} 配置，默认位于
 * {@code ${user.home}/.dameng-mcp/datasources}（H2 实际生成 {@code datasources.mv.db}）。
 * </p>
 * <p>
 * 通过独立的 JDBC 连接自行管理，不参与 Spring 的数据源自动装配，
 * 与 MCP 业务数据源（Druid/ES/Redis）完全隔离。所有读写方法均加锁保证线程安全。
 * </p>
 */
@Slf4j
@Component
public class DataSourcePersistence {

    private static final String TABLE = "DYNAMIC_DATASOURCE";

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                    "NAME VARCHAR(255) PRIMARY KEY, " +
                    "DESCRIPTION VARCHAR(1024), " +
                    "TYPE VARCHAR(50), " +
                    "URL VARCHAR(2048), " +
                    "USERNAME VARCHAR(255), " +
                    "PASSWORD VARCHAR(1024), " +
                    "READONLY BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "INITIAL_SIZE INT NOT NULL DEFAULT 5, " +
                    "MIN_IDLE INT NOT NULL DEFAULT 5, " +
                    "MAX_ACTIVE INT NOT NULL DEFAULT 20, " +
                    "MAX_WAIT BIGINT NOT NULL DEFAULT 60000, " +
                    "HOST VARCHAR(255), " +
                    "PORT INT, " +
                    "DATABASE_INDEX INT, " +
                    "API_KEY VARCHAR(2048), " +
                    "SECURITY_PROTOCOL VARCHAR(50), " +
                    "SASL_MECHANISM VARCHAR(50)" +
                    ")";

    /** 旧版 H2 表的向后兼容迁移，可重复执行。 */
    private static final List<String> MIGRATIONS = List.of(
            "ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS SECURITY_PROTOCOL VARCHAR(50)",
            "ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS SASL_MECHANISM VARCHAR(50)"
    );

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE + " (NAME, DESCRIPTION, TYPE, URL, USERNAME, PASSWORD, READONLY, " +
                    "INITIAL_SIZE, MIN_IDLE, MAX_ACTIVE, MAX_WAIT, HOST, PORT, DATABASE_INDEX, API_KEY, " +
                    "SECURITY_PROTOCOL, SASL_MECHANISM) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_SQL = "SELECT * FROM " + TABLE;

    /** H2 JDBC 连接地址（file 模式，AUTO_SERVER 允许多连接/多进程共享）。 */
    private final String jdbcUrl;
    /** H2 数据库用户名。 */
    private final String username;
    /** H2 数据库密码。 */
    private final String password;
    /** H2 数据库文件基础路径（用于日志/展示）。 */
    private final Path basePath;

    public DataSourcePersistence(DataSourceProperties properties) {
        String configured = properties.getDynamicDatasourceH2();
        if (StringUtils.hasText(configured)) {
            this.basePath = Paths.get(configured);
        } else {
            this.basePath = Paths.get(System.getProperty("user.home"), ".dameng-mcp", "datasources");
        }
        this.jdbcUrl = "jdbc:h2:file:" + basePath.toAbsolutePath()
                + (properties.isDynamicDatasourceH2AutoServer() ? ";AUTO_SERVER=TRUE" : "");
        this.username = properties.getDynamicDatasourceH2Username();
        this.password = properties.getDynamicDatasourceH2Password() == null
                ? "" : properties.getDynamicDatasourceH2Password();
        initSchema();
    }

    /**
     * 确保 H2 数据库文件所在目录存在，并初始化表结构。
     */
    private void initSchema() {
        try {
            Path parent = basePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new RuntimeException("创建 H2 持久化目录失败: " + basePath.getParent(), e);
        }
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
            for (String migration : MIGRATIONS) {
                stmt.execute(migration);
            }
        } catch (SQLException e) {
            log.error("初始化动态数据源持久化表失败: {}", jdbcUrl, e);
            throw new RuntimeException("初始化 H2 持久化表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载已持久化的动态数据源配置。表为空时返回空列表。
     */
    public synchronized List<DataSourceProperties.DataSourceItem> load() {
        List<DataSourceProperties.DataSourceItem> items = new ArrayList<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_SQL)) {
            while (rs.next()) {
                items.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("加载动态数据源配置失败: {}", jdbcUrl, e);
            return new ArrayList<>();
        }
        return items;
    }

    /**
     * 全量保存动态数据源配置到 H2 数据库（先清空再插入，单事务保证原子性）。
     */
    public synchronized void saveAll(List<DataSourceProperties.DataSourceItem> items) {
        List<DataSourceProperties.DataSourceItem> list = items == null ? new ArrayList<>() : items;
        Connection conn = null;
        try {
            conn = openConnection();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM " + TABLE);
            }
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (DataSourceProperties.DataSourceItem item : list) {
                    bindItem(ps, item);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            log.info("已持久化 {} 个动态数据源到 H2: {}", list.size(), jdbcUrl);
        } catch (SQLException e) {
            rollbackQuietly(conn);
            log.error("持久化动态数据源配置失败: {}", jdbcUrl, e);
            throw new RuntimeException("持久化数据源配置失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 返回 H2 数据库文件基础路径（用于日志/展示）。
     */
    public Path getStoragePath() {
        return basePath;
    }

    /* ====================== 内部工具方法 ====================== */

    private Connection openConnection() throws SQLException {
        // H2 驱动在类路径上，DriverManager 可直接加载；账号密码由配置提供（默认 sa/空）
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private DataSourceProperties.DataSourceItem mapRow(ResultSet rs) throws SQLException {
        DataSourceProperties.DataSourceItem item = new DataSourceProperties.DataSourceItem();
        item.setName(rs.getString("NAME"));
        item.setDescription(rs.getString("DESCRIPTION"));
        item.setType(rs.getString("TYPE"));
        item.setUrl(rs.getString("URL"));
        item.setUsername(rs.getString("USERNAME"));
        item.setPassword(rs.getString("PASSWORD"));
        item.setReadonly(rs.getBoolean("READONLY"));
        item.setInitialSize(rs.getInt("INITIAL_SIZE"));
        item.setMinIdle(rs.getInt("MIN_IDLE"));
        item.setMaxActive(rs.getInt("MAX_ACTIVE"));
        item.setMaxWait(rs.getLong("MAX_WAIT"));
        item.setHost(rs.getString("HOST"));
        item.setPort(getNullableInt(rs, "PORT"));
        item.setDatabase(getNullableInt(rs, "DATABASE_INDEX"));
        item.setApiKey(rs.getString("API_KEY"));
        item.setSecurityProtocol(rs.getString("SECURITY_PROTOCOL"));
        item.setSaslMechanism(rs.getString("SASL_MECHANISM"));
        // 从持久化加载的数据源统一标记为动态
        item.setDynamic(true);
        return item;
    }

    private void bindItem(PreparedStatement ps, DataSourceProperties.DataSourceItem item) throws SQLException {
        ps.setString(1, item.getName());
        ps.setString(2, item.getDescription());
        ps.setString(3, item.getType());
        ps.setString(4, item.getUrl());
        ps.setString(5, item.getUsername());
        ps.setString(6, item.getPassword());
        ps.setBoolean(7, item.isReadonly());
        ps.setInt(8, item.getInitialSize());
        ps.setInt(9, item.getMinIdle());
        ps.setInt(10, item.getMaxActive());
        ps.setLong(11, item.getMaxWait());
        ps.setString(12, item.getHost());
        setNullableInt(ps, 13, item.getPort());
        setNullableInt(ps, 14, item.getDatabase());
        ps.setString(15, item.getApiKey());
        ps.setString(16, item.getSecurityProtocol());
        ps.setString(17, item.getSaslMechanism());
    }

    private Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 忽略回滚异常
            }
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
