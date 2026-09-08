package com.alianga.jkit.sql.ast;

/**
 * FROM 项：表、连接、子查询。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public abstract class SqlTableSource extends SqlNode {
    private String alias;

    /**
     * @return 别名
     */
    public String alias() {
        return alias;
    }

    /**
     * @param alias 别名
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }
}
