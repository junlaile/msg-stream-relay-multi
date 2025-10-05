package cn.junlaile.msg.stream.relay.multi.support;

import cn.junlaile.msg.stream.relay.multi.rabbit.RabbitMQClientManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理队列映射关系的组件，维护三层映射缓存：
 * 1. 前端队列名称 → 规则（exchange:routingKey）
 * 2. 规则 → 不重复ID（使用 SHA-256 哈希生成）
 * 3. 不重复ID → RabbitMQ 队列名称及元数据
 *
 * 支持高并发访问，自动清理过期队列，避免内存泄漏
 */
@ApplicationScoped
public class QueueMappingManager {

    private static final Logger LOG = Logger.getLogger(QueueMappingManager.class);

    /**
     * 队列空闲超时时间（毫秒）：30 分钟
     */
    private static final long QUEUE_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 队列名称前缀
     */
    private static final String QUEUE_PREFIX = "queue-";

    /**
     * 第一层映射：前端队列名称 → 规则（exchange:routingKey）
     */
    private final ConcurrentMap<String, String> destinationToRule = new ConcurrentHashMap<>();

    /**
     * 第二层映射：规则 → 唯一ID
     */
    private final ConcurrentMap<String, String> ruleToId = new ConcurrentHashMap<>();

    /**
     * 第三层映射：唯一ID → 队列信息
     */
    private final ConcurrentMap<String, QueueInfo> idToQueueInfo = new ConcurrentHashMap<>();

    private final RabbitMQClientManager clientManager;

    /**
     * 构造队列映射管理器
     *
     * @param clientManager RabbitMQ 客户端管理器，用于删除队列操作
     */
    @Inject
    public QueueMappingManager(RabbitMQClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /**
     * 解析或生成队列映射
     * 如果映射已存在则复用，否则生成新的队列并建立映射
     *
     * @param destination 前端队列名称（STOMP destination）
     * @param exchange RabbitMQ 交换机名称
     * @param routingKey RabbitMQ 路由键
     * @return 队列映射结果，包含队列名称和是否为新创建
     */
    public QueueMapping resolveQueue(String destination, String exchange, String routingKey) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(exchange, "exchange");

        String rule = buildRule(destination, exchange, routingKey);

        // 检查缓存中是否已存在映射
        String existingRule = destinationToRule.get(destination);
        if (existingRule != null && existingRule.equals(rule)) {
            String queueId = ruleToId.get(rule);
            if (queueId != null) {
                QueueInfo queueInfo = idToQueueInfo.get(queueId);
                if (queueInfo != null) {
                    queueInfo.updateLastAccessTime();
                    LOG.debugf("Reusing existing queue mapping: destination=%s -> queue=%s",
                              destination, queueInfo.queueName);
                    return new QueueMapping(queueInfo.queueName, false);
                }
            }
        }

        // 检查规则是否已存在（队列已创建）
        boolean queueExists = ruleToId.containsKey(rule);

        // 生成或获取队列ID
        String queueId = ruleToId.computeIfAbsent(rule, this::generateQueueId);
        String queueName = QUEUE_PREFIX + queueId;

        QueueInfo queueInfo = idToQueueInfo.computeIfAbsent(queueId,
            id -> new QueueInfo(queueName));
        queueInfo.updateLastAccessTime();

        destinationToRule.put(destination, rule);

        if (queueExists) {
            LOG.debugf("Mapped destination to existing queue: destination=%s, rule=%s, queue=%s",
                      destination, rule, queueName);
        } else {
            LOG.infof("Created new queue mapping: destination=%s, rule=%s, queue=%s",
                      destination, rule, queueName);
        }

        return new QueueMapping(queueName, !queueExists);
    }

    /**
     * 增加队列的消费者计数
     * 当客户端订阅时调用
     *
     * @param destination 前端队列名称
     */
    public void incrementConsumer(String destination) {
        String rule = destinationToRule.get(destination);
        if (rule != null) {
            String queueId = ruleToId.get(rule);
            if (queueId != null) {
                QueueInfo queueInfo = idToQueueInfo.get(queueId);
                if (queueInfo != null) {
                    queueInfo.incrementConsumerCount();
                    queueInfo.updateLastAccessTime();
                    LOG.debugf("Incremented consumer count for queue %s: count=%d",
                              queueInfo.queueName, queueInfo.consumerCount);
                }
            }
        }
    }

    /**
     * 减少队列的消费者计数
     * 当客户端取消订阅或断开连接时调用
     * 如果消费者数量降为 0，则触发队列清理
     *
     * @param destination 前端队列名称
     */
    public void decrementConsumer(String destination) {
        String rule = destinationToRule.get(destination);
        if (rule == null) {
            return;
        }

        String queueId = ruleToId.get(rule);
        if (queueId == null) {
            return;
        }

        QueueInfo queueInfo = idToQueueInfo.get(queueId);
        if (queueInfo == null) {
            return;
        }

        int count = queueInfo.decrementConsumerCount();
        LOG.debugf("Decremented consumer count for queue %s: count=%d",
                  queueInfo.queueName, count);

        if (count <= 0) {
            // 消费者为 0，标记为待清理
            queueInfo.updateLastAccessTime();
            LOG.infof("Queue %s has no consumers, will be cleaned up after timeout",
                     queueInfo.queueName);
        }
    }

    /**
     * 定时清理过期的队列映射
     * 每 30 分钟执行一次，扫描所有队列，移除无消费者且超时的条目
     *
     * 清理条件：
     * 1. 消费者数量为 0
     * 2. 最后访问时间超过 30 分钟
     */
    @Scheduled(cron = "0 */30 * * * ?")
    void cleanupExpiredQueues() {
        long now = System.currentTimeMillis();
        int removedCount = 0;

        List<String> expiredIds = new ArrayList<>();

        for (Map.Entry<String, QueueInfo> entry : idToQueueInfo.entrySet()) {
            String queueId = entry.getKey();
            QueueInfo queueInfo = entry.getValue();

            if (queueInfo.consumerCount <= 0
                && (now - queueInfo.lastAccessTime) > QUEUE_IDLE_TIMEOUT_MS) {
                expiredIds.add(queueId);
            }
        }

        for (String queueId : expiredIds) {
            QueueInfo queueInfo = idToQueueInfo.remove(queueId);
            if (queueInfo != null) {
                // 删除 RabbitMQ 队列
                deleteQueueAsync(queueInfo.queueName);

                // 清理反向映射
                cleanupReverseMappings(queueId);

                removedCount++;
                LOG.infof("Cleaned up expired queue: id=%s, queue=%s",
                         queueId, queueInfo.queueName);
            }
        }

        if (removedCount > 0) {
            LOG.infof("Cleanup completed: removed %d expired queue(s)", removedCount);
        } else {
            LOG.debugf("Cleanup completed: no expired queues found");
        }
    }

    /**
     * 获取当前缓存的统计信息，用于监控和调试
     *
     * @return 包含映射数量和队列信息的统计数据
     */
    public CacheStats getStats() {
        int totalQueues = idToQueueInfo.size();
        int activeQueues = 0;
        int idleQueues = 0;

        for (QueueInfo info : idToQueueInfo.values()) {
            if (info.consumerCount > 0) {
                activeQueues++;
            } else {
                idleQueues++;
            }
        }

        return new CacheStats(
            destinationToRule.size(),
            ruleToId.size(),
            totalQueues,
            activeQueues,
            idleQueues
        );
    }

    /**
     * 构建规则字符串：destination -> exchange:routingKey
     * 使用 destination 作为规则的一部分，确保广播模式下每个订阅独立
     *
     * @param destination 目标地址（广播模式下包含唯一标识）
     * @param exchange 交换机名称
     * @param routingKey 路由键，可以为 null
     * @return 规则字符串
     */
    private String buildRule(String destination, String exchange, String routingKey) {
        String key = (routingKey == null || routingKey.isEmpty()) ? "" : routingKey;
        return destination + "->" + exchange + ":" + key;
    }

    /**
     * 为规则生成唯一 ID，使用 MurmurHash3 算法
     * MurmurHash3 比 SHA-256 快约 10-20 倍，且碰撞率极低
     * 返回 16 字符的十六进制字符串
     *
     * @param rule 规则字符串
     * @return 唯一 ID（16 字符十六进制）
     */
    private String generateQueueId(String rule) {
        byte[] bytes = rule.getBytes(StandardCharsets.UTF_8);
        long hash = murmurHash3(bytes);

        // 使用哈希值生成 16 字符的十六进制字符串
        // 取高低 32 位各生成 8 个字符
        return String.format("%08x%08x", (int) (hash >>> 32), (int) hash);
    }

    /**
     * MurmurHash3 32-bit x86 实现（64位版本）
     * 非加密哈希算法，速度快且碰撞率低
     *
     * @param data 输入字节数组
     * @return 64位哈希值
     */
    private long murmurHash3(byte[] data) {
        final int seed = 0x9747b28c; // 固定种子确保一致性
        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;

        long h1 = seed;
        long h2 = seed;

        int length = data.length;
        int nblocks = length / 16;

        // Body
        for (int i = 0; i < nblocks; i++) {
            int offset = i * 16;
            long k1 = getLong(data, offset);
            long k2 = getLong(data, offset + 8);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        // Tail
        long k1 = 0;
        long k2 = 0;
        int offset = nblocks * 16;

        switch (length & 15) {
            case 15: k2 ^= ((long) data[offset + 14] & 0xff) << 48;
            case 14: k2 ^= ((long) data[offset + 13] & 0xff) << 40;
            case 13: k2 ^= ((long) data[offset + 12] & 0xff) << 32;
            case 12: k2 ^= ((long) data[offset + 11] & 0xff) << 24;
            case 11: k2 ^= ((long) data[offset + 10] & 0xff) << 16;
            case 10: k2 ^= ((long) data[offset + 9] & 0xff) << 8;
            case 9:  k2 ^= ((long) data[offset + 8] & 0xff);
                     k2 *= c2;
                     k2 = Long.rotateLeft(k2, 33);
                     k2 *= c1;
                     h2 ^= k2;
            case 8:  k1 ^= ((long) data[offset + 7] & 0xff) << 56;
            case 7:  k1 ^= ((long) data[offset + 6] & 0xff) << 48;
            case 6:  k1 ^= ((long) data[offset + 5] & 0xff) << 40;
            case 5:  k1 ^= ((long) data[offset + 4] & 0xff) << 32;
            case 4:  k1 ^= ((long) data[offset + 3] & 0xff) << 24;
            case 3:  k1 ^= ((long) data[offset + 2] & 0xff) << 16;
            case 2:  k1 ^= ((long) data[offset + 1] & 0xff) << 8;
            case 1:  k1 ^= ((long) data[offset] & 0xff);
                     k1 *= c1;
                     k1 = Long.rotateLeft(k1, 31);
                     k1 *= c2;
                     h1 ^= k1;
        }

        // Finalization
        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        return h1 ^ h2;
    }

    /**
     * 从字节数组中读取 little-endian 格式的 long 值
     */
    private long getLong(byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
            | (((long) data[offset + 1] & 0xff) << 8)
            | (((long) data[offset + 2] & 0xff) << 16)
            | (((long) data[offset + 3] & 0xff) << 24)
            | (((long) data[offset + 4] & 0xff) << 32)
            | (((long) data[offset + 5] & 0xff) << 40)
            | (((long) data[offset + 6] & 0xff) << 48)
            | (((long) data[offset + 7] & 0xff) << 56);
    }

    /**
     * MurmurHash3 最终混合函数
     */
    private long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /**
     * 异步删除 RabbitMQ 队列
     *
     * @param queueName 队列名称
     */
    private void deleteQueueAsync(String queueName) {
        clientManager.deleteQueue(queueName)
            .whenComplete((ignored, err) -> {
                if (err != null) {
                    LOG.warnf(err, "Failed to delete queue %s from RabbitMQ", queueName);
                } else {
                    LOG.debugf("Successfully deleted queue %s from RabbitMQ", queueName);
                }
            });
    }

    /**
     * 清理与指定队列 ID 相关的反向映射
     *
     * @param queueId 队列 ID
     */
    private void cleanupReverseMappings(String queueId) {
        // 清理 ruleToId 映射
        ruleToId.entrySet().removeIf(entry -> entry.getValue().equals(queueId));

        // 清理 destinationToRule 映射
        // 需要找到所有指向已删除规则的 destination
        Set<String> rulesToRemove = new HashSet<>();
        for (Map.Entry<String, String> entry : ruleToId.entrySet()) {
            if (entry.getValue().equals(queueId)) {
                rulesToRemove.add(entry.getKey());
            }
        }

        destinationToRule.entrySet().removeIf(entry -> rulesToRemove.contains(entry.getValue()));
    }

    /**
     * 队列信息类，包含队列名称、消费者计数和最后访问时间
     */
    private static class QueueInfo {
        private final String queueName;
        private volatile int consumerCount;
        private volatile long lastAccessTime;

        QueueInfo(String queueName) {
            this.queueName = queueName;
            this.consumerCount = 0;
            this.lastAccessTime = System.currentTimeMillis();
        }

        synchronized int incrementConsumerCount() {
            return ++consumerCount;
        }

        synchronized int decrementConsumerCount() {
            if (consumerCount > 0) {
                consumerCount--;
            }
            return consumerCount;
        }

        void updateLastAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    /**
     * 队列映射结果
     *
     * @param queueName RabbitMQ 队列名称
     * @param isNew 是否为新创建的映射
     */
    public record QueueMapping(String queueName, boolean isNew) {}

    /**
     * 缓存统计信息
     *
     * @param destinationCount 前端队列名称数量
     * @param ruleCount 规则数量
     * @param totalQueues 总队列数量
     * @param activeQueues 活跃队列数量（有消费者）
     * @param idleQueues 空闲队列数量（无消费者）
     */
    public record CacheStats(
        int destinationCount,
        int ruleCount,
        int totalQueues,
        int activeQueues,
        int idleQueues
    ) {}
}
