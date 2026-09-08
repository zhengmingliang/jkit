package com.alianga.jkit.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link SQL#wall(String)} 检测结果。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlWallResult {
    private final List<String> violations;

    /**
     * @param violations 违规项，空表示通过
     */
    public SqlWallResult(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            this.violations = Collections.emptyList();
        } else {
            this.violations = Collections.unmodifiableList(new ArrayList<String>(violations));
        }
    }

    /**
     * @return 是否通过（无违规）
     */
    public boolean passed() {
        return violations.isEmpty();
    }

    /**
     * @return 违规代号列表（只读）
     */
    public List<String> violations() {
        return violations;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return passed() ? "SqlWallResult{passed}" : "SqlWallResult{violations=" + violations + '}';
    }
}
