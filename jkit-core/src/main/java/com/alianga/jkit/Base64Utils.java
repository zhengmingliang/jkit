package com.alianga.jkit;

import com.alianga.jkit.json.internal.utils.EnvUtils;
import com.alianga.jkit.log.Log;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * base64 编解码。
 *
 * <h2>为什么它和 {@link Base64} 并存</h2>
 * 两者不是重复实现，各自负责不同的能力，<b>不要把其中一方合并到另一方</b>：
 *
 * <ul>
 * <li><b>本类的独有价值是「零拷贝」API</b>：{@link #encode(byte[], char[], int)} /
 *     {@link #encode(byte[], byte[], int)} 直接把结果写进调用方已有的缓冲区，
 *     {@link #decode(char[], int, int)} / {@link #decode(byte[], int, int)} 直接从缓冲区的一段范围解码，
 *     全程不产生中间 {@code String} 或数组。JDK 的 {@code Base64} 没有等价 API。
 *     JSON 模块的序列化与反序列化只用这几个方法（见 {@code JSONWriter}、{@code JSONByteArrayWriter}、
 *     {@code JSONCharArrayWriter}、{@code JSONTypeDeserializer}），实测比 JDK 的整块编码更快。</li>
 * <li><b>整块 {@code byte[]} ↔ {@code String} 转换哪个更快取决于 JDK 版本</b>，
 *     因此 {@link #encodeToString(byte[])} 和 {@link #decode(String)} 会按运行时版本自动选择，
 *     调用方无需关心。阈值见下表。</li>
 * </ul>
 *
 * <h2>实测数据（本机 Corretto 8/9/11/17 + GraalVM 21，固定循环数、充分预热、15 轮取中位数）</h2>
 * 表中为 {@code JDK 实现 / 本类手写实现} 的吞吐比，&gt;1 表示 JDK 更快：
 *
 * <pre>
 *          整块编码              整块解码
 * JDK 8    0.83~0.85（手写快）   0.59~0.61（手写快 1.6~1.7 倍）
 * JDK 9    1.07~1.14（JDK 快）   0.61~0.68（手写快 1.5~1.6 倍）
 * JDK 11   0.95~0.99（持平）     1.42~1.50（JDK 快）
 * JDK 17   1.10~1.13（JDK 快）   1.29~1.43（JDK 快）
 * JDK 21   1.94~4.06（JDK 快）   4.07~4.85（JDK 快）
 * </pre>
 *
 * <p>原因是 JDK 为 {@code java.util.Base64} 陆续加入了 JIT intrinsic（编码较早、解码较晚），
 * 低版本上没有 intrinsic 时手写实现更有优势。由此取两个阈值：
 * 编码只在 JDK 8 上用手写（9 起 JDK 反超，11 上持平，17+ JDK 明显更快）；
 * 解码在 JDK 11 处干净翻转。
 *
 * <p>本类另外提供 JDK 没有的宽松解码（{@link #decodeLenient}）和 MIME 解码（{@link #decodeMime}），
 * 以及若干历史兼容 API。字符串相关方法一律按 UTF-8 处理。
 *
 * <h2>为什么 decode 保持严格（要求显式补位）</h2>
 * {@code decode} 系列要求长度是 4 的整数倍，不补位的输入会抛异常；需要宽松语义请用
 * {@link #decodeLenient}。<b>不要把 decode 改成宽松</b>，有两条原因：
 * <ol>
 * <li>{@link #decode(byte[], int, int)} 用 {@code n = len >> 2} 推导输出长度，整段实现都建立在
 *     「长度是 4 的倍数」这个前提上。只去掉长度校验不会变宽松，而是会<b>静默丢掉末尾 2~3 个字符</b>
 *     （例如 {@code "abc"} 本应解出 2 字节，实际返回空数组）。真正支持不补位需要一整套余数处理，
 *     而这正是 {@code decodeLenient} 已经实现的。</li>
 * <li>这几个方法是 JSON 反序列化的热路径（{@code JSONTypeDeserializer} 直接从解析缓冲区的一段范围解码）。
 *     严格模式能让损坏的 base64 字段立刻报错；放宽后会退化成静默产出更短的字节数组，
 *     等于把「解析失败」变成「数据悄悄少了一截」。</li>
 * </ol>
 */
public class Base64Utils {
    private static final Log log = Log.get(Base64Utils.class);

    /**
     * 整块编码是否走 {@link Base64}：JDK 9 起其实现不慢于手写版，17+ 明显更快。
     *
     * <p>{@code static final boolean}，JIT 会把分支常量折叠掉，运行时无判断开销。
     */
    private static final boolean USE_JDK_ENCODER = EnvUtils.JDK_9_PLUS;

    /**
     * 整块解码是否走 {@link Base64}：JDK 11 及以上其实现更快
     * （JDK 8/9/10 上手写版快 1.5~1.7 倍）。
     */
    private static final boolean USE_JDK_DECODER = EnvUtils.JDK_11_PLUS;

    private static final char[] BASE64_CHARS = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
    };

    private static final int[] BASE64_VALUES = new int[]{
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57,
            58, 59, 60, 61, -1, -1, -1, -2, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38,
            39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1
    };

    // ==================== encode ====================

    /**
     * 将整块字节编码为 base64 字节。
     *
     * <p>按 JDK 版本选择更快的实现，两条实现输出一致。
     *
     * @param src 待编码字节
     * @return base64 的 ASCII 字节
     */
    public static byte[] encode(byte[] src) {
        if (USE_JDK_ENCODER) {
            return Base64.getEncoder().encode(src);
        }
        return encodeInternal(src);
    }

    /**
     * 本类手写的整块编码实现（输出 byte[]）。包级可见以便测试对比等价性。
     *
     * @param src 待编码字节
     * @return base64 的 ASCII 字节
     */
    static byte[] encodeInternal(byte[] src) {
        int tlen = ((src.length + 2) / 3) << 2;
        byte[] dst = new byte[tlen];
        encode(src, dst, 0);
        return dst;
    }

    /**
     * 将字节数组编码为 base64 的 ASCII 字节并写入目标数组。
     *
     * @param src    待编码字节
     * @param dst    目标数组，需保证从 {@code offset} 起有 {@code ((src.length + 2) / 3) * 4} 个可写位置
     * @param offset 目标数组的写入起始下标
     * @return 实际写入的字节数
     */
    public static int encode(byte[] src, byte[] dst, int offset) {
        int n = src.length / 3;
        int n3 = n * 3;
        int rem = src.length - n3;
        int begin = offset;
        int srcOff = 0;
        for (int i = 0; i < n; ++i) {
            int b1 = src[srcOff++] & 0xff;
            int b2 = src[srcOff++] & 0xff;
            int b3 = src[srcOff++] & 0xff;
            int bits = b1 << 16 | b2 << 8 | b3;
            dst[offset++] = (byte) BASE64_CHARS[b1 >> 2];
            dst[offset++] = (byte) BASE64_CHARS[(bits >> 12) & 0x3f];
            dst[offset++] = (byte) BASE64_CHARS[(bits >> 6) & 0x3f];
            dst[offset++] = (byte) BASE64_CHARS[bits & 0x3f];
        }
        if (rem == 1) {
            int b = src[n3] & 0xff;
            dst[offset++] = (byte) BASE64_CHARS[b >> 2];
            dst[offset++] = (byte) BASE64_CHARS[(b & 3) << 4];
            dst[offset++] = '=';
            dst[offset++] = '=';
        } else if (rem == 2) {
            int b1 = src[n3] & 0xff;
            int b2 = src[n3 + 1] & 0xff;
            dst[offset++] = (byte) BASE64_CHARS[b1 >> 2];
            dst[offset++] = (byte) BASE64_CHARS[(b1 & 3) << 4 | b2 >> 4];
            dst[offset++] = (byte) BASE64_CHARS[(b2 & 0xf) << 2];
            dst[offset++] = '=';
        }
        return offset - begin;
    }

    /**
     * 将字节数组编码为 base64 字符并写入目标字符数组。
     *
     * @param src    待编码字节
     * @param dst    目标数组，需保证从 {@code offset} 起有 {@code ((src.length + 2) / 3) * 4} 个可写位置
     * @param offset 目标数组的写入起始下标
     * @return 实际写入的字符数
     */
    public static int encode(byte[] src, char[] dst, int offset) {
        int n = src.length / 3;
        int n3 = n * 3;
        int rem = src.length - n3;
        int begin = offset;
        int srcOff = 0;
        for (int i = 0; i < n; ++i) {
            int b1 = src[srcOff++] & 0xff;
            int b2 = src[srcOff++] & 0xff;
            int b3 = src[srcOff++] & 0xff;
            int bits = b1 << 16 | b2 << 8 | b3;
            dst[offset++] = BASE64_CHARS[b1 >> 2];
            dst[offset++] = BASE64_CHARS[(bits >> 12) & 0x3f];
            dst[offset++] = BASE64_CHARS[(bits >> 6) & 0x3f];
            dst[offset++] = BASE64_CHARS[bits & 0x3f];
        }
        if (rem == 1) {
            int b = src[n3] & 0xff;
            dst[offset++] = BASE64_CHARS[b >> 2];
            dst[offset++] = BASE64_CHARS[(b & 3) << 4];
            dst[offset++] = '=';
            dst[offset++] = '=';
        } else if (rem == 2) {
            int b1 = src[n3] & 0xff;
            int b2 = src[n3 + 1] & 0xff;
            dst[offset++] = BASE64_CHARS[b1 >> 2];
            dst[offset++] = BASE64_CHARS[(b1 & 3) << 4 | b2 >> 4];
            dst[offset++] = BASE64_CHARS[(b2 & 0xf) << 2];
            dst[offset++] = '=';
        }
        return offset - begin;
    }

    /**
     * 将整块字节编码为 base64 字符串。
     *
     * <p>按 JDK 版本选择更快的实现，见 {@link #USE_JDK_ENCODER}。两条实现输出完全一致。
     *
     * @param src 待编码字节
     * @return base64 字符串
     */
    public static String encodeToString(byte[] src) {
        if (USE_JDK_ENCODER) {
            return Base64.getEncoder().encodeToString(src);
        }
        return encodeToStringInternal(src);
    }

    /**
     * 本类手写的整块编码实现，JDK 16 以下比 {@link Base64} 更快。
     *
     * <p>包级可见以便单元测试直接对比两条实现的等价性。
     *
     * @param src 待编码字节
     * @return base64 字符串
     */
    static String encodeToStringInternal(byte[] src) {
        int tlen = ((src.length + 2) / 3) << 2;
        if (EnvUtils.JDK_9_PLUS) {
            byte[] dst = new byte[tlen];
            encode(src, dst, 0);
            return UnsafeHelper.getAsciiString(dst);
        } else {
            char[] dst = new char[tlen];
            encode(src, dst, 0);
            return UnsafeHelper.getString(dst);
        }
    }

    // ==================== strict decode ====================

    /**
     * 解码 base64 字符串。
     *
     * <p>按 JDK 版本选择更快的实现，见 {@link #USE_JDK_DECODER}。两条实现的结果与抛出的异常一致。
     *
     * @param src base64 字符串
     * @return 解码后的字节
     */
    public static byte[] decode(String src) {
        if (USE_JDK_DECODER) {
            // JDK 的解码器允许最后一组不补位（例如 "abc" 会被解成 2 字节），
            // 而本类的契约要求长度必须是 4 的整数倍（见 decode(byte[], int, int) 的校验）。
            // 委托前先做同样的长度校验，否则同一份数据在 JDK 8 上抛异常、在 JDK 11+ 上却能解开。
            checkBase64Length(src.length());
            return Base64.getDecoder().decode(src);
        }
        return decodeInternal(src);
    }

    /**
     * 校验 base64 长度必须是 4 的整数倍（即要求显式补位），与手写解码实现保持一致的严格程度。
     *
     * @param len 待解码长度
     */
    private static void checkBase64Length(int len) {
        if ((len & 3) > 0) {
            throw new IllegalArgumentException("The length error of base64 should be an integer multiple of 4");
        }
    }

    /**
     * 本类手写的整块解码实现，JDK 11 以下比 {@link Base64} 快约 1.6~2 倍。
     *
     * <p>包级可见以便单元测试直接对比两条实现的等价性。
     *
     * @param src base64 字符串
     * @return 解码后的字节
     */
    static byte[] decodeInternal(String src) {
        if (EnvUtils.JDK_9_PLUS) {
            byte[] buf = (byte[]) UnsafeHelper.getStringValue(src);
            return decode(buf, 0, buf.length);
        } else {
            char[] buf = (char[]) UnsafeHelper.getStringValue(src);
            return decode(buf, 0, buf.length);
        }
    }

    /**
     * 严格模式解码整个 base64 字节数组。
     *
     * @param src base64 的 ASCII 字节
     * @return 解码后的字节；长度不是 4 的整数倍或包含非法字符时抛出 {@link IllegalArgumentException}
     */
    public static byte[] decode(byte[] src) {
        return decode(src, 0, src.length);
    }

    /**
     * 严格模式解码整个 base64 字符数组。
     *
     * @param in base64 字符
     * @return 解码后的字节；长度不是 4 的整数倍或包含非法字符时抛出 {@link IllegalArgumentException}
     */
    public static byte[] decode(char[] in) {
        return decode(in, 0, in.length);
    }

    /**
     * 严格模式解码 base64 字节数组的指定片段，要求长度是 4 的整数倍（必须显式补位）。
     *
     * @param src    base64 的 ASCII 字节
     * @param offset 起始下标
     * @param len    参与解码的长度
     * @return 解码后的字节，{@code len} 为 0 时返回空数组；长度非法或数据非法时抛出 {@link IllegalArgumentException}
     */
    public static byte[] decode(byte[] src, int offset, int len) {
        if (len == 0) {
            return new byte[0];
        }
        if ((len & 3) > 0) {
            throw new IllegalArgumentException("The length error of base64 should be an integer multiple of 4");
        }
        try {
            int n = len >> 2;
            int tlen = n * 3;
            int srcOff = offset;
            int endOffset = offset + len;
            byte[] dst;
            if (src[--endOffset] == '=') {
                --n;
                --tlen;
                int v;
                if ((v = src[--endOffset]) == '=') {
                    --tlen;
                    dst = new byte[tlen];
                    int v2 = BASE64_VALUES[src[--endOffset]];
                    int v1 = BASE64_VALUES[src[--endOffset]];
                    if (v1 > -1 && v2 > -1) {
                        dst[tlen - 1] = (byte) (v1 << 2 | (v2 >> 4));
                    } else {
                        throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
                    }
                } else {
                    dst = new byte[tlen];
                    int v3 = BASE64_VALUES[v];
                    int v2 = BASE64_VALUES[src[--endOffset]];
                    int v1 = BASE64_VALUES[src[--endOffset]];
                    if (v1 > -1 && v2 > -1 && v3 > -1) {
                        int bits = v1 << 10 | v2 << 4 | (v3 >> 2);
                        dst[tlen - 2] = (byte) (bits >> 8);
                        dst[tlen - 1] = (byte) bits;
                    } else {
                        throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
                    }
                }
            } else {
                dst = new byte[tlen];
            }
            int dstOff = 0;
            for (int i = 0; i < n; ++i) {
                int v1 = BASE64_VALUES[src[srcOff++]];
                int v2 = BASE64_VALUES[src[srcOff++]];
                int v3 = BASE64_VALUES[src[srcOff++]];
                int v4 = BASE64_VALUES[src[srcOff++]];
                if (v1 > -1 && v2 > -1 && v3 > -1 && v4 > -1) {
                    int bits = v1 << 18 | v2 << 12 | v3 << 6 | v4;
                    dst[dstOff++] = (byte) (bits >> 16);
                    dst[dstOff++] = (byte) (bits >> 8);
                    dst[dstOff++] = (byte) bits;
                } else {
                    throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
                }
            }
            return dst;
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
        }
    }

    /**
     * 严格模式解码 base64 字符数组的指定片段，要求长度是 4 的整数倍（必须显式补位）。
     *
     * @param src    base64 字符
     * @param offset 起始下标
     * @param len    参与解码的长度
     * @return 解码后的字节，{@code len} 为 0 时返回空数组；长度非法或数据非法时抛出 {@link IllegalArgumentException}
     */
    public static byte[] decode(char[] src, int offset, int len) {
        if (len == 0) {
            return new byte[0];
        }
        if ((len & 3) > 0) {
            throw new IllegalArgumentException("The length error of base64 should be an integer multiple of 4");
        }
        try {
            int n = len >> 2;
            int tlen = n * 3;
            int srcOff = offset;
            int endOffset = offset + len;
            byte[] dst;
            if (src[--endOffset] == '=') {
                --n;
                --tlen;
                int v;
                if ((v = src[--endOffset]) == '=') {
                    --tlen;
                    dst = new byte[tlen];
                    int v2 = BASE64_VALUES[src[--endOffset]];
                    int v1 = BASE64_VALUES[src[--endOffset]];
                    if (v1 > -1 && v2 > -1) {
                        dst[tlen - 1] = (byte) (v1 << 2 | (v2 >> 4));
                    } else {
                        throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
                    }
                } else {
                    dst = new byte[tlen];
                    int v3 = BASE64_VALUES[v];
                    int v2 = BASE64_VALUES[src[--endOffset]];
                    int v1 = BASE64_VALUES[src[--endOffset]];
                    if (v3 > -1 && v2 > -1 && v1 > -1) {
                        int bits = v1 << 10 | v2 << 4 | (v3 >> 2);
                        dst[tlen - 2] = (byte) (bits >> 8);
                        dst[tlen - 1] = (byte) bits;
                    } else {
                        throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
                    }
                }
            } else {
                dst = new byte[tlen];
            }
            int dstOff = 0;
            for (int i = 0; i < n; ++i) {
                int v1 = BASE64_VALUES[src[srcOff++]];
                int v2 = BASE64_VALUES[src[srcOff++]];
                int v3 = BASE64_VALUES[src[srcOff++]];
                int v4 = BASE64_VALUES[src[srcOff++]];
                if (v1 > -1 && v2 > -1 && v3 > -1 && v4 > -1) {
                    int bits = v1 << 18 | v2 << 12 | v3 << 6 | v4;
                    dst[dstOff++] = (byte) (bits >> 16);
                    dst[dstOff++] = (byte) (bits >> 8);
                    dst[dstOff++] = (byte) bits;
                } else {
                    throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
                }
            }
            return dst;
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("Base64 data error: " + new String(src, offset, len));
        }
    }

    // ==================== lenient decode ====================

    /**
     * 宽容模式解码：strip 尾部所有 '='，不足 4 倍数时补 'A'（值 0），
     * 行为与 java.util.Base64.getDecoder() / getUrlDecoder() 完全一致。
     * <p>
     * 规则：len % 4 == 0 走严格解码；len % 4 != 0 时 strip '=' 并补 'A' 解码，
     * 只取前 strippedLen*3/4 个字节。
     *
     * @param src base64 字符串
     * @return 解码后的字节
     */
    public static byte[] decodeLenient(String src) {
        if (EnvUtils.JDK_9_PLUS) {
            byte[] buf = (byte[]) UnsafeHelper.getStringValue(src);
            return decodeLenient(buf, 0, buf.length);
        } else {
            char[] buf = (char[]) UnsafeHelper.getStringValue(src);
            return decodeLenient(buf, 0, buf.length);
        }
    }

    /**
     * 宽容模式解码整个 base64 字节数组，允许缺省补位。
     *
     * @param src base64 的 ASCII 字节
     * @return 解码后的字节
     */
    public static byte[] decodeLenient(byte[] src) {
        return decodeLenient(src, 0, src.length);
    }

    /**
     * 宽容模式解码 base64 字节数组的指定片段：长度为 4 的整数倍时走严格解码，
     * 否则剥离尾部 {@code '='} 并补 {@code 'A'} 后解码，仅返回有效字节。
     *
     * @param src    base64 的 ASCII 字节
     * @param offset 起始下标
     * @param len    参与解码的长度
     * @return 解码后的字节，{@code len} 为 0 时返回空数组；补位或长度非法时抛出 {@link IllegalArgumentException}
     */
    public static byte[] decodeLenient(byte[] src, int offset, int len) {
        if (len == 0) {
            return new byte[0];
        }
        if (len % 4 != 0) {
            int strippedLen = len;
            while (strippedLen > 0 && src[offset + strippedLen - 1] == '=') {
                strippedLen--;
            }
            if (strippedLen == 0) {
                throw new IllegalArgumentException("Base64 data error: all padding");
            }
            if (strippedLen == 1) {
                throw new IllegalArgumentException("Base64 data error: invalid length");
            }
            // strippedLen % 4 == 1 → 非法（"QQQQQ", "QQQQQQQQQ"）
            if (strippedLen % 4 == 1) {
                throw new IllegalArgumentException("Base64 data error: invalid length");
            }
            // 含单 padding 的输入非法（"YQ=", "QQ=", "ab="）
            if (len > strippedLen && (len - strippedLen) != 2) {
                throw new IllegalArgumentException("Base64 data error: invalid padding");
            }
            int padLen = (4 - (strippedLen % 4)) % 4;
            char[] padded = new char[strippedLen + padLen];
            for (int i = 0; i < strippedLen; i++) {
                padded[i] = (char) src[offset + i];
            }
            for (int i = strippedLen; i < padded.length; i++) {
                padded[i] = 'A';
            }
            byte[] decoded = decode(padded, 0, padded.length);
            // 只取完整组的字节：paddedLen/4 组 * 3 字节/组
            int outLen = strippedLen * 3 / 4;
            if (outLen < decoded.length) {
                byte[] result = new byte[outLen];
                System.arraycopy(decoded, 0, result, 0, outLen);
                return result;
            }
            return decoded;
        }
        return decode(src, offset, len);
    }

    /**
     * 宽容模式解码 base64 字符数组的指定片段：长度为 4 的整数倍时走严格解码，
     * 否则剥离尾部 {@code '='} 并补 {@code 'A'} 后解码，仅返回有效字节。
     *
     * @param src    base64 字符
     * @param offset 起始下标
     * @param len    参与解码的长度
     * @return 解码后的字节，{@code len} 为 0 时返回空数组；补位或长度非法时抛出 {@link IllegalArgumentException}
     */
    public static byte[] decodeLenient(char[] src, int offset, int len) {
        if (len == 0) {
            return new byte[0];
        }
        if (len % 4 != 0) {
            int strippedLen = len;
            while (strippedLen > 0 && src[offset + strippedLen - 1] == '=') {
                strippedLen--;
            }
            if (strippedLen == 0) {
                throw new IllegalArgumentException("Base64 data error: all padding");
            }
            if (strippedLen == 1) {
                throw new IllegalArgumentException("Base64 data error: invalid length");
            }
            // strippedLen % 4 == 1 → 非法（"QQQQQ", "QQQQQQQQQ"）
            if (strippedLen % 4 == 1) {
                throw new IllegalArgumentException("Base64 data error: invalid length");
            }
            // 含单 padding 的输入非法（"YQ=", "QQ=", "ab="）
            if (len > strippedLen && (len - strippedLen) != 2) {
                throw new IllegalArgumentException("Base64 data error: invalid padding");
            }
            int padLen = (4 - (strippedLen % 4)) % 4;
            char[] padded = new char[strippedLen + padLen];
            System.arraycopy(src, offset, padded, 0, strippedLen);
            Arrays.fill(padded, strippedLen, padded.length, 'A');
            byte[] decoded = decode(padded, 0, padded.length);
            int outLen = strippedLen * 3 / 4;
            if (outLen < decoded.length) {
                byte[] result = new byte[outLen];
                System.arraycopy(decoded, 0, result, 0, outLen);
                return result;
            }
            return decoded;
        }
        return decode(src, offset, len);
    }

    // ==================== MIME decode ====================

    /**
     * MIME 宽容模式：忽略空白字符，然后执行 lenient 解码。
     * 行为与 java.util.Base64.getMimeDecoder() 一致。
     *
     * @param src base64 文本，可包含空格、制表符与换行
     * @return 解码后的字节；{@code src} 为 {@code null}、空串或全为空白字符时返回空数组
     */
    public static byte[] decodeMime(String src) {
        if (src == null || src.isEmpty()) {
            return new byte[0];
        }
        int p = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c != ' ' && c != '\r' && c != '\n' && c != '\t') {
                p++;
            }
        }
        if (p == 0) {
            return new byte[0];
        }
        char[] buf = new char[p];
        p = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c != ' ' && c != '\r' && c != '\n' && c != '\t') {
                buf[p++] = c;
            }
        }
        // JDK MimeDecoder: len % 4 == 0 走严格解码，否则走 lenient
        if (p % 4 == 0) {
            return decode(buf, 0, p);
        }
        return decodeLenient(buf, 0, p);
    }

    /**
     * MIME 宽容模式解码字节数组的指定片段，先过滤空格、回车、换行与制表符再按宽容规则解码。
     *
     * @param src    base64 的 ASCII 字节，可包含空白字符
     * @param offset 起始下标
     * @param len    参与解码的长度
     * @return 解码后的字节，过滤空白后为空时返回空数组
     */
    public static byte[] decodeMime(byte[] src, int offset, int len) {
        char[] buf = new char[len];
        int p = 0;
        for (int i = offset; i < offset + len; i++) {
            char c = (char) src[i];
            if (c != ' ' && c != '\r' && c != '\n' && c != '\t') {
                buf[p++] = c;
            }
        }
        return decodeLenient(buf, 0, p);
    }

    // ==================== 兼容原有 API ====================

    /**
     * 解码为字符串，按 UTF-8 还原。
     *
     * @param s base64 文本
     * @return 解码后的字符串
     */
    public static String decodeString(String s) {
        return new String(decode(s), StandardCharsets.UTF_8);
    }

    /**
     * 将字符串按 UTF-8 取字节后编码。
     *
     * @param s 原始字符串，可为 {@code null}
     * @return base64 文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodeString(String s) {
        if (s == null) {
            return null;
        }
        return encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 文件编码 ====================

    /**
     * 将文件编码为 base64，并按文件实际 MIME 类型拼接 data URI 前缀。
     *
     * @param file 待编码文件
     * @return 形如 {@code data:image/png;base64,xxx} 的文本，文件不可读时为空串
     */
    public static String encodeFileWithPrefix(File file) {
        String str = encodeFile(file);
        if (StringUtils.isNotBlank(str)) {
            return "data:" + FileUtils.getContentType(file) + ";base64," + str;
        }
        return str;
    }

    /**
     * 将文件内容编码为 base64。
     *
     * @param file 待编码文件
     * @return base64 文本，读取失败时为空串
     */
    public static String encodeFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = IOUtils.isToBytes(fis);
            return encodeToString(bytes);
        } catch (IOException e) {
            log.error("base64 编码文件失败: {}", file, e);
        }
        return "";
    }

    // ==================== 判定 ====================

    /**
     * 猜测一个字符串「看起来是不是 base64 编码的可打印文本」。
     *
     * <p>判定方式：先解码，要求解码结果里<b>所有字符都是可打印 ASCII</b>（32~126），
     * 再把它重新编码回去，要求与原串完全相同。
     *
     * <p><strong>这是启发式判断，不是格式校验，用之前请了解下面几条限制：</strong>
     * <ul>
     * <li><b>二进制内容会返回 {@code false}。</b>图片、压缩包等编码出的 base64 解码后含不可打印字节，
     *     一律判为 {@code false}。要判断「是否是合法的 base64 格式」请用 {@link #isBase64Format(String)}。</li>
     * <li><b>本身形似 base64 的普通文本无法区分。</b>例如 {@code "MTIzNDU2"} 解码为 {@code "123456"}
     *     且能原样编码回去，会被判为 {@code true}，但它也可能只是一段普通字符串。</li>
     * <li>要求<b>标准字母表 + 显式补位</b>。URL 安全字母表（{@code -_}）、末组不补位、
     *     含折行的 MIME 形式都会返回 {@code false}（重新编码后与原串不等）。</li>
     * </ul>
     *
     * @param encodeString 待判断的字符串
     * @return 同时满足上述条件时为 {@code true}
     */
    public static boolean isBase64(String encodeString) {
        if (StringUtils.isBlank(encodeString)) {
            return false;
        }
        try {
            String decoded = new String(decode(encodeString), StandardCharsets.UTF_8);
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (c < 32 || c > 126) {
                    // 含不可打印字符，判为「不是可打印文本的 base64」
                    return false;
                }
            }
            return encodeToString(decoded.getBytes(StandardCharsets.UTF_8)).equals(encodeString);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断字符串是否是<b>格式合法</b>的标准 base64，不关心解码后是文本还是二进制。
     *
     * <p>与 {@link #isBase64(String)} 的区别：本方法只校验能否被 {@link #decode(String)} 解开，
     * 所以二进制数据的 base64 也返回 {@code true}。严格程度与本类 {@code decode} 一致：
     * 要求标准字母表且显式补位（长度为 4 的整数倍）。
     *
     * @param text 待判断的字符串，{@code null} 或空串返回 {@code false}
     * @return 是合法标准 base64 时为 {@code true}
     */
    public static boolean isBase64Format(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        try {
            decode(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Base64Utils() {
    }
}
