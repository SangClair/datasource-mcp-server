package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.kafka.KafkaConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Kafka MCP 工具服务。
 * <p>
 * 提供只读运维诊断能力：列出 topic、查看 topic 详情、列出消费者组及 lag、抓取最近消息。
 * 抓取消息严格无副作用（assign + seek + 禁用提交 + 随机 group.id），不会污染任何 consumer group offset。
 * 通过 {@link DataSourceRegistry} 按名称路由到对应的 Kafka 数据源。
 * </p>
 */
@Slf4j
@Service
public class KafkaToolService {

    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final int LIMIT_MAX_MESSAGES = 500;
    private static final int DEFAULT_POLL_TIMEOUT_MS = 2000;
    private static final int LIMIT_POLL_TIMEOUT_MS = 5000;

    private final DataSourceRegistry registry;

    public KafkaToolService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @Tool(description = "列出 Kafka 集群中的所有 topic（只读）。返回 topic 名称列表。")
    public String kafkaListTopics(
            @ToolParam(description = "Kafka 数据源名称，通过 list_datasources 获取。为空则使用第一个 Kafka 数据源") String datasource,
            @ToolParam(description = "是否包含内部 topic（如 __consumer_offsets），默认 false") boolean includeInternal) {
        try {
            KafkaConnection conn = registry.getKafka(datasource);
            List<String> topics = conn.listTopics(includeInternal);
            StringBuilder sb = new StringBuilder();
            sb.append("Kafka 数据源 [").append(display(datasource)).append("] 的 topic")
                    .append(includeInternal ? "（含内部）" : "").append("，共 ")
                    .append(topics.size()).append(" 个：\n\n");
            if (topics.isEmpty()) {
                sb.append("（无 topic）");
                return sb.toString();
            }
            int i = 1;
            for (String topic : topics) {
                sb.append(i++).append(". ").append(topic).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("kafkaListTopics 执行失败：datasource={}", datasource, e);
            return "列出 Kafka topic 失败：" + safeMessage(e);
        }
    }

    @Tool(description = "查看指定 Kafka topic 的详情（只读）：分区数、各分区 leader/副本/ISR，以及关键配置(如 retention)。")
    public String kafkaDescribeTopic(
            @ToolParam(description = "Kafka 数据源名称，为空则使用第一个 Kafka 数据源") String datasource,
            @ToolParam(description = "topic 名称") String topic) {
        if (isBlank(topic)) {
            return "参数错误：topic 不能为空。";
        }
        try {
            KafkaConnection conn = registry.getKafka(datasource);
            KafkaConnection.TopicDetail detail = conn.describeTopic(topic);
            StringBuilder sb = new StringBuilder();
            sb.append("topic [").append(detail.name).append("] 详情：\n");
            sb.append("- 内部 topic：").append(detail.internal ? "是" : "否").append("\n");
            sb.append("- 分区数：").append(detail.partitions.size()).append("\n\n");
            sb.append("分区分布：\n");
            for (KafkaConnection.PartitionDetail pd : detail.partitions) {
                sb.append("  分区 ").append(pd.partition)
                        .append(" | leader=").append(pd.leader)
                        .append(" | replicas=").append(pd.replicas)
                        .append(" | isr=").append(pd.isr).append("\n");
            }
            if (detail.configs != null && !detail.configs.isEmpty()) {
                sb.append("\n关键配置（非默认）：\n");
                for (Map.Entry<String, String> e : detail.configs.entrySet()) {
                    sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
                }
            }
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("kafkaDescribeTopic 执行失败：datasource={}, topic={}", datasource, topic, e);
            return "查看 topic [" + topic + "] 详情失败：" + safeMessage(e);
        }
    }

    @Tool(description = "列出 Kafka 消费者组及其状态与 lag（只读）：groupId、状态、成员数、总积压 lag。")
    public String kafkaListConsumerGroups(
            @ToolParam(description = "Kafka 数据源名称，为空则使用第一个 Kafka 数据源") String datasource) {
        try {
            KafkaConnection conn = registry.getKafka(datasource);
            List<KafkaConnection.ConsumerGroupSummary> groups = conn.listConsumerGroups();
            StringBuilder sb = new StringBuilder();
            sb.append("Kafka 数据源 [").append(display(datasource)).append("] 的消费者组，共 ")
                    .append(groups.size()).append(" 个：\n\n");
            if (groups.isEmpty()) {
                sb.append("（无消费者组）");
                return sb.toString();
            }
            int i = 1;
            for (KafkaConnection.ConsumerGroupSummary g : groups) {
                sb.append(i++).append(". ").append(g.groupId)
                        .append(" | 状态=").append(g.state)
                        .append(" | 成员数=").append(g.members)
                        .append(" | 总 lag=").append(g.totalLag).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("kafkaListConsumerGroups 执行失败：datasource={}", datasource, e);
            return "列出消费者组失败：" + safeMessage(e);
        }
    }

    @Tool(description = "抓取指定 topic 最近的若干条消息用于诊断（只读，无副作用：不提交任何 offset）。返回分区/偏移/时间戳/key/value。")
    public String kafkaPeekMessages(
            @ToolParam(description = "Kafka 数据源名称，为空则使用第一个 Kafka 数据源") String datasource,
            @ToolParam(description = "topic 名称") String topic,
            @ToolParam(description = "分区号，传 -1 或负数表示全部分区") int partition,
            @ToolParam(description = "最大返回消息数，默认 20，最大 500。传 0 或负数用默认值") int maxMessages,
            @ToolParam(description = "单次 poll 超时(毫秒)，默认 2000，最大 5000。传 0 或负数用默认值") int pollTimeoutMs) {
        if (isBlank(topic)) {
            return "参数错误：topic 不能为空。";
        }
        int effectiveMax = normalizeMax(maxMessages);
        long effectiveTimeout = normalizeTimeout(pollTimeoutMs);
        Integer targetPartition = partition < 0 ? null : partition;
        try {
            KafkaConnection conn = registry.getKafka(datasource);
            List<KafkaConnection.KafkaRecordView> records =
                    conn.peekMessages(topic, targetPartition, effectiveMax, effectiveTimeout);
            StringBuilder sb = new StringBuilder();
            sb.append("topic [").append(topic).append("] ")
                    .append(targetPartition == null ? "全部分区" : "分区 " + targetPartition)
                    .append(" 最近 ").append(records.size()).append(" 条消息（上限 ")
                    .append(effectiveMax).append("）：\n\n");
            if (records.isEmpty()) {
                sb.append("（无消息）");
                return sb.toString();
            }
            int i = 1;
            for (KafkaConnection.KafkaRecordView r : records) {
                sb.append(i++).append(". [分区 ").append(r.partition)
                        .append(" | offset ").append(r.offset)
                        .append(" | ").append(formatTimestamp(r.timestamp)).append("]\n");
                sb.append("   key: ").append(r.key == null ? "（null）" : r.key).append("\n");
                sb.append("   value: ").append(r.value == null ? "（null）" : r.value).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "数据源不存在：" + safeMessage(e);
        } catch (Exception e) {
            log.error("kafkaPeekMessages 执行失败：datasource={}, topic={}", datasource, topic, e);
            return "抓取 topic [" + topic + "] 消息失败：" + safeMessage(e);
        }
    }

    public List<String> listTopicRecords(String datasource, boolean includeInternal) throws Exception {
        return registry.getKafka(datasource).listTopics(includeInternal);
    }

    public KafkaConnection.TopicDetail topicDetailRecord(String datasource, String topic) throws Exception {
        requireText(topic, "topic");
        return registry.getKafka(datasource).describeTopic(topic);
    }

    public List<KafkaConnection.ConsumerGroupSummary> consumerGroupRecords(String datasource) throws Exception {
        return registry.getKafka(datasource).listConsumerGroups();
    }

    public List<KafkaConnection.KafkaRecordView> peekMessageRecords(
            String datasource, String topic, Integer partition, int maxMessages, int pollTimeoutMs) throws Exception {
        requireText(topic, "topic");
        return registry.getKafka(datasource).peekMessages(topic,
                partition == null || partition < 0 ? null : partition,
                normalizeMax(maxMessages), normalizeTimeout(pollTimeoutMs));
    }

    private void requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /* ====================== 内部工具方法 ====================== */

    private int normalizeMax(int max) {
        if (max <= 0) {
            return DEFAULT_MAX_MESSAGES;
        }
        return Math.min(max, LIMIT_MAX_MESSAGES);
    }

    private long normalizeTimeout(int timeout) {
        if (timeout <= 0) {
            return DEFAULT_POLL_TIMEOUT_MS;
        }
        return Math.min(timeout, LIMIT_POLL_TIMEOUT_MS);
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp < 0) {
            return "无时间戳";
        }
        return Instant.ofEpochMilli(timestamp).toString();
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
