package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Source;

/**
 * MySQL complex-sql 001–150 的 L2 往返。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlMysqlSliceL2Test extends AbstractComplexSqlSliceL2Test {
    /**
     * {@inheritDoc}
     */
    @Override
    protected Source source() {
        return Source.MYSQL;
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
