package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlInsertBranch;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlOverExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.visitor.SqlAstVisitor;

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
 * 把绑定占位符换成字面量或表达式。{@code String} 只做 SQL 单引号加倍，不当公式解析；
 * 公式请传 {@link SqlExpr}（如 {@code SQL.parseExpr("NOW()")}）。
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
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        Iterator<Object> pos = positional == null
                ? Collections.emptyIterator() : Arrays.asList(positional).iterator();
        Map<String, ?> names = named == null
                ? Collections.<String, Object>emptyMap() : named;
        statement.accept(new Binder(d, pos, names));
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
     * 绑定值收成 AST。{@link SqlExpr} 深拷贝后原样嵌入（公式 / 函数 / 列引用）；其余走 {@link #literal}。
     *
     * @param value Java 值或 {@link SqlExpr}
     * @param dialect 方言
     * @return 表达式
     */
    public static SqlExpr expr(Object value, SqlDialectSpec dialect) {
        if (value instanceof SqlExpr) {
            return (SqlExpr) ((SqlExpr) value).copy();
        }
        return literal(value, dialect);
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

    private static boolean isBind(SqlExpr expr) {
        if (!(expr instanceof SqlLiteral)) {
            return false;
        }
        SqlLiteral lit = (SqlLiteral) expr;
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
                out.add(expr(item, dialect));
            }
            return out;
        }
        int n = Array.getLength(v);
        for (int i = 0; i < n; i++) {
            out.add(expr(Array.get(v, i), dialect));
        }
        return out;
    }

    /**
     * 在父节点上把绑定占位换成字面量或公式，保证 {@code NOW()} 等能嵌进树，而不只改 Literal 字段。
     */
    private static final class Binder extends SqlAstVisitor {
        private final SqlDialectSpec dialect;
        private final Iterator<Object> pos;
        private final Map<String, ?> names;

        private Binder(SqlDialectSpec dialect, Iterator<Object> pos, Map<String, ?> names) {
            this.dialect = dialect;
            this.pos = pos;
            this.names = names;
        }

        private SqlExpr replace(SqlExpr e) {
            if (!isBind(e)) {
                return e;
            }
            return expr(take((SqlLiteral) e, pos, names), dialect);
        }

        private void replaceList(List<SqlExpr> list) {
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                list.set(i, replace(list.get(i)));
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitSelect(SqlSelect node) {
            node.setWhere(replace(node.where()));
            node.setHaving(replace(node.having()));
            node.setTop(replace(node.top()));
            node.setQualify(replace(node.qualify()));
            node.setConnectBy(replace(node.connectBy()));
            node.setStartWith(replace(node.startWith()));
            replaceList(node.groupBy());
            replaceList(node.distinctOn());
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitSelectItem(SqlSelectItem node) {
            node.setExpr(replace(node.expr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitOrderByItem(SqlOrderByItem node) {
            node.setExpr(replace(node.expr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitLimit(SqlLimit node) {
            node.setOffset(replace(node.offset()));
            node.setRowCount(replace(node.rowCount()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitUpdate(SqlUpdate node) {
            node.setWhere(replace(node.where()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitDelete(SqlDelete node) {
            node.setWhere(replace(node.where()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitInsert(SqlInsert node) {
            List<List<SqlExpr>> rows = node.valuesList();
            for (int i = 0; i < rows.size(); i++) {
                replaceList(rows.get(i));
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitInsertBranch(SqlInsertBranch node) {
            replaceList(node.values());
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitJoin(SqlJoin node) {
            node.setCondition(replace(node.condition()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitMerge(SqlMerge node) {
            node.setOn(replace(node.on()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitMergeWhen(SqlMergeWhen node) {
            node.setAndPredicate(replace(node.andPredicate()));
            node.setDeleteWhere(replace(node.deleteWhere()));
            node.setInsertWhere(replace(node.insertWhere()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitBinaryExpr(SqlBinaryExpr node) {
            node.setLeft(replace(node.left()));
            node.setRight(replace(node.right()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitUnaryExpr(SqlUnaryExpr node) {
            node.setExpr(replace(node.expr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitFunctionExpr(SqlFunctionExpr node) {
            replaceList(node.arguments());
            if (node.hasParameters()) {
                replaceList(node.parameters());
            }
            node.setSeparator(replace(node.separator()));
            node.setFilter(replace(node.filter()));
            node.setAgainst(replace(node.against()));
            node.setOver(replace(node.over()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitCaseExpr(SqlCaseExpr node) {
            node.setValue(replace(node.value()));
            replaceList(node.whenList());
            replaceList(node.thenList());
            node.setElseExpr(replace(node.elseExpr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitCastExpr(SqlCastExpr node) {
            node.setExpr(replace(node.expr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitBetweenExpr(SqlBetweenExpr node) {
            node.setExpr(replace(node.expr()));
            node.setBegin(replace(node.begin()));
            node.setEnd(replace(node.end()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitInExpr(SqlInExpr node) {
            node.setExpr(replace(node.expr()));
            List<SqlExpr> values = node.values();
            if (values != null && values.size() == 1 && isBind(values.get(0))) {
                Object v = take((SqlLiteral) values.get(0), pos, names);
                if (v instanceof Collection || (v != null && v.getClass().isArray())) {
                    List<SqlExpr> items = flatten(v, dialect);
                    if (items.isEmpty()) {
                        throw new IllegalArgumentException("cannot bind empty collection to IN");
                    }
                    node.setValues(items);
                    return true;
                }
                values.set(0, expr(v, dialect));
                return true;
            }
            replaceList(values);
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitListExpr(SqlListExpr node) {
            replaceList(node.items());
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitOverExpr(SqlOverExpr node) {
            replaceList(node.partitionBy());
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitValuesTable(SqlValuesTable node) {
            replaceList(node.rows());
            return true;
        }
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
