package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlBlockStatement;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlCommentOnStatement;
import com.alianga.jkit.sql.ast.SqlControlStatement;
import com.alianga.jkit.sql.ast.SqlCopyStatement;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDeclareStatement;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExplainStatement;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFlushStatement;
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
import com.alianga.jkit.sql.ast.SqlLoadDataStatement;
import com.alianga.jkit.sql.ast.SqlLockTablesStatement;
import com.alianga.jkit.sql.ast.SqlMaintenanceStatement;
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
import com.alianga.jkit.sql.ast.SqlSetStatement;
import com.alianga.jkit.sql.ast.SqlShowStatement;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStartTransactionStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlSubset;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableHandlerStatement;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlTransactionControlStatement;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.ast.SqlWindowDefinition;
import com.alianga.jkit.sql.ast.SqlWithItem;

import java.util.List;
import java.util.Locale;

/**
 * 把 AST 打回 SQL。pretty 模式换行缩进；compact 模式只保留必要空格。
 * 标识符引号按 {@link SqlDialect#identQuoteOpen()} / {@link SqlDialect#identQuoteClose()} 输出；
 * 默认仅当 {@link SqlIdentifier#quoted()} 为 true 时加引号；
 * {@link SqlFormatOptions#quoteIdentifiers(boolean)} 为 true 时强制给每个标识符段加方言引号。
 * {@code ||} 按 AST 运算符回写（{@link SqlBinaryOp#CONCAT}→{@code ||}，{@link SqlBinaryOp#OR}→{@code OR}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFormatter {
    private final StringBuilder out = new StringBuilder(256);
    private boolean pretty;
    private SqlDialectSpec dialect;
    private SqlFormatOptions options;
    private SqlKeywordCase keywordCase;
    private int indent;

    /**
     * @param pretty 是否换行缩进
     * @param dialect 方言（影响 LIMIT / 引号）
     */
    public SqlFormatter(boolean pretty, SqlDialectSpec dialect) {
        this(pretty, dialect, null);
    }

    /**
     * @param pretty 是否换行缩进
     * @param dialect 方言（影响 LIMIT / 引号）
     * @param options 格式化选项；null 视为 {@link SqlFormatOptions#defaults()}
     * @since 2.0.1
     */
    public SqlFormatter(boolean pretty, SqlDialectSpec dialect, SqlFormatOptions options) {
        configure(pretty, dialect, options);
    }

    /**
     * 复用实例时重配 pretty/方言/选项（配合 {@link SQL} 线程本地 Formatter）。
     */
    void configure(boolean pretty, SqlDialectSpec dialect, SqlFormatOptions options) {
        this.pretty = pretty;
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        this.options = options == null ? SqlFormatOptions.DEFAULTS : options;
        this.keywordCase = this.options.keywordCase();
    }

    /**
     * @param node 节点
     * @return SQL 文本
     */
    public String format(SqlNode node) {
        out.setLength(0);
        indent = 0;
        writeNode(node);
        return out.toString();
    }

    /**
     * 重配后格式化（热路径：避免每次 new Formatter + StringBuilder）。
     */
    String format(SqlNode node, boolean pretty, SqlDialectSpec dialect, SqlFormatOptions options) {
        configure(pretty, dialect, options);
        return format(node);
    }

    /**
     * offset=0 经典 ORACLE ROWNUM 包装：{@code SELECT * FROM (}{@code node}{@code ) XX WHERE ROWNUM <= end}。
     * 调用方须已临时清掉 node 上的 LIMIT/TOP；不改 AST 结构。
     */
    String formatOracleRownumOffset0Wrap(SqlNode node, long end, boolean pretty,
            SqlDialectSpec dialect, SqlFormatOptions options) {
        configure(pretty, dialect, options);
        out.setLength(0);
        indent = 0;
        kw("SELECT");
        sp();
        out.append('*');
        nl();
        kw("FROM");
        sp();
        out.append('(');
        writeNode(node);
        out.append(')');
        sp();
        out.append("XX");
        nl();
        kw("WHERE");
        sp();
        out.append("ROWNUM");
        sp();
        out.append("<=");
        sp();
        out.append(Long.toString(end));
        return out.toString();
    }

    private void writeNode(SqlNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlStatement) {
            writeLeadingComments((SqlStatement) node);
        }
        if (node instanceof SqlSelect) {
            writeSelect((SqlSelect) node);
        } else if (node instanceof SqlInsert) {
            writeInsert((SqlInsert) node);
        } else if (node instanceof SqlUpdate) {
            writeUpdate((SqlUpdate) node);
        } else if (node instanceof SqlDelete) {
            writeDelete((SqlDelete) node);
        } else if (node instanceof SqlMerge) {
            writeMerge((SqlMerge) node);
        } else if (node instanceof SqlDdlStatement) {
            writeDdl((SqlDdlStatement) node);
        } else if (node instanceof SqlControlStatement) {
            writeControl((SqlControlStatement) node);
        } else if (node instanceof SqlDeclareStatement) {
            writeDeclare((SqlDeclareStatement) node);
        } else if (node instanceof SqlHandlerStatement) {
            writeHandler((SqlHandlerStatement) node);
        } else if (node instanceof SqlBlockStatement) {
            writeBlock((SqlBlockStatement) node);
        } else if (node instanceof SqlTableHandlerStatement) {
            writeTableHandler((SqlTableHandlerStatement) node);
        } else if (node instanceof SqlPrepareStatement) {
            writePrepare((SqlPrepareStatement) node);
        } else if (node instanceof SqlLockTablesStatement) {
            writeLockTables((SqlLockTablesStatement) node);
        } else if (node instanceof SqlCopyStatement) {
            writeCopy((SqlCopyStatement) node);
        } else if (node instanceof SqlFlushStatement) {
            writeFlush((SqlFlushStatement) node);
        } else if (node instanceof SqlMaintenanceStatement) {
            writeMaintenance((SqlMaintenanceStatement) node);
        } else if (node instanceof SqlTransactionControlStatement) {
            writeTransactionControl((SqlTransactionControlStatement) node);
        } else if (node instanceof SqlStartTransactionStatement) {
            writeStartTransaction((SqlStartTransactionStatement) node);
        } else if (node instanceof SqlLoadDataStatement) {
            writeLoadData((SqlLoadDataStatement) node);
        } else if (node instanceof SqlCommentOnStatement) {
            writeCommentOn((SqlCommentOnStatement) node);
        } else if (node instanceof SqlSetStatement) {
            writeSet((SqlSetStatement) node);
        } else if (node instanceof SqlExplainStatement) {
            writeExplain((SqlExplainStatement) node);
        } else if (node instanceof SqlShowStatement) {
            writeShow((SqlShowStatement) node);
        } else if (node instanceof SqlSimpleStatement) {
            writeSimple((SqlSimpleStatement) node);
        } else if (node instanceof SqlExpr) {
            writeExpr((SqlExpr) node);
        } else if (node instanceof SqlTableSource) {
            writeFrom((SqlTableSource) node);
        } else if (node instanceof SqlLimit) {
            writeLimit((SqlLimit) node);
        } else if (node instanceof SqlSelectItem) {
            writeSelectItem((SqlSelectItem) node);
        } else if (node instanceof SqlOrderByItem) {
            writeOrderByItem((SqlOrderByItem) node);
        } else if (node instanceof SqlWithItem) {
            writeWithItem((SqlWithItem) node);
        } else if (node instanceof SqlWindowDefinition) {
            writeWindowDefinition((SqlWindowDefinition) node);
        } else if (node instanceof SqlModelClause) {
            writeModelClause((SqlModelClause) node);
        } else if (node instanceof SqlMatchRecognize) {
            writeMatchRecognizeBody((SqlMatchRecognize) node);
        } else if (node instanceof SqlNamedExpr) {
            writeNamedExpr((SqlNamedExpr) node);
        } else if (node instanceof SqlModelRule) {
            writeModelRule((SqlModelRule) node);
        } else if (node instanceof SqlSubset) {
            writeSubset((SqlSubset) node);
        } else if (node instanceof SqlMergeWhen) {
            writeMergeWhen((SqlMergeWhen) node);
        } else if (node instanceof SqlInsertBranch) {
            writeInsertBranch((SqlInsertBranch) node);
        } else {
            out.append(node.getClass().getSimpleName());
        }
    }

    private void writeLeadingComments(SqlStatement stmt) {
        if (stmt == null || stmt.comments().isEmpty()) {
            return;
        }
        for (int i = 0; i < stmt.comments().size(); i++) {
            out.append(stmt.comments().get(i));
            nl();
        }
    }

    private void writeWith(SqlStatement stmt) {
        List<SqlWithItem> items = stmt.withItems();
        if (items.isEmpty()) {
            return;
        }
        kw("WITH");
        if (stmt.withRecursive()) {
            sp();
            kw("RECURSIVE");
        }
        sp();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            writeWithItem(items.get(i));
        }
        nl();
    }

    private void writeWithItem(SqlWithItem item) {
        writeExpr(item.name());
        if (item.columns() != null && !item.columns().isEmpty()) {
            out.append('(');
            commaIdents(item.columns());
            out.append(')');
        }
        sp();
        kw("AS");
        sp();
        out.append('(');
        writeNode(item.query());
        out.append(')');
        if (item.searchClause() != null && item.searchClause().length() > 0) {
            sp();
            out.append(item.searchClause());
        }
        if (item.cycleClause() != null && item.cycleClause().length() > 0) {
            sp();
            out.append(item.cycleClause());
        }
    }

    private void writeSelect(SqlSelect select) {
        writeWith(select);
        if (select.valuesClause()) {
            writeValuesClause(select);
            if (select.union() != null) {
                nl();
                kw(select.unionOp() == null ? "UNION" : select.unionOp());
                nl();
                writeSelect(select.union());
            }
            return;
        }
        kw("SELECT");
        if (!select.hints().isEmpty()) {
            for (int hi = 0; hi < select.hints().size(); hi++) {
                sp();
                out.append(select.hints().get(hi));
            }
        }
        if (select.distinct()) {
            sp();
            kw(select.distinctRow() ? "DISTINCTROW" : "DISTINCT");
            if (!select.distinctOn().isEmpty()) {
                sp();
                kw("ON");
                sp();
                out.append('(');
                commaExprs(select.distinctOn());
                out.append(')');
            }
        }
        if (select.highPriority()) {
            sp();
            kw("HIGH_PRIORITY");
        }
        if (select.straightJoin()) {
            sp();
            kw("STRAIGHT_JOIN");
        }
        if (select.smallResult()) {
            sp();
            kw("SQL_SMALL_RESULT");
        }
        if (select.bigResult()) {
            sp();
            kw("SQL_BIG_RESULT");
        }
        if (select.bufferResult()) {
            sp();
            kw("SQL_BUFFER_RESULT");
        }
        if (select.cache()) {
            sp();
            kw("SQL_CACHE");
        }
        if (select.noCache()) {
            sp();
            kw("SQL_NO_CACHE");
        }
        if (select.calcFoundRows()) {
            sp();
            kw("SQL_CALC_FOUND_ROWS");
        }
        if (select.top() != null) {
            sp();
            kw("TOP");
            sp();
            writeExpr(select.top());
            if (select.topWithTies()) {
                sp();
                kw("WITH");
                sp();
                kw("TIES");
            }
        }
        sp();
        List<SqlSelectItem> items = select.selectItems();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            writeSelectItem(items.get(i));
        }
        if (select.forcePartition() != null && select.forcePartition().length() > 0) {
            sp();
            out.append(select.forcePartition());
        }
        writeSelectInto(select);
        if (select.from() != null) {
            nl();
            kw("FROM");
            sp();
            writeFrom(select.from());
        } else if (dialect.typeFamily() == SqlDialect.ORACLE
                || dialect.typeFamily() == SqlDialect.ORACLE12
                || dialect.typeFamily() == SqlDialect.DAMENG) {
            // Oracle / 达梦 不允许裸 SELECT <expr>，必须补 FROM dual
            nl();
            kw("FROM");
            sp();
            kw("dual");
        }
        if (select.modelClause() != null) {
            nl();
            writeModelClause(select.modelClause());
        }
        if (select.where() != null) {
            nl();
            kw("WHERE");
            sp();
            writeExpr(select.where());
        }
        if (select.startWith() != null) {
            nl();
            kw("START");
            sp();
            kw("WITH");
            sp();
            writeExpr(select.startWith());
        }
        if (select.connectBy() != null) {
            nl();
            kw("CONNECT");
            sp();
            kw("BY");
            if (select.connectByNocycle()) {
                sp();
                kw("NOCYCLE");
            }
            sp();
            writeExpr(select.connectBy());
        }
        if (select.groupByExtension() != null) {
            nl();
            kw("GROUP");
            sp();
            kw("BY");
            if (select.groupByDistinct()) {
                sp();
                kw("DISTINCT");
            }
            sp();
            if (!select.groupBy().isEmpty()) {
                commaExprs(select.groupBy());
                sp();
            }
            out.append(select.groupByExtension());
        } else if (!select.groupBy().isEmpty()) {
            nl();
            kw("GROUP");
            sp();
            kw("BY");
            if (select.groupByDistinct()) {
                sp();
                kw("DISTINCT");
            }
            sp();
            commaExprs(select.groupBy());
            if (select.groupByRollup()) {
                sp();
                kw("WITH");
                sp();
                kw("ROLLUP");
            }
            if (select.groupByCube()) {
                sp();
                kw("WITH");
                sp();
                kw("CUBE");
            }
        }
        if (select.having() != null) {
            nl();
            kw("HAVING");
            sp();
            writeExpr(select.having());
        }
        if (select.qualify() != null) {
            nl();
            kw("QUALIFY");
            sp();
            writeExpr(select.qualify());
        }
        if (!select.windows().isEmpty()) {
            nl();
            kw("WINDOW");
            sp();
            List<SqlWindowDefinition> windows = select.windows();
            for (int i = 0; i < windows.size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeWindowDefinition(windows.get(i));
            }
        }
        if (!select.orderBy().isEmpty()) {
            nl();
            kw("ORDER");
            if (select.orderSiblings()) {
                sp();
                kw("SIBLINGS");
            }
            sp();
            kw("BY");
            sp();
            writeOrder(select.orderBy());
        }
        if (select.distributeBy() != null) {
            nl();
            kw("DISTRIBUTE");
            sp();
            kw("BY");
            sp();
            out.append(select.distributeBy());
        }
        if (select.clusterBy() != null) {
            nl();
            kw("CLUSTER");
            sp();
            kw("BY");
            sp();
            out.append(select.clusterBy());
        }
        if (select.sortBy() != null) {
            nl();
            kw("SORT");
            sp();
            kw("BY");
            sp();
            out.append(select.sortBy());
        }
        if (select.limit() != null) {
            nl();
            writeLimit(select.limit());
        }
        if (select.forUpdate()) {
            sp();
            kw("FOR");
            sp();
            kw("UPDATE");
            if (!select.forUpdateOf().isEmpty()) {
                sp();
                kw("OF");
                sp();
                commaIdents(select.forUpdateOf());
            }
            if (select.forUpdateWait() != null) {
                sp();
                out.append(select.forUpdateWait());
            }
            if (select.forUpdateTail() != null) {
                sp();
                out.append(select.forUpdateTail());
            }
        }
        if (select.lockInShare()) {
            sp();
            kw("LOCK");
            sp();
            kw("IN");
            sp();
            kw("SHARE");
            sp();
            kw("MODE");
        }
        if (select.queryOption() != null && select.queryOption().length() > 0) {
            sp();
            kw("OPTION");
            sp();
            out.append(select.queryOption());
        }
        if (select.union() != null) {
            nl();
            kw(select.unionOp() == null ? "UNION" : select.unionOp());
            nl();
            writeSelect(select.union());
        }
    }

    private void writeValuesClause(SqlSelect select) {
        kw("VALUES");
        sp();
        if (select.selectItems().isEmpty()) {
            return;
        }
        SqlExpr expr = select.selectItems().get(0).expr();
        if (expr instanceof SqlFunctionExpr
                && equalsIgnoreCase(((SqlFunctionExpr) expr).name().simpleName(), "VALUES")) {
            List<SqlExpr> rows = ((SqlFunctionExpr) expr).arguments();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeValuesRow(rows.get(i));
            }
            return;
        }
        writeExpr(expr);
    }

    private void writeValuesRow(SqlExpr row) {
        if (row instanceof SqlListExpr) {
            writeExpr(row);
            return;
        }
        out.append('(');
        writeExpr(row);
        out.append(')');
    }

    private void writeInsert(SqlInsert insert) {
        writeWith(insert);
        kw(insert.replace() ? "REPLACE" : "INSERT");
        if (insert.delayed()) {
            sp();
            kw("DELAYED");
        } else if (insert.lowPriority()) {
            sp();
            kw("LOW_PRIORITY");
        } else if (insert.highPriority()) {
            sp();
            kw("HIGH_PRIORITY");
        }
        if (insert.ignore()) {
            sp();
            kw("IGNORE");
        }
        if (insert.insertAll() || insert.insertFirst()) {
            sp();
            kw(insert.insertFirst() ? "FIRST" : "ALL");
            for (int i = 0; i < insert.branches().size(); i++) {
                sp();
                writeInsertBranch(insert.branches().get(i));
            }
            if (insert.query() != null) {
                sp();
                writeNode(insert.query());
            }
            return;
        }
        if (insert.overwrite()) {
            sp();
            kw("OVERWRITE");
        }
        if (insert.table() != null) {
            sp();
            if (!insert.overwrite()) {
                kw("INTO");
            } else if (insert.tableKeyword()) {
                kw("TABLE");
            }
            sp();
            writeFrom(insert.table());
        }
        if (insert.partitionRaw() != null) {
            sp();
            kw("PARTITION");
            sp();
            out.append(insert.partitionRaw());
        }
        if (!insert.columns().isEmpty()) {
            out.append('(');
            commaIdents(insert.columns());
            out.append(')');
        }
        writeOutput(insert.output(), insert.outputInto());
        if (!insert.setList().isEmpty()) {
            sp();
            kw("SET");
            sp();
            commaBinaries(insert.setList());
        } else if (insert.query() != null) {
            sp();
            writeNode(insert.query());
        } else if (!insert.valuesList().isEmpty()) {
            sp();
            kw("VALUES");
            sp();
            for (int i = 0; i < insert.valuesList().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                out.append('(');
                commaExprs(insert.valuesList().get(i));
                out.append(')');
            }
        }
        if (insert.onConflict()) {
            sp();
            kw("ON");
            sp();
            kw("CONFLICT");
            if (insert.conflictConstraint() != null) {
                sp();
                kw("ON");
                sp();
                kw("CONSTRAINT");
                sp();
                writeExpr(insert.conflictConstraint());
            } else if (!insert.conflictTarget().isEmpty()) {
                sp();
                out.append('(');
                commaIdents(insert.conflictTarget());
                out.append(')');
            }
            sp();
            kw("DO");
            sp();
            if (insert.conflictDoNothing()) {
                kw("NOTHING");
            } else {
                kw("UPDATE");
                sp();
                kw("SET");
                sp();
                commaBinaries(insert.duplicateUpdates());
            }
        } else if (!insert.duplicateUpdates().isEmpty()) {
            sp();
            kw("ON");
            sp();
            kw("DUPLICATE");
            sp();
            kw("KEY");
            sp();
            kw("UPDATE");
            sp();
            commaBinaries(insert.duplicateUpdates());
        }
        if (insert.returning() != null) {
            sp();
            kw("RETURNING");
            sp();
            writeReturning(insert.returning());
        }
    }

    private void writeInsertBranch(SqlInsertBranch branch) {
        if (branch.elseBranch()) {
            kw("ELSE");
            sp();
        } else if (branch.when() != null) {
            kw("WHEN");
            sp();
            writeExpr(branch.when());
            sp();
            kw("THEN");
            sp();
        }
        kw("INTO");
        sp();
        writeFrom(branch.table());
        if (!branch.columns().isEmpty()) {
            out.append('(');
            commaIdents(branch.columns());
            out.append(')');
        }
        sp();
        kw("VALUES");
        sp();
        out.append('(');
        commaExprs(branch.values());
        out.append(')');
    }

    private void writeOutput(List<SqlExpr> output, SqlTable outputInto) {
        if (output == null || output.isEmpty()) {
            return;
        }
        sp();
        kw("OUTPUT");
        sp();
        commaExprs(output);
        if (outputInto != null) {
            sp();
            kw("INTO");
            sp();
            writeFrom(outputInto);
        }
    }

    private void writeUpdate(SqlUpdate update) {
        writeWith(update);
        kw("UPDATE");
        if (update.lowPriority()) {
            sp();
            kw("LOW_PRIORITY");
        }
        if (update.ignore()) {
            sp();
            kw("IGNORE");
        }
        if (update.forcePartition() != null && update.forcePartition().length() > 0) {
            sp();
            out.append(update.forcePartition());
        }
        if (update.table() != null) {
            sp();
            writeFrom(update.table());
        }
        sp();
        kw("SET");
        sp();
        commaBinaries(update.setList());
        writeOutput(update.output(), update.outputInto());
        if (update.from() != null) {
            sp();
            kw("FROM");
            sp();
            writeFrom(update.from());
        }
        if (update.where() != null) {
            nl();
            kw("WHERE");
            sp();
            writeExpr(update.where());
        }
        if (!update.orderBy().isEmpty()) {
            nl();
            kw("ORDER");
            sp();
            kw("BY");
            sp();
            writeOrder(update.orderBy());
        }
        if (update.limit() != null) {
            nl();
            writeLimit(update.limit());
        }
        if (update.returning() != null) {
            sp();
            kw("RETURNING");
            sp();
            writeReturning(update.returning());
        }
    }

    private void writeDelete(SqlDelete delete) {
        writeWith(delete);
        kw("DELETE");
        if (delete.lowPriority()) {
            sp();
            kw("LOW_PRIORITY");
        }
        if (delete.quick()) {
            sp();
            kw("QUICK");
        }
        if (delete.ignore()) {
            sp();
            kw("IGNORE");
        }
        if (delete.forcePartition() != null && delete.forcePartition().length() > 0) {
            sp();
            out.append(delete.forcePartition());
        }
        if (!delete.targets().isEmpty()) {
            // MySQL 多表删除：DELETE FROM a1, a2 USING t1 a1 JOIN t2 a2
            sp();
            kw("FROM");
            sp();
            commaIdents(delete.targets());
            sp();
            kw("USING");
            sp();
            writeFrom(delete.from());
        } else if (delete.table() != null) {
            // PG DELETE FROM t USING …；MySQL DELETE t FROM …
            if (delete.from() == null || delete.usingKeyword()) {
                sp();
                kw("FROM");
                sp();
            } else {
                sp();
            }
            writeFrom(delete.table());
        }
        if (delete.from() != null && delete.targets().isEmpty()) {
            sp();
            kw(delete.usingKeyword() ? "USING" : "FROM");
            sp();
            writeFrom(delete.from());
        }
        writeOutput(delete.output(), delete.outputInto());
        if (delete.where() != null) {
            nl();
            kw("WHERE");
            sp();
            writeExpr(delete.where());
        }
        if (delete.limit() != null) {
            nl();
            writeLimit(delete.limit());
        }
        if (delete.returning() != null) {
            sp();
            kw("RETURNING");
            sp();
            writeReturning(delete.returning());
        }
    }

    private void writeMerge(SqlMerge merge) {
        kw("MERGE");
        sp();
        kw("INTO");
        sp();
        writeFrom(merge.into());
        sp();
        kw("USING");
        sp();
        writeFrom(merge.using());
        sp();
        kw("ON");
        sp();
        writeExpr(merge.on());
        for (int i = 0; i < merge.whens().size(); i++) {
            sp();
            writeMergeWhen(merge.whens().get(i));
        }
        writeOutput(merge.output(), merge.outputInto());
    }

    private void writeMergeWhen(SqlMergeWhen when) {
        kw("WHEN");
        sp();
        if (when.kind() == SqlMergeWhen.MatchKind.MATCHED) {
            kw("MATCHED");
        } else if (when.kind() == SqlMergeWhen.MatchKind.NOT_MATCHED_BY_SOURCE) {
            kw("NOT");
            sp();
            kw("MATCHED");
            sp();
            kw("BY");
            sp();
            kw("SOURCE");
        } else if (when.kind() == SqlMergeWhen.MatchKind.NOT_MATCHED_BY_TARGET) {
            kw("NOT");
            sp();
            kw("MATCHED");
            sp();
            kw("BY");
            sp();
            kw("TARGET");
        } else {
            kw("NOT");
            sp();
            kw("MATCHED");
        }
        if (when.andPredicate() != null) {
            sp();
            kw("AND");
            sp();
            writeExpr(when.andPredicate());
        }
        sp();
        kw("THEN");
        sp();
        if (when.update() != null) {
            writeUpdate(when.update());
            if (when.delete()) {
                sp();
                kw("DELETE");
                if (when.deleteWhere() != null) {
                    sp();
                    kw("WHERE");
                    sp();
                    writeExpr(when.deleteWhere());
                }
            }
        } else if (when.insert() != null) {
            writeInsert(when.insert());
            if (when.insertWhere() != null) {
                sp();
                kw("WHERE");
                sp();
                writeExpr(when.insertWhere());
            }
        } else if (when.delete()) {
            kw("DELETE");
            if (when.deleteWhere() != null) {
                sp();
                kw("WHERE");
                sp();
                writeExpr(when.deleteWhere());
            }
        }
    }

    private void writeDdl(SqlDdlStatement ddl) {
        kw(ddl.type().name());
        if (ddl.orReplace()) {
            sp();
            kw("OR");
            sp();
            kw("REPLACE");
        }
        if (ddl.objectType() != null) {
            sp();
            out.append(ddl.objectType());
        }
        if (ddl.ifNotExists()) {
            sp();
            kw("IF");
            sp();
            kw("NOT");
            sp();
            kw("EXISTS");
        }
        if (ddl.ifExists()) {
            sp();
            kw("IF");
            sp();
            kw("EXISTS");
        }
        if (ddl.userSpec() != null) {
            // CREATE USER 'u'@'%'：账号原文输出，保留引号与 @ 结构
            sp();
            out.append(ddl.userSpec());
        }
        if (!ddl.names().isEmpty()) {
            sp();
            if (isIndexDdl(ddl) && ddl.names().size() >= 2) {
                writeExpr(ddl.names().get(0));
                sp();
                kw("ON");
                sp();
                writeExpr(ddl.names().get(1));
                for (int i = 2; i < ddl.names().size(); i++) {
                    out.append(',');
                    sp();
                    writeExpr(ddl.names().get(i));
                }
            } else {
                commaIdents(ddl.names());
            }
        }
        if (isIndexDdl(ddl) && !ddl.columns().isEmpty()) {
            sp();
            out.append('(');
            commaIdents(ddl.columns());
            out.append(')');
        }
        if (ddl.type() == SqlStatementType.CREATE && isTableDdl(ddl) && ddl.query() == null
                && (!ddl.columnDefinitions().isEmpty() || !ddl.columns().isEmpty())) {
            writeCreateTableColumns(ddl);
        }
        if (ddl.type() == SqlStatementType.CREATE && ddl.likeTable() != null) {
            // CREATE TABLE t2 LIKE t1
            sp();
            kw("LIKE");
            sp();
            writeExpr(ddl.likeTable());
        }
        if (ddl.type() == SqlStatementType.ALTER || ddl.type() == SqlStatementType.RENAME) {
            writeAlterClauses(ddl);
        }
        if (ddl.engine() != null) {
            sp();
            kw("ENGINE");
            out.append('=');
            out.append(ddl.engine());
        }
        if (ddl.charset() != null) {
            sp();
            kw("DEFAULT");
            sp();
            kw("CHARSET");
            out.append('=');
            out.append(ddl.charset());
        }
        if (ddl.collate() != null) {
            sp();
            kw("COLLATE");
            out.append('=');
            out.append(ddl.collate());
        }
        if (ddl.comment() != null) {
            sp();
            kw("COMMENT");
            out.append('=');
            out.append(ddl.comment());
        }
        if (ddl.query() != null) {
            sp();
            kw("AS");
            sp();
            writeNode(ddl.query());
            if (ddl.tail() != null) {
                // CTAS 尾缀（WITH [NO] DATA 等）
                sp();
                out.append(ddl.tail());
            }
        } else if (ddl.triggerTiming() != null || ddl.triggerEvent() != null
                || ddl.triggerTable() != null || ddl.eventScheduleKind() != null
                || ddl.triggerForEach() != null || ddl.triggerOrder() != null
                || !ddl.triggerUpdateColumns().isEmpty()
                || ddl.eventStarts() != null || ddl.eventEnds() != null
                || ddl.eventEnabled() != null || ddl.eventComment() != null
                || ddl.eventOnCompletion() != null || ddl.eventDisableOnSlave()
                || (!ddl.bodyStatements().isEmpty() && "TRIGGER".equalsIgnoreCase(ddl.objectType()))
                || (!ddl.bodyStatements().isEmpty() && "EVENT".equalsIgnoreCase(ddl.objectType()))) {
            writeTriggerOrEvent(ddl);
        } else if (!ddl.parameters().isEmpty() || !ddl.bodyStatements().isEmpty()
                || (ddl.bodyRaw() != null && ddl.bodyRaw().length() > 0)
                || (ddl.returnsType() != null && ddl.returnsType().length() > 0)) {
            writeRoutineParamsAndBody(ddl);
        } else if (ddl.tail() != null && ddl.type() != SqlStatementType.ALTER
                && ddl.type() != SqlStatementType.RENAME) {
            sp();
            out.append(ddl.tail());
        }
        // ALTER 的 tail 由 writeAlterClauses 输出，避免重复
    }

    /**
     * CREATE TABLE 列清单：pretty 时每列一行并缩进，compact 保持单行。
     */
    private void writeCreateTableColumns(SqlDdlStatement ddl) {
        sp();
        out.append('(');
        if (!ddl.columnDefinitions().isEmpty()) {
            if (pretty) {
                indent++;
                for (int i = 0; i < ddl.columnDefinitions().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    nl();
                    out.append(ddl.columnDefinitions().get(i));
                }
                indent--;
                nl();
            } else {
                for (int i = 0; i < ddl.columnDefinitions().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                        sp();
                    }
                    out.append(ddl.columnDefinitions().get(i));
                }
            }
        } else {
            commaIdents(ddl.columns());
        }
        out.append(')');
    }

    private void writeTriggerOrEvent(SqlDdlStatement ddl) {
        if (ddl.eventScheduleKind() != null) {
            sp();
            kw("ON");
            sp();
            kw("SCHEDULE");
            sp();
            out.append(ddl.eventScheduleKind());
            if (ddl.eventScheduleRaw() != null && ddl.eventScheduleRaw().length() > 0) {
                sp();
                out.append(ddl.eventScheduleRaw());
            }
            if (ddl.eventStarts() != null && ddl.eventStarts().length() > 0) {
                sp();
                kw("STARTS");
                sp();
                out.append(ddl.eventStarts());
            }
            if (ddl.eventEnds() != null && ddl.eventEnds().length() > 0) {
                sp();
                kw("ENDS");
                sp();
                out.append(ddl.eventEnds());
            }
            if (ddl.eventOnCompletion() != null && ddl.eventOnCompletion().length() > 0) {
                sp();
                kw("ON");
                sp();
                kw("COMPLETION");
                sp();
                out.append(ddl.eventOnCompletion());
            }
            if (ddl.eventDisableOnSlave()) {
                sp();
                kw("DISABLE");
                sp();
                kw("ON");
                sp();
                kw("SLAVE");
            } else if (ddl.eventEnabled() != null) {
                sp();
                out.append(ddl.eventEnabled().booleanValue() ? "ENABLE" : "DISABLE");
            }
            if (ddl.eventComment() != null && ddl.eventComment().length() > 0) {
                sp();
                kw("COMMENT");
                sp();
                out.append(ddl.eventComment());
            }
            sp();
            kw("DO");
        }
        if (ddl.triggerTiming() != null) {
            sp();
            out.append(ddl.triggerTiming());
        }
        if (ddl.triggerEvent() != null) {
            sp();
            out.append(ddl.triggerEvent());
            if (!ddl.triggerUpdateColumns().isEmpty()) {
                sp();
                kw("OF");
                for (int i = 0; i < ddl.triggerUpdateColumns().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                        sp();
                    }
                    writeExpr(ddl.triggerUpdateColumns().get(i));
                }
            }
        }
        if (ddl.triggerTable() != null) {
            sp();
            kw("ON");
            sp();
            writeExpr(ddl.triggerTable());
        }
        if (ddl.triggerForEach() != null) {
            sp();
            kw("FOR");
            sp();
            kw("EACH");
            sp();
            out.append(ddl.triggerForEach());
        }
        if (ddl.triggerOrder() != null) {
            sp();
            out.append(ddl.triggerOrder());
            if (ddl.triggerOther() != null) {
                sp();
                writeExpr(ddl.triggerOther());
            }
        }
        if (!ddl.bodyStatements().isEmpty()) {
            boolean needBegin = ddl.bodyStatements().size() > 1
                    || (ddl.bodyRaw() != null && ddl.bodyRaw().toUpperCase().contains("BEGIN"));
            if (needBegin) {
                sp();
                kw("BEGIN");
            } else {
                sp();
            }
            for (int i = 0; i < ddl.bodyStatements().size(); i++) {
                if (needBegin) {
                    sp();
                }
                writeNode(ddl.bodyStatements().get(i));
                if (needBegin || i < ddl.bodyStatements().size() - 1) {
                    out.append(';');
                }
            }
            if (needBegin) {
                sp();
                kw("END");
            }
        } else if (ddl.bodyRaw() != null && ddl.bodyRaw().length() > 0) {
            sp();
            out.append(ddl.bodyRaw());
        } else if (ddl.tail() != null) {
            sp();
            out.append(ddl.tail());
        }
    }

    private void writeRoutineParamsAndBody(SqlDdlStatement ddl) {
        if (!ddl.parameters().isEmpty()) {
            sp();
            out.append('(');
            for (int i = 0; i < ddl.parameters().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeRoutineParam(ddl.parameters().get(i));
            }
            out.append(')');
        } else if (ddl.tail() != null && ddl.tail().startsWith("(")
                && ddl.bodyRaw() == null && ddl.bodyStatements().isEmpty()) {
            sp();
            out.append(ddl.tail());
            return;
        }
        if (ddl.returnsType() != null && ddl.returnsType().length() > 0) {
            sp();
            kw("RETURNS");
            sp();
            out.append(ddl.returnsType());
        }
        if (!ddl.bodyStatements().isEmpty()) {
            sp();
            kw("BEGIN");
            for (int i = 0; i < ddl.bodyStatements().size(); i++) {
                sp();
                writeNode(ddl.bodyStatements().get(i));
                out.append(';');
            }
            sp();
            kw("END");
        } else if (ddl.bodyRaw() != null && ddl.bodyRaw().length() > 0) {
            sp();
            out.append(ddl.bodyRaw());
        } else if (ddl.tail() != null) {
            // parameters 已写出时 tail 含参数+体，避免重复参数
            if (ddl.parameters().isEmpty()) {
                sp();
                out.append(ddl.tail());
            } else {
                String t = ddl.tail().trim();
                if (t.startsWith("(")) {
                    int depth = 0;
                    int cut = -1;
                    for (int i = 0; i < t.length(); i++) {
                        char c = t.charAt(i);
                        if (c == '(') {
                            depth++;
                        } else if (c == ')') {
                            depth--;
                            if (depth == 0) {
                                cut = i + 1;
                                break;
                            }
                        }
                    }
                    if (cut >= 0 && cut < t.length()) {
                        String rest = t.substring(cut).trim();
                        if (!rest.isEmpty()) {
                            sp();
                            out.append(rest);
                        }
                    }
                } else {
                    sp();
                    out.append(t);
                }
            }
        }
    }

    private void writeRoutineParam(SqlRoutineParam param) {
        if (param.mode() != null) {
            out.append(param.mode());
            sp();
        }
        if (param.name() != null) {
            writeExpr(param.name());
        }
        if (param.typeRaw() != null && param.typeRaw().length() > 0) {
            sp();
            out.append(param.typeRaw());
        }
    }

    private void writeAlterClauses(SqlDdlStatement ddl) {
        if (ddl.alterAction() == null) {
            if (ddl.tail() != null) {
                spBeforeComma(ddl.tail());
                out.append(ddl.tail());
            }
            return;
        }
        sp();
        out.append(ddl.alterAction());
        if (ddl.renameTo() != null) {
            sp();
            writeExpr(ddl.renameTo());
            // RENAME TABLE a TO b, c TO d：第二组以后在 tail，提前 return 会静默丢组
        }
        if (ddl.constraintName() != null) {
            sp();
            writeExpr(ddl.constraintName());
        }
        if (ddl.constraintType() != null
                && ddl.alterAction() != null
                && ddl.alterAction().toUpperCase().contains("CONSTRAINT")) {
            sp();
            out.append(ddl.constraintType());
        }
        if (ddl.indexName() != null) {
            sp();
            writeExpr(ddl.indexName());
        }
        if (!ddl.indexColumns().isEmpty()) {
            sp();
            out.append('(');
            commaIdents(ddl.indexColumns());
            out.append(')');
        }
        if (!ddl.columns().isEmpty() && ddl.indexName() == null
                && ddl.constraintType() == null) {
            // CHANGE old new … 用空格分隔；其余 ADD/DROP/MODIFY 列清单亦按空格（单列常见）
            for (int i = 0; i < ddl.columns().size(); i++) {
                sp();
                writeExpr(ddl.columns().get(i));
            }
        }
        if (!ddl.referencedTables().isEmpty()) {
            sp();
            kw("REFERENCES");
            sp();
            writeExpr(ddl.referencedTables().get(0));
        }
        if (ddl.columnDefinition() != null) {
            sp();
            out.append(ddl.columnDefinition());
        }
        if (ddl.tail() != null) {
            spBeforeComma(ddl.tail());
            out.append(ddl.tail());
        }
    }

    /**
     * tail 以逗号开头（RENAME TABLE a TO b , c TO d 的后续组）时不再补空格。
     */
    private void spBeforeComma(String tail) {
        if (tail.isEmpty() || tail.charAt(0) != ',') {
            sp();
        }
    }

    private static boolean isTableDdl(SqlDdlStatement ddl) {
        String objectType = ddl.objectType();
        return objectType != null && "TABLE".equalsIgnoreCase(objectType);
    }

    private static boolean isIndexDdl(SqlDdlStatement ddl) {
        String objectType = ddl.objectType();
        return objectType != null && "INDEX".equalsIgnoreCase(objectType);
    }

    private void writeExplain(SqlExplainStatement ex) {
        if (ex == null) {
            return;
        }
        if (ex.describe()) {
            kw("DESCRIBE");
            if (ex.name() != null) {
                sp();
                writeExpr(ex.name());
            }
            if (ex.raw() != null && ex.raw().length() > 0) {
                sp();
                out.append(ex.raw());
            }
            return;
        }
        kw("EXPLAIN");
        if (!ex.options().isEmpty()) {
            sp();
            out.append('(');
            boolean needComma = false;
            if (ex.analyze()) {
                kw("ANALYZE");
                needComma = true;
            }
            for (int i = 0; i < ex.options().size(); i++) {
                if (needComma) {
                    out.append(", ");
                }
                out.append(ex.options().get(i));
                needComma = true;
            }
            if (ex.format() != null && ex.format().length() > 0) {
                if (needComma) {
                    out.append(", ");
                }
                kw("FORMAT");
                sp();
                out.append(ex.format());
            }
            out.append(')');
        } else {
            if (ex.analyze()) {
                sp();
                kw("ANALYZE");
            }
            if (ex.format() != null && ex.format().length() > 0) {
                sp();
                kw("FORMAT");
                out.append('=');
                out.append(ex.format());
            }
        }
        if (ex.statement() != null) {
            sp();
            writeNode(ex.statement());
        } else if (ex.name() != null) {
            sp();
            writeExpr(ex.name());
        }
        if (ex.raw() != null && ex.raw().length() > 0) {
            sp();
            out.append(ex.raw());
        }
    }

    private void writeSet(SqlSetStatement set) {
        if (set == null) {
            return;
        }
        kw("SET");
        if (set.scope() != null && set.scope().length() > 0) {
            sp();
            out.append(set.scope());
        }
        if ("PASSWORD".equalsIgnoreCase(set.setKind())) {
            sp();
            kw("PASSWORD");
            if (set.raw() != null && set.raw().length() > 0) {
                sp();
                out.append(set.raw());
            }
            return;
        }
        if ("NAMES".equalsIgnoreCase(set.setKind()) || "CHARACTER SET".equalsIgnoreCase(set.setKind())) {
            sp();
            out.append(set.setKind());
            if (!set.assignments().isEmpty()) {
                SqlSetStatement.Assignment a = set.assignments().get(0);
                if (a != null && a.value() != null) {
                    sp();
                    writeExpr(a.value());
                }
            }
            if (set.raw() != null && set.raw().length() > 0) {
                sp();
                out.append(set.raw());
            }
            return;
        }
        for (int i = 0; i < set.assignments().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            SqlSetStatement.Assignment a = set.assignments().get(i);
            if (a == null) {
                continue;
            }
            sp();
            if (a.name() != null) {
                writeExpr(a.name());
            }
            if (a.value() != null) {
                if (a.equalsSign()) {
                    out.append(" = ");
                } else {
                    sp();
                }
                writeExpr(a.value());
            }
        }
        if (set.raw() != null && set.raw().length() > 0) {
            sp();
            out.append(set.raw());
        }
    }

    private void writeCommentOn(SqlCommentOnStatement c) {
        if (c == null) {
            return;
        }
        kw("COMMENT");
        sp();
        kw("ON");
        if (c.objectKind() != null && c.objectKind().length() > 0) {
            sp();
            out.append(c.objectKind());
        }
        if (c.name() != null) {
            sp();
            writeExpr(c.name());
        }
        if (c.comment() != null) {
            sp();
            kw("IS");
            sp();
            writeExpr(c.comment());
        }
        if (c.raw() != null && c.raw().length() > 0) {
            sp();
            out.append(c.raw());
        }
    }

    private void writeShow(SqlShowStatement show) {
        if (show == null) {
            return;
        }
        kw("SHOW");
        if (show.showKind() != null && show.showKind().length() > 0) {
            sp();
            out.append(show.showKind());
        }
        if ("CREATE".equalsIgnoreCase(show.showKind())
                && show.objectType() != null && show.objectType().length() > 0) {
            sp();
            out.append(show.objectType());
        }
        if (show.fromOrIn() != null && show.fromOrIn().length() > 0) {
            sp();
            out.append(show.fromOrIn());
        }
        if (show.name() != null) {
            sp();
            writeExpr(show.name());
        }
        if (show.raw() != null && show.raw().length() > 0) {
            sp();
            out.append(show.raw());
        }
    }

    private void writeSimple(SqlSimpleStatement stmt) {
        // OTHER（BEGIN/DECLARE 等）：text 已是完整语句
        if (stmt.type() == SqlStatementType.OTHER) {
            if (stmt.text() != null) {
                out.append(stmt.text());
            }
            return;
        }
        kw(stmt.type().name());
        // SHOW：text 已含完整子句（含表名），name 仅供抽表
        if (stmt.type() == SqlStatementType.SHOW) {
            if (stmt.text() != null && !stmt.text().isEmpty()) {
                sp();
                out.append(stmt.text());
            } else if (stmt.name() != null) {
                sp();
                writeExpr(stmt.name());
            }
            if (stmt.inner() != null) {
                sp();
                writeNode(stmt.inner());
            }
            return;
        }
        if (stmt.type() == SqlStatementType.CALL) {
            if (stmt.name() != null) {
                sp();
                writeExpr(stmt.name());
            }
            if (stmt.withArguments()) {
                out.append('(');
                commaExprs(stmt.arguments());
                out.append(')');
            }
            return;
        }
        if (stmt.type() == SqlStatementType.GRANT || stmt.type() == SqlStatementType.REVOKE) {
            if (stmt.privileges() != null && !stmt.privileges().isEmpty()) {
                sp();
                out.append(stmt.privileges());
            }
            if (stmt.name() != null) {
                sp();
                kw("ON");
                sp();
                writeExpr(stmt.name());
            }
            if (stmt.text() != null) {
                sp();
                out.append(stmt.text());
            }
            return;
        }
        if (stmt.type() == SqlStatementType.TRUNCATE) {
            // TRUNCATE [TABLE] t：统一带 TABLE（MySQL 推荐写法，两种输入语义相同）
            sp();
            kw("TABLE");
        }
        if (stmt.name() != null) {
            sp();
            writeExpr(stmt.name());
        }
        if (stmt.value() != null) {
            // SET NAMES utf8mb4 无等号；其它 SET a = 1 保留 =
            if (stmt.type() == SqlStatementType.SET && isNamesIdent(stmt.name())) {
                sp();
            } else {
                out.append(" = ");
            }
            writeExpr(stmt.value());
        }
        if (stmt.inner() != null) {
            sp();
            writeNode(stmt.inner());
        }
        if (stmt.text() != null) {
            sp();
            out.append(stmt.text());
        }
    }

    private static boolean isNamesIdent(SqlIdentifier name) {
        return name != null && "NAMES".equalsIgnoreCase(name.simpleName());
    }

    private void writeFrom(SqlTableSource source) {
        if (source == null) {
            return;
        }
        if (source instanceof SqlTable) {
            writeExpr(((SqlTable) source).name());
            SqlTable table = (SqlTable) source;
            if (!table.partitions().isEmpty()) {
                sp();
                kw("PARTITION");
                sp();
                out.append('(');
                commaIdents(table.partitions());
                out.append(')');
            }
            if (table.partitionBy() != null && table.partitionBy().length() > 0) {
                sp();
                kw("PARTITION");
                sp();
                kw("BY");
                sp();
                out.append(table.partitionBy());
            }
            if (table.indexHint() != null) {
                sp();
                out.append(table.indexHint());
            }
            if (table.temporalClause() != null) {
                sp();
                out.append(table.temporalClause());
            }
            if (table.sampleClause() != null) {
                sp();
                out.append(table.sampleClause());
            }
            if (table.optimizerHint() != null) {
                sp();
                out.append(table.optimizerHint());
            }
        } else if (source instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) source;
            if (join.joinType() == SqlJoin.Type.LATERAL_VIEW
                    && join.right() instanceof SqlFunctionTable) {
                writeFrom(join.left());
                sp();
                kw("LATERAL");
                sp();
                kw("VIEW");
                sp();
                SqlFunctionTable lvf = (SqlFunctionTable) join.right();
                if ("OUTER".equals(lvf.withDefinition())) {
                    kw("OUTER");
                    sp();
                }
                writeExpr(lvf.function());
                if (lvf.alias() != null) {
                    sp();
                    out.append(lvf.alias());
                }
                if (!lvf.columnAliases().isEmpty()) {
                    sp();
                    kw("AS");
                    sp();
                    commaIdents(lvf.columnAliases());
                }
            } else {
                writeFrom(join.left());
                sp();
                writeJoinType(join.joinType(), join.natural());
                sp();
                writeFrom(join.right());
                if (join.condition() != null) {
                    sp();
                    kw("ON");
                    sp();
                    writeExpr(join.condition());
                } else if (join.using() != null && !join.using().isEmpty()) {
                    sp();
                    kw("USING");
                    out.append('(');
                    commaIdents(join.using());
                    out.append(')');
                }
            }
        } else if (source instanceof SqlPivotTable) {
            SqlPivotTable pivot = (SqlPivotTable) source;
            writeFrom(pivot.input());
            sp();
            kw(pivot.unpivot() ? "UNPIVOT" : "PIVOT");
            if (pivot.nullsClause() != null) {
                sp();
                out.append(pivot.nullsClause());
            }
            sp();
            out.append('(');
            if (pivot.definition() != null) {
                out.append(pivot.definition());
            }
            out.append(')');
        } else if (source instanceof SqlSubqueryTable) {
            SqlSubqueryTable sub = (SqlSubqueryTable) source;
            if (sub.lateral()) {
                kw("LATERAL");
                sp();
            }
            out.append('(');
            writeNode(sub.query());
            out.append(')');
        } else if (source instanceof SqlFunctionTable) {
            SqlFunctionTable ft = (SqlFunctionTable) source;
            if (ft.lateral()) {
                kw("LATERAL");
                sp();
            }
            if (ft.tableKeyword()) {
                kw("TABLE");
                out.append('(');
                writeExpr(ft.function());
                out.append(')');
            } else {
                writeExpr(ft.function());
            }
            if (ft.withOrdinality()) {
                sp();
                kw("WITH");
                sp();
                kw("ORDINALITY");
            }
            if (ft.withDefinition() != null && !ft.withDefinition().isEmpty()) {
                sp();
                kw("WITH");
                sp();
                out.append(ft.withDefinition());
            }
        } else if (source instanceof SqlValuesTable) {
            SqlValuesTable vt = (SqlValuesTable) source;
            out.append('(');
            kw("VALUES");
            sp();
            List<SqlExpr> rows = vt.rows();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeValuesRow(rows.get(i));
            }
            out.append(')');
        }
        if (source.alias() != null) {
            sp();
            writeIdentPart(source.alias(), options.quoteIdentifiers() || needsIdentQuote(source.alias()));
            if (!source.columnAliases().isEmpty()) {
                out.append('(');
                commaIdents(source.columnAliases());
                out.append(')');
            }
        }
        if (source instanceof SqlTable) {
            SqlTable t = (SqlTable) source;
            if (t.withHint() != null) {
                sp();
                out.append(t.withHint());
            }
            if (t.matchRecognize() != null) {
                sp();
                kw("MATCH_RECOGNIZE");
                sp();
                out.append('(');
                writeMatchRecognizeBody(t.matchRecognize());
                out.append(')');
            }
        }
    }

    private void writeModelClause(SqlModelClause model) {
        if (model == null) {
            return;
        }
        boolean structured = !model.partitionBy().isEmpty() || !model.dimensionBy().isEmpty()
                || !model.measures().isEmpty() || (model.rules() != null && model.rules().length() > 0)
                || !model.ruleEntries().isEmpty()
                || (model.options() != null && model.options().length() > 0);
        if (!structured) {
            if (model.raw() != null && model.raw().length() > 0) {
                out.append(model.raw());
            } else if (model.tail() != null) {
                out.append(model.tail());
            }
            return;
        }
        kw("MODEL");
        if (model.options() != null && model.options().length() > 0) {
            sp();
            out.append(model.options());
        }
        if (!model.partitionBy().isEmpty()) {
            sp();
            kw("PARTITION");
            sp();
            kw("BY");
            sp();
            out.append('(');
            for (int i = 0; i < model.partitionBy().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeExpr(model.partitionBy().get(i));
            }
            out.append(')');
        }
        if (!model.dimensionBy().isEmpty()) {
            sp();
            kw("DIMENSION");
            sp();
            kw("BY");
            sp();
            out.append('(');
            for (int i = 0; i < model.dimensionBy().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeExpr(model.dimensionBy().get(i));
            }
            out.append(')');
        }
        if (!model.measures().isEmpty()) {
            sp();
            kw("MEASURES");
            sp();
            out.append('(');
            for (int i = 0; i < model.measures().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeExpr(model.measures().get(i));
            }
            out.append(')');
        }
        if (model.rules() != null || !model.ruleEntries().isEmpty()) {
            sp();
            kw("RULES");
            if (model.rulesModifiers() != null && model.rulesModifiers().length() > 0) {
                sp();
                out.append(model.rulesModifiers());
            }
            sp();
            out.append('(');
            if (!model.ruleEntries().isEmpty()) {
                for (int i = 0; i < model.ruleEntries().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                        sp();
                    }
                    writeModelRule(model.ruleEntries().get(i));
                }
            } else {
                out.append(model.rules());
            }
            out.append(')');
        }
        if (model.tail() != null && model.tail().length() > 0) {
            sp();
            out.append(model.tail());
        }
    }

    private void writeMatchRecognizeBody(SqlMatchRecognize mr) {
        if (mr == null) {
            return;
        }
        boolean structured = !mr.partitionBy().isEmpty() || !mr.orderBy().isEmpty()
                || !mr.measures().isEmpty() || (mr.pattern() != null && mr.pattern().length() > 0)
                || !mr.define().isEmpty() || !mr.subsets().isEmpty()
                || (mr.rowsPerMatch() != null && mr.rowsPerMatch().length() > 0)
                || (mr.afterMatch() != null && mr.afterMatch().length() > 0)
                || (mr.within() != null && mr.within().length() > 0);
        if (!structured) {
            if (mr.raw() != null) {
                out.append(mr.raw());
            } else if (mr.optionsRaw() != null) {
                out.append(mr.optionsRaw());
            }
            return;
        }
        boolean needSp = false;
        if (!mr.partitionBy().isEmpty()) {
            kw("PARTITION");
            sp();
            kw("BY");
            sp();
            for (int i = 0; i < mr.partitionBy().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeExpr(mr.partitionBy().get(i));
            }
            needSp = true;
        }
        if (!mr.orderBy().isEmpty()) {
            if (needSp) {
                sp();
            }
            kw("ORDER");
            sp();
            kw("BY");
            sp();
            writeOrder(mr.orderBy());
            needSp = true;
        }
        if (!mr.measures().isEmpty()) {
            if (needSp) {
                sp();
            }
            kw("MEASURES");
            sp();
            for (int i = 0; i < mr.measures().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeNamedExpr(mr.measures().get(i));
            }
            needSp = true;
        }
        if (mr.rowsPerMatch() != null && mr.rowsPerMatch().length() > 0) {
            if (needSp) {
                sp();
            }
            out.append(mr.rowsPerMatch());
            needSp = true;
        }
        if (mr.afterMatch() != null && mr.afterMatch().length() > 0) {
            if (needSp) {
                sp();
            }
            out.append(mr.afterMatch());
            needSp = true;
        }
        if (mr.pattern() != null) {
            if (needSp) {
                sp();
            }
            kw("PATTERN");
            sp();
            out.append('(');
            out.append(mr.pattern());
            out.append(')');
            needSp = true;
        }
        if (mr.within() != null && mr.within().length() > 0) {
            if (needSp) {
                sp();
            }
            out.append(mr.within());
            needSp = true;
        }
        if (!mr.define().isEmpty()) {
            if (needSp) {
                sp();
            }
            kw("DEFINE");
            sp();
            for (int i = 0; i < mr.define().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeNamedExpr(mr.define().get(i));
            }
            needSp = true;
        }
        if (!mr.subsets().isEmpty()) {
            if (needSp) {
                sp();
            }
            kw("SUBSET");
            sp();
            for (int i = 0; i < mr.subsets().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeSubset(mr.subsets().get(i));
            }
            needSp = true;
        }
        if (mr.optionsRaw() != null && mr.optionsRaw().length() > 0) {
            if (needSp) {
                sp();
            }
            out.append(mr.optionsRaw());
        }
    }

    private void writeDeclare(SqlDeclareStatement decl) {
        if (decl.names().isEmpty() && decl.raw() != null && decl.raw().length() > 0) {
            out.append(decl.raw());
            return;
        }
        kw("DECLARE");
        for (int i = 0; i < decl.names().size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            } else {
                sp();
            }
            writeExpr(decl.names().get(i));
        }
        if (decl.kind() == SqlDeclareStatement.Kind.CURSOR) {
            sp();
            kw("CURSOR");
            sp();
            kw("FOR");
            if (decl.cursorQuery() != null) {
                sp();
                writeNode(decl.cursorQuery());
            }
        } else if (decl.kind() == SqlDeclareStatement.Kind.CONDITION) {
            sp();
            kw("CONDITION");
            sp();
            kw("FOR");
            if (decl.conditionFor() != null && decl.conditionFor().length() > 0) {
                sp();
                out.append(decl.conditionFor());
            }
        } else {
            if (decl.typeRaw() != null && decl.typeRaw().length() > 0) {
                sp();
                out.append(decl.typeRaw());
            }
            if (decl.defaultValue() != null) {
                sp();
                kw("DEFAULT");
                sp();
                writeExpr(decl.defaultValue());
            }
        }
    }

    private void writeHandler(SqlHandlerStatement h) {
        if (h.raw() != null && h.raw().length() > 0
                && (h.action() == null || h.conditions().isEmpty())) {
            out.append(h.raw());
            return;
        }
        kw("DECLARE");
        if (h.action() != null) {
            sp();
            out.append(h.action());
        }
        sp();
        kw("HANDLER");
        sp();
        kw("FOR");
        for (int i = 0; i < h.conditions().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            sp();
            out.append(h.conditions().get(i));
        }
        if (!h.bodyStatements().isEmpty()) {
            boolean needBegin = h.bodyStatements().size() > 1
                    || (h.raw() != null && h.raw().toUpperCase().contains("BEGIN"));
            if (needBegin) {
                sp();
                kw("BEGIN");
            }
            for (int i = 0; i < h.bodyStatements().size(); i++) {
                sp();
                writeNode(h.bodyStatements().get(i));
                if (needBegin || i < h.bodyStatements().size() - 1) {
                    out.append(';');
                }
            }
            if (needBegin) {
                sp();
                kw("END");
            }
        } else if (h.raw() != null) {
            sp();
            out.append(h.raw());
        }
    }

    private void writeBlock(SqlBlockStatement block) {
        if (block.raw() != null && block.bodyStatements().isEmpty() && block.declares().isEmpty()) {
            out.append(block.raw());
            return;
        }
        if (block.withDeclare()) {
            kw("DECLARE");
            if (block.declareRaw() != null && block.declareRaw().length() > 0
                    && block.declares().isEmpty()) {
                sp();
                out.append(block.declareRaw());
            } else {
                for (int i = 0; i < block.declares().size(); i++) {
                    nl();
                    writeNode(block.declares().get(i));
                    out.append(';');
                }
            }
            nl();
        }
        kw("BEGIN");
        for (int i = 0; i < block.bodyStatements().size(); i++) {
            sp();
            writeNode(block.bodyStatements().get(i));
            out.append(';');
        }
        sp();
        kw("END");
    }

    private void writeTableHandler(SqlTableHandlerStatement h) {
        if (h.raw() != null && h.operation() == null) {
            out.append(h.raw());
            return;
        }
        kw("HANDLER");
        if (h.table() != null) {
            sp();
            writeExpr(h.table());
        }
        if (h.operation() != null) {
            sp();
            out.append(h.operation());
        }
        if ("OPEN".equals(h.operation()) && h.alias() != null && h.alias().length() > 0) {
            sp();
            out.append(h.alias());
        }
        if ("READ".equals(h.operation())) {
            if (h.indexName() != null) {
                sp();
                writeExpr(h.indexName());
            }
            if (h.readDirection() != null) {
                sp();
                out.append(h.readDirection());
            }
            if (h.keyRaw() != null && h.keyRaw().length() > 0) {
                sp();
                out.append(h.keyRaw());
            }
            if (h.where() != null) {
                sp();
                kw("WHERE");
                sp();
                writeExpr(h.where());
            }
            if (h.limit() != null) {
                sp();
                writeLimit(h.limit());
            }
        }
    }

    private void writeCopy(SqlCopyStatement copy) {
        if (copy == null) {
            return;
        }
        if (copy.raw() != null && copy.table() == null && copy.query() == null
                && copy.source() == null && copy.sourceKind() == null) {
            out.append(copy.raw());
            return;
        }
        kw("COPY");
        if (copy.query() != null && copy.query().length() > 0) {
            sp();
            out.append(copy.query());
        } else if (copy.table() != null) {
            sp();
            writeExpr(copy.table());
            if (!copy.columns().isEmpty()) {
                out.append('(');
                for (int i = 0; i < copy.columns().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                        sp();
                    }
                    writeExpr(copy.columns().get(i));
                }
                out.append(')');
            }
        }
        sp();
        out.append(copy.to() ? "TO" : "FROM");
        if (copy.sourceKind() != null) {
            sp();
            if ("FILE".equalsIgnoreCase(copy.sourceKind())) {
                if (copy.source() != null) {
                    writeExpr(copy.source());
                }
            } else if ("PROGRAM".equalsIgnoreCase(copy.sourceKind())) {
                kw("PROGRAM");
                if (copy.source() != null) {
                    sp();
                    writeExpr(copy.source());
                }
            } else {
                out.append(copy.sourceKind());
            }
        } else if (copy.source() != null) {
            sp();
            writeExpr(copy.source());
        }
        if (copy.withClause() != null && copy.withClause().length() > 0) {
            sp();
            out.append(copy.withClause());
        }
        if (copy.raw() != null && copy.raw().length() > 0
                && !copy.raw().toUpperCase().startsWith("COPY")) {
            sp();
            out.append(copy.raw());
        }
    }

    private void writeFlush(SqlFlushStatement flush) {
        if (flush == null) {
            return;
        }
        if (flush.raw() != null && flush.options().isEmpty() && flush.tables().isEmpty()
                && flush.raw().toUpperCase().startsWith("FLUSH")) {
            out.append(flush.raw());
            return;
        }
        kw("FLUSH");
        if (flush.noWriteToBinlog()) {
            sp();
            kw("NO_WRITE_TO_BINLOG");
        }
        for (int i = 0; i < flush.options().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            sp();
            String opt = flush.options().get(i);
            kw(opt);
            if ("TABLES".equalsIgnoreCase(opt)) {
                for (int j = 0; j < flush.tables().size(); j++) {
                    if (j > 0) {
                        out.append(',');
                    }
                    sp();
                    writeExpr(flush.tables().get(j));
                }
                if (flush.tablesModifier() != null && flush.tablesModifier().length() > 0) {
                    sp();
                    out.append(flush.tablesModifier());
                }
            }
        }
        if (flush.raw() != null && flush.raw().length() > 0
                && !flush.raw().toUpperCase().startsWith("FLUSH")) {
            sp();
            out.append(flush.raw());
        }
    }

    private void writeMaintenance(SqlMaintenanceStatement m) {
        if (m == null) {
            return;
        }
        if (m.kind() == null) {
            if (m.raw() != null) {
                out.append(m.raw());
            }
            return;
        }
        out.append(m.kind().toUpperCase());
        if (m.optionsRaw() != null && m.optionsRaw().length() > 0) {
            sp();
            out.append(m.optionsRaw());
        }
        for (int i = 0; i < m.tables().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            sp();
            writeExpr(m.tables().get(i));
        }
        if (m.raw() != null && m.raw().length() > 0) {
            sp();
            out.append(m.raw());
        }
    }

    private void writeTransactionControl(SqlTransactionControlStatement tx) {
        if (tx == null) {
            return;
        }
        if (tx.kind() == null) {
            if (tx.raw() != null) {
                out.append(tx.raw());
            }
            return;
        }
        String kind = tx.kind().toUpperCase();
        kw(kind);
        if ("RELEASE".equals(kind)) {
            sp();
            kw("SAVEPOINT");
            if (tx.savepoint() != null) {
                sp();
                writeExpr(tx.savepoint());
            }
        } else if ("SAVEPOINT".equals(kind)) {
            if (tx.savepoint() != null) {
                sp();
                writeExpr(tx.savepoint());
            }
        } else if ("ROLLBACK".equals(kind) && tx.toSavepoint()) {
            sp();
            kw("TO");
            sp();
            kw("SAVEPOINT");
            if (tx.savepoint() != null) {
                sp();
                writeExpr(tx.savepoint());
            }
        }
        if (tx.raw() != null && tx.raw().length() > 0) {
            sp();
            out.append(tx.raw());
        }
    }

    private void writeStartTransaction(SqlStartTransactionStatement tx) {
        if (tx == null) {
            return;
        }
        boolean hasChar = tx.isolationLevel() != null || tx.readOnly() != null
                || tx.consistentSnapshot() || tx.deferrable() != null || tx.work();
        if (!tx.beginForm() && !hasChar && tx.raw() != null) {
            out.append(tx.raw());
            return;
        }
        if (tx.beginForm()) {
            kw("BEGIN");
            if (tx.work()) {
                sp();
                kw("WORK");
            }
        } else {
            kw("START");
            sp();
            kw("TRANSACTION");
        }
        boolean needComma = false;
        if (tx.isolationLevel() != null && tx.isolationLevel().length() > 0) {
            sp();
            kw("ISOLATION");
            sp();
            kw("LEVEL");
            sp();
            out.append(tx.isolationLevel());
            needComma = true;
        }
        if (tx.readOnly() != null) {
            if (needComma) {
                out.append(',');
            }
            sp();
            out.append(tx.readOnly().booleanValue() ? "READ ONLY" : "READ WRITE");
            needComma = true;
        }
        if (tx.consistentSnapshot()) {
            if (needComma) {
                out.append(',');
            }
            sp();
            kw("WITH");
            sp();
            kw("CONSISTENT");
            sp();
            kw("SNAPSHOT");
            needComma = true;
        }
        if (tx.deferrable() != null) {
            if (needComma) {
                out.append(',');
            }
            sp();
            if (!tx.deferrable().booleanValue()) {
                kw("NOT");
                sp();
            }
            kw("DEFERRABLE");
        }
        if (tx.raw() != null && tx.raw().length() > 0) {
            sp();
            out.append(tx.raw());
        }
    }

    private void writeLoadData(SqlLoadDataStatement load) {
        if (load == null) {
            return;
        }
        if (load.raw() != null && load.table() == null && load.fileName() == null) {
            out.append(load.raw());
            return;
        }
        kw("LOAD");
        sp();
        kw("DATA");
        if (load.priority() != null) {
            sp();
            out.append(load.priority());
        }
        if (load.local()) {
            sp();
            kw("LOCAL");
        }
        sp();
        kw("INFILE");
        if (load.fileName() != null) {
            sp();
            writeExpr(load.fileName());
        }
        if (load.duplicateMode() != null) {
            sp();
            out.append(load.duplicateMode());
        }
        sp();
        kw("INTO");
        sp();
        kw("TABLE");
        if (load.table() != null) {
            sp();
            writeExpr(load.table());
        }
        if (load.characterSet() != null) {
            sp();
            kw("CHARACTER");
            sp();
            kw("SET");
            sp();
            out.append(load.characterSet());
        }
        if (load.fieldsClause() != null && load.fieldsClause().length() > 0) {
            sp();
            out.append(load.fieldsClause());
        }
        if (load.linesClause() != null && load.linesClause().length() > 0) {
            sp();
            out.append(load.linesClause());
        }
        if (load.ignoreClause() != null && load.ignoreClause().length() > 0) {
            sp();
            out.append(load.ignoreClause());
        }
        if (!load.columns().isEmpty()) {
            sp();
            out.append('(');
            for (int i = 0; i < load.columns().size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeExpr(load.columns().get(i));
            }
            out.append(')');
        }
        if (load.tail() != null && load.tail().length() > 0) {
            sp();
            out.append(load.tail());
        }
    }

    private void writeLockTables(SqlLockTablesStatement lock) {
        if (lock == null) {
            return;
        }
        if (lock.raw() != null && lock.items().isEmpty() && !lock.unlock()) {
            out.append(lock.raw());
            return;
        }
        if (lock.unlock()) {
            kw("UNLOCK");
            sp();
            kw("TABLES");
            return;
        }
        kw("LOCK");
        sp();
        kw("TABLES");
        for (int i = 0; i < lock.items().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            SqlLockTablesStatement.LockItem item = lock.items().get(i);
            if (item.table() != null) {
                sp();
                writeExpr(item.table());
            }
            if (item.alias() != null && item.alias().length() > 0) {
                sp();
                out.append(item.alias());
            }
            if (item.lockMode() != null && item.lockMode().length() > 0) {
                sp();
                out.append(item.lockMode());
            }
        }
    }

    private void writePrepare(SqlPrepareStatement p) {
        if (p == null) {
            return;
        }
        if (p.raw() != null && p.name() == null && p.source() == null && p.usingBinds().isEmpty()) {
            out.append(p.raw());
            return;
        }
        if (p.kind() == SqlPrepareStatement.Kind.DEALLOCATE) {
            kw("DEALLOCATE");
            sp();
            kw("PREPARE");
            if (p.name() != null) {
                sp();
                writeExpr(p.name());
            }
            return;
        }
        if (p.kind() == SqlPrepareStatement.Kind.EXECUTE_IMMEDIATE) {
            kw("EXECUTE");
            sp();
            kw("IMMEDIATE");
            if (p.source() != null) {
                sp();
                writeExpr(p.source());
            }
            writePrepareUsing(p);
            return;
        }
        if (p.kind() == SqlPrepareStatement.Kind.EXECUTE) {
            kw("EXECUTE");
            if (p.name() != null) {
                sp();
                writeExpr(p.name());
            }
            writePrepareUsing(p);
            return;
        }
        kw("PREPARE");
        if (p.name() != null) {
            sp();
            writeExpr(p.name());
        }
        if (p.source() != null) {
            sp();
            kw("FROM");
            sp();
            writeExpr(p.source());
        }
        if (p.raw() != null && p.source() == null) {
            sp();
            out.append(p.raw());
        }
    }

    private void writePrepareUsing(SqlPrepareStatement p) {
        if (p.usingBinds().isEmpty()) {
            return;
        }
        sp();
        kw("USING");
        for (int i = 0; i < p.usingBinds().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            sp();
            writeExpr(p.usingBinds().get(i));
        }
    }

    private void writeControl(SqlControlStatement ctrl) {
        if (ctrl == null) {
            return;
        }
        if ((ctrl.condition() == null && ctrl.bodyStatements().isEmpty())
                && ctrl.raw() != null) {
            out.append(ctrl.raw());
            return;
        }
        if (ctrl.label() != null
                && (ctrl.kind() == SqlControlStatement.Kind.WHILE
                || ctrl.kind() == SqlControlStatement.Kind.LOOP
                || ctrl.kind() == SqlControlStatement.Kind.REPEAT)) {
            out.append(ctrl.label());
            out.append(':');
            sp();
        }
        if (ctrl.kind() == SqlControlStatement.Kind.IF) {
            kw("IF");
            sp();
            writeExpr(ctrl.condition());
            sp();
            kw("THEN");
            writeStmtListInline(ctrl.bodyStatements());
            for (int i = 0; i < ctrl.elseIfs().size(); i++) {
                SqlControlStatement br = ctrl.elseIfs().get(i);
                sp();
                kw("ELSEIF");
                sp();
                writeExpr(br.condition());
                sp();
                kw("THEN");
                writeStmtListInline(br.bodyStatements());
            }
            if (!ctrl.elseStatements().isEmpty()) {
                sp();
                kw("ELSE");
                writeStmtListInline(ctrl.elseStatements());
            }
            sp();
            kw("END");
            sp();
            kw("IF");
        } else if (ctrl.kind() == SqlControlStatement.Kind.WHILE) {
            kw("WHILE");
            sp();
            writeExpr(ctrl.condition());
            sp();
            kw("DO");
            writeStmtListInline(ctrl.bodyStatements());
            sp();
            kw("END");
            sp();
            kw("WHILE");
        } else if (ctrl.kind() == SqlControlStatement.Kind.LOOP) {
            kw("LOOP");
            writeStmtListInline(ctrl.bodyStatements());
            sp();
            kw("END");
            sp();
            kw("LOOP");
        } else if (ctrl.kind() == SqlControlStatement.Kind.REPEAT) {
            kw("REPEAT");
            writeStmtListInline(ctrl.bodyStatements());
            sp();
            kw("UNTIL");
            sp();
            writeExpr(ctrl.condition());
            sp();
            kw("END");
            sp();
            kw("REPEAT");
        } else if (ctrl.kind() == SqlControlStatement.Kind.CASE) {
            kw("CASE");
            if (ctrl.condition() != null) {
                sp();
                writeExpr(ctrl.condition());
            }
            for (int i = 0; i < ctrl.elseIfs().size(); i++) {
                SqlControlStatement br = ctrl.elseIfs().get(i);
                sp();
                kw("WHEN");
                sp();
                writeExpr(br.condition());
                sp();
                kw("THEN");
                writeStmtListInline(br.bodyStatements());
            }
            if (!ctrl.elseStatements().isEmpty()) {
                sp();
                kw("ELSE");
                writeStmtListInline(ctrl.elseStatements());
            }
            sp();
            kw("END");
            sp();
            kw("CASE");
        } else if (ctrl.kind() == SqlControlStatement.Kind.LEAVE) {
            kw("LEAVE");
            if (ctrl.label() != null) {
                sp();
                out.append(ctrl.label());
            }
        } else if (ctrl.kind() == SqlControlStatement.Kind.ITERATE) {
            kw("ITERATE");
            if (ctrl.label() != null) {
                sp();
                out.append(ctrl.label());
            }
        } else if (ctrl.kind() == SqlControlStatement.Kind.RETURN) {
            kw("RETURN");
            if (ctrl.condition() != null) {
                sp();
                writeExpr(ctrl.condition());
            }
        } else if (ctrl.raw() != null) {
            out.append(ctrl.raw());
        }
    }

    private void writeStmtListInline(java.util.List<SqlStatement> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            sp();
            writeNode(stmts.get(i));
            out.append(';');
        }
    }

    private void writeNamedExpr(SqlNamedExpr item) {
        if (item == null) {
            return;
        }
        if (item.expr() == null && item.raw() != null) {
            out.append(item.raw());
            return;
        }
        if (item.nameFirst()) {
            if (item.name() != null) {
                out.append(item.name());
                sp();
                kw("AS");
                sp();
            }
            writeExpr(item.expr());
        } else {
            writeExpr(item.expr());
            if (item.name() != null) {
                sp();
                out.append(item.name());
            }
        }
    }

    private void writeModelRule(SqlModelRule rule) {
        if (rule == null) {
            return;
        }
        if (rule.value() == null && rule.raw() != null) {
            out.append(rule.raw());
            return;
        }
        if (rule.modifiers() != null && rule.modifiers().length() > 0) {
            out.append(rule.modifiers());
            sp();
        }
        if (rule.cell() != null) {
            out.append(rule.cell());
        }
        if (rule.value() != null) {
            sp();
            out.append('=');
            sp();
            writeExpr(rule.value());
        } else if (rule.raw() != null && rule.cell() == null) {
            out.append(rule.raw());
        }
    }

    private void writeSubset(SqlSubset subset) {
        if (subset == null) {
            return;
        }
        if (subset.name() == null && subset.raw() != null) {
            out.append(subset.raw());
            return;
        }
        if (subset.name() != null) {
            out.append(subset.name());
        }
        sp();
        out.append('=');
        sp();
        out.append('(');
        for (int i = 0; i < subset.members().size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            out.append(subset.members().get(i));
        }
        out.append(')');
    }

    private void writeJoinType(SqlJoin.Type type, boolean natural) {
        if (natural) {
            kw("NATURAL");
            sp();
        }
        if (type == null || type == SqlJoin.Type.INNER || type == SqlJoin.Type.NATURAL) {
            kw("JOIN");
            return;
        }
        switch (type) {
            case LEFT:
                kw("LEFT");
                sp();
                kw("JOIN");
                break;
            case LEFT_ANTI:
                kw("LEFT");
                sp();
                kw("ANTI");
                sp();
                kw("JOIN");
                break;
            case LEFT_SEMI:
                kw("LEFT");
                sp();
                kw("SEMI");
                sp();
                kw("JOIN");
                break;
            case RIGHT:
                kw("RIGHT");
                sp();
                kw("JOIN");
                break;
            case RIGHT_ANTI:
                kw("RIGHT");
                sp();
                kw("ANTI");
                sp();
                kw("JOIN");
                break;
            case RIGHT_SEMI:
                kw("RIGHT");
                sp();
                kw("SEMI");
                sp();
                kw("JOIN");
                break;
            case FULL:
                kw("FULL");
                sp();
                kw("JOIN");
                break;
            case CROSS:
                kw("CROSS");
                sp();
                kw("JOIN");
                break;
            case COMMA:
                out.append(',');
                break;
            case STRAIGHT:
                kw("STRAIGHT_JOIN");
                break;
            case NATURAL:
                kw("NATURAL");
                sp();
                kw("JOIN");
                break;
            case CROSS_APPLY:
                kw("CROSS");
                sp();
                kw("APPLY");
                break;
            case OUTER_APPLY:
                kw("OUTER");
                sp();
                kw("APPLY");
                break;
            case LATERAL_VIEW:
                // right 侧已是表函数；左侧 writeFrom 后直接写 LATERAL VIEW 由专用分支处理
                kw("LATERAL");
                sp();
                kw("VIEW");
                break;
            default:
                kw("JOIN");
                break;
        }
    }

    private void writeLimit(SqlLimit limit) {
        if (limit.fetchStyle()) {
            if (limit.offset() != null) {
                kw("OFFSET");
                sp();
                writeExpr(limit.offset());
                sp();
                kw("ROWS");
                sp();
            }
            if (limit.rowCount() != null) {
                kw("FETCH");
                sp();
                kw("FIRST");
                sp();
                writeExpr(limit.rowCount());
                sp();
                kw("ROWS");
                sp();
                kw("ONLY");
            }
            return;
        }
        if (limit.mysqlCommaStyle() && limit.offset() != null && limit.rowCount() != null) {
            kw("LIMIT");
            sp();
            writeExpr(limit.offset());
            out.append(',');
            writeExpr(limit.rowCount());
            return;
        }
        if (limit.rowCount() != null) {
            kw("LIMIT");
            sp();
            writeExpr(limit.rowCount());
            if (limit.offset() != null) {
                sp();
                kw("OFFSET");
                sp();
                writeExpr(limit.offset());
            }
            return;
        }
        if (limit.offset() != null) {
            kw("OFFSET");
            sp();
            writeExpr(limit.offset());
        }
    }

    /**
     * RETURNING 多列用 {@link SqlListExpr} 存，回写时不加括号。
     */
    private void writeReturning(SqlExpr expr) {
        if (expr instanceof SqlListExpr) {
            List<SqlExpr> items = ((SqlListExpr) expr).items();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(',');
                    sp();
                }
                writeReturningItem(items.get(i));
            }
        } else {
            writeReturningItem(expr);
        }
    }

    private void writeReturningItem(SqlExpr expr) {
        if (expr instanceof SqlFunctionExpr) {
            SqlFunctionExpr fn = (SqlFunctionExpr) expr;
            if (fn.name() != null && "AS".equalsIgnoreCase(fn.name().simpleName())
                    && fn.arguments().size() == 2) {
                writeExpr(fn.arguments().get(0));
                sp();
                kw("AS");
                sp();
                writeExpr(fn.arguments().get(1));
                return;
            }
        }
        writeExpr(expr);
    }

    private void writeOrder(List<SqlOrderByItem> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            writeOrderByItem(items.get(i));
        }
    }

    private void writeSelectInto(SqlSelect select) {
        if (select.intoTable() == null && select.intoVariables().isEmpty()
                && select.intoOutfile() == null) {
            return;
        }
        sp();
        kw("INTO");
        if (select.intoFileKind() != null) {
            sp();
            kw(select.intoFileKind());
            if (select.intoOutfile() != null) {
                sp();
                out.append(select.intoOutfile());
            }
            return;
        }
        if (!select.intoVariables().isEmpty()) {
            sp();
            commaExprs(select.intoVariables());
            return;
        }
        if (select.intoTable() != null) {
            sp();
            writeFrom(select.intoTable());
        }
    }

    private void writeSelectItem(SqlSelectItem item) {
        writeExpr(item.expr());
        if (!item.columnAliases().isEmpty()) {
            sp();
            kw("AS");
            sp();
            out.append('(');
            commaIdents(item.columnAliases());
            out.append(')');
        } else if (item.alias() != null) {
            sp();
            kw("AS");
            sp();
            writeIdentPart(item.alias(), options.quoteIdentifiers() || needsIdentQuote(item.alias()));
        }
    }

    /**
     * 别名不是裸标识符（含空格、连字符等）时强制加引号，
     * 避免 {@code AS -- a} 这类回写把别名输出成注释。
     */
    private static boolean needsIdentQuote(String s) {
        if (s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return true;
            }
        }
        return false;
    }

    private void writeOrderByItem(SqlOrderByItem item) {
        writeExpr(item.expr());
        sp();
        kw(item.asc() ? "ASC" : "DESC");
        if (item.nulls() != null) {
            sp();
            out.append(item.nulls());
        }
    }

    private void writeWindowDefinition(SqlWindowDefinition window) {
        writeExpr(window.name());
        sp();
        kw("AS");
        sp();
        writeOver(window.spec());
    }

    private void writeExpr(SqlExpr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SqlLiteral) {
            writeLiteral((SqlLiteral) expr);
        } else if (expr instanceof SqlIdentifier) {
            writeIdentifier((SqlIdentifier) expr);
        } else if (expr instanceof SqlAllColumns) {
            SqlAllColumns all = (SqlAllColumns) expr;
            if (all.owner() != null) {
                writeExpr(all.owner());
                out.append('.');
            }
            out.append('*');
        } else if (expr instanceof SqlBinaryExpr) {
            SqlBinaryExpr bin = (SqlBinaryExpr) expr;
            if (bin.operator() == SqlBinaryOp.SUBSCRIPT) {
                writeExpr(bin.left());
                out.append('[');
                writeExpr(bin.right());
                out.append(']');
            } else if (bin.operator() == SqlBinaryOp.MEMBER) {
                writeExpr(bin.left());
                out.append('.');
                writeExpr(bin.right());
            } else {
                if (bin.parenthesized()) {
                    out.append('(');
                }
                writeExpr(bin.left());
                sp();
                // CONCAT 一律回写 ||；MySQL 默认把 || 解析为 OR，故 AST 里的 OR 回写为 OR
                kw(bin.operator().symbol());
                sp();
                writeExpr(bin.right());
                if (bin.parenthesized()) {
                    out.append(')');
                }
            }
        } else if (expr instanceof SqlUnaryExpr) {
            SqlUnaryExpr u = (SqlUnaryExpr) expr;
            if (u.operator() == SqlUnaryExpr.Op.ORACLE_OUTER_JOIN) {
                writeExpr(u.expr());
                out.append("(+)");
            } else {
                if (u.operator() == SqlUnaryExpr.Op.EXISTS) {
                    kw("EXISTS");
                    sp();
                } else if (u.operator() == SqlUnaryExpr.Op.NOT) {
                    kw("NOT");
                    sp();
                } else if (u.operator() == SqlUnaryExpr.Op.MINUS) {
                    out.append('-');
                } else if (u.operator() == SqlUnaryExpr.Op.PLUS) {
                    out.append('+');
                } else if (u.operator() == SqlUnaryExpr.Op.TILDE) {
                    out.append('~');
                } else if (u.operator() == SqlUnaryExpr.Op.BINARY) {
                    kw("BINARY");
                    sp();
                }
                writeExpr(u.expr());
            }
        } else if (expr instanceof SqlBetweenExpr) {
            SqlBetweenExpr b = (SqlBetweenExpr) expr;
            writeExpr(b.expr());
            sp();
            if (b.not()) {
                kw("NOT");
                sp();
            }
            kw("BETWEEN");
            sp();
            writeExpr(b.begin());
            sp();
            kw("AND");
            sp();
            writeExpr(b.end());
        } else if (expr instanceof SqlInExpr) {
            SqlInExpr in = (SqlInExpr) expr;
            writeExpr(in.expr());
            sp();
            if (in.not()) {
                kw("NOT");
                sp();
            }
            kw("IN");
            sp();
            out.append('(');
            if (in.subquery() != null) {
                writeNode(in.subquery());
            } else {
                commaExprs(in.values());
            }
            out.append(')');
        } else if (expr instanceof SqlFunctionExpr) {
            writeFunction((SqlFunctionExpr) expr);
        } else if (expr instanceof SqlOverExpr) {
            writeOver((SqlOverExpr) expr);
        } else if (expr instanceof SqlCaseExpr) {
            writeCase((SqlCaseExpr) expr);
        } else if (expr instanceof SqlCastExpr) {
            SqlCastExpr cast = (SqlCastExpr) expr;
            if (cast.postgresStyle()) {
                writeExpr(cast.expr());
                out.append("::");
                out.append(cast.dataType());
            } else {
                kw("CAST");
                out.append('(');
                writeExpr(cast.expr());
                sp();
                kw("AS");
                sp();
                out.append(cast.dataType());
                out.append(')');
            }
        } else if (expr instanceof SqlListExpr) {
            out.append('(');
            commaExprs(((SqlListExpr) expr).items());
            out.append(')');
        } else if (expr instanceof SqlQueryExpr) {
            out.append('(');
            writeNode(((SqlQueryExpr) expr).query());
            out.append(')');
        }
    }

    /**
     * 按方言输出标识符引号：MySQL 反引号、SQL Server {@code []}、其余双引号。
     * 默认仅当 {@link SqlIdentifier#quoted()} 为 true 时加引号；
     * {@link SqlFormatOptions#quoteIdentifiers()} 为 true 时强制加引号。
     */
    private void writeIdentifier(SqlIdentifier id) {
        List<String> names = id.names();
        boolean force = options.quoteIdentifiers();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append('.');
            }
            writeIdentPart(names.get(i), force || id.quoted());
        }
        if (id.dblink() != null) {
            // Oracle DB Link：fn@dblink
            out.append('@');
            out.append(id.dblink());
        }
    }

    private void writeIdentPart(String part, boolean quoted) {
        if (part == null) {
            return;
        }
        if (!quoted) {
            out.append(part);
            return;
        }
        char open = dialect.identQuoteOpen();
        char close = dialect.identQuoteClose();
        out.append(open);
        if (open == '`') {
            out.append(part.replace("`", "``"));
        } else if (open == '"') {
            out.append(part.replace("\"", "\"\""));
        } else if (open == '[') {
            out.append(part.replace("]", "]]"));
        } else {
            out.append(part);
        }
        out.append(close);
    }

    private void writeFunction(SqlFunctionExpr fn) {
        String fnName = fn.name() == null ? "" : fn.name().simpleName();
        List<SqlExpr> args = fn.arguments();
        if (isTypedLiteralName(fnName) && canWriteTypedLiteral(fn)) {
            writeExpr(fn.name());
            sp();
            if (equalsIgnoreCase(fnName, "INTERVAL") && args.size() == 2
                    && args.get(1) instanceof SqlIdentifier) {
                writeExpr(args.get(0));
                sp();
                writeExpr(args.get(1));
            } else {
                writeExpr(args.get(0));
            }
            writeFunctionSuffix(fn);
            return;
        }
        writeExpr(fn.name());
        if (fn.arrayConstructor()) {
            out.append('[');
            commaFunctionArgs(args);
            out.append(']');
            writeFunctionSuffix(fn);
            return;
        }
        if (fn.hasParameters()) {
            out.append('(');
            commaFunctionArgs(fn.parameters());
            out.append(')');
        }
        out.append('(');
        if (fn.distinct()) {
            kw("DISTINCT");
            sp();
        }
        if (fn.usingCharset() && args.size() >= 2) {
            writeExpr(args.get(0));
            sp();
            kw("USING");
            sp();
            writeExpr(args.get(1));
        } else if (equalsIgnoreCase(fnName, "EXTRACT") && args.size() == 2) {
            writeExpr(args.get(0));
            sp();
            kw("FROM");
            sp();
            writeExpr(args.get(1));
        } else if (equalsIgnoreCase(fnName, "TRIM")) {
            writeTrimArgs(args);
        } else if (equalsIgnoreCase(fnName, "SUBSTRING") && args.size() >= 2 && args.size() <= 3
                && substringUsesFromFor()) {
            writeExpr(args.get(0));
            sp();
            kw("FROM");
            sp();
            writeExpr(args.get(1));
            if (args.size() >= 3) {
                sp();
                kw("FOR");
                sp();
                writeExpr(args.get(2));
            }
        } else if (equalsIgnoreCase(fnName, "POSITION") && args.size() == 2) {
            writeExpr(args.get(0));
            sp();
            kw("IN");
            sp();
            writeExpr(args.get(1));
        } else {
            commaFunctionArgs(args);
            if (!fn.withinGroup() && fn.orderBy() != null && !fn.orderBy().isEmpty()) {
                sp();
                kw("ORDER");
                sp();
                kw("BY");
                sp();
                writeOrder(fn.orderBy());
            }
            if (fn.separator() != null) {
                sp();
                kw("SEPARATOR");
                sp();
                writeExpr(fn.separator());
            }
        }
        out.append(')');
        if (fn.against() != null) {
            sp();
            kw("AGAINST");
            out.append(" (");
            writeExpr(fn.against());
            if (fn.againstModifier() != null && fn.againstModifier().length() > 0) {
                sp();
                out.append(fn.againstModifier());
            }
            out.append(')');
        }
        writeFunctionSuffix(fn);
    }

    /**
     * SQL 标准 {@code SUBSTRING(x FROM n FOR m)}：PG / ANSI / MySQL / H2 / Presto / DB2 认。
     * SQL Server / SQLite / Hive / ClickHouse 只认逗号形态 {@code SUBSTRING(x, n, m)}。
     */
    private boolean substringUsesFromFor() {
        SqlDialect family = dialect.typeFamily();
        return family == SqlDialect.MYSQL
                || family == SqlDialect.POSTGRES
                || family == SqlDialect.ANSI
                || family == SqlDialect.H2
                || family == SqlDialect.PRESTO
                || family == SqlDialect.DB2;
    }

    private void writeFunctionSuffix(SqlFunctionExpr fn) {
        if (fn.keepClause() != null && fn.keepClause().length() > 0) {
            sp();
            kw("KEEP");
            sp();
            out.append(fn.keepClause());
        }
        if (fn.withinGroup() && fn.orderBy() != null && !fn.orderBy().isEmpty()) {
            sp();
            kw("WITHIN");
            sp();
            kw("GROUP");
            out.append(" (");
            kw("ORDER");
            sp();
            kw("BY");
            sp();
            writeOrder(fn.orderBy());
            out.append(')');
        } else if (fn.aggOption() != null) {
            sp();
            out.append(fn.aggOption());
        }
        if (fn.filter() != null) {
            sp();
            kw("FILTER");
            out.append(" (");
            kw("WHERE");
            sp();
            writeExpr(fn.filter());
            out.append(')');
        }
        if (fn.over() != null) {
            sp();
            kw("OVER");
            sp();
            writeExpr(fn.over());
        }
    }

    private static boolean isTypedLiteralName(String fnName) {
        return equalsIgnoreCase(fnName, "DATE")
                || equalsIgnoreCase(fnName, "TIME")
                || equalsIgnoreCase(fnName, "TIMESTAMP")
                || equalsIgnoreCase(fnName, "DATETIME")
                || equalsIgnoreCase(fnName, "INTERVAL");
    }

    private static boolean canWriteTypedLiteral(SqlFunctionExpr fn) {
        if (fn.distinct() || fn.over() != null || fn.filter() != null || fn.against() != null
                || fn.separator() != null || fn.usingCharset() || fn.withinGroup()
                || fn.keepClause() != null || fn.hasParameters()
                || (fn.orderBy() != null && !fn.orderBy().isEmpty()) || fn.aggOption() != null) {
            return false;
        }
        List<SqlExpr> args = fn.arguments();
        if (args == null || args.isEmpty()) {
            return false;
        }
        String name = fn.name() == null ? "" : fn.name().simpleName();
        if (equalsIgnoreCase(name, "INTERVAL") && args.size() == 2
                && args.get(1) instanceof SqlIdentifier) {
            return true;
        }
        return args.size() == 1;
    }

    private void writeTrimArgs(List<SqlExpr> args) {
        if (args.isEmpty()) {
            return;
        }
        if (args.size() == 1) {
            writeExpr(args.get(0));
            return;
        }
        if (args.size() == 2) {
            writeExpr(args.get(0));
            sp();
            kw("FROM");
            sp();
            writeExpr(args.get(1));
            return;
        }
        // TRIM(BOTH 'x' FROM name)
        writeExpr(args.get(0));
        sp();
        writeExpr(args.get(1));
        sp();
        kw("FROM");
        sp();
        writeExpr(args.get(2));
        for (int i = 3; i < args.size(); i++) {
            out.append(',');
            sp();
            writeExpr(args.get(i));
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private void writeOver(SqlOverExpr over) {
        if (over.windowName() != null) {
            writeExpr(over.windowName());
            return;
        }
        out.append('(');
        boolean need = false;
        if (over.existingWindowName() != null) {
            writeExpr(over.existingWindowName());
            need = true;
        }
        if (!over.partitionBy().isEmpty()) {
            if (need) {
                sp();
            }
            kw(over.sparkStyle() ? "DISTRIBUTE" : "PARTITION");
            sp();
            kw("BY");
            sp();
            commaExprs(over.partitionBy());
            need = true;
        }
        if (!over.orderBy().isEmpty()) {
            if (need) {
                sp();
            }
            kw(over.sparkStyle() ? "SORT" : "ORDER");
            sp();
            kw("BY");
            sp();
            writeOrder(over.orderBy());
            need = true;
        }
        if (over.frameUnit() != null) {
            if (need) {
                sp();
            }
            kw(over.frameUnit());
            sp();
            if (over.frameEnd() != null) {
                kw("BETWEEN");
                sp();
                out.append(over.frameStart());
                sp();
                kw("AND");
                sp();
                out.append(over.frameEnd());
            } else if (over.frameStart() != null) {
                out.append(over.frameStart());
            }
        }
        out.append(')');
    }

    private void writeCase(SqlCaseExpr cse) {
        kw("CASE");
        if (cse.value() != null) {
            sp();
            writeExpr(cse.value());
        }
        for (int i = 0; i < cse.whenList().size(); i++) {
            sp();
            kw("WHEN");
            sp();
            writeExpr(cse.whenList().get(i));
            sp();
            kw("THEN");
            sp();
            writeExpr(cse.thenList().get(i));
        }
        if (cse.elseExpr() != null) {
            sp();
            kw("ELSE");
            sp();
            writeExpr(cse.elseExpr());
        }
        sp();
        kw("END");
    }

    private void writeLiteral(SqlLiteral lit) {
        switch (lit.kind()) {
            case NULL:
                kw("NULL");
                break;
            case BOOLEAN:
                kw(Boolean.parseBoolean(lit.value()) ? "TRUE" : "FALSE");
                break;
            case BIND:
                out.append('?');
                break;
            case NAMED_BIND:
                out.append(':');
                out.append(lit.name() == null ? lit.value() : lit.name());
                break;
            case VARIABLE:
                out.append(lit.value());
                break;
            case STRING:
                if (lit.name() != null) {
                    // 字符集前缀字面量：_latin1'string'
                    out.append(lit.name());
                }
                out.append(lit.value());
                break;
            case NUMBER:
            case HEX:
            case BIT:
            default:
                out.append(lit.value());
                break;
        }
    }

    private void commaFunctionArgs(List<SqlExpr> exprs) {
        if (exprs == null) {
            return;
        }
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            SqlExpr arg = exprs.get(i);
            if (arg instanceof SqlQueryExpr) {
                writeNode(((SqlQueryExpr) arg).query());
            } else {
                writeExpr(arg);
            }
        }
    }

    private void commaExprs(List<SqlExpr> exprs) {
        if (exprs == null) {
            return;
        }
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            writeExpr(exprs.get(i));
        }
    }

    private void commaIdents(List<SqlIdentifier> ids) {
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            writeExpr(ids.get(i));
        }
    }

    private void commaBinaries(List<SqlBinaryExpr> list) {
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            writeExpr(list.get(i));
        }
    }

    private void kw(String word) {
        // 默认 AS_IS：直接 append，避免无谓分支与大小写转换
        if (keywordCase == SqlKeywordCase.AS_IS || keywordCase == null) {
            out.append(word);
        } else if (keywordCase == SqlKeywordCase.UPPER) {
            out.append(word.toUpperCase(Locale.ROOT));
        } else {
            out.append(word.toLowerCase(Locale.ROOT));
        }
    }

    private void sp() {
        if (out.length() == 0) {
            return;
        }
        char last = out.charAt(out.length() - 1);
        if (last != ' ' && last != '(' && last != '\n') {
            out.append(' ');
        }
    }

    private void nl() {
        if (pretty) {
            out.append('\n');
            for (int i = 0; i < indent; i++) {
                out.append("  ");
            }
        } else {
            sp();
        }
    }
}
