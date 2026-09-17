package com.alianga.jkit.common.idgenerate;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID v7（RFC 9562）：把毫秒时间戳放进 UUID 的高 48 位，其余位随机。
 *
 * <p>布局：{@code 48 位毫秒时间戳 | 4 位 version(7) | 12 位 rand_a | 2 位 variant | 62 位 rand_b}。
 * 因此 UUIDv7 按字典序大致按生成时间递增，做数据库主键时索引写入接近顺序追加，
 * 不像 UUIDv4 那样随机散布导致页分裂。想用 {@code uuid} 列又在意写入性能时选它。
 *
 * <p>{@link #next()} 每次取新的随机位；{@link #nextMonotonic()} 在同一毫秒内递增
 * 12 位计数器，单 JVM 内字典序严格递增。两者都是标准 UUID 形态，可直接存进
 * {@code uuid} 列或被任何按 UUID 处理的库消费。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class UUIDv7 {
    /**
     * 12 位计数器上限，与布局里的 rand_a 宽度一致。
     */
    private static final int MAX_COUNTER = 1 << 12;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Holder MONOTONIC = new Holder();

    private UUIDv7() {
    }

    /**
     * 生成一个 UUIDv7。
     *
     * @return UUIDv7 实例
     */
    public static UUID next() {
        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);
        return build(System.currentTimeMillis(), entropy);
    }

    /**
     * 生成一个单调 UUIDv7：同一毫秒内计数器递增，保证单 JVM 内字典序递增。
     *
     * @return UUIDv7 实例
     */
    public static UUID nextMonotonic() {
        return MONOTONIC.next();
    }

    /**
     * 生成一个 UUIDv7 字符串（带连字符的小写形式）。
     *
     * @return UUID 字符串
     */
    public static String nextString() {
        return next().toString();
    }

    /**
     * 取 UUIDv7 携带的毫秒时间戳。
     *
     * @param uuid 由 {@link #next()} 生成的 UUID
     * @return 生成时刻的毫秒时间戳
     */
    public static long timestamp(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    /**
     * 判断 UUID 是否为 v7（version=7 且 variant 为 RFC 4122）。
     *
     * @param uuid 待判断的 UUID，可为 {@code null}
     * @return 是 UUIDv7 返回 {@code true}
     */
    public static boolean isV7(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return (msb >>> 12 & 0x0F) == 7 && (lsb >>> 62) == 2;
    }

    /**
     * 按时间戳与随机字节组装 UUIDv7：熵的高 12 位作 rand_a，紧随的 62 位作 rand_b。
     *
     * @param timestamp 毫秒时间戳
     * @param entropy 10 字节随机数
     * @return UUIDv7 实例
     */
    private static UUID build(long timestamp, byte[] entropy) {
        long hi = 0;
        long lo = 0;
        for (int i = 0; i < 8; i++) {
            hi = hi << 8 | entropy[i] & 0xFFL;
        }
        for (int i = 8; i < 10; i++) {
            lo = lo << 8 | entropy[i] & 0xFFL;
        }
        long randA = hi >>> 52;
        long randB = (hi & 0x000FFFFFFFFFFFFFL) << 10 | lo >>> 6;
        return assemble(timestamp, (int) randA, randB);
    }

    /**
     * 按时间戳、计数器与 62 位随机数组装 UUIDv7（单调模式）。
     *
     * @param timestamp 毫秒时间戳
     * @param counter 12 位单调计数器
     * @param entropy 8 字节随机数，取低 62 位作 rand_b
     * @return UUIDv7 实例
     */
    private static UUID buildMonotonic(long timestamp, int counter, byte[] entropy) {
        long randB = 0;
        for (int i = 0; i < 8; i++) {
            randB = randB << 8 | entropy[i] & 0xFFL;
        }
        return assemble(timestamp, counter, randB >>> 2);
    }

    /**
     * 拼装最终的两个 long：msb = 时间戳 | version(7) | rand_a，lsb = variant | rand_b。
     *
     * @param timestamp 毫秒时间戳（48 位）
     * @param randA 12 位随机数或计数器
     * @param randB 62 位随机数
     * @return UUIDv7 实例
     */
    private static UUID assemble(long timestamp, int randA, long randB) {
        long msb = timestamp << 16 | 0x7000L | randA & 0x0FFF;
        long lsb = 0x8000000000000000L | randB & 0x3FFFFFFFFFFFFFFFL;
        return new UUID(msb, lsb);
    }

    /**
     * 单调生成器的状态载体：时间戳 + 12 位计数器。
     */
    private static final class Holder {
        private long lastMillis;
        private int counter;

        /**
         * 生成下一个单调 UUIDv7。
         *
         * @return UUIDv7 实例
         */
        synchronized UUID next() {
            long now = System.currentTimeMillis();
            byte[] entropy = new byte[8];
            RANDOM.nextBytes(entropy);
            if (now > lastMillis) {
                lastMillis = now;
                // 换毫秒时随机起一个计数器起点，避免每毫秒都从 0 开始让 ID 可预测
                counter = RANDOM.nextInt(MAX_COUNTER);
            } else {
                // 时钟回拨或同一毫秒：沿用上一时间戳并递增计数器
                now = lastMillis;
                counter++;
                if (counter >= MAX_COUNTER) {
                    // 12 位计数器在一毫秒内用完：逻辑时钟前进 1ms，避免 ID 回退
                    lastMillis = now + 1;
                    now = lastMillis;
                    counter = 0;
                }
            }
            return buildMonotonic(now, counter, entropy);
        }
    }
}
