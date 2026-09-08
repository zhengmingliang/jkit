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

import java.util.ArrayList;
import java.util.List;

/**
 * 递归下降 SQL 解析器。实例可 {@link #reset} 后复用，配合 {@link ThreadLocal} 降低分配。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlParser {
    private final SqlLexer lexer = new SqlLexer();
    private SqlToken token;
    private SqlDialect dialect;

    /**
     * 绑定输入。
     *
     * @param sql SQL
     * @param dialect 方言
     */
    public void reset(String sql, SqlDialect dialect) {
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        lexer.reset(sql, this.dialect);
        next();
    }

    /**
     * 解析全部语句。
     *
     * @return 语句列表
     */
    public List<SqlStatement> parseAll() {
        List<SqlStatement> list = new ArrayList<SqlStatement>(1);
        while (!is(SqlTokenType.EOF)) {
            while (isStmtSeparator()) {
                next();
            }
            if (is(SqlTokenType.EOF)) {
                break;
            }
            list.add(parseStatement());
            if (isStmtSeparator()) {
                next();
            }
        }
        return list;
    }

    /**
     * 解析一条语句。
     *
     * @return 语句
     */
    public SqlStatement parseStatement() {
        if (is(SqlTokenType.WITH)) {
            return parseWith();
        }
        return parseStatementNoWith();
    }

    private SqlStatement parseStatementNoWith() {
        SqlTokenType t = token.type();
        switch (t) {
            case SELECT:
            case VALUES:
                return parseSelect();
            case INSERT:
                return parseInsert(false);
            case REPLACE:
                return parseInsert(true);
            case UPDATE:
                return parseUpdate();
            case DELETE:
                return parseDelete();
            case MERGE:
                return parseMerge();
            case CREATE:
                return parseCreate();
            case DROP:
                return parseDrop();
            case ALTER:
            case RENAME:
                return parseAlter();
            case TRUNCATE:
                return parseTruncate();
            case EXPLAIN:
            case DESCRIBE:
            case DESC:
                return parseExplain();
            case SET:
                return parseSet();
            case USE:
                return parseUse();
            case SHOW:
                return parseShow();
            case CALL:
                return parseCall();
            case GRANT:
            case REVOKE:
                return parseGrant();
            case BEGIN:
                return parseBeginBlock();
            case DECLARE:
                return parseDeclare();
            case ANALYZE:
            case VACUUM:
            case OPTIMIZE:
            case REPAIR:
            case CHECK:
                return parseMaintenance();
            case COMMENT:
                return parseCommentOn();
            case LPAREN:
                return parseSelect();
            default:
                throw error("unsupported statement starting with " + t);
        }
    }

    private SqlStatement parseWith() {
        next();
        boolean recursive = match(SqlTokenType.RECURSIVE);
        List<SqlWithItem> items = new ArrayList<SqlWithItem>(2);
        do {
            SqlWithItem item = new SqlWithItem();
            item.setName(parseName());
            if (match(SqlTokenType.LPAREN)) {
                List<SqlIdentifier> cols = new ArrayList<SqlIdentifier>(2);
                do {
                    cols.add(parseName());
                } while (match(SqlTokenType.COMMA));
                expect(SqlTokenType.RPAREN);
                item.setColumns(cols);
            }
            expect(SqlTokenType.AS);
            expect(SqlTokenType.LPAREN);
            item.setQuery(parseStatement());
            expect(SqlTokenType.RPAREN);
            items.add(item);
        } while (match(SqlTokenType.COMMA));
        SqlStatement body = parseStatementNoWith();
        body.setWithItems(items);
        body.setWithRecursive(recursive);
        return body;
    }

    private SqlSelect parseSelect() {
        if (is(SqlTokenType.LPAREN)) {
            next();
            SqlSelect inner = parseSelect();
            expect(SqlTokenType.RPAREN);
            parseSelectTail(inner);
            return inner;
        }
        if (is(SqlTokenType.VALUES)) {
            return parseValuesSelect();
        }
        expect(SqlTokenType.SELECT);
        SqlSelect select = new SqlSelect();
        if (match(SqlTokenType.DISTINCT) || match(SqlTokenType.DISTINCTROW)) {
            select.setDistinct(true);
            if (match(SqlTokenType.ON)) {
                expect(SqlTokenType.LPAREN);
                do {
                    select.distinctOn().add(parseExpr());
                } while (match(SqlTokenType.COMMA));
                expect(SqlTokenType.RPAREN);
            }
        } else {
            match(SqlTokenType.ALL);
        }
        match(SqlTokenType.HIGH_PRIORITY);
        match(SqlTokenType.SQL_CALC_FOUND_ROWS);
        if (match(SqlTokenType.TOP)) {
            select.setTop(parsePrimary());
            match(SqlTokenType.PERCENT);
        }
        do {
            select.addSelectItem(parseSelectItem());
        } while (match(SqlTokenType.COMMA));
        if (match(SqlTokenType.FROM)) {
            select.setFrom(parseJoinedTable());
        }
        if (match(SqlTokenType.WHERE)) {
            select.setWhere(parseExpr());
        }
        if (match(SqlTokenType.START)) {
            expect(SqlTokenType.WITH);
            select.setStartWith(parseExpr());
        }
        if (match(SqlTokenType.CONNECT)) {
            expect(SqlTokenType.BY);
            match(SqlTokenType.NOWAIT);
            select.setConnectBy(parseExpr());
        }
        if (match(SqlTokenType.GROUP)) {
            expect(SqlTokenType.BY);
            do {
                select.groupBy().add(parseExpr());
            } while (match(SqlTokenType.COMMA));
            if (match(SqlTokenType.WITH) && match(SqlTokenType.ROLLUP)) {
                select.setGroupByRollup(true);
            }
        }
        if (match(SqlTokenType.HAVING)) {
            select.setHaving(parseExpr());
        }
        if (match(SqlTokenType.WINDOW)) {
            do {
                SqlWindowDefinition window = new SqlWindowDefinition();
                window.setName(parseName());
                expect(SqlTokenType.AS);
                window.setSpec(parseOver());
                select.windows().add(window);
            } while (match(SqlTokenType.COMMA));
        }
        if (match(SqlTokenType.ORDER)) {
            expect(SqlTokenType.BY);
            parseOrderBy(select.orderBy());
        }
        parseLimitFetch(select);
        if (match(SqlTokenType.FOR)) {
            expect(SqlTokenType.UPDATE);
            select.setForUpdate(true);
            if (match(SqlTokenType.OF)) {
                do {
                    select.forUpdateOf().add(parseName());
                } while (match(SqlTokenType.COMMA));
            }
            if (match(SqlTokenType.NOWAIT)) {
                select.setForUpdateWait("NOWAIT");
            } else if (match(SqlTokenType.SKIP)) {
                expect(SqlTokenType.LOCKED);
                select.setForUpdateWait("SKIP LOCKED");
            } else if (!is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF)
                    && !is(SqlTokenType.UNION) && !is(SqlTokenType.INTERSECT)
                    && !is(SqlTokenType.EXCEPT) && !is(SqlTokenType.MINUS)
                    && !is(SqlTokenType.LOCK) && !isAliasStop(token.type(), false)) {
                select.setForUpdateTail(consumeRawUntilClause());
            }
        }
        if (is(SqlTokenType.LOCK)) {
            next();
            expect(SqlTokenType.IN);
            expect(SqlTokenType.SHARE);
            expect(SqlTokenType.MODE);
            select.setLockInShare(true);
        }
        parseSelectTail(select);
        return select;
    }

    private void parseSelectTail(SqlSelect select) {
        if (is(SqlTokenType.UNION) || is(SqlTokenType.INTERSECT)
                || is(SqlTokenType.EXCEPT) || is(SqlTokenType.MINUS)) {
            String op = token.text().toUpperCase();
            next();
            if (match(SqlTokenType.ALL)) {
                op = op + " ALL";
            } else {
                match(SqlTokenType.DISTINCT);
            }
            select.setUnionOp(op);
            select.setUnion(parseSelect());
        }
    }

    private SqlSelect parseValuesSelect() {
        SqlSelect select = new SqlSelect();
        select.setValuesClause(true);
        SqlFunctionExpr values = new SqlFunctionExpr();
        values.setName(SqlIdentifier.of("VALUES"));
        expect(SqlTokenType.VALUES);
        do {
            values.addArgument(parsePrimary());
        } while (match(SqlTokenType.COMMA));
        SqlSelectItem item = new SqlSelectItem();
        item.setExpr(values);
        select.addSelectItem(item);
        return select;
    }

    private SqlSelectItem parseSelectItem() {
        SqlSelectItem item = new SqlSelectItem();
        if (is(SqlTokenType.STAR)) {
            next();
            item.setExpr(new SqlAllColumns());
            return item;
        }
        SqlExpr expr = parseExpr();
        if (expr instanceof SqlIdentifier && match(SqlTokenType.DOT) && match(SqlTokenType.STAR)) {
            SqlAllColumns all = new SqlAllColumns();
            all.setOwner((SqlIdentifier) expr);
            item.setExpr(all);
        } else {
            item.setExpr(expr);
            item.setAlias(parseAlias(false));
        }
        return item;
    }

    private SqlInsert parseInsert(boolean replace) {
        next();
        match(SqlTokenType.IGNORE);
        if (!replace && (is(SqlTokenType.ALL) || is(SqlTokenType.FIRST))) {
            return parseMultiInsert();
        }
        match(SqlTokenType.INTO);
        // Hive / 部分引擎：INSERT INTO TABLE t
        match(SqlTokenType.TABLE);
        SqlInsert insert = new SqlInsert();
        insert.setReplace(replace);
        insert.setTable(SqlTable.of(parseName()));
        parseTableHints(insert.table());
        if (match(SqlTokenType.LPAREN) && !isQueryStart()) {
            if (!is(SqlTokenType.RPAREN)) {
                do {
                    insert.columns().add(parseName());
                } while (match(SqlTokenType.COMMA));
            }
            expect(SqlTokenType.RPAREN);
        }
        parseOutputClause(insert.output());
        if (match(SqlTokenType.SET)) {
            parseAssignList(insert.setList());
        } else if (is(SqlTokenType.SELECT) || is(SqlTokenType.WITH) || is(SqlTokenType.LPAREN)) {
            insert.setQuery(parseStatement());
        } else if (match(SqlTokenType.VALUES) || match(SqlTokenType.VALUE)) {
            parseValuesRows(insert);
        }
        parseOnConflictOrDuplicate(insert);
        if (match(SqlTokenType.RETURNING)) {
            insert.setReturning(parseExpr());
        }
        if (insert.output().isEmpty()) {
            parseOutputClause(insert.output());
        }
        return insert;
    }

    /**
     * Oracle INSERT ALL / INSERT FIRST … SELECT。
     */
    private SqlInsert parseMultiInsert() {
        SqlInsert insert = new SqlInsert();
        if (match(SqlTokenType.ALL)) {
            insert.setInsertAll(true);
        } else {
            expect(SqlTokenType.FIRST);
            insert.setInsertFirst(true);
        }
        while (is(SqlTokenType.WHEN) || is(SqlTokenType.INTO) || is(SqlTokenType.ELSE)) {
            SqlInsertBranch branch = new SqlInsertBranch();
            if (match(SqlTokenType.ELSE)) {
                branch.setElseBranch(true);
            } else if (match(SqlTokenType.WHEN)) {
                branch.setWhen(parseExpr());
                expect(SqlTokenType.THEN);
            }
            expect(SqlTokenType.INTO);
            match(SqlTokenType.TABLE);
            branch.setTable(SqlTable.of(parseName()));
            if (match(SqlTokenType.LPAREN) && !isQueryStart()) {
                if (!is(SqlTokenType.RPAREN)) {
                    do {
                        branch.columns().add(parseName());
                    } while (match(SqlTokenType.COMMA));
                }
                expect(SqlTokenType.RPAREN);
            }
            expect(SqlTokenType.VALUES);
            expect(SqlTokenType.LPAREN);
            if (!is(SqlTokenType.RPAREN)) {
                do {
                    branch.values().add(parseExpr());
                } while (match(SqlTokenType.COMMA));
            }
            expect(SqlTokenType.RPAREN);
            insert.branches().add(branch);
            if (branch.elseBranch()) {
                break;
            }
        }
        if (insert.branches().isEmpty()) {
            throw error("expected INTO after INSERT ALL/FIRST");
        }
        if (is(SqlTokenType.SELECT) || is(SqlTokenType.WITH) || is(SqlTokenType.LPAREN)) {
            insert.setQuery(parseStatement());
        } else {
            throw error("expected SELECT after INSERT ALL/FIRST branches");
        }
        return insert;
    }

    private void parseOnConflictOrDuplicate(SqlInsert insert) {
        if (!match(SqlTokenType.ON)) {
            return;
        }
        if (match(SqlTokenType.DUPLICATE)) {
            expect(SqlTokenType.KEY);
            expect(SqlTokenType.UPDATE);
            parseAssignList(insert.duplicateUpdates());
            return;
        }
        if (match(SqlTokenType.CONFLICT)) {
            insert.setOnConflict(true);
            if (match(SqlTokenType.LPAREN)) {
                do {
                    insert.conflictTarget().add(parseName());
                } while (match(SqlTokenType.COMMA));
                expect(SqlTokenType.RPAREN);
            } else if (match(SqlTokenType.ON)) {
                expect(SqlTokenType.CONSTRAINT);
                insert.setConflictConstraint(parseName());
            }
            expect(SqlTokenType.DO);
            if (match(SqlTokenType.NOTHING)) {
                insert.setConflictDoNothing(true);
            } else {
                expect(SqlTokenType.UPDATE);
                expect(SqlTokenType.SET);
                parseAssignList(insert.duplicateUpdates());
            }
            return;
        }
        throw error("expected DUPLICATE KEY or CONFLICT after ON");
    }

    private void parseOutputClause(List<SqlExpr> target) {
        if (!match(SqlTokenType.OUTPUT)) {
            return;
        }
        do {
            target.add(parseExpr());
        } while (match(SqlTokenType.COMMA));
        // OUTPUT … INTO @table / table — 暂不结构化，跳过 INTO 后的简单表名
        if (match(SqlTokenType.INTO)) {
            parseName();
        }
    }

    private void parseValuesRows(SqlInsert insert) {
        do {
            expect(SqlTokenType.LPAREN);
            List<SqlExpr> row = new ArrayList<SqlExpr>(4);
            if (!is(SqlTokenType.RPAREN)) {
                do {
                    row.add(parseExpr());
                } while (match(SqlTokenType.COMMA));
            }
            expect(SqlTokenType.RPAREN);
            insert.valuesList().add(row);
        } while (match(SqlTokenType.COMMA));
    }

    private SqlUpdate parseUpdate() {
        expect(SqlTokenType.UPDATE);
        match(SqlTokenType.IGNORE);
        SqlUpdate update = new SqlUpdate();
        update.setTable(parseJoinedTable());
        expect(SqlTokenType.SET);
        parseAssignList(update.setList());
        parseOutputClause(update.output());
        if (match(SqlTokenType.FROM)) {
            update.setFrom(parseJoinedTable());
        }
        if (match(SqlTokenType.WHERE)) {
            update.setWhere(parseExpr());
        }
        if (match(SqlTokenType.ORDER)) {
            expect(SqlTokenType.BY);
            parseOrderBy(update.orderBy());
        }
        if (is(SqlTokenType.LIMIT)) {
            update.setLimit(parseLimit());
        }
        if (match(SqlTokenType.RETURNING)) {
            update.setReturning(parseExpr());
        }
        if (update.output().isEmpty()) {
            parseOutputClause(update.output());
        }
        return update;
    }

    private SqlDelete parseDelete() {
        expect(SqlTokenType.DELETE);
        match(SqlTokenType.IGNORE);
        SqlDelete delete = new SqlDelete();
        if (match(SqlTokenType.FROM)) {
            delete.setTable(parseJoinedTable());
            // PG: DELETE FROM t USING s WHERE …
            if (match(SqlTokenType.USING)) {
                delete.setFrom(parseJoinedTable());
                delete.setUsingKeyword(true);
            }
        } else if (identLike()) {
            delete.setTable(parseJoinedTable());
            if (match(SqlTokenType.USING)) {
                delete.setFrom(parseJoinedTable());
                delete.setUsingKeyword(true);
            } else if (match(SqlTokenType.FROM)) {
                delete.setFrom(parseJoinedTable());
            }
        }
        parseOutputClause(delete.output());
        if (match(SqlTokenType.WHERE)) {
            delete.setWhere(parseExpr());
        }
        if (match(SqlTokenType.ORDER)) {
            expect(SqlTokenType.BY);
            parseOrderBy(delete.orderBy());
        }
        if (is(SqlTokenType.LIMIT)) {
            delete.setLimit(parseLimit());
        }
        if (match(SqlTokenType.RETURNING)) {
            delete.setReturning(parseExpr());
        }
        if (delete.output().isEmpty()) {
            parseOutputClause(delete.output());
        }
        return delete;
    }

    private SqlMerge parseMerge() {
        expect(SqlTokenType.MERGE);
        expect(SqlTokenType.INTO);
        SqlMerge merge = new SqlMerge();
        merge.setInto(parseTableSource());
        expect(SqlTokenType.USING);
        merge.setUsing(parseTableSource());
        expect(SqlTokenType.ON);
        merge.setOn(parseExpr());
        while (match(SqlTokenType.WHEN)) {
            SqlMergeWhen when = new SqlMergeWhen();
            boolean not = match(SqlTokenType.NOT);
            expect(SqlTokenType.MATCHED);
            if (not) {
                if (match(SqlTokenType.BY)) {
                    if (match(SqlTokenType.SOURCE)) {
                        when.setKind(SqlMergeWhen.MatchKind.NOT_MATCHED_BY_SOURCE);
                    } else {
                        expect(SqlTokenType.TARGET);
                        when.setKind(SqlMergeWhen.MatchKind.NOT_MATCHED_BY_TARGET);
                    }
                } else {
                    when.setKind(SqlMergeWhen.MatchKind.NOT_MATCHED);
                }
            } else {
                when.setKind(SqlMergeWhen.MatchKind.MATCHED);
                if (match(SqlTokenType.BY)) {
                    // WHEN MATCHED BY TARGET — 罕见，按 MATCHED 处理
                    match(SqlTokenType.TARGET);
                    match(SqlTokenType.SOURCE);
                }
            }
            if (match(SqlTokenType.AND)) {
                when.setAndPredicate(parseExpr());
            }
            expect(SqlTokenType.THEN);
            if (match(SqlTokenType.UPDATE)) {
                SqlUpdate upd = new SqlUpdate();
                expect(SqlTokenType.SET);
                parseAssignList(upd.setList());
                when.setUpdate(upd);
            } else if (match(SqlTokenType.INSERT)) {
                SqlInsert ins = new SqlInsert();
                match(SqlTokenType.INTO);
                if (match(SqlTokenType.LPAREN)) {
                    do {
                        ins.columns().add(parseName());
                    } while (match(SqlTokenType.COMMA));
                    expect(SqlTokenType.RPAREN);
                }
                expect(SqlTokenType.VALUES);
                parseValuesRows(ins);
                when.setInsert(ins);
            } else if (match(SqlTokenType.DELETE)) {
                when.setDelete(true);
            } else {
                throw error("expected UPDATE, INSERT or DELETE after THEN");
            }
            merge.whens().add(when);
        }
        parseOutputClause(merge.output());
        return merge;
    }

    private SqlStatement parseCreate() {
        expect(SqlTokenType.CREATE);
        boolean orReplace = false;
        if (match(SqlTokenType.OR)) {
            expect(SqlTokenType.REPLACE);
            orReplace = true;
        }
        match(SqlTokenType.TEMPORARY);
        match(SqlTokenType.TEMP);
        match(SqlTokenType.UNIQUE);
        match(SqlTokenType.MATERIALIZED);
        SqlDdlStatement ddl = new SqlDdlStatement();
        ddl.setStatementType(SqlStatementType.CREATE);
        ddl.setOrReplace(orReplace);
        if (is(SqlTokenType.TABLE) || is(SqlTokenType.VIEW) || is(SqlTokenType.INDEX)
                || is(SqlTokenType.DATABASE) || is(SqlTokenType.SCHEMA)
                || is(SqlTokenType.SEQUENCE) || is(SqlTokenType.PROCEDURE)
                || is(SqlTokenType.FUNCTION) || is(SqlTokenType.TRIGGER)
                || is(SqlTokenType.EVENT)) {
            ddl.setObjectType(token.text().toUpperCase());
            next();
        } else if (identLike()) {
            ddl.setObjectType(consumeIdentRaw().toUpperCase());
        }
        if (match(SqlTokenType.IF)) {
            expect(SqlTokenType.NOT);
            expect(SqlTokenType.EXISTS);
            ddl.setIfNotExists(true);
        }
        ddl.names().add(parseName());
        // CREATE INDEX name ON table；EVENT 的 ON SCHEDULE 不抽成对象名
        if (isIndexObject(ddl.objectType()) && match(SqlTokenType.ON)) {
            ddl.names().add(parseName());
        }
        boolean routine = isRoutineObject(ddl.objectType());
        String paramTail = null;
        if (match(SqlTokenType.LPAREN)) {
            if (routine) {
                paramTail = "(" + skipBalancedParensContent() + ")";
            } else {
                parseCreateColumns(ddl);
                expect(SqlTokenType.RPAREN);
            }
        }
        if (match(SqlTokenType.AS) || is(SqlTokenType.SELECT) || is(SqlTokenType.WITH)) {
            match(SqlTokenType.AS);
            ddl.setQuery(parseStatement());
        } else if (!atStmtBreak()) {
            if ("TABLE".equalsIgnoreCase(ddl.objectType())) {
                parseCreateTableOptions(ddl);
            } else {
                String body = consumeRawAllowingBeginEnd();
                if (paramTail != null) {
                    ddl.setTail(body.isEmpty() ? paramTail : paramTail + " " + body);
                } else {
                    ddl.setTail(body);
                }
            }
        } else if (paramTail != null) {
            ddl.setTail(paramTail);
        }
        return ddl;
    }

    private void parseCreateColumns(SqlDdlStatement ddl) {
        int depth = 1;
        if (is(SqlTokenType.RPAREN)) {
            return;
        }
        do {
            if (is(SqlTokenType.PRIMARY) || is(SqlTokenType.UNIQUE) || is(SqlTokenType.KEY)
                    || is(SqlTokenType.CONSTRAINT) || is(SqlTokenType.INDEX)
                    || is(SqlTokenType.FOREIGN) || is(SqlTokenType.CHECK)) {
                skipBalancedComma(depth);
            } else if (identLike()) {
                ddl.columns().add(parseName());
                skipBalancedComma(depth);
            } else {
                skipBalancedComma(depth);
            }
        } while (match(SqlTokenType.COMMA));
    }

    private void skipBalancedComma(int depth) {
        while (!is(SqlTokenType.EOF) && !is(SqlTokenType.SEMICOLON)) {
            if (is(SqlTokenType.LPAREN)) {
                depth++;
                next();
            } else if (is(SqlTokenType.RPAREN)) {
                depth--;
                if (depth == 0) {
                    return;
                }
                next();
            } else if (is(SqlTokenType.COMMA) && depth == 1) {
                return;
            } else {
                next();
            }
        }
    }

    private SqlStatement parseDrop() {
        expect(SqlTokenType.DROP);
        SqlDdlStatement ddl = new SqlDdlStatement();
        ddl.setStatementType(SqlStatementType.DROP);
        match(SqlTokenType.TEMPORARY);
        if (identLike() || token.type().keyword()) {
            ddl.setObjectType(token.text().toUpperCase());
            next();
        }
        if (match(SqlTokenType.IF)) {
            expect(SqlTokenType.EXISTS);
            ddl.setIfExists(true);
        }
        do {
            ddl.names().add(parseName());
        } while (match(SqlTokenType.COMMA));
        match(SqlTokenType.CASCADE);
        match(SqlTokenType.RESTRICT);
        return ddl;
    }

    private SqlStatement parseAlter() {
        SqlDdlStatement ddl = new SqlDdlStatement();
        ddl.setStatementType(SqlStatementType.ALTER);
        next();
        if (identLike() || token.type().keyword()) {
            ddl.setObjectType(token.text().toUpperCase());
            next();
        }
        if (identLike()) {
            ddl.names().add(parseName());
        }
        if (is(SqlTokenType.RENAME)) {
            next();
            if (match(SqlTokenType.TO) || match(SqlTokenType.AS)) {
                ddl.setAlterAction("RENAME TO");
                ddl.setRenameTo(parseName());
            } else {
                ddl.setTail("RENAME " + consumeRawUntilSemi());
            }
        } else if (isIdent("ADD") || is(SqlTokenType.DROP) || isIdent("MODIFY") || isIdent("CHANGE")) {
            String action = token.text().toUpperCase();
            next();
            if (parseAlterIndexAction(ddl, action)) {
                return ddl;
            }
            boolean columnKw = false;
            if (isIdent("COLUMN")) {
                next();
                columnKw = true;
            }
            if (identLike()) {
                ddl.columns().add(parseName());
            }
            ddl.setAlterAction(columnKw ? action + " COLUMN" : action);
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                ddl.setTail(rest);
            }
        } else if (!is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF)) {
            ddl.setTail(consumeRawUntilSemi());
        }
        return ddl;
    }

    /**
     * 解析 ADD/DROP INDEX|KEY；成功则填好 AST 并返回 true。
     */
    private boolean parseAlterIndexAction(SqlDdlStatement ddl, String action) {
        boolean unique = false;
        if ("ADD".equals(action) && is(SqlTokenType.UNIQUE)) {
            next();
            unique = true;
        }
        if (!(is(SqlTokenType.INDEX) || isIdent("KEY"))) {
            return false;
        }
        String indexKw = token.text().toUpperCase();
        next();
        String alterAction = action + (unique ? " UNIQUE " : " ") + indexKw;
        ddl.setAlterAction(alterAction);
        if (identLike()) {
            ddl.setIndexName(parseName());
        }
        if ("ADD".equals(action) && match(SqlTokenType.LPAREN)) {
            do {
                ddl.indexColumns().add(parseName());
                // 跳过长度 / ASC / DESC 等列修饰
                while (!is(SqlTokenType.COMMA) && !is(SqlTokenType.RPAREN)
                        && !is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF)) {
                    next();
                }
            } while (match(SqlTokenType.COMMA));
            expect(SqlTokenType.RPAREN);
        }
        if (!is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF)) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                ddl.setTail(rest);
            }
        }
        return true;
    }

    private void parseCreateTableOptions(SqlDdlStatement ddl) {
        StringBuilder unknown = new StringBuilder();
        while (!is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF)) {
            if (is(SqlTokenType.ENGINE)) {
                next();
                match(SqlTokenType.EQ);
                ddl.setEngine(consumeOptionValue());
            } else if (is(SqlTokenType.CHARSET)
                    || (is(SqlTokenType.CHARACTER) && lexer.peek().type() == SqlTokenType.SET)) {
                if (is(SqlTokenType.CHARACTER)) {
                    next();
                    expect(SqlTokenType.SET);
                } else {
                    next();
                }
                match(SqlTokenType.EQ);
                ddl.setCharset(consumeOptionValue());
            } else if (is(SqlTokenType.DEFAULT)
                    && (lexer.peek().type() == SqlTokenType.CHARSET
                    || lexer.peek().type() == SqlTokenType.CHARACTER
                    || lexer.peek().type() == SqlTokenType.COLLATE)) {
                next();
                if (is(SqlTokenType.CHARSET)
                        || (is(SqlTokenType.CHARACTER) && lexer.peek().type() == SqlTokenType.SET)) {
                    if (is(SqlTokenType.CHARACTER)) {
                        next();
                        expect(SqlTokenType.SET);
                    } else {
                        next();
                    }
                    match(SqlTokenType.EQ);
                    ddl.setCharset(consumeOptionValue());
                } else if (is(SqlTokenType.COLLATE)) {
                    next();
                    match(SqlTokenType.EQ);
                    ddl.setCollate(consumeOptionValue());
                }
            } else if (is(SqlTokenType.COLLATE)) {
                next();
                match(SqlTokenType.EQ);
                ddl.setCollate(consumeOptionValue());
            } else if (is(SqlTokenType.COMMENT)) {
                next();
                match(SqlTokenType.EQ);
                if (is(SqlTokenType.STRING)) {
                    ddl.setComment(token.text());
                    next();
                } else {
                    ddl.setComment(consumeOptionValue());
                }
            } else {
                if (unknown.length() > 0) {
                    unknown.append(' ');
                }
                unknown.append(token.text());
                next();
            }
        }
        if (unknown.length() > 0) {
            ddl.setTail(unknown.toString());
        }
    }

    private String consumeOptionValue() {
        if (is(SqlTokenType.STRING) || is(SqlTokenType.NUMBER) || identLike()
                || (token.type() != null && token.type().keyword())) {
            String v = token.text();
            next();
            return v;
        }
        throw error("expected table option value");
    }

    private SqlStatement parseTruncate() {
        expect(SqlTokenType.TRUNCATE);
        match(SqlTokenType.TABLE);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.TRUNCATE);
        stmt.setName(parseName());
        return stmt;
    }

    private SqlStatement parseExplain() {
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.EXPLAIN);
        if (is(SqlTokenType.DESC) || is(SqlTokenType.DESCRIBE)) {
            next();
            stmt.setName(parseName());
            return stmt;
        }
        expect(SqlTokenType.EXPLAIN);
        match(SqlTokenType.ANALYZE);
        if (match(SqlTokenType.FORMAT)) {
            match(SqlTokenType.EQ);
            next();
        }
        if (isQueryStart() || is(SqlTokenType.INSERT) || is(SqlTokenType.UPDATE)
                || is(SqlTokenType.DELETE) || is(SqlTokenType.WITH)) {
            stmt.setInner(parseStatement());
        } else if (identLike()) {
            stmt.setName(parseName());
        }
        return stmt;
    }

    private SqlStatement parseSet() {
        expect(SqlTokenType.SET);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.SET);
        match(SqlTokenType.SESSION);
        match(SqlTokenType.GLOBAL);
        match(SqlTokenType.LOCAL);
        if (match(SqlTokenType.NAMES)) {
            stmt.setName(SqlIdentifier.of("NAMES"));
            stmt.setValue(parsePrimary());
            return stmt;
        }
        if (is(SqlTokenType.VARIABLE)) {
            stmt.setName(SqlIdentifier.of(token.text()));
            next();
        } else {
            stmt.setName(parseName());
        }
        if (match(SqlTokenType.EQ) || match(SqlTokenType.ASSIGN)) {
            stmt.setValue(parseExpr());
        }
        return stmt;
    }

    private SqlStatement parseUse() {
        expect(SqlTokenType.USE);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.USE);
        stmt.setName(parseName());
        return stmt;
    }

    private SqlStatement parseShow() {
        expect(SqlTokenType.SHOW);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.SHOW);
        StringBuilder clause = new StringBuilder();
        if (match(SqlTokenType.CREATE)) {
            clause.append("CREATE");
            if (is(SqlTokenType.TABLE) || is(SqlTokenType.VIEW) || is(SqlTokenType.DATABASE)
                    || is(SqlTokenType.INDEX) || identLike()) {
                clause.append(' ').append(token.text().toUpperCase());
                next();
            }
            if (identLike()) {
                stmt.setName(parseName());
                clause.append(' ').append(stmt.name().qualifiedName());
            }
        } else if (is(SqlTokenType.COLUMNS) || isIdent("FIELDS") || isIdent("INDEX")
                || is(SqlTokenType.INDEX) || is(SqlTokenType.TABLES) || is(SqlTokenType.DATABASES)) {
            clause.append(token.text().toUpperCase());
            next();
            if (is(SqlTokenType.FROM) || is(SqlTokenType.IN)) {
                clause.append(' ').append(token.text().toUpperCase());
                next();
                if (identLike()) {
                    stmt.setName(parseName());
                    clause.append(' ').append(stmt.name().qualifiedName());
                }
            }
        }
        if (!is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF)) {
            String rest = consumeRawUntilSemi();
            if (!rest.isEmpty()) {
                if (clause.length() > 0) {
                    clause.append(' ');
                }
                clause.append(rest);
            }
        }
        if (clause.length() > 0) {
            stmt.setText(clause.toString());
        }
        return stmt;
    }

    private SqlStatement parseCall() {
        expect(SqlTokenType.CALL);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.CALL);
        stmt.setName(parseName());
        if (match(SqlTokenType.LPAREN)) {
            stmt.setWithArguments(true);
            if (!is(SqlTokenType.RPAREN)) {
                do {
                    stmt.arguments().add(parseExpr());
                } while (match(SqlTokenType.COMMA));
            }
            expect(SqlTokenType.RPAREN);
        }
        return stmt;
    }

    private SqlStatement parseBeginBlock() {
        expect(SqlTokenType.BEGIN);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        String body = trailingRawAllowingBeginEnd(1);
        stmt.setText(body.isEmpty() ? "BEGIN" : "BEGIN " + body);
        return stmt;
    }

    private SqlStatement parseDeclare() {
        expect(SqlTokenType.DECLARE);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        if (identLike()) {
            stmt.setName(parseName());
        }
        String rest = consumeRawUntilSemi();
        StringBuilder text = new StringBuilder("DECLARE");
        if (stmt.name() != null) {
            text.append(' ').append(stmt.name().qualifiedName());
        }
        if (!rest.isEmpty()) {
            text.append(' ').append(rest);
        }
        stmt.setText(text.toString());
        return stmt;
    }

    private SqlStatement parseMaintenance() {
        String kind = token.text().toUpperCase();
        next();
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder(kind);
        // VACUUM [FULL] [ANALYZE] / ANALYZE [TABLE] / OPTIMIZE|REPAIR|CHECK TABLE
        while (is(SqlTokenType.ANALYZE) || is(SqlTokenType.TABLE) || is(SqlTokenType.FULL)
                || is(SqlTokenType.LOCAL) || isIdent("FREEZE") || isIdent("VERBOSE")
                || isIdent("NO_WRITE_TO_BINLOG")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        }
        if (identLike()) {
            stmt.setName(parseName());
            text.append(' ').append(stmt.name().qualifiedName());
            while (match(SqlTokenType.COMMA)) {
                text.append(',');
                SqlIdentifier more = parseName();
                text.append(' ').append(more.qualifiedName());
            }
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (!rest.isEmpty()) {
                text.append(' ').append(rest);
            }
        }
        stmt.setText(text.toString());
        return stmt;
    }

    private SqlStatement parseCommentOn() {
        expect(SqlTokenType.COMMENT);
        expect(SqlTokenType.ON);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("COMMENT ON");
        if (is(SqlTokenType.TABLE) || is(SqlTokenType.INDEX) || is(SqlTokenType.VIEW)
                || isIdent("COLUMN") || identLike()) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        }
        if (identLike()) {
            stmt.setName(parseName());
            text.append(' ').append(stmt.name().qualifiedName());
        }
        if (match(SqlTokenType.IS)) {
            text.append(" IS");
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (!rest.isEmpty()) {
                text.append(' ').append(rest);
            }
        }
        stmt.setText(text.toString());
        return stmt;
    }

    private SqlStatement parseGrant() {
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.GRANT);
        stmt.setText(token.text() + " " + consumeRawUntilSemi());
        return stmt;
    }

    private SqlTableSource parseJoinedTable() {
        SqlTableSource left = parseTableSource();
        while (true) {
            SqlJoin.Type type = null;
            if (match(SqlTokenType.COMMA)) {
                type = SqlJoin.Type.COMMA;
            } else if (match(SqlTokenType.JOIN) || match(SqlTokenType.STRAIGHT_JOIN)
                    || match(SqlTokenType.INNER)) {
                match(SqlTokenType.JOIN);
                type = SqlJoin.Type.INNER;
                if (token.type() == SqlTokenType.STRAIGHT_JOIN) {
                    type = SqlJoin.Type.STRAIGHT;
                    next();
                }
            } else if (match(SqlTokenType.LEFT)) {
                match(SqlTokenType.OUTER);
                expect(SqlTokenType.JOIN);
                type = SqlJoin.Type.LEFT;
            } else if (match(SqlTokenType.RIGHT)) {
                match(SqlTokenType.OUTER);
                expect(SqlTokenType.JOIN);
                type = SqlJoin.Type.RIGHT;
            } else if (match(SqlTokenType.FULL)) {
                match(SqlTokenType.OUTER);
                expect(SqlTokenType.JOIN);
                type = SqlJoin.Type.FULL;
            } else if (match(SqlTokenType.CROSS)) {
                if (match(SqlTokenType.APPLY)) {
                    type = SqlJoin.Type.CROSS_APPLY;
                } else {
                    expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.CROSS;
                }
            } else if (match(SqlTokenType.OUTER)) {
                expect(SqlTokenType.APPLY);
                type = SqlJoin.Type.OUTER_APPLY;
            } else if (match(SqlTokenType.NATURAL)) {
                match(SqlTokenType.LEFT);
                match(SqlTokenType.RIGHT);
                match(SqlTokenType.INNER);
                match(SqlTokenType.OUTER);
                match(SqlTokenType.JOIN);
                type = SqlJoin.Type.NATURAL;
            } else {
                break;
            }
            SqlJoin join = new SqlJoin();
            join.setJoinType(type);
            join.setLeft(left);
            join.setRight(parseTableSource());
            if (match(SqlTokenType.ON)) {
                join.setCondition(parseExpr());
            } else if (match(SqlTokenType.USING)) {
                expect(SqlTokenType.LPAREN);
                List<SqlIdentifier> using = new ArrayList<SqlIdentifier>(2);
                do {
                    using.add(parseName());
                } while (match(SqlTokenType.COMMA));
                expect(SqlTokenType.RPAREN);
                join.setUsing(using);
            }
            left = join;
        }
        return left;
    }

    private SqlTableSource parseTableSource() {
        boolean lateral = match(SqlTokenType.LATERAL);
        // Oracle / SQL 标准：TABLE(fn(...))
        if (is(SqlTokenType.TABLE) && lexer.peek().type() == SqlTokenType.LPAREN) {
            next();
            expect(SqlTokenType.LPAREN);
            SqlFunctionTable ft = new SqlFunctionTable();
            ft.setTableKeyword(true);
            ft.setLateral(lateral);
            ft.setFunction(parseExpr());
            expect(SqlTokenType.RPAREN);
            parseTableAlias(ft);
            return ft;
        }
        if (match(SqlTokenType.LPAREN)) {
            SqlTableSource source;
            if (is(SqlTokenType.VALUES)) {
                source = parseValuesTable();
            } else if (isQueryStart() || is(SqlTokenType.WITH)) {
                SqlSubqueryTable sub = new SqlSubqueryTable();
                sub.setQuery(parseStatement());
                sub.setLateral(lateral);
                source = sub;
            } else {
                if (lateral) {
                    throw error("LATERAL requires a subquery or table function");
                }
                source = parseJoinedTable();
            }
            expect(SqlTokenType.RPAREN);
            parseTableAlias(source);
            return source;
        }
        if (identLike() || (token.type() != null && token.type().keyword()
                && !is(SqlTokenType.SELECT) && !is(SqlTokenType.WITH)
                && !is(SqlTokenType.VALUES))) {
            SqlIdentifier name = parseName();
            if (is(SqlTokenType.LPAREN)) {
                SqlFunctionTable ft = new SqlFunctionTable();
                ft.setLateral(lateral);
                ft.setFunction(parseFunction(name));
                parseTableAlias(ft);
                return ft;
            }
            if (lateral) {
                throw error("LATERAL requires a subquery or table function");
            }
            SqlTable table = SqlTable.of(name);
            parseTableHints(table);
            parseTableAlias(table);
            return table;
        }
        if (lateral) {
            throw error("LATERAL requires a subquery or table function");
        }
        throw error("expected table source");
    }

    private SqlValuesTable parseValuesTable() {
        SqlValuesTable values = new SqlValuesTable();
        expect(SqlTokenType.VALUES);
        do {
            values.rows().add(parsePrimary());
        } while (match(SqlTokenType.COMMA));
        return values;
    }

    private void parseTableAlias(SqlTableSource source) {
        String alias = parseAlias(true);
        source.setAlias(alias);
        if (alias != null && match(SqlTokenType.LPAREN)) {
            do {
                source.columnAliases().add(parseName());
            } while (match(SqlTokenType.COMMA));
            expect(SqlTokenType.RPAREN);
        }
    }

    private void parseTableHints(SqlTable table) {
        if (is(SqlTokenType.USE) || is(SqlTokenType.FORCE) || is(SqlTokenType.IGNORE)) {
            StringBuilder sb = new StringBuilder();
            sb.append(token.text());
            next();
            if (is(SqlTokenType.INDEX) || is(SqlTokenType.KEY)) {
                sb.append(' ').append(token.text());
                next();
            }
            if (match(SqlTokenType.LPAREN)) {
                sb.append('(');
                sb.append(consumeRawUntilType(SqlTokenType.RPAREN));
                expect(SqlTokenType.RPAREN);
                sb.append(')');
            }
            table.setIndexHint(sb.toString());
        }
    }

    private String parseAlias(boolean inFrom) {
        if (match(SqlTokenType.AS)) {
            if (is(SqlTokenType.STRING)) {
                return unquote(consumeStringRaw());
            }
            return unquote(consumeIdentRaw());
        }
        if (is(SqlTokenType.STRING)) {
            // MySQL：SELECT id "别名" / SELECT 1 'x' —— 双引号在 MYSQL 方言下是字符串记号
            return unquote(consumeStringRaw());
        }
        if (identLike() && !isAliasStop(token.type(), inFrom)) {
            return unquote(consumeIdentRaw());
        }
        return null;
    }

    private static boolean isAliasStop(SqlTokenType t, boolean inFrom) {
        switch (t) {
            case WHERE:
            case GROUP:
            case HAVING:
            case ORDER:
            case LIMIT:
            case OFFSET:
            case FETCH:
            case UNION:
            case INTERSECT:
            case EXCEPT:
            case MINUS:
            case JOIN:
            case INNER:
            case LEFT:
            case RIGHT:
            case FULL:
            case CROSS:
            case OUTER:
            case NATURAL:
            case STRAIGHT_JOIN:
            case LATERAL:
            case ON:
            case USING:
            case SET:
            case COMMA:
            case RPAREN:
            case SEMICOLON:
            case GO:
            case EOF:
            case FOR:
            case START:
            case CONNECT:
            case RETURNING:
            case INTO:
            case LOCK:
            case IN:
            case SHARE:
            case MODE:
            case FROM:
            case WINDOW:
            case APPLY:
            case OUTPUT:
            case WHEN:
            case MATCHED:
                return true;
            default:
                return false;
        }
    }

    private void parseAssignList(List<SqlBinaryExpr> target) {
        do {
            SqlExpr left = parseName();
            expect(SqlTokenType.EQ);
            SqlExpr right = parseExpr();
            target.add(SqlBinaryExpr.of(left, SqlBinaryOp.EQ, right));
        } while (match(SqlTokenType.COMMA));
    }

    private void parseOrderBy(List<SqlOrderByItem> list) {
        do {
            SqlOrderByItem item = new SqlOrderByItem();
            item.setExpr(parseExpr());
            if (match(SqlTokenType.DESC)) {
                item.setAsc(false);
            } else {
                match(SqlTokenType.ASC);
            }
            if (isIdent("NULLS")) {
                next();
                item.setNulls("NULLS " + consumeIdentRaw().toUpperCase());
            }
            list.add(item);
        } while (match(SqlTokenType.COMMA));
    }

    private void parseLimitFetch(SqlSelect select) {
        if (is(SqlTokenType.LIMIT)) {
            select.setLimit(parseLimit());
        }
        if (match(SqlTokenType.OFFSET)) {
            SqlLimit limit = select.limit();
            if (limit == null) {
                limit = new SqlLimit();
                select.setLimit(limit);
            }
            limit.setOffset(parsePrimary());
            match(SqlTokenType.ROW);
            match(SqlTokenType.ROWS);
        }
        if (match(SqlTokenType.FETCH)) {
            match(SqlTokenType.FIRST);
            match(SqlTokenType.NEXT);
            SqlLimit limit = select.limit();
            if (limit == null) {
                limit = new SqlLimit();
                select.setLimit(limit);
            }
            limit.setRowCount(parsePrimary());
            match(SqlTokenType.ROW);
            match(SqlTokenType.ROWS);
            match(SqlTokenType.ONLY);
            match(SqlTokenType.WITH);
            match(SqlTokenType.TIES);
        }
    }

    private SqlLimit parseLimit() {
        expect(SqlTokenType.LIMIT);
        SqlLimit limit = new SqlLimit();
        SqlExpr first = parseExpr();
        if (match(SqlTokenType.COMMA)) {
            limit.setMysqlCommaStyle(true);
            limit.setOffset(first);
            limit.setRowCount(parseExpr());
        } else {
            limit.setRowCount(first);
            if (match(SqlTokenType.OFFSET)) {
                limit.setOffset(parseExpr());
            }
        }
        return limit;
    }

    private SqlExpr parseExpr() {
        return parseOr();
    }

    private SqlExpr parseOr() {
        SqlExpr left = parseXor();
        while (is(SqlTokenType.OR) || is(SqlTokenType.OR_OP)) {
            next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.OR, parseXor());
        }
        return left;
    }

    private SqlExpr parseXor() {
        SqlExpr left = parseAnd();
        while (is(SqlTokenType.XOR)) {
            next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.XOR, parseAnd());
        }
        return left;
    }

    private SqlExpr parseAnd() {
        SqlExpr left = parseNot();
        while (is(SqlTokenType.AND) || is(SqlTokenType.AND_OP)) {
            next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.AND, parseNot());
        }
        return left;
    }

    private SqlExpr parseNot() {
        if (is(SqlTokenType.NOT) || is(SqlTokenType.NOT_OP)) {
            next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.NOT, parseNot());
        }
        return parseComparison();
    }

    private SqlExpr parseComparison() {
        SqlExpr left = parseBit();
        while (true) {
            if (is(SqlTokenType.EQ)) {
                next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.EQ, parseBit());
            } else if (is(SqlTokenType.NE)) {
                next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.NE, parseBit());
            } else if (is(SqlTokenType.LT)) {
                next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LT, parseBit());
            } else if (is(SqlTokenType.GT)) {
                next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.GT, parseBit());
            } else if (is(SqlTokenType.LE)) {
                next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LE, parseBit());
            } else if (is(SqlTokenType.GE)) {
                next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.GE, parseBit());
            } else if (is(SqlTokenType.IS)) {
                next();
                boolean not = match(SqlTokenType.NOT);
                if (match(SqlTokenType.DISTINCT)) {
                    expect(SqlTokenType.FROM);
                    left = SqlBinaryExpr.of(left,
                            not ? SqlBinaryOp.IS_NOT_DISTINCT_FROM : SqlBinaryOp.IS_DISTINCT_FROM,
                            parseBit());
                } else {
                    left = SqlBinaryExpr.of(left, not ? SqlBinaryOp.IS_NOT : SqlBinaryOp.IS, parseBit());
                }
            } else if (is(SqlTokenType.LIKE) || is(SqlTokenType.ILIKE)
                    || is(SqlTokenType.REGEXP) || is(SqlTokenType.RLIKE)) {
                SqlBinaryOp op = is(SqlTokenType.ILIKE) ? SqlBinaryOp.ILIKE
                        : (is(SqlTokenType.LIKE) ? SqlBinaryOp.LIKE : SqlBinaryOp.REGEXP);
                next();
                left = SqlBinaryExpr.of(left, op, parseBit());
                if (match(SqlTokenType.ESCAPE)) {
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.ESCAPE, parseBit());
                }
            } else if (is(SqlTokenType.BETWEEN)) {
                left = parseBetween(left, false);
            } else if (is(SqlTokenType.IN)) {
                left = parseIn(left, false);
            } else if (is(SqlTokenType.NOT)) {
                SqlToken peeked = lexer.peek();
                if (peeked.type() == SqlTokenType.LIKE) {
                    next();
                    next();
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.NOT_LIKE, parseBit());
                } else if (peeked.type() == SqlTokenType.IN) {
                    next();
                    left = parseIn(left, true);
                } else if (peeked.type() == SqlTokenType.BETWEEN) {
                    next();
                    left = parseBetween(left, true);
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return left;
    }

    private SqlExpr parseBetween(SqlExpr left, boolean not) {
        expect(SqlTokenType.BETWEEN);
        SqlBetweenExpr b = new SqlBetweenExpr();
        b.setExpr(left);
        b.setNot(not);
        b.setBegin(parseBit());
        expect(SqlTokenType.AND);
        b.setEnd(parseBit());
        return b;
    }

    private SqlExpr parseIn(SqlExpr left, boolean not) {
        expect(SqlTokenType.IN);
        SqlInExpr in = new SqlInExpr();
        in.setExpr(left);
        in.setNot(not);
        expect(SqlTokenType.LPAREN);
        if (isQueryStart()) {
            in.setSubquery(parseStatement());
        } else {
            List<SqlExpr> values = new ArrayList<SqlExpr>(4);
            if (!is(SqlTokenType.RPAREN)) {
                do {
                    values.add(parseExpr());
                } while (match(SqlTokenType.COMMA));
            }
            in.setValues(values);
        }
        expect(SqlTokenType.RPAREN);
        return in;
    }

    private SqlExpr parseBit() {
        SqlExpr left = parseAdd();
        while (is(SqlTokenType.BIT_AND) || is(SqlTokenType.BIT_OR) || is(SqlTokenType.BIT_XOR)
                || is(SqlTokenType.SHIFT_LEFT) || is(SqlTokenType.SHIFT_RIGHT)
                || is(SqlTokenType.CONCAT) || is(SqlTokenType.JSON_OP) || is(SqlTokenType.CAST_OP)) {
            SqlTokenType t = token.type();
            String opText = token.text();
            next();
            if (t == SqlTokenType.CAST_OP) {
                SqlCastExpr cast = new SqlCastExpr();
                cast.setExpr(left);
                cast.setPostgresStyle(true);
                cast.setDataType(parseDataType());
                left = cast;
            } else {
                SqlBinaryOp op;
                if (t == SqlTokenType.CONCAT) {
                    op = SqlBinaryOp.CONCAT;
                } else if (t == SqlTokenType.JSON_OP) {
                    op = jsonOp(opText);
                } else if (t == SqlTokenType.BIT_AND) {
                    op = SqlBinaryOp.BIT_AND;
                } else if (t == SqlTokenType.BIT_OR) {
                    op = SqlBinaryOp.BIT_OR;
                } else if (t == SqlTokenType.BIT_XOR) {
                    op = SqlBinaryOp.BIT_XOR;
                } else if (t == SqlTokenType.SHIFT_LEFT) {
                    op = SqlBinaryOp.SHIFT_LEFT;
                } else {
                    op = SqlBinaryOp.SHIFT_RIGHT;
                }
                left = SqlBinaryExpr.of(left, op, parseAdd());
            }
        }
        return left;
    }

    private SqlExpr parseAdd() {
        SqlExpr left = parseMul();
        while (is(SqlTokenType.PLUS) || is(SqlTokenType.MINUS)) {
            SqlBinaryOp op = is(SqlTokenType.PLUS) ? SqlBinaryOp.PLUS : SqlBinaryOp.MINUS;
            next();
            left = SqlBinaryExpr.of(left, op, parseMul());
        }
        return left;
    }

    private SqlExpr parseMul() {
        SqlExpr left = parseUnary();
        while (is(SqlTokenType.STAR) || is(SqlTokenType.SLASH) || is(SqlTokenType.PERCENT)
                || is(SqlTokenType.DIV) || is(SqlTokenType.MOD)) {
            SqlBinaryOp op;
            if (is(SqlTokenType.STAR)) {
                op = SqlBinaryOp.MUL;
            } else if (is(SqlTokenType.SLASH)) {
                op = SqlBinaryOp.DIV;
            } else if (is(SqlTokenType.DIV)) {
                op = SqlBinaryOp.INT_DIV;
            } else {
                op = SqlBinaryOp.MOD;
            }
            next();
            left = SqlBinaryExpr.of(left, op, parseUnary());
        }
        return left;
    }

    private SqlExpr parseUnary() {
        if (is(SqlTokenType.MINUS)) {
            next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.MINUS, parseUnary());
        }
        if (is(SqlTokenType.PLUS)) {
            next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.PLUS, parseUnary());
        }
        if (is(SqlTokenType.TILDE)) {
            next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.TILDE, parseUnary());
        }
        if (is(SqlTokenType.EXISTS)) {
            next();
            expect(SqlTokenType.LPAREN);
            SqlUnaryExpr u = SqlUnaryExpr.of(SqlUnaryExpr.Op.EXISTS, SqlQueryExpr.of(parseStatement()));
            expect(SqlTokenType.RPAREN);
            return u;
        }
        if (isIdent("PRIOR")) {
            next();
            SqlFunctionExpr prior = new SqlFunctionExpr();
            prior.setName(SqlIdentifier.of("PRIOR"));
            prior.addArgument(parseUnary());
            return prior;
        }
        return parsePrimary();
    }

    private SqlExpr parsePrimary() {
        SqlExpr expr = parsePrimaryInner();
        while (match(SqlTokenType.DOT)) {
            if (match(SqlTokenType.STAR)) {
                SqlAllColumns all = new SqlAllColumns();
                if (expr instanceof SqlIdentifier) {
                    all.setOwner((SqlIdentifier) expr);
                }
                return all;
            }
            if (expr instanceof SqlIdentifier) {
                ((SqlIdentifier) expr).addName(unquote(consumeIdentRaw()));
            } else {
                SqlIdentifier id = new SqlIdentifier();
                id.addName(unquote(consumeIdentRaw()));
                expr = id;
            }
        }
        if (is(SqlTokenType.LPAREN) && expr instanceof SqlIdentifier) {
            return parseFunction((SqlIdentifier) expr);
        }
        while (match(SqlTokenType.LBRACKET)) {
            SqlExpr index = parseExpr();
            expect(SqlTokenType.RBRACKET);
            expr = SqlBinaryExpr.of(expr, SqlBinaryOp.SUBSCRIPT, index);
        }
        if (match(SqlTokenType.COLLATE)) {
            expr = SqlBinaryExpr.of(expr, SqlBinaryOp.COLLATE, parseName());
        }
        return expr;
    }

    private SqlExpr parsePrimaryInner() {
        if (is(SqlTokenType.NULL)) {
            next();
            return SqlLiteral.of(SqlLiteral.Kind.NULL, "NULL");
        }
        if (is(SqlTokenType.TRUE) || is(SqlTokenType.FALSE)) {
            String v = token.text();
            next();
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, v);
        }
        if (is(SqlTokenType.NUMBER) || is(SqlTokenType.HEX) || is(SqlTokenType.BIT)) {
            SqlLiteral.Kind kind = is(SqlTokenType.NUMBER) ? SqlLiteral.Kind.NUMBER
                    : (is(SqlTokenType.HEX) ? SqlLiteral.Kind.HEX : SqlLiteral.Kind.BIT);
            SqlLiteral lit = SqlLiteral.of(kind, token.text());
            next();
            return lit;
        }
        if (is(SqlTokenType.STRING)) {
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.STRING, token.text());
            next();
            return lit;
        }
        if (is(SqlTokenType.BIND)) {
            next();
            return SqlLiteral.of(SqlLiteral.Kind.BIND, "?");
        }
        if (is(SqlTokenType.NAMED_BIND)) {
            String raw = token.text();
            next();
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.NAMED_BIND, raw);
            lit.setName(raw.startsWith(":") ? raw.substring(1) : raw);
            return lit;
        }
        if (is(SqlTokenType.VARIABLE)) {
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.VARIABLE, token.text());
            next();
            return lit;
        }
        if (is(SqlTokenType.STAR)) {
            next();
            return new SqlAllColumns();
        }
        if (is(SqlTokenType.CASE)) {
            return parseCase();
        }
        if (is(SqlTokenType.CAST)) {
            return parseCast();
        }
        if ((is(SqlTokenType.DATE) || is(SqlTokenType.TIME) || is(SqlTokenType.TIMESTAMP)
                || is(SqlTokenType.DATETIME))
                && lexer.peek().type() == SqlTokenType.STRING) {
            SqlFunctionExpr typed = new SqlFunctionExpr();
            typed.setName(SqlIdentifier.of(token.text().toUpperCase()));
            next();
            typed.addArgument(parsePrimaryInner());
            return typed;
        }
        if (is(SqlTokenType.INTERVAL)) {
            next();
            SqlFunctionExpr fn = new SqlFunctionExpr();
            fn.setName(SqlIdentifier.of("INTERVAL"));
            fn.addArgument(parsePrimary());
            if (identLike()) {
                fn.addArgument(SqlIdentifier.of(consumeIdentRaw()));
            }
            return fn;
        }
        if (match(SqlTokenType.LPAREN)) {
            if (isQueryStart()) {
                SqlQueryExpr q = SqlQueryExpr.of(parseStatement());
                expect(SqlTokenType.RPAREN);
                return q;
            }
            SqlExpr first = parseExpr();
            if (match(SqlTokenType.COMMA)) {
                SqlListExpr list = new SqlListExpr();
                list.add(first);
                do {
                    list.add(parseExpr());
                } while (match(SqlTokenType.COMMA));
                expect(SqlTokenType.RPAREN);
                return list;
            }
            expect(SqlTokenType.RPAREN);
            return first;
        }
        if (identLike() || token.type().keyword()) {
            return parseName();
        }
        throw error("unexpected token " + token.type());
    }

    private SqlExpr parseFunction(SqlIdentifier name) {
        String fnName = name.simpleName();
        if (equalsIgnoreCase(fnName, "EXTRACT")) {
            return parseExtract(name);
        }
        if (equalsIgnoreCase(fnName, "TRIM")) {
            return parseTrim(name);
        }
        if (equalsIgnoreCase(fnName, "SUBSTRING")) {
            return parseSubstring(name);
        }
        if (equalsIgnoreCase(fnName, "POSITION")) {
            return parsePosition(name);
        }
        if (equalsIgnoreCase(fnName, "CONVERT")) {
            return parseConvert(name);
        }
        if (equalsIgnoreCase(fnName, "GROUP_CONCAT") || equalsIgnoreCase(fnName, "STRING_AGG")) {
            return parseGroupConcatLike(name);
        }
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (match(SqlTokenType.DISTINCT)) {
            fn.setDistinct(true);
        }
        if (!is(SqlTokenType.RPAREN)) {
            do {
                if (is(SqlTokenType.STAR)) {
                    fn.addArgument(parsePrimary());
                } else if (isQueryStart()) {
                    fn.addArgument(SqlQueryExpr.of(parseStatement()));
                } else {
                    fn.addArgument(parseExpr());
                }
            } while (match(SqlTokenType.COMMA));
        }
        expect(SqlTokenType.RPAREN);
        if (equalsIgnoreCase(fnName, "MATCH")) {
            parseMatchAgainst(fn);
        }
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseConvert(SqlIdentifier name) {
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseExpr());
        if (match(SqlTokenType.USING)) {
            fn.setUsingCharset(true);
            if (identLike() || (token.type() != null && token.type().keyword())) {
                fn.addArgument(parseName());
            } else {
                fn.addArgument(parsePrimary());
            }
        } else if (match(SqlTokenType.COMMA)) {
            do {
                fn.addArgument(parseExpr());
            } while (match(SqlTokenType.COMMA));
        }
        expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseGroupConcatLike(SqlIdentifier name) {
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (match(SqlTokenType.DISTINCT)) {
            fn.setDistinct(true);
        }
        if (!is(SqlTokenType.RPAREN)) {
            fn.addArgument(parseExpr());
            while (match(SqlTokenType.COMMA)) {
                if (is(SqlTokenType.ORDER)) {
                    break;
                }
                fn.addArgument(parseExpr());
            }
            if (match(SqlTokenType.ORDER)) {
                expect(SqlTokenType.BY);
                parseOrderBy(fn.orderBy());
            }
            if (isIdent("SEPARATOR")) {
                next();
                fn.setSeparator(parseExpr());
            }
        }
        expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private void parseMatchAgainst(SqlFunctionExpr fn) {
        if (!(is(SqlTokenType.AGAINST) || isIdent("AGAINST"))) {
            return;
        }
        next();
        expect(SqlTokenType.LPAREN);
        // 不能用 parseExpr：IN BOOLEAN MODE 会被当成 IN 谓词
        fn.setAgainst(parseBit());
        if (!is(SqlTokenType.RPAREN)) {
            String mod = consumeRawUntilType(SqlTokenType.RPAREN).trim();
            if (mod.length() > 0) {
                fn.setAgainstModifier(mod);
            }
        }
        expect(SqlTokenType.RPAREN);
    }

    private void parseFunctionTail(SqlFunctionExpr fn) {
        if (isIdent("FILTER")) {
            next();
            expect(SqlTokenType.LPAREN);
            expect(SqlTokenType.WHERE);
            fn.setFilter(parseExpr());
            expect(SqlTokenType.RPAREN);
        }
        if (match(SqlTokenType.OVER)) {
            fn.setOver(parseOver());
        }
        if (isIdent("WITHIN")) {
            next();
            expect(SqlTokenType.GROUP);
            expect(SqlTokenType.LPAREN);
            expect(SqlTokenType.ORDER);
            expect(SqlTokenType.BY);
            parseOrderBy(fn.orderBy());
            expect(SqlTokenType.RPAREN);
            fn.setWithinGroup(true);
        }
    }

    private static SqlBinaryOp jsonOp(String text) {
        if ("->>".equals(text)) {
            return SqlBinaryOp.JSON_ARROW_TEXT;
        }
        if ("#>".equals(text)) {
            return SqlBinaryOp.JSON_PATH;
        }
        if ("#>>".equals(text)) {
            return SqlBinaryOp.JSON_PATH_TEXT;
        }
        if ("->".equals(text)) {
            return SqlBinaryOp.JSON_ARROW;
        }
        return SqlBinaryOp.JSON;
    }

    private SqlExpr parseExtract(SqlIdentifier name) {
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseName());
        expect(SqlTokenType.FROM);
        fn.addArgument(parseExpr());
        expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseTrim(SqlIdentifier name) {
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (is(SqlTokenType.BOTH) || is(SqlTokenType.LEADING) || is(SqlTokenType.TRAILING)) {
            fn.addArgument(SqlIdentifier.of(token.text().toUpperCase()));
            next();
        }
        if (match(SqlTokenType.FROM)) {
            fn.addArgument(parseExpr());
        } else {
            fn.addArgument(parseExpr());
            if (match(SqlTokenType.FROM)) {
                fn.addArgument(parseExpr());
            }
        }
        expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseSubstring(SqlIdentifier name) {
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseExpr());
        if (match(SqlTokenType.FROM)) {
            fn.addArgument(parseExpr());
            if (match(SqlTokenType.FOR)) {
                fn.addArgument(parseExpr());
            }
        } else if (match(SqlTokenType.COMMA)) {
            do {
                fn.addArgument(parseExpr());
            } while (match(SqlTokenType.COMMA));
        }
        expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parsePosition(SqlIdentifier name) {
        expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseExpr());
        if (match(SqlTokenType.IN)) {
            fn.addArgument(parseExpr());
        } else if (match(SqlTokenType.COMMA)) {
            fn.addArgument(parseExpr());
        }
        expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlOverExpr parseOver() {
        SqlOverExpr over = new SqlOverExpr();
        if (!is(SqlTokenType.LPAREN) && identLike()) {
            over.setWindowName(parseName());
            return over;
        }
        expect(SqlTokenType.LPAREN);
        // 继承已有窗口：WINDOW w2 AS (w) / (w ORDER BY b) / (w PARTITION BY ... 非法但留给方言)
        if (identLike()
                && !is(SqlTokenType.PARTITION)
                && !is(SqlTokenType.ORDER)
                && !is(SqlTokenType.ROWS)
                && !isIdent("RANGE")
                && !isIdent("GROUPS")) {
            over.setExistingWindowName(parseName());
        }
        if (match(SqlTokenType.PARTITION)) {
            expect(SqlTokenType.BY);
            do {
                over.partitionBy().add(parseExpr());
            } while (match(SqlTokenType.COMMA));
        }
        if (match(SqlTokenType.ORDER)) {
            expect(SqlTokenType.BY);
            parseOrderBy(over.orderBy());
        }
        if (is(SqlTokenType.ROWS) || isIdent("RANGE")) {
            over.setFrameUnit(token.text().toUpperCase());
            next();
            if (match(SqlTokenType.BETWEEN)) {
                over.setFrameStart(parseFrameBound());
                expect(SqlTokenType.AND);
                over.setFrameEnd(parseFrameBound());
            } else {
                over.setFrameStart(parseFrameBound());
            }
        }
        expect(SqlTokenType.RPAREN);
        return over;
    }

    private String parseFrameBound() {
        if (match(SqlTokenType.UNBOUNDED)) {
            if (match(SqlTokenType.PRECEDING)) {
                return "UNBOUNDED PRECEDING";
            }
            expect(SqlTokenType.FOLLOWING);
            return "UNBOUNDED FOLLOWING";
        }
        if (match(SqlTokenType.CURRENT)) {
            expect(SqlTokenType.ROW);
            return "CURRENT ROW";
        }
        SqlExpr expr = parsePrimary();
        String value;
        if (expr instanceof SqlLiteral) {
            value = ((SqlLiteral) expr).value();
        } else if (expr instanceof SqlIdentifier) {
            value = ((SqlIdentifier) expr).qualifiedName();
        } else {
            value = "?";
        }
        if (match(SqlTokenType.PRECEDING)) {
            return value + " PRECEDING";
        }
        if (match(SqlTokenType.FOLLOWING)) {
            return value + " FOLLOWING";
        }
        return value;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    private SqlCaseExpr parseCase() {
        expect(SqlTokenType.CASE);
        SqlCaseExpr cse = new SqlCaseExpr();
        if (!is(SqlTokenType.WHEN)) {
            cse.setValue(parseExpr());
        }
        while (match(SqlTokenType.WHEN)) {
            SqlExpr when = parseExpr();
            expect(SqlTokenType.THEN);
            cse.addWhenThen(when, parseExpr());
        }
        if (match(SqlTokenType.ELSE)) {
            cse.setElseExpr(parseExpr());
        }
        expect(SqlTokenType.END);
        return cse;
    }

    private SqlCastExpr parseCast() {
        expect(SqlTokenType.CAST);
        expect(SqlTokenType.LPAREN);
        SqlCastExpr cast = new SqlCastExpr();
        cast.setExpr(parseExpr());
        expect(SqlTokenType.AS);
        cast.setDataType(parseDataType());
        expect(SqlTokenType.RPAREN);
        return cast;
    }

    private String parseDataType() {
        StringBuilder sb = new StringBuilder();
        sb.append(consumeIdentRaw());
        if (match(SqlTokenType.LPAREN)) {
            sb.append('(');
            sb.append(consumeRawUntilType(SqlTokenType.RPAREN));
            expect(SqlTokenType.RPAREN);
            sb.append(')');
        }
        while (is(SqlTokenType.UNSIGNED) || is(SqlTokenType.ZEROFILL) || is(SqlTokenType.VARYING)
                || is(SqlTokenType.PRECISION) || is(SqlTokenType.ZONE) || is(SqlTokenType.WITHOUT)
                || isIdent("TIME")) {
            sb.append(' ').append(token.text());
            next();
        }
        return sb.toString();
    }

    private SqlIdentifier parseName() {
        SqlIdentifier id = new SqlIdentifier();
        String raw = consumeIdentRaw();
        if (isQuoted(raw)) {
            id.setQuoted(true);
        }
        id.addName(unquote(raw));
        while (is(SqlTokenType.DOT)) {
            if (lexer.peek().type() == SqlTokenType.STAR) {
                break;
            }
            next();
            id.addName(unquote(consumeIdentRaw()));
        }
        return id;
    }

    private boolean isQueryStart() {
        return is(SqlTokenType.SELECT) || is(SqlTokenType.WITH) || is(SqlTokenType.VALUES);
    }

    private boolean identLike() {
        return is(SqlTokenType.IDENT) || (token.type() != null && token.type().keyword()
                && !isAliasStop(token.type(), true));
    }

    private boolean isIdent(String word) {
        return is(SqlTokenType.IDENT) && token.textEqualsIgnoreCase(word);
    }

    private String consumeIdentRaw() {
        if (!(is(SqlTokenType.IDENT) || token.type().keyword())) {
            throw error("expected identifier");
        }
        String raw = token.text();
        next();
        return raw;
    }

    private String consumeStringRaw() {
        if (!is(SqlTokenType.STRING)) {
            throw error("expected string");
        }
        String raw = token.text();
        next();
        return raw;
    }

    private boolean isStmtSeparator() {
        return is(SqlTokenType.SEMICOLON) || is(SqlTokenType.GO);
    }

    private boolean atStmtBreak() {
        return isStmtSeparator() || is(SqlTokenType.EOF);
    }

    private static boolean isIndexObject(String objectType) {
        return objectType != null && "INDEX".equalsIgnoreCase(objectType);
    }

    private static boolean isRoutineObject(String objectType) {
        if (objectType == null) {
            return false;
        }
        return "PROCEDURE".equalsIgnoreCase(objectType)
                || "FUNCTION".equalsIgnoreCase(objectType)
                || "TRIGGER".equalsIgnoreCase(objectType)
                || "EVENT".equalsIgnoreCase(objectType);
    }

    /**
     * LPAREN 已消费；跳过到匹配的 RPAREN（含消费该 RPAREN），返回括号内原文。
     *
     * @return 括号内 token 文本（不含外层括号）
     */
    private String skipBalancedParensContent() {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (!is(SqlTokenType.EOF) && depth > 0) {
            if (is(SqlTokenType.LPAREN)) {
                depth++;
                appendRawToken(sb);
                next();
            } else if (is(SqlTokenType.RPAREN)) {
                depth--;
                if (depth == 0) {
                    next();
                    break;
                }
                appendRawToken(sb);
                next();
            } else {
                appendRawToken(sb);
                next();
            }
        }
        return sb.toString();
    }

    private String consumeRawAllowingBeginEnd() {
        return trailingRawAllowingBeginEnd(0);
    }

    /**
     * 吞掉过程体等尾部：BEGIN/END 配对内允许分号；CASE … END / END IF 等不误关 BEGIN。
     *
     * @param beginDepth 起始 BEGIN 深度（parseBeginBlock 传入 1）
     * @return 尾部原文
     */
    private String trailingRawAllowingBeginEnd(int beginDepth) {
        StringBuilder sb = new StringBuilder();
        int caseDepth = 0;
        while (!is(SqlTokenType.EOF)) {
            if (beginDepth == 0 && caseDepth == 0 && isStmtSeparator()) {
                break;
            }
            if (is(SqlTokenType.BEGIN)) {
                beginDepth++;
                appendRawToken(sb);
                next();
                continue;
            }
            if (is(SqlTokenType.CASE)) {
                caseDepth++;
                appendRawToken(sb);
                next();
                continue;
            }
            if (is(SqlTokenType.END)) {
                appendRawToken(sb);
                next();
                if (is(SqlTokenType.IF) || is(SqlTokenType.CASE) || isIdent("WHILE")
                        || isIdent("LOOP") || isIdent("REPEAT")) {
                    if (is(SqlTokenType.CASE) && caseDepth > 0) {
                        caseDepth--;
                    }
                    appendRawToken(sb);
                    next();
                } else if (caseDepth > 0) {
                    caseDepth--;
                } else if (beginDepth > 0) {
                    beginDepth--;
                }
                continue;
            }
            appendRawToken(sb);
            next();
        }
        return sb.toString();
    }

    private void appendRawToken(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(token.text());
    }

    private String consumeRawUntilSemi() {
        StringBuilder sb = new StringBuilder();
        while (!atStmtBreak()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token.text());
            next();
        }
        return sb.toString();
    }

    private String consumeRawUntilType(SqlTokenType end) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (!is(SqlTokenType.EOF)) {
            if (depth == 0 && is(end)) {
                break;
            }
            if (is(SqlTokenType.LPAREN)) {
                depth++;
            } else if (is(SqlTokenType.RPAREN)) {
                depth--;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token.text());
            next();
        }
        return sb.toString();
    }

    private String consumeRawUntilClause() {
        StringBuilder sb = new StringBuilder();
        while (!is(SqlTokenType.EOF) && !is(SqlTokenType.SEMICOLON) && !isAliasStop(token.type(), false)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token.text());
            next();
        }
        return sb.toString();
    }

    private static boolean isQuoted(String raw) {
        if (raw == null || raw.length() < 2) {
            return false;
        }
        char a = raw.charAt(0);
        char b = raw.charAt(raw.length() - 1);
        return a == '`' && b == '`'
                || a == '"' && b == '"'
                || a == '\'' && b == '\''
                || a == '[' && b == ']';
    }

    private static String unquote(String raw) {
        if (!isQuoted(raw)) {
            return raw;
        }
        char open = raw.charAt(0);
        String inner = raw.substring(1, raw.length() - 1);
        if (open == '`') {
            return inner.replace("``", "`");
        }
        if (open == '"') {
            return inner.replace("\"\"", "\"");
        }
        if (open == '\'') {
            return inner.replace("''", "'");
        }
        return inner.replace("]]", "]");
    }

    private boolean is(SqlTokenType type) {
        return token.type() == type;
    }

    private boolean match(SqlTokenType type) {
        if (is(type)) {
            next();
            return true;
        }
        return false;
    }

    private void expect(SqlTokenType type) {
        if (!match(type)) {
            throw error("expected " + type + " but got " + token.type());
        }
    }

    private void next() {
        token = lexer.next();
    }

    private SqlParseException error(String message) {
        return new SqlParseException(message, token.line(), token.column(),
                lexer.snippet(token.start()));
    }
}
