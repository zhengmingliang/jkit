package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * MERGE 的一条 {@code WHEN [NOT] MATCHED [BY SOURCE|TARGET] [AND pred] THEN …} 子句。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlMergeWhen extends SqlNode {
    /**
     * MATCHED / NOT MATCHED 形态。
     */
    public enum MatchKind {
        /** WHEN MATCHED */
        MATCHED,
        /** WHEN NOT MATCHED（等同 BY TARGET） */
        NOT_MATCHED,
        /** WHEN NOT MATCHED BY TARGET */
        NOT_MATCHED_BY_TARGET,
        /** WHEN NOT MATCHED BY SOURCE */
        NOT_MATCHED_BY_SOURCE
    }

    private MatchKind kind = MatchKind.MATCHED;
    private SqlExpr andPredicate;
    private SqlUpdate update;
    private SqlInsert insert;
    private boolean delete;

    /**
     * @return 匹配种类
     */
    public MatchKind kind() {
        return kind;
    }

    /**
     * @param kind 匹配种类
     */
    public void setKind(MatchKind kind) {
        this.kind = kind;
    }

    /**
     * @return AND 附加谓词
     */
    public SqlExpr andPredicate() {
        return andPredicate;
    }

    /**
     * @param andPredicate AND 谓词
     */
    public void setAndPredicate(SqlExpr andPredicate) {
        this.andPredicate = andPredicate;
    }

    /**
     * @return THEN UPDATE
     */
    public SqlUpdate update() {
        return update;
    }

    /**
     * @param update UPDATE
     */
    public void setUpdate(SqlUpdate update) {
        this.update = update;
    }

    /**
     * @return THEN INSERT
     */
    public SqlInsert insert() {
        return insert;
    }

    /**
     * @param insert INSERT
     */
    public void setInsert(SqlInsert insert) {
        this.insert = insert;
    }

    /**
     * @return THEN DELETE
     */
    public boolean delete() {
        return delete;
    }

    /**
     * @param delete DELETE
     */
    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, andPredicate);
        child(visitor, update);
        child(visitor, insert);
    }
}
