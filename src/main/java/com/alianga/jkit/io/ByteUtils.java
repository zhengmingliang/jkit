package com.alianga.jkit.io;

import com.alianga.jkit.IOUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 字节数组工具类.
 *
 * <p>提供字节数组的读写、拷贝、填充、十六进制互转等基础操作。
 * 支持大端模式的 int/long/float/double 读写.
 */
public final class ByteUtils {
    /**
     * 创建指定长度的 byte 数组并用值 c 填充.
     *
     * @param c      填充值，仅低 8 位有效
     * @param length 数组长度
     * @return 长度为 {@code length} 且每个元素均为 {@code (byte) c} 的新数组
     */
    public static byte[] memset(int c, int length) {
        byte[] buffer = new byte[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = (byte) c;
        }
        return buffer;
    }

    /**
     * 内存拷贝（从 src 的 start 位置拷贝 len 个字节到新数组）.
     *
     * @param src   源字节数组
     * @param start 源数组中的起始下标
     * @param len   拷贝的字节数
     * @return 长度为 {@code len} 的新数组，内容为源数组的对应片段
     */
    public static byte[] memcpy(byte[] src, int start, int len) {
        byte[] buffer = new byte[len];
        System.arraycopy(src, start, buffer, 0, len);
        return buffer;
    }

    /**
     * 将字节数组以单字节字符输出为字符串.
     *
     * @param bytes 字节数组
     * @return 逐字节按单字节字符拼接的字符串，遇到值为 0 的字节即结束
     */
    public static String toString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = bytes.length; (i < len) && (bytes[i] != 0); i++) {
            sb.append((char) bytes[i]);
        }
        return sb.toString();
    }

    /**
     * 将字符串字符以单字节转化为指定大小的字节数组.
     *
     * @param str  源字符串，为 {@code null} 时按空串处理
     * @param size 目标数组长度，字符串不足时其余字节保持为 0
     * @return 长度为 {@code size} 的字节数组
     */
    public static byte[] toBytes(String str, int size) {
        int len = str == null ? 0 : str.length();
        byte[] bytes = new byte[size];
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                bytes[i] = ((byte) str.charAt(i));
            }
        }
        return bytes;
    }

    /**
     * 将 int 值以大端模式写入字节数组，返回写入字节数.
     *
     * @param buf    目标字节数组
     * @param offset 写入的起始下标
     * @param value  待写入的 int 值
     * @return 固定返回写入的字节数 4
     */
    public static int writeInt(byte[] buf, int offset, int value) {
        buf[offset++] = (byte) (value >> 24 & 0xff);
        buf[offset++] = (byte) (value >> 16 & 0xff);
        buf[offset++] = (byte) (value >> 8 & 0xff);
        buf[offset] = (byte) (value & 0xff);
        return 4;
    }

    /**
     * 将 long 值以大端模式写入字节数组，返回写入字节数.
     *
     * @param buf    目标字节数组
     * @param offset 写入的起始下标
     * @param value  待写入的 long 值
     * @return 固定返回写入的字节数 8
     */
    public static int writeLong(byte[] buf, int offset, long value) {
        buf[offset++] = (byte) (value >> 56 & 0xff);
        buf[offset++] = (byte) (value >> 48 & 0xff);
        buf[offset++] = (byte) (value >> 40 & 0xff);
        buf[offset++] = (byte) (value >> 32 & 0xff);
        buf[offset++] = (byte) (value >> 24 & 0xff);
        buf[offset++] = (byte) (value >> 16 & 0xff);
        buf[offset++] = (byte) (value >> 8 & 0xff);
        buf[offset] = (byte) (value & 0xff);
        return 8;
    }

    /**
     * 从字节数组中以大端模式读取一个 int 值.
     *
     * @param buf    源字节数组
     * @param offset 读取的起始下标
     * @return 由 {@code offset} 开始的 4 个字节按大端拼成的 int 值
     */
    public static int readInt(byte[] buf, int offset) {
        int value = 0;
        value |= (buf[offset++] & 0xFF) << 24;
        value |= (buf[offset++] & 0xFF) << 16;
        value |= (buf[offset++] & 0xFF) << 8;
        value |= buf[offset] & 0xFF;
        return value;
    }

    /**
     * 从字节数组中以大端模式读取一个 long 值.
     *
     * @param buf    源字节数组
     * @param offset 读取的起始下标
     * @return 由 {@code offset} 开始的 8 个字节按大端拼成的 long 值
     */
    public static long readLong(byte[] buf, int offset) {
        long value = 0;
        long mask = 0xFF;
        value |= (buf[offset++] & mask) << 56;
        value |= (buf[offset++] & mask) << 48;
        value |= (buf[offset++] & mask) << 40;
        value |= (buf[offset++] & mask) << 32;
        value |= (buf[offset++] & mask) << 24;
        value |= (buf[offset++] & mask) << 16;
        value |= (buf[offset++] & mask) << 8;
        value |= buf[offset] & mask;
        return value;
    }

    /**
     * 从字节数组中以大端模式读取一个 float 值.
     *
     * @param buf 源字节数组
     * @param off 读取的起始下标
     * @return 由 4 个字节的 IEEE 754 位模式还原出的 float 值
     */
    public static float readFloat(byte[] buf, int off) {
        int bits = readInt(buf, off);
        return Float.intBitsToFloat(bits);
    }

    /**
     * 从字节数组中以大端模式读取一个 double 值.
     *
     * @param buf 源字节数组
     * @param off 读取的起始下标
     * @return 由 8 个字节的 IEEE 754 位模式还原出的 double 值
     */
    public static double readDouble(byte[] buf, int off) {
        long bits = readLong(buf, off);
        return Double.longBitsToDouble(bits);
    }

    /**
     * 将字节数组以二进制序列输出.
     *
     * @param b 字节数组
     * @param splitChar 分隔符（0 表示无分隔符）
     * @return 每个字节固定 8 位、按需以分隔符隔开的二进制字符串
     */
    public static String toBinaryString(byte[] b, char splitChar) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            String bits = Integer.toBinaryString(b[i] & 0xFF);
            int count = bits.length();
            while (count++ < 8) {
                builder.append('0');
            }
            builder.append(bits);
            if (splitChar > 0) {
                builder.append(splitChar);
            }
        }
        return builder.toString();
    }

    /**
     * 16 进制字符串转二进制序列.
     *
     * @param hexString 16 进制字符串（大写字母形式）
     * @return 每个 16 进制字符展开为 4 位二进制后的字符串，非法字符原样保留
     */
    public static String hexToBinaryString(String hexString) {
        StringBuilder builder = new StringBuilder();
        char[] chars = hexString.toCharArray();
        for (char ch : chars) {
            int numIndex = ch > '9' ? ch - 55 : ch - 48;
            if (numIndex < 0 || numIndex >= 16) {
                builder.append(ch);
                continue;
            }
            String bits = Integer.toBinaryString(numIndex);
            int count = bits.length();
            while (count++ < 4) {
                builder.append('0');
            }
            builder.append(bits);
        }
        return builder.toString();
    }

    /**
     * 十六进制字符表（小写）.
     */
    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();

    /**
     * 十六进制字符表（大写）.
     */
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    /**
     * 将 byte 数组转化为 16 进制字符串输出（每个字节转化为 2 位大写 16 进制）.
     *
     * @param b 字节数组
     * @return 无分隔符的大写 16 进制字符串
     */
    public static String toHexString(byte[] b) {
        return toHexString(b, (char) 0);
    }

    /**
     * 将 byte 数组转为 16 进制字符串，可指定分隔符.
     *
     * @param b         字节数组
     * @param splitChar 分隔符（0 表示无分隔符），非 0 时每个字节后都会追加该字符
     * @return 大写 16 进制字符串
     */
    public static String toHexString(byte[] b, char splitChar) {
        StringBuilder builder = new StringBuilder(b.length * (splitChar > 0 ? 3 : 2));
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            builder.append(HEX_UPPER[v >>> 4]).append(HEX_UPPER[v & 0x0F]);
            if (splitChar > 0) {
                builder.append(splitChar);
            }
        }
        return builder.toString();
    }

    /**
     * 将 byte 数组转为小写、每字节固定两位、无分隔符的 16 进制字符串.
     *
     * <p>这是全库 16 进制转换的统一实现：摘要（MD5/SHA/HMAC）与文件头识别都要求小写且零填充，
     * 与大写的 {@link #toHexString(byte[])} 区分开，调用方请按需要的大小写选择。
     *
     * @param b 字节数组，为 {@code null} 时返回 {@code null}
     * @return 小写 16 进制字符串
     */
    public static String toHexStringLower(byte[] b) {
        if (b == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            builder.append(HEX_LOWER[v >>> 4]).append(HEX_LOWER[v & 0x0F]);
        }
        return builder.toString();
    }

    /**
     * 将 16 进制字符串还原为 byte 数组.
     *
     * @param hexString 16 进制字符串，大小写均可
     * @return 还原出的字节数组，非 16 进制字符会被忽略
     */
    public static byte[] hexString2Bytes(String hexString) {
        char[] chars = hexString.toCharArray();
        return hexString2Bytes(chars, 0, chars.length);
    }

    /**
     * 将 16 进制的字符数组还原为 byte 数组.
     *
     * @param chars  16 进制字符数组，大小写均可
     * @param offset 起始下标
     * @param len    参与转换的字符个数
     * @return 还原出的字节数组，非 16 进制字符会被忽略；有效字符不足时返回实际长度的数组
     */
    public static byte[] hexString2Bytes(char[] chars, int offset, int len) {
        byte[] bytes = new byte[len / 2];
        int byteLength = 0;
        int b = -1;
        for (int i = offset, count = offset + len; i < count; i++) {
            char ch = Character.toUpperCase(chars[i]);
            int numIndex = ch > '9' ? ch - 55 : ch - 48;
            if (numIndex < 0 || numIndex >= 16) {
                continue;
            }
            if (b == -1) {
                b = numIndex << 4;
            } else {
                b += numIndex;
                bytes[byteLength++] = (byte) b;
                b = -1;
            }
        }
        if (byteLength == bytes.length) {
            return bytes;
        }
        return memcpy(bytes, 0, byteLength);
    }

    /**
     * 读取输入流中的数据返回字节数组.
     *
     * @param is 输入流
     * @return 输入流中剩余全部内容对应的字节数组
     * @throws IOException 读取输入流失败时抛出
     */
    public static byte[] readStreamBytes(InputStream is) throws IOException {
        return IOUtils.readBytes(is);
    }
}
