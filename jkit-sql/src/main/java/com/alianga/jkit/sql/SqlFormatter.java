package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlInsertBranch;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlOverExpr;
import com.alianga.jkit.sql.ast.SqlQueryExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.ast.SqlWindowDefinition;
import com.alianga.jkit.sql.ast.SqlWithItem;

import java.util.List;

/**
 * 把 AST 打回 SQL。pretty 模式换行缩进；compact 模式只保留必要空格。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlFormatter {
    private final StringBuilder out = new StringBuilder(128);
    private final boolean pretty;
    private final SqlDialect dialect;
    private int indent;

    /**
     * @param pretty 是否换行缩进
     * @param dialect 方言（影响 LIMIT / 引号）
     */
    public SqlFormatter(boolean pretty, SqlDialect dialect) {
        this.pretty = pretty;
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
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
        } else if (node instanceof SqlSimpleStatement) {
            writeSimple((SqlSimpleStatement) node);
        } else if (node instanceof SqlExpr) {
            writeExpr((SqlExpr) node);
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
            SqlWithItem item = items.get(i);
            writeExpr(item.name());
            sp();
            kw("AS");
            sp();
            out.append('(');
            writeNode(item.query());
            out.append(')');
        }
        nl();
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
            kw("DISTINCT");
            if (!select.distinctOn().isEmpty()) {
                sp();
                kw("ON");
                sp();
                out.append('(');
                commaExprs(select.distinctOn());
                out.append(')');
            }
        }
        if (select.top() != null) {
            sp();
            kw("TOP");
            sp();
            writeExpr(select.top());
        }
        sp();
        List<SqlSelectItem> items = select.selectItems();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            SqlSelectItem item = items.get(i);
            writeExpr(item.expr());
            if (item.alias() != null) {
                sp();
                kw("AS");
                sp();
                out.append(item.alias());
            }
        }
        if (select.from() != null) {
            nl();
            kw("FROM");
            sp();
            writeFrom(select.from());
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
            sp();
            writeExpr(select.connectBy());
        }
        if (!select.groupBy().isEmpty()) {
            nl();
            kw("GROUP");
            sp();
            kw("BY");
            sp();
            commaExprs(select.groupBy());
            if (select.groupByRollup()) {
                sp();
                kw("WITH");
                sp();
                kw("ROLLUP");
            }
        }
        if (select.having() != null) {
            nl();
            kw("HAVING");
            sp();
            writeExpr(select.having());
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
                SqlWindowDefinition window = windows.get(i);
                writeExpr(window.name());
                sp();
                kw("AS");
                sp();
                writeOver(window.spec());
            }
        }
        if (!select.orderBy().isEmpty()) {
            nl();
            kw("ORDER");
            sp();
            kw("BY");
            sp();
            writeOrder(select.orderBy());
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
        if (insert.table() != null) {
            sp();
            kw("INTO");
            sp();
            writeFrom(insert.table());
        }
        if (!insert.columns().isEmpty()) {
            out.append('(');
            commaIdents(insert.columns());
            out.append(')');
        }
        writeOutput(insert.output());
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

    private void writeOutput(List<SqlExpr> output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        sp();
        kw("OUTPUT");
        sp();
        commaExprs(output);
    }

    private void writeUpdate(SqlUpdate update) {
        writeWith(update);
        kw("UPDATE");
        if (update.table() != null) {
            sp();
            writeFrom(update.table());
        }
        sp();
        kw("SET");
        sp();
        commaBinaries(update.setList());
        writeOutput(update.output());
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
        if (delete.table() != null) {
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
        if (delete.from() != null) {
            sp();
            kw(delete.usingKeyword() ? "USING" : "FROM");
            sp();
            writeFrom(delete.from());
        }
        writeOutput(delete.output());
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
        writeOutput(merge.output());
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
        } else if (when.insert() != null) {
            writeInsert(when.insert());
        } else if (when.delete()) {
            kw("DELETE");
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
        if (ddl.type() == SqlStatementType.CREATE && isTableDdl(ddl) && !ddl.columns().isEmpty()
                && ddl.query() == null) {
            sp();
            out.append('(');
            commaIdents(ddl.columns());
            out.append(')');
        }
        if (ddl.type() == SqlStatementType.ALTER) {
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
        } else if (ddl.tail() != null && ddl.type() != SqlStatementType.ALTER) {
            sp();
            out.append(ddl.tail());
        }
        // ALTER 的 tail 由 writeAlterClauses 输出，避免重复
    }

    private void writeAlterClauses(SqlDdlStatement ddl) {
        if (ddl.alterAction() == null) {
            if (ddl.tail() != null) {
                sp();
                out.append(ddl.tail());
            }
            return;
        }
        sp();
        out.append(ddl.alterAction());
        if (ddl.renameTo() != null) {
            sp();
            writeExpr(ddl.renameTo());
            return;
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
        if (!ddl.columns().isEmpty() && ddl.indexName() == null) {
            sp();
            commaIdents(ddl.columns());
        }
        if (ddl.tail() != null) {
            sp();
            out.append(ddl.tail());
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

    private void writeSimple(SqlSimpleStatement stmt) {
        // OTHER（BEGIN/DECLARE/ANALYZE/COMMENT ON 等）：text 已是完整语句
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
            if (table.indexHint() != null) {
                sp();
                out.append(table.indexHint());
            }
            if (table.optimizerHint() != null) {
                sp();
                out.append(table.optimizerHint());
            }
        } else if (source instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) source;
            writeFrom(join.left());
            sp();
            writeJoinType(join.joinType());
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
            out.append(source.alias());
            if (!source.columnAliases().isEmpty()) {
                out.append('(');
                commaIdents(source.columnAliases());
                out.append(')');
            }
        }
    }

    private void writeJoinType(SqlJoin.Type type) {
        if (type == null || type == SqlJoin.Type.INNER) {
            kw("JOIN");
            return;
        }
        switch (type) {
            case LEFT:
                kw("LEFT");
                sp();
                kw("JOIN");
                break;
            case RIGHT:
                kw("RIGHT");
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
        kw("LIMIT");
        sp();
        if (limit.mysqlCommaStyle() && limit.offset() != null) {
            writeExpr(limit.offset());
            out.append(',');
            writeExpr(limit.rowCount());
            return;
        }
        if (limit.rowCount() != null) {
            writeExpr(limit.rowCount());
        }
        if (limit.offset() != null) {
            sp();
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
            commaExprs(((SqlListExpr) expr).items());
        } else {
            writeExpr(expr);
        }
    }

    private void writeOrder(List<SqlOrderByItem> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
                sp();
            }
            SqlOrderByItem item = items.get(i);
            writeExpr(item.expr());
            sp();
            kw(item.asc() ? "ASC" : "DESC");
            if (item.nulls() != null) {
                sp();
                out.append(item.nulls());
            }
        }
    }

    private void writeExpr(SqlExpr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SqlLiteral) {
            writeLiteral((SqlLiteral) expr);
        } else if (expr instanceof SqlIdentifier) {
            out.append(((SqlIdentifier) expr).qualifiedName());
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
            } else {
                writeExpr(bin.left());
                sp();
                out.append(bin.operator().symbol());
                sp();
                writeExpr(bin.right());
            }
        } else if (expr instanceof SqlUnaryExpr) {
            SqlUnaryExpr u = (SqlUnaryExpr) expr;
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
            }
            writeExpr(u.expr());
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
        } else if (equalsIgnoreCase(fnName, "SUBSTRING") && args.size() >= 2 && args.size() <= 3) {
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

    private void writeFunctionSuffix(SqlFunctionExpr fn) {
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
            kw("PARTITION");
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
            kw("ORDER");
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
        out.append(word);
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
