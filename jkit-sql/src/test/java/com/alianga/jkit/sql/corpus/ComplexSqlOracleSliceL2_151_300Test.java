package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Source;

/**
 * Oracle complex-sql 151–300 的 L2 往返（语料为 12c+，方言 {@code ORACLE12}）。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlOracleSliceL2_151_300Test extends AbstractComplexSqlSliceL2Test {
    /**
     * {@inheritDoc}
     */
    @Override
    protected Source source() {
        return Source.ORACLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int fromId() {
        return 151;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int toId() {
        return 300;
    }
}
