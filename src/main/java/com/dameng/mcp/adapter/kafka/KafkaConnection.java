package com.dameng.mcp.adapter.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kafka 连接封装（基于 {@link AdminClient}）。
 * <p>
 * 提供只读运维诊断能力：列出 topic、查看 topic 详情、列出消费者组及 lag、抓取最近消息。
 * {@link AdminClient} 线程安全且随数据源长期存活；抓取消息时以 {@code consumerBaseProps}
 * 每次新建短生命周期 {@link KafkaConsumer} 并用完即关，规避 Consumer 非线程安全问题。
 * </p>
 * <p>
 * 消息读取严格无副作用：使用 {@code assign} + {@code seek}、禁用自动提交、随机 group.id，
 * 全程不调用任何 commit，绝不污染既有 consumer group offset。
 * </p>
 */
@Slf4j
public class KafkaConnection implements AutoCloseable {

    /**
     * listConsumerGroups 汇总的消费者组数量上限，避免大集群下逐组查询开销失控。
     */
    private static final int MAX_GROUPS = 100;

    private final AdminClient admin;
    private final Properties consumerBaseProps;
    private final long apiTimeoutMs;
    private final boolean readonly;

    public KafkaConnection(AdminClient admin, Properties consumerBaseProps, long apiTimeoutMs, boolean readonly) {
        this.admin = admin;
        this.consumerBaseProps = consumerBaseProps;
        this.apiTimeoutMs = apiTimeoutMs;
        this.readonly = readonly;
    }

    /**
     * 连通性探测：获取集群 clusterId。
     */
    public String ping() throws Exception {
        return admin.describeCluster().clusterId().get(apiTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 列出所有 topic 名称（升序）。
     *
     * @param includeInternal 是否包含内部 topic（如 __consumer_offsets）
     */
    public List<String> listTopics(boolean includeInternal) throws Exception {
        ListTopicsOptions options = new ListTopicsOptions().listInternal(includeInternal);
        Collection<String> names = admin.listTopics(options).names().get(apiTimeoutMs, TimeUnit.MILLISECONDS);
        return new ArrayList<>(new TreeSet<>(names));
    }

    /**
     * 查看 topic 详情：分区分布 + 关键配置。topic 不存在时抛异常。
     */
    public TopicDetail describeTopic(String topic) throws Exception {
        TopicDescription description = admin.describeTopics(List.of(topic))
                .allTopicNames().get(apiTimeoutMs, TimeUnit.MILLISECONDS).get(topic);
        if (description == null) {
            throw new IllegalArgumentException("topic 不存在: " + topic);
        }
        TopicDetail detail = new TopicDetail();
        detail.name = description.name();
        detail.internal = description.isInternal();
        detail.partitions = new ArrayList<>();
        for (TopicPartitionInfo info : description.partitions()) {
            PartitionDetail pd = new PartitionDetail();
            pd.partition = info.partition();
            pd.leader = info.leader() == null ? -1 : info.leader().id();
            pd.replicas = info.replicas().stream().map(Node::id).collect(Collectors.toList());
            pd.isr = info.isr().stream().map(Node::id).collect(Collectors.toList());
            detail.partitions.add(pd);
        }
        // 读取 topic 级配置（失败不影响主体信息）
        detail.configs = new LinkedHashMap<>();
        try {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Map<ConfigResource, org.apache.kafka.clients.admin.Config> configMap =
                    admin.describeConfigs(List.of(resource)).all().get(apiTimeoutMs, TimeUnit.MILLISECONDS);
            org.apache.kafka.clients.admin.Config config = configMap.get(resource);
            if (config != null) {
                for (ConfigEntry entry : config.entries()) {
                    // 仅保留非默认的显式配置，减少噪音
                    if (!entry.isDefault()) {
                        detail.configs.put(entry.name(), entry.value());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取 topic [{}] 配置失败: {}", topic, e.getMessage());
        }
        return detail;
    }

    /**
     * 列出消费者组及其状态、成员数与总 lag（最多 {@value #MAX_GROUPS} 个组）。
     */
    public List<ConsumerGroupSummary> listConsumerGroups() throws Exception {
        Collection<ConsumerGroupListing> listings =
                admin.listConsumerGroups().all().get(apiTimeoutMs, TimeUnit.MILLISECONDS);
        List<String> groupIds = listings.stream()
                .map(ConsumerGroupListing::groupId)
                .sorted()
                .limit(MAX_GROUPS)
                .collect(Collectors.toList());
        List<ConsumerGroupSummary> result = new ArrayList<>(groupIds.size());
        if (groupIds.isEmpty()) {
            return result;
        }
        Map<String, ConsumerGroupDescription> descriptions =
                admin.describeConsumerGroups(groupIds).all().get(apiTimeoutMs, TimeUnit.MILLISECONDS);
        try (KafkaConsumer<byte[], byte[]> consumer = newConsumer()) {
            for (String groupId : groupIds) {
                ConsumerGroupSummary summary = new ConsumerGroupSummary();
                summary.groupId = groupId;
                ConsumerGroupDescription desc = descriptions.get(groupId);
                summary.state = desc == null || desc.state() == null ? "UNKNOWN" : desc.state().toString();
                summary.members = desc == null ? 0 : desc.members().size();
                summary.totalLag = computeTotalLag(consumer, groupId);
                result.add(summary);
            }
        }
        return result;
    }

    /**
     * 计算某消费者组的总 lag = Σ(分区 endOffset - committedOffset)。
     * 无法确定的分区（无 committed / 无 endOffset）跳过。
     */
    private long computeTotalLag(KafkaConsumer<byte[], byte[]> consumer, String groupId) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(apiTimeoutMs, TimeUnit.MILLISECONDS);
        if (committed == null || committed.isEmpty()) {
            return 0L;
        }
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(committed.keySet());
        long total = 0L;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            Long end = endOffsets.get(entry.getKey());
            if (end == null || entry.getValue() == null) {
                continue;
            }
            long lag = end - entry.getValue().offset();
            if (lag > 0) {
                total += lag;
            }
        }
        return total;
    }

    /**
     * 无副作用地抓取 topic 最近的若干条消息。
     * <p>
     * 使用 {@code assign} + {@code seek} 定位到各分区尾部，禁用自动提交，全程不 commit。
     * </p>
     *
     * @param topic         topic 名称
     * @param partition     指定分区；为 null 或负数表示全部分区
     * @param maxMessages   最大返回消息总数
     * @param pollTimeoutMs 单次 poll 超时（毫秒）
     */
    public List<KafkaRecordView> peekMessages(String topic, Integer partition, int maxMessages, long pollTimeoutMs)
            throws Exception {
        List<KafkaRecordView> views = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = newConsumer()) {
            // 1. 确定目标分区
            List<TopicPartition> targets = resolvePartitions(consumer, topic, partition);
            if (targets.isEmpty()) {
                return views;
            }
            // 2. assign（不使用 subscribe，避免组协调副作用）
            consumer.assign(targets);

            // 3. seek 到"尾部 N 条"
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(targets);
            Map<TopicPartition, Long> end = consumer.endOffsets(targets);
            int perPartition = (int) Math.ceil((double) maxMessages / targets.size());
            long available = 0L;
            for (TopicPartition tp : targets) {
                long begin = beginning.getOrDefault(tp, 0L);
                long last = end.getOrDefault(tp, 0L);
                long start = Math.max(begin, last - perPartition);
                consumer.seek(tp, start);
                available += Math.max(0L, last - start);
            }
            if (available <= 0L) {
                // 所有分区均无可读消息
                return views;
            }

            // 4. 有界拉取：达到 maxMessages、读到各分区尾部、或连续空轮询即停止
            int emptyPolls = 0;
            while (views.size() < maxMessages && emptyPolls < 2) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                if (records.isEmpty()) {
                    emptyPolls++;
                    if (reachedEnd(consumer, targets, end)) {
                        break;
                    }
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    KafkaRecordView view = new KafkaRecordView();
                    view.partition = record.partition();
                    view.offset = record.offset();
                    view.timestamp = record.timestamp();
                    view.key = decodeToText(record.key());
                    view.value = decodeToText(record.value());
                    views.add(view);
                    if (views.size() >= maxMessages) {
                        break;
                    }
                }
                if (reachedEnd(consumer, targets, end)) {
                    break;
                }
            }
        }
        // 全程未调用任何 commit，consumer 关闭后不残留 offset
        return views;
    }

    /**
     * 判断所有目标分区是否均已读到抓取起始时记录的尾部 offset。
     */
    private boolean reachedEnd(KafkaConsumer<byte[], byte[]> consumer, List<TopicPartition> targets,
                               Map<TopicPartition, Long> end) {
        for (TopicPartition tp : targets) {
            long last = end.getOrDefault(tp, 0L);
            if (consumer.position(tp) < last) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析目标分区：指定分区则仅该分区，否则取全部分区。topic 不存在或无分区时返回空列表。
     */
    private List<TopicPartition> resolvePartitions(KafkaConsumer<byte[], byte[]> consumer, String topic,
                                                   Integer partition) {
        List<org.apache.kafka.common.PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
        if (partitionInfos == null || partitionInfos.isEmpty()) {
            return new ArrayList<>();
        }
        List<TopicPartition> targets = new ArrayList<>();
        if (partition != null && partition >= 0) {
            targets.add(new TopicPartition(topic, partition));
        } else {
            for (org.apache.kafka.common.PartitionInfo info : partitionInfos) {
                targets.add(new TopicPartition(topic, info.partition()));
            }
        }
        return targets;
    }

    /**
     * 依据基础属性新建短生命周期 Consumer（随机 group.id，禁用自动提交）。
     */
    private KafkaConsumer<byte[], byte[]> newConsumer() {
        Properties props = new Properties();
        props.putAll(consumerBaseProps);
        props.put("group.id", "dameng-mcp-peek-" + UUID.randomUUID());
        return new KafkaConsumer<>(props);
    }

    /**
     * 将字节数组友好地解码为文本：可打印文本按 UTF-8 展示；二进制内容以长度 + base64 截断提示。
     */
    private String decodeToText(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (isProbablyText(text)) {
            return text;
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        if (base64.length() > 128) {
            base64 = base64.substring(0, 128) + "...";
        }
        return "<binary " + bytes.length + " bytes, base64: " + base64 + ">";
    }

    /**
     * 粗略判断解码后的字符串是否为可读文本（不含替换字符、无过多控制字符）。
     */
    private boolean isProbablyText(String text) {
        int controlCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD') {
                return false;
            }
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                controlCount++;
            }
        }
        return controlCount == 0;
    }

    @Override
    public void close() {
        try {
            admin.close(Duration.ofSeconds(3));
        } catch (Exception e) {
            log.warn("关闭 Kafka AdminClient 失败: {}", e.getMessage());
        }
    }

    /* ====================== 轻量值对象 ====================== */

    /**
     * 消费者组摘要。
     */
    public static class ConsumerGroupSummary {
        public String groupId;
        public String state;
        public int members;
        public long totalLag;
    }

    /**
     * 单条消息视图。
     */
    public static class KafkaRecordView {
        public int partition;
        public long offset;
        public long timestamp;
        public String key;
        public String value;
    }

    /**
     * topic 详情。
     */
    public static class TopicDetail {
        public String name;
        public boolean internal;
        public List<PartitionDetail> partitions;
        public Map<String, String> configs;
    }

    /**
     * 分区详情。
     */
    public static class PartitionDetail {
        public int partition;
        public int leader;
        public List<Integer> replicas;
        public List<Integer> isr;
    }
}
