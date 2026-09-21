package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.redis.RedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Redis MCP 工具服务。
 * <p>
 * 提供 key 扫描、类型/TTL 查看、类型感知取值等只读能力，以及 SET / DEL / EXPIRE 等写能力。
 * 写操作受数据源 {@code readonly} 标记控制，只读数据源会在客户端层被硬拦截。
 * 通过 {@link DataSourceRegistry} 按名称路由到对应的 Redis 数据源。
 * </p>
 */
@Slf4j
@Service
public class RedisToolService {

    private static final int DEFAULT_SCAN_COUNT = 50;
    private static final int LIMIT_SCAN_COUNT = 500;

    private final DataSourceRegistry registry;

    public RedisToolService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @Tool(description = "扫描 Redis 中匹配指定模式的 key（基于 SCAN，避免 KEYS 阻塞）。返回匹配到的 key 列表。")
    public String redisScanKeys(
            @ToolParam(description = "Redis 数据源名称，通过 list_datasources 获取。为空则使用第一个 Redis 数据源") String datasource,
            @ToolParam(description = "匹配模式，如 user:*、order:1?。为空则匹配全部 (*)") String pattern,
            @ToolParam(description = "最大返回 key 数量，默认 50，最大 500。传入 0 或负数使用默认值。") int count) {
        int effectiveCount = normalizeCount(count);
        try {
            RedisConnection conn = registry.getRedis(datasource);
            List<String> keys = conn.scanKeys(pattern, effectiveCount);
            StringBuilder sb = new StringBuilder();
            sb.append("Redis 数据源 [").append(display(datasource)).append("] 匹配模式 [")
                    .append(pattern == null || pattern.isEmpty() ? "*" : pattern).append("] 的 key，")
                    .append("返回 ").append(keys.size()).append(" 个（上限 ").append(effectiveCount).append("）：\n\n");
            if (keys.isEmpty()) {
                sb.append("（无匹配 key）");
                return sb.toString();
            }
            int i = 1;
            for (String key : keys) {
                sb.append(i++).append(". ").append(key).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("redisScanKeys 执行失败：datasource={}, pattern={}", datasource, pattern, e);
            return "扫描 Redis key 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "查看指定 Redis key 的元信息：数据类型（string/list/set/zset/hash）与剩余存活时间 TTL（秒，-1 永久，-2 不存在）。")
    public String redisKeyInfo(
            @ToolParam(description = "Redis 数据源名称，为空则使用第一个 Redis 数据源") String datasource,
            @ToolParam(description = "key 名称") String key) {
        if (isBlank(key)) {
            return "参数错误：key 不能为空。";
        }
        try {
            RedisConnection conn = registry.getRedis(datasource);
            String type = conn.type(key);
            if ("none".equals(type)) {
                return "key [" + key + "] 不存在。";
            }
            long ttl = conn.ttl(key);
            return "key [" + key + "] 信息：\n- 类型：" + type + "\n- TTL：" + formatTtl(ttl);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("redisKeyInfo 执行失败：datasource={}, key={}", datasource, key, e);
            return "查询 key [" + key + "] 信息失败：" + safeMessage(e);
        }
    }

    @Tool(description = "读取指定 Redis key 的值（类型感知）：string 返回字符串；list/set 返回元素列表；hash 返回字段映射；zset 返回成员及分值。")
    public String redisGetKey(
            @ToolParam(description = "Redis 数据源名称，为空则使用第一个 Redis 数据源") String datasource,
            @ToolParam(description = "key 名称") String key) {
        if (isBlank(key)) {
            return "参数错误：key 不能为空。";
        }
        try {
            RedisConnection conn = registry.getRedis(datasource);
            String type = conn.type(key);
            if ("none".equals(type)) {
                return "key [" + key + "] 不存在。";
            }
            Object value = conn.getValue(key);
            return "key [" + key + "]（类型 " + type + "）的值：\n\n" + formatValue(value);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("redisGetKey 执行失败：datasource={}, key={}", datasource, key, e);
            return "读取 key [" + key + "] 的值失败：" + safeMessage(e);
        }
    }

    @Tool(description = "设置一个 Redis 字符串 key（SET）。【警告】此操作会写入/覆盖数据。只读数据源将拒绝执行。")
    public String redisSet(
            @ToolParam(description = "Redis 数据源名称，为空则使用第一个 Redis 数据源") String datasource,
            @ToolParam(description = "key 名称") String key,
            @ToolParam(description = "要设置的字符串值") String value) {
        if (isBlank(key)) {
            return "参数错误：key 不能为空。";
        }
        try {
            RedisConnection conn = registry.getRedis(datasource);
            String result = conn.set(key, value);
            return "SET 执行成功：key=" + key + "，返回=" + result;
        } catch (SecurityException se) {
            return "操作被拒绝：" + safeMessage(se);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("redisSet 执行失败：datasource={}, key={}", datasource, key, e);
            return "SET key [" + key + "] 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "删除一个 Redis key（DEL）。【警告】此操作不可恢复！返回删除的 key 数量。只读数据源将拒绝执行。")
    public String redisDelete(
            @ToolParam(description = "Redis 数据源名称，为空则使用第一个 Redis 数据源") String datasource,
            @ToolParam(description = "要删除的 key 名称") String key) {
        if (isBlank(key)) {
            return "参数错误：key 不能为空。";
        }
        try {
            RedisConnection conn = registry.getRedis(datasource);
            long removed = conn.delete(key);
            return "DEL 执行成功：删除 " + removed + " 个 key（" + key + "）。";
        } catch (SecurityException se) {
            return "操作被拒绝：" + safeMessage(se);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("redisDelete 执行失败：datasource={}, key={}", datasource, key, e);
            return "DEL key [" + key + "] 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "为指定 Redis key 设置过期时间（EXPIRE，单位秒）。返回是否设置成功。只读数据源将拒绝执行。")
    public String redisExpire(
            @ToolParam(description = "Redis 数据源名称，为空则使用第一个 Redis 数据源") String datasource,
            @ToolParam(description = "key 名称") String key,
            @ToolParam(description = "过期时间（秒）") int seconds) {
        if (isBlank(key)) {
            return "参数错误：key 不能为空。";
        }
        if (seconds <= 0) {
            return "参数错误：seconds 必须为正整数。";
        }
        try {
            RedisConnection conn = registry.getRedis(datasource);
            long ok = conn.expire(key, seconds);
            return ok == 1
                    ? "EXPIRE 执行成功：key=" + key + " 将在 " + seconds + " 秒后过期。"
                    : "EXPIRE 未生效：key [" + key + "] 不存在。";
        } catch (SecurityException se) {
            return "操作被拒绝：" + safeMessage(se);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("redisExpire 执行失败：datasource={}, key={}", datasource, key, e);
            return "EXPIRE key [" + key + "] 失败：" + safeMessage(e);
        }
    }

    public RedisConnection.ScanPage<String> scanKeyRecords(
            String datasource, String pattern, String redisCursor, int limit) {
        return registry.getRedis(datasource).scanKeysPage(pattern, redisCursor, normalizeCount(limit));
    }

    public Map<String, Object> keyInfoRecord(String datasource, String key) {
        requireKey(key);
        RedisConnection connection = registry.getRedis(datasource);
        String type = connection.type(key);
        return Map.of("key", key, "type", type, "ttl_seconds", connection.ttl(key));
    }

    public RedisConnection.ValuePage valueRecord(
            String datasource, String key, String redisCursor, int limit, int maxChars) {
        requireKey(key);
        return registry.getRedis(datasource).getValuePage(key, redisCursor,
                normalizeCount(limit), Math.max(1, maxChars));
    }

    public String setRecord(String datasource, String key, String value) {
        requireKey(key);
        return registry.getRedis(datasource).set(key, value);
    }

    public long deleteRecord(String datasource, String key) {
        requireKey(key);
        return registry.getRedis(datasource).delete(key);
    }

    public long expireRecord(String datasource, String key, int seconds) {
        requireKey(key);
        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds 必须为正整数");
        }
        return registry.getRedis(datasource).expire(key, seconds);
    }

    private void requireKey(String key) {
        if (isBlank(key)) {
            throw new IllegalArgumentException("key 不能为空");
        }
    }

    /* ====================== 内部工具方法 ====================== */

    @SuppressWarnings("unchecked")
    private String formatValue(Object value) {
        if (value == null) {
            return "（空）";
        }
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ?> e : ((Map<String, ?>) value).entrySet()) {
                sb.append("- ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            }
            return sb.toString();
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (Object o : (List<?>) value) {
                sb.append(i++).append(". ").append(o).append("\n");
            }
            return sb.toString();
        }
        if (value instanceof java.util.Set) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (java.util.Set<?>) value) {
                sb.append("- ").append(o).append("\n");
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private String formatTtl(long ttl) {
        if (ttl == -1) {
            return "-1（永久）";
        }
        if (ttl == -2) {
            return "-2（key 不存在）";
        }
        return ttl + " 秒";
    }

    private int normalizeCount(int count) {
        if (count <= 0) {
            return DEFAULT_SCAN_COUNT;
        }
        return Math.min(count, LIMIT_SCAN_COUNT);
    }

    private String display(String datasource) {
        return isBlank(datasource) ? "default" : datasource;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
