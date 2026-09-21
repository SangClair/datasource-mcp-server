package com.dameng.mcp.adapter.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConnectionTest {

    @Test
    void readsListsByRangeInsteadOfLoadingTheWholeCollection() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.type("events")).thenReturn("list");
        when(jedis.llen("events")).thenReturn(1_000L);
        when(jedis.lrange("events", 0, 9)).thenReturn(List.of("a", "b"));
        RedisConnection connection = new RedisConnection(pool, true);

        RedisConnection.ValuePage page = connection.getValuePage("events", "0", 10, 4096);

        assertThat(page.value()).isEqualTo(List.of("a", "b"));
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("2");
        verify(jedis).lrange("events", 0, 9);
        verify(jedis, never()).lrange("events", 0, -1);
    }

    @Test
    void advancesStringCursorByUtf8Bytes() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.type("text")).thenReturn("string");
        when(jedis.strlen("text")).thenReturn(9L);
        byte[] firstPage = "中文".getBytes(StandardCharsets.UTF_8);
        when(jedis.getrange("text".getBytes(StandardCharsets.UTF_8), 0, 5)).thenReturn(firstPage);
        RedisConnection connection = new RedisConnection(pool, true);

        RedisConnection.ValuePage page = connection.getValuePage("text", "0", 10, 6);

        assertThat(page.value()).isEqualTo("中文");
        assertThat(page.nextCursor()).isEqualTo("6");
        assertThat(page.hasMore()).isTrue();
    }
}
