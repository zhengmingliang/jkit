package com.alianga.jkit.sql.auto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库里已有的一张表。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoLiveTable {
    private final String name;
    private final Map<String, SqlAutoLiveColumn> columns;
    private final Map<String, SqlAutoLiveIndex> indexes;

    /**
     * @param name 表名（库中原文）
     * @param columns 列，key 为小写列名
     * @param indexes 索引，key 为小写索引名
     */
    public SqlAutoLiveTable(String name, Map<String, SqlAutoLiveColumn> columns,
                            Map<String, SqlAutoLiveIndex> indexes) {
        this.name = name == null ? "" : name;
        this.columns = columns == null
                ? Collections.<String, SqlAutoLiveColumn>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, SqlAutoLiveColumn>(columns));
        this.indexes = indexes == null
                ? Collections.<String, SqlAutoLiveIndex>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, SqlAutoLiveIndex>(indexes));
    }

    /**
     * @return 表名
     */
    public String name() {
        return name;
    }

    /**
     * @return 列
     */
    public Map<String, SqlAutoLiveColumn> columns() {
        return columns;
    }

    /**
     * @return 索引
     */
    public Map<String, SqlAutoLiveIndex> indexes() {
        return indexes;
    }

    /**
     * @param columnName 列名
     * @return 列，没有则 null
     */
    public SqlAutoLiveColumn column(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columns.get(columnName.toLowerCase());
    }

    /**
     * @param indexName 索引名
     * @return 索引，没有则 null
     */
    public SqlAutoLiveIndex index(String indexName) {
        if (indexName == null) {
            return null;
        }
        return indexes.get(indexName.toLowerCase());
    }
}
