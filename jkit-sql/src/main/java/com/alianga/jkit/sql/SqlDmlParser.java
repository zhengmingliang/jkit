package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlInsertBranch;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlUpdate;

import java.util.ArrayList;
import java.util.List;

/**
 * INSERT / UPDATE / DELETE / MERGE 解析协作类，共享 {@link SqlParser} 记号游标。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
final class SqlDmlParser {
    private final SqlParser p;

    SqlDmlParser(SqlParser parser) {
        this.p = parser;
    }

    /** SQL Server OPTION / Exasol PREFERRING 等 DML 尾。 */
    private void consumeDmlDialectTails(SqlUpdate update) {
        consumeOptionOrPreferring();
    }

    private void consumeOptionOrPreferring() {
        while (true) {
            if (p.token != null && p.token.textEqualsIgnoreCase("OPTION")
                    && (p.is(SqlTokenType.IDENT) || (p.token.type() != null && p.token.type().keyword()))) {
                p.next();
                if (p.match(SqlTokenType.LPAREN)) {
                    p.skipBalancedParensContent();
                }
            } else if (p.isIdent("PREFERRING")) {
                p.next();
                while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                        && !p.is(SqlTokenType.GO)
                        && !(p.token.textEqualsIgnoreCase("OPTION"))) {
                    // 停在下一语句或 OPTION
                    if (p.is(SqlTokenType.INSERT) || p.is(SqlTokenType.UPDATE)
                            || p.is(SqlTokenType.DELETE) || p.is(SqlTokenType.SELECT)
                            || p.is(SqlTokenType.CREATE) || p.is(SqlTokenType.DROP)
                            || p.is(SqlTokenType.MERGE) || p.is(SqlTokenType.WITH)) {
                        break;
                    }
                    p.next();
                }
            } else {
                break;
            }
        }
    }

    private void parseAssignList(List<SqlBinaryExpr> target) {
        do {
            SqlExpr left;
            if (p.match(SqlTokenType.LPAREN)) {
                // UPDATE t SET (a, b, c) = (1, 2, 3) / (VALUES …)
                SqlListExpr cols = new SqlListExpr();
                do {
                    cols.add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
                left = cols;
            } else {
                left = p.parseName();
                // PG：SET listes[0] = 1 / listes[(select …)] = / listes[0:3] =
                while (p.match(SqlTokenType.LBRACKET)) {
                    if (p.is(SqlTokenType.RBRACKET)) {
                        p.next();
                        continue;
                    }
                    SqlExpr from = p.exprParser.parseExpr();
                    if (p.match(SqlTokenType.COLON)) {
                        SqlExpr to = p.exprParser.parseExpr();
                        SqlFunctionExpr slice = new SqlFunctionExpr();
                        slice.setName(SqlIdentifier.of("[]"));
                        slice.addArgument(left);
                        slice.addArgument(from);
                        slice.addArgument(to);
                        left = slice;
                    } else if (p.is(SqlTokenType.NAMED_BIND) && p.token.text() != null
                            && p.token.text().length() > 1 && p.token.text().charAt(0) == ':') {
                        String num = p.token.text().substring(1);
                        p.next();
                        SqlFunctionExpr slice = new SqlFunctionExpr();
                        slice.setName(SqlIdentifier.of("[]"));
                        slice.addArgument(left);
                        slice.addArgument(from);
                        slice.addArgument(SqlIdentifier.of(num));
                        left = slice;
                    } else {
                        left = SqlBinaryExpr.of(left, SqlBinaryOp.SUBSCRIPT, from);
                    }
                    p.expect(SqlTokenType.RBRACKET);
                }
            }
            // MySQL := 与 =
            if (p.match(SqlTokenType.ASSIGN)) {
                // keep as EQ in AST
            } else {
                p.expect(SqlTokenType.EQ);
            }
            SqlExpr right = p.exprParser.parseExpr();
            target.add(SqlBinaryExpr.of(left, SqlBinaryOp.EQ, right));
        } while (p.match(SqlTokenType.COMMA));
    }

    SqlDelete parseDelete() {
        p.expect(SqlTokenType.DELETE);
        SqlDelete delete = new SqlDelete();
        while (p.is(SqlTokenType.HINT)) {
            p.next(); // 游离 hint：DELETE /*+ INDEX(t1 i1) */ FROM …
        }
        if (p.match(SqlTokenType.LOW_PRIORITY)) {
            delete.setLowPriority(true);
        }
        if (p.match(SqlTokenType.QUICK)) {
            delete.setQuick(true);
        }
        if (p.match(SqlTokenType.IGNORE)) {
            delete.setIgnore(true);
        }
        String fp = parseForcePartitionClause();
        if (fp != null) {
            delete.setForcePartition(fp);
        }
        while (p.is(SqlTokenType.HINT)) {
            p.next();
        }
        if (p.match(SqlTokenType.FROM)) {
            SqlTableSource first = p.selectParser.parseTableSource();
            if (p.is(SqlTokenType.COMMA) && first instanceof SqlTable) {
                // MySQL 多表删除第二形式：DELETE FROM a1, a2 USING t1 a1 JOIN t2 a2
                delete.targets().add(((SqlTable) first).name());
                while (p.match(SqlTokenType.COMMA)) {
                    delete.targets().add(p.parseName());
                }
                p.expect(SqlTokenType.USING);
                delete.setFrom(p.selectParser.parseJoinedTable());
                delete.setUsingKeyword(true);
            } else {
                delete.setTable(p.selectParser.parseJoinChain(first));
                // PG: DELETE FROM t USING s WHERE …
                if (p.match(SqlTokenType.USING)) {
                    delete.setFrom(p.selectParser.parseJoinedTable());
                    delete.setUsingKeyword(true);
                }
            }
        } else if (p.identLike() || (p.token.type() != null && p.token.type().keyword()
                && !p.is(SqlTokenType.WHERE) && !p.is(SqlTokenType.SET))) {
            // DELETE t1.*, t2.* FROM …  / DELETE a FROM users a …
            if (lookingAtDeleteStarTargets()) {
                do {
                    SqlIdentifier target = p.parseName();
                    if (p.match(SqlTokenType.DOT)) {
                        p.expect(SqlTokenType.STAR);
                    }
                    delete.targets().add(target);
                } while (p.match(SqlTokenType.COMMA));
                if (p.match(SqlTokenType.FROM)) {
                    delete.setFrom(p.selectParser.parseJoinedTable());
                }
            } else {
                delete.setTable(p.selectParser.parseJoinedTable());
                if (p.match(SqlTokenType.USING)) {
                    delete.setFrom(p.selectParser.parseJoinedTable());
                    delete.setUsingKeyword(true);
                } else if (p.match(SqlTokenType.FROM)) {
                    delete.setFrom(p.selectParser.parseJoinedTable());
                }
            }
        }
        delete.setOutputInto(parseOutputClause(delete.output()));
        // SQL Server：DELETE t OUTPUT … FROM src …
        if (delete.from() == null && p.match(SqlTokenType.FROM)) {
            delete.setFrom(p.selectParser.parseJoinedTable());
        }
        if (p.match(SqlTokenType.WHERE)) {
            delete.setWhere(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.ORDER)) {
            p.expect(SqlTokenType.BY);
            p.selectParser.parseOrderBy(delete.orderBy());
        }
        if (p.is(SqlTokenType.LIMIT)) {
            delete.setLimit(p.selectParser.parseLimit());
        }
        if (p.match(SqlTokenType.RETURNING)) {
            delete.setReturning(parseReturningExpr());
        }
        if (delete.output().isEmpty()) {
            delete.setOutputInto(parseOutputClause(delete.output()));
        }
        consumeOptionOrPreferring();
        return delete;
    }

    SqlInsert parseInsert(boolean replace) {
        p.next();
        SqlInsert insert = new SqlInsert();
        while (p.is(SqlTokenType.HINT)) {
            p.next(); // INSERT /*+ hint */ INTO …
        }
        if (!replace && p.isIdent("OVERWRITE")) {
            // Hive：INSERT OVERWRITE [TABLE] t [PARTITION (...)] SELECT …
            p.next();
            insert.setOverwrite(true);
        }
        if (!replace && !insert.overwrite() && p.match(SqlTokenType.DELAYED)) {
            insert.setDelayed(true);
        } else if (p.match(SqlTokenType.LOW_PRIORITY)) {
            insert.setLowPriority(true);
        } else {
            insert.setHighPriority(p.match(SqlTokenType.HIGH_PRIORITY));
        }
        if (p.match(SqlTokenType.IGNORE)) {
            insert.setIgnore(true);
        }
        if (!replace && (p.is(SqlTokenType.ALL) || p.is(SqlTokenType.FIRST))) {
            return parseMultiInsert();
        }
        p.match(SqlTokenType.INTO);
        // Hive / 部分引擎：INSERT INTO TABLE t
        if (p.match(SqlTokenType.TABLE)) {
            insert.setTableKeyword(true);
        }
        insert.setReplace(replace);
        insert.setTable(SqlTable.of(p.parseName()));
        if (p.is(SqlTokenType.PARTITION)) {
            p.next();
            if (p.match(SqlTokenType.LPAREN)) {
                insert.setPartitionRaw("(" + p.skipBalancedParensContent() + ")");
            }
        }
        p.selectParser.parseTableHints(insert.table());
        // PG：INSERT INTO t AS x / INSERT INTO t x …
        String insAlias = p.parseAlias();
        if (insAlias != null) {
            insert.table().setAlias(insAlias);
        }
        if (p.match(SqlTokenType.LPAREN) && !p.isQueryStart()) {
            if (!p.is(SqlTokenType.RPAREN)) {
                do {
                    insert.columns().add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
            }
            p.expect(SqlTokenType.RPAREN);
        }
        insert.setOutputInto(parseOutputClause(insert.output()));
        // PG：OVERRIDING SYSTEM|USER VALUE（VALUE 属本子句，勿留给 VALUES 解析）
        if (p.isIdent("OVERRIDING")) {
            p.next();
            while (!p.is(SqlTokenType.SELECT) && !p.is(SqlTokenType.WITH)
                    && !p.is(SqlTokenType.DEFAULT) && !p.is(SqlTokenType.SET)
                    && !p.is(SqlTokenType.LPAREN) && !p.atStmtBreak()
                    && !p.is(SqlTokenType.EOF)
                    && !(p.is(SqlTokenType.VALUES) && p.lexer.peek() != null
                    && p.lexer.peek().type() == SqlTokenType.LPAREN)) {
                p.next();
            }
        }
        if (p.match(SqlTokenType.SET)) {
            parseAssignList(insert.setList());
            // MySQL 8：INSERT … SET … AS new ON DUPLICATE …
            if (p.is(SqlTokenType.AS) || (p.identLike() && p.lexer.peek() != null
                    && p.lexer.peek().type() == SqlTokenType.ON)) {
                if (p.match(SqlTokenType.AS)) {
                    if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                        p.next();
                    }
                } else if (p.identLike()) {
                    p.next();
                }
            }
        } else if (p.is(SqlTokenType.SELECT) || p.is(SqlTokenType.WITH)) {
            insert.setQuery(p.parseStatement());
        } else if (p.is(SqlTokenType.LPAREN)) {
            insert.setQuery(p.selectParser.parseSelect());
        } else if (p.match(SqlTokenType.DEFAULT)) {
            p.expect(SqlTokenType.VALUES);
            insert.valuesList().add(new ArrayList<SqlExpr>(0));
        } else if (p.match(SqlTokenType.VALUES) || p.match(SqlTokenType.VALUE)) {
            parseValuesRows(insert);
        }
        parseOnConflictOrDuplicate(insert);
        consumeOptionOrPreferring();
        if (p.match(SqlTokenType.RETURNING)) {
            insert.setReturning(parseReturningExpr());
        }
        if (insert.output().isEmpty()) {
            insert.setOutputInto(parseOutputClause(insert.output()));
        }
        return insert;
    }

    SqlMerge parseMerge() {
        p.expect(SqlTokenType.MERGE);
        while (p.is(SqlTokenType.HINT)) {
            p.next();
        }
        p.expect(SqlTokenType.INTO);
        SqlMerge merge = new SqlMerge();
        merge.setInto(p.selectParser.parseTableSource());
        p.expect(SqlTokenType.USING);
        merge.setUsing(p.selectParser.parseTableSource());
        p.expect(SqlTokenType.ON);
        merge.setOn(p.exprParser.parseExpr());
        while (p.match(SqlTokenType.WHEN)) {
            SqlMergeWhen when = new SqlMergeWhen();
            boolean not = p.match(SqlTokenType.NOT);
            p.expect(SqlTokenType.MATCHED);
            if (not) {
                if (p.match(SqlTokenType.BY)) {
                    if (p.match(SqlTokenType.SOURCE)) {
                        when.setKind(SqlMergeWhen.MatchKind.NOT_MATCHED_BY_SOURCE);
                    } else {
                        p.expect(SqlTokenType.TARGET);
                        when.setKind(SqlMergeWhen.MatchKind.NOT_MATCHED_BY_TARGET);
                    }
                } else {
                    when.setKind(SqlMergeWhen.MatchKind.NOT_MATCHED);
                }
            } else {
                when.setKind(SqlMergeWhen.MatchKind.MATCHED);
                if (p.match(SqlTokenType.BY)) {
                    // WHEN MATCHED BY TARGET — 罕见，按 MATCHED 处理
                    p.match(SqlTokenType.TARGET);
                    p.match(SqlTokenType.SOURCE);
                }
            }
            if (p.match(SqlTokenType.AND)) {
                when.setAndPredicate(p.exprParser.parseExpr());
            }
            p.expect(SqlTokenType.THEN);
            if (p.match(SqlTokenType.UPDATE)) {
                SqlUpdate upd = new SqlUpdate();
                p.expect(SqlTokenType.SET);
                parseAssignList(upd.setList());
                when.setUpdate(upd);
            } else if (p.match(SqlTokenType.INSERT)) {
                SqlInsert ins = new SqlInsert();
                p.match(SqlTokenType.INTO);
                if (p.match(SqlTokenType.LPAREN)) {
                    do {
                        ins.columns().add(p.parseName());
                    } while (p.match(SqlTokenType.COMMA));
                    p.expect(SqlTokenType.RPAREN);
                }
                p.expect(SqlTokenType.VALUES);
                parseValuesRows(ins);
                when.setInsert(ins);
            } else if (p.match(SqlTokenType.DELETE)) {
                when.setDelete(true);
            } else {
                throw p.error("expected UPDATE, INSERT or DELETE after THEN");
            }
            merge.whens().add(when);
        }
        merge.setOutputInto(parseOutputClause(merge.output()));
        return merge;
    }

    /**
     * Oracle INSERT ALL / INSERT FIRST … SELECT。
     */
    private SqlInsert parseMultiInsert() {
        SqlInsert insert = new SqlInsert();
        if (p.match(SqlTokenType.ALL)) {
            insert.setInsertAll(true);
        } else {
            p.expect(SqlTokenType.FIRST);
            insert.setInsertFirst(true);
        }
        while (p.is(SqlTokenType.WHEN) || p.is(SqlTokenType.INTO) || p.is(SqlTokenType.ELSE)) {
            SqlInsertBranch branch = new SqlInsertBranch();
            if (p.match(SqlTokenType.ELSE)) {
                branch.setElseBranch(true);
            } else if (p.match(SqlTokenType.WHEN)) {
                branch.setWhen(p.exprParser.parseExpr());
                p.expect(SqlTokenType.THEN);
            }
            p.expect(SqlTokenType.INTO);
            p.match(SqlTokenType.TABLE);
            branch.setTable(SqlTable.of(p.parseName()));
            if (p.match(SqlTokenType.LPAREN) && !p.isQueryStart()) {
                if (!p.is(SqlTokenType.RPAREN)) {
                    do {
                        branch.columns().add(p.parseName());
                    } while (p.match(SqlTokenType.COMMA));
                }
                p.expect(SqlTokenType.RPAREN);
            }
            p.expect(SqlTokenType.VALUES);
            p.expect(SqlTokenType.LPAREN);
            if (!p.is(SqlTokenType.RPAREN)) {
                do {
                    branch.values().add(p.exprParser.parseExpr());
                } while (p.match(SqlTokenType.COMMA));
            }
            p.expect(SqlTokenType.RPAREN);
            insert.branches().add(branch);
            if (branch.elseBranch()) {
                break;
            }
        }
        if (insert.branches().isEmpty()) {
            throw p.error("expected INTO after INSERT ALL/FIRST");
        }
        if (p.is(SqlTokenType.SELECT) || p.is(SqlTokenType.WITH) || p.is(SqlTokenType.LPAREN)) {
            insert.setQuery(p.parseStatement());
        } else {
            throw p.error("expected SELECT after INSERT ALL/FIRST branches");
        }
        return insert;
    }

    private void parseOnConflictOrDuplicate(SqlInsert insert) {
        if (!p.match(SqlTokenType.ON)) {
            return;
        }
        if (p.match(SqlTokenType.DUPLICATE)) {
            p.expect(SqlTokenType.KEY);
            p.expect(SqlTokenType.UPDATE);
            parseAssignList(insert.duplicateUpdates());
            return;
        }
        if (p.match(SqlTokenType.CONFLICT)) {
            insert.setOnConflict(true);
            if (p.match(SqlTokenType.LPAREN)) {
                do {
                    insert.conflictTarget().add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
            } else if (p.match(SqlTokenType.ON)) {
                p.expect(SqlTokenType.CONSTRAINT);
                insert.setConflictConstraint(p.parseName());
            }
            // PG：ON CONFLICT (…) WHERE predicate DO …
            if (p.match(SqlTokenType.WHERE)) {
                p.exprParser.parseExpr();
            }
            p.expect(SqlTokenType.DO);
            if (p.match(SqlTokenType.NOTHING)) {
                insert.setConflictDoNothing(true);
            } else {
                p.expect(SqlTokenType.UPDATE);
                p.expect(SqlTokenType.SET);
                parseAssignList(insert.duplicateUpdates());
            }
            return;
        }
        throw p.error("expected DUPLICATE KEY or CONFLICT after ON");
    }

    private SqlTable parseOutputClause(List<SqlExpr> target) {
        if (!p.match(SqlTokenType.OUTPUT)) {
            return null;
        }
        do {
            target.add(parseReturningItem());
        } while (p.match(SqlTokenType.COMMA));
        if (!p.match(SqlTokenType.INTO)) {
            return null;
        }
        SqlTable into = SqlTable.of(parseOutputIntoName());
        // 可选列清单 OUTPUT … INTO tgt (c1, c2)
        if (p.match(SqlTokenType.LPAREN)) {
            p.skipBalancedParensContent();
        }
        return into;
    }

    /**
     * OUTPUT INTO 目标：普通名 / {@code dbo.archive} / {@code @out} / {@code #tmp}。
     */
    private SqlIdentifier parseOutputIntoName() {
        if (p.is(SqlTokenType.VARIABLE)) {
            SqlIdentifier id = SqlIdentifier.of(p.token.text());
            p.next();
            return id;
        }
        return p.parseName();
    }

    /**
     * RETURNING 列表：单列直接返回表达式；多列包进 {@link com.alianga.jkit.sql.ast.SqlListExpr}
     *（format 时不加外层括号）。
     */
    private SqlExpr parseReturningExpr() {
        SqlExpr first = parseReturningItem();
        SqlExpr result;
        if (!p.match(SqlTokenType.COMMA)) {
            result = first;
        } else {
            SqlListExpr list = new SqlListExpr();
            list.add(first);
            do {
                list.add(parseReturningItem());
            } while (p.match(SqlTokenType.COMMA));
            result = list;
        }
        // Oracle：RETURNING … INTO var[, var2]
        if (p.match(SqlTokenType.INTO)) {
            do {
                p.exprParser.parsePrimary();
            } while (p.match(SqlTokenType.COMMA));
        }
        return result;
    }

    /** RETURNING / OUTPUT 项：{@code expr [AS alias]} / {@code old.*} / {@code new.*} / {@code DELETED.*}。 */
    private SqlExpr parseReturningItem() {
        SqlExpr expr = p.exprParser.parseExpr();
        if (expr instanceof SqlIdentifier && p.match(SqlTokenType.DOT) && p.match(SqlTokenType.STAR)) {
            SqlAllColumns all = new SqlAllColumns();
            all.setOwner((SqlIdentifier) expr);
            expr = all;
        }
        String alias = p.parseAlias();
        if (alias != null) {
            SqlFunctionExpr as = new SqlFunctionExpr();
            as.setName(SqlIdentifier.of("AS"));
            as.addArgument(expr);
            as.addArgument(SqlIdentifier.of(alias));
            return as;
        }
        return expr;
    }

    SqlUpdate parseUpdate() {
        p.expect(SqlTokenType.UPDATE);
        SqlUpdate update = new SqlUpdate();
        while (p.is(SqlTokenType.HINT)) {
            p.next(); // UPDATE /*+ hint */ t SET …
        }
        if (p.match(SqlTokenType.LOW_PRIORITY)) {
            update.setLowPriority(true);
        }
        if (p.match(SqlTokenType.IGNORE)) {
            update.setIgnore(true);
        }
        String fp = parseForcePartitionClause();
        if (fp != null) {
            update.setForcePartition(fp);
        }
        while (p.is(SqlTokenType.HINT)) {
            p.next();
        }
        update.setTable(p.selectParser.parseJoinedTable());
        p.expect(SqlTokenType.SET);
        parseAssignList(update.setList());
        update.setOutputInto(parseOutputClause(update.output()));
        if (p.match(SqlTokenType.FROM)) {
            update.setFrom(p.selectParser.parseJoinedTable());
        }
        if (p.match(SqlTokenType.WHERE)) {
            update.setWhere(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.ORDER)) {
            p.expect(SqlTokenType.BY);
            p.selectParser.parseOrderBy(update.orderBy());
        }
        if (p.is(SqlTokenType.LIMIT)) {
            update.setLimit(p.selectParser.parseLimit());
        }
        if (p.match(SqlTokenType.RETURNING)) {
            update.setReturning(parseReturningExpr());
        }
        if (update.output().isEmpty()) {
            update.setOutputInto(parseOutputClause(update.output()));
        }
        consumeDmlDialectTails(update);
        return update;
    }

    private void parseValuesRows(SqlInsert insert) {
        do {
            p.expect(SqlTokenType.LPAREN);
            List<SqlExpr> row = new ArrayList<SqlExpr>(4);
            if (!p.is(SqlTokenType.RPAREN)) {
                do {
                    row.add(p.exprParser.parseExpr());
                } while (p.match(SqlTokenType.COMMA));
            }
            p.expect(SqlTokenType.RPAREN);
            insert.valuesList().add(row);
        } while (p.match(SqlTokenType.COMMA));
    }

    /**
     * ODPS/MaxCompute：{@code FORCE PARTITION 'pt'} / {@code FORCE ALL PARTITIONS}。
     *
     * @return 原文或 null
     */
    private String parseForcePartitionClause() {
        if (!p.is(SqlTokenType.FORCE)) {
            return null;
        }
        SqlToken peeked = p.lexer.peek();
        if (peeked == null) {
            return null;
        }
        boolean all = peeked.textEqualsIgnoreCase("ALL");
        boolean part = peeked.type() == SqlTokenType.PARTITION
                || peeked.textEqualsIgnoreCase("PARTITION")
                || peeked.textEqualsIgnoreCase("PARTITIONS");
        if (!all && !part) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.token.text());
        p.next();
        if (p.isIdent("ALL")) {
            sb.append(' ').append(p.token.text());
            p.next();
            if (p.is(SqlTokenType.PARTITION) || p.isIdent("PARTITIONS") || p.isIdent("PARTITION")) {
                sb.append(' ').append(p.token.text());
                p.next();
            }
        } else {
            sb.append(' ').append(p.token.text());
            p.next();
            if (p.is(SqlTokenType.STRING) || p.is(SqlTokenType.NUMBER) || p.identLike()) {
                sb.append(' ').append(p.token.text());
                p.next();
            } else if (p.match(SqlTokenType.LPAREN)) {
                sb.append('(').append(p.skipBalancedParensContent()).append(')');
            }
        }
        return sb.toString();
    }

    /** {@code DELETE t1.*, t2.* FROM …} 目标形态。 */
    private boolean lookingAtDeleteStarTargets() {
        if (!p.identLike() && !(p.token.type() != null && p.token.type().keyword())) {
            return false;
        }
        SqlToken a = p.lexer.peek();
        if (a != null && a.type() == SqlTokenType.DOT) {
            // t1.*  — 需要再看一眼，但环形 peek 只有一层；用 text 扫描不划算。
            // 约定：下一记号为 DOT 即视为 star-target 形态（随后 expect STAR）。
            return true;
        }
        if (a != null && a.type() == SqlTokenType.COMMA) {
            // t1, t2 FROM … 多目标（无 .*）也走 targets 列表
            return true;
        }
        return false;
    }
}
