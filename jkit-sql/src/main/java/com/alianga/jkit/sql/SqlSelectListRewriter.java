package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SELECT 列表改写：按列名替换投影、把 {@code *} / {@code t.*} 展开成具名列。
 * 就地修改；公开门面会先 clone。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlSelectListRewriter {
    private SqlSelectListRewriter() {
    }

    /**
     * 整树替换匹配的 SELECT 项。{@code column} 为简单名时匹配别名或标识符最后一段；
     * 含点号时按 {@code 限定.列} 匹配。全部命中项都会替换（不只第一项）。
     *
     * @param statement 语句
     * @param column 列名或 {@code t.col}
     * @param replacement 新表达式
     * @param alias 新别名；{@code null} 时保留原别名，没有则用列简单名，保证输出列名不变
     * @return 原对象
     */
    public static SqlStatement replaceSelectItem(SqlStatement statement, String column,
            SqlExpr replacement, String alias) {
        if (statement == null || column == null || column.trim().isEmpty() || replacement == null) {
            return statement;
        }
        Map<String, SqlExpr> one = new LinkedHashMap<String, SqlExpr>(2);
        one.put(column.trim(), replacement);
        return replaceSelectItems(statement, one, alias);
    }

    /**
     * 整树一次遍历替换多列。限定名（{@code t.col}）优先于简单名。
     *
     * @param statement 语句
     * @param replacements 列名 → 新表达式
     * @return 原对象
     */
    public static SqlStatement replaceSelectItems(SqlStatement statement,
            Map<String, ? extends SqlExpr> replacements) {
        return replaceSelectItems(statement, replacements, null);
    }

    static SqlStatement replaceSelectItems(SqlStatement statement,
            Map<String, ? extends SqlExpr> replacements, final String alias) {
        if (statement == null || replacements == null || replacements.isEmpty()) {
            return statement;
        }
        final List<ReplaceSpec> specs = new ArrayList<ReplaceSpec>(replacements.size());
        List<ReplaceSpec> simple = new ArrayList<ReplaceSpec>(replacements.size());
        for (Map.Entry<String, ? extends SqlExpr> e : replacements.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String spec = e.getKey().trim();
            if (spec.isEmpty()) {
                continue;
            }
            ReplaceSpec parsed = ReplaceSpec.parse(spec, e.getValue());
            if (parsed.qualifier != null) {
                specs.add(parsed);
            } else {
                simple.add(parsed);
            }
        }
        specs.addAll(simple);
        if (specs.isEmpty()) {
            return statement;
        }
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlSelect) {
                    replaceInSelectBatch((SqlSelect) node, specs, alias);
                }
                return true;
            }
        });
        return statement;
    }

    /**
     * 整树展开 {@code *} / {@code t.*}。解析不到列的星号保持原样。
     *
     * @param statement 语句
     * @param resolver 表 → 列
     * @return 原对象
     */
    public static SqlStatement expandStar(SqlStatement statement, SqlColumnResolver resolver) {
        if (statement == null || resolver == null) {
            return statement;
        }
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void endVisit(SqlNode node) {
                if (node instanceof SqlSelect) {
                    expandSelect((SqlSelect) node, resolver);
                }
            }
        });
        return statement;
    }

    /**
     * 用 Map 做 {@link SqlColumnResolver}（键忽略大小写）。
     *
     * @param columnsByTable 表简单名 → 列
     * @return 解析器；map 为 null 时对任何表都返回 null
     */
    public static SqlColumnResolver mapResolver(Map<String, ? extends List<String>> columnsByTable) {
        final Map<String, List<String>> lower = new LinkedHashMap<String, List<String>>();
        if (columnsByTable != null) {
            for (Map.Entry<String, ? extends List<String>> e : columnsByTable.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                List<String> cols = e.getValue() == null
                        ? Collections.<String>emptyList() : new ArrayList<String>(e.getValue());
                lower.put(e.getKey().toLowerCase(Locale.ROOT), cols);
            }
        }
        return new SqlColumnResolver() {
            /**
             * {@inheritDoc}
             */
            @Override
            public List<String> columnsOf(String tableSimpleName) {
                if (tableSimpleName == null) {
                    return null;
                }
                return lower.get(tableSimpleName.toLowerCase(Locale.ROOT));
            }
        };
    }

    private static void replaceInSelectBatch(SqlSelect select, List<ReplaceSpec> specs, String alias) {
        List<SqlSelectItem> items = select.selectItems();
        // 同一 SELECT 内共享别名只赋给首个命中项，避免多列替换产生重复别名（2.0.2 修复）
        boolean sharedAliasUsed = false;
        for (int i = 0; i < items.size(); i++) {
            SqlSelectItem item = items.get(i);
            for (int s = 0; s < specs.size(); s++) {
                ReplaceSpec spec = specs.get(s);
                if (!matches(item, spec.qualifier, spec.simple)) {
                    continue;
                }
                item.setExpr(copyExpr(spec.replacement));
                if (alias != null && alias.length() == 0) {
                    item.setAlias(null);
                } else if (alias != null) {
                    if (!sharedAliasUsed) {
                        item.setAlias(alias);
                        sharedAliasUsed = true;
                    } else {
                        // 重复命中：退回各列自己的别名，保证输出列名不变且无重复
                        item.setAlias(spec.simple);
                    }
                } else if (item.alias() == null || item.alias().isEmpty()) {
                    item.setAlias(spec.simple);
                }
                break;
            }
        }
    }

    private static final class ReplaceSpec {
        final String qualifier;
        final String simple;
        final SqlExpr replacement;

        private ReplaceSpec(String qualifier, String simple, SqlExpr replacement) {
            this.qualifier = qualifier;
            this.simple = simple;
            this.replacement = replacement;
        }

        static ReplaceSpec parse(String spec, SqlExpr replacement) {
            String qualifier = null;
            String simple = spec;
            int dot = spec.lastIndexOf('.');
            if (dot > 0 && dot < spec.length() - 1) {
                qualifier = spec.substring(0, dot);
                simple = spec.substring(dot + 1);
            }
            return new ReplaceSpec(qualifier, simple, replacement);
        }
    }

    private static boolean matches(SqlSelectItem item, String qualifier, String simple) {
        if (item.alias() != null && qualifier == null && simple.equalsIgnoreCase(item.alias())) {
            return true;
        }
        if (!(item.expr() instanceof SqlIdentifier)) {
            return false;
        }
        SqlIdentifier id = (SqlIdentifier) item.expr();
        if (!simple.equalsIgnoreCase(id.simpleName())) {
            return false;
        }
        if (qualifier == null) {
            return true;
        }
        List<String> names = id.names();
        if (names.size() < 2) {
            return false;
        }
        return qualifier.equalsIgnoreCase(names.get(names.size() - 2));
    }

    private static void expandSelect(SqlSelect select, SqlColumnResolver resolver) {
        List<SqlSelectItem> items = select.selectItems();
        boolean hasStar = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).expr() instanceof SqlAllColumns) {
                hasStar = true;
                break;
            }
        }
        if (!hasStar) {
            return;
        }
        Map<String, SqlTable> byAlias = physicalByAlias(select.from());
        List<SqlSelectItem> next = new ArrayList<SqlSelectItem>(items.size() + 4);
        for (int i = 0; i < items.size(); i++) {
            SqlSelectItem item = items.get(i);
            if (!(item.expr() instanceof SqlAllColumns)) {
                next.add(item);
                continue;
            }
            SqlAllColumns star = (SqlAllColumns) item.expr();
            List<SqlSelectItem> expanded = expandOneStar(select, star, resolver, byAlias);
            if (expanded == null) {
                next.add(item);
            } else {
                next.addAll(expanded);
            }
        }
        items.clear();
        items.addAll(next);
    }

    private static List<SqlSelectItem> expandOneStar(SqlSelect select, SqlAllColumns star,
            SqlColumnResolver resolver, Map<String, SqlTable> byAlias) {
        if (star.owner() != null) {
            String owner = star.owner().simpleName();
            SqlTable table = resolveTable(owner, byAlias, select.from());
            if (table != null) {
                return columnsToItems(table, resolver, qualify(table));
            }
            SqlSubqueryTable sub = findSubquery(select.from(), owner);
            if (sub != null) {
                return subqueryOutputItems(sub, owner);
            }
            return null;
        }
        List<SqlTableSource> leaves = leafSources(select.from());
        if (leaves.isEmpty()) {
            return null;
        }
        List<SqlSelectItem> out = new ArrayList<SqlSelectItem>(8);
        boolean qualify = leaves.size() > 1;
        for (int i = 0; i < leaves.size(); i++) {
            SqlTableSource src = leaves.get(i);
            if (src instanceof SqlTable) {
                SqlTable table = (SqlTable) src;
                String q = qualify ? qualify(table) : (table.alias() != null ? table.alias() : null);
                List<SqlSelectItem> part = columnsToItems(table, resolver, q);
                if (part == null) {
                    return null;
                }
                out.addAll(part);
            } else if (src instanceof SqlSubqueryTable) {
                SqlSubqueryTable sub = (SqlSubqueryTable) src;
                List<SqlSelectItem> part = subqueryOutputItems(sub, sub.alias());
                if (part == null) {
                    return null;
                }
                out.addAll(part);
            } else {
                return null;
            }
        }
        return out;
    }

    private static List<SqlSelectItem> columnsToItems(SqlTable table, SqlColumnResolver resolver,
            String qualifier) {
        if (table.name() == null) {
            return null;
        }
        List<String> cols = resolver.columnsOf(table.name().simpleName());
        if (cols == null) {
            return null;
        }
        List<SqlSelectItem> items = new ArrayList<SqlSelectItem>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            if (col == null || col.trim().isEmpty()) {
                continue;
            }
            items.add(identItem(qualifier, col.trim()));
        }
        return items;
    }

    private static List<SqlSelectItem> subqueryOutputItems(SqlSubqueryTable sub, String qualifier) {
        if (!(sub.query() instanceof SqlSelect)) {
            return null;
        }
        SqlSelect inner = (SqlSelect) sub.query();
        List<SqlSelectItem> innerItems = inner.selectItems();
        List<SqlSelectItem> out = new ArrayList<SqlSelectItem>(innerItems.size());
        for (int i = 0; i < innerItems.size(); i++) {
            SqlSelectItem it = innerItems.get(i);
            if (it.expr() instanceof SqlAllColumns) {
                return null;
            }
            String name = it.alias();
            if (name == null || name.isEmpty()) {
                if (it.expr() instanceof SqlIdentifier) {
                    name = ((SqlIdentifier) it.expr()).simpleName();
                } else {
                    return null;
                }
            }
            out.add(identItem(qualifier, name));
        }
        return out;
    }

    private static SqlSelectItem identItem(String qualifier, String column) {
        SqlIdentifier id;
        if (qualifier == null || qualifier.isEmpty()) {
            id = SqlIdentifier.of(column);
        } else {
            id = new SqlIdentifier();
            id.addName(qualifier);
            id.addName(column);
        }
        SqlSelectItem item = new SqlSelectItem();
        item.setExpr(id);
        return item;
    }

    private static String qualify(SqlTable table) {
        if (table.alias() != null && table.alias().length() > 0) {
            return table.alias();
        }
        return table.name() == null ? null : table.name().simpleName();
    }

    private static SqlTable resolveTable(String owner, Map<String, SqlTable> byAlias, SqlTableSource from) {
        if (owner == null) {
            return null;
        }
        SqlTable hit = byAlias.get(owner.toLowerCase(Locale.ROOT));
        if (hit != null) {
            return hit;
        }
        List<SqlTable> all = new ArrayList<SqlTable>(4);
        collectPhysical(from, all);
        for (int i = 0; i < all.size(); i++) {
            SqlTable t = all.get(i);
            if (t.name() != null && owner.equalsIgnoreCase(t.name().simpleName())) {
                return t;
            }
        }
        return null;
    }

    private static SqlSubqueryTable findSubquery(SqlTableSource src, String owner) {
        if (src == null || owner == null) {
            return null;
        }
        if (src instanceof SqlSubqueryTable) {
            SqlSubqueryTable sub = (SqlSubqueryTable) src;
            if (sub.alias() != null && owner.equalsIgnoreCase(sub.alias())) {
                return sub;
            }
        } else if (src instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) src;
            SqlSubqueryTable left = findSubquery(join.left(), owner);
            if (left != null) {
                return left;
            }
            return findSubquery(join.right(), owner);
        }
        return null;
    }

    private static Map<String, SqlTable> physicalByAlias(SqlTableSource from) {
        List<SqlTable> all = new ArrayList<SqlTable>(4);
        collectPhysical(from, all);
        Map<String, SqlTable> map = new LinkedHashMap<String, SqlTable>();
        for (int i = 0; i < all.size(); i++) {
            SqlTable t = all.get(i);
            if (t.alias() != null && t.alias().length() > 0) {
                map.put(t.alias().toLowerCase(Locale.ROOT), t);
            }
            if (t.name() != null) {
                map.put(t.name().simpleName().toLowerCase(Locale.ROOT), t);
            }
        }
        return map;
    }

    private static List<SqlTableSource> leafSources(SqlTableSource src) {
        List<SqlTableSource> out = new ArrayList<SqlTableSource>(4);
        collectLeaves(src, out);
        return out;
    }

    private static void collectLeaves(SqlTableSource src, List<SqlTableSource> out) {
        if (src == null) {
            return;
        }
        if (src instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) src;
            collectLeaves(join.left(), out);
            collectLeaves(join.right(), out);
        } else {
            out.add(src);
        }
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

    private static SqlExpr copyExpr(SqlExpr expr) {
        return (SqlExpr) expr.copy();
    }
}
