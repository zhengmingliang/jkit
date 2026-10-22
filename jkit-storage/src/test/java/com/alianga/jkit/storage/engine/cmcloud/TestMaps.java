package com.alianga.jkit.storage.engine.cmcloud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 139 云盘引擎单测的 Java 8 兼容小工具：jkit-storage 以 Java 8 为目标，
 * 测试代码同样不能用 {@code Map.of} / {@code HexFormat} 这些 Java 9+ 的 API。
 *
 * @author 郑明亮
 */
final class TestMaps {
    private TestMaps() {
    }

    /**
     * 按「键、值」交替的可变参数构造有序 Map（替代 {@code Map.of}）。
     *
     * @param kv 键值对（偶数个）
     * @return 有序 Map
     */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    /** 小写十六进制串（替代 {@code HexFormat.of().formatHex}）。 */
    static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
