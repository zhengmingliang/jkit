package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

/**
 * 常见改写：补/读/改分页（LIMIT/TOP/FETCH/ROWNUM/row_number）、AND WHERE、换表名。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlRewriter {
    private SqlRewriter() {
    }

    /**
     * 给 SELECT 补 LIMIT；已有则不改。UNION 时只作用于最外层（集合运算链末端）。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @param dialect 方言
     * @return 原对象（就地修改）
     */
    public static SqlStatement addLimit(SqlStatement statement, long rowCount, SqlDialectSpec dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        if (getLimit(statement) != null) {
            return statement;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        applyPagination(select, 0L, rowCount, d, false);
        return statement;
    }

    /**
     * 读取当前行数上限：LIMIT/TOP，或 Oracle ROWNUM / SQL Server row_number 包装的页大小。
     * UNION 时读集合运算链末端的 LIMIT/TOP。非数字字面量时返回 {@code null}。
     *
     * @param statement 语句
     * @return 行数，无则 null
     */
    public static Long getLimit(SqlStatement statement) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return null;
        }
        SqlSelect owner = paginationOwner(select);
        Long explicit = explicitLimit(owner);
        if (explicit != null) {
            return explicit;
        }
        RowNumPage page = detectRowNumPage(select);
        if (page != null) {
            return Long.valueOf(page.end - page.offset);
        }
        return null;
    }

    /**
     * 读取当前 OFFSET（LIMIT.offset，或 ROWNUM/row_number 包装的下界）。
     *
     * @param statement 语句
     * @return 偏移，无则 null
     */
    public static Long getOffset(SqlStatement statement) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return null;
        }
        SqlSelect owner = paginationOwner(select);
        if (owner.limit() != null && owner.limit().offset() != null) {
            return asLong(owner.limit().offset());
        }
        RowNumPage page = detectRowNumPage(select);
        if (page != null && page.offset > 0L) {
            return Long.valueOf(page.offset);
        }
        return null;
    }

    /**
     * 设置/替换行数上限（就地）。已识别 ROWNUM/row_number 包装时只改数值边界；
     * UNION 时改写集合运算链末端。
     *
     * @param statement 语句
     * @param rowCount 行数（负值清空 LIMIT/TOP；包装分页保留结构）
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement setLimit(SqlStatement statement, long rowCount, SqlDialectSpec dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (rowCount < 0) {
            RowNumPage page = detectRowNumPage(select);
            if (page == null) {
                clearPagination(paginationOwner(select));
            }
            return statement;
        }
        Long offset = getOffset(statement);
        long off = offset == null ? 0L : offset.longValue();
        applyPagination(select, off, rowCount, d, offset != null);
        return statement;
    }

    /**
     * 设置/替换 OFFSET（就地）。无 LIMIT/TOP 时不同时发明行数。
     *
     * @param statement 语句
     * @param offset 偏移（负值视为 0）
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement setOffset(SqlStatement statement, long offset, SqlDialectSpec dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        long off = offset < 0 ? 0L : offset;
        RowNumPage page = detectRowNumPage(select);
        if (page != null) {
            long lim = page.end - page.offset;
            if (lim < 1L) {
                lim = 1L;
            }
            // 与 applyPagination 一致：经典单层 ROWNUM 在 offset>0 时扩成双层
            applyPagination(select, off, lim, d, true);
            return statement;
        }
        Long limit = getLimit(statement);
        SqlSelect owner = paginationOwner(select);
        if (limit == null) {
            ensureLimitNode(owner, d, true);
            owner.limit().setOffset(number(off));
            if (d.supportsTop()) {
                owner.setTop(null);
                owner.limit().setFetchStyle(true);
            } else if (d.supportsFetchFirst() && !d.supportsLimitOffset()) {
                owner.limit().setFetchStyle(true);
            }
            return statement;
        }
        applyPagination(select, off, limit.longValue(), d, true);
        return statement;
    }

    /**
     * 按页码改写分页（就地）。{@code pageNo} 从 1 起；{@code offset=(pageNo-1)*pageSize}。
     * 已包装的 ROWNUM/row_number 只更新边界，不叠 OFFSET/FETCH。
     *
     * @param statement 语句
     * @param pageNo 页码（&lt;1 视为 1）
     * @param pageSize 页大小（&lt;1 视为 1）
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement setPage(SqlStatement statement, long pageNo, long pageSize,
            SqlDialectSpec dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        long pn = pageNo < 1 ? 1L : pageNo;
        long ps = pageSize < 1 ? 1L : pageSize;
        long offset = (pn - 1L) * ps;
        applyPagination(select, offset, ps, d, true);
        return statement;
    }

    /** 按方言就地挂/改分页（包内供 {@link SqlBuilder} 复用）。 */
    static void applyPagination(SqlSelect root, long offset, long rowCount,
            SqlDialectSpec dialect, boolean withOffset) {
        RowNumPage page = detectRowNumPage(root);
        if (page != null) {
            if (page.kind == RowNumPage.Kind.ORACLE_SIMPLE && offset > 0L) {
                expandOracleSimpleToNested(root, page, offset, rowCount);
            } else {
                applyRowNumBounds(page, offset, rowCount);
            }
            return;
        }
        // 经典 Oracle（≤11g）：裸 SELECT 一律 ROWNUM 包装，禁止 OFFSET/FETCH
        if (dialect.supportsRownum() && !dialect.supportsFetchFirst()) {
            wrapOracleRownum(root, offset, rowCount);
            return;
        }
        SqlSelect select = paginationOwner(root);
        clearPagination(select);
        // SQL Server 无偏移（或 offset=0）用 TOP
        if (dialect.supportsTop() && offset == 0L) {
            select.setTop(number(rowCount));
            return;
        }
        SqlLimit limit = new SqlLimit();
        limit.setRowCount(number(rowCount));
        if (offset > 0L) {
            limit.setOffset(number(offset));
        }
        if (dialect.supportsTop()
                || (dialect.supportsFetchFirst() && !dialect.supportsLimitOffset())) {
            // SQL Server（有偏移）或 Oracle 12c+：OFFSET/FETCH
            limit.setFetchStyle(true);
        } else if (dialect.supportsLimitOffset() && dialect.supportsCommaLimitOffset() && offset > 0L) {
            limit.setMysqlCommaStyle(true);
        }
        select.setLimit(limit);
    }

    /**
     * 把裸 SELECT（含 UNION 链）包成 ROWNUM 分页；offset=0 用单层，否则双层。
     * WITH 留在外层。
     */
    private static void wrapOracleRownum(SqlSelect root, long offset, long rowCount) {
        long off = offset < 0L ? 0L : offset;
        long end = off + rowCount;
        if (end < 1L) {
            end = 1L;
        }
        SqlSelect core = detachSelectBody(root);
        if (off == 0L) {
            SqlSubqueryTable from = new SqlSubqueryTable();
            from.setQuery(core);
            from.setAlias("XX");
            SqlSelectItem star = new SqlSelectItem();
            star.setExpr(new SqlAllColumns());
            root.addSelectItem(star);
            root.setFrom(from);
            root.setWhere(SqlBinaryExpr.of(SqlIdentifier.of("ROWNUM"), SqlBinaryOp.LE, number(end)));
            return;
        }
        SqlSelect middle = new SqlSelect();
        SqlSelectItem midStar = new SqlSelectItem();
        SqlAllColumns all = new SqlAllColumns();
        all.setOwner(SqlIdentifier.of("XX"));
        midStar.setExpr(all);
        middle.addSelectItem(midStar);
        SqlSelectItem rnItem = new SqlSelectItem();
        rnItem.setExpr(SqlIdentifier.of("ROWNUM"));
        rnItem.setAlias("RN");
        middle.addSelectItem(rnItem);
        SqlSubqueryTable midFrom = new SqlSubqueryTable();
        midFrom.setQuery(core);
        midFrom.setAlias("XX");
        middle.setFrom(midFrom);
        middle.setWhere(SqlBinaryExpr.of(SqlIdentifier.of("ROWNUM"), SqlBinaryOp.LE, number(end)));

        SqlSubqueryTable outerFrom = new SqlSubqueryTable();
        outerFrom.setQuery(middle);
        outerFrom.setAlias("XXX");
        SqlSelectItem outerStar = new SqlSelectItem();
        outerStar.setExpr(new SqlAllColumns());
        root.addSelectItem(outerStar);
        root.setFrom(outerFrom);
        root.setWhere(SqlBinaryExpr.of(SqlIdentifier.of("RN"), SqlBinaryOp.GT, number(off)));
    }

    /**
     * 将 root 的 SELECT 体挪到新节点（WITH/注释留在 root）。
     */
    private static SqlSelect detachSelectBody(SqlSelect root) {
        SqlSelect core = new SqlSelect();
        core.setDistinct(root.distinct());
        root.setDistinct(false);
        core.distinctOn().addAll(root.distinctOn());
        root.distinctOn().clear();
        core.setTop(root.top());
        root.setTop(null);
        core.setTopWithTies(root.topWithTies());
        root.setTopWithTies(false);
        core.selectItems().addAll(root.selectItems());
        root.selectItems().clear();
        core.setFrom(root.from());
        root.setFrom(null);
        core.setWhere(root.where());
        root.setWhere(null);
        core.groupBy().addAll(root.groupBy());
        root.groupBy().clear();
        core.setGroupByRollup(root.groupByRollup());
        root.setGroupByRollup(false);
        core.setHaving(root.having());
        root.setHaving(null);
        core.orderBy().addAll(root.orderBy());
        root.orderBy().clear();
        core.setLimit(root.limit());
        root.setLimit(null);
        core.setForUpdate(root.forUpdate());
        root.setForUpdate(false);
        core.setLockInShare(root.lockInShare());
        root.setLockInShare(false);
        core.setForUpdateTail(root.forUpdateTail());
        root.setForUpdateTail(null);
        core.forUpdateOf().addAll(root.forUpdateOf());
        root.forUpdateOf().clear();
        core.setForUpdateWait(root.forUpdateWait());
        root.setForUpdateWait(null);
        core.setUnion(root.union());
        root.setUnion(null);
        core.setUnionOp(root.unionOp());
        root.setUnionOp(null);
        core.setConnectBy(root.connectBy());
        root.setConnectBy(null);
        core.setStartWith(root.startWith());
        root.setStartWith(null);
        core.windows().addAll(root.windows());
        root.windows().clear();
        core.setValuesClause(root.valuesClause());
        root.setValuesClause(false);
        // 优化器 hint / WITH 留在外层 root
        return core;
    }

    private static void ensureLimitNode(SqlSelect select, SqlDialectSpec dialect, boolean fetchIfNeeded) {
        if (select.limit() == null) {
            SqlLimit limit = new SqlLimit();
            if (fetchIfNeeded && (dialect.supportsTop()
                    || (dialect.supportsFetchFirst() && !dialect.supportsLimitOffset()))) {
                limit.setFetchStyle(true);
            }
            select.setLimit(limit);
        }
    }

    private static void clearPagination(SqlSelect select) {
        select.setTop(null);
        select.setLimit(null);
    }

    /**
     * UNION/INTERSECT 链的末端 SELECT（ORDER BY / LIMIT 挂靠处）。
     */
    static SqlSelect paginationOwner(SqlSelect select) {
        SqlSelect cur = select;
        while (cur.union() != null) {
            cur = cur.union();
        }
        return cur;
    }

    private static Long explicitLimit(SqlSelect select) {
        if (select.limit() != null && select.limit().rowCount() != null) {
            return asLong(select.limit().rowCount());
        }
        if (select.top() != null) {
            return asLong(select.top());
        }
        return null;
    }

    private static SqlSelect asSelect(SqlStatement statement) {
        return statement instanceof SqlSelect ? (SqlSelect) statement : null;
    }

    private static SqlLiteral number(long value) {
        return SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(value));
    }

    private static Long asLong(SqlExpr expr) {
        if (!(expr instanceof SqlLiteral)) {
            return null;
        }
        SqlLiteral lit = (SqlLiteral) expr;
        if (lit.kind() != SqlLiteral.Kind.NUMBER || lit.value() == null) {
            return null;
        }
        try {
            return Long.valueOf(lit.value());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isIdent(SqlExpr expr, String name) {
        return expr instanceof SqlIdentifier
                && name.equalsIgnoreCase(((SqlIdentifier) expr).simpleName());
    }

    private static String identName(SqlExpr expr) {
        return expr instanceof SqlIdentifier ? ((SqlIdentifier) expr).simpleName() : null;
    }

    private static void setCmpNumber(SqlBinaryExpr cmp, long value) {
        cmp.setRight(number(value));
    }

    /**
     * Oracle nested / simple ROWNUM 或 SQL Server row_number 包装分页。
     */
    private static final class RowNumPage {
        enum Kind {
            ORACLE_NESTED,
            ORACLE_SIMPLE,
            SS_ROW_NUMBER
        }

        Kind kind;
        long offset;
        long end;
        SqlBinaryExpr startCmp;
        SqlBinaryExpr endCmp;
    }

    private static RowNumPage detectRowNumPage(SqlSelect select) {
        if (select == null || select.from() == null || !(select.from() instanceof SqlSubqueryTable)) {
            return null;
        }
        RowNumPage nested = detectOracleNested(select);
        if (nested != null) {
            return nested;
        }
        RowNumPage ss = detectSqlServerRowNumber(select);
        if (ss != null) {
            return ss;
        }
        return detectOracleSimple(select);
    }

    /**
     * {@code SELECT * FROM (SELECT XX.*, ROWNUM AS RN FROM (...) XX WHERE ROWNUM <= end)
     * XXX WHERE RN > offset}
     */
    private static RowNumPage detectOracleNested(SqlSelect outer) {
        Bound start = parseLowerBound(outer.where(), null);
        if (start == null) {
            return null;
        }
        SqlSelect middle = subquerySelect(outer);
        if (middle == null || middle.where() == null) {
            return null;
        }
        Bound end = parseUpperBound(middle.where(), "ROWNUM");
        if (end == null) {
            return null;
        }
        if (!selectHasRownumAlias(middle, start.name)) {
            return null;
        }
        if (!(middle.from() instanceof SqlSubqueryTable)) {
            return null;
        }
        if (end.value <= start.value) {
            return null;
        }
        RowNumPage page = new RowNumPage();
        page.kind = RowNumPage.Kind.ORACLE_NESTED;
        page.offset = start.value;
        page.end = end.value;
        page.startCmp = start.cmp;
        page.endCmp = end.cmp;
        return page;
    }

    /**
     * {@code ... FROM (sub) a WHERE rownum <= n}（可带 select 列表中的 rownum 别名）。
     */
    private static RowNumPage detectOracleSimple(SqlSelect outer) {
        Bound end = parseUpperBound(outer.where(), "ROWNUM");
        if (end == null) {
            return null;
        }
        if (!(outer.from() instanceof SqlSubqueryTable)) {
            return null;
        }
        RowNumPage page = new RowNumPage();
        page.kind = RowNumPage.Kind.ORACLE_SIMPLE;
        page.offset = 0L;
        page.end = end.value;
        page.endCmp = end.cmp;
        return page;
    }

    /**
     * {@code SELECT * FROM (SELECT ..., row_number() AS RN FROM ...) XX
     * WHERE RN > offset AND RN <= end}
     */
    private static RowNumPage detectSqlServerRowNumber(SqlSelect outer) {
        SqlSelect middle = subquerySelect(outer);
        if (middle == null || outer.where() == null) {
            return null;
        }
        String alias = findRowNumberAlias(middle);
        if (alias == null) {
            return null;
        }
        SqlExpr where = outer.where();
        if (!(where instanceof SqlBinaryExpr) || ((SqlBinaryExpr) where).operator() != SqlBinaryOp.AND) {
            return null;
        }
        SqlBinaryExpr and = (SqlBinaryExpr) where;
        Bound start = parseLowerBound(and.left(), alias);
        if (start == null) {
            start = parseLowerBound(and.right(), alias);
        }
        Bound end = parseUpperBound(and.left(), alias);
        if (end == null) {
            end = parseUpperBound(and.right(), alias);
        }
        if (start == null || end == null || end.value <= start.value) {
            return null;
        }
        RowNumPage page = new RowNumPage();
        page.kind = RowNumPage.Kind.SS_ROW_NUMBER;
        page.offset = start.value;
        page.end = end.value;
        page.startCmp = start.cmp;
        page.endCmp = end.cmp;
        return page;
    }

    private static SqlSelect subquerySelect(SqlSelect outer) {
        if (!(outer.from() instanceof SqlSubqueryTable)) {
            return null;
        }
        SqlStatement q = ((SqlSubqueryTable) outer.from()).query();
        return q instanceof SqlSelect ? (SqlSelect) q : null;
    }

    private static boolean selectHasRownumAlias(SqlSelect select, String alias) {
        for (SqlSelectItem item : select.selectItems()) {
            if (item.alias() != null && alias.equalsIgnoreCase(item.alias())
                    && isIdent(item.expr(), "ROWNUM")) {
                return true;
            }
        }
        return false;
    }

    private static String findRowNumberAlias(SqlSelect select) {
        for (SqlSelectItem item : select.selectItems()) {
            if (!(item.expr() instanceof SqlFunctionExpr)) {
                continue;
            }
            SqlFunctionExpr fn = (SqlFunctionExpr) item.expr();
            if (fn.name() != null && "ROW_NUMBER".equalsIgnoreCase(fn.name().simpleName())) {
                return item.alias() != null ? item.alias() : "ROW_NUMBER";
            }
        }
        return null;
    }

    private static final class Bound {
        String name;
        long value;
        SqlBinaryExpr cmp;
    }

    /** {@code alias > n} / {@code alias >= n} → exclusive lower bound. */
    private static Bound parseLowerBound(SqlExpr where, String expectedName) {
        if (!(where instanceof SqlBinaryExpr)) {
            return null;
        }
        SqlBinaryExpr cmp = (SqlBinaryExpr) where;
        String name = identName(cmp.left());
        Long num = asLong(cmp.right());
        if (name == null || num == null) {
            return null;
        }
        if (expectedName != null && !expectedName.equalsIgnoreCase(name)) {
            return null;
        }
        Bound b = new Bound();
        b.name = name;
        b.cmp = cmp;
        if (cmp.operator() == SqlBinaryOp.GT) {
            b.value = num.longValue();
            return b;
        }
        if (cmp.operator() == SqlBinaryOp.GE) {
            b.value = num.longValue() - 1L;
            return b;
        }
        return null;
    }

    /** {@code alias <= n} / {@code alias < n} → inclusive end row. */
    private static Bound parseUpperBound(SqlExpr where, String expectedName) {
        if (!(where instanceof SqlBinaryExpr)) {
            return null;
        }
        SqlBinaryExpr cmp = (SqlBinaryExpr) where;
        String name = identName(cmp.left());
        Long num = asLong(cmp.right());
        if (name == null || num == null) {
            return null;
        }
        if (expectedName != null && !expectedName.equalsIgnoreCase(name)) {
            return null;
        }
        Bound b = new Bound();
        b.name = name;
        b.cmp = cmp;
        if (cmp.operator() == SqlBinaryOp.LE) {
            b.value = num.longValue();
            return b;
        }
        if (cmp.operator() == SqlBinaryOp.LT) {
            b.value = num.longValue() - 1L;
            return b;
        }
        return null;
    }

    private static void applyRowNumBounds(RowNumPage page, long offset, long rowCount) {
        long off = offset < 0L ? 0L : offset;
        long end = off + rowCount;
        if (page.startCmp != null) {
            setCmpNumber(page.startCmp, off);
            if (page.startCmp.operator() == SqlBinaryOp.GE) {
                page.startCmp.setOperator(SqlBinaryOp.GT);
            }
        }
        if (page.endCmp != null) {
            setCmpNumber(page.endCmp, end);
            if (page.endCmp.operator() == SqlBinaryOp.LT) {
                page.endCmp.setOperator(SqlBinaryOp.LE);
            }
        }
        page.offset = off;
        page.end = end;
    }

    /**
     * 简单 {@code WHERE ROWNUM <= n} 在需要 offset 时升级为双层 ROWNUM 包装。
     */
    private static void expandOracleSimpleToNested(SqlSelect outer, RowNumPage page,
            long offset, long rowCount) {
        long off = offset < 0L ? 0L : offset;
        long end = off + rowCount;
        SqlStatement innerQuery = ((SqlSubqueryTable) outer.from()).query();

        SqlSelect core = new SqlSelect();
        if (innerQuery instanceof SqlSelect) {
            core = (SqlSelect) innerQuery;
        } else {
            return;
        }

        SqlSelect middle = new SqlSelect();
        SqlSelectItem star = new SqlSelectItem();
        SqlAllColumns all = new SqlAllColumns();
        all.setOwner(SqlIdentifier.of("XX"));
        star.setExpr(all);
        middle.addSelectItem(star);
        SqlSelectItem rnItem = new SqlSelectItem();
        rnItem.setExpr(SqlIdentifier.of("ROWNUM"));
        rnItem.setAlias("RN");
        middle.addSelectItem(rnItem);
        SqlSubqueryTable midFrom = new SqlSubqueryTable();
        midFrom.setQuery(core);
        midFrom.setAlias("XX");
        middle.setFrom(midFrom);
        middle.setWhere(SqlBinaryExpr.of(SqlIdentifier.of("ROWNUM"), SqlBinaryOp.LE, number(end)));

        SqlSubqueryTable outerFrom = new SqlSubqueryTable();
        outerFrom.setQuery(middle);
        outerFrom.setAlias("XXX");
        outer.setFrom(outerFrom);
        outer.selectItems().clear();
        SqlSelectItem outerStar = new SqlSelectItem();
        outerStar.setExpr(new SqlAllColumns());
        outer.addSelectItem(outerStar);
        outer.setWhere(SqlBinaryExpr.of(SqlIdentifier.of("RN"), SqlBinaryOp.GT, number(off)));
        outer.setTop(null);
        outer.setLimit(null);
    }

    /**
     * 把谓词 AND 到顶层 WHERE（就地修改；公开门面 {@link SQL#andWhere} 会先 clone）。
     *
     * @param statement 语句
     * @param predicate 谓词
     * @return 原对象
     */
    public static SqlStatement andWhere(SqlStatement statement, SqlExpr predicate) {
        if (predicate == null) {
            return statement;
        }
        if (statement instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) statement;
            select.setWhere(and(select.where(), predicate));
        } else if (statement instanceof SqlUpdate) {
            SqlUpdate update = (SqlUpdate) statement;
            update.setWhere(and(update.where(), predicate));
        } else if (statement instanceof SqlDelete) {
            SqlDelete delete = (SqlDelete) statement;
            delete.setWhere(and(delete.where(), predicate));
        }
        return statement;
    }

    /**
     * 替换物理表名（忽略大小写；就地修改；公开门面 {@link SQL#replaceTable} 会先 clone）。
     *
     * @param statement 语句
     * @param from 原表简单名
     * @param to 新表简单名
     * @return 原对象
     */
    public static SqlStatement replaceTable(SqlStatement statement, final String from, final String to) {
        if (statement == null || from == null || to == null) {
            return statement;
        }
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlTable) {
                    SqlIdentifier name = ((SqlTable) node).name();
                    if (name != null && from.equalsIgnoreCase(name.simpleName())) {
                        name.names().set(name.names().size() - 1, to);
                    }
                }
                return true;
            }
        });
        return statement;
    }

    /**
     * 替换列名（忽略大小写；多段名改最后一段；就地修改；公开门面 {@link SQL#replaceColumn} 会先 clone）。
     * 不改表名与表别名（跳过 {@link SqlTable} 子树中的标识符）。
     *
     * @param statement 语句
     * @param from 原列简单名
     * @param to 新列简单名
     * @return 原对象
     */
    public static SqlStatement replaceColumn(SqlStatement statement, final String from, final String to) {
        if (statement == null || from == null || to == null) {
            return statement;
        }
        statement.accept(new SqlVisitorAdapter() {
            private int tableDepth;

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlTable) {
                    tableDepth++;
                    return true;
                }
                if (tableDepth == 0 && node instanceof SqlIdentifier) {
                    SqlIdentifier id = (SqlIdentifier) node;
                    if (from.equalsIgnoreCase(id.simpleName()) && !id.names().isEmpty()) {
                        id.names().set(id.names().size() - 1, to);
                    }
                }
                return true;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void endVisit(SqlNode node) {
                if (node instanceof SqlTable && tableDepth > 0) {
                    tableDepth--;
                }
            }
        });
        return statement;
    }

    private static SqlExpr and(SqlExpr left, SqlExpr right) {
        if (left == null) {
            return right;
        }
        return SqlBinaryExpr.of(left, SqlBinaryOp.AND, right);
    }
}
