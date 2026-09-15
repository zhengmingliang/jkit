package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
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
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.visitor.SqlAstVisitor;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 把绑定占位符换成字面量、公式或标识符。{@code String} 在表达式里只做 SQL 单引号加倍；
 * 公式请传 {@link SqlExpr}。模板占位（{@code @name@} / {@code #{table}} 等）按命名键替换：
 * 表名位置写成标识符（必要时加方言引号），表达式位置与 {@code :name} 相同。
 *
 * <p>就地修改；公开门面 {@link SQL#bind} 会先 clone。未启用命名值时不碰标识符，热路径与原先一致。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlBinder {
    /** 常见包裹占位，bind 时用来从 IDENT 抠键；解析热路径不读这份表。 */
    private static final SqlPlaceholderPattern[] DEFAULT_NAMED_WRAPS = defaultNamedWraps();

    private SqlBinder() {
    }

    /**
     * 填充 {@code ?}、{@code :name} 以及模板占位 IDENT。
     *
     * @param statement 语句
     * @param dialect 方言（影响布尔等字面量写法，字符串一律单引号加倍）
     * @param positional 位置参数，可空
     * @param named 命名参数（不含冒号 / 包裹符），可空
     * @return 原对象
     */
    public static SqlStatement bind(SqlStatement statement, SqlDialectSpec dialect,
            Object[] positional, Map<String, ?> named) {
        return bind(statement, dialect, positional, named, null);
    }

    /**
     * 填充占位符。{@code placeholders} 在默认包裹（{@code @*@} / {@code #{*}} / {@code ${*}} /
     * {@code {{*}}} / {@code <*>} / {@code <-*->}）之外追加自定义模式。
     *
     * @param statement 语句
     * @param dialect 方言
     * @param positional 位置参数
     * @param named 命名参数
     * @param placeholders 额外包裹模式，可空
     * @return 原对象
     */
    public static SqlStatement bind(SqlStatement statement, SqlDialectSpec dialect,
            Object[] positional, Map<String, ?> named, SqlPlaceholders placeholders) {
        if (statement == null) {
            return statement;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        Map<String, ?> names = named == null
                ? Collections.<String, Object>emptyMap() : named;
        statement.accept(new Binder(d, positional, names, extraPatterns(placeholders)));
        return statement;
    }

    private static SqlPlaceholderPattern[] defaultNamedWraps() {
        SqlPlaceholders p = SqlPlaceholders.create()
                .atWrapped()
                .mybatis()
                .add("{{*}}")
                .angle()
                .arrowAngle();
        List<SqlPlaceholderPattern> list = p.patterns();
        return list.toArray(new SqlPlaceholderPattern[list.size()]);
    }

    private static SqlPlaceholderPattern[] extraPatterns(SqlPlaceholders placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return null;
        }
        List<SqlPlaceholderPattern> list = placeholders.patterns();
        return list.toArray(new SqlPlaceholderPattern[list.size()]);
    }

    private static final Object ABSENT = new Object();

    static String unwrapNamedKey(String token, SqlPlaceholderPattern[] extra) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (extra != null) {
            String k = unwrapWith(token, extra);
            if (k != null) {
                return k;
            }
        }
        return unwrapWith(token, DEFAULT_NAMED_WRAPS);
    }

    private static String unwrapWith(String token, SqlPlaceholderPattern[] patterns) {
        if (patterns == null) {
            return null;
        }
        for (int i = 0; i < patterns.length; i++) {
            String key = patterns[i].extractNamedKey(token);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private static boolean looksWrapped(String token) {
        char c = token.charAt(0);
        return c == '@' || c == '#' || c == '$' || c == '{' || c == '<';
    }

    private static boolean isUnquotedIdent(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char c0 = s.charAt(0);
        if (!((c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z') || c0 == '_' || c0 > 0x7f)) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '$' || c > 0x7f) {
                continue;
            }
            return false;
        }
        return true;
    }

    static SqlIdentifier identValue(Object v) {
        if (v instanceof SqlIdentifier) {
            return (SqlIdentifier) ((SqlIdentifier) v).copy();
        }
        if (v instanceof SqlExpr) {
            throw new IllegalArgumentException(
                    "table placeholder requires a name, not an expression: " + v);
        }
        if (v == null) {
            throw new IllegalArgumentException("table placeholder cannot be null");
        }
        String s = String.valueOf(v);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("table placeholder cannot be empty");
        }
        SqlIdentifier id = SqlIdentifier.of(s);
        if (!isUnquotedIdent(s)) {
            id.setQuoted(true);
        }
        return id;
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
            SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
            if (d.booleanLiteralAsNumber()) {
                return SqlLiteral.of(SqlLiteral.Kind.NUMBER, b ? "1" : "0");
            }
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, b ? "TRUE" : "FALSE");
        }
        if (value instanceof Number) {
            if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
                return SqlLiteral.of(SqlLiteral.Kind.NUMBER, value.toString());
            }
            return SqlLiteral.of(SqlLiteral.Kind.NUMBER, numberText((Number) value));
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
        int q = raw.indexOf('\'');
        if (q < 0) {
            return "'" + raw + "'";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 4);
        sb.append('\'');
        sb.append(raw, 0, q);
        for (int i = q; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'') {
                sb.append('\'');
            }
            sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }

    private static final String[] SMALL_INTS = smallInts();

    private static String[] smallInts() {
        String[] a = new String[128];
        for (int i = 0; i < a.length; i++) {
            a[i] = Integer.toString(i);
        }
        return a;
    }

    private static String numberText(Number value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            long n = value.longValue();
            if (n >= 0L && n < SMALL_INTS.length) {
                return SMALL_INTS[(int) n];
            }
            return Long.toString(n);
        }
        return value.toString();
    }

    private static boolean isBind(SqlExpr expr) {
        if (!(expr instanceof SqlLiteral)) {
            return false;
        }
        SqlLiteral lit = (SqlLiteral) expr;
        return lit.kind() == SqlLiteral.Kind.BIND || lit.kind() == SqlLiteral.Kind.NAMED_BIND;
    }

    private static Object takeNamed(SqlLiteral bind, Map<String, ?> names) {
        String name = bind.name() == null ? bind.value() : bind.name();
        if (name == null || !names.containsKey(name)) {
            throw new IllegalArgumentException("missing named bind :" + name);
        }
        return names.get(name);
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
        private final Object[] positional;
        private int posAt;
        private final Map<String, ?> names;
        private final boolean hasNamed;
        private final SqlPlaceholderPattern[] extraWraps;

        private Binder(SqlDialectSpec dialect, Object[] positional, Map<String, ?> names,
                SqlPlaceholderPattern[] extraWraps) {
            this.dialect = dialect;
            this.positional = positional;
            this.names = names;
            this.hasNamed = names != null && !names.isEmpty();
            this.extraWraps = extraWraps;
        }

        private Object take(SqlLiteral bind) {
            if (bind.kind() == SqlLiteral.Kind.NAMED_BIND) {
                return takeNamed(bind, names);
            }
            if (positional == null || posAt >= positional.length) {
                throw new IllegalArgumentException("not enough bind values");
            }
            return positional[posAt++];
        }

        private SqlExpr replace(SqlExpr e) {
            if (isBind(e)) {
                return expr(take((SqlLiteral) e), dialect);
            }
            if (hasNamed && e instanceof SqlIdentifier) {
                Object v = lookupIdent((SqlIdentifier) e);
                if (v != ABSENT) {
                    return expr(v, dialect);
                }
            }
            return e;
        }

        private Object lookupIdent(SqlIdentifier id) {
            String token = id.simpleName();
            if (token == null || token.isEmpty()) {
                return ABSENT;
            }
            if (!looksWrapped(token) && extraWraps == null) {
                return ABSENT;
            }
            String key = unwrapNamedKey(token, extraWraps);
            if (key != null && names.containsKey(key)) {
                return names.get(key);
            }
            if (looksWrapped(token) && names.containsKey(token)) {
                return names.get(token);
            }
            return ABSENT;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitTable(SqlTable node) {
            if (hasNamed && node.name() != null) {
                Object v = lookupIdent(node.name());
                if (v != ABSENT) {
                    node.setName(identValue(v));
                }
            }
            return true;
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
                Object v = take((SqlLiteral) values.get(0));
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
