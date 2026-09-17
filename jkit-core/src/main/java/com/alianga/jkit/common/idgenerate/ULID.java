package com.alianga.jkit.common.idgenerate;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * ULID（Universally Unique Lexicographically Sortable Identifier）。
 *
 * <p>26 个 Crockford Base32 字符：前 10 位是毫秒时间戳（48 位），后 16 位是随机数（80 位）。
 * 按字符串排序即按生成时间排序，比 UUID 更适合做数据库索引键——UUIDv4 的随机前缀会让
 * B+Tree 频繁页分裂，ULID 是单调追加的。
 *
 * <p>两种生成方式：
 * <ul>
 *   <li>{@link #next()}：每调用一次取 80 位随机数，同一毫秒内的多个 ID <b>顺序不确定</b>。</li>
 *   <li>{@link #nextMonotonic()}：同一毫秒内在上一个随机值上 {@code +1}，保证单 JVM 内严格递增。
 *       多实例部署时同毫秒仍可能交错（随机部分不是全局同步的）。</li>
 * </ul>
 *
 * <p>字母表去掉了易混淆的 I / L / O / U，解析时把 {@code I}/{@code L} 当作 {@code 1}、
 * {@code O} 当作 {@code 0}，大小写不敏感；含 {@code U} 或非法字符一律拒绝。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class ULID {
    /**
     * Crockford Base32 字母表（去掉 I、L、O、U）。
     */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /**
     * ULID 字符串长度：10 位时间戳 + 16 位随机数。
     */
    public static final int LENGTH = 26;

    /**
     * 时间戳部分占用的字符数。
     */
    private static final int TIME_CHARS = 10;

    /**
     * 随机部分占用的字符数。
     */
    private static final int RANDOM_CHARS = 16;

    /**
     * 随机部分字节数（80 位）。
     */
    private static final int RANDOM_BYTES = 10;

    /**
     * 时间戳上限：2^48，超出即溢出（公元 10889 年）。
     */
    private static final long MAX_TIMESTAMP = 1L << 48;

    /**
     * 非单调共享实例：纯随机，无锁。
     */
    private static final ULID RANDOM = new ULID(false);

    /**
     * 单调共享实例：同毫秒递增，需要同步。
     */
    private static final ULID MONOTONIC = new ULID(true);

    private final SecureRandom random;
    private final boolean monotonic;

    private long lastTime;
    private final byte[] lastRandomBytes;

    /**
     * 构造 ULID 生成器。
     *
     * @param monotonic 为 {@code true} 时同一毫秒内在上一个随机值上递增，保证严格单调
     */
    public ULID(boolean monotonic) {
        this(new SecureRandom(), monotonic);
    }

    /**
     * 构造 ULID 生成器（可注入随机源，便于测试）。
     *
     * @param random 随机数生成器
     * @param monotonic 为 {@code true} 时同一毫秒内递增
     */
    public ULID(SecureRandom random, boolean monotonic) {
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        this.random = random;
        this.monotonic = monotonic;
        this.lastRandomBytes = new byte[RANDOM_BYTES];
    }

    /**
     * 生成一个 ULID 字符串（26 字符，随机部分纯随机）。
     *
     * @return ULID 字符串
     */
    public static String next() {
        return RANDOM.generate();
    }

    /**
     * 生成一个单调 ULID 字符串：同一毫秒内在上一个随机值上递增。
     *
     * @return ULID 字符串
     */
    public static String nextMonotonic() {
        return MONOTONIC.generate();
    }

    /**
     * 生成一个 ULID 字符串，可指定是否单调。
     *
     * @param monotonic 是否需要同一毫秒内严格递增
     * @return ULID 字符串
     */
    public static String next(boolean monotonic) {
        return monotonic ? nextMonotonic() : next();
    }

    /**
     * 生成本实例的下一个 ULID。
     *
     * @return ULID 字符串
     */
    public String generate() {
        if (!monotonic) {
            byte[] entropy = new byte[RANDOM_BYTES];
            random.nextBytes(entropy);
            return encode(System.currentTimeMillis(), entropy);
        }
        return generateMonotonic();
    }

    /**
     * 单调生成：时间戳相同时随机部分 {@code +1}（带进位）；时间戳前进时重新取随机数。
     *
     * @return ULID 字符串
     */
    private synchronized String generateMonotonic() {
        long now = System.currentTimeMillis();
        if (now > lastTime) {
            lastTime = now;
            random.nextBytes(lastRandomBytes);
        } else {
            // 时钟回拨或同一毫秒：沿用上一个时间戳，随机部分进位
            now = lastTime;
            increment(lastRandomBytes);
        }
        return encode(now, lastRandomBytes);
    }

    /**
     * 解析 ULID 字符串。
     *
     * @param ulid ULID 字符串（26 字符，大小写不敏感）
     * @return 解析出的时间戳与随机部分
     * @throws IllegalArgumentException 长度不为 26 或含非法字符时抛出
     */
    public static Value parse(String ulid) {
        byte[] values = decodeChars(ulid);
        long time = 0;
        for (int i = 0; i < TIME_CHARS; i++) {
            time = time << 5 | values[i];
        }
        byte[] entropy = new byte[RANDOM_BYTES];
        // 每个字符 5 位，按与 encode 相同的位序（高位在前）还原成 10 字节
        int bit = 0;
        for (int i = 0; i < RANDOM_CHARS; i++) {
            int v = values[TIME_CHARS + i];
            for (int b = 4; b >= 0; b--) {
                if ((v >>> b & 1) != 0) {
                    entropy[bit >>> 3] |= (byte) (1 << (8 - (bit & 7) - 1));
                }
                bit++;
            }
        }
        return new Value(time, entropy);
    }

    /**
     * 判断字符串是否是合法 ULID。
     *
     * @param ulid 待校验字符串，可为 {@code null}
     * @return 合法返回 {@code true}
     */
    public static boolean isUlid(String ulid) {
        if (ulid == null || ulid.length() != LENGTH) {
            return false;
        }
        try {
            decodeChars(ulid);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 把 ULID 转成 16 字节紧凑布局。
     *
     * @param ulid ULID 字符串
     * @return 16 字节数组：前 6 字节毫秒时间戳（大端），后 10 字节随机数
     */
    public static byte[] toBytes(String ulid) {
        byte[] values = decodeChars(ulid);
        byte[] out = new byte[16];
        long time = 0;
        for (int i = 0; i < TIME_CHARS; i++) {
            time = time << 5 | values[i];
        }
        for (int i = 5; i >= 0; i--) {
            out[i] = (byte) time;
            time >>>= 8;
        }
        System.arraycopy(parse(ulid).randomBytes(), 0, out, 6, RANDOM_BYTES);
        return out;
    }

    /**
     * 把 {@link #toBytes(String)} 的字节布局还原成 ULID 字符串。
     *
     * @param bytes 16 字节数组
     * @return ULID 字符串
     * @throws IllegalArgumentException 字节数组长度不为 16 时抛出
     */
    public static String fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("ULID bytes must be 16 bytes, got: "
                    + (bytes == null ? "null" : bytes.length));
        }
        long time = 0;
        for (int i = 0; i < 6; i++) {
            time = time << 8 | bytes[i] & 0xFFL;
        }
        byte[] entropy = Arrays.copyOfRange(bytes, 6, 16);
        return encode(time, entropy);
    }

    /**
     * ULID 的解析结果：毫秒时间戳 + 80 位随机部分。
     *
     * @since 2.0.2
     */
    public static final class Value {
        private final long timestamp;
        private final byte[] entropy;

        private Value(long timestamp, byte[] entropy) {
            this.timestamp = timestamp;
            this.entropy = entropy;
        }

        /**
         * 生成时刻的毫秒时间戳。
         *
         * @return 毫秒时间戳
         */
        public long timestamp() {
            return timestamp;
        }

        /**
         * 随机部分（10 字节副本）。
         *
         * @return 随机部分
         */
        public byte[] randomBytes() {
            return Arrays.copyOf(entropy, entropy.length);
        }

        /**
         * 还原成 26 字符 ULID 字符串。
         *
         * @return ULID 字符串
         */
        @Override
        public String toString() {
            return encode(timestamp, entropy);
        }
    }

    /**
     * 逐字符解码为 0–31 的数值。
     *
     * @param ulid ULID 字符串
     * @return 26 个 0–31 的数值
     */
    private static byte[] decodeChars(String ulid) {
        if (ulid == null || ulid.length() != LENGTH) {
            throw new IllegalArgumentException("ULID must be " + LENGTH + " characters: " + ulid);
        }
        byte[] values = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            char c = ulid.charAt(i);
            int v = valueOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Illegal ULID character '" + c + "' at index " + i);
            }
            values[i] = (byte) v;
        }
        return values;
    }

    /**
     * 单个字符转数值：数字直接取；I / L 视作 1，O 视作 0；U 与其它的非法。
     *
     * @param c 字符
     * @return 0–31 的数值，非法返回 -1
     */
    private static int valueOf(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'z') {
            c = (char) (c - 32);
        }
        switch (c) {
            case 'I':
            case 'L':
                return 1;
            case 'O':
                return 0;
            case 'U':
                return -1;
            default:
                break;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 编码：时间戳 10 字符 + 随机 16 字符，均按大端（高位在前）输出。
     *
     * @param timestamp 毫秒时间戳
     * @param entropy 10 字节随机数
     * @return ULID 字符串
     */
    private static String encode(long timestamp, byte[] entropy) {
        if (timestamp < 0 || timestamp >= MAX_TIMESTAMP) {
            throw new IllegalArgumentException("ULID timestamp out of range: " + timestamp);
        }
        char[] out = new char[LENGTH];
        long time = timestamp;
        for (int i = TIME_CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        int bit = 0;
        for (int i = 0; i < RANDOM_CHARS; i++) {
            int value = 0;
            for (int b = 0; b < 5; b++) {
                int byteIndex = bit >>> 3;
                int shift = 8 - (bit & 7) - 1;
                value = value << 1 | (entropy[byteIndex] >>> shift & 1);
                bit++;
            }
            out[TIME_CHARS + i] = ALPHABET[value];
        }
        return new String(out);
    }

    /**
     * 80 位随机部分 {@code +1}，从最低字节向高位进位；溢出后从 0 重新开始。
     *
     * @param bytes 随机部分
     */
    private static void increment(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            int v = bytes[i] & 0xFF;
            if (v == 0xFF) {
                bytes[i] = 0;
            } else {
                bytes[i] = (byte) (v + 1);
                return;
            }
        }
        // 80 位全 1 后归零，实际概率极低
        Arrays.fill(bytes, (byte) 0);
    }
}
