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
            // 先清掉旧 LIMIT/TOP，避免 detachSelectBody 把它们拷进子查询
            clearPagination(paginationOwner(root));
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
        // 调用方已 clearPagination；此处仅兜底 UNION 末端残留
        SqlSelect coreOwner = paginationOwner(core);
        if (coreOwner.limit() != null || coreOwner.top() != null) {
            clearPagination(coreOwner);
        }
        if (off == 0L) {
            wrapOracleRownumOffset0(root, core, end);
            return;
        }
        SqlSelect middle = new SqlSelect();
        SqlSelectItem midStar = new SqlSelectItem();
        SqlAllColumns all = new SqlAllColumns();
        all.setOwner(ID_XX);
        midStar.setExpr(all);
        middle.addSelectItem(midStar);
        SqlSelectItem rnItem = new SqlSelectItem();
        rnItem.setExpr(ID_ROWNUM);
        rnItem.setAlias("RN");
        middle.addSelectItem(rnItem);
        SqlSubqueryTable midFrom = new SqlSubqueryTable();
        midFrom.setQuery(core);
        midFrom.setAlias("XX");
        middle.setFrom(midFrom);
        middle.setWhere(SqlBinaryExpr.of(ID_ROWNUM, SqlBinaryOp.LE, number(end)));

        SqlSubqueryTable outerFrom = new SqlSubqueryTable();
        outerFrom.setQuery(middle);
        outerFrom.setAlias("XXX");
        SqlSelectItem outerStar = new SqlSelectItem();
        outerStar.setExpr(new SqlAllColumns());
        root.addSelectItem(outerStar);
        root.setFrom(outerFrom);
        root.setWhere(SqlBinaryExpr.of(ID_RN, SqlBinaryOp.GT, number(off)));
    }

    /** offset=0：单层 {@code SELECT * FROM (core) XX WHERE ROWNUM <= end}。 */
    private static void wrapOracleRownumOffset0(SqlSelect root, SqlSelect core, long end) {
        SqlSubqueryTable from = new SqlSubqueryTable();
        from.setQuery(core);
        from.setAlias("XX");
        SqlSelectItem star = new SqlSelectItem();
        star.setExpr(new SqlAllColumns());
        root.addSelectItem(star);
        root.setFrom(from);
        root.setWhere(SqlBinaryExpr.of(ID_ROWNUM, SqlBinaryOp.LE, number(end)));
    }

    /**
     * 将 root 的 SELECT 体挪到新节点（WITH/注释留在 root）。
     */
    private static SqlSelect detachSelectBody(SqlSelect root) {
        SqlSelect core = new SqlSelect();
        core.setDistinct(root.distinct());
        root.setDistinct(false);
        moveList(root.distinctOn(), core.distinctOn());
        core.setTop(root.top());
        root.setTop(null);
        core.setTopWithTies(root.topWithTies());
        root.setTopWithTies(false);
        moveList(root.selectItems(), core.selectItems());
        core.setFrom(root.from());
        root.setFrom(null);
        core.setWhere(root.where());
        root.setWhere(null);
        moveList(root.groupBy(), core.groupBy());
        core.setGroupByRollup(root.groupByRollup());
        root.setGroupByRollup(false);
        core.setHaving(root.having());
        root.setHaving(null);
        moveList(root.orderBy(), core.orderBy());
        core.setLimit(root.limit());
        root.setLimit(null);
        core.setForUpdate(root.forUpdate());
        root.setForUpdate(false);
        core.setLockInShare(root.lockInShare());
        root.setLockInShare(false);
        core.setForUpdateTail(root.forUpdateTail());
        root.setForUpdateTail(null);
        moveList(root.forUpdateOf(), core.forUpdateOf());
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
        moveList(root.windows(), core.windows());
        core.setValuesClause(root.valuesClause());
        root.setValuesClause(false);
        // 优化器 hint / WITH 留在外层 root
        return core;
    }

    /** 非空才 addAll+clear，减少空列表抖动。 */
    private static <T> void moveList(java.util.List<T> from, java.util.List<T> to) {
        if (from.isEmpty()) {
            return;
        }
        to.addAll(from);
        from.clear();
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

    /**
     * 小整数数字字面量缓存（含常见 pageSize 上界 10000）。
     * wrap/applyBounds 只替换引用、不 mutate value；克隆器会深拷贝，故可安全共享。
     */
    private static final int SMALL_NUMBER_CACHE = 10001;
    private static final SqlLiteral[] SMALL_NUMBER_LITERALS = buildSmallNumberLiterals(SMALL_NUMBER_CACHE);

    /** ROWNUM 包装热路径冻结标识符（只读，勿 setNames/addName）。 */
    private static final SqlIdentifier ID_ROWNUM = SqlIdentifier.of("ROWNUM");
    private static final SqlIdentifier ID_RN = SqlIdentifier.of("RN");
    private static final SqlIdentifier ID_XX = SqlIdentifier.of("XX");

    private static SqlLiteral[] buildSmallNumberLiterals(int n) {
        SqlLiteral[] arr = new SqlLiteral[n];
        for (int i = 0; i < n; i++) {
            arr[i] = SqlLiteral.of(SqlLiteral.Kind.NUMBER, Integer.toString(i));
        }
        return arr;
    }

    private static SqlLiteral number(long value) {
        if (value >= 0L && value < (long) SMALL_NUMBER_LITERALS.length) {
            return SMALL_NUMBER_LITERALS[(int) value];
        }
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
        all.setOwner(ID_XX);
        star.setExpr(all);
        middle.addSelectItem(star);
        SqlSelectItem rnItem = new SqlSelectItem();
        rnItem.setExpr(ID_ROWNUM);
        rnItem.setAlias("RN");
        middle.addSelectItem(rnItem);
        SqlSubqueryTable midFrom = new SqlSubqueryTable();
        midFrom.setQuery(core);
        midFrom.setAlias("XX");
        middle.setFrom(midFrom);
        middle.setWhere(SqlBinaryExpr.of(ID_ROWNUM, SqlBinaryOp.LE, number(end)));

        SqlSubqueryTable outerFrom = new SqlSubqueryTable();
        outerFrom.setQuery(middle);
        outerFrom.setAlias("XXX");
        outer.setFrom(outerFrom);
        outer.selectItems().clear();
        SqlSelectItem outerStar = new SqlSelectItem();
        outerStar.setExpr(new SqlAllColumns());
        outer.addSelectItem(outerStar);
        outer.setWhere(SqlBinaryExpr.of(ID_RN, SqlBinaryOp.GT, number(off)));
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
                        name.ensureMutableNames();
                        name.names().set(name.names().size() - 1, to);
                    }
                }
                return true;
            }
        });
        return statement;
    }

    /**
     * 按目标方言适配分页形态（就地）：读取当前 limit/offset（含 LIMIT/TOP/ROWNUM/row_number），
     * 清掉旧分页形态，再 {@link #applyPagination}。无分页时 no-op。
     * 公开门面 {@link SQL#adaptPagination} / {@link SQL#format} 会先 clone 再调用。
     *
     * @param statement 语句
     * @param dialect 目标方言
     * @return 原对象
     * @since 2.0.1
     */
    public static SqlStatement adaptPagination(SqlStatement statement, SqlDialectSpec dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        // 一次探测 ROWNUM/row_number，避免 getLimit/getOffset/strip 重复扫描
        RowNumPage page = detectRowNumPage(select);
        long limVal;
        long offVal;
        boolean hasOff;
        if (page != null) {
            limVal = page.end - page.offset;
            offVal = page.offset;
            hasOff = page.offset > 0L;
        } else {
            SqlSelect owner = paginationOwner(select);
            Long lim = explicitLimit(owner);
            if (lim == null) {
                return statement;
            }
            limVal = lim.longValue();
            if (owner.limit() != null && owner.limit().offset() != null) {
                Long o = asLong(owner.limit().offset());
                offVal = o == null ? 0L : o.longValue();
                hasOff = o != null;
            } else {
                offVal = 0L;
                hasOff = false;
            }
            // 快路径：裸 LIMIT/TOP → 经典 ORACLE ROWNUM（MySQL→ORACLE 热路径）
            if (d.supportsRownum() && !d.supportsFetchFirst()) {
                clearPagination(owner);
                wrapOracleRownum(select, offVal, limVal);
                return statement;
            }
        }
        // 形态兼容且无需规范化逗号 LIMIT 时 no-op（含 ROWNUM↔LIMIT 完整转换）
        if (isPaginationFormCompatible(select, d) && !needsCommaLimitNormalize(select, d)) {
            return statement;
        }
        boolean withOffset = hasOff || offVal > 0L;
        if (page != null) {
            unwrapRowNumPage(select, page);
        } else {
            clearPagination(paginationOwner(select));
        }
        applyPagination(select, offVal, limVal, d, withOffset);
        return statement;
    }

    /**
     * 回写路径是否需要按目标方言改写分页（供 {@link SQL#format} 决定是否 clone+adapt）。
     * 基于 {@link #isPaginationFormCompatible}：LIMIT↔ROWNUM/FETCH/TOP 不兼容时 true；
     * MySQL 逗号 {@code LIMIT off,n} 而目标不支持逗号风格时也 true（规范化为 {@code LIMIT n OFFSET m}）。
     * 同形态同方言（如已是非逗号 PG LIMIT → format POSTGRES）为 false，零额外开销。
     *
     * @param statement 语句
     * @param dialect 目标方言
     * @return 需要适配时 true
     * @since 2.0.1
     */
    /**
     * format 快路径：经典 ORACLE、offset=0、裸 LIMIT/TOP、无 WITH/注释/UNION 时返回 ROWNUM 上界；
     * 否则 0（走 clone+adapt）。不修改入参。
     */
    static long simpleOracleRownumWrapEnd(SqlStatement statement, SqlDialectSpec dialect) {
        if (!(statement instanceof SqlSelect)) {
            return 0L;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (!d.supportsRownum() || d.supportsFetchFirst()) {
            return 0L;
        }
        SqlSelect select = (SqlSelect) statement;
        if (select.withRecursive() || !select.withItems().isEmpty()) {
            return 0L;
        }
        if (!select.comments().isEmpty()) {
            return 0L;
        }
        if (select.union() != null) {
            return 0L;
        }
        if (detectRowNumPage(select) != null) {
            return 0L;
        }
        SqlSelect owner = paginationOwner(select);
        Long lim = explicitLimit(owner);
        if (lim == null || lim.longValue() < 1L) {
            return 0L;
        }
        if (owner.limit() != null && owner.limit().offset() != null) {
            Long off = asLong(owner.limit().offset());
            if (off != null && off.longValue() > 0L) {
                return 0L;
            }
        }
        return lim.longValue();
    }

    public static boolean paginationNeedsAdapt(SqlStatement statement, SqlDialectSpec dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null || getLimit(statement) == null) {
            return false;
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (!isPaginationFormCompatible(select, d)) {
            return true;
        }
        return needsCommaLimitNormalize(select, d);
    }

    /**
     * MySQL/ClickHouse 逗号 LIMIT 在目标方言不支持时需规范化。
     */
    private static boolean needsCommaLimitNormalize(SqlSelect select, SqlDialectSpec d) {
        if (d.supportsCommaLimitOffset()) {
            return false;
        }
        SqlSelect owner = paginationOwner(select);
        return owner.limit() != null
                && !owner.limit().fetchStyle()
                && owner.limit().mysqlCommaStyle();
    }

    /**
     * 追加 SELECT 列表项（就地；公开门面 {@link SQL#addSelectItem} 会先 clone）。
     *
     * @param statement 语句
     * @param expr 表达式
     * @param alias 别名，可空
     * @return 原对象
     * @since 2.0.1
     */
    public static SqlStatement addSelectItem(SqlStatement statement, SqlExpr expr, String alias) {
        SqlSelect select = asSelect(statement);
        if (select == null || expr == null) {
            return statement;
        }
        SqlSelectItem item = new SqlSelectItem();
        item.setExpr(expr);
        if (alias != null && !alias.isEmpty()) {
            item.setAlias(alias);
        }
        select.addSelectItem(item);
        return statement;
    }

    /**
     * 按简单列名（忽略大小写）从 SELECT 列表移除；可匹配 {@code t.col} 的最后一段或显式别名。
     * 不允许删光（至少保留一项），否则抛 {@link IllegalArgumentException}。
     *
     * @param statement 语句
     * @param columnSimpleName 列简单名
     * @return 原对象
     * @since 2.0.1
     */
    public static SqlStatement removeSelectItem(SqlStatement statement, String columnSimpleName) {
        SqlSelect select = asSelect(statement);
        if (select == null || columnSimpleName == null || columnSimpleName.isEmpty()) {
            return statement;
        }
        java.util.List<SqlSelectItem> items = select.selectItems();
        java.util.List<SqlSelectItem> kept = new java.util.ArrayList<SqlSelectItem>(items.size());
        boolean removed = false;
        for (SqlSelectItem item : items) {
            if (!removed && selectItemMatchesSimpleName(item, columnSimpleName)) {
                removed = true;
                continue;
            }
            kept.add(item);
        }
        if (!removed) {
            return statement;
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("cannot remove last select item: " + columnSimpleName);
        }
        items.clear();
        items.addAll(kept);
        return statement;
    }

    private static boolean selectItemMatchesSimpleName(SqlSelectItem item, String simpleName) {
        if (item.alias() != null && simpleName.equalsIgnoreCase(item.alias())) {
            return true;
        }
        if (item.expr() instanceof SqlIdentifier) {
            return simpleName.equalsIgnoreCase(((SqlIdentifier) item.expr()).simpleName());
        }
        return false;
    }

    private static boolean isPaginationFormCompatible(SqlSelect select, SqlDialectSpec d) {
        RowNumPage page = detectRowNumPage(select);
        if (page != null) {
            if (page.kind == RowNumPage.Kind.SS_ROW_NUMBER) {
                return d.supportsTop();
            }
            // 经典 ORACLE 才以 ROWNUM 为原生形态；ORACLE12 宜改写为 OFFSET/FETCH
            return d.supportsRownum() && !d.supportsFetchFirst();
        }
        SqlSelect owner = paginationOwner(select);
        if (owner.top() != null) {
            return d.supportsTop();
        }
        if (owner.limit() != null) {
            if (owner.limit().fetchStyle()) {
                // OFFSET/FETCH：经典 ORACLE 必须改成 ROWNUM；其余支持 FETCH/TOP 的可保留
                if (d.supportsRownum() && !d.supportsFetchFirst()) {
                    return false;
                }
                return d.supportsFetchFirst() || d.supportsTop();
            }
            // 普通 LIMIT：仅 LIMIT 族方言兼容；ORACLE/ORACLE12/SQLSERVER 等需改写
            if (d.supportsRownum() && !d.supportsFetchFirst()) {
                return false;
            }
            if (d.supportsFetchFirst() && !d.supportsLimitOffset()) {
                return false;
            }
            if (d.supportsTop() && !d.supportsLimitOffset()) {
                return false;
            }
            return d.supportsLimitOffset();
        }
        return true;
    }

    private static void stripPaginationForm(SqlSelect select) {
        RowNumPage page = detectRowNumPage(select);
        if (page != null) {
            unwrapRowNumPage(select, page);
            return;
        }
        clearPagination(paginationOwner(select));
    }

    private static void unwrapRowNumPage(SqlSelect outer, RowNumPage page) {
        SqlSelect core = null;
        if (page.kind == RowNumPage.Kind.ORACLE_NESTED) {
            SqlSelect middle = subquerySelect(outer);
            core = middle == null ? null : subquerySelect(middle);
        } else if (page.kind == RowNumPage.Kind.ORACLE_SIMPLE) {
            core = subquerySelect(outer);
        } else if (page.kind == RowNumPage.Kind.SS_ROW_NUMBER) {
            // SELECT * FROM (SELECT ..., row_number() AS RN FROM <src>) XX WHERE ...
            SqlSelect middle = subquerySelect(outer);
            if (middle != null && middle.from() instanceof SqlSubqueryTable) {
                SqlStatement q = ((SqlSubqueryTable) middle.from()).query();
                if (q instanceof SqlSelect) {
                    core = (SqlSelect) q;
                }
            }
            if (core == null && middle != null) {
                // from 是普通表：去掉 row_number 列后把 middle 当核心
                java.util.List<SqlSelectItem> cleaned =
                        new java.util.ArrayList<SqlSelectItem>(middle.selectItems().size());
                for (SqlSelectItem item : middle.selectItems()) {
                    if (item.expr() instanceof SqlFunctionExpr) {
                        SqlFunctionExpr fn = (SqlFunctionExpr) item.expr();
                        if (fn.name() != null
                                && "ROW_NUMBER".equalsIgnoreCase(fn.name().simpleName())) {
                            continue;
                        }
                    }
                    cleaned.add(item);
                }
                middle.selectItems().clear();
                middle.selectItems().addAll(cleaned);
                clearPagination(middle);
                middle.setWhere(null);
                adoptSelectBody(outer, middle);
                return;
            }
        }
        if (core == null) {
            clearPagination(paginationOwner(outer));
            return;
        }
        adoptSelectBody(outer, core);
    }

    /**
     * 把 core 的 SELECT 体装回 root（WITH/hint 留在 root），并清掉分页。
     */
    private static void adoptSelectBody(SqlSelect root, SqlSelect core) {
        root.setDistinct(core.distinct());
        root.distinctOn().clear();
        root.distinctOn().addAll(core.distinctOn());
        root.setTop(core.top());
        root.setTopWithTies(core.topWithTies());
        root.selectItems().clear();
        root.selectItems().addAll(core.selectItems());
        root.setFrom(core.from());
        root.setWhere(core.where());
        root.groupBy().clear();
        root.groupBy().addAll(core.groupBy());
        root.setGroupByRollup(core.groupByRollup());
        root.setHaving(core.having());
        root.orderBy().clear();
        root.orderBy().addAll(core.orderBy());
        root.setLimit(core.limit());
        root.setForUpdate(core.forUpdate());
        root.setLockInShare(core.lockInShare());
        root.setForUpdateTail(core.forUpdateTail());
        root.forUpdateOf().clear();
        root.forUpdateOf().addAll(core.forUpdateOf());
        root.setForUpdateWait(core.forUpdateWait());
        root.setUnion(core.union());
        root.setUnionOp(core.unionOp());
        root.setConnectBy(core.connectBy());
        root.setStartWith(core.startWith());
        root.windows().clear();
        root.windows().addAll(core.windows());
        root.setValuesClause(core.valuesClause());
        clearPagination(paginationOwner(root));
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
                        id.ensureMutableNames();
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
