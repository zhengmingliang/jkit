package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SELECT，含 UNION 链。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSelect extends SqlStatement {
    private boolean distinct;
    private final List<SqlExpr> distinctOn = new ArrayList<SqlExpr>(2);
    private SqlExpr top;
    private boolean topWithTies;
    private final List<SqlSelectItem> selectItems = new ArrayList<SqlSelectItem>(4);
    private SqlTableSource from;
    private SqlExpr where;
    private final List<SqlExpr> groupBy = new ArrayList<SqlExpr>(2);
    private boolean groupByRollup;
    private SqlExpr having;
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private SqlLimit limit;
    private boolean forUpdate;
    private boolean lockInShare;
    private String forUpdateTail;
    private final List<SqlIdentifier> forUpdateOf = new ArrayList<SqlIdentifier>(2);
    private String forUpdateWait;
    private SqlSelect union;
    private String unionOp;
    private SqlExpr connectBy;
    private SqlExpr startWith;
    private final List<SqlWindowDefinition> windows = new ArrayList<SqlWindowDefinition>(2);
    private boolean valuesClause;
    private List<String> hints;
    /** SELECT … INTO 目标表（备份风格 / 临时表） */
    private SqlTable intoTable;
    /** INTO @var [, @var2…] */
    private final List<SqlExpr> intoVariables = new ArrayList<SqlExpr>(2);
    /** INTO OUTFILE / DUMPFILE 路径原文（含引号） */
    private String intoOutfile;
    /** {@code OUTFILE} 或 {@code DUMPFILE}，可空 */
    private String intoFileKind;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.SELECT;
    }

    /**
     * @return 优化器提示原文列表（含 slash-star-plus 包装），可能为空列表
     * @since 2.0.1
     */
    public List<String> hints() {
        if (hints == null) {
            return Collections.emptyList();
        }
        return hints;
    }

    /**
     * @param hint 优化器提示原文
     * @since 2.0.1
     */
    public void addHint(String hint) {
        if (hint == null || hint.isEmpty()) {
            return;
        }
        if (hints == null) {
            hints = new ArrayList<String>(2);
        }
        hints.add(hint);
    }

    /**
     * @return 是否为顶层 {@code VALUES (...), (...)} 行构造查询（非 {@code SELECT} 关键字）
     * @since 2.0.1
     */
    public boolean valuesClause() {
        return valuesClause;
    }

    /**
     * @param valuesClause {@code VALUES} 查询
     * @since 2.0.1
     */
    public void setValuesClause(boolean valuesClause) {
        this.valuesClause = valuesClause;
    }

    /**
     * @return DISTINCT
     */
    public boolean distinct() {
        return distinct;
    }

    /**
     * @param distinct DISTINCT
     */
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    /**
     * @return PostgreSQL {@code DISTINCT ON (...)}
     */
    public List<SqlExpr> distinctOn() {
        return distinctOn;
    }

    /**
     * @return SQL Server TOP 表达式
     */
    public SqlExpr top() {
        return top;
    }

    /**
     * @param top TOP
     */
    public void setTop(SqlExpr top) {
        this.top = top;
    }

    /**
     * @return SQL Server {@code TOP … WITH TIES}
     * @since 2.0.1
     */
    public boolean topWithTies() {
        return topWithTies;
    }

    /**
     * @param topWithTies {@code WITH TIES}
     * @since 2.0.1
     */
    public void setTopWithTies(boolean topWithTies) {
        this.topWithTies = topWithTies;
    }

    /**
     * @return SELECT 列表
     */
    public List<SqlSelectItem> selectItems() {
        return selectItems;
    }

    /**
     * @param item 追加
     */
    public void addSelectItem(SqlSelectItem item) {
        selectItems.add(item);
    }

    /**
     * @return FROM
     */
    public SqlTableSource from() {
        return from;
    }

    /**
     * @param from FROM
     */
    public void setFrom(SqlTableSource from) {
        this.from = from;
    }

    /**
     * @return WHERE
     */
    public SqlExpr where() {
        return where;
    }

    /**
     * @param where WHERE
     */
    public void setWhere(SqlExpr where) {
        this.where = where;
    }

    /**
     * @return GROUP BY
     */
    public List<SqlExpr> groupBy() {
        return groupBy;
    }

    /**
     * @return GROUP BY ROLLUP
     */
    public boolean groupByRollup() {
        return groupByRollup;
    }

    /**
     * @param groupByRollup ROLLUP
     */
    public void setGroupByRollup(boolean groupByRollup) {
        this.groupByRollup = groupByRollup;
    }

    /**
     * @return HAVING
     */
    public SqlExpr having() {
        return having;
    }

    /**
     * @param having HAVING
     */
    public void setHaving(SqlExpr having) {
        this.having = having;
    }

    /**
     * @return ORDER BY
     */
    public List<SqlOrderByItem> orderBy() {
        return orderBy;
    }

    /**
     * @return LIMIT
     */
    public SqlLimit limit() {
        return limit;
    }

    /**
     * @param limit LIMIT
     */
    public void setLimit(SqlLimit limit) {
        this.limit = limit;
    }

    /**
     * @return FOR UPDATE
     */
    public boolean forUpdate() {
        return forUpdate;
    }

    /**
     * @param forUpdate FOR UPDATE
     */
    public void setForUpdate(boolean forUpdate) {
        this.forUpdate = forUpdate;
    }

    /**
     * @return LOCK IN SHARE MODE
     */
    public boolean lockInShare() {
        return lockInShare;
    }

    /**
     * @param lockInShare LOCK IN SHARE MODE
     */
    public void setLockInShare(boolean lockInShare) {
        this.lockInShare = lockInShare;
    }

    /**
     * @return FOR UPDATE 未建模后缀（结构化 OF / wait 之外的残余）
     */
    public String forUpdateTail() {
        return forUpdateTail;
    }

    /**
     * @param forUpdateTail 后缀
     */
    public void setForUpdateTail(String forUpdateTail) {
        this.forUpdateTail = forUpdateTail;
    }

    /**
     * @return {@code FOR UPDATE OF} 列列表
     * @since 2.0.1
     */
    public List<SqlIdentifier> forUpdateOf() {
        return forUpdateOf;
    }

    /**
     * @return 锁等待策略：{@code NOWAIT} / {@code SKIP LOCKED}，可空
     * @since 2.0.1
     */
    public String forUpdateWait() {
        return forUpdateWait;
    }

    /**
     * @param forUpdateWait {@code NOWAIT} / {@code SKIP LOCKED}
     * @since 2.0.1
     */
    public void setForUpdateWait(String forUpdateWait) {
        this.forUpdateWait = forUpdateWait;
    }

    /**
     * @return UNION 右侧，可空
     */
    public SqlSelect union() {
        return union;
    }

    /**
     * @param union UNION 右侧
     */
    public void setUnion(SqlSelect union) {
        this.union = union;
    }

    /**
     * @return UNION / UNION ALL / INTERSECT / EXCEPT / MINUS
     */
    public String unionOp() {
        return unionOp;
    }

    /**
     * @param unionOp 集合运算
     */
    public void setUnionOp(String unionOp) {
        this.unionOp = unionOp;
    }

    /**
     * @return Oracle CONNECT BY
     */
    public SqlExpr connectBy() {
        return connectBy;
    }

    /**
     * @param connectBy CONNECT BY
     */
    public void setConnectBy(SqlExpr connectBy) {
        this.connectBy = connectBy;
    }

    /**
     * @return START WITH
     */
    public SqlExpr startWith() {
        return startWith;
    }

    /**
     * @param startWith START WITH
     */
    public void setStartWith(SqlExpr startWith) {
        this.startWith = startWith;
    }

    /**
     * @return SELECT 级 {@code WINDOW} 定义列表
     * @since 2.0.1
     */
    public List<SqlWindowDefinition> windows() {
        return windows;
    }

    /**
     * @return {@code SELECT … INTO tbl} 目标表，可空
     * @since 2.0.1
     */
    public SqlTable intoTable() {
        return intoTable;
    }

    /**
     * @param intoTable INTO 目标表
     * @since 2.0.1
     */
    public void setIntoTable(SqlTable intoTable) {
        this.intoTable = intoTable;
    }

    /**
     * @return {@code INTO @var} 变量列表
     * @since 2.0.1
     */
    public List<SqlExpr> intoVariables() {
        return intoVariables;
    }

    /**
     * @return {@code INTO OUTFILE/DUMPFILE} 路径原文，可空
     * @since 2.0.1
     */
    public String intoOutfile() {
        return intoOutfile;
    }

    /**
     * @param intoOutfile 文件路径原文
     * @since 2.0.1
     */
    public void setIntoOutfile(String intoOutfile) {
        this.intoOutfile = intoOutfile;
    }

    /**
     * @return {@code OUTFILE} 或 {@code DUMPFILE}
     * @since 2.0.1
     */
    public String intoFileKind() {
        return intoFileKind;
    }

    /**
     * @param intoFileKind OUTFILE / DUMPFILE
     * @since 2.0.1
     */
    public void setIntoFileKind(String intoFileKind) {
        this.intoFileKind = intoFileKind;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        children(visitor, distinctOn);
        child(visitor, top);
        children(visitor, selectItems);
        children(visitor, intoVariables);
        child(visitor, from);
        child(visitor, where);
        children(visitor, groupBy);
        child(visitor, having);
        children(visitor, orderBy);
        child(visitor, limit);
        child(visitor, connectBy);
        child(visitor, startWith);
        children(visitor, windows);
        child(visitor, union);
    }
}
