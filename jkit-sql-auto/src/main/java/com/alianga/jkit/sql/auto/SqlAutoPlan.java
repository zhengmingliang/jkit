package com.alianga.jkit.sql.auto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次同步将要执行（或校验出）的变更列表。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoPlan {
    private final List<SqlAutoChange> changes;

    /**
     * @param changes 变更，可空
     */
    public SqlAutoPlan(List<SqlAutoChange> changes) {
        if (changes == null || changes.isEmpty()) {
            this.changes = Collections.emptyList();
        } else {
            this.changes = Collections.unmodifiableList(new ArrayList<SqlAutoChange>(changes));
        }
    }

    /**
     * @return 空计划
     */
    public static SqlAutoPlan empty() {
        return new SqlAutoPlan(null);
    }

    /**
     * @return 变更（不可变）
     */
    public List<SqlAutoChange> changes() {
        return changes;
    }

    /**
     * @return 是否没有任何变更
     */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * @return 非空可执行 SQL（跳过纯校验项）
     */
    public List<String> sql() {
        List<String> out = new ArrayList<String>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            String s = changes.get(i).sql();
            if (s != null && s.length() > 0) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * @param kind 种类
     * @return 该种类的变更
     */
    public List<SqlAutoChange> ofKind(SqlAutoChange.Kind kind) {
        List<SqlAutoChange> out = new ArrayList<SqlAutoChange>(2);
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).kind() == kind) {
                out.add(changes.get(i));
            }
        }
        return out;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "SqlAutoPlan{changes=" + changes.size() + "}";
    }
}
