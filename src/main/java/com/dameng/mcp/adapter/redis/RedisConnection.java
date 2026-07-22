package com.dameng.mcp.adapter.redis;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 连接封装（基于 {@link JedisPool}）。
 * <p>
 * 提供只读命令（SCAN / TYPE / TTL / 类型感知的取值）与写命令（SET / DEL / EXPIRE）。
 * 所有写操作在 {@code readonly} 数据源上会直接抛出 {@link SecurityException} 作为客户端层硬拦截。
 * </p>
 */
@Slf4j
public class RedisConnection implements AutoCloseable {

    private final JedisPool jedisPool;
    private final boolean readonly;

    public RedisConnection(JedisPool jedisPool, boolean readonly) {
        this.jedisPool = jedisPool;
        this.readonly = readonly;
    }

    /**
     * 连通性探测：PING。
     */
    public String ping() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.ping();
        }
    }

    /**
     * 使用 SCAN 增量扫描匹配 pattern 的 key（避免 KEYS 阻塞）。
     * 单次调用最多返回约 count 个 key。
     *
     * @param pattern 匹配模式，如 {@code user:*}；为空时匹配全部
     * @param count   本次期望返回的 key 数量上限
     * @return 匹配到的 key 列表
     */
    public List<String> scanKeys(String pattern, int count) {
        String matchPattern = (pattern == null || pattern.trim().isEmpty()) ? "*" : pattern;
        ScanParams params = new ScanParams().match(matchPattern).count(Math.max(count, 10));
        List<String> keys = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                keys.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && keys.size() < count);
        }
        if (keys.size() > count) {
            return new ArrayList<>(keys.subList(0, count));
        }
        return keys;
    }

    /**
     * 获取 key 的数据类型（string/list/set/zset/hash/none）。
     */
    public String type(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.type(key);
        }
    }

    /**
     * 获取 key 的剩余存活时间（秒）。-1 表示永久，-2 表示不存在。
     */
    public long ttl(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.ttl(key);
        }
    }

    /**
     * 类型感知地读取 key 的值。返回结构随类型不同：
     * string -> String；list -> List；set -> Set；hash -> Map；zset -> Map(member -> score)。
     */
    public Object getValue(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            String type = jedis.type(key);
            switch (type) {
                case "string":
                    return jedis.get(key);
                case "list":
                    return jedis.lrange(key, 0, -1);
                case "set":
                    return jedis.smembers(key);
                case "hash":
                    return jedis.hgetAll(key);
                case "zset":
                    List<Tuple> tuples = jedis.zrangeWithScores(key, 0, -1);
                    Map<String, Double> zset = new LinkedHashMap<>();
                    for (Tuple t : tuples) {
                        zset.put(t.getElement(), t.getScore());
                    }
                    return zset;
                case "none":
                default:
                    return null;
            }
        }
    }

    /**
     * 设置字符串 key（SET）。仅当非只读时允许。
     */
    public String set(String key, String value) {
        ensureWritable();
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.set(key, value == null ? "" : value);
        }
    }

    /**
     * 删除 key（DEL）。返回删除的 key 数量。仅当非只读时允许。
     */
    public long delete(String key) {
        ensureWritable();
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.del(key);
        }
    }

    /**
     * 设置 key 过期时间（EXPIRE，单位秒）。返回 1 表示成功，0 表示 key 不存在。仅当非只读时允许。
     */
    public long expire(String key, long seconds) {
        ensureWritable();
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.expire(key, seconds);
        }
    }

    /**
     * 命中的 key 总数估算（用于展示）。这里返回 DBSIZE。
     */
    public long dbSize() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.dbSize();
        }
    }

    /**
     * 只读硬拦截：只读数据源禁止任何写操作。
     */
    private void ensureWritable() {
        if (readonly) {
            throw new SecurityException("当前 Redis 数据源为只读模式，禁止执行任何写操作");
        }
    }

    @Override
    public void close() {
        try {
            jedisPool.close();
        } catch (Exception e) {
            log.warn("关闭 Redis 连接池失败: {}", e.getMessage());
        }
    }
}
