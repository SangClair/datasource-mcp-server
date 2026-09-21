package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DatabaseAdapterFactory;
import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchClientFactory;
import com.dameng.mcp.adapter.kafka.KafkaClientFactory;
import com.dameng.mcp.adapter.kafka.KafkaConnection;
import com.dameng.mcp.adapter.redis.RedisClientFactory;
import com.dameng.mcp.config.DataSourcePersistence;
import com.dameng.mcp.config.DataSourceProperties;
import com.dameng.mcp.model.DataSourceInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceManagerTest {

    @Test
    void unavailableDynamicDatasourceRemainsVisibleEditableAndDeletable() {
        Fixture fixture = fixture();
        DataSourceProperties.DataSourceItem kafka = kafkaConfig();

        fixture.manager.recordUnavailableDynamic(kafka, new RuntimeException("Kafka 连接失败: authentication failed"));

        DataSourceInfo info = fixture.manager.list().get(0);
        assertThat(info.getName()).isEqualTo("secure-kafka");
        assertThat(info.isAvailable()).isFalse();
        assertThat(info.getError()).contains("authentication failed");
        assertThat(fixture.manager.getForEdit("secure-kafka").getSecurityProtocol()).isEqualTo("SASL_SSL");

        fixture.manager.remove("secure-kafka");

        assertThat(fixture.manager.list()).isEmpty();
        verify(fixture.persistence).saveAll(List.of());
    }

    @Test
    void correctingUnavailableKafkaRegistersItWithoutDroppingPersistedConfiguration() {
        Fixture fixture = fixture();
        DataSourceProperties.DataSourceItem oldConfig = kafkaConfig();
        oldConfig.setPassword("existing-secret");
        fixture.manager.recordUnavailableDynamic(oldConfig, new RuntimeException("PLAINTEXT connection failed"));

        DataSourceProperties.DataSourceItem corrected = kafkaConfig();
        corrected.setPassword("");
        corrected.setSaslMechanism("SCRAM-SHA-512");
        when(fixture.kafkaFactory.create(corrected)).thenReturn(mock(KafkaConnection.class));

        fixture.manager.update(corrected);

        DataSourceInfo info = fixture.manager.list().get(0);
        assertThat(info.isAvailable()).isTrue();
        assertThat(fixture.registry.getConfig("secure-kafka").getPassword()).isEqualTo("existing-secret");
        assertThat(fixture.registry.getConfig("secure-kafka").getSaslMechanism()).isEqualTo("SCRAM-SHA-512");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DataSourceProperties.DataSourceItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(fixture.persistence).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(DataSourceProperties.DataSourceItem::getName)
                .isEqualTo("secure-kafka");
    }

    @Test
    void savingAnotherDatasourcePreservesUnavailableConfigurations() {
        Fixture fixture = fixture();
        DataSourceProperties.DataSourceItem unavailable = kafkaConfig();
        fixture.manager.recordUnavailableDynamic(unavailable, new RuntimeException("connection failed"));

        DataSourceProperties.DataSourceItem online = kafkaConfig();
        online.setName("online-kafka");
        when(fixture.kafkaFactory.create(online)).thenReturn(mock(KafkaConnection.class));
        reset(fixture.persistence);

        fixture.manager.add(online);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DataSourceProperties.DataSourceItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(fixture.persistence).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(DataSourceProperties.DataSourceItem::getName)
                .containsExactlyInAnyOrder("secure-kafka", "online-kafka");
    }

    private Fixture fixture() {
        DatabaseAdapterFactory databaseFactory = mock(DatabaseAdapterFactory.class);
        ElasticsearchClientFactory esFactory = mock(ElasticsearchClientFactory.class);
        RedisClientFactory redisFactory = mock(RedisClientFactory.class);
        KafkaClientFactory kafkaFactory = mock(KafkaClientFactory.class);
        DataSourceRegistry registry = new DataSourceRegistry();
        DataSourcePersistence persistence = mock(DataSourcePersistence.class);
        DataSourceManager manager = new DataSourceManager(
                databaseFactory, esFactory, redisFactory, kafkaFactory, registry, persistence);
        return new Fixture(manager, kafkaFactory, registry, persistence);
    }

    private DataSourceProperties.DataSourceItem kafkaConfig() {
        DataSourceProperties.DataSourceItem item = new DataSourceProperties.DataSourceItem();
        item.setName("secure-kafka");
        item.setDescription("Secure Kafka");
        item.setType("kafka");
        item.setUrl("broker.example:9093");
        item.setUsername("user");
        item.setReadonly(true);
        item.setDynamic(true);
        item.setSecurityProtocol("SASL_SSL");
        item.setSaslMechanism("SCRAM-SHA-256");
        return item;
    }

    private record Fixture(DataSourceManager manager,
                           KafkaClientFactory kafkaFactory,
                           DataSourceRegistry registry,
                           DataSourcePersistence persistence) {
    }
}
