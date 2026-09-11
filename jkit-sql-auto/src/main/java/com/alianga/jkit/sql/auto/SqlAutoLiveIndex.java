package com.alianga.jkit.sql.auto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 库里已有的索引。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoLiveIndex {
    private final String name;
    private final boolean unique;
    private final List<String> columns;

    /**
     * @param name 索引名
     * @param unique 是否唯一
     * @param columns 列
     */
    public SqlAutoLiveIndex(String name, boolean unique, List<String> columns) {
        this.name = name == null ? "" : name;
        this.unique = unique;
        if (columns == null || columns.isEmpty()) {
            this.columns = Collections.emptyList();
        } else {
            this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
        }
    }

    /**
     * @return 名
     */
    public String name() {
        return name;
    }

    /**
     * @return 唯一
     */
    public boolean unique() {
        return unique;
    }

    /**
     * @return 列
     */
    public List<String> columns() {
        return columns;
    }
}
