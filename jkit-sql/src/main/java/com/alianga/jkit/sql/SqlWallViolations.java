package com.alianga.jkit.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wall 检查违规码收集器：{@link #add(String)} 自动去重并保持首现顺序，
 * 供 {@link SqlWallRule} 与内置检查共用。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlWallViolations {
    private final List<String> codes = new ArrayList<String>(4);

    /**
     * @param code 违规码；null 或已存在时忽略
     */
    public void add(String code) {
        if (code == null || codes.contains(code)) {
            return;
        }
        codes.add(code);
    }

    /**
     * @return 违规码列表（只读，首现顺序）
     */
    public List<String> codes() {
        return Collections.unmodifiableList(codes);
    }

    /**
     * @return 是否已有违规
     */
    public boolean isEmpty() {
        return codes.isEmpty();
    }
}
