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
    private final String comment;
    private final List<SqlEntityColumn> columns;
    private final List<String> indexes;

    /**
     * @param type 实体类
     * @param tableName 表名
     * @param columns 列
     */
    public SqlEntityModel(Class<?> type, String tableName, List<SqlEntityColumn> columns) {
        this(type, tableName, columns, Collections.<String>emptyList());
    }

    /**
     * @param type 实体类
     * @param tableName 表名
     * @param columns 列
     * @param indexes 索引（{@code name:col1,col2} 或 {@code col1,col2}）
     */
    public SqlEntityModel(Class<?> type, String tableName, List<SqlEntityColumn> columns,
                          List<String> indexes) {
        this(type, tableName, columns, indexes, null);
    }

    /**
     * @param type 实体类
     * @param tableName 表名
     * @param columns 列
     * @param indexes 索引
     * @param comment 表注释
     */
    public SqlEntityModel(Class<?> type, String tableName, List<SqlEntityColumn> columns,
                          List<String> indexes, String comment) {
        this.type = type;
        this.tableName = tableName == null ? "" : tableName;
        this.comment = comment;
        if (columns == null || columns.isEmpty()) {
            this.columns = Collections.emptyList();
        } else {
            this.columns = Collections.unmodifiableList(new ArrayList<SqlEntityColumn>(columns));
        }
        if (indexes == null || indexes.isEmpty()) {
            this.indexes = Collections.emptyList();
        } else {
            this.indexes = Collections.unmodifiableList(new ArrayList<String>(indexes));
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
     * @return 表注释，可空
     */
    public String comment() {
        return comment;
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
        List<SqlEntityColumn> ids = idColumns();
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * @return 全部主键列
     */
    public List<SqlEntityColumn> idColumns() {
        List<SqlEntityColumn> ids = new ArrayList<SqlEntityColumn>(2);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primaryKey()) {
                ids.add(columns.get(i));
            }
        }
        return ids;
    }

    /**
     * @return 索引定义
     */
    public List<String> indexes() {
        return indexes;
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
