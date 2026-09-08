package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 参数化归一与字面量导出（对标 Druid ParameterizedOutputVisitor / ExportParameterVisitor）。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlParameterizer {
    private SqlParameterizer() {
    }

    /**
     * 将字面量替换为 {@code ?}，便于 SQL 指纹 / 去重。不改动入参 AST（内部拷贝）。
     *
     * @param statement 语句
     * @param dialect 方言
     * @return 参数化后的紧凑 SQL
     */
    public static String parameterize(SqlStatement statement, SqlDialect dialect) {
        if (statement == null) {
            return "";
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlStatement copy = SQL.clone(statement, d);
        replaceLiteralsWithBind(copy);
        return SQL.toSqlString(copy, d);
    }

    /**
     * 导出字面量参数值（出现顺序），与 {@link SQL#parameters(SqlStatement)}（绑定占位符）不同。
     *
     * @param statement 语句
     * @return 值列表：String / Number / Boolean；NULL 字面量不导出
     */
    public static List<Object> exportParameterValues(SqlStatement statement) {
        if (statement == null) {
            return Collections.emptyList();
        }
        final List<Object> values = new ArrayList<Object>(8);
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlLiteral) {
                    Object v = toExportValue((SqlLiteral) node);
                    if (v != SKIP) {
                        values.add(v);
                    }
                }
                return true;
            }
        });
        return values;
    }

    private static final Object SKIP = new Object();
    /**
     * 就地把可导出字面量改成 {@code ?}。
     *
     * @param statement 语句
     */
    static void replaceLiteralsWithBind(SqlStatement statement) {
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (!(node instanceof SqlLiteral)) {
                    return true;
                }
                SqlLiteral lit = (SqlLiteral) node;
                if (toExportValue(lit) == SKIP) {
                    return true;
                }
                lit.setKind(SqlLiteral.Kind.BIND);
                lit.setValue("?");
                lit.setName(null);
                return true;
            }
        });
    }

    private static Object toExportValue(SqlLiteral lit) {
        if (lit.kind() == null) {
            return SKIP;
        }
        switch (lit.kind()) {
            case STRING:
                return unquoteString(lit.value());
            case HEX:
            case BIT:
                return lit.value();
            case NUMBER:
                return parseNumber(lit.value());
            case BOOLEAN:
                if ("TRUE".equalsIgnoreCase(lit.value()) || "1".equals(lit.value())) {
                    return Boolean.TRUE;
                }
                if ("FALSE".equalsIgnoreCase(lit.value()) || "0".equals(lit.value())) {
                    return Boolean.FALSE;
                }
                return lit.value();
            case NULL:
            case BIND:
            case NAMED_BIND:
            case VARIABLE:
            default:
                return SKIP;
        }
    }

    private static String unquoteString(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char c0 = value.charAt(0);
        char c1 = value.charAt(value.length() - 1);
        if ((c0 == '\'' && c1 == '\'') || (c0 == '"' && c1 == '"')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Object parseNumber(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            if (value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
                return new BigDecimal(value);
            }
            long n = Long.parseLong(value);
            if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) n);
            }
            return Long.valueOf(n);
        } catch (NumberFormatException ex) {
            return value;
        }
    }
}
