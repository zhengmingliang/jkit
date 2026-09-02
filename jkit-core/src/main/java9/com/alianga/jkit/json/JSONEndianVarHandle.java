package com.alianga.jkit.json;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * {@link JSONEndian} 基于 {@link VarHandle} 的实现，在 JDK9 及以上版本用
 * {@link MethodHandles#byteArrayViewVarHandle} 按本机字节序整块读写 byte 数组，
 * 替代逐字节位运算以提升 JSON 序列化与反序列化的吞吐
 */
public abstract class JSONEndianVarHandle extends JSONEndian {
    /**
     * 把 byte 数组按本机字节序当作 long 数组读写的句柄
     */
    public static final VarHandle LONG_HANDLE_BA =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    /**
     * 把 byte 数组按本机字节序当作 int 数组读写的句柄
     */
    public static final VarHandle INTEGER_HANDLE_BA =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    /**
     * 把 byte 数组按本机字节序当作 short 数组读写的句柄
     */
    public static final VarHandle SHORT_HANDLE_BA =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());
    static final boolean LITTLE_ENDIAN;
    long[] PADDINGS = new long[]{3472328296227680304L, 3472328296227680304L};

    /**
     * 构造基于 VarHandle 的字节序读写实现，大端与小端的差异由子类给出
     */
    public JSONEndianVarHandle() {
    }

    public final short getShort(byte[] buf, int offset) {
        return (short) SHORT_HANDLE_BA.get(buf, offset);
    }

    final int twoBytesValue(byte[] buf, int offset) {
        int rem = buf.length - offset;
        if (rem < 2) {
            if (LITTLE_ENDIAN) {
                return rem == 0 ? 0 : buf[offset] & 255;
            } else {
                return rem == 0 ? 0 : (buf[offset] & 255) << 8;
            }
        } else {
            return (int) SHORT_HANDLE_BA.get(buf, offset);
        }
    }

    public final int getInt(byte[] buf, int offset) {
        if (buf.length - offset < 4) {
            if (LITTLE_ENDIAN) {
                int val = 0;
                int bits = 0;

                for (int i = offset; i < buf.length; ++i) {
                    val |= (buf[i] & 255) << bits;
                    bits += 8;
                }

                return val;
            }

            int val = 0;
            int bits = 24;

            for (int i = offset; i < buf.length; ++i) {
                val |= (buf[i] & 255) << bits;
                bits -= 8;
            }
        }

        return (int) INTEGER_HANDLE_BA.get(buf, offset);
    }

    public final long getLong(byte[] buf, int offset) {
        if (buf.length - offset < 8) {
            if (LITTLE_ENDIAN) {
                long val = 0L;
                int bits = 0;

                for (int i = offset; i < buf.length; ++i) {
                    val |= ((long) buf[i] & 255L) << bits;
                    bits += 8;
                }

                return val;
            }

            long val = 0L;
            int bits = 56;

            for (int i = offset; i < buf.length; ++i) {
                val |= ((long) buf[i] & 255L) << bits;
                bits -= 8;
            }
        }

        return (int) LONG_HANDLE_BA.get(buf, offset);
    }

    public Object getStringValue(String value) {
        throw new UnsupportedOperationException();
    }

    public final String createStringJDK8(char[] buf) {
        throw new UnsupportedOperationException();
    }

    public String createAsciiString(byte[] asciiBytes) {
        throw new UnsupportedOperationException();
    }

    public final int putShort(byte[] buf, int offset, short value) {
        SHORT_HANDLE_BA.set(buf, offset, value);
        return 2;
    }

    public abstract int putInt(char[] var1, int var2, int var3);

    public final int putInt(byte[] buf, int offset, int value) {
        INTEGER_HANDLE_BA.set(buf, offset, value);
        return 4;
    }

    public abstract int putLong(char[] var1, int var2, long var3);

    public final int putLong(byte[] buf, int offset, long value) {
        LONG_HANDLE_BA.set(buf, offset, value);
        return 8;
    }

    static {
        LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    }
}
