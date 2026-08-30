package com.alianga.jkit.json;

/**
 * 字节序（大端/小端）相关的底层读写抽象，供JSON序列化与反序列化按本机字节序批量处理
 * short/int/long 数据以及两位、四位数字的快速解析
 */
public abstract class JSONEndian {
    /**
     * 查表获取两位ASCII数字对应的数值
     *
     * @param val 由两位ASCII数字按约定位运算组合出的索引（0-255）
     * @return 对应的 0-99 数值；索引不是两位十进制数字的组合时返回 -1
     */
    protected static final int getTwoDigitsValue(int val) {
        return JSONGeneral.TWO_DIGITS_VALUES[val];
    }

    /**
     * 查表获取两位数字的16位ASCII字符编码
     *
     * @param val 0-99 之间的数值
     * @return 该数值两位ASCII字符按本机字节序组合成的 short 值
     */
    protected static final short getTwoDigitsBitsValue(int val) {
        return JSONWriter.TWO_DIGITS_16_BITS[val];
    }

    /**
     * 查表获取四位数字的32位ASCII字符编码
     *
     * @param val 0-9999 之间的数值
     * @return 该数值四位ASCII字符按本机字节序组合成的 int 值
     */
    protected static final int getFourDigitsBitsValue(int val) {
        return JSONWriter.FOUR_DIGITS_32_BITS[val];
    }

    //    final JSONEndian INSTANCE;
    /**
     * 解析缓冲区中连续两个ASCII数字字节
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 两位数字对应的 0-99 数值；两个字节不都是数字时返回 -1
     */
    public abstract int digits2Bytes(byte[] buf, int offset);

    /**
     * 解析缓冲区中连续两个ASCII数字字符
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 两位数字对应的 0-99 数值；两个字符不都是数字时返回 -1
     */
    public abstract int digits2Chars(char[] buf, int offset);

    /**
     * 按本机字节序将一个16位值与前后各一个字符合并为32位整型
     *
     * @param shortVal 位于中间的16位值
     * @param pre 起始位置的字符
     * @param suff 结束位置的字符
     * @return 合并后的 int 值，可直接按本机字节序写入缓冲区
     */
    public abstract int mergeInt32(short shortVal, char pre, char suff);

    /**
     * 按本机字节序将一个32位值与前后各一个16位值合并为64位整型
     *
     * @param val 位于中间的32位值
     * @param pre 起始位置的16位值
     * @param suff 结束位置的16位值
     * @return 合并后的 long 值，可直接按本机字节序写入缓冲区
     */
    public abstract long mergeInt64(long val, long pre, long suff);

    /**
     * 按本机字节序将两个32位值合并为64位整型
     *
     * @param h32 书写顺序在前的32位值
     * @param l32 书写顺序在后的32位值
     * @return 合并后的 long 值，可直接按本机字节序写入缓冲区
     */
    public abstract long mergeInt64(long h32, long l32);

    /**
     * 将年份(4位)和月份（2位）合并为8个字节的long值(yyyy-MM-)
     *
     * @param year 年份（4位）
     * @param month 月份（2位）
     * @return 包含 {@code yyyy-MM-} 八个ASCII字符的 long 值
     */
    public abstract long mergeYearAndMonth(int year, int month);

    /**
     * 将时分秒合并为8个字节的long值(HH:mm:ss)
     *
     * @param hour 小时（2位）
     * @param minute 分钟（2位）
     * @param second 秒（2位）
     * @return 包含 {@code HH:mm:ss} 八个ASCII字符的 long 值
     */
    public abstract long mergeHHMMSS(int hour, int minute, int second);

    /**
     * 按本机字节序从字节缓冲区读取64位整型
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 long 值，剩余长度不足时仅组装剩余字节
     */
    public abstract long getLong(byte[] buf, int offset);

    /**
     * 按本机字节序从字符缓冲区读取64位整型（4个字符）
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 读取到的 long 值，剩余长度不足时仅组装剩余字符
     */
    public abstract long getLong(char[] buf, int offset);

    /**
     * 按本机字节序从字节缓冲区读取32位整型
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 int 值，剩余长度不足时仅组装剩余字节
     */
    public abstract int getInt(byte[] buf, int offset);

    /**
     * 按本机字节序从字符缓冲区读取32位整型（2个字符）
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 读取到的 int 值，剩余长度不足时仅组装剩余字符
     */
    public abstract int getInt(char[] buf, int offset);

    /**
     * 按本机字节序从字节缓冲区读取16位整型
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 short 值，剩余长度不足时仅组装剩余字节
     */
    public abstract short getShort(byte[] buf, int offset);

    /**
     * 按本机字节序向字节缓冲区写入64位整型
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @param value 待写入的值
     * @return 写入的字节数，固定为 8
     */
    public abstract int putLong(byte[] buf, int offset, long value);

    /**
     * 按本机字节序向字符缓冲区写入64位整型
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @param value 待写入的值
     * @return 写入的字符数，固定为 4
     */
    public abstract int putLong(char[] buf, int offset, long value);

    /**
     * 按本机字节序向字节缓冲区写入32位整型
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @param value 待写入的值
     * @return 写入的字节数，固定为 4
     */
    public abstract int putInt(byte[] buf, int offset, int value);

    /**
     * 按本机字节序向字符缓冲区写入32位整型
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @param value 待写入的值
     * @return 写入的字符数，固定为 2
     */
    public abstract int putInt(char[] buf, int offset, int value);

    /**
     * 按本机字节序向字节缓冲区写入16位整型
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @param value 待写入的值
     * @return 写入的字节数，固定为 2
     */
    public abstract int putShort(byte[] buf, int offset, short value);

    /**
     * 获取字符串内部 value 字段的值
     *
     * @param value 源字符串
     * @return 字符串内部的字符数组或字节数组（取决于JDK版本），不应修改
     */
    public abstract Object getStringValue(String value);

    /**
     * 基于字符数组创建字符串（JDK8 环境下可直接共享该数组）
     *
     * @param buf 字符数组
     * @return 由该字符数组内容构成的字符串
     */
    public abstract String createStringJDK8(char[] buf);

    /**
     * 基于ASCII字节数组创建字符串
     *
     * @param asciiBytes 仅包含ASCII字符的字节数组
     * @return 由该字节数组内容构成的字符串
     */
    public abstract String createAsciiString(byte[] asciiBytes);

    /**
     * 以小端序从字符缓冲区读取32位整型（2个字符）
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 读取到的 int 值；剩余长度为 0 时返回 0，为 1 时只取该字符
     */
    public static final int getIntLE(char[] buf, int offset) {
        final int rem = buf.length - offset;
        if (rem < 2) {
            return rem == 0 ? 0 : buf[offset];
        }
        return buf[offset] | buf[offset + 1] << 16;
    }

    /**
     * 以大端序从字符缓冲区读取32位整型（2个字符）
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 读取到的 int 值；剩余长度为 0 时返回 0，为 1 时只取该字符并置于高16位
     */
    public static final int getIntBE(char[] buf, int offset) {
        final int rem = buf.length - offset;
        if (rem < 2) {
            return rem == 0 ? 0 : buf[offset] << 16;
        }
        return buf[offset + 1] | buf[offset] << 16;
    }

    /**
     * 以小端序从字符缓冲区读取64位整型（4个字符）
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 读取到的 long 值；剩余字符不足4个时只组装剩余字符，其余位为 0
     */
    public static final long getLongLE(char[] buf, int offset) {
        if (buf.length - offset < 4) {
            long val = 0;
            int bits = 0;
            for (int i = offset; i < buf.length; ++i) {
                val = val | (long) buf[i] << bits;
                bits += 16;
            }
            return val;
        }
        return buf[offset] | (long) buf[offset + 1] << 16 | (long) buf[offset + 2] << 32 | (long) buf[offset + 3] << 48;
    }

    /**
     * 以大端序从字符缓冲区读取64位整型（4个字符）
     *
     * @param buf 字符缓冲区
     * @param offset 起始位置
     * @return 读取到的 long 值；剩余字符不足4个时只组装剩余字符（从高位开始填充）
     */
    public static final long getLongBE(char[] buf, int offset) {
        if (buf.length - offset < 4) {
            long val = 0;
            int bits = 48;
            for (int i = offset; i < buf.length; ++i) {
                val = val | (long) buf[i] << bits;
                bits -= 16;
            }
            return val;
        }
        return (long) buf[offset] << 48 | (long) buf[offset + 1] << 32 | (long) buf[offset + 2] << 16 |
                (long) buf[offset + 3];
    }

    /**
     * 以小端序从字节缓冲区读取2个字节并返回无符号16位值
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的值（低8位为起始字节）；剩余长度为 0 时返回 0，为 1 时只取该字节
     */
    public static final int getShortLE(byte[] buf, int offset) {
        final int rem = buf.length - offset;
        if (rem < 2) {
            return rem == 0 ? 0 : buf[offset] & 0xFF;
        }
        return (buf[offset] & 0xFF) | (buf[offset + 1] & 0xFF) << 8;
    }

    /**
     * 以大端序从字节缓冲区读取2个字节并返回无符号16位值
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的值（高8位为起始字节）；剩余长度为 0 时返回 0，为 1 时只取该字节并置于高8位
     */
    public static final int getShortBE(byte[] buf, int offset) {
        final int rem = buf.length - offset;
        if (rem < 2) {
            return rem == 0 ? 0 : (buf[offset] & 0xFF) << 8;
        }
        return (buf[offset + 1] & 0xFF) | (buf[offset] & 0xFF) << 8;
    }

    /**
     * 以小端序从字节缓冲区读取32位整型（4个字节）
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 int 值；剩余字节不足4个时只组装剩余字节，其余位为 0
     */
    public static final int getIntLE(byte[] buf, int offset) {
        if (buf.length - offset < 4) {
            int val = 0;
            int bits = 0;
            for (int i = offset; i < buf.length; ++i) {
                val = val | (buf[i] & 0xFF) << bits;
                bits += 8;
            }
            return val;
        }
        return (buf[offset] & 0xFF) | (buf[offset + 1] & 0xFF) << 8 | (buf[offset + 2] & 0xFF) << 16 |
                (buf[offset + 3] & 0xFF) << 24;
    }

    /**
     * 以大端序从字节缓冲区读取32位整型（4个字节）
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 int 值；剩余字节不足4个时只组装剩余字节（从高位开始填充）
     */
    public static final int getIntBE(byte[] buf, int offset) {
        if (buf.length - offset < 4) {
            int val = 0;
            int bits = 24;
            for (int i = offset; i < buf.length; ++i) {
                val = val | (buf[i] & 0xFF) << bits;
                bits -= 8;
            }
            return val;
        }
        return (buf[offset] & 0xFF) << 24 | (buf[offset + 1] & 0xFF) << 16 | (buf[offset + 2] & 0xFF) << 8 |
                (buf[offset + 3] & 0xFF);
    }

    /**
     * 以小端序从字节缓冲区读取64位整型（8个字节）
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 long 值；剩余字节不足8个时只组装剩余字节，其余位为 0
     */
    public static final long getLongLE(byte[] buf, int offset) {
        if (buf.length - offset < 8) {
            long val = 0;
            int bits = 0;
            for (int i = offset; i < buf.length; ++i) {
                val = val | (buf[i] & 0xFFL) << bits;
                bits += 8;
            }
            return val;
        }
        return (buf[offset] & 0xFFL) | (buf[offset + 1] & 0xFFL) << 8 | (buf[offset + 2] & 0xFFL) << 16 |
                (buf[offset + 3] & 0xFFL) << 24 | (buf[offset + 4] & 0xFFL) << 32 | (buf[offset + 5] & 0xFFL) << 40 |
                (buf[offset + 6] & 0xFFL) << 48 | (buf[offset + 7] & 0xFFL) << 56;
    }

    /**
     * 以大端序从字节缓冲区读取64位整型（8个字节）
     *
     * @param buf 字节缓冲区
     * @param offset 起始位置
     * @return 读取到的 long 值；剩余字节不足8个时只组装剩余字节（从高位开始填充）
     */
    public static final long getLongBE(byte[] buf, int offset) {
        if (buf.length - offset < 8) {
            long val = 0;
            int bits = 56;
            for (int i = offset; i < buf.length; ++i) {
                val = val | (buf[i] & 0xFFL) << bits;
                bits -= 8;
            }
            return val;
        }
        return (buf[offset] & 0xFFL) << 56 | (buf[offset + 1] & 0xFFL) << 48 | (buf[offset + 2] & 0xFFL) << 40 |
                (buf[offset + 3] & 0xFFL) << 32 | (buf[offset + 4] & 0xFFL) << 24 | (buf[offset + 5] & 0xFFL) << 16 |
                (buf[offset + 6] & 0xFFL) << 8 | (buf[offset + 7] & 0xFF);
    }
}
