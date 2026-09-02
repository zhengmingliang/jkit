package com.alianga.jkit.json;

/**
 * {@link JSONEndianVarHandle} 的小端实现，按低位在前的顺序组装与拆解 short/int/long 数据
 */
public class JSONLittleEndianVarHandle extends JSONEndianVarHandle {
    /**
     * 构造小端字节序的读写实现
     */
    public JSONLittleEndianVarHandle() {
    }

    public int mergeInt32(short shortVal, char pre, char suff) {
        return suff << 24 | shortVal << 8 | pre;
    }

    public long mergeInt64(long val, long pre, long suff) {
        return suff << 48 | val << 16 | pre;
    }

    public long mergeInt64(long h32, long l32) {
        return l32 << 32 | h32;
    }

    public long mergeYearAndMonth(int year, int month) {
        return 3242591924980285440L | (long) getTwoDigitsBitsValue(month) << 40 | (long) getFourDigitsBitsValue(year);
    }

    public long mergeHHMMSS(int hour, int minute, int second) {
        return 63771678212096L | (long) getTwoDigitsBitsValue(second) << 48 |
                (long) getTwoDigitsBitsValue(minute) << 24 | (long) getTwoDigitsBitsValue(hour);
    }

    public int digits2Chars(char[] buf, int offset) {
        int value = getIntLE(buf, offset);
        if ((value & -983056) == 3145776) {
            int h = value & 63;
            int l8 = value >> 12 & 240;
            return getTwoDigitsValue(h ^ l8);
        } else {
            return -1;
        }
    }

    public int digits2Bytes(byte[] buf, int offset) {
        int value = this.twoBytesValue(buf, offset);
        if ((value & '\uf0f0') == 12336) {
            int h = value & 63;
            int l8 = value >> 4 & 240;
            return getTwoDigitsValue(h ^ l8);
        } else {
            return -1;
        }
    }

    public long getLong(char[] buf, int offset) {
        return getLongLE(buf, offset);
    }

    public int getInt(char[] buf, int offset) {
        return getIntLE(buf, offset);
    }

    public int putInt(char[] buf, int offset, int value) {
        buf[offset] = (char) value;
        buf[offset + 1] = (char) (value >> 16);
        return 2;
    }

    public int putLong(char[] buf, int offset, long value) {
        buf[offset] = (char) ((int) value);
        buf[offset + 1] = (char) ((int) (value >> 16));
        buf[offset + 2] = (char) ((int) (value >> 32));
        buf[offset + 3] = (char) ((int) (value >> 48));
        return 4;
    }
}

