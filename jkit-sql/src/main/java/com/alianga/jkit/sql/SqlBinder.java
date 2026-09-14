package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 把绑定占位符换成字面量。字符串只做 SQL 单引号加倍，不当标识符、不当表达式解析，
 * 因此 {@code '; DROP TABLE t; --} 仍是一条字符串，不会拆成多语句。
 *
 * <p>就地修改；公开门面 {@link SQL#bind} 会先 clone。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlBinder {
    private SqlBinder() {
    }

    /**
     * 填充 {@code ?} 与 {@code :name}。
     *
     * @param statement 语句
     * @param dialect 方言（影响布尔等字面量写法，字符串一律单引号加倍）
     * @param positional 位置参数，可空
     * @param named 命名参数（不含冒号），可空
     * @return 原对象
     */
    public static SqlStatement bind(SqlStatement statement, SqlDialectSpec dialect,
            Object[] positional, Map<String, ?> named) {
        if (statement == null) {
            return statement;
        }
        final SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        final Iterator<Object> pos = positional == null
                ? Collections.emptyIterator() : Arrays.asList(positional).iterator();
        final Map<String, ?> names = named == null
                ? Collections.<String, Object>emptyMap() : named;
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlInExpr) {
                    return visitIn((SqlInExpr) node, d, pos, names);
                }
                if (node instanceof SqlLiteral) {
                    replaceLiteral((SqlLiteral) node, d, pos, names);
                }
                return true;
            }
        });
        return statement;
    }

    /**
     * Java 值 → SQL 字面量。{@code String} 只转义单引号，绝不解析成表达式。
     *
     * @param value Java 值
     * @param dialect 方言
     * @return 字面量表达式
     */
    public static SqlExpr literal(Object value, SqlDialectSpec dialect) {
        if (value == null) {
            return SqlLiteral.of(SqlLiteral.Kind.NULL, "NULL");
        }
        if (value instanceof SqlExpr) {
            return (SqlExpr) value;
        }
        if (value instanceof Boolean) {
            boolean b = ((Boolean) value).booleanValue();
            SqlDialect family = dialect == null ? SqlDialect.MYSQL : dialect.typeFamily();
            if (family == SqlDialect.MYSQL || family == SqlDialect.HIVE) {
                return SqlLiteral.of(SqlLiteral.Kind.NUMBER, b ? "1" : "0");
            }
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, b ? "TRUE" : "FALSE");
        }
        if (value instanceof Number) {
            if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
                return SqlLiteral.of(SqlLiteral.Kind.NUMBER, value.toString());
            }
            return SqlLiteral.of(SqlLiteral.Kind.NUMBER, value.toString());
        }
        if (value instanceof byte[]) {
            return SqlLiteral.of(SqlLiteral.Kind.HEX, "X'" + toHex((byte[]) value) + "'");
        }
        if (value instanceof Date) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return SqlLiteral.of(SqlLiteral.Kind.STRING, quoteSqlString(fmt.format((Date) value)));
        }
        if (value instanceof Character) {
            return SqlLiteral.of(SqlLiteral.Kind.STRING, quoteSqlString(String.valueOf(value)));
        }
        return SqlLiteral.of(SqlLiteral.Kind.STRING, quoteSqlString(String.valueOf(value)));
    }

    /**
     * SQL 字符串字面量：单引号加倍。不使用反斜杠，避免 MySQL {@code \' } 提前结束字符串。
     *
     * @param raw 原文
     * @return 含外层引号的 SQL 片段
     */
    public static String quoteSqlString(String raw) {
        if (raw == null) {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('\'');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'') {
                sb.append('\'');
            }
            sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }

    private static boolean visitIn(SqlInExpr in, SqlDialectSpec dialect, Iterator<Object> pos,
            Map<String, ?> names) {
        List<SqlExpr> values = in.values();
        if (values == null || values.size() != 1 || !(values.get(0) instanceof SqlLiteral)) {
            return true;
        }
        SqlLiteral bind = (SqlLiteral) values.get(0);
        if (!isBind(bind)) {
            return true;
        }
        Object v = take(bind, pos, names);
        if (v instanceof Collection || (v != null && v.getClass().isArray())) {
            List<SqlExpr> items = flatten(v, dialect);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("cannot bind empty collection to IN");
            }
            in.setValues(items);
            return false;
        }
        values.set(0, literal(v, dialect));
        return false;
    }

    private static void replaceLiteral(SqlLiteral lit, SqlDialectSpec dialect, Iterator<Object> pos,
            Map<String, ?> names) {
        if (!isBind(lit)) {
            return;
        }
        Object v = take(lit, pos, names);
        SqlExpr expr = literal(v, dialect);
        if (!(expr instanceof SqlLiteral)) {
            return;
        }
        SqlLiteral src = (SqlLiteral) expr;
        lit.setKind(src.kind());
        lit.setValue(src.value());
        lit.setName(src.name());
    }

    private static boolean isBind(SqlLiteral lit) {
        return lit.kind() == SqlLiteral.Kind.BIND || lit.kind() == SqlLiteral.Kind.NAMED_BIND;
    }

    private static Object take(SqlLiteral bind, Iterator<Object> pos, Map<String, ?> names) {
        if (bind.kind() == SqlLiteral.Kind.NAMED_BIND) {
            String name = bind.name() == null ? bind.value() : bind.name();
            if (name == null || !names.containsKey(name)) {
                throw new IllegalArgumentException("missing named bind :" + name);
            }
            return names.get(name);
        }
        if (!pos.hasNext()) {
            throw new IllegalArgumentException("not enough bind values");
        }
        return pos.next();
    }

    private static List<SqlExpr> flatten(Object v, SqlDialectSpec dialect) {
        List<SqlExpr> out = new ArrayList<SqlExpr>(4);
        if (v instanceof Collection) {
            for (Object item : (Collection<?>) v) {
                out.add(literal(item, dialect));
            }
            return out;
        }
        int n = Array.getLength(v);
        for (int i = 0; i < n; i++) {
            out.add(literal(Array.get(v, i), dialect));
        }
        return out;
    }

    private static String toHex(byte[] data) {
        char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xff;
            sb.append(hex[b >>> 4]);
            sb.append(hex[b & 0x0f]);
        }
        return sb.toString();
    }
}
