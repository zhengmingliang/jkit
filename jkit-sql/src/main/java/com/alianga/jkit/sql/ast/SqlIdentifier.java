package com.alianga.jkit.sql.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 标识符，可带限定名 {@code catalog.schema.table} 或 {@code t.col}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlIdentifier extends SqlExpr {
    private List<String> names;
    private boolean quoted;

    /**
     * @param name 单段名
     * @return 标识符
     */
    public static SqlIdentifier of(String name) {
        SqlIdentifier id = new SqlIdentifier();
        id.names = new ArrayList<String>(1);
        id.names.add(name);
        return id;
    }

    /**
     * @return 各段名字，从左到右
     */
    public List<String> names() {
        if (names == null) {
            return Collections.emptyList();
        }
        return names;
    }

    /**
     * @param names 各段
     */
    public void setNames(List<String> names) {
        this.names = names;
    }

    /**
     * @param name 追加一段
     */
    public void addName(String name) {
        if (names == null) {
            names = new ArrayList<String>(2);
        }
        names.add(name);
    }

    /**
     * @return 最后一段（列名或表名）
     */
    public String simpleName() {
        if (names == null || names.isEmpty()) {
            return "";
        }
        return names.get(names.size() - 1);
    }

    /**
     * @return 点号连接的全名
     */
    public String qualifiedName() {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    /**
     * @return 是否带引号
     */
    public boolean quoted() {
        return quoted;
    }

    /**
     * @param quoted 是否带引号
     */
    public void setQuoted(boolean quoted) {
        this.quoted = quoted;
    }
}
