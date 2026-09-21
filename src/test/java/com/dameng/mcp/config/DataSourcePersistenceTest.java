package com.dameng.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourcePersistenceTest {

    private static final String LEGACY_DDL =
            "CREATE TABLE DYNAMIC_DATASOURCE (" +
                    "NAME VARCHAR(255) PRIMARY KEY, DESCRIPTION VARCHAR(1024), TYPE VARCHAR(50), " +
                    "URL VARCHAR(2048), USERNAME VARCHAR(255), PASSWORD VARCHAR(1024), " +
                    "READONLY BOOLEAN NOT NULL DEFAULT FALSE, INITIAL_SIZE INT NOT NULL DEFAULT 5, " +
                    "MIN_IDLE INT NOT NULL DEFAULT 5, MAX_ACTIVE INT NOT NULL DEFAULT 20, " +
                    "MAX_WAIT BIGINT NOT NULL DEFAULT 60000, HOST VARCHAR(255), PORT INT, " +
                    "DATABASE_INDEX INT, API_KEY VARCHAR(2048))";

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyTableAndPreservesKafkaSecuritySettings() throws Exception {
        Path storage = tempDir.resolve("datasources");
        String jdbcUrl = "jdbc:h2:file:" + storage.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_DDL);
        }

        DataSourceProperties properties = new DataSourceProperties();
        properties.setDynamicDatasourceH2(storage.toString());
        properties.setDynamicDatasourceH2AutoServer(false);
        DataSourcePersistence persistence = new DataSourcePersistence(properties);

        DataSourceProperties.DataSourceItem kafka = new DataSourceProperties.DataSourceItem();
        kafka.setName("secure-kafka");
        kafka.setType("kafka");
        kafka.setUrl("broker.example:9093");
        kafka.setReadonly(true);
        kafka.setSecurityProtocol("SASL_SSL");
        kafka.setSaslMechanism("SCRAM-SHA-256");
        persistence.saveAll(List.of(kafka));

        DataSourceProperties.DataSourceItem restored =
                new DataSourcePersistence(properties).load().get(0);

        assertThat(restored.getSecurityProtocol()).isEqualTo("SASL_SSL");
        assertThat(restored.getSaslMechanism()).isEqualTo("SCRAM-SHA-256");
        assertThat(restored.isDynamic()).isTrue();
    }

    @Test
    void springCanAutowireTheSingleConstructor() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setDynamicDatasourceH2(tempDir.resolve("spring-context").toString());
        properties.setDynamicDatasourceH2AutoServer(false);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSourceProperties.class, () -> properties);
            context.register(DataSourcePersistence.class);
            context.refresh();

            assertThat(context.getBean(DataSourcePersistence.class)).isNotNull();
        }
    }
}
