package com.alianga.jkit.storage.engine.cmcloud;

/**
 * 中国移动云盘 / 139 云盘引擎内部的字符串与字节工具。
 *
 * <p>jkit-storage 以 Java 8 为目标发布，因此不直接使用 Java 11 的
 * {@code String#isBlank} 与 Java 17 的 {@code java.util.HexFormat}，这里给出等价实现，
 * 供同包引擎类统一调用。</p>
 *
 * @author 郑明亮
 */
final class Strs {
    private Strs() {
    }

    /**
     * 判断是否为空串（null、空串或全空白）。
     *
     * @param value 待判断字符串，可为 null
     * @return true 表示空
     */
    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 字节数组转小写十六进制串（摘要值用，与 {@code HexFormat.of().formatHex} 同结果）。
     *
     * @param data 字节数组
     * @return 十六进制串
     */
    static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
