package com.alianga.jkit.sql.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * FROM 项：表、连接、子查询、表函数、VALUES。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public abstract class SqlTableSource extends SqlNode {
    private String alias;
    private final List<SqlIdentifier> columnAliases = new ArrayList<SqlIdentifier>(2);

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

    /**
     * @return 列别名清单，如 {@code AS v(id, name)} 中的 {@code id, name}
     * @since 2.1.0
     */
    public List<SqlIdentifier> columnAliases() {
        return columnAliases;
    }
}
