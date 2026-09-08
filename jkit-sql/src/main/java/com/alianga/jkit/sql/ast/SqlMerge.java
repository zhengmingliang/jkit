package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * MERGE INTO ... USING ... ON ... WHEN MATCHED / NOT MATCHED [BY SOURCE|TARGET]。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlMerge extends SqlStatement {
    private SqlTableSource into;
    private SqlTableSource using;
    private SqlExpr on;
    private final List<SqlMergeWhen> whens = new ArrayList<SqlMergeWhen>(2);
    private final List<SqlExpr> output = new ArrayList<SqlExpr>(2);
    private SqlTable outputInto;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.MERGE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return INTO
     */
    public SqlTableSource into() {
        return into;
    }

    /**
     * @param into INTO
     */
    public void setInto(SqlTableSource into) {
        this.into = into;
    }

    /**
     * @return USING
     */
    public SqlTableSource using() {
        return using;
    }

    /**
     * @param using USING
     */
    public void setUsing(SqlTableSource using) {
        this.using = using;
    }

    /**
     * @return ON
     */
    public SqlExpr on() {
        return on;
    }

    /**
     * @param on ON
     */
    public void setOn(SqlExpr on) {
        this.on = on;
    }

    /**
     * @return WHEN 子句列表
     */
    public List<SqlMergeWhen> whens() {
        return whens;
    }

    /**
     * @return SQL Server OUTPUT
     */
    public List<SqlExpr> output() {
        return output;
    }

    /**
     * 兼容：首个 MATCHED 的 UPDATE。
     *
     * @return UPDATE，可能为 null
     */
    public SqlUpdate update() {
        for (int i = 0; i < whens.size(); i++) {
            SqlMergeWhen w = whens.get(i);
            if (w.kind() == SqlMergeWhen.MatchKind.MATCHED && w.update() != null) {
                return w.update();
            }
        }
        return null;
    }

    /**
     * 兼容：首个 NOT MATCHED 的 INSERT。
     *
     * @return INSERT，可能为 null
     */
    public SqlInsert insert() {
        for (int i = 0; i < whens.size(); i++) {
            SqlMergeWhen w = whens.get(i);
            if (w.kind() != SqlMergeWhen.MatchKind.MATCHED
                    && w.kind() != SqlMergeWhen.MatchKind.NOT_MATCHED_BY_SOURCE
                    && w.insert() != null) {
                return w.insert();
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */

    /**
     * @return SQL Server {@code OUTPUT … INTO} 目标表/表变量（可 {@code @out} / {@code #tmp}）
     * @since 2.0.1
     */
    public SqlTable outputInto() {
        return outputInto;
    }

    /**
     * @param outputInto INTO 目标
     * @since 2.0.1
     */
    public void setOutputInto(SqlTable outputInto) {
        this.outputInto = outputInto;
    }

    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, into);
        child(visitor, using);
        child(visitor, on);
        children(visitor, whens);
        children(visitor, output);
        child(visitor, outputInto);
    }
}
