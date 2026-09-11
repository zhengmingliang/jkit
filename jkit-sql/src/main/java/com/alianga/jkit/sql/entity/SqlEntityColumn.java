package com.alianga.jkit.sql.entity;

import com.alianga.jkit.sql.schema.model.CanonicalType;

import java.lang.reflect.Field;

/**
 * 实体上的一列。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlEntityColumn {
    private final String columnName;
    private final CanonicalType canonical;
    private final Integer precision;
    private final Integer scale;
    private final boolean nullable;
    private final boolean primaryKey;
    private final boolean autoIncrement;
    private final boolean unique;
    private final String rawType;
    private final String referencesTable;
    private final String referencesColumn;
    private final Field field;

    /**
     * @param columnName 列名
     * @param canonical canonical 类型
     * @param precision 精度，可空
     * @param scale 标度，可空
     * @param nullable 可空
     * @param primaryKey 主键
     * @param autoIncrement 自增
     * @param unique 唯一
     * @param field 源字段
     */
    public SqlEntityColumn(String columnName, CanonicalType canonical, Integer precision, Integer scale,
                           boolean nullable, boolean primaryKey, boolean autoIncrement, boolean unique,
                           Field field) {
        this(columnName, canonical, precision, scale, nullable, primaryKey, autoIncrement, unique,
                null, null, null, field);
    }

    /**
     * @param columnName 列名
     * @param canonical canonical
     * @param precision 精度
     * @param scale 标度
     * @param nullable 可空
     * @param primaryKey 主键
     * @param autoIncrement 自增
     * @param unique 唯一
     * @param rawType 原始类型字面量
     * @param referencesTable 引用表
     * @param referencesColumn 引用列
     * @param field 源字段
     */
    public SqlEntityColumn(String columnName, CanonicalType canonical, Integer precision, Integer scale,
                           boolean nullable, boolean primaryKey, boolean autoIncrement, boolean unique,
                           String rawType, String referencesTable, String referencesColumn, Field field) {
        this.columnName = columnName;
        this.canonical = canonical == null ? CanonicalType.VARCHAR : canonical;
        this.precision = precision;
        this.scale = scale;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.autoIncrement = autoIncrement;
        this.unique = unique;
        this.rawType = rawType;
        this.referencesTable = referencesTable;
        this.referencesColumn = referencesColumn;
        this.field = field;
    }

    /**
     * @return 列名
     */
    public String columnName() {
        return columnName;
    }

    /**
     * @return canonical 类型
     */
    public CanonicalType canonical() {
        return canonical;
    }

    /**
     * @return 精度，可空
     */
    public Integer precision() {
        return precision;
    }

    /**
     * @return 标度，可空
     */
    public Integer scale() {
        return scale;
    }

    /**
     * @return 可空
     */
    public boolean nullable() {
        return nullable;
    }

    /**
     * @return 主键
     */
    public boolean primaryKey() {
        return primaryKey;
    }

    /**
     * @return 自增
     */
    public boolean autoIncrement() {
        return autoIncrement;
    }

    /**
     * @return 唯一
     */
    public boolean unique() {
        return unique;
    }

    /**
     * @return 源字段
     */
    public Field field() {
        return field;
    }

    /**
     * @return 原始类型字面量，可空
     */
    public String rawType() {
        return rawType;
    }

    /**
     * @return 引用表，可空
     */
    public String referencesTable() {
        return referencesTable;
    }

    /**
     * @return 引用列，可空
     */
    public String referencesColumn() {
        return referencesColumn;
    }
}
