package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.schema.model.CanonicalType;

/**
 * 列 canonical 类型变化时联动改写 DEFAULT 字面量。
 *
 * <p>BOOLEAN：数值 {@code 0}/{@code 1} 在目标方言有真正布尔类型时改为 {@code false}/{@code true}；
 * Oracle {@code NUMBER(1)} / SQL Server {@code BIT} / MySQL {@code TINYINT(1)} 保持 0/1。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class DefaultValueCoercer {
    private DefaultValueCoercer() {
    }

    /**
     * @param original 原默认值
     * @param from 源 canonical
     * @param to 目标 canonical
     * @param target 目标方言
     * @return 改写后的表达式；无需改写时返回 original
     */
    public static SqlExpr coerce(SqlExpr original, CanonicalType from, CanonicalType to,
                                 SqlDialectSpec target) {
        return coerce(original, from, to, target == null ? SqlDialect.MYSQL : target.typeFamily());
    }

    /**
     * @param original 原默认值
     * @param from 源 canonical
     * @param to 目标 canonical
     * @param target 目标方言
     * @return 改写后的表达式
     */
    public static SqlExpr coerce(SqlExpr original, CanonicalType from, CanonicalType to,
                                 SqlDialect target) {
        if (original == null || to != CanonicalType.BOOLEAN) {
            return original;
        }
        if (!(original instanceof SqlLiteral)) {
            return original;
        }
        SqlLiteral lit = (SqlLiteral) original;
        Boolean flag = booleanFromLiteral(lit);
        if (flag == null) {
            return original;
        }
        if (nativeBoolean(target)) {
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, flag.booleanValue() ? "true" : "false");
        }
        return SqlLiteral.of(SqlLiteral.Kind.NUMBER, flag.booleanValue() ? "1" : "0");
    }

    /**
     * @param dialect 方言
     * @return 该方言 BOOLEAN 是否写成 true/false
     */
    public static boolean nativeBoolean(SqlDialect dialect) {
        return dialect == SqlDialect.POSTGRES || dialect == SqlDialect.ANSI
                || dialect == SqlDialect.H2 || dialect == SqlDialect.PRESTO
                || dialect == SqlDialect.HIVE || dialect == SqlDialect.CLICKHOUSE;
    }

    /**
     * 把默认值表达式渲染成 DDL 片段（不含 DEFAULT 关键字）。
     *
     * @param expr 表达式，可空
     * @param rawText 原文兜底
     * @param target 目标方言
     * @return 文本
     */
    public static String render(SqlExpr expr, String rawText, SqlDialectSpec target) {
        return render(expr, rawText, target == null ? SqlDialect.MYSQL : target.typeFamily());
    }

    /**
     * @param expr 表达式
     * @param rawText 原文
     * @param target 目标方言
     * @return DDL 片段
     */
    public static String render(SqlExpr expr, String rawText, SqlDialect target) {
        if (expr instanceof SqlLiteral) {
            SqlLiteral lit = (SqlLiteral) expr;
            if (lit.kind() == SqlLiteral.Kind.NULL) {
                return "NULL";
            }
            if (lit.kind() == SqlLiteral.Kind.BOOLEAN) {
                String v = lit.value();
                boolean t = v != null && ("true".equalsIgnoreCase(v) || "1".equals(v));
                if (nativeBoolean(target)) {
                    return t ? "true" : "false";
                }
                return t ? "1" : "0";
            }
            if (lit.kind() == SqlLiteral.Kind.NUMBER) {
                return lit.value() == null ? "0" : lit.value();
            }
            if (lit.kind() == SqlLiteral.Kind.STRING) {
                String v = lit.value() == null ? "" : lit.value();
                return "'" + v.replace("'", "''") + "'";
            }
        }
        if (expr instanceof SqlFunctionExpr) {
            SqlFunctionExpr f = (SqlFunctionExpr) expr;
            String n = BuiltinFunctionRewriter.functionName(f);
            if (f.arguments() == null || f.arguments().isEmpty()) {
                return n;
            }
            return expr.toString();
        }
        if (expr instanceof SqlIdentifier) {
            return ((SqlIdentifier) expr).qualifiedName();
        }
        return rawText == null ? "" : rawText.trim();
    }

    private static Boolean booleanFromLiteral(SqlLiteral lit) {
        String v = lit.value();
        if (v == null) {
            return null;
        }
        if (lit.kind() == SqlLiteral.Kind.NUMBER) {
            if ("1".equals(v) || "1.0".equals(v)) {
                return Boolean.TRUE;
            }
            if ("0".equals(v) || "0.0".equals(v)) {
                return Boolean.FALSE;
            }
        }
        if (lit.kind() == SqlLiteral.Kind.BOOLEAN) {
            if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
