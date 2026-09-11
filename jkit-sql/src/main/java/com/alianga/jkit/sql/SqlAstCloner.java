package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
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

import java.util.ArrayList;
import java.util.List;

/**
 * AST 深拷贝（树拷贝，无 format→parse）。热路径类型靠前分发。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAstCloner {
    private SqlAstCloner() {
    }

    /**
     * 深拷贝任意 AST 节点；null 入参返回 null。
     *
     * @param node 原节点
     * @param <T> 节点类型
     * @return 新树
     */
    @SuppressWarnings("unchecked")
    public static <T extends SqlNode> T copy(T node) {
        if (node == null) {
            return null;
        }
        return (T) copyNode(node);
    }

    /**
     * 深拷贝语句。
     *
     * @param statement 语句
     * @return 新语句
     */
    public static SqlStatement copyStatement(SqlStatement statement) {
        return copy(statement);
    }

    private static SqlNode copyNode(SqlNode node) {
        // 热路径优先
        if (node instanceof SqlSelect) {
            return copySelect((SqlSelect) node);
        }
        if (node instanceof SqlLiteral) {
            return copyLiteral((SqlLiteral) node);
        }
        if (node instanceof SqlIdentifier) {
            return copyIdentifier((SqlIdentifier) node);
        }
        if (node instanceof SqlBinaryExpr) {
            return copyBinaryExpr((SqlBinaryExpr) node);
        }
        if (node instanceof SqlLimit) {
            return copyLimit((SqlLimit) node);
        }
        if (node instanceof SqlSelectItem) {
            return copySelectItem((SqlSelectItem) node);
        }
        if (node instanceof SqlTable) {
            return copyTable((SqlTable) node);
        }
        if (node instanceof SqlSubqueryTable) {
            return copySubqueryTable((SqlSubqueryTable) node);
        }
        if (node instanceof SqlJoin) {
            return copyJoin((SqlJoin) node);
        }
        if (node instanceof SqlAllColumns) {
            return copyAllColumns((SqlAllColumns) node);
        }
        if (node instanceof SqlOrderByItem) {
            return copyOrderByItem((SqlOrderByItem) node);
        }
        if (node instanceof SqlFunctionExpr) {
            return copyFunctionExpr((SqlFunctionExpr) node);
        }
        if (node instanceof SqlUnaryExpr) {
            return copyUnaryExpr((SqlUnaryExpr) node);
        }
        if (node instanceof SqlInExpr) {
            return copyInExpr((SqlInExpr) node);
        }
        if (node instanceof SqlBetweenExpr) {
            return copyBetweenExpr((SqlBetweenExpr) node);
        }
        if (node instanceof SqlCaseExpr) {
            return copyCaseExpr((SqlCaseExpr) node);
        }
        if (node instanceof SqlCastExpr) {
            return copyCastExpr((SqlCastExpr) node);
        }
        if (node instanceof SqlListExpr) {
            return copyListExpr((SqlListExpr) node);
        }
        if (node instanceof SqlQueryExpr) {
            return copyQueryExpr((SqlQueryExpr) node);
        }
        if (node instanceof SqlOverExpr) {
            return copyOverExpr((SqlOverExpr) node);
        }
        if (node instanceof SqlNamedExpr) {
            return copyNamedExpr((SqlNamedExpr) node);
        }
        if (node instanceof SqlWindowDefinition) {
            return copyWindowDefinition((SqlWindowDefinition) node);
        }
        if (node instanceof SqlWithItem) {
            return copyWithItem((SqlWithItem) node);
        }
        if (node instanceof SqlFunctionTable) {
            return copyFunctionTable((SqlFunctionTable) node);
        }
        if (node instanceof SqlValuesTable) {
            return copyValuesTable((SqlValuesTable) node);
        }
        if (node instanceof SqlPivotTable) {
            return copyPivotTable((SqlPivotTable) node);
        }
        if (node instanceof SqlInsert) {
            return copyInsert((SqlInsert) node);
        }
        if (node instanceof SqlUpdate) {
            return copyUpdate((SqlUpdate) node);
        }
        if (node instanceof SqlDelete) {
            return copyDelete((SqlDelete) node);
        }
        if (node instanceof SqlMerge) {
            return copyMerge((SqlMerge) node);
        }
        if (node instanceof SqlMergeWhen) {
            return copyMergeWhen((SqlMergeWhen) node);
        }
        if (node instanceof SqlInsertBranch) {
            return copyInsertBranch((SqlInsertBranch) node);
        }
        if (node instanceof SqlModelClause) {
            return copyModelClause((SqlModelClause) node);
        }
        if (node instanceof SqlModelRule) {
            return copyModelRule((SqlModelRule) node);
        }
        if (node instanceof SqlMatchRecognize) {
            return copyMatchRecognize((SqlMatchRecognize) node);
        }
        if (node instanceof SqlSubset) {
            return copySubset((SqlSubset) node);
        }
        if (node instanceof SqlRoutineParam) {
            return copyRoutineParam((SqlRoutineParam) node);
        }
        if (node instanceof SqlDdlStatement) {
            return copyDdl((SqlDdlStatement) node);
        }
        if (node instanceof SqlSimpleStatement) {
            return copySimple((SqlSimpleStatement) node);
        }
        if (node instanceof SqlSetStatement) {
            return copySet((SqlSetStatement) node);
        }
        if (node instanceof SqlShowStatement) {
            return copyShow((SqlShowStatement) node);
        }
        if (node instanceof SqlExplainStatement) {
            return copyExplain((SqlExplainStatement) node);
        }
        if (node instanceof SqlBlockStatement) {
            return copyBlock((SqlBlockStatement) node);
        }
        if (node instanceof SqlControlStatement) {
            return copyControl((SqlControlStatement) node);
        }
        if (node instanceof SqlDeclareStatement) {
            return copyDeclare((SqlDeclareStatement) node);
        }
        if (node instanceof SqlHandlerStatement) {
            return copyHandler((SqlHandlerStatement) node);
        }
        if (node instanceof SqlPrepareStatement) {
            return copyPrepare((SqlPrepareStatement) node);
        }
        if (node instanceof SqlStartTransactionStatement) {
            return copyStartTx((SqlStartTransactionStatement) node);
        }
        if (node instanceof SqlTransactionControlStatement) {
            return copyTxControl((SqlTransactionControlStatement) node);
        }
        if (node instanceof SqlCommentOnStatement) {
            return copyCommentOn((SqlCommentOnStatement) node);
        }
        if (node instanceof SqlCopyStatement) {
            return copyCopy((SqlCopyStatement) node);
        }
        if (node instanceof SqlLoadDataStatement) {
            return copyLoadData((SqlLoadDataStatement) node);
        }
        if (node instanceof SqlFlushStatement) {
            return copyFlush((SqlFlushStatement) node);
        }
        if (node instanceof SqlLockTablesStatement) {
            return copyLockTables((SqlLockTablesStatement) node);
        }
        if (node instanceof SqlMaintenanceStatement) {
            return copyMaintenance((SqlMaintenanceStatement) node);
        }
        if (node instanceof SqlTableHandlerStatement) {
            return copyTableHandler((SqlTableHandlerStatement) node);
        }
        throw new IllegalArgumentException("unsupported AST node: " + node.getClass().getName());
    }

    private static SqlExpr copyExpr(SqlExpr expr) {
        return copy(expr);
    }

    private static SqlStatement copyStmt(SqlStatement statement) {
        return copy(statement);
    }

    private static SqlTableSource copyTableSource(SqlTableSource source) {
        return copy(source);
    }

    private static void copyStatementBase(SqlStatement src, SqlStatement dest) {
        dest.setWithRecursive(src.withRecursive());
        List<SqlWithItem> withItems = src.withItems();
        if (!withItems.isEmpty()) {
            for (int i = 0; i < withItems.size(); i++) {
                dest.addWithItem(copy(withItems.get(i)));
            }
        }
        List<String> comments = src.comments();
        if (!comments.isEmpty()) {
            for (int i = 0; i < comments.size(); i++) {
                dest.addComment(comments.get(i));
            }
        }
    }

    private static void copyTableSourceBase(SqlTableSource src, SqlTableSource dest) {
        dest.setAlias(src.alias());
        List<SqlIdentifier> cols = src.columnAliases();
        if (!cols.isEmpty()) {
            for (int i = 0; i < cols.size(); i++) {
                dest.columnAliases().add(copy(cols.get(i)));
            }
        }
    }

    private static void copyExprs(List<SqlExpr> src, List<SqlExpr> dest) {
        if (src == null || src.isEmpty()) {
            return;
        }
        for (int i = 0; i < src.size(); i++) {
            dest.add(copyExpr(src.get(i)));
        }
    }

    private static void copyIdents(List<SqlIdentifier> src, List<SqlIdentifier> dest) {
        if (src == null || src.isEmpty()) {
            return;
        }
        for (int i = 0; i < src.size(); i++) {
            dest.add(copy(src.get(i)));
        }
    }

    private static void copyOrderItems(List<SqlOrderByItem> src, List<SqlOrderByItem> dest) {
        if (src == null || src.isEmpty()) {
            return;
        }
        for (int i = 0; i < src.size(); i++) {
            dest.add(copy(src.get(i)));
        }
    }

    private static void copyStrings(List<String> src, List<String> dest) {
        if (src == null || src.isEmpty()) {
            return;
        }
        for (int i = 0; i < src.size(); i++) {
            dest.add(src.get(i));
        }
    }

    private static void copyStmts(List<SqlStatement> src, List<SqlStatement> dest) {
        if (src == null || src.isEmpty()) {
            return;
        }
        for (int i = 0; i < src.size(); i++) {
            dest.add(copyStmt(src.get(i)));
        }
    }

    private static SqlLiteral copyLiteral(SqlLiteral src) {
        SqlLiteral dest = SqlLiteral.of(src.kind(), src.value());
        dest.setName(src.name());
        return dest;
    }

    private static SqlIdentifier copyIdentifier(SqlIdentifier src) {
        SqlIdentifier dest = new SqlIdentifier();
        List<String> names = src.names();
        if (!names.isEmpty()) {
            List<String> copyNames = new ArrayList<String>(names.size());
            for (int i = 0; i < names.size(); i++) {
                copyNames.add(names.get(i));
            }
            dest.setNames(copyNames);
        }
        dest.setQuoted(src.quoted());
        dest.setDblink(src.dblink());
        return dest;
    }

    private static SqlBinaryExpr copyBinaryExpr(SqlBinaryExpr src) {
        return SqlBinaryExpr.of(copyExpr(src.left()), src.operator(), copyExpr(src.right()));
    }

    private static SqlUnaryExpr copyUnaryExpr(SqlUnaryExpr src) {
        SqlUnaryExpr dest = new SqlUnaryExpr();
        dest.setOperator(src.operator());
        dest.setExpr(copyExpr(src.expr()));
        return dest;
    }

    private static SqlLimit copyLimit(SqlLimit src) {
        SqlLimit dest = new SqlLimit();
        dest.setOffset(copyExpr(src.offset()));
        dest.setRowCount(copyExpr(src.rowCount()));
        dest.setMysqlCommaStyle(src.mysqlCommaStyle());
        dest.setFetchStyle(src.fetchStyle());
        return dest;
    }

    private static SqlSelectItem copySelectItem(SqlSelectItem src) {
        SqlSelectItem dest = new SqlSelectItem();
        dest.setExpr(copyExpr(src.expr()));
        dest.setAlias(src.alias());
        copyIdents(src.columnAliases(), dest.columnAliases());
        return dest;
    }

    private static SqlAllColumns copyAllColumns(SqlAllColumns src) {
        SqlAllColumns dest = new SqlAllColumns();
        dest.setOwner(copy(src.owner()));
        return dest;
    }

    private static SqlOrderByItem copyOrderByItem(SqlOrderByItem src) {
        SqlOrderByItem dest = new SqlOrderByItem();
        dest.setExpr(copyExpr(src.expr()));
        dest.setAsc(src.asc());
        dest.setNulls(src.nulls());
        return dest;
    }

    private static SqlBetweenExpr copyBetweenExpr(SqlBetweenExpr src) {
        SqlBetweenExpr dest = new SqlBetweenExpr();
        dest.setExpr(copyExpr(src.expr()));
        dest.setBegin(copyExpr(src.begin()));
        dest.setEnd(copyExpr(src.end()));
        dest.setNot(src.not());
        return dest;
    }

    private static SqlInExpr copyInExpr(SqlInExpr src) {
        SqlInExpr dest = new SqlInExpr();
        dest.setExpr(copyExpr(src.expr()));
        dest.setNot(src.not());
        dest.setSubquery(copyStmt(src.subquery()));
        if (src.values() != null && !src.values().isEmpty()) {
            List<SqlExpr> values = new ArrayList<SqlExpr>(src.values().size());
            copyExprs(src.values(), values);
            dest.setValues(values);
        }
        return dest;
    }

    private static SqlCaseExpr copyCaseExpr(SqlCaseExpr src) {
        SqlCaseExpr dest = new SqlCaseExpr();
        dest.setValue(copyExpr(src.value()));
        copyExprs(src.whenList(), dest.whenList());
        copyExprs(src.thenList(), dest.thenList());
        dest.setElseExpr(copyExpr(src.elseExpr()));
        return dest;
    }

    private static SqlCastExpr copyCastExpr(SqlCastExpr src) {
        SqlCastExpr dest = new SqlCastExpr();
        dest.setExpr(copyExpr(src.expr()));
        dest.setDataType(src.dataType());
        dest.setPostgresStyle(src.postgresStyle());
        return dest;
    }

    private static SqlListExpr copyListExpr(SqlListExpr src) {
        SqlListExpr dest = new SqlListExpr();
        copyExprs(src.items(), dest.items());
        return dest;
    }

    private static SqlQueryExpr copyQueryExpr(SqlQueryExpr src) {
        SqlQueryExpr dest = new SqlQueryExpr();
        dest.setQuery(copyStmt(src.query()));
        return dest;
    }

    private static SqlNamedExpr copyNamedExpr(SqlNamedExpr src) {
        SqlNamedExpr dest = new SqlNamedExpr();
        dest.setName(src.name());
        dest.setExpr(copyExpr(src.expr()));
        dest.setRaw(src.raw());
        dest.setNameFirst(src.nameFirst());
        return dest;
    }

    private static SqlFunctionExpr copyFunctionExpr(SqlFunctionExpr src) {
        SqlFunctionExpr dest = new SqlFunctionExpr();
        dest.setName(copy(src.name()));
        dest.setDistinct(src.distinct());
        dest.setArrayConstructor(src.arrayConstructor());
        dest.setOver(copyExpr(src.over()));
        dest.setFilter(copyExpr(src.filter()));
        dest.setAggOption(src.aggOption());
        dest.setWithinGroup(src.withinGroup());
        dest.setKeepClause(src.keepClause());
        dest.setSeparator(copyExpr(src.separator()));
        dest.setUsingCharset(src.usingCharset());
        dest.setAgainst(copyExpr(src.against()));
        dest.setAgainstModifier(src.againstModifier());
        if (src.hasParameters()) {
            copyExprs(src.parameters(), dest.parameters());
        }
        // arguments()/orderBy() 可能惰性建空列表；空列表对语义无影响
        List<SqlExpr> args = src.arguments();
        if (!args.isEmpty()) {
            copyExprs(args, dest.arguments());
        }
        List<SqlOrderByItem> order = src.orderBy();
        if (!order.isEmpty()) {
            copyOrderItems(order, dest.orderBy());
        }
        return dest;
    }

    private static SqlOverExpr copyOverExpr(SqlOverExpr src) {
        SqlOverExpr dest = new SqlOverExpr();
        dest.setWindowName(copy(src.windowName()));
        dest.setExistingWindowName(copy(src.existingWindowName()));
        copyExprs(src.partitionBy(), dest.partitionBy());
        copyOrderItems(src.orderBy(), dest.orderBy());
        dest.setSparkStyle(src.sparkStyle());
        dest.setFrameUnit(src.frameUnit());
        dest.setFrameStart(src.frameStart());
        dest.setFrameEnd(src.frameEnd());
        return dest;
    }

    private static SqlWindowDefinition copyWindowDefinition(SqlWindowDefinition src) {
        SqlWindowDefinition dest = new SqlWindowDefinition();
        dest.setName(copy(src.name()));
        dest.setSpec(copy(src.spec()));
        return dest;
    }

    private static SqlWithItem copyWithItem(SqlWithItem src) {
        SqlWithItem dest = new SqlWithItem();
        dest.setName(copy(src.name()));
        if (src.columns() != null && !src.columns().isEmpty()) {
            List<SqlIdentifier> cols = new ArrayList<SqlIdentifier>(src.columns().size());
            copyIdents(src.columns(), cols);
            dest.setColumns(cols);
        }
        dest.setQuery(copyStmt(src.query()));
        dest.setSearchClause(src.searchClause());
        dest.setCycleClause(src.cycleClause());
        return dest;
    }

    private static SqlTable copyTable(SqlTable src) {
        SqlTable dest = new SqlTable();
        copyTableSourceBase(src, dest);
        dest.setName(copy(src.name()));
        dest.setIndexHint(src.indexHint());
        dest.setWithHint(src.withHint());
        dest.setOptimizerHint(src.optimizerHint());
        dest.setSampleClause(src.sampleClause());
        dest.setTemporalClause(src.temporalClause());
        dest.setMatchRecognize(copy(src.matchRecognize()));
        copyIdents(src.partitions(), dest.partitions());
        dest.setPartitionBy(src.partitionBy());
        return dest;
    }

    private static SqlSubqueryTable copySubqueryTable(SqlSubqueryTable src) {
        SqlSubqueryTable dest = new SqlSubqueryTable();
        copyTableSourceBase(src, dest);
        dest.setQuery(copyStmt(src.query()));
        dest.setLateral(src.lateral());
        return dest;
    }

    private static SqlJoin copyJoin(SqlJoin src) {
        SqlJoin dest = new SqlJoin();
        copyTableSourceBase(src, dest);
        dest.setJoinType(src.joinType());
        dest.setNatural(src.natural());
        dest.setLeft(copyTableSource(src.left()));
        dest.setRight(copyTableSource(src.right()));
        dest.setCondition(copyExpr(src.condition()));
        if (src.using() != null && !src.using().isEmpty()) {
            List<SqlIdentifier> using = new ArrayList<SqlIdentifier>(src.using().size());
            copyIdents(src.using(), using);
            dest.setUsing(using);
        }
        return dest;
    }

    private static SqlFunctionTable copyFunctionTable(SqlFunctionTable src) {
        SqlFunctionTable dest = new SqlFunctionTable();
        copyTableSourceBase(src, dest);
        dest.setFunction(copyExpr(src.function()));
        dest.setTableKeyword(src.tableKeyword());
        dest.setLateral(src.lateral());
        dest.setWithOrdinality(src.withOrdinality());
        dest.setWithDefinition(src.withDefinition());
        return dest;
    }

    private static SqlValuesTable copyValuesTable(SqlValuesTable src) {
        SqlValuesTable dest = new SqlValuesTable();
        copyTableSourceBase(src, dest);
        copyExprs(src.rows(), dest.rows());
        return dest;
    }

    private static SqlPivotTable copyPivotTable(SqlPivotTable src) {
        SqlPivotTable dest = new SqlPivotTable();
        copyTableSourceBase(src, dest);
        dest.setInput(copyTableSource(src.input()));
        dest.setUnpivot(src.unpivot());
        dest.setDefinition(src.definition());
        dest.setNullsClause(src.nullsClause());
        return dest;
    }

    private static SqlSelect copySelect(SqlSelect src) {
        SqlSelect dest = new SqlSelect();
        copyStatementBase(src, dest);
        dest.setDistinct(src.distinct());
        dest.setDistinctRow(src.distinctRow());
        dest.setHighPriority(src.highPriority());
        dest.setStraightJoin(src.straightJoin());
        dest.setSmallResult(src.smallResult());
        dest.setBigResult(src.bigResult());
        dest.setBufferResult(src.bufferResult());
        dest.setCache(src.cache());
        dest.setNoCache(src.noCache());
        dest.setCalcFoundRows(src.calcFoundRows());
        copyExprs(src.distinctOn(), dest.distinctOn());
        dest.setTop(copyExpr(src.top()));
        dest.setTopWithTies(src.topWithTies());
        List<SqlSelectItem> items = src.selectItems();
        for (int i = 0; i < items.size(); i++) {
            dest.addSelectItem(copy(items.get(i)));
        }
        dest.setFrom(copyTableSource(src.from()));
        dest.setWhere(copyExpr(src.where()));
        copyExprs(src.groupBy(), dest.groupBy());
        dest.setGroupByRollup(src.groupByRollup());
        dest.setGroupByCube(src.groupByCube());
        dest.setGroupByDistinct(src.groupByDistinct());
        dest.setGroupByExtension(src.groupByExtension());
        dest.setOrderSiblings(src.orderSiblings());
        dest.setDistributeBy(src.distributeBy());
        dest.setSortBy(src.sortBy());
        dest.setClusterBy(src.clusterBy());
        dest.setModelClause(copy(src.modelClause()));
        dest.setHaving(copyExpr(src.having()));
        copyOrderItems(src.orderBy(), dest.orderBy());
        dest.setLimit(copy(src.limit()));
        dest.setForUpdate(src.forUpdate());
        dest.setLockInShare(src.lockInShare());
        dest.setForUpdateTail(src.forUpdateTail());
        dest.setConnectByNocycle(src.connectByNocycle());
        dest.setQualify(copyExpr(src.qualify()));
        dest.setQueryOption(src.queryOption());
        copyIdents(src.forUpdateOf(), dest.forUpdateOf());
        dest.setForUpdateWait(src.forUpdateWait());
        dest.setUnion(copy(src.union()));
        dest.setUnionOp(src.unionOp());
        dest.setConnectBy(copyExpr(src.connectBy()));
        dest.setStartWith(copyExpr(src.startWith()));
        List<SqlWindowDefinition> windows = src.windows();
        for (int i = 0; i < windows.size(); i++) {
            dest.windows().add(copy(windows.get(i)));
        }
        dest.setValuesClause(src.valuesClause());
        List<String> hints = src.hints();
        if (!hints.isEmpty()) {
            for (int i = 0; i < hints.size(); i++) {
                dest.addHint(hints.get(i));
            }
        }
        dest.setIntoTable(copy(src.intoTable()));
        copyExprs(src.intoVariables(), dest.intoVariables());
        dest.setIntoOutfile(src.intoOutfile());
        dest.setIntoFileKind(src.intoFileKind());
        dest.setForcePartition(src.forcePartition());
        return dest;
    }

    private static SqlInsert copyInsert(SqlInsert src) {
        SqlInsert dest = new SqlInsert();
        copyStatementBase(src, dest);
        dest.setReplace(src.replace());
        dest.setDelayed(src.delayed());
        dest.setIgnore(src.ignore());
        dest.setLowPriority(src.lowPriority());
        dest.setHighPriority(src.highPriority());
        dest.setOverwrite(src.overwrite());
        dest.setTableKeyword(src.tableKeyword());
        dest.setPartitionRaw(src.partitionRaw());
        dest.setTable(copy(src.table()));
        copyIdents(src.columns(), dest.columns());
        List<List<SqlExpr>> values = src.valuesList();
        for (int i = 0; i < values.size(); i++) {
            List<SqlExpr> row = values.get(i);
            List<SqlExpr> rowCopy = new ArrayList<SqlExpr>(row == null ? 0 : row.size());
            copyExprs(row, rowCopy);
            dest.valuesList().add(rowCopy);
        }
        dest.setQuery(copyStmt(src.query()));
        List<SqlBinaryExpr> setList = src.setList();
        for (int i = 0; i < setList.size(); i++) {
            dest.setList().add(copy(setList.get(i)));
        }
        List<SqlBinaryExpr> dup = src.duplicateUpdates();
        for (int i = 0; i < dup.size(); i++) {
            dest.duplicateUpdates().add(copy(dup.get(i)));
        }
        copyIdents(src.conflictTarget(), dest.conflictTarget());
        dest.setOnConflict(src.onConflict());
        dest.setConflictDoNothing(src.conflictDoNothing());
        dest.setConflictConstraint(copy(src.conflictConstraint()));
        dest.setInsertAll(src.insertAll());
        dest.setInsertFirst(src.insertFirst());
        List<SqlInsertBranch> branches = src.branches();
        for (int i = 0; i < branches.size(); i++) {
            dest.branches().add(copy(branches.get(i)));
        }
        dest.setReturning(copyExpr(src.returning()));
        copyExprs(src.output(), dest.output());
        dest.setOutputInto(copy(src.outputInto()));
        return dest;
    }

    private static SqlInsertBranch copyInsertBranch(SqlInsertBranch src) {
        SqlInsertBranch dest = new SqlInsertBranch();
        dest.setWhen(copyExpr(src.when()));
        dest.setElseBranch(src.elseBranch());
        dest.setTable(copy(src.table()));
        copyIdents(src.columns(), dest.columns());
        copyExprs(src.values(), dest.values());
        return dest;
    }

    private static SqlUpdate copyUpdate(SqlUpdate src) {
        SqlUpdate dest = new SqlUpdate();
        copyStatementBase(src, dest);
        dest.setTable(copyTableSource(src.table()));
        dest.setIgnore(src.ignore());
        dest.setLowPriority(src.lowPriority());
        dest.setForcePartition(src.forcePartition());
        List<SqlBinaryExpr> setList = src.setList();
        for (int i = 0; i < setList.size(); i++) {
            dest.setList().add(copy(setList.get(i)));
        }
        dest.setWhere(copyExpr(src.where()));
        dest.setLimit(copy(src.limit()));
        copyOrderItems(src.orderBy(), dest.orderBy());
        dest.setFrom(copyTableSource(src.from()));
        dest.setReturning(copyExpr(src.returning()));
        copyExprs(src.output(), dest.output());
        dest.setOutputInto(copy(src.outputInto()));
        return dest;
    }

    private static SqlDelete copyDelete(SqlDelete src) {
        SqlDelete dest = new SqlDelete();
        copyStatementBase(src, dest);
        dest.setTable(copyTableSource(src.table()));
        dest.setFrom(copyTableSource(src.from()));
        dest.setUsingKeyword(src.usingKeyword());
        copyIdents(src.targets(), dest.targets());
        dest.setIgnore(src.ignore());
        dest.setLowPriority(src.lowPriority());
        dest.setQuick(src.quick());
        dest.setForcePartition(src.forcePartition());
        dest.setWhere(copyExpr(src.where()));
        dest.setLimit(copy(src.limit()));
        copyOrderItems(src.orderBy(), dest.orderBy());
        dest.setReturning(copyExpr(src.returning()));
        copyExprs(src.output(), dest.output());
        dest.setOutputInto(copy(src.outputInto()));
        return dest;
    }

    private static SqlMerge copyMerge(SqlMerge src) {
        SqlMerge dest = new SqlMerge();
        copyStatementBase(src, dest);
        dest.setInto(copyTableSource(src.into()));
        dest.setUsing(copyTableSource(src.using()));
        dest.setOn(copyExpr(src.on()));
        List<SqlMergeWhen> whens = src.whens();
        for (int i = 0; i < whens.size(); i++) {
            dest.whens().add(copy(whens.get(i)));
        }
        copyExprs(src.output(), dest.output());
        dest.setOutputInto(copy(src.outputInto()));
        return dest;
    }

    private static SqlMergeWhen copyMergeWhen(SqlMergeWhen src) {
        SqlMergeWhen dest = new SqlMergeWhen();
        dest.setKind(src.kind());
        dest.setAndPredicate(copyExpr(src.andPredicate()));
        dest.setUpdate(copy(src.update()));
        dest.setInsert(copy(src.insert()));
        dest.setDelete(src.delete());
        dest.setDeleteWhere(copyExpr(src.deleteWhere()));
        dest.setInsertWhere(copyExpr(src.insertWhere()));
        return dest;
    }

    private static SqlModelClause copyModelClause(SqlModelClause src) {
        SqlModelClause dest = new SqlModelClause();
        dest.setOptions(src.options());
        copyExprs(src.partitionBy(), dest.partitionBy());
        copyExprs(src.dimensionBy(), dest.dimensionBy());
        copyExprs(src.measures(), dest.measures());
        dest.setRules(src.rules());
        List<SqlModelRule> rules = src.ruleEntries();
        for (int i = 0; i < rules.size(); i++) {
            dest.ruleEntries().add(copy(rules.get(i)));
        }
        dest.setRulesModifiers(src.rulesModifiers());
        dest.setTail(src.tail());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlModelRule copyModelRule(SqlModelRule src) {
        SqlModelRule dest = new SqlModelRule();
        dest.setModifiers(src.modifiers());
        dest.setCell(src.cell());
        copyStrings(src.cellDims(), dest.cellDims());
        copyExprs(src.cellDimExprs(), dest.cellDimExprs());
        dest.setValue(copyExpr(src.value()));
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlMatchRecognize copyMatchRecognize(SqlMatchRecognize src) {
        SqlMatchRecognize dest = new SqlMatchRecognize();
        copyExprs(src.partitionBy(), dest.partitionBy());
        copyOrderItems(src.orderBy(), dest.orderBy());
        List<SqlNamedExpr> measures = src.measures();
        for (int i = 0; i < measures.size(); i++) {
            dest.measures().add(copy(measures.get(i)));
        }
        dest.setRowsPerMatch(src.rowsPerMatch());
        dest.setAfterMatch(src.afterMatch());
        dest.setWithin(src.within());
        dest.setPattern(src.pattern());
        List<SqlNamedExpr> define = src.define();
        for (int i = 0; i < define.size(); i++) {
            dest.define().add(copy(define.get(i)));
        }
        List<SqlSubset> subsets = src.subsets();
        for (int i = 0; i < subsets.size(); i++) {
            dest.subsets().add(copy(subsets.get(i)));
        }
        dest.setOptionsRaw(src.optionsRaw());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlSubset copySubset(SqlSubset src) {
        SqlSubset dest = new SqlSubset();
        dest.setName(src.name());
        copyStrings(src.members(), dest.members());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlRoutineParam copyRoutineParam(SqlRoutineParam src) {
        SqlRoutineParam dest = new SqlRoutineParam();
        dest.setMode(src.mode());
        dest.setName(copy(src.name()));
        dest.setTypeRaw(src.typeRaw());
        return dest;
    }

    private static SqlDdlStatement copyDdl(SqlDdlStatement src) {
        SqlDdlStatement dest = new SqlDdlStatement();
        copyStatementBase(src, dest);
        dest.setStatementType(src.type());
        dest.setObjectType(src.objectType());
        dest.setOrReplace(src.orReplace());
        copyIdents(src.names(), dest.names());
        dest.setIfExists(src.ifExists());
        dest.setIfNotExists(src.ifNotExists());
        dest.setQuery(copyStmt(src.query()));
        copyIdents(src.columns(), dest.columns());
        dest.setEngine(src.engine());
        dest.setCharset(src.charset());
        dest.setCollate(src.collate());
        dest.setComment(src.comment());
        dest.setAlterAction(src.alterAction());
        dest.setIndexName(copy(src.indexName()));
        copyIdents(src.indexColumns(), dest.indexColumns());
        dest.setRenameTo(copy(src.renameTo()));
        dest.setColumnDefinition(src.columnDefinition());
        copyStrings(src.columnDefinitions(), dest.columnDefinitions());
        dest.setConstraintName(copy(src.constraintName()));
        dest.setConstraintType(src.constraintType());
        copyIdents(src.referencedTables(), dest.referencedTables());
        dest.setLikeTable(copy(src.likeTable()));
        dest.setTail(src.tail());
        dest.setUserSpec(src.userSpec());
        List<SqlRoutineParam> params = src.parameters();
        for (int i = 0; i < params.size(); i++) {
            dest.parameters().add(copy(params.get(i)));
        }
        copyStmts(src.bodyStatements(), dest.bodyStatements());
        dest.setBodyRaw(src.bodyRaw());
        dest.setReturnsType(src.returnsType());
        dest.setTriggerTiming(src.triggerTiming());
        dest.setTriggerEvent(src.triggerEvent());
        dest.setTriggerTable(copy(src.triggerTable()));
        dest.setTriggerForEach(src.triggerForEach());
        dest.setTriggerOrder(src.triggerOrder());
        dest.setTriggerOther(copy(src.triggerOther()));
        dest.setEventScheduleKind(src.eventScheduleKind());
        dest.setEventScheduleRaw(src.eventScheduleRaw());
        copyIdents(src.triggerUpdateColumns(), dest.triggerUpdateColumns());
        dest.setEventStarts(src.eventStarts());
        dest.setEventEnds(src.eventEnds());
        dest.setEventEnabled(src.eventEnabled());
        dest.setEventComment(src.eventComment());
        dest.setEventOnCompletion(src.eventOnCompletion());
        dest.setEventDisableOnSlave(src.eventDisableOnSlave());
        return dest;
    }

    private static SqlSimpleStatement copySimple(SqlSimpleStatement src) {
        SqlSimpleStatement dest = new SqlSimpleStatement();
        copyStatementBase(src, dest);
        dest.setStatementType(src.type());
        dest.setInner(copyStmt(src.inner()));
        dest.setName(copy(src.name()));
        dest.setValue(copyExpr(src.value()));
        dest.setText(src.text());
        copyExprs(src.arguments(), dest.arguments());
        dest.setWithArguments(src.withArguments());
        dest.setParseError(src.parseError());
        dest.setPrivileges(src.privileges());
        return dest;
    }

    private static SqlSetStatement copySet(SqlSetStatement src) {
        SqlSetStatement dest = new SqlSetStatement();
        copyStatementBase(src, dest);
        dest.setScope(src.scope());
        dest.setSetKind(src.setKind());
        dest.setRaw(src.raw());
        List<SqlSetStatement.Assignment> assignments = src.assignments();
        for (int i = 0; i < assignments.size(); i++) {
            SqlSetStatement.Assignment a = assignments.get(i);
            SqlSetStatement.Assignment b = new SqlSetStatement.Assignment();
            b.setName(copy(a.name()));
            b.setValue(copyExpr(a.value()));
            b.setEqualsSign(a.equalsSign());
            dest.assignments().add(b);
        }
        return dest;
    }

    private static SqlShowStatement copyShow(SqlShowStatement src) {
        SqlShowStatement dest = new SqlShowStatement();
        copyStatementBase(src, dest);
        dest.setShowKind(src.showKind());
        dest.setObjectType(src.objectType());
        dest.setName(copy(src.name()));
        dest.setFromOrIn(src.fromOrIn());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlExplainStatement copyExplain(SqlExplainStatement src) {
        SqlExplainStatement dest = new SqlExplainStatement();
        copyStatementBase(src, dest);
        dest.setDescribe(src.describe());
        dest.setAnalyze(src.analyze());
        dest.setFormat(src.format());
        copyStrings(src.options(), dest.options());
        dest.setStatement(copyStmt(src.statement()));
        dest.setName(copy(src.name()));
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlBlockStatement copyBlock(SqlBlockStatement src) {
        SqlBlockStatement dest = new SqlBlockStatement();
        copyStatementBase(src, dest);
        dest.setWithDeclare(src.withDeclare());
        copyStmts(src.declares(), dest.declares());
        copyStmts(src.bodyStatements(), dest.bodyStatements());
        dest.setDeclareRaw(src.declareRaw());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlControlStatement copyControl(SqlControlStatement src) {
        SqlControlStatement dest = new SqlControlStatement();
        copyStatementBase(src, dest);
        dest.setKind(src.kind());
        dest.setLabel(src.label());
        dest.setCondition(copyExpr(src.condition()));
        copyStmts(src.bodyStatements(), dest.bodyStatements());
        List<SqlControlStatement> elseIfs = src.elseIfs();
        for (int i = 0; i < elseIfs.size(); i++) {
            dest.elseIfs().add(copy(elseIfs.get(i)));
        }
        copyStmts(src.elseStatements(), dest.elseStatements());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlDeclareStatement copyDeclare(SqlDeclareStatement src) {
        SqlDeclareStatement dest = new SqlDeclareStatement();
        copyStatementBase(src, dest);
        dest.setKind(src.kind());
        copyIdents(src.names(), dest.names());
        dest.setTypeRaw(src.typeRaw());
        dest.setDefaultValue(copyExpr(src.defaultValue()));
        dest.setConditionFor(src.conditionFor());
        dest.setCursorQuery(copyStmt(src.cursorQuery()));
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlHandlerStatement copyHandler(SqlHandlerStatement src) {
        SqlHandlerStatement dest = new SqlHandlerStatement();
        copyStatementBase(src, dest);
        dest.setAction(src.action());
        copyStrings(src.conditions(), dest.conditions());
        copyStmts(src.bodyStatements(), dest.bodyStatements());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlPrepareStatement copyPrepare(SqlPrepareStatement src) {
        SqlPrepareStatement dest = new SqlPrepareStatement();
        copyStatementBase(src, dest);
        dest.setKind(src.kind());
        dest.setName(copy(src.name()));
        dest.setSource(copyExpr(src.source()));
        copyExprs(src.usingBinds(), dest.usingBinds());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlStartTransactionStatement copyStartTx(SqlStartTransactionStatement src) {
        SqlStartTransactionStatement dest = new SqlStartTransactionStatement();
        copyStatementBase(src, dest);
        dest.setBeginForm(src.beginForm());
        dest.setWork(src.work());
        dest.setIsolationLevel(src.isolationLevel());
        dest.setReadOnly(src.readOnly());
        dest.setConsistentSnapshot(src.consistentSnapshot());
        dest.setDeferrable(src.deferrable());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlTransactionControlStatement copyTxControl(SqlTransactionControlStatement src) {
        SqlTransactionControlStatement dest = new SqlTransactionControlStatement();
        copyStatementBase(src, dest);
        dest.setKind(src.kind());
        dest.setSavepoint(copy(src.savepoint()));
        dest.setToSavepoint(src.toSavepoint());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlCommentOnStatement copyCommentOn(SqlCommentOnStatement src) {
        SqlCommentOnStatement dest = new SqlCommentOnStatement();
        copyStatementBase(src, dest);
        dest.setObjectKind(src.objectKind());
        dest.setName(copy(src.name()));
        dest.setComment(copyExpr(src.comment()));
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlCopyStatement copyCopy(SqlCopyStatement src) {
        SqlCopyStatement dest = new SqlCopyStatement();
        copyStatementBase(src, dest);
        dest.setTable(copy(src.table()));
        copyIdents(src.columns(), dest.columns());
        dest.setTo(src.to());
        dest.setSourceKind(src.sourceKind());
        dest.setSource(copyExpr(src.source()));
        dest.setWithClause(src.withClause());
        dest.setQuery(src.query());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlLoadDataStatement copyLoadData(SqlLoadDataStatement src) {
        SqlLoadDataStatement dest = new SqlLoadDataStatement();
        copyStatementBase(src, dest);
        dest.setLocal(src.local());
        dest.setPriority(src.priority());
        dest.setDuplicateMode(src.duplicateMode());
        dest.setFileName(copyExpr(src.fileName()));
        dest.setTable(copy(src.table()));
        copyIdents(src.columns(), dest.columns());
        dest.setCharacterSet(src.characterSet());
        dest.setFieldsClause(src.fieldsClause());
        dest.setLinesClause(src.linesClause());
        dest.setIgnoreClause(src.ignoreClause());
        dest.setTail(src.tail());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlFlushStatement copyFlush(SqlFlushStatement src) {
        SqlFlushStatement dest = new SqlFlushStatement();
        copyStatementBase(src, dest);
        dest.setNoWriteToBinlog(src.noWriteToBinlog());
        copyStrings(src.options(), dest.options());
        copyIdents(src.tables(), dest.tables());
        dest.setTablesModifier(src.tablesModifier());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlLockTablesStatement copyLockTables(SqlLockTablesStatement src) {
        SqlLockTablesStatement dest = new SqlLockTablesStatement();
        copyStatementBase(src, dest);
        dest.setUnlock(src.unlock());
        dest.setRaw(src.raw());
        List<SqlLockTablesStatement.LockItem> items = src.items();
        for (int i = 0; i < items.size(); i++) {
            SqlLockTablesStatement.LockItem a = items.get(i);
            SqlLockTablesStatement.LockItem b = new SqlLockTablesStatement.LockItem();
            b.setTable(copy(a.table()));
            b.setAlias(a.alias());
            b.setLockMode(a.lockMode());
            dest.items().add(b);
        }
        return dest;
    }

    private static SqlMaintenanceStatement copyMaintenance(SqlMaintenanceStatement src) {
        SqlMaintenanceStatement dest = new SqlMaintenanceStatement();
        copyStatementBase(src, dest);
        dest.setKind(src.kind());
        copyIdents(src.tables(), dest.tables());
        dest.setOptionsRaw(src.optionsRaw());
        dest.setRaw(src.raw());
        return dest;
    }

    private static SqlTableHandlerStatement copyTableHandler(SqlTableHandlerStatement src) {
        SqlTableHandlerStatement dest = new SqlTableHandlerStatement();
        copyStatementBase(src, dest);
        dest.setTable(copy(src.table()));
        dest.setOperation(src.operation());
        dest.setAlias(src.alias());
        dest.setIndexName(copy(src.indexName()));
        dest.setReadDirection(src.readDirection());
        dest.setKeyRaw(src.keyRaw());
        dest.setWhere(copyExpr(src.where()));
        dest.setLimit(copy(src.limit()));
        dest.setRaw(src.raw());
        return dest;
    }
}
