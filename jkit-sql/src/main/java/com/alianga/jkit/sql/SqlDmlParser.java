package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlInsertBranch;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlUpdate;

import java.util.ArrayList;
import java.util.List;

/**
 * INSERT / UPDATE / DELETE / MERGE 解析协作类，共享 {@link SqlParser} 记号游标。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
final class SqlDmlParser {
    private final SqlParser p;

    SqlDmlParser(SqlParser parser) {
        this.p = parser;
    }

    private void parseAssignList(List<SqlBinaryExpr> target) {
        do {
            SqlExpr left = p.parseName();
            p.expect(SqlTokenType.EQ);
            SqlExpr right = p.exprParser.parseExpr();
            target.add(SqlBinaryExpr.of(left, SqlBinaryOp.EQ, right));
        } while (p.match(SqlTokenType.COMMA));
    }

    SqlDelete parseDelete() {
        p.expect(SqlTokenType.DELETE);
        p.match(SqlTokenType.IGNORE);
        SqlDelete delete = new SqlDelete();
        if (p.match(SqlTokenType.FROM)) {
            delete.setTable(p.selectParser.parseJoinedTable());
            // PG: DELETE FROM t USING s WHERE …
            if (p.match(SqlTokenType.USING)) {
                delete.setFrom(p.selectParser.parseJoinedTable());
                delete.setUsingKeyword(true);
            }
        } else if (p.identLike()) {
            delete.setTable(p.selectParser.parseJoinedTable());
            if (p.match(SqlTokenType.USING)) {
                delete.setFrom(p.selectParser.parseJoinedTable());
                delete.setUsingKeyword(true);
            } else if (p.match(SqlTokenType.FROM)) {
                delete.setFrom(p.selectParser.parseJoinedTable());
            }
        }
        parseOutputClause(delete.output());
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
            parseOutputClause(delete.output());
        }
        return delete;
    }

    SqlInsert parseInsert(boolean replace) {
        p.next();
        SqlInsert early = null;
        if (!replace && p.match(SqlTokenType.DELAYED)) {
            early = new SqlInsert();
            early.setDelayed(true);
        } else {
            p.match(SqlTokenType.LOW_PRIORITY);
            p.match(SqlTokenType.HIGH_PRIORITY);
        }
        p.match(SqlTokenType.IGNORE);
        if (!replace && (p.is(SqlTokenType.ALL) || p.is(SqlTokenType.FIRST))) {
            return parseMultiInsert();
        }
        p.match(SqlTokenType.INTO);
        // Hive / 部分引擎：INSERT INTO TABLE t
        p.match(SqlTokenType.TABLE);
        SqlInsert insert = early != null ? early : new SqlInsert();
        insert.setReplace(replace);
        insert.setTable(SqlTable.of(p.parseName()));
        p.selectParser.parseTableHints(insert.table());
        if (p.match(SqlTokenType.LPAREN) && !p.isQueryStart()) {
            if (!p.is(SqlTokenType.RPAREN)) {
                do {
                    insert.columns().add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
            }
            p.expect(SqlTokenType.RPAREN);
        }
        parseOutputClause(insert.output());
        if (p.match(SqlTokenType.SET)) {
            parseAssignList(insert.setList());
        } else if (p.is(SqlTokenType.SELECT) || p.is(SqlTokenType.WITH) || p.is(SqlTokenType.LPAREN)) {
            insert.setQuery(p.parseStatement());
        } else if (p.match(SqlTokenType.VALUES) || p.match(SqlTokenType.VALUE)) {
            parseValuesRows(insert);
        }
        parseOnConflictOrDuplicate(insert);
        if (p.match(SqlTokenType.RETURNING)) {
            insert.setReturning(parseReturningExpr());
        }
        if (insert.output().isEmpty()) {
            parseOutputClause(insert.output());
        }
        return insert;
    }

    SqlMerge parseMerge() {
        p.expect(SqlTokenType.MERGE);
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
        parseOutputClause(merge.output());
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

    private void parseOutputClause(List<SqlExpr> target) {
        if (!p.match(SqlTokenType.OUTPUT)) {
            return;
        }
        do {
            target.add(p.exprParser.parseExpr());
        } while (p.match(SqlTokenType.COMMA));
        // OUTPUT … INTO @table / table — 暂不结构化，跳过 INTO 后的简单表名
        if (p.match(SqlTokenType.INTO)) {
            p.parseName();
        }
    }

    /**
     * RETURNING 列表：单列直接返回表达式；多列包进 {@link com.alianga.jkit.sql.ast.SqlListExpr}
     *（format 时不加外层括号）。
     */
    private SqlExpr parseReturningExpr() {
        SqlExpr first = p.exprParser.parseExpr();
        if (!p.match(SqlTokenType.COMMA)) {
            return first;
        }
        SqlListExpr list = new SqlListExpr();
        list.add(first);
        do {
            list.add(p.exprParser.parseExpr());
        } while (p.match(SqlTokenType.COMMA));
        return list;
    }

    SqlUpdate parseUpdate() {
        p.expect(SqlTokenType.UPDATE);
        p.match(SqlTokenType.IGNORE);
        SqlUpdate update = new SqlUpdate();
        update.setTable(p.selectParser.parseJoinedTable());
        p.expect(SqlTokenType.SET);
        parseAssignList(update.setList());
        parseOutputClause(update.output());
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
            parseOutputClause(update.output());
        }
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
}
