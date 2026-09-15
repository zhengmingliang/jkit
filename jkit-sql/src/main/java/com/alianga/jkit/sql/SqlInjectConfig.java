package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlExpr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 行级条件注入配置：列名、值、表白名单。租户、软删、机构号等都用这一套，不是只给租户。
 *
 * <pre>{@code
 * SqlInjectConfig cfg = SqlInjectConfig.create()
 *         .tables("t_order", "t_user")
 *         .add("deleted", 0)
 *         .add("tenant_id", new SqlInjectValue() {
 *             public Object get() { return TenantHolder.get(); }
 *         });
 * SQL.injectConfig(cfg);          // 全局默认（启动时）
 * SqlStatement out = SQL.inject(stmt); // 拦截器里不再传表/列
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlInjectConfig {
    private final List<String> tables = new ArrayList<String>(4);
    private final List<Column> columns = new ArrayList<Column>(2);

    private SqlInjectConfig() {
    }

    /**
     * @return 空配置
     */
    public static SqlInjectConfig create() {
        return new SqlInjectConfig();
    }

    /**
     * 全局表白名单。空则对所有物理表注入（仍跳过 CTE 名与 {@code DUAL}）。
     * 单列 {@link #add(String, Object, String...)} 传入的表优先于本名单。
     *
     * @param names 表简单名
     * @return this
     */
    public SqlInjectConfig tables(String... names) {
        tables.clear();
        if (names != null) {
            tables.addAll(Arrays.asList(names));
        }
        return this;
    }

    /**
     * @param names 表简单名
     * @return this
     */
    public SqlInjectConfig tables(Collection<String> names) {
        tables.clear();
        if (names != null) {
            tables.addAll(names);
        }
        return this;
    }

    /**
     * 追加一列常量。使用 {@link #tables} 的表白名单。
     *
     * @param column 列简单名
     * @param value Java 值 / {@link SqlExpr} / {@code "?"}
     * @return this
     */
    public SqlInjectConfig add(String column, Object value) {
        if (value instanceof SqlInjectValue) {
            return addValue(column, (SqlInjectValue) value, null);
        }
        return addValue(column, constant(value), null);
    }

    /**
     * 追加一列，值每次 {@link SQL#inject} 时再取（切面 / 请求上下文）。
     *
     * @param column 列简单名
     * @param value 取值
     * @return this
     */
    public SqlInjectConfig add(String column, SqlInjectValue value) {
        return addValue(column, value, null);
    }

    /**
     * 追加一列常量，仅注入列出的表（覆盖全局表白名单）。
     *
     * @param column 列简单名
     * @param value Java 值
     * @param tables 本列表白名单
     * @return this
     */
    public SqlInjectConfig add(String column, Object value, String... tables) {
        if (value instanceof SqlInjectValue) {
            return addValue(column, (SqlInjectValue) value, tables);
        }
        return addValue(column, constant(value), tables);
    }

    /**
     * 追加一列动态值，仅注入列出的表。
     *
     * @param column 列简单名
     * @param value 取值
     * @param tables 本列表白名单
     * @return this
     */
    public SqlInjectConfig add(String column, SqlInjectValue value, String... tables) {
        return addValue(column, value, tables);
    }

    /**
     * @return 全局表白名单（只读）
     */
    public List<String> tables() {
        return Collections.unmodifiableList(tables);
    }

    /**
     * @return 要注入的列（只读）
     */
    public List<Column> columns() {
        return Collections.unmodifiableList(columns);
    }

    private SqlInjectConfig addValue(String column, SqlInjectValue value, String[] tableNames) {
        if (column == null || column.trim().isEmpty()) {
            throw new IllegalArgumentException("inject column required");
        }
        if (value == null) {
            throw new IllegalArgumentException("inject value required");
        }
        List<String> colTables = tableNames == null || tableNames.length == 0
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(tableNames)));
        columns.add(new Column(column.trim(), value, colTables));
        return this;
    }

    private static SqlInjectValue constant(final Object value) {
        return new SqlInjectValue() {
            /**
             * {@inheritDoc}
             */
            @Override
            public Object get() {
                return value;
            }
        };
    }

    /**
     * 一列注入项。
     *
     * @author 郑明亮
     * @since 2.0.2
     */
    public static final class Column {
        private final String name;
        private final SqlInjectValue value;
        private final List<String> tables;

        Column(String name, SqlInjectValue value, List<String> tables) {
            this.name = name;
            this.value = value;
            this.tables = tables;
        }

        /**
         * @return 列简单名
         */
        public String name() {
            return name;
        }

        /**
         * @return 取值
         */
        public SqlInjectValue value() {
            return value;
        }

        /**
         * @return 本列表白名单；空则用配置级 {@link SqlInjectConfig#tables()}
         */
        public List<String> tables() {
            return tables;
        }
    }
}
