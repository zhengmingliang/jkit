package com.alianga.jkit.json;

/**
 * {@link JSONEndianVarHandle} 的大端实现，按高位在前的顺序组装与拆解 short/int/long 数据
 */
public class JSONBigEndianVarHandle extends JSONEndianVarHandle {
    /**
     * 构造大端字节序的读写实现
     */
    public JSONBigEndianVarHandle() {
    }

    public int mergeInt32(short shortVal, char pre, char suff) {
        return pre << 24 | shortVal << 8 | suff;
    }

    public long mergeInt64(long val, long pre, long suff) {
        return pre << 48 | val << 16 | suff;
    }

    public long mergeInt64(long h32, long l32) {
        return h32 << 32 | l32;
    }

    public long mergeYearAndMonth(int year, int month) {
        return (long) getFourDigitsBitsValue(year) << 32 | (long) (getTwoDigitsBitsValue(month) << 8) | 754974765L;
    }

    public long mergeHHMMSS(int hour, int minute, int second) {
        return 63771678212096L | (long) getTwoDigitsBitsValue(hour) << 48 | (long) getTwoDigitsBitsValue(minute) << 24 |
                (long) getTwoDigitsBitsValue(second);
    }

    public int digits2Bytes(byte[] buf, int offset) {
        int bigShortVal = this.twoBytesValue(buf, offset);
        if ((bigShortVal & '\uf0f0') == 12336) {
            int l = bigShortVal & 15;
            int h = bigShortVal >> 8 & 15;
            return h <= 9 && l <= 9 ? (h << 3) + (h << 1) + l : -1;
        } else {
            return -1;
        }
    }

    public int digits2Chars(char[] buf, int offset) {
        int bigIntVal = getIntBE(buf, offset);
        if ((bigIntVal & -983056) == 3145776) {
            int l = bigIntVal & 15;
            int h = bigIntVal >> 16 & 15;
            return h <= 9 && l <= 9 ? (h << 3) + (h << 1) + l : -1;
        } else {
            return -1;
        }
    }

    public int getInt(char[] buf, int offset) {
        return getIntBE(buf, offset);
    }

    public long getLong(char[] buf, int offset) {
        return getLongBE(buf, offset);
    }

    public int putInt(char[] buf, int offset, int value) {
        buf[offset] = (char) (value >> 16);
        buf[offset + 1] = (char) value;
        return 2;
    }

    public int putLong(char[] buf, int offset, long value) {
        buf[offset] = (char) ((int) (value >> 48));
        buf[offset + 1] = (char) ((int) (value >> 32));
        buf[offset + 2] = (char) ((int) (value >> 16));
        buf[offset + 3] = (char) ((int) value);
        return 4;
    }
}
