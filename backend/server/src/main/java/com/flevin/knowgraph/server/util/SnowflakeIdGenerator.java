package com.flevin.knowgraph.server.util;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 应用侧 Snowflake Long 标识生成器。
 *
 * <p>生成结果由时间戳、固定节点号和进程内序列组成，能够在写入关联记录前
 * 先拿到稳定的 Long 主键，不依赖数据库自增或 UUID。当前项目以单实例本地
 * 部署为主，因此节点号固定为 1；后续扩展多实例时再把节点号纳入配置。</p>
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH_MILLIS = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final long NODE_ID = 1L;
    private static final long NODE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long NODE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS;

    private static long lastTimestamp = -1L;
    private static long sequence;

    private SnowflakeIdGenerator() {
    }

    /**
     * 生成下一个可排序的 Long 标识。
     *
     * @return 当前进程生成的唯一 Long 标识
     */
    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("系统时钟回拨，无法安全生成 Long 标识");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (NODE_ID << NODE_ID_SHIFT)
                | sequence;
    }

    /**
     * 根据稳定字符串生成正数 Long，用于需要跨重复运行保持同一标识的兼容实体。
     *
     * @param value 稳定业务输入
     * @return 基于 SHA-256 前八字节截取的正数 Long
     */
    public static long stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                result = (result << Byte.SIZE) | (digest[index] & 0xffL);
            }
            return result & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 未提供 SHA-256", exception);
        }
    }

    private static long waitUntilNextMillis(long previousTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= previousTimestamp) {
            Thread.onSpinWait();
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
