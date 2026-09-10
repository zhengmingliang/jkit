package com.alianga.jkit.sql.visitor;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBlockStatement;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlControlStatement;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDeclareStatement;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlHandlerStatement;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlInsertBranch;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlLockTablesStatement;
import com.alianga.jkit.sql.ast.SqlMatchRecognize;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlModelClause;
import com.alianga.jkit.sql.ast.SqlModelRule;
import com.alianga.jkit.sql.ast.SqlNamedExpr;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlOverExpr;
import com.alianga.jkit.sql.ast.SqlPivotTable;
import com.alianga.jkit.sql.ast.SqlPrepareStatement;
import com.alianga.jkit.sql.ast.SqlQueryExpr;
import com.alianga.jkit.sql.ast.SqlRoutineParam;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlSubset;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableHandlerStatement;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.ast.SqlWindowDefinition;
import com.alianga.jkit.sql.ast.SqlWithItem;

/**
 * 带具体 AST 类型分发的访问者（对标 Druid {@code visit(SQLSelect)}）。
 *
 * <p>实现 {@link SqlVisitor}，与已有 {@link SqlVisitorAdapter} 并存不破坏。子类覆写感兴趣的
 * {@code visitXxx} 即可；返回 false 跳过该节点子树。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlAstVisitor implements SqlVisitor {
    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean visit(SqlNode node) {
        if (node instanceof SqlSelect) {
            return visitSelect((SqlSelect) node);
        }
        if (node instanceof SqlInsert) {
            return visitInsert((SqlInsert) node);
        }
        if (node instanceof SqlUpdate) {
            return visitUpdate((SqlUpdate) node);
        }
        if (node instanceof SqlDelete) {
            return visitDelete((SqlDelete) node);
        }
        if (node instanceof SqlMerge) {
            return visitMerge((SqlMerge) node);
        }
        if (node instanceof SqlDdlStatement) {
            return visitDdl((SqlDdlStatement) node);
        }
        if (node instanceof SqlControlStatement) {
            return visitControl((SqlControlStatement) node);
        }
        if (node instanceof SqlDeclareStatement) {
            return visitDeclare((SqlDeclareStatement) node);
        }
        if (node instanceof SqlHandlerStatement) {
            return visitHandler((SqlHandlerStatement) node);
        }
        if (node instanceof SqlBlockStatement) {
            return visitBlock((SqlBlockStatement) node);
        }
        if (node instanceof SqlTableHandlerStatement) {
            return visitTableHandler((SqlTableHandlerStatement) node);
        }
        if (node instanceof SqlPrepareStatement) {
            return visitPrepare((SqlPrepareStatement) node);
        }
        if (node instanceof SqlLockTablesStatement) {
            return visitLockTables((SqlLockTablesStatement) node);
        }
        if (node instanceof SqlSimpleStatement) {
            return visitSimple((SqlSimpleStatement) node);
        }
        if (node instanceof SqlTable) {
            return visitTable((SqlTable) node);
        }
        if (node instanceof SqlJoin) {
            return visitJoin((SqlJoin) node);
        }
        if (node instanceof SqlPivotTable) {
            return visitPivotTable((SqlPivotTable) node);
        }
        if (node instanceof SqlSubqueryTable) {
            return visitSubqueryTable((SqlSubqueryTable) node);
        }
        if (node instanceof SqlFunctionTable) {
            return visitFunctionTable((SqlFunctionTable) node);
        }
        if (node instanceof SqlValuesTable) {
            return visitValuesTable((SqlValuesTable) node);
        }
        if (node instanceof SqlIdentifier) {
            return visitIdentifier((SqlIdentifier) node);
        }
        if (node instanceof SqlLiteral) {
            return visitLiteral((SqlLiteral) node);
        }
        if (node instanceof SqlBinaryExpr) {
            return visitBinaryExpr((SqlBinaryExpr) node);
        }
        if (node instanceof SqlUnaryExpr) {
            return visitUnaryExpr((SqlUnaryExpr) node);
        }
        if (node instanceof SqlFunctionExpr) {
            return visitFunctionExpr((SqlFunctionExpr) node);
        }
        if (node instanceof SqlCaseExpr) {
            return visitCaseExpr((SqlCaseExpr) node);
        }
        if (node instanceof SqlCastExpr) {
            return visitCastExpr((SqlCastExpr) node);
        }
        if (node instanceof SqlBetweenExpr) {
            return visitBetweenExpr((SqlBetweenExpr) node);
        }
        if (node instanceof SqlInExpr) {
            return visitInExpr((SqlInExpr) node);
        }
        if (node instanceof SqlListExpr) {
            return visitListExpr((SqlListExpr) node);
        }
        if (node instanceof SqlQueryExpr) {
            return visitQueryExpr((SqlQueryExpr) node);
        }
        if (node instanceof SqlAllColumns) {
            return visitAllColumns((SqlAllColumns) node);
        }
        if (node instanceof SqlOverExpr) {
            return visitOverExpr((SqlOverExpr) node);
        }
        if (node instanceof SqlSelectItem) {
            return visitSelectItem((SqlSelectItem) node);
        }
        if (node instanceof SqlOrderByItem) {
            return visitOrderByItem((SqlOrderByItem) node);
        }
        if (node instanceof SqlLimit) {
            return visitLimit((SqlLimit) node);
        }
        if (node instanceof SqlWithItem) {
            return visitWithItem((SqlWithItem) node);
        }
        if (node instanceof SqlWindowDefinition) {
            return visitWindowDefinition((SqlWindowDefinition) node);
        }
        if (node instanceof SqlModelClause) {
            return visitModelClause((SqlModelClause) node);
        }
        if (node instanceof SqlMatchRecognize) {
            return visitMatchRecognize((SqlMatchRecognize) node);
        }
        if (node instanceof SqlNamedExpr) {
            return visitNamedExpr((SqlNamedExpr) node);
        }
        if (node instanceof SqlModelRule) {
            return visitModelRule((SqlModelRule) node);
        }
        if (node instanceof SqlSubset) {
            return visitSubset((SqlSubset) node);
        }
        if (node instanceof SqlRoutineParam) {
            return visitRoutineParam((SqlRoutineParam) node);
        }
        if (node instanceof SqlMergeWhen) {
            return visitMergeWhen((SqlMergeWhen) node);
        }
        if (node instanceof SqlInsertBranch) {
            return visitInsertBranch((SqlInsertBranch) node);
        }
        return visitOther(node);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void endVisit(SqlNode node) {
    }

    /**
     * @param node 未单独分发的节点
     * @return 是否继续访问子节点
     */
    protected boolean visitOther(SqlNode node) {
        return true;
    }

    /** @param node SELECT @return 是否继续子节点 */
    protected boolean visitSelect(SqlSelect node) {
        return true;
    }

    /** @param node INSERT @return 是否继续子节点 */
    protected boolean visitInsert(SqlInsert node) {
        return true;
    }

    /** @param node UPDATE @return 是否继续子节点 */
    protected boolean visitUpdate(SqlUpdate node) {
        return true;
    }

    /** @param node DELETE @return 是否继续子节点 */
    protected boolean visitDelete(SqlDelete node) {
        return true;
    }

    /** @param node MERGE @return 是否继续子节点 */
    protected boolean visitMerge(SqlMerge node) {
        return true;
    }

    /** @param node DDL @return 是否继续子节点 */
    protected boolean visitDdl(SqlDdlStatement node) {
        return true;
    }

    /** @param node 简单语句 @return 是否继续子节点 */
    protected boolean visitSimple(SqlSimpleStatement node) {
        return true;
    }

    /** @param node 控制流 @return 是否继续子节点 */
    protected boolean visitControl(SqlControlStatement node) {
        return true;
    }

    /** @param node DECLARE @return 是否继续子节点 */
    protected boolean visitDeclare(SqlDeclareStatement node) {
        return true;
    }

    /** @param node HANDLER @return 是否继续子节点 */
    protected boolean visitHandler(SqlHandlerStatement node) {
        return true;
    }

    /** @param node 匿名块 @return 是否继续子节点 */
    protected boolean visitBlock(SqlBlockStatement node) {
        return true;
    }

    /** @param node 表 HANDLER @return 是否继续子节点 */
    protected boolean visitTableHandler(SqlTableHandlerStatement node) {
        return true;
    }

    /** @param node PREPARE/EXECUTE/DEALLOCATE @return 是否继续子节点 */
    protected boolean visitPrepare(SqlPrepareStatement node) {
        return true;
    }

    /** @param node LOCK/UNLOCK TABLES @return 是否继续子节点 */
    protected boolean visitLockTables(SqlLockTablesStatement node) {
        return true;
    }

    /** @param node 表 @return 是否继续子节点 */
    protected boolean visitTable(SqlTable node) {
        return true;
    }

    /** @param node JOIN @return 是否继续子节点 */
    protected boolean visitJoin(SqlJoin node) {
        return true;
    }

    /**
     * @param node PIVOT/UNPIVOT 表源
     * @return 是否继续子节点
     */
    protected boolean visitPivotTable(SqlPivotTable node) {
        return true;
    }

    /** @param node 子查询表 @return 是否继续子节点 */
    protected boolean visitSubqueryTable(SqlSubqueryTable node) {
        return true;
    }

    /** @param node 表函数 @return 是否继续子节点 */
    protected boolean visitFunctionTable(SqlFunctionTable node) {
        return true;
    }

    /** @param node VALUES 表 @return 是否继续子节点 */
    protected boolean visitValuesTable(SqlValuesTable node) {
        return true;
    }

    /** @param node 标识符 @return 是否继续子节点 */
    protected boolean visitIdentifier(SqlIdentifier node) {
        return true;
    }

    /** @param node 字面量 @return 是否继续子节点 */
    protected boolean visitLiteral(SqlLiteral node) {
        return true;
    }

    /** @param node 二元表达式 @return 是否继续子节点 */
    protected boolean visitBinaryExpr(SqlBinaryExpr node) {
        return true;
    }

    /** @param node 一元表达式 @return 是否继续子节点 */
    protected boolean visitUnaryExpr(SqlUnaryExpr node) {
        return true;
    }

    /** @param node 函数 @return 是否继续子节点 */
    protected boolean visitFunctionExpr(SqlFunctionExpr node) {
        return true;
    }

    /** @param node CASE @return 是否继续子节点 */
    protected boolean visitCaseExpr(SqlCaseExpr node) {
        return true;
    }

    /** @param node CAST @return 是否继续子节点 */
    protected boolean visitCastExpr(SqlCastExpr node) {
        return true;
    }

    /** @param node BETWEEN @return 是否继续子节点 */
    protected boolean visitBetweenExpr(SqlBetweenExpr node) {
        return true;
    }

    /** @param node IN @return 是否继续子节点 */
    protected boolean visitInExpr(SqlInExpr node) {
        return true;
    }

    /** @param node 列表表达式 @return 是否继续子节点 */
    protected boolean visitListExpr(SqlListExpr node) {
        return true;
    }

    /** @param node 标量子查询 @return 是否继续子节点 */
    protected boolean visitQueryExpr(SqlQueryExpr node) {
        return true;
    }

    /** @param node * / t.* @return 是否继续子节点 */
    protected boolean visitAllColumns(SqlAllColumns node) {
        return true;
    }

    /** @param node OVER @return 是否继续子节点 */
    protected boolean visitOverExpr(SqlOverExpr node) {
        return true;
    }

    /** @param node 选择项 @return 是否继续子节点 */
    protected boolean visitSelectItem(SqlSelectItem node) {
        return true;
    }

    /** @param node ORDER BY 项 @return 是否继续子节点 */
    protected boolean visitOrderByItem(SqlOrderByItem node) {
        return true;
    }

    /** @param node LIMIT @return 是否继续子节点 */
    protected boolean visitLimit(SqlLimit node) {
        return true;
    }

    /** @param node WITH 项 @return 是否继续子节点 */
    protected boolean visitWithItem(SqlWithItem node) {
        return true;
    }

    /** @param node WINDOW 定义 @return 是否继续子节点 */
    protected boolean visitWindowDefinition(SqlWindowDefinition node) {
        return true;
    }

    /** @param node MERGE WHEN @return 是否继续子节点 */
    protected boolean visitMergeWhen(SqlMergeWhen node) {
        return true;
    }

    /** @param node INSERT ALL 分支 @return 是否继续子节点 */
    protected boolean visitInsertBranch(SqlInsertBranch node) {
        return true;
    }

    /** @param node MODEL 子句 @return 是否继续子节点 */
    protected boolean visitModelClause(SqlModelClause node) {
        return true;
    }

    /** @param node MATCH_RECOGNIZE @return 是否继续子节点 */
    protected boolean visitMatchRecognize(SqlMatchRecognize node) {
        return true;
    }

    /** @param node 具名表达式对 @return 是否继续子节点 */
    protected boolean visitNamedExpr(SqlNamedExpr node) {
        return true;
    }

    /** @param node MODEL RULE 条目 @return 是否继续子节点 */
    protected boolean visitModelRule(SqlModelRule node) {
        return true;
    }

    /** @param node MATCH_RECOGNIZE SUBSET @return 是否继续子节点 */
    protected boolean visitSubset(SqlSubset node) {
        return true;
    }

    /** @param node 过程参数 @return 是否继续子节点 */
    protected boolean visitRoutineParam(SqlRoutineParam node) {
        return true;
    }
}
