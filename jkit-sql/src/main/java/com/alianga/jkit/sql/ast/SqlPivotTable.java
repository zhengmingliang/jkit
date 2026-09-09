package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * Oracle / SQL Server {@code PIVOT}/{@code UNPIVOT} 表源包装。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlPivotTable extends SqlTableSource {
    private SqlTableSource input;
    private boolean unpivot;
    /** 括号内定义原文（不含外层括号） */
    private String definition;

    /**
     * @return 输入表源
     */
    public SqlTableSource input() {
        return input;
    }

    /**
     * @param input 输入表源
     */
    public void setInput(SqlTableSource input) {
        this.input = input;
    }

    /**
     * @return 是否 {@code UNPIVOT}
     */
    public boolean unpivot() {
        return unpivot;
    }

    /**
     * @param unpivot {@code UNPIVOT}
     */
    public void setUnpivot(boolean unpivot) {
        this.unpivot = unpivot;
    }

    /**
     * @return 括号内定义
     */
    public String definition() {
        return definition;
    }

    /**
     * @param definition 括号内定义
     */
    public void setDefinition(String definition) {
        this.definition = definition;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, input);
    }
}
