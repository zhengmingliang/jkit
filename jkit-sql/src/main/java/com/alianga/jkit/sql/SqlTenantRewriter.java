package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlInsertBranch;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlWithItem;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多租户隔离改写：按表白名单把 {@code alias.tenant_col = value} 注入 SELECT / UPDATE / DELETE
 * 的 WHERE（含 UNION 臂、FROM 子查询、CTE 体、EXISTS 标量子查询），并给匹配的 INSERT / MERGE
 * 补列或 ON 条件。
 *
 * <p>就地修改；公开门面 {@link SQL#injectTenant} 会先 clone。CTE 名不当物理表；{@code DUAL} 跳过。
 * 无白名单时对所有物理表注入。值表达式每次使用前深拷贝，避免共享节点。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlTenantRewriter {
    private SqlTenantRewriter() {
    }

    /**
     * 就地注入租户条件。
     *
     * @param statement 语句
     * @param column 租户列简单名，如 {@code tenant_id}
     * @param value 租户值（字面量 / 绑定）；null 视为 {@code NULL}
     * @param tables 需要隔离的物理表简单名；null 或空 = 全部物理表
     * @return 原对象（已改）
     */
    public static SqlStatement inject(SqlStatement statement, String column, SqlExpr value,
            Collection<String> tables) {
        if (statement == null) {
            return statement;
        }
        if (column == null || column.trim().isEmpty()) {
            throw new IllegalArgumentException("tenant column required");
        }
        String col = column.trim();
        SqlExpr val = value == null ? SqlLiteral.of(SqlLiteral.Kind.NULL, "NULL") : value;
        Set<String> cte = collectCteNames(statement);
        Set<String> whitelist = toLowerSet(tables);
        statement.accept(new Injector(col, val, cte, whitelist));
        return statement;
    }

    /**
     * 把 Java 值收成 AST 字面量。{@code String} 按 SQL 字符串转义（单引号加倍），不会当表达式解析。
     * {@code "?"} 生成绑定占位；{@code SqlExpr} 原样返回。
     *
     * @param value Java 值
     * @return 表达式
     */
    public static SqlExpr literalValue(Object value) {
        if (value == null) {
            return SqlLiteral.of(SqlLiteral.Kind.NULL, "NULL");
        }
        if (value instanceof SqlExpr) {
            return (SqlExpr) value;
        }
        if (value instanceof Boolean) {
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, ((Boolean) value).booleanValue() ? "TRUE" : "FALSE");
        }
        if (value instanceof Number) {
            return SqlLiteral.of(SqlLiteral.Kind.NUMBER, value.toString());
        }
        String raw = String.valueOf(value);
        if ("?".equals(raw)) {
            return SqlLiteral.of(SqlLiteral.Kind.BIND, "?");
        }
        return SqlLiteral.of(SqlLiteral.Kind.STRING, quoteSqlString(raw));
    }

    static String quoteSqlString(String raw) {
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

    private static Set<String> toLowerSet(Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<String>(tables.size() * 2);
        for (String t : tables) {
            if (t != null && t.trim().length() > 0) {
                out.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static Set<String> collectCteNames(SqlStatement statement) {
        final Set<String> names = new HashSet<String>(4);
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlWithItem) {
                    SqlIdentifier name = ((SqlWithItem) node).name();
                    if (name != null && name.simpleName().length() > 0) {
                        names.add(name.simpleName().toLowerCase(Locale.ROOT));
                    }
                }
                return true;
            }
        });
        return names;
    }

    private static SqlExpr copyExpr(SqlExpr expr) {
        if (expr == null) {
            return null;
        }
        return (SqlExpr) expr.copy();
    }

    private static SqlExpr and(SqlExpr left, SqlExpr right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return SqlBinaryExpr.of(left, SqlBinaryOp.AND, right);
    }

    private static boolean matches(SqlTable table, Set<String> cte, Set<String> whitelist) {
        if (table == null || table.name() == null) {
            return false;
        }
        String simple = table.name().simpleName();
        if (simple == null || simple.isEmpty()) {
            return false;
        }
        String lower = simple.toLowerCase(Locale.ROOT);
        if ("dual".equals(lower)) {
            return false;
        }
        if (cte.contains(lower)) {
            return false;
        }
        if (!whitelist.isEmpty() && !whitelist.contains(lower)) {
            return false;
        }
        return true;
    }

    private static String qualifier(SqlTable table) {
        if (table.alias() != null && table.alias().length() > 0) {
            return table.alias();
        }
        if (table.name() == null) {
            return null;
        }
        return table.name().simpleName();
    }

    private static void collectPhysical(SqlTableSource src, List<SqlTable> out) {
        if (src == null) {
            return;
        }
        if (src instanceof SqlTable) {
            out.add((SqlTable) src);
        } else if (src instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) src;
            collectPhysical(join.left(), out);
            collectPhysical(join.right(), out);
        }
    }

    private static SqlExpr tenantEq(String qualifier, String column, SqlExpr value) {
        SqlIdentifier left;
        if (qualifier == null || qualifier.isEmpty()) {
            left = SqlIdentifier.of(column);
        } else {
            left = new SqlIdentifier();
            left.addName(qualifier);
            left.addName(column);
        }
        return SqlBinaryExpr.of(left, SqlBinaryOp.EQ, copyExpr(value));
    }

    private static SqlExpr predicatesFor(List<SqlTable> tables, String column, SqlExpr value,
            Set<String> cte, Set<String> whitelist) {
        SqlExpr acc = null;
        for (int i = 0; i < tables.size(); i++) {
            SqlTable table = tables.get(i);
            if (!matches(table, cte, whitelist)) {
                continue;
            }
            acc = and(acc, tenantEq(qualifier(table), column, value));
        }
        return acc;
    }

    private static int columnIndex(List<SqlIdentifier> columns, String column) {
        for (int i = 0; i < columns.size(); i++) {
            SqlIdentifier id = columns.get(i);
            if (id != null && column.equalsIgnoreCase(id.simpleName())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean selectIsStar(SqlSelect select) {
        if (select == null || select.selectItems().size() != 1) {
            return false;
        }
        return select.selectItems().get(0).expr() instanceof SqlAllColumns;
    }

    private static void putValueAt(List<SqlExpr> row, int index, SqlExpr value) {
        SqlExpr copied = copyExpr(value);
        if (index < row.size()) {
            row.set(index, copied);
        } else {
            while (row.size() < index) {
                row.add(SqlLiteral.of(SqlLiteral.Kind.NULL, "NULL"));
            }
            row.add(copied);
        }
    }

    private static void appendSelectValue(SqlStatement query, SqlExpr value) {
        SqlSelect select = query instanceof SqlSelect ? (SqlSelect) query : null;
        while (select != null) {
            SqlSelectItem item = new SqlSelectItem();
            item.setExpr(copyExpr(value));
            select.selectItems().add(item);
            select = select.union();
        }
    }

    private static void replaceSelectValueAt(SqlStatement query, int index, SqlExpr value) {
        SqlSelect select = query instanceof SqlSelect ? (SqlSelect) query : null;
        while (select != null) {
            if (!selectIsStar(select) && index < select.selectItems().size()) {
                select.selectItems().get(index).setExpr(copyExpr(value));
            }
            select = select.union();
        }
    }

    private static void ensureInsertColumn(SqlInsert insert, String column, SqlExpr value,
            Set<String> cte, Set<String> whitelist) {
        if (insert == null) {
            return;
        }
        if (insert.table() != null && !matches(insert.table(), cte, whitelist)) {
            return;
        }
        if (insert.table() == null && insert.columns().isEmpty() && insert.setList().isEmpty()
                && insert.valuesList().isEmpty()) {
            return;
        }
        if (!insert.setList().isEmpty()) {
            ensureSetAssignment(insert.setList(), column, value);
            return;
        }
        List<SqlIdentifier> cols = insert.columns();
        if (cols.isEmpty()) {
            return;
        }
        int idx = columnIndex(cols, column);
        if (idx >= 0) {
            for (int r = 0; r < insert.valuesList().size(); r++) {
                putValueAt(insert.valuesList().get(r), idx, value);
            }
            if (insert.query() != null) {
                replaceSelectValueAt(insert.query(), idx, value);
            }
            return;
        }
        cols.add(SqlIdentifier.of(column));
        for (int r = 0; r < insert.valuesList().size(); r++) {
            insert.valuesList().get(r).add(copyExpr(value));
        }
        if (insert.query() != null) {
            appendSelectValue(insert.query(), value);
        }
    }

    private static void ensureSetAssignment(List<SqlBinaryExpr> setList, String column, SqlExpr value) {
        for (int i = 0; i < setList.size(); i++) {
            SqlBinaryExpr asg = setList.get(i);
            if (asg.left() instanceof SqlIdentifier
                    && column.equalsIgnoreCase(((SqlIdentifier) asg.left()).simpleName())) {
                asg.setRight(copyExpr(value));
                return;
            }
        }
        setList.add(SqlBinaryExpr.of(SqlIdentifier.of(column), SqlBinaryOp.EQ, copyExpr(value)));
    }

    private static void ensureBranchColumn(SqlInsertBranch branch, String column, SqlExpr value,
            Set<String> cte, Set<String> whitelist) {
        if (branch == null || !matches(branch.table(), cte, whitelist)) {
            return;
        }
        List<SqlIdentifier> cols = branch.columns();
        if (cols.isEmpty()) {
            return;
        }
        int idx = columnIndex(cols, column);
        if (idx >= 0) {
            putValueAt(branch.values(), idx, value);
            return;
        }
        cols.add(SqlIdentifier.of(column));
        branch.values().add(copyExpr(value));
    }

    /**
     * 按语句类型注入。
     */
    private static final class Injector extends SqlVisitorAdapter {
        private final String column;
        private final SqlExpr value;
        private final Set<String> cte;
        private final Set<String> whitelist;

        private Injector(String column, SqlExpr value, Set<String> cte, Set<String> whitelist) {
            this.column = column;
            this.value = value;
            this.cte = cte;
            this.whitelist = whitelist;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean visit(SqlNode node) {
            if (node instanceof SqlSelect) {
                injectSelect((SqlSelect) node);
            } else if (node instanceof SqlUpdate) {
                injectUpdate((SqlUpdate) node);
            } else if (node instanceof SqlDelete) {
                injectDelete((SqlDelete) node);
            } else if (node instanceof SqlInsert) {
                injectInsert((SqlInsert) node);
            } else if (node instanceof SqlMerge) {
                injectMerge((SqlMerge) node);
            }
            return true;
        }

        private void injectSelect(SqlSelect select) {
            List<SqlTable> tables = new ArrayList<SqlTable>(4);
            collectPhysical(select.from(), tables);
            SqlExpr extra = predicatesFor(tables, column, value, cte, whitelist);
            if (extra != null) {
                select.setWhere(and(select.where(), extra));
            }
        }

        private void injectUpdate(SqlUpdate update) {
            List<SqlTable> tables = new ArrayList<SqlTable>(4);
            collectPhysical(update.table(), tables);
            collectPhysical(update.from(), tables);
            SqlExpr extra = predicatesFor(tables, column, value, cte, whitelist);
            if (extra != null) {
                update.setWhere(and(update.where(), extra));
            }
        }

        private void injectDelete(SqlDelete delete) {
            List<SqlTable> tables = new ArrayList<SqlTable>(4);
            collectPhysical(delete.table(), tables);
            collectPhysical(delete.from(), tables);
            SqlExpr extra = predicatesFor(tables, column, value, cte, whitelist);
            if (extra != null) {
                delete.setWhere(and(delete.where(), extra));
            }
        }

        private void injectInsert(SqlInsert insert) {
            if (insert.table() != null) {
                ensureInsertColumn(insert, column, value, cte, whitelist);
            }
            List<SqlInsertBranch> branches = insert.branches();
            for (int i = 0; i < branches.size(); i++) {
                ensureBranchColumn(branches.get(i), column, value, cte, whitelist);
            }
        }

        private void injectMerge(SqlMerge merge) {
            List<SqlTable> tables = new ArrayList<SqlTable>(4);
            collectPhysical(merge.into(), tables);
            collectPhysical(merge.using(), tables);
            SqlExpr extra = predicatesFor(tables, column, value, cte, whitelist);
            if (extra != null) {
                merge.setOn(and(merge.on(), extra));
            }
            boolean intoMatches = merge.into() instanceof SqlTable
                    && matches((SqlTable) merge.into(), cte, whitelist);
            if (!intoMatches) {
                return;
            }
            List<SqlMergeWhen> whens = merge.whens();
            for (int i = 0; i < whens.size(); i++) {
                SqlInsert nested = whens.get(i).insert();
                if (nested != null) {
                    ensureInsertColumn(nested, column, value, cte, whitelist);
                }
            }
        }
    }
}
