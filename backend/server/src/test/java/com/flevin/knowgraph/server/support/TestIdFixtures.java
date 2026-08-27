package com.flevin.knowgraph.server.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 为测试语义键生成稳定的 MySQL BIGINT 标识，避免测试继续把业务字符串写入数据库主键列。
 */
public final class TestIdFixtures {

    private TestIdFixtures() {
    }

    /**
     * 将测试语义键转换为稳定且为正的 {@code Long} 数据库标识。
     *
     * @param businessKey 测试中用于说明用途的非空业务键
     * @return 可用于 MySQL {@code BIGINT} 主键或关联列的稳定正数标识
     */
    public static Long id(String businessKey) {
        try {
            // 使用固定 SHA-256 摘要保证相同测试业务键始终映射到相同数据库标识
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(businessKey.getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return value == 0L ? 1L : value;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成测试数据库标识", exception);
        }
    }
}
