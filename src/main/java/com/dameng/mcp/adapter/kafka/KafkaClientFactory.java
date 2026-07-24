package com.dameng.mcp.adapter.kafka;

import com.dameng.mcp.config.DataSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Kafka 客户端工厂。
 * <p>
 * 根据数据源配置构建 {@link AdminClient} 并封装为 {@link KafkaConnection}，
 * 同时预置抓取消息所需的 Consumer 基础属性。{@code url} 作为 bootstrap.servers，
 * 支持逗号分隔多节点；可选 SASL（PLAIN / SCRAM）认证。
 * </p>
 */
@Slf4j
@Component
public class KafkaClientFactory {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5000L;

    /**
     * 创建 Kafka 连接封装。内部会做一次 clusterId 探测以校验连通性。
     */
    public KafkaConnection create(DataSourceProperties.DataSourceItem config) {
        long timeout = resolveTimeout(config);
        AdminClient admin = AdminClient.create(buildAdminProps(config, timeout));
        KafkaConnection connection = new KafkaConnection(admin, buildConsumerBaseProps(config, timeout),
                timeout, config.isReadonly());
        try {
            String clusterId = connection.ping();
            log.info("Kafka 数据源 [{}] 初始化成功: bootstrap={}, clusterId={}",
                    config.getName(), config.getUrl(), clusterId);
        } catch (Exception e) {
            connection.close();
            throw new RuntimeException("Kafka 连接失败: " + e.getMessage(), e);
        }
        return connection;
    }

    /**
     * 仅测试连接（不复用），调用方无需关闭：本方法内部创建并关闭连接。
     */
    public void testConnection(DataSourceProperties.DataSourceItem config) {
        long timeout = resolveTimeout(config);
        AdminClient admin = AdminClient.create(buildAdminProps(config, timeout));
        KafkaConnection connection = new KafkaConnection(admin, buildConsumerBaseProps(config, timeout),
                timeout, config.isReadonly());
        try {
            connection.ping();
        } catch (Exception e) {
            throw new RuntimeException("Kafka 连接失败: " + e.getMessage(), e);
        } finally {
            connection.close();
        }
    }

    /**
     * 构建 AdminClient 属性。
     */
    private Properties buildAdminProps(DataSourceProperties.DataSourceItem config, long timeout) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, resolveBootstrap(config));
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout);
        applySecurity(props, config);
        return props;
    }

    /**
     * 构建抓取消息用的 Consumer 基础属性（group.id 在每次新建 Consumer 时随机注入）。
     */
    private Properties buildConsumerBaseProps(DataSourceProperties.DataSourceItem config, long timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolveBootstrap(config));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout);
        applySecurity(props, config);
        return props;
    }

    /**
     * 应用安全协议与 SASL 认证配置（仅在 securityProtocol 非空时生效）。
     */
    private void applySecurity(Properties props, DataSourceProperties.DataSourceItem config) {
        if (!StringUtils.hasText(config.getSecurityProtocol())) {
            return;
        }
        props.put("security.protocol", config.getSecurityProtocol().trim());
        if (!StringUtils.hasText(config.getSaslMechanism())) {
            return;
        }
        String mechanism = config.getSaslMechanism().trim();
        props.put("sasl.mechanism", mechanism);
        String username = config.getUsername() == null ? "" : config.getUsername();
        String password = config.getPassword() == null ? "" : config.getPassword();
        String loginModule = mechanism.startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        String jaas = String.format(
                "%s required username=\"%s\" password=\"%s\";", loginModule, username, password);
        props.put("sasl.jaas.config", jaas);
    }

    /**
     * bootstrap.servers 取自 url，去除首尾空白。
     */
    private String resolveBootstrap(DataSourceProperties.DataSourceItem config) {
        if (!StringUtils.hasText(config.getUrl())) {
            throw new IllegalArgumentException("Kafka bootstrap.servers(url) 不能为空");
        }
        return config.getUrl().trim();
    }

    /**
     * 超时取 maxWait，非法时回退默认值。
     */
    private long resolveTimeout(DataSourceProperties.DataSourceItem config) {
        return config.getMaxWait() > 0 ? config.getMaxWait() : DEFAULT_TIMEOUT_MILLIS;
    }
}
