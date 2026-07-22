package com.dameng.mcp.adapter;

import com.alibaba.druid.pool.DruidDataSource;
import com.dameng.mcp.adapter.dameng.DamengDatabaseAdapter;
import com.dameng.mcp.adapter.dameng.DamengDialect;
import com.dameng.mcp.adapter.mysql.MysqlDatabaseAdapter;
import com.dameng.mcp.adapter.mysql.MysqlDialect;
import com.dameng.mcp.adapter.oracle.OracleDatabaseAdapter;
import com.dameng.mcp.adapter.oracle.OracleDialect;
import com.dameng.mcp.config.DataSourceProperties;
import com.dameng.mcp.security.SqlSecurityValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 数据库适配器工厂。
 * <p>
 * 根据配置项的数据库类型创建对应的 {@link DataSource}、{@link DatabaseDialect}
 * 与 {@link DatabaseAdapter}，所有 Adapter / Dialect 的实例都由此工厂手动构建，
 * 不再交由 Spring 容器扫描，便于在多数据源场景下实现「同一类型可注册多个数据源」。
 * </p>
 */
@Slf4j
@Component
public class DatabaseAdapterFactory {

    private final SqlSecurityValidator securityValidator;

    public DatabaseAdapterFactory(SqlSecurityValidator securityValidator) {
        this.securityValidator = securityValidator;
    }

    /**
     * 数据源注册结果：包含适配器与其底层数据源。
     */
    public record Registration(DatabaseAdapter adapter, DataSource dataSource) {
    }

    /**
     * 根据配置创建对应类型的数据库适配器及其底层数据源。
     */
    public Registration create(DataSourceProperties.DataSourceItem config) {
        DataSource dataSource = createDataSource(config);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        String type = config.getType().toLowerCase();
        DatabaseAdapter adapter;
        switch (type) {
            case "dameng":
                adapter = new DamengDatabaseAdapter(jdbcTemplate, new DamengDialect(), securityValidator, config.isReadonly());
                break;
            case "oracle":
                adapter = new OracleDatabaseAdapter(jdbcTemplate, new OracleDialect(), securityValidator, config.isReadonly());
                break;
            case "mysql":
                adapter = new MysqlDatabaseAdapter(jdbcTemplate, new MysqlDialect(), securityValidator, config.isReadonly());
                break;
            default:
                // 类型非法时关闭已创建的数据源，避免连接泄漏
                closeQuietly(dataSource);
                throw new IllegalArgumentException("不支持的数据库类型: " + type + "。支持: dameng, oracle, mysql");
        }
        return new Registration(adapter, dataSource);
    }

    /**
     * 基于 Druid 创建数据源，并完成基本的连接池参数与有效性检测配置。
     * 该方法为 public，便于「测试连接」等场景复用（用完请自行关闭）。
     */
    public DataSource createDataSource(DataSourceProperties.DataSourceItem config) {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(config.getUrl());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPassword());
        ds.setDriverClassName(getDriverClassName(config.getType()));
        ds.setInitialSize(config.getInitialSize());
        ds.setMinIdle(config.getMinIdle());
        ds.setMaxActive(config.getMaxActive());
        ds.setMaxWait(config.getMaxWait());
        ds.setTestWhileIdle(true);
        ds.setValidationQuery(getValidationQuery(config.getType()));
        ds.setTimeBetweenEvictionRunsMillis(60000);

        // 只读数据源在 DataSource 层设置默认只读连接属性，作为 JDBC 驱动级别的第三重拦截。
        // 部分数据库驱动会据此拒绝写操作，不支持的驱动也不会报错。
        if (config.isReadonly()) {
            ds.setDefaultReadOnly(true);
        }

        try {
            ds.init();
            log.info("数据源 [{}] 初始化成功: type={}, url={}", config.getName(), config.getType(), config.getUrl());
        } catch (SQLException e) {
            log.error("数据源 [{}] 初始化失败: {}", config.getName(), e.getMessage());
            throw new RuntimeException("数据源初始化失败: " + config.getName(), e);
        }
        return ds;
    }

    /**
     * 静默关闭数据源，忽略异常。
     */
    private void closeQuietly(DataSource dataSource) {
        if (dataSource instanceof DruidDataSource) {
            try {
                ((DruidDataSource) dataSource).close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 根据数据库类型获取 JDBC 驱动类名
     */
    private String getDriverClassName(String type) {
        switch (type.toLowerCase()) {
            case "dameng":
                return "dm.jdbc.driver.DmDriver";
            case "oracle":
                return "oracle.jdbc.OracleDriver";
            case "mysql":
                return "com.mysql.cj.jdbc.Driver";
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + type);
        }
    }

    /**
     * 根据数据库类型获取连接有效性检测 SQL
     */
    private String getValidationQuery(String type) {
        switch (type.toLowerCase()) {
            case "dameng":
                return "SELECT 1";
            case "oracle":
                return "SELECT 1 FROM DUAL";
            case "mysql":
                return "SELECT 1";
            default:
                return "SELECT 1";
        }
    }
}
