package com.alianga.jkit.crypto;

import com.alianga.jkit.Base64Utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于上下文派生密钥流的对称混淆工具，用于给已有密钥或短敏感数据再套一层包装，
 * 避免它们以明文形式出现在响应体里。
 *
 * <p><b>这不是加密算法</b>。本类的设计目标是<b>提高逆向门槛</b>，而非提供密码学强度：
 * 它把明文与「调用方上下文 + 内置 pepper」派生出的密钥流做异或，并附加一段截断校验和。
 * 因此方法命名为 {@code obfuscate} / {@code deobfuscate} 而非 {@code encrypt} / {@code decrypt}，
 * 以免在调用点被误认为等价于 AES 等标准算法。</p>
 *
 * <p>它能提供的性质：
 * <ul>
 *   <li>仅抓到密文拿不到可用明文，还需同时还原算法逻辑并知道全部上下文；</li>
 *   <li>每次调用都掺入随机 salt，相同明文与上下文也会产生不同密文，无法重放比对；</li>
 *   <li>上下文或密文任一被改动，校验和都会失配并报错。</li>
 * </ul>
 *
 * <p><b>它不能提供的性质（务必如实评估）</b>：
 * <ul>
 *   <li>算法未经密码学同行评审，<b>不能替代 AES 等标准算法</b>；</li>
 *   <li>算法规则终归可被还原（前端实现即使打包混淆也可被反混淆）；</li>
 *   <li>一旦算法被还原，低熵上下文（如用户名）与可枚举上下文（如时间戳）可被离线暴力派生；</li>
 *   <li>校验和不带密钥，只能发现意外损坏与上下文不匹配，<b>不是 MAC</b>，无法抵抗有意伪造；</li>
 *   <li>能直接调用本类（或其前端对应实现）的攻击者可径直取得明文。</li>
 * </ul>
 * 传输安全应依赖 HTTPS，静态存储安全应依赖不下发的独占密钥。
 *
 * <p>密文格式（标准 Base64，含填充）：{@code salt(8) + 异或密文(明文等长) + 校验和(4)}。
 * <br>密钥流派生：以 {@code 上下文1 + "::" + 上下文2 + ... + "::" + salt + pepper} 为初始状态，
 * 每轮做 64 次 SHA-256，将该轮摘要按字节翻转后拼入密钥流，
 * 并以 {@code SHA-256(翻转摘要 + 初始状态)} 作为下一轮状态，直到密钥流覆盖明文长度。
 *
 * <p>本类线程安全：无可变共享状态，{@link SecureRandom} 本身可并发使用。
 *
 * @author 郑明亮
 * @version 1.0.0
 */
public final class ContextObfuscator {
    /**
     * 内置 pepper 的第一段。
     *
     * <p>注意：这是<b>硬编码在算法里的固定常量（pepper）</b>，与每次随机生成的 salt 是两回事。
     * 拆成多段在运行时拼接，是为了避免整段常量被直接静态搜索到，属刻意的抗逆向措施。
     * 任何互操作实现（如前端 JS）都必须使用完全相同的字节。</p>
     */
    private static final byte[] PEPPER_PART_A = {0x37, (byte) 0xA2, 0x5C, 0x19, (byte) 0xE4, 0x6B};

    /**
     * 内置 pepper 的第二段，语义同 {@link #PEPPER_PART_A}
     */
    private static final byte[] PEPPER_PART_B = {(byte) 0xD1, 0x0F, (byte) 0x88, 0x4A, (byte) 0xB3, 0x7E};

    /**
     * 内置 pepper 的尾段，语义同 {@link #PEPPER_PART_A}
     */
    private static final byte[] PEPPER_TAIL = {0x5A, (byte) 0xC3, 0x2D, 0x71};

    /**
     * 每生成一段密钥流所做的摘要轮数。互操作实现必须一致。
     */
    private static final int DIGEST_ROUNDS = 64;

    /**
     * 随机 salt 的字节数
     */
    private static final int SALT_LENGTH = 8;

    /**
     * 校验和字节数，取明文 SHA-256 摘要的前若干字节
     */
    private static final int CHECKSUM_LENGTH = 4;

    /**
     * 上下文之间以及上下文与 salt 之间的分隔符（{@code "::"}）
     */
    private static final byte[] CONTEXT_SEPARATOR = {':', ':'};

    /**
     * 派生与校验使用的摘要算法，JDK 规范要求所有实现都必须支持
     */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * 生成 salt 的随机源。
     *
     * <p>此处刻意使用 {@code new SecureRandom()} 而非 {@link SecureRandom#getInstanceStrong()}：
     * 后者在 Linux 上会解析为读取 {@code /dev/random} 的阻塞实现，容器内熵不足时会长时间卡住。
     * salt 在本算法中承担 nonce 角色，只需不可预测，{@code /dev/urandom} 级别的随机性已足够。</p>
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ContextObfuscator() {
    }

    /**
     * 用给定上下文混淆明文字节。
     *
     * <p>每次调用都会生成新的随机 salt，因此相同入参多次调用会得到不同结果。</p>
     *
     * @param plain 待混淆的明文字节，为 {@code null} 或空数组时返回空字符串
     * @param contexts 参与密钥流派生的上下文，顺序敏感，解混淆时必须逐项一致；
     *        其中的 {@code null} 元素按空字符串处理
     * @return 标准 Base64 编码的密文（{@code salt + 异或密文 + 校验和}）
     * @throws IllegalStateException 运行环境不支持 SHA-256 时抛出
     */
    public static String obfuscate(byte[] plain, String... contexts) {
        if (plain == null || plain.length == 0) {
            return "";
        }
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        byte[] keyStream = deriveKeyStream(derivationBase(salt, contexts), plain.length);
        byte[] masked = xor(plain, keyStream);
        byte[] checksum = checksum(plain);
        return Base64Utils.encodeToString(concat(salt, masked, checksum));
    }

    /**
     * 还原经 {@link #obfuscate(byte[], String...)} 混淆的字节。
     *
     * @param obfuscated 标准 Base64 编码的密文，为 {@code null} 或空字符串时返回空数组
     * @param contexts 参与密钥流派生的上下文，必须与混淆时逐项一致
     * @return 还原后的明文字节
     * @throws IllegalArgumentException 密文不是合法 Base64、长度不足，
     *         或校验和失配（上下文不一致、密文被篡改或损坏）时抛出
     * @throws IllegalStateException 运行环境不支持 SHA-256 时抛出
     */
    public static byte[] deobfuscate(String obfuscated, String... contexts) {
        if (obfuscated == null || obfuscated.isEmpty()) {
            return new byte[0];
        }
        byte[] raw = Base64Utils.decode(obfuscated);
        int maskedLength = raw.length - SALT_LENGTH - CHECKSUM_LENGTH;
        if (maskedLength < 0) {
            throw new IllegalArgumentException("obfuscated text is too short: " + raw.length
                    + " bytes, at least " + (SALT_LENGTH + CHECKSUM_LENGTH) + " required");
        }
        byte[] salt = new byte[SALT_LENGTH];
        System.arraycopy(raw, 0, salt, 0, SALT_LENGTH);
        byte[] masked = new byte[maskedLength];
        System.arraycopy(raw, SALT_LENGTH, masked, 0, maskedLength);
        byte[] expectedChecksum = new byte[CHECKSUM_LENGTH];
        System.arraycopy(raw, SALT_LENGTH + maskedLength, expectedChecksum, 0, CHECKSUM_LENGTH);

        byte[] keyStream = deriveKeyStream(derivationBase(salt, contexts), maskedLength);
        byte[] plain = xor(masked, keyStream);
        if (!constantTimeEquals(checksum(plain), expectedChecksum)) {
            throw new IllegalArgumentException("checksum mismatch: contexts do not match or data is corrupted");
        }
        return plain;
    }

    /**
     * 拼装密钥流派生的初始状态：{@code 上下文以 "::" 连接 + "::" + salt + pepper}。
     *
     * @param salt 本次随机生成或从密文中取出的 salt
     * @param contexts 调用方上下文，可为 {@code null}
     * @return 派生初始状态字节
     */
    private static byte[] derivationBase(byte[] salt, String[] contexts) {
        List<byte[]> parts = new ArrayList<byte[]>();
        if (contexts != null) {
            for (int i = 0; i < contexts.length; i++) {
                if (i > 0) {
                    parts.add(CONTEXT_SEPARATOR);
                }
                String context = contexts[i] == null ? "" : contexts[i];
                parts.add(context.getBytes(StandardCharsets.UTF_8));
            }
        }
        // 末尾固定再接一个分隔符，使上下文段与 salt 段之间始终有边界
        parts.add(CONTEXT_SEPARATOR);
        parts.add(salt);
        parts.add(pepper());
        return concat(parts.toArray(new byte[parts.size()][]));
    }

    /**
     * 派生指定长度的密钥流。
     *
     * @param base 派生初始状态
     * @param length 需要的密钥流字节数
     * @return 长度为 {@code length} 的密钥流
     * @throws IllegalStateException 运行环境不支持 SHA-256 时抛出
     */
    private static byte[] deriveKeyStream(byte[] base, int length) {
        MessageDigest digest = newDigest();
        byte[] keyStream = new byte[length];
        int filled = 0;
        byte[] state = base;
        while (filled < length) {
            for (int round = 0; round < DIGEST_ROUNDS; round++) {
                state = digest.digest(state);
            }
            // 按字节翻转后再拼接，单纯为增加还原算法的成本
            byte[] reversed = reverse(state);
            int copyLength = Math.min(reversed.length, length - filled);
            System.arraycopy(reversed, 0, keyStream, filled, copyLength);
            filled += copyLength;
            // 下一轮状态依赖上一轮输出，避免长明文时密钥流出现周期
            state = digest.digest(concat(reversed, base));
        }
        return keyStream;
    }

    /**
     * 运行时拼出内置 pepper。
     *
     * @return 16 字节 pepper
     */
    private static byte[] pepper() {
        return concat(PEPPER_PART_A, PEPPER_PART_B, PEPPER_TAIL);
    }

    /**
     * 取明文 SHA-256 摘要的前 {@link #CHECKSUM_LENGTH} 个字节作为校验和。
     *
     * @param data 明文字节
     * @return 校验和字节
     * @throws IllegalStateException 运行环境不支持 SHA-256 时抛出
     */
    private static byte[] checksum(byte[] data) {
        byte[] digest = newDigest().digest(data);
        byte[] checksum = new byte[CHECKSUM_LENGTH];
        System.arraycopy(digest, 0, checksum, 0, CHECKSUM_LENGTH);
        return checksum;
    }

    /**
     * 创建摘要实例。{@link MessageDigest} 非线程安全，因此每次调用都新建。
     *
     * @return SHA-256 摘要实例
     * @throws IllegalStateException 运行环境不支持 SHA-256 时抛出
     */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范要求所有实现都支持 SHA-256，走到这里说明运行环境被裁剪过
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", e);
        }
    }

    /**
     * 逐字节异或。
     *
     * @param source 源字节
     * @param mask 掩码字节，长度不得小于 {@code source}
     * @return 异或结果，长度与 {@code source} 一致
     */
    private static byte[] xor(byte[] source, byte[] mask) {
        if (mask.length < source.length) {
            throw new IllegalStateException("key stream is shorter than data: "
                    + mask.length + " < " + source.length);
        }
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (byte) (source[i] ^ mask[i]);
        }
        return result;
    }

    /**
     * 按字节翻转数组。
     *
     * @param source 源字节
     * @return 翻转后的新数组
     */
    private static byte[] reverse(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[source.length - 1 - i];
        }
        return result;
    }

    /**
     * 依次拼接多个字节数组。
     *
     * @param parts 待拼接的数组
     * @return 拼接后的新数组
     */
    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    /**
     * 恒定时间比较两个字节数组，避免按字节短路比较泄漏匹配前缀长度。
     *
     * @param left 左值
     * @param right 右值
     * @return 长度与内容都相同时返回 {@code true}
     */
    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }
}
