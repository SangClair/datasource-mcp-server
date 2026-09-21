package com.dameng.mcp.config;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.service.DataSourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceConfigTest {

    @Test
    void recordsDynamicDatasourceWhenStartupRecoveryFails() {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.DataSourceItem kafka = new DataSourceProperties.DataSourceItem();
        kafka.setName("secure-kafka");
        kafka.setType("kafka");
        kafka.setDynamic(true);

        DataSourceManager manager = mock(DataSourceManager.class);
        DataSourceRegistry registry = mock(DataSourceRegistry.class);
        DataSourcePersistence persistence = mock(DataSourcePersistence.class);
        Environment environment = mock(Environment.class);
        RuntimeException failure = new RuntimeException("authentication failed");
        when(persistence.load()).thenReturn(List.of(kafka));
        when(registry.exists("secure-kafka")).thenReturn(false);
        when(registry.size()).thenReturn(0);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"http"});
        doThrow(failure).when(manager).registerStartup(kafka);

        new DataSourceConfig(properties, manager, registry, persistence, environment).init();

        verify(manager).recordUnavailableDynamic(kafka, failure);
    }
}
