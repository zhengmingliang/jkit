package com.alianga.jkit.storage.git;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Git 引擎单测的 Java 8 兼容小工具：jkit-storage 以 Java 8 为目标，
 * 测试代码同样不能用 {@code Map.of} / 文本块这些 Java 9+ 的语法。
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
    static Map<String, String> of(Object... kv) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return out;
    }
}
