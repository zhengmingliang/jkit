package com.alianga.jkit.sql.schema.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构化列定义。由 {@link com.alianga.jkit.sql.schema.parse.SqlColumnDefinitionParser}
 * 从原文解析得到，是 schema 转换的唯一输入形式。
 *
 * <p>{@link com.alianga.jkit.sql.ast.SqlDdlStatement#columnDefinitions()} 仍返回
 * {@code List<String>}，本类型是并行通路，不破坏既有 API。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ColumnDefinition {
    private final String columnName;
    private final SqlDataType dataType;
    private final List<ColumnConstraint> constraints;
    private final String rawText;
    private final boolean tableConstraint;

    /**
     * @param columnName 列名（已去引号）；表级约束时可空
     * @param dataType 类型，不可空
     * @param constraints 约束，可空
     * @param rawText 原文
     * @param tableConstraint 是否表级约束（PRIMARY KEY / FOREIGN KEY / CHECK 等）
     */
    public ColumnDefinition(String columnName, SqlDataType dataType,
                            List<ColumnConstraint> constraints, String rawText,
                            boolean tableConstraint) {
        this.columnName = columnName == null ? "" : columnName;
        this.dataType = dataType == null ? SqlDataType.unknown("") : dataType;
        if (constraints == null || constraints.isEmpty()) {
            this.constraints = Collections.emptyList();
        } else {
            this.constraints = Collections.unmodifiableList(
                    new ArrayList<ColumnConstraint>(constraints));
        }
        this.rawText = rawText == null ? "" : rawText;
        this.tableConstraint = tableConstraint;
    }

    /**
     * @return 列名（已去引号）
     */
    public String columnName() {
        return columnName;
    }

    /**
     * @return 类型
     */
    public SqlDataType dataType() {
        return dataType;
    }

    /**
     * @return 约束（不可变）
     */
    public List<ColumnConstraint> constraints() {
        return constraints;
    }

    /**
     * @return 原文，解析失败时用于兜底回退
     */
    public String rawText() {
        return rawText;
    }

    /**
     * @return 是否表级约束（非列定义）
     */
    public boolean tableConstraint() {
        return tableConstraint;
    }

    /**
     * @param type 约束类型
     * @return 是否含该约束
     */
    public boolean has(Class<? extends ColumnConstraint> type) {
        if (type == null) {
            return false;
        }
        for (int i = 0; i < constraints.size(); i++) {
            if (type.isInstance(constraints.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param type 约束类型
     * @param <T> 约束类型
     * @return 第一个匹配的约束，没有则 null
     */
    public <T extends ColumnConstraint> T find(Class<T> type) {
        if (type == null) {
            return null;
        }
        for (int i = 0; i < constraints.size(); i++) {
            ColumnConstraint c = constraints.get(i);
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
