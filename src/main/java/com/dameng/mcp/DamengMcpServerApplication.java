package com.dameng.mcp;

import com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

/**
 * 达梦 MCP Server 应用启动类。
 * <p>
 * 多数据源架构下，数据源由 {@code DataSourceConfig} + {@code DatabaseAdapterFactory}
 * 手动创建并注册到 {@code DataSourceRegistry}，因此需要排除 Spring Boot 默认的
 * 单数据源自动装配，避免启动时因缺少 spring.datasource.url 而报错。
 * </p>
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        DruidDataSourceAutoConfigure.class
})
public class DamengMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DamengMcpServerApplication.class, args);
    }
}
