package com.alianga.jkit.sql.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实体映射：表名 + 列。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlEntityModel {
    private final Class<?> type;
    private final String tableName;
    private final List<SqlEntityColumn> columns;

    /**
     * @param type 实体类
     * @param tableName 表名
     * @param columns 列
     */
    public SqlEntityModel(Class<?> type, String tableName, List<SqlEntityColumn> columns) {
        this.type = type;
        this.tableName = tableName == null ? "" : tableName;
        if (columns == null || columns.isEmpty()) {
            this.columns = Collections.emptyList();
        } else {
            this.columns = Collections.unmodifiableList(new ArrayList<SqlEntityColumn>(columns));
        }
    }

    /**
     * @return 实体类
     */
    public Class<?> type() {
        return type;
    }

    /**
     * @return 表名
     */
    public String tableName() {
        return tableName;
    }

    /**
     * @return 列（不可变）
     */
    public List<SqlEntityColumn> columns() {
        return columns;
    }

    /**
     * @return 主键列，没有则 null
     */
    public SqlEntityColumn idColumn() {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primaryKey()) {
                return columns.get(i);
            }
        }
        return null;
    }

    /**
     * @return 非自增列（INSERT 常用）
     */
    public List<SqlEntityColumn> insertColumns() {
        List<SqlEntityColumn> out = new ArrayList<SqlEntityColumn>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            SqlEntityColumn c = columns.get(i);
            if (!c.autoIncrement()) {
                out.add(c);
            }
        }
        return out;
    }
}
