package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Source;

/**
 * SQL Server complex-sql 001–150 的 L2 往返。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlSqlServerSliceL2Test extends AbstractComplexSqlSliceL2Test {
    /**
     * {@inheritDoc}
     */
    @Override
    protected Source source() {
        return Source.SQLSERVER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int fromId() {
        return 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int toId() {
        return 150;
    }
}
