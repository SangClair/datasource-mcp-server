package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.security.SqlSecurityValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseWriteServiceTest {

    @Test
    void updateWithoutWhereStillExecutesAndReturnsWarning() {
        DataSourceRegistry registry = mock(DataSourceRegistry.class);
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(registry.getDefaultName()).thenReturn("main");
        when(registry.isReadonly("main")).thenReturn(false);
        when(registry.getDefaultAdapter()).thenReturn(adapter);
        when(adapter.executeUpdate("UPDATE users SET enabled = 0")).thenReturn(12);
        DatabaseWriteService service = new DatabaseWriteService(registry, new SqlSecurityValidator());

        DatabaseWriteService.WriteOperationResult result = service.executeWriteRecord(
                null, "UPDATE users SET enabled = 0", "UPDATE");

        assertThat(result.unsafeScope()).isTrue();
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.affectedRows()).isEqualTo(12);
        verify(adapter).executeUpdate("UPDATE users SET enabled = 0");
    }

    @Test
    void arbitrarySqlAllowsSingleDeleteAndWarnsAboutMissingWhere() {
        DataSourceRegistry registry = mock(DataSourceRegistry.class);
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(registry.getDefaultName()).thenReturn("main");
        when(registry.isReadonly("main")).thenReturn(false);
        when(registry.getDefaultAdapter()).thenReturn(adapter);
        DatabaseWriteService service = new DatabaseWriteService(registry, new SqlSecurityValidator());

        DatabaseWriteService.SqlOperationResult result = service.executeSqlRecord(null, "DELETE FROM audit_log");

        assertThat(result.statementType()).isEqualTo("DELETE");
        assertThat(result.unsafeScope()).isTrue();
        assertThat(result.warnings()).isNotEmpty();
        verify(adapter).executeRaw("DELETE FROM audit_log");
    }
}
