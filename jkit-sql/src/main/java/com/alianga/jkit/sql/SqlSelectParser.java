package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.ast.SqlWindowDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT / 表源 / JOIN / ORDER BY / LIMIT 解析协作类，共享 {@link SqlParser} 记号游标。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
final class SqlSelectParser {
    private final SqlParser p;

    SqlSelectParser(SqlParser parser) {
        this.p = parser;
    }

    private void consumeSelectHints(SqlSelect select) {
        while (p.is(SqlTokenType.HINT)) {
            select.addHint(p.token.text());
            p.next();
        }
    }

    /**
     * SQL Server {@code OPENJSON(...) WITH (...)} 等：WITH 后跟括号的 schema 定义。
     *
     * @param ft 表函数
     */
    private void parseFunctionTableWith(SqlFunctionTable ft) {
        if (!p.is(SqlTokenType.WITH) || p.lexer.peek().type() != SqlTokenType.LPAREN) {
            return;
        }
        p.next(); // WITH
        p.expect(SqlTokenType.LPAREN);
        String inner = p.skipBalancedParensContent();
        ft.setWithDefinition("(" + inner + ")");
    }

    SqlTableSource parseJoinedTable() {
        SqlTableSource left = parseTableSource();
        while (true) {
            SqlJoin.Type type = null;
            if (p.match(SqlTokenType.COMMA)) {
                type = SqlJoin.Type.COMMA;
            } else if (p.match(SqlTokenType.JOIN) || p.match(SqlTokenType.STRAIGHT_JOIN)
                    || p.match(SqlTokenType.INNER)) {
                p.match(SqlTokenType.JOIN);
                type = SqlJoin.Type.INNER;
                if (p.token.type() == SqlTokenType.STRAIGHT_JOIN) {
                    type = SqlJoin.Type.STRAIGHT;
                    p.next();
                }
            } else if (p.match(SqlTokenType.LEFT)) {
                p.match(SqlTokenType.OUTER);
                p.expect(SqlTokenType.JOIN);
                type = SqlJoin.Type.LEFT;
            } else if (p.match(SqlTokenType.RIGHT)) {
                p.match(SqlTokenType.OUTER);
                p.expect(SqlTokenType.JOIN);
                type = SqlJoin.Type.RIGHT;
            } else if (p.match(SqlTokenType.FULL)) {
                p.match(SqlTokenType.OUTER);
                p.expect(SqlTokenType.JOIN);
                type = SqlJoin.Type.FULL;
            } else if (p.match(SqlTokenType.CROSS)) {
                if (p.match(SqlTokenType.APPLY)) {
                    type = SqlJoin.Type.CROSS_APPLY;
                } else {
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.CROSS;
                }
            } else if (p.match(SqlTokenType.OUTER)) {
                p.expect(SqlTokenType.APPLY);
                type = SqlJoin.Type.OUTER_APPLY;
            } else if (p.match(SqlTokenType.NATURAL)) {
                p.match(SqlTokenType.LEFT);
                p.match(SqlTokenType.RIGHT);
                p.match(SqlTokenType.INNER);
                p.match(SqlTokenType.OUTER);
                p.match(SqlTokenType.JOIN);
                type = SqlJoin.Type.NATURAL;
            } else {
                break;
            }
            SqlJoin join = new SqlJoin();
            join.setJoinType(type);
            join.setLeft(left);
            join.setRight(parseTableSource());
            if (p.match(SqlTokenType.ON)) {
                join.setCondition(p.exprParser.parseExpr());
            } else if (p.match(SqlTokenType.USING)) {
                p.expect(SqlTokenType.LPAREN);
                List<SqlIdentifier> using = new ArrayList<SqlIdentifier>(2);
                do {
                    using.add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
                join.setUsing(using);
            }
            left = join;
        }
        return left;
    }

    SqlLimit parseLimit() {
        p.expect(SqlTokenType.LIMIT);
        SqlLimit limit = new SqlLimit();
        SqlExpr first = p.exprParser.parseExpr();
        if (p.match(SqlTokenType.COMMA)) {
            limit.setMysqlCommaStyle(true);
            limit.setOffset(first);
            limit.setRowCount(p.exprParser.parseExpr());
        } else {
            limit.setRowCount(first);
            if (p.match(SqlTokenType.OFFSET)) {
                limit.setOffset(p.exprParser.parseExpr());
            }
        }
        return limit;
    }

    private void parseLimitFetch(SqlSelect select) {
        if (p.is(SqlTokenType.LIMIT)) {
            select.setLimit(parseLimit());
        }
        if (p.match(SqlTokenType.OFFSET)) {
            SqlLimit limit = select.limit();
            if (limit == null) {
                limit = new SqlLimit();
                select.setLimit(limit);
            }
            limit.setOffset(p.exprParser.parsePrimary());
            p.match(SqlTokenType.ROW);
            p.match(SqlTokenType.ROWS);
        }
        if (p.match(SqlTokenType.FETCH)) {
            p.match(SqlTokenType.FIRST);
            p.match(SqlTokenType.NEXT);
            SqlLimit limit = select.limit();
            if (limit == null) {
                limit = new SqlLimit();
                select.setLimit(limit);
            }
            limit.setFetchStyle(true);
            limit.setRowCount(p.exprParser.parsePrimary());
            p.match(SqlTokenType.ROW);
            p.match(SqlTokenType.ROWS);
            p.match(SqlTokenType.ONLY);
            p.match(SqlTokenType.WITH);
            p.match(SqlTokenType.TIES);
        }
    }

    void parseOrderBy(List<SqlOrderByItem> list) {
        do {
            SqlOrderByItem item = new SqlOrderByItem();
            item.setExpr(p.exprParser.parseExpr());
            if (p.match(SqlTokenType.DESC)) {
                item.setAsc(false);
            } else {
                p.match(SqlTokenType.ASC);
            }
            if (p.isIdent("NULLS")) {
                p.next();
                item.setNulls("NULLS " + p.consumeIdentRaw().toUpperCase());
            }
            list.add(item);
        } while (p.match(SqlTokenType.COMMA));
    }

    SqlSelect parseSelect() {
        if (p.is(SqlTokenType.LPAREN)) {
            p.next();
            SqlSelect inner = parseSelect();
            p.expect(SqlTokenType.RPAREN);
            parseSelectTail(inner);
            // (SELECT ...) UNION (SELECT ...) ORDER BY / LIMIT 挂在集合运算链末端
            SqlSelect owner = inner;
            while (owner.union() != null) {
                owner = owner.union();
            }
            if (p.match(SqlTokenType.ORDER)) {
                p.expect(SqlTokenType.BY);
                parseOrderBy(owner.orderBy());
            }
            parseLimitFetch(owner);
            return inner;
        }
        if (p.is(SqlTokenType.VALUES)) {
            return parseValuesSelect();
        }
        p.expect(SqlTokenType.SELECT);
        SqlSelect select = new SqlSelect();
        consumeSelectHints(select);
        if (p.match(SqlTokenType.DISTINCT) || p.match(SqlTokenType.DISTINCTROW)) {
            select.setDistinct(true);
            if (p.match(SqlTokenType.ON)) {
                p.expect(SqlTokenType.LPAREN);
                do {
                    select.distinctOn().add(p.exprParser.parseExpr());
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
            }
        } else {
            p.match(SqlTokenType.ALL);
        }
        p.match(SqlTokenType.HIGH_PRIORITY);
        p.match(SqlTokenType.SQL_CALC_FOUND_ROWS);
        if (p.match(SqlTokenType.TOP)) {
            select.setTop(p.exprParser.parsePrimary());
            p.match(SqlTokenType.PERCENT);
            if (p.match(SqlTokenType.WITH)) {
                p.expect(SqlTokenType.TIES);
                select.setTopWithTies(true);
            }
        }
        consumeSelectHints(select);
        do {
            select.addSelectItem(parseSelectItem());
        } while (p.match(SqlTokenType.COMMA));
        parseSelectInto(select);
        if (p.match(SqlTokenType.FROM)) {
            select.setFrom(parseJoinedTable());
        }
        if (p.match(SqlTokenType.WHERE)) {
            select.setWhere(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.START)) {
            p.expect(SqlTokenType.WITH);
            select.setStartWith(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.CONNECT)) {
            p.expect(SqlTokenType.BY);
            p.match(SqlTokenType.NOWAIT);
            select.setConnectBy(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.GROUP)) {
            p.expect(SqlTokenType.BY);
            do {
                select.groupBy().add(p.exprParser.parseExpr());
            } while (p.match(SqlTokenType.COMMA));
            if (p.match(SqlTokenType.WITH) && p.match(SqlTokenType.ROLLUP)) {
                select.setGroupByRollup(true);
            }
        }
        if (p.match(SqlTokenType.HAVING)) {
            select.setHaving(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.WINDOW)) {
            do {
                SqlWindowDefinition window = new SqlWindowDefinition();
                window.setName(p.parseName());
                p.expect(SqlTokenType.AS);
                window.setSpec(p.exprParser.parseOver());
                select.windows().add(window);
            } while (p.match(SqlTokenType.COMMA));
        }
        if (p.match(SqlTokenType.ORDER)) {
            p.expect(SqlTokenType.BY);
            parseOrderBy(select.orderBy());
        }
        parseLimitFetch(select);
        // MySQL：INTO 也可出现在 FROM/WHERE/ORDER/LIMIT 之后（与 SELECT 列表后 INTO 二选一）
        parseSelectInto(select);
        if (p.match(SqlTokenType.FOR)) {
            p.expect(SqlTokenType.UPDATE);
            select.setForUpdate(true);
            if (p.match(SqlTokenType.OF)) {
                do {
                    select.forUpdateOf().add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
            }
            if (p.match(SqlTokenType.NOWAIT)) {
                select.setForUpdateWait("NOWAIT");
            } else if (p.match(SqlTokenType.SKIP)) {
                p.expect(SqlTokenType.LOCKED);
                select.setForUpdateWait("SKIP LOCKED");
            } else if (!p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)
                    && !p.is(SqlTokenType.UNION) && !p.is(SqlTokenType.INTERSECT)
                    && !p.is(SqlTokenType.EXCEPT) && !p.is(SqlTokenType.MINUS)
                    && !p.is(SqlTokenType.LOCK) && !SqlParser.isAliasStop(p.token.type())) {
                select.setForUpdateTail(p.consumeRawUntilClause());
            }
        }
        if (p.is(SqlTokenType.LOCK)) {
            p.next();
            p.expect(SqlTokenType.IN);
            p.expect(SqlTokenType.SHARE);
            p.expect(SqlTokenType.MODE);
            select.setLockInShare(true);
        }
        parseSelectTail(select);
        return select;
    }

    /**
     * MySQL {@code SELECT cols INTO dest FROM src} / {@code INTO @var} / {@code INTO OUTFILE}，
     * 以及 {@code SELECT … FROM … INTO @var|OUTFILE|DUMPFILE}（INTO 在 FROM 之后）。
     */
    private void parseSelectInto(SqlSelect select) {
        if (!p.match(SqlTokenType.INTO)) {
            return;
        }
        if (p.is(SqlTokenType.OUTFILE) || p.is(SqlTokenType.DUMPFILE)) {
            select.setIntoFileKind(p.token.text().toUpperCase());
            p.next();
            if (p.is(SqlTokenType.STRING)) {
                select.setIntoOutfile(p.token.text());
                p.next();
            } else {
                StringBuilder sb = new StringBuilder();
                while (!p.atStmtBreak() && !p.is(SqlTokenType.FROM) && !p.is(SqlTokenType.WHERE)
                        && !p.is(SqlTokenType.GROUP) && !p.is(SqlTokenType.ORDER)
                        && !p.is(SqlTokenType.LIMIT) && !p.is(SqlTokenType.HAVING)
                        && !p.is(SqlTokenType.UNION) && !p.is(SqlTokenType.INTERSECT)
                        && !p.is(SqlTokenType.EXCEPT) && !p.is(SqlTokenType.MINUS)
                        && !p.is(SqlTokenType.FOR) && !p.is(SqlTokenType.LOCK)
                        && !p.is(SqlTokenType.START) && !p.is(SqlTokenType.CONNECT)
                        && !p.is(SqlTokenType.WINDOW)) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(p.token.text());
                    p.next();
                }
                select.setIntoOutfile(sb.toString());
            }
            if (!p.is(SqlTokenType.FROM) && !p.atStmtBreak()) {
                StringBuilder rest = new StringBuilder();
                while (!p.atStmtBreak() && !p.is(SqlTokenType.FROM) && !p.is(SqlTokenType.UNION)
                        && !p.is(SqlTokenType.INTERSECT) && !p.is(SqlTokenType.EXCEPT)
                        && !p.is(SqlTokenType.MINUS)) {
                    if (rest.length() > 0) {
                        rest.append(' ');
                    }
                    rest.append(p.token.text());
                    p.next();
                }
                if (rest.length() > 0) {
                    String prev = select.intoOutfile() == null ? "" : select.intoOutfile();
                    select.setIntoOutfile((prev + " " + rest.toString()).trim());
                }
            }
            return;
        }
        if (p.is(SqlTokenType.VARIABLE)) {
            do {
                select.intoVariables().add(p.exprParser.parsePrimary());
            } while (p.match(SqlTokenType.COMMA));
            return;
        }
        p.match(SqlTokenType.TABLE);
        SqlTable into = SqlTable.of(p.parseName());
        select.setIntoTable(into);
    }

    private SqlSelectItem parseSelectItem() {
        SqlSelectItem item = new SqlSelectItem();
        if (p.is(SqlTokenType.STAR)) {
            p.next();
            item.setExpr(new SqlAllColumns());
            return item;
        }
        SqlExpr expr = p.exprParser.parseExpr();
        if (expr instanceof SqlIdentifier && p.match(SqlTokenType.DOT) && p.match(SqlTokenType.STAR)) {
            SqlAllColumns all = new SqlAllColumns();
            all.setOwner((SqlIdentifier) expr);
            item.setExpr(all);
        } else {
            item.setExpr(expr);
            item.setAlias(p.parseAlias());
        }
        return item;
    }

    private void parseSelectTail(SqlSelect select) {
        if (p.is(SqlTokenType.UNION) || p.is(SqlTokenType.INTERSECT)
                || p.is(SqlTokenType.EXCEPT) || p.is(SqlTokenType.MINUS)) {
            String op = p.token.text().toUpperCase();
            p.next();
            if (p.match(SqlTokenType.ALL)) {
                op = op + " ALL";
            } else {
                p.match(SqlTokenType.DISTINCT);
            }
            select.setUnionOp(op);
            select.setUnion(parseSelect());
        }
    }

    private void parseTableAlias(SqlTableSource source) {
        String alias = p.parseAlias();
        source.setAlias(alias);
        if (alias != null && p.match(SqlTokenType.LPAREN)) {
            do {
                source.columnAliases().add(p.parseName());
            } while (p.match(SqlTokenType.COMMA));
            p.expect(SqlTokenType.RPAREN);
        }
    }

    void parseTableHints(SqlTable table) {
        while (p.is(SqlTokenType.HINT)) {
            String h = p.token.text();
            p.next();
            if (table.optimizerHint() == null) {
                table.setOptimizerHint(h);
            } else {
                table.setOptimizerHint(table.optimizerHint() + " " + h);
            }
        }
        if (p.is(SqlTokenType.USE) || p.is(SqlTokenType.FORCE) || p.is(SqlTokenType.IGNORE)) {
            StringBuilder sb = new StringBuilder();
            sb.append(p.token.text());
            p.next();
            if (p.is(SqlTokenType.INDEX) || p.is(SqlTokenType.KEY)) {
                sb.append(' ').append(p.token.text());
                p.next();
            }
            // MySQL: FORCE/USE/IGNORE INDEX FOR JOIN|ORDER BY|GROUP BY (idx)
            if (p.match(SqlTokenType.FOR)) {
                sb.append(" FOR");
                if (p.is(SqlTokenType.JOIN)) {
                    sb.append(' ').append(p.token.text());
                    p.next();
                } else if (p.is(SqlTokenType.ORDER) || p.is(SqlTokenType.GROUP)) {
                    sb.append(' ').append(p.token.text());
                    p.next();
                    if (p.match(SqlTokenType.BY)) {
                        sb.append(" BY");
                    }
                }
            }
            if (p.match(SqlTokenType.LPAREN)) {
                sb.append('(');
                sb.append(p.consumeRawUntilType(SqlTokenType.RPAREN));
                p.expect(SqlTokenType.RPAREN);
                sb.append(')');
            }
            table.setIndexHint(sb.toString());
        }
        while (p.is(SqlTokenType.HINT)) {
            String h = p.token.text();
            p.next();
            if (table.optimizerHint() == null) {
                table.setOptimizerHint(h);
            } else {
                table.setOptimizerHint(table.optimizerHint() + " " + h);
            }
        }
    }

    /**
     * MySQL {@code PARTITION (p0, p1)} 表分区限定（位于表名之后、别名之前）。
     */
    private void parseTablePartition(SqlTable table) {
        if (!p.match(SqlTokenType.PARTITION)) {
            return;
        }
        p.expect(SqlTokenType.LPAREN);
        do {
            table.partitions().add(p.parseName());
        } while (p.match(SqlTokenType.COMMA));
        p.expect(SqlTokenType.RPAREN);
    }

    private void parseTableSample(SqlTable table) {
        // PG: TABLESAMPLE SYSTEM|BERNOULLI (p) [REPEATABLE (seed)]
        if (p.isIdent("TABLESAMPLE")) {
            StringBuilder sb = new StringBuilder();
            sb.append(p.token.text());
            p.next();
            if (p.identLike()) {
                sb.append(' ').append(p.token.text());
                p.next();
            }
            if (p.match(SqlTokenType.LPAREN)) {
                sb.append('(');
                sb.append(p.consumeRawUntilType(SqlTokenType.RPAREN));
                p.expect(SqlTokenType.RPAREN);
                sb.append(')');
            }
            if (p.isIdent("REPEATABLE")) {
                sb.append(' ').append(p.token.text());
                p.next();
                if (p.match(SqlTokenType.LPAREN)) {
                    sb.append('(');
                    sb.append(p.consumeRawUntilType(SqlTokenType.RPAREN));
                    p.expect(SqlTokenType.RPAREN);
                    sb.append(')');
                }
            }
            table.setSampleClause(sb.toString());
            return;
        }
        // Oracle: SAMPLE [BLOCK] (percent)
        if (p.isIdent("SAMPLE")) {
            StringBuilder sb = new StringBuilder();
            sb.append(p.token.text());
            p.next();
            if (p.isIdent("BLOCK")) {
                sb.append(' ').append(p.token.text());
                p.next();
            }
            if (p.match(SqlTokenType.LPAREN)) {
                sb.append('(');
                sb.append(p.consumeRawUntilType(SqlTokenType.RPAREN));
                p.expect(SqlTokenType.RPAREN);
                sb.append(')');
            }
            table.setSampleClause(sb.toString());
        }
    }

    SqlTableSource parseTableSource() {
        boolean lateral = p.match(SqlTokenType.LATERAL);
        // Oracle / SQL 标准：TABLE(fn(...))
        if (p.is(SqlTokenType.TABLE) && p.lexer.peek().type() == SqlTokenType.LPAREN) {
            p.next();
            p.expect(SqlTokenType.LPAREN);
            SqlFunctionTable ft = new SqlFunctionTable();
            ft.setTableKeyword(true);
            ft.setLateral(lateral);
            ft.setFunction(p.exprParser.parseExpr());
            p.expect(SqlTokenType.RPAREN);
            parseTableAlias(ft);
            parseFunctionTableWith(ft);
            return ft;
        }
        if (p.match(SqlTokenType.LPAREN)) {
            SqlTableSource source;
            if (p.is(SqlTokenType.VALUES)) {
                source = parseValuesTable();
            } else if (p.isQueryStart() || p.is(SqlTokenType.WITH)) {
                SqlSubqueryTable sub = new SqlSubqueryTable();
                sub.setQuery(p.parseStatement());
                sub.setLateral(lateral);
                source = sub;
            } else {
                if (lateral) {
                    throw p.error("LATERAL requires a subquery or table function");
                }
                source = parseJoinedTable();
            }
            p.expect(SqlTokenType.RPAREN);
            parseTableAlias(source);
            return source;
        }
        if (p.identLike() || (p.token.type() != null && p.token.type().keyword()
                && !p.is(SqlTokenType.SELECT) && !p.is(SqlTokenType.WITH)
                && !p.is(SqlTokenType.VALUES))) {
            SqlIdentifier name = p.parseName();
            if (p.is(SqlTokenType.LPAREN)) {
                SqlFunctionTable ft = new SqlFunctionTable();
                ft.setLateral(lateral);
                ft.setFunction(p.exprParser.parseFunction(name));
                parseTableAlias(ft);
                parseFunctionTableWith(ft);
                return ft;
            }
            if (lateral) {
                throw p.error("LATERAL requires a subquery or table function");
            }
            SqlTable table = SqlTable.of(name);
            parseTablePartition(table);
            parseTableHints(table);
            parseTableAlias(table);
            parseTableSample(table);
            return table;
        }
        if (lateral) {
            throw p.error("LATERAL requires a subquery or table function");
        }
        throw p.error("expected table source");
    }

    private SqlSelect parseValuesSelect() {
        SqlSelect select = new SqlSelect();
        select.setValuesClause(true);
        SqlFunctionExpr values = new SqlFunctionExpr();
        values.setName(SqlIdentifier.of("VALUES"));
        p.expect(SqlTokenType.VALUES);
        do {
            values.addArgument(p.exprParser.parsePrimary());
        } while (p.match(SqlTokenType.COMMA));
        SqlSelectItem item = new SqlSelectItem();
        item.setExpr(values);
        select.addSelectItem(item);
        return select;
    }

    private SqlValuesTable parseValuesTable() {
        SqlValuesTable values = new SqlValuesTable();
        p.expect(SqlTokenType.VALUES);
        do {
            values.rows().add(p.exprParser.parsePrimary());
        } while (p.match(SqlTokenType.COMMA));
        return values;
    }
}
