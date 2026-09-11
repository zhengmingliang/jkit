package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlMatchRecognize;
import com.alianga.jkit.sql.ast.SqlModelClause;
import com.alianga.jkit.sql.ast.SqlModelRule;
import com.alianga.jkit.sql.ast.SqlNamedExpr;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlPivotTable;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlSubset;
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
        return parseJoinChain(parseTableSource());
    }

    /**
     * 从已解析的左表续接 PIVOT 链与 JOIN 链（MySQL 多表删除等场景的首表重入）。
     *
     * @param left 已解析的左表
     * @return 完整表源
     */
    SqlTableSource parseJoinChain(SqlTableSource left) {
        left = parsePivotUnpivotChain(left);
        while (true) {
            // Hive: t LATERAL VIEW explode(a) x AS c1, c2
            if (p.is(SqlTokenType.LATERAL) && p.lexer.peek().type() == SqlTokenType.VIEW) {
                left = parseLateralView(left);
                left = parsePivotUnpivotChain(left);
                continue;
            }
            SqlJoin.Type type = null;
            boolean natural = false;
            if (p.match(SqlTokenType.COMMA)) {
                // Informix：FROM a, OUTER b  → 逗号后 OUTER 表视为外连接
                if (p.is(SqlTokenType.OUTER) && p.lexer.peek() != null
                        && p.lexer.peek().type() != SqlTokenType.APPLY) {
                    p.next();
                    type = SqlJoin.Type.LEFT;
                } else {
                    type = SqlJoin.Type.COMMA;
                }
            } else if (p.isIdent("GLOBAL") && p.lexer.peek() != null
                    && (p.lexer.peek().type() == SqlTokenType.LEFT
                    || p.lexer.peek().type() == SqlTokenType.RIGHT
                    || p.lexer.peek().type() == SqlTokenType.FULL
                    || p.lexer.peek().type() == SqlTokenType.INNER
                    || p.lexer.peek().type() == SqlTokenType.JOIN)) {
                // MaxCompute：global left join
                p.next();
                continue; // 重新识别 JOIN 类型
            } else if (p.match(SqlTokenType.NATURAL)) {
                // NATURAL 与连接类型正交：NATURAL [LEFT|RIGHT|FULL|INNER] JOIN
                natural = true;
                if (p.match(SqlTokenType.LEFT)) {
                    type = SqlJoin.Type.LEFT;
                } else if (p.match(SqlTokenType.RIGHT)) {
                    type = SqlJoin.Type.RIGHT;
                } else if (p.match(SqlTokenType.FULL)) {
                    type = SqlJoin.Type.FULL;
                } else {
                    p.match(SqlTokenType.INNER);
                    type = SqlJoin.Type.INNER;
                }
                p.match(SqlTokenType.OUTER);
                p.expect(SqlTokenType.JOIN);
            } else if (p.token.type() == SqlTokenType.STRAIGHT_JOIN) {
                p.next();
                type = SqlJoin.Type.STRAIGHT;
            } else if (p.match(SqlTokenType.JOIN) || p.match(SqlTokenType.INNER)) {
                p.match(SqlTokenType.JOIN);
                type = SqlJoin.Type.INNER;
            } else if (p.match(SqlTokenType.LEFT)) {
                if (p.isIdent("ANTI")) {
                    p.next();
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.LEFT_ANTI;
                } else if (p.isIdent("SEMI")) {
                    p.next();
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.LEFT_SEMI;
                } else {
                    p.match(SqlTokenType.OUTER);
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.LEFT;
                }
            } else if (p.match(SqlTokenType.RIGHT)) {
                if (p.isIdent("ANTI")) {
                    p.next();
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.RIGHT_ANTI;
                } else if (p.isIdent("SEMI")) {
                    p.next();
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.RIGHT_SEMI;
                } else {
                    p.match(SqlTokenType.OUTER);
                    p.expect(SqlTokenType.JOIN);
                    type = SqlJoin.Type.RIGHT;
                }
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
            } else {
                break;
            }
            SqlJoin join = new SqlJoin();
            join.setJoinType(type);
            join.setNatural(natural);
            join.setLeft(left);
            join.setRight(parseTableSource());
            if (p.match(SqlTokenType.ON)) {
                join.setCondition(p.exprParser.parseExpr());
                // Trino/Presto 风格：JOIN ON 后的 /*+joinMethod=…*/ hint 挂到右表
                while (p.is(SqlTokenType.HINT)) {
                    String h = p.token.text();
                    p.next();
                    if (join.right() instanceof SqlTable) {
                        SqlTable t = (SqlTable) join.right();
                        t.setOptimizerHint(t.optimizerHint() == null ? h : t.optimizerHint() + " " + h);
                    }
                }
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
            left = parsePivotUnpivotChain(left);
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
            // ClickHouse：LIMIT n BY col[, col2]
            if (p.is(SqlTokenType.BY) || p.isIdent("BY")) {
                StringBuilder sb = new StringBuilder("BY");
                p.next();
                do {
                    sb.append(' ');
                    int start = p.token.start();
                    p.exprParser.parseExpr();
                    sb.append(p.lexer.rawSlice(start, p.token.start()).trim());
                } while (p.match(SqlTokenType.COMMA));
                String prev = select.queryOption();
                select.setQueryOption(prev == null ? sb.toString() : prev + " " + sb);
            }
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
            // OFFSET n LIMIT m（OFFSET 在前）
            if (limit.rowCount() == null && p.is(SqlTokenType.LIMIT)) {
                p.next();
                limit.setRowCount(p.exprParser.parseExpr());
            }
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
        if (p.is(SqlTokenType.WITH)) {
            com.alianga.jkit.sql.ast.SqlStatement w = p.parseStatement();
            if (!(w instanceof SqlSelect)) {
                throw p.error("expected SELECT after WITH");
            }
            return (SqlSelect) w;
        }
        if (p.is(SqlTokenType.LPAREN)) {
            p.next();
            SqlSelect inner;
            if (p.is(SqlTokenType.WITH)) {
                // (WITH cte AS (…) SELECT …)
                com.alianga.jkit.sql.ast.SqlStatement w = p.parseStatement();
                if (!(w instanceof SqlSelect)) {
                    throw p.error("expected SELECT after WITH");
                }
                inner = (SqlSelect) w;
            } else {
                inner = parseSelect();
            }
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
        if (p.match(SqlTokenType.DISTINCTROW)) {
            select.setDistinct(true);
            select.setDistinctRow(true);
        } else if (p.match(SqlTokenType.DISTINCT)) {
            select.setDistinct(true);
        }
        if (select.distinct()) {
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
        select.setHighPriority(p.match(SqlTokenType.HIGH_PRIORITY));
        // MySQL SELECT 修饰符链：STRAIGHT_JOIN / SQL_SMALL_RESULT / SQL_BIG_RESULT /
        // SQL_BUFFER_RESULT / SQL_CACHE / SQL_NO_CACHE（非关键字，按 ident 识别）
        if (p.match(SqlTokenType.STRAIGHT_JOIN)) {
            select.setStraightJoin(true);
        }
        while (true) {
            if (p.isIdent("SQL_SMALL_RESULT")) {
                p.next();
                select.setSmallResult(true);
            } else if (p.isIdent("SQL_BIG_RESULT")) {
                p.next();
                select.setBigResult(true);
            } else if (p.isIdent("SQL_BUFFER_RESULT")) {
                p.next();
                select.setBufferResult(true);
            } else if (p.isIdent("SQL_CACHE")) {
                p.next();
                select.setCache(true);
            } else if (p.isIdent("SQL_NO_CACHE")) {
                p.next();
                select.setNoCache(true);
            } else {
                break;
            }
        }
        select.setCalcFoundRows(p.match(SqlTokenType.SQL_CALC_FOUND_ROWS));
        if (p.match(SqlTokenType.TOP)) {
            select.setTop(p.exprParser.parsePrimary());
            p.match(SqlTokenType.PERCENT);
            if (p.match(SqlTokenType.WITH)) {
                p.expect(SqlTokenType.TIES);
                select.setTopWithTies(true);
            }
        }
        // Informix：SELECT SKIP n FIRST m …（n 可为 ? / ?1）
        if (p.isIdent("SKIP")) {
            p.next();
            SqlLimit lim = select.limit();
            if (lim == null) {
                lim = new SqlLimit();
                select.setLimit(lim);
            }
            lim.setOffset(parseSkipFirstArg());
        }
        if (p.isIdent("FIRST") || p.is(SqlTokenType.FIRST)) {
            p.next();
            select.setTop(parseSkipFirstArg());
        }
        consumeSelectHints(select);
        do {
            select.addSelectItem(parseSelectItem());
        } while (p.match(SqlTokenType.COMMA));
        // ODPS/MaxCompute：SELECT … FORCE PARTITION 'pt' FROM …
        parseForcePartition(select);
        parseSelectInto(select);
        if (p.match(SqlTokenType.FROM)) {
            select.setFrom(parseJoinedTable());
        }
        // Oracle MODEL 子句（FROM 之后、WHERE 之前）
        if (p.isIdent("MODEL")) {
            select.setModelClause(parseModelClause());
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
            if (p.isIdent("NOCYCLE")) {
                p.next();
                select.setConnectByNocycle(true);
            }
            select.setConnectBy(p.exprParser.parseExpr());
        }
        // MySQL 偶见 HAVING 写在 GROUP BY 之前
        if (p.match(SqlTokenType.HAVING)) {
            select.setHaving(p.exprParser.parseExpr());
        }
        if (p.match(SqlTokenType.GROUP)) {
            p.expect(SqlTokenType.BY);
            if (p.match(SqlTokenType.DISTINCT)) {
                select.setGroupByDistinct(true);
            }
            if (p.is(SqlTokenType.GROUPING) || p.is(SqlTokenType.CUBE) || p.is(SqlTokenType.ROLLUP)) {
                StringBuilder sb = new StringBuilder();
                sb.append(p.token.text().toUpperCase());
                p.next();
                if (p.is(SqlTokenType.SETS)) {
                    sb.append(' ').append(p.token.text().toUpperCase());
                    p.next();
                }
                p.expect(SqlTokenType.LPAREN);
                sb.append('(').append(p.skipBalancedParensContent()).append(')');
                select.setGroupByExtension(sb.toString());
            } else {
                do {
                    select.groupBy().add(p.exprParser.parseExpr());
                } while (p.match(SqlTokenType.COMMA));
                // GROUP BY a GROUPING SETS (…)：列表后再跟集合运算（PG / 标准）
                if (p.is(SqlTokenType.GROUPING) || p.is(SqlTokenType.CUBE) || p.is(SqlTokenType.ROLLUP)) {
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append(p.token.text().toUpperCase());
                    p.next();
                    if (p.is(SqlTokenType.SETS)) {
                        sb2.append(' ').append(p.token.text().toUpperCase());
                        p.next();
                    }
                    if (p.match(SqlTokenType.LPAREN)) {
                        sb2.append('(').append(p.skipBalancedParensContent()).append(')');
                        select.setGroupByExtension(sb2.toString());
                    }
                } else if (p.match(SqlTokenType.WITH)) {
                    if (p.match(SqlTokenType.ROLLUP)) {
                        select.setGroupByRollup(true);
                    } else if (p.match(SqlTokenType.CUBE)) {
                        select.setGroupByCube(true);
                    }
                }
            }
        }
        if (select.having() == null && p.match(SqlTokenType.HAVING)) {
            select.setHaving(p.exprParser.parseExpr());
        }
        // Teradata / Snowflake / ClickHouse：QUALIFY 窗口过滤（HAVING 之后、ORDER BY 之前）
        if (p.isIdent("QUALIFY")) {
            p.next();
            select.setQualify(p.exprParser.parseExpr());
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
            if (p.match(SqlTokenType.SIBLINGS)) {
                select.setOrderSiblings(true);
            }
            p.expect(SqlTokenType.BY);
            parseOrderBy(select.orderBy());
        }
        parseHiveDistributeSort(select);
        parseLimitFetch(select);
        // MySQL：INTO 也可出现在 FROM/WHERE/ORDER/LIMIT 之后（与 SELECT 列表后 INTO 二选一）
        parseSelectInto(select);
        // DB2：语句级 WITH UR|CS|RS|RR
        if (p.is(SqlTokenType.WITH) && p.lexer.peek() != null
                && (p.lexer.peek().textEqualsIgnoreCase("UR")
                || p.lexer.peek().textEqualsIgnoreCase("CS")
                || p.lexer.peek().textEqualsIgnoreCase("RS")
                || p.lexer.peek().textEqualsIgnoreCase("RR"))) {
            p.next();
            select.setQueryOption("WITH " + p.token.text().toUpperCase());
            p.next();
        }
        if (p.match(SqlTokenType.FOR)) {
            if (p.isIdent("SYSTEM_TIME")) {
                // 时态表查询更常见于表级；此处兜底吞掉残留 FOR SYSTEM_TIME …
                select.setForUpdateTail("SYSTEM_TIME " + consumeSystemTimeBody());
            } else if (p.isIdent("XML")) {
                // SQL Server：FOR XML PATH('') [, TYPE] [, ROOT('x')] …
                p.next();
                select.setForUpdateTail("XML " + consumeForXmlTail());
            } else if (p.isIdent("BROWSE")) {
                // SQL Server：FOR BROWSE
                p.next();
                select.setForUpdateTail("BROWSE");
            } else if (p.isIdent("NO") && p.lexer.peek() != null
                    && (p.lexer.peek().type() == SqlTokenType.KEY
                    || p.lexer.peek().textEqualsIgnoreCase("KEY"))) {
                // PG：FOR NO KEY UPDATE
                select.setForUpdateTail(p.consumeRawUntilClause());
            } else if ((p.is(SqlTokenType.KEY) || p.isIdent("KEY")) && p.lexer.peek() != null
                    && p.lexer.peek().textEqualsIgnoreCase("SHARE")) {
                // PG：FOR KEY SHARE（KEY 为关键字）
                select.setForUpdateTail(p.consumeRawUntilClause());
                select.setLockInShare(true);
            } else if (p.match(SqlTokenType.SHARE) || p.isIdent("SHARE")) {
                if (p.isIdent("SHARE")) {
                    p.next();
                }
                select.setLockInShare(true);
                if (p.match(SqlTokenType.OF)) {
                    do {
                        select.forUpdateOf().add(p.parseName());
                    } while (p.match(SqlTokenType.COMMA));
                }
            } else {
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
        }
        if (p.is(SqlTokenType.LOCK)) {
            p.next();
            p.expect(SqlTokenType.IN);
            p.expect(SqlTokenType.SHARE);
            p.expect(SqlTokenType.MODE);
            select.setLockInShare(true);
        }
        // SQL Server：OPTION (MAXRECURSION 100) 等查询提示
        if (p.token.textEqualsIgnoreCase("OPTION")
                && (p.is(SqlTokenType.IDENT) || (p.token.type() != null && p.token.type().keyword()))) {
            p.next();
            p.expect(SqlTokenType.LPAREN);
            String opt = p.skipBalancedParensContent();
            select.setQueryOption("(" + opt + ")");
        }
        parseSelectTail(select);
        // 表达式层收集的游离 hint（如 WHERE 中的 TDDL hint）统一挂到 SELECT
        for (String h : p.exprParser.drainFloatingHints()) {
            select.addHint(h);
        }
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
        // SELECT a,b INTO (c,d) FROM t  /  SELECT a,b INTO c,d FROM t
        if (p.match(SqlTokenType.LPAREN)) {
            do {
                select.intoVariables().add(p.parseName());
            } while (p.match(SqlTokenType.COMMA));
            p.expect(SqlTokenType.RPAREN);
            return;
        }
        if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
            // 多变量 INTO c,d 与单表 INTO t 歧义：若下一记号为逗号则按变量列表
            SqlIdentifier first = p.parseName();
            if (p.is(SqlTokenType.COMMA)) {
                select.intoVariables().add(first);
                while (p.match(SqlTokenType.COMMA)) {
                    select.intoVariables().add(p.parseName());
                }
                return;
            }
            p.match(SqlTokenType.TABLE);
            select.setIntoTable(SqlTable.of(first));
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
            // Hive UDTF：fn(...) AS (c0, c1, c2)
            if (p.match(SqlTokenType.AS) && p.is(SqlTokenType.LPAREN)) {
                p.next();
                do {
                    item.columnAliases().add(p.parseName());
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
            } else {
                item.setAlias(p.parseAlias());
            }
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
     * SQL Server 表提示 {@code WITH (NOLOCK)} / {@code WITH (INDEX(ix))}，
     * 可出现在表名之后或别名之后。
     */
    private void parseWithTableHint(SqlTable table) {
        // DB2：WITH UR|CS|RS|RR 隔离级别
        if (p.is(SqlTokenType.WITH) && p.lexer.peek() != null
                && (p.lexer.peek().textEqualsIgnoreCase("UR")
                || p.lexer.peek().textEqualsIgnoreCase("CS")
                || p.lexer.peek().textEqualsIgnoreCase("RS")
                || p.lexer.peek().textEqualsIgnoreCase("RR"))) {
            p.next();
            String iso = p.token.text();
            p.next();
            String prev = table.withHint();
            String clause = "WITH " + iso;
            table.setWithHint(prev == null || prev.isEmpty() ? clause : prev + " " + clause);
            return;
        }
        if (!p.is(SqlTokenType.WITH) || p.lexer.peek().type() != SqlTokenType.LPAREN) {
            return;
        }
        p.next();
        p.expect(SqlTokenType.LPAREN);
        String hint = "WITH (" + p.skipBalancedParensContent() + ")";
        String prev = table.withHint();
        table.setWithHint(prev == null || prev.isEmpty() ? hint : prev + " " + hint);
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

    /** Informix SKIP/FIRST 参数：字面量 / 绑定。 */
    private SqlExpr parseSkipFirstArg() {
        return p.exprParser.parsePrimary();
    }

    /**
     * ODPS/MaxCompute：{@code FORCE PARTITION 'pt'} / {@code FORCE ALL PARTITIONS}（SELECT 列表后）。
     */
    /** FOR XML 尾部：允许逗号与括号（PATH('') / ROOT('x')）。 */
    private String consumeForXmlTail() {
        StringBuilder sb = new StringBuilder();
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                && !p.is(SqlTokenType.UNION) && !p.is(SqlTokenType.INTERSECT)
                && !p.is(SqlTokenType.EXCEPT) && !p.is(SqlTokenType.MINUS)
                && !p.is(SqlTokenType.GO)) {
            if (p.is(SqlTokenType.RPAREN) && sb.length() == 0) {
                break;
            }
            // 语句级子句停止（非 XML 选项）
            if (sb.length() > 0 && (p.is(SqlTokenType.ORDER) || p.is(SqlTokenType.LIMIT)
                    || p.is(SqlTokenType.OFFSET) || p.is(SqlTokenType.FETCH)
                    || p.is(SqlTokenType.FOR) || p.token.textEqualsIgnoreCase("OPTION"))) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (p.is(SqlTokenType.LPAREN)) {
                sb.append('(').append(p.skipBalancedParensContent()).append(')');
                continue;
            }
            sb.append(p.token.text());
            p.next();
        }
        return sb.toString();
    }

    private void parseForcePartition(SqlSelect select) {
        if (!p.is(SqlTokenType.FORCE)) {
            return;
        }
        SqlToken peeked = p.lexer.peek();
        if (peeked == null) {
            return;
        }
        boolean all = peeked.textEqualsIgnoreCase("ALL");
        boolean part = peeked.type() == SqlTokenType.PARTITION
                || peeked.textEqualsIgnoreCase("PARTITION")
                || peeked.textEqualsIgnoreCase("PARTITIONS");
        if (!all && !part) {
            return;
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
        select.setForcePartition(sb.toString());
    }

    /**
     * Oracle 闪回：{@code VERSIONS BETWEEN TIMESTAMP|SCN expr AND expr}。
     */
    private void parseVersionsBetween(SqlTable table) {
        if (!(p.identLike() || (p.token.type() != null && p.token.type().keyword()))
                || !p.token.textEqualsIgnoreCase("VERSIONS")) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.token.text());
        p.next();
        if (p.match(SqlTokenType.BETWEEN)) {
            sb.append(" BETWEEN");
        }
        // TIMESTAMP | SCN
        if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
            sb.append(' ').append(p.token.text());
            p.next();
        }
        int start = p.token.start();
        p.exprParser.parseExpr();
        if (p.match(SqlTokenType.AND)) {
            p.exprParser.parseExpr();
        }
        sb.append(' ').append(p.lexer.rawSlice(start, p.token.start()).trim());
        String prev = table.temporalClause();
        table.setTemporalClause(prev == null || prev.isEmpty() ? sb.toString() : prev + " " + sb.toString());
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

    /**
     * 当前在 {@code (} 上时，窥视嵌套括号内首个非括号记号是否为查询起头。
     */
    private boolean lookingAtNestedParenQuery() {
        if (!p.is(SqlTokenType.LPAREN)) {
            return false;
        }
        // p.token 已是 '('；lexer.lookahead(i) 相对当前记号之后
        int depth = 1;
        for (int i = 0; i < 64; i++) {
            SqlToken tok = p.lexer.lookahead(i);
            if (tok == null || tok.type() == SqlTokenType.EOF) {
                return false;
            }
            if (tok.type() == SqlTokenType.LPAREN) {
                depth++;
                continue;
            }
            if (tok.type() == SqlTokenType.RPAREN) {
                depth--;
                if (depth <= 0) {
                    return false;
                }
                continue;
            }
            return tok.type() == SqlTokenType.SELECT || tok.type() == SqlTokenType.WITH
                    || tok.type() == SqlTokenType.VALUES;
        }
        return false;
    }

    SqlTableSource parseTableSource() {
        // JDBC/ODBC 转义：{oj <join>} 解包为普通 JOIN 表源
        if (p.is(SqlTokenType.LBRACE) && p.lexer.peek() != null
                && p.lexer.peek().type() == SqlTokenType.IDENT
                && p.lexer.peek().textEqualsIgnoreCase("oj")) {
            p.next();
            p.next();
            SqlTableSource inner = parseJoinedTable();
            p.expect(SqlTokenType.RBRACE);
            return inner;
        }
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
            } else if (p.isQueryStart() || p.is(SqlTokenType.WITH)
                    || (p.is(SqlTokenType.LPAREN) && lookingAtNestedParenQuery())) {
                // (SELECT…) / ((SELECT…) UNION …) / (WITH … SELECT…)
                SqlSubqueryTable sub = new SqlSubqueryTable();
                sub.setQuery(parseSelect());
                sub.setLateral(lateral);
                source = sub;
            } else {
                // ((((t)))) 多层括号表：先剥掉纯括号层再解析表/JOIN
                if (lateral && !p.is(SqlTokenType.LPAREN) && !p.isQueryStart()) {
                    throw p.error("LATERAL requires a subquery or table function");
                }
                int wraps = 0;
                while (p.is(SqlTokenType.LPAREN) && !lookingAtNestedParenQuery()
                        && !p.isQueryStart() && !p.is(SqlTokenType.WITH) && !p.is(SqlTokenType.VALUES)) {
                    p.next();
                    wraps++;
                }
                if (p.isQueryStart() || p.is(SqlTokenType.WITH)
                        || (p.is(SqlTokenType.LPAREN) && lookingAtNestedParenQuery())) {
                    SqlSubqueryTable sub = new SqlSubqueryTable();
                    sub.setQuery(parseSelect());
                    sub.setLateral(lateral);
                    source = sub;
                } else {
                    source = parseJoinedTable();
                }
                for (int i = 0; i < wraps; i++) {
                    p.expect(SqlTokenType.RPAREN);
                }
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
                if (p.is(SqlTokenType.WITH) && p.lexer.peek() != null && p.lexer.peek().text() != null
                        && SqlParser.equalsIgnoreCase(p.lexer.peek().text(), "ORDINALITY")) {
                    p.next();
                    p.next();
                    ft.setWithOrdinality(true);
                }
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
            if (p.is(SqlTokenType.FOR) && p.lexer.peek().textEqualsIgnoreCase("SYSTEM_TIME")) {
                table.setTemporalClause(consumeForSystemTimeClause());
                parseTableAlias(table);
            } else if (p.is(SqlTokenType.AS) && p.lexer.peek().textEqualsIgnoreCase("OF")) {
                table.setTemporalClause(consumeAsOfClause());
                // AS OF 不是别名；其后仍可能有别名
                parseTableAlias(table);
            } else {
                parseTableAlias(table);
            }
            // 别名之后仍可出现 USE/FORCE/IGNORE INDEX（MySQL）
            parseTableHints(table);
            // Oracle 闪回版本：VERSIONS BETWEEN TIMESTAMP … AND …
            parseVersionsBetween(table);
            parseWithTableHint(table);
            parseTableSample(table);
            parseMatchRecognize(table);
            return table;
        }
        if (lateral) {
            throw p.error("LATERAL requires a subquery or table function");
        }
        throw p.error("expected table source");
    }

    private SqlTableSource parsePivotUnpivotChain(SqlTableSource source) {
        while (p.is(SqlTokenType.PIVOT) || p.is(SqlTokenType.UNPIVOT)) {
            boolean unpivot = p.is(SqlTokenType.UNPIVOT);
            p.next();
            String nulls = null;
            boolean xml = false;
            if (p.isIdent("XML")) {
                p.next();
                xml = true;
            }
            if ((p.identLike() || (p.token.type() != null && p.token.type().keyword()))
                    && (p.token.textEqualsIgnoreCase("INCLUDE") || p.token.textEqualsIgnoreCase("EXCLUDE"))) {
                String mode = p.token.text().toUpperCase();
                p.next();
                if (p.is(SqlTokenType.NULL) || p.isIdent("NULLS")) {
                    p.next();
                    nulls = mode + " NULLS";
                }
            }
            p.expect(SqlTokenType.LPAREN);
            String body = p.skipBalancedParensContent();
            SqlPivotTable pivot = new SqlPivotTable();
            pivot.setInput(source);
            pivot.setUnpivot(unpivot);
            pivot.setNullsClause(nulls);
            pivot.setDefinition(xml ? "XML (" + body + ")" : body);
            parseTableAlias(pivot);
            source = pivot;
        }
        return source;
    }

    private SqlTableSource parseLateralView(SqlTableSource left) {
        p.expect(SqlTokenType.LATERAL);
        p.expect(SqlTokenType.VIEW);
        boolean outer = false;
        if (p.isIdent("OUTER")) {
            outer = true;
            p.next();
        }
        SqlIdentifier fnName = p.parseName();
        SqlFunctionTable ft = new SqlFunctionTable();
        ft.setLateral(true);
        ft.setFunction(p.exprParser.parseFunction(fnName));
        if (outer) {
            ft.setWithDefinition("OUTER");
        }
        if (p.identLike() && !p.is(SqlTokenType.AS) && !SqlParser.isAliasStop(p.token.type())) {
            ft.setAlias(SqlParser.unquote(p.consumeIdentRaw()));
        }
        if (p.match(SqlTokenType.AS)) {
            do {
                ft.columnAliases().add(p.parseName());
            } while (p.match(SqlTokenType.COMMA));
        }
        SqlJoin join = new SqlJoin();
        join.setJoinType(SqlJoin.Type.LATERAL_VIEW);
        join.setLeft(left);
        join.setRight(ft);
        return join;
    }

    private void parseMatchRecognize(SqlTable table) {
        if (!p.isIdent("MATCH_RECOGNIZE")) {
            return;
        }
        p.next();
        p.expect(SqlTokenType.LPAREN);
        table.setMatchRecognize(parseMatchRecognizeBody());
        parseTableAlias(table);
    }

    private SqlMatchRecognize parseMatchRecognizeBody() {
        SqlMatchRecognize mr = new SqlMatchRecognize();
        int start = p.token.start();
        StringBuilder leftover = new StringBuilder();
        while (!p.is(SqlTokenType.RPAREN) && !p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
            if (p.match(SqlTokenType.PARTITION)) {
                p.expect(SqlTokenType.BY);
                parseExprListInto(mr.partitionBy());
            } else if (p.match(SqlTokenType.ORDER)) {
                p.expect(SqlTokenType.BY);
                parseOrderBy(mr.orderBy());
            } else if (p.isIdent("MEASURES")) {
                p.next();
                parseMatchMeasures(mr.measures());
            } else if (p.isIdent("ONE") || p.is(SqlTokenType.ALL) || p.isIdent("ALL")) {
                mr.setRowsPerMatch(consumeRowsPerMatch());
            } else if (p.isIdent("AFTER")) {
                mr.setAfterMatch(consumeAfterMatch());
            } else if (p.isIdent("PATTERN")) {
                p.next();
                p.expect(SqlTokenType.LPAREN);
                mr.setPattern(p.skipBalancedParensContent());
            } else if (p.isIdent("WITHIN")) {
                mr.setWithin(consumeWithinClause());
            } else if (p.isIdent("DEFINE")) {
                p.next();
                parseMatchDefine(mr.define());
            } else if (p.isIdent("SUBSET")) {
                p.next();
                parseMatchSubsets(mr.subsets());
            } else if (p.isIdent("MATCH_NUMBER")
                    || p.isIdent("CLASSIFIER") || p.isIdent("PERMUTE")) {
                appendLeftoverClause(leftover);
            } else {
                appendLeftoverToken(leftover);
            }
        }
        mr.setRaw(p.lexer.rawSlice(start, p.token.start()).trim());
        if (leftover.length() > 0) {
            mr.setOptionsRaw(leftover.toString().trim());
        }
        p.expect(SqlTokenType.RPAREN);
        return mr;
    }

    private void parseExprListInto(List<SqlExpr> target) {
        do {
            target.add(p.exprParser.parseExpr());
        } while (p.match(SqlTokenType.COMMA));
    }

    private void parseMatchMeasures(List<SqlNamedExpr> measures) {
        do {
            SqlNamedExpr item = new SqlNamedExpr();
            item.setNameFirst(false);
            int start = p.token.start();
            item.setExpr(p.exprParser.parseExpr());
            if (p.match(SqlTokenType.AS)) {
                item.setName(SqlParser.unquote(p.consumeIdentRaw()));
            } else if (p.identLike() && !isMatchRecognizeClauseStart()) {
                item.setName(SqlParser.unquote(p.consumeIdentRaw()));
            }
            item.setRaw(p.lexer.rawSlice(start, p.token.start()).trim());
            measures.add(item);
        } while (p.match(SqlTokenType.COMMA));
    }

    private void parseMatchDefine(List<SqlNamedExpr> define) {
        do {
            SqlNamedExpr item = new SqlNamedExpr();
            item.setNameFirst(true);
            int start = p.token.start();
            item.setName(SqlParser.unquote(p.consumeIdentRaw()));
            p.expect(SqlTokenType.AS);
            item.setExpr(p.exprParser.parseExpr());
            item.setRaw(p.lexer.rawSlice(start, p.token.start()).trim());
            define.add(item);
        } while (p.match(SqlTokenType.COMMA));
    }

    private String consumeRowsPerMatch() {
        int start = p.token.start();
        p.next(); // ONE | ALL
        if (p.is(SqlTokenType.ROW) || p.is(SqlTokenType.ROWS) || p.isIdent("ROW")) {
            p.next();
        }
        if (p.isIdent("PER") || (p.identLike() && p.token.textEqualsIgnoreCase("PER"))) {
            p.next();
        }
        if (p.isIdent("MATCH") || (p.identLike() && p.token.textEqualsIgnoreCase("MATCH"))) {
            p.next();
        }
        // ALL ROWS PER MATCH SHOW EMPTY MATCHES / WITH UNMATCHED ROWS 等后缀
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                && !p.is(SqlTokenType.RPAREN) && !isMatchRecognizeClauseStart()) {
            String t = p.token.text().toUpperCase();
            if ("SHOW".equals(t) || "EMPTY".equals(t) || "MATCHES".equals(t)
                    || "WITH".equals(t) || "UNMATCHED".equals(t) || "OMIT".equals(t)
                    || "ABSENT".equals(t) || "ROWS".equals(t)) {
                p.next();
            } else {
                break;
            }
        }
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    private String consumeAfterMatch() {
        int start = p.token.start();
        p.next(); // AFTER
        if (p.isIdent("MATCH")) {
            p.next();
        }
        // SKIP TO NEXT ROW / SKIP PAST LAST ROW / SKIP TO FIRST|LAST pattern_var
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                && !p.is(SqlTokenType.RPAREN) && !isMatchRecognizeClauseStart()) {
            p.next();
        }
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    private boolean isMatchRecognizeClauseStart() {
        return p.is(SqlTokenType.PARTITION) || p.is(SqlTokenType.ORDER)
                || p.isIdent("MEASURES") || p.isIdent("ONE") || p.is(SqlTokenType.ALL) || p.isIdent("ALL")
                || p.isIdent("AFTER") || p.isIdent("PATTERN") || p.isIdent("DEFINE")
                || p.isIdent("SUBSET") || p.isIdent("WITHIN");
    }

    private String consumeWithinClause() {
        int start = p.token.start();
        p.next(); // WITHIN
        if (p.match(SqlTokenType.LPAREN)) {
            p.skipBalancedParensContent();
        } else {
            while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                    && !p.is(SqlTokenType.RPAREN) && !isMatchRecognizeClauseStart()) {
                if (p.is(SqlTokenType.LPAREN)) {
                    p.next();
                    p.skipBalancedParensContent();
                } else {
                    p.next();
                }
            }
        }
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    private void appendLeftoverClause(StringBuilder leftover) {
        if (leftover.length() > 0) {
            leftover.append(' ');
        }
        int start = p.token.start();
        p.next();
        if (p.match(SqlTokenType.LPAREN)) {
            p.skipBalancedParensContent();
        } else {
            while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                    && !p.is(SqlTokenType.RPAREN) && !isMatchRecognizeClauseStart()) {
                if (p.is(SqlTokenType.LPAREN)) {
                    p.next();
                    p.skipBalancedParensContent();
                } else {
                    p.next();
                }
            }
        }
        leftover.append(p.lexer.rawSlice(start, p.token.start()).trim());
    }

    private void appendLeftoverToken(StringBuilder leftover) {
        if (leftover.length() > 0) {
            leftover.append(' ');
        }
        leftover.append(p.token.text());
        if (p.is(SqlTokenType.LPAREN)) {
            p.next();
            leftover.append('(').append(p.skipBalancedParensContent()).append(')');
        } else {
            p.next();
        }
    }

    private String consumeAsOfClause() {
        int start = p.token.start();
        p.next(); // AS
        // OF
        p.next();
        // TIMESTAMP | SCN | 其他
        if (p.is(SqlTokenType.TIMESTAMP) || p.isIdent("SCN") || p.identLike()) {
            p.next();
        }
        if (p.match(SqlTokenType.LPAREN)) {
            p.skipBalancedParensContent();
        } else if (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                && !SqlParser.isAliasStop(p.token.type())) {
            p.exprParser.parseExpr();
        }
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    private String consumeForSystemTimeClause() {
        int start = p.token.start();
        p.next(); // FOR
        p.next(); // SYSTEM_TIME
        consumeSystemTimeBody();
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    /** SYSTEM_TIME 之后：AS OF expr / BETWEEN … / FROM … TO … / ALL */
    private String consumeSystemTimeBody() {
        int start = p.token.start();
        if (p.isIdent("ALL")) {
            p.next();
        } else if (p.match(SqlTokenType.AS)) {
            // OF 是关键字 SqlTokenType.OF
            boolean of = p.match(SqlTokenType.OF);
            if (!of && p.isIdent("OF")) {
                p.next();
                of = true;
            }
            if (of) {
                if (p.match(SqlTokenType.LPAREN)) {
                    p.skipBalancedParensContent();
                } else {
                    p.exprParser.parseExpr();
                }
            }
        } else if (p.isIdent("BETWEEN") || p.is(SqlTokenType.FROM)) {
            int depth = 0;
            while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
                if (depth == 0 && isTemporalStop()) {
                    break;
                }
                if (p.is(SqlTokenType.LPAREN)) {
                    depth++;
                } else if (p.is(SqlTokenType.RPAREN)) {
                    depth--;
                }
                p.next();
            }
        }
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    private boolean isTemporalStop() {
        return p.is(SqlTokenType.WHERE) || p.is(SqlTokenType.GROUP) || p.is(SqlTokenType.HAVING)
                || p.is(SqlTokenType.ORDER) || p.is(SqlTokenType.LIMIT) || p.is(SqlTokenType.OFFSET)
                || p.is(SqlTokenType.FETCH) || p.is(SqlTokenType.UNION) || p.is(SqlTokenType.INTERSECT)
                || p.is(SqlTokenType.EXCEPT) || p.is(SqlTokenType.MINUS) || p.is(SqlTokenType.WINDOW)
                || p.is(SqlTokenType.FOR) || p.is(SqlTokenType.LOCK) || p.is(SqlTokenType.START)
                || p.is(SqlTokenType.CONNECT) || p.isIdent("MODEL") || p.isIdent("DISTRIBUTE")
                || p.isIdent("CLUSTER") || p.isIdent("SORT")
                || (p.is(SqlTokenType.IDENT) && p.token.textEqualsIgnoreCase("OPTION"));
    }

    private SqlModelClause parseModelClause() {
        SqlModelClause model = new SqlModelClause();
        int start = p.token.start();
        p.next(); // MODEL
        StringBuilder options = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON) && !isModelStop()) {
            if (p.match(SqlTokenType.PARTITION)) {
                p.expect(SqlTokenType.BY);
                parseModelExprList(model.partitionBy());
            } else if (p.isIdent("DIMENSION") && isByPeek()) {
                p.next();
                p.expect(SqlTokenType.BY);
                parseModelExprList(model.dimensionBy());
            } else if (p.isIdent("MEASURES")) {
                p.next();
                parseModelExprList(model.measures());
            } else if (p.isIdent("RULES")) {
                p.next();
                StringBuilder mods = new StringBuilder();
                while (p.identLike() && !isModelSectionStart() && !isModelStop()) {
                    String t = p.token.text().toUpperCase();
                    if ("UPSERT".equals(t) || "UPDATE".equals(t) || "AUTOMATIC".equals(t)
                            || "SEQUENTIAL".equals(t) || "ORDERED".equals(t) || "ITERATE".equals(t)
                            || "UNTIL".equals(t)) {
                        if (mods.length() > 0) {
                            mods.append(' ');
                        }
                        mods.append(t);
                        p.next();
                        if ("ITERATE".equals(t) && p.match(SqlTokenType.LPAREN)) {
                            mods.append('(').append(p.skipBalancedParensContent()).append(')');
                        } else if ("UNTIL".equals(t) && p.match(SqlTokenType.LPAREN)) {
                            mods.append('(').append(p.skipBalancedParensContent()).append(')');
                        }
                    } else {
                        break;
                    }
                }
                if (mods.length() > 0) {
                    model.setRulesModifiers(mods.toString());
                }
                if (p.match(SqlTokenType.LPAREN)) {
                    parseModelRulesBody(model);
                }
            } else if (isModelOptionToken()) {
                if (options.length() > 0) {
                    options.append(' ');
                }
                options.append(p.token.text().toUpperCase());
                p.next();
                if (p.match(SqlTokenType.LPAREN)) {
                    options.append('(').append(p.skipBalancedParensContent()).append(')');
                }
            } else {
                if (tail.length() > 0) {
                    tail.append(' ');
                }
                int tStart = p.token.start();
                if (p.is(SqlTokenType.LPAREN)) {
                    p.next();
                    p.skipBalancedParensContent();
                } else if (p.is(SqlTokenType.LBRACKET)) {
                    int depth = 0;
                    do {
                        if (p.is(SqlTokenType.LBRACKET)) {
                            depth++;
                        } else if (p.is(SqlTokenType.RBRACKET)) {
                            depth--;
                        }
                        p.next();
                    } while (depth > 0 && !p.is(SqlTokenType.EOF));
                } else {
                    p.next();
                }
                tail.append(p.lexer.rawSlice(tStart, p.token.start()).trim());
            }
        }
        if (options.length() > 0) {
            model.setOptions(options.toString());
        }
        if (tail.length() > 0) {
            model.setTail(tail.toString().trim());
        }
        model.setRaw(p.lexer.rawSlice(start, p.token.start()).trim());
        return model;
    }

    private void parseMatchSubsets(List<SqlSubset> subsets) {
        do {
            SqlSubset subset = new SqlSubset();
            int start = p.token.start();
            if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                subset.setName(SqlParser.unquote(p.consumeIdentRaw()));
            }
            if (p.match(SqlTokenType.EQ) || p.match(SqlTokenType.ASSIGN)) {
                if (p.match(SqlTokenType.LPAREN)) {
                    if (!p.is(SqlTokenType.RPAREN)) {
                        do {
                            if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                                subset.members().add(SqlParser.unquote(p.consumeIdentRaw()));
                            } else {
                                break;
                            }
                        } while (p.match(SqlTokenType.COMMA));
                    }
                    p.expect(SqlTokenType.RPAREN);
                }
            }
            subset.setRaw(p.lexer.rawSlice(start, p.token.start()).trim());
            subsets.add(subset);
        } while (p.match(SqlTokenType.COMMA)
                && (p.identLike() || (p.token.type() != null && p.token.type().keyword()))
                && !isMatchRecognizeClauseStart());
    }

    private void parseModelRulesBody(SqlModelClause model) {
        int start = p.token.start();
        if (!p.is(SqlTokenType.RPAREN) && !p.is(SqlTokenType.EOF)) {
            do {
                model.ruleEntries().add(parseModelRuleEntry());
            } while (p.match(SqlTokenType.COMMA) && !p.is(SqlTokenType.RPAREN) && !p.is(SqlTokenType.EOF));
        }
        while (!p.is(SqlTokenType.RPAREN) && !p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
            if (p.is(SqlTokenType.LPAREN)) {
                p.next();
                p.skipBalancedParensContent();
            } else if (p.is(SqlTokenType.LBRACKET)) {
                p.next();
                skipBalancedBracketsContent();
            } else {
                p.next();
            }
        }
        model.setRules(p.lexer.rawSlice(start, p.token.start()).trim());
        p.expect(SqlTokenType.RPAREN);
    }

    private SqlModelRule parseModelRuleEntry() {
        SqlModelRule rule = new SqlModelRule();
        int start = p.token.start();
        StringBuilder mods = new StringBuilder();
        while (p.identLike() || p.is(SqlTokenType.ALL) || p.isIdent("ALL")) {
            String t = p.token.text().toUpperCase();
            if ("UPSERT".equals(t) || "UPDATE".equals(t) || "ALL".equals(t)) {
                if (mods.length() > 0) {
                    mods.append(' ');
                }
                mods.append(t);
                p.next();
            } else {
                break;
            }
        }
        if (mods.length() > 0) {
            rule.setModifiers(mods.toString());
        }
        int cellStart = p.token.start();
        int depthParen = 0;
        int depthBracket = 0;
        boolean sawEq = false;
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
            if (depthParen == 0 && depthBracket == 0
                    && (p.is(SqlTokenType.EQ) || p.is(SqlTokenType.ASSIGN))) {
                sawEq = true;
                break;
            }
            if (depthParen == 0 && depthBracket == 0
                    && (p.is(SqlTokenType.COMMA) || p.is(SqlTokenType.RPAREN))) {
                break;
            }
            if (p.is(SqlTokenType.LPAREN)) {
                depthParen++;
                p.next();
            } else if (p.is(SqlTokenType.RPAREN)) {
                if (depthParen == 0) {
                    break;
                }
                depthParen--;
                p.next();
            } else if (p.is(SqlTokenType.LBRACKET)) {
                depthBracket++;
                p.next();
            } else if (p.is(SqlTokenType.RBRACKET)) {
                if (depthBracket > 0) {
                    depthBracket--;
                }
                p.next();
            } else {
                p.next();
            }
        }
        String cell = p.lexer.rawSlice(cellStart, p.token.start()).trim();
        if (!cell.isEmpty()) {
            rule.setCell(cell);
            fillModelCellDims(rule, cell);
        }
        if (sawEq && (p.match(SqlTokenType.EQ) || p.match(SqlTokenType.ASSIGN))) {
            try {
                rule.setValue(p.exprParser.parseExpr());
            } catch (SqlParseException ex) {
                while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)
                        && !p.is(SqlTokenType.COMMA) && !p.is(SqlTokenType.RPAREN)) {
                    if (p.is(SqlTokenType.LPAREN)) {
                        p.next();
                        p.skipBalancedParensContent();
                    } else if (p.is(SqlTokenType.LBRACKET)) {
                        p.next();
                        skipBalancedBracketsContent();
                    } else {
                        p.next();
                    }
                }
            }
        }
        rule.setRaw(p.lexer.rawSlice(start, p.token.start()).trim());
        return rule;
    }

    /** 从 {@code measure[d1, d2, …]} 原文拆出顶层维度片段。 */
    private void fillModelCellDims(SqlModelRule rule, String cell) {
        if (cell == null) {
            return;
        }
        int lb = cell.indexOf('[');
        int rb = cell.lastIndexOf(']');
        if (lb < 0 || rb <= lb) {
            return;
        }
        String inside = cell.substring(lb + 1, rb).trim();
        if (inside.isEmpty()) {
            return;
        }
        StringBuilder cur = new StringBuilder();
        int depthParen = 0;
        int depthBracket = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '(') {
                depthParen++;
                cur.append(c);
            } else if (c == ')') {
                if (depthParen > 0) {
                    depthParen--;
                }
                cur.append(c);
            } else if (c == '[') {
                depthBracket++;
                cur.append(c);
            } else if (c == ']') {
                if (depthBracket > 0) {
                    depthBracket--;
                }
                cur.append(c);
            } else if (c == ',' && depthParen == 0 && depthBracket == 0) {
                String part = cur.toString().trim();
                if (!part.isEmpty()) {
                    rule.cellDims().add(part);
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) {
            rule.cellDims().add(last);
        }
        fillSimpleCellDimExprs(rule);
    }

    /** 当各维均为简单标识符时填充 {@link SqlModelRule#cellDimExprs()}。 */
    private void fillSimpleCellDimExprs(SqlModelRule rule) {
        if (rule.cellDims().isEmpty()) {
            return;
        }
        List<SqlExpr> exprs = new ArrayList<SqlExpr>(rule.cellDims().size());
        for (int i = 0; i < rule.cellDims().size(); i++) {
            String part = rule.cellDims().get(i);
            if (!isSimpleIdentDim(part)) {
                return;
            }
            exprs.add(SqlIdentifier.of(part));
        }
        rule.cellDimExprs().addAll(exprs);
    }

    private static boolean isSimpleIdentDim(String part) {
        if (part == null || part.isEmpty()) {
            return false;
        }
        // 允许 a / a.b / "a" / `a`；拒绝含运算符/空白的维
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '=' || c == '(' || c == ')' || c == '[' || c == ']' || c == ','
                    || c == '+' || c == '-' || c == '*' || c == '/' || c == '<' || c == '>'
                    || Character.isWhitespace(c) || c == '\'') {
                return false;
            }
        }
        char first = part.charAt(0);
        return Character.isLetter(first) || first == '_' || first == '"' || first == '`' || first == '[';
    }

    private String skipBalancedBracketsContent() {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (!p.is(SqlTokenType.EOF) && depth > 0) {
            if (p.is(SqlTokenType.LBRACKET)) {
                depth++;
                sb.append(p.token.text());
                p.next();
            } else if (p.is(SqlTokenType.RBRACKET)) {
                depth--;
                if (depth == 0) {
                    p.next();
                    break;
                }
                sb.append(p.token.text());
                p.next();
            } else if (p.is(SqlTokenType.LPAREN)) {
                sb.append('(');
                p.next();
                sb.append(p.skipBalancedParensContent());
                sb.append(')');
            } else {
                sb.append(p.token.text());
                p.next();
            }
        }
        return sb.toString();
    }

    private void parseModelExprList(List<SqlExpr> target) {
        boolean paren = p.match(SqlTokenType.LPAREN);
        do {
            SqlExpr expr = p.exprParser.parseExpr();
            // MODEL MEASURES 允许裸别名（sale s）：仅当后续紧跟 COMMA/RPAREN 时才吸收，避免吃掉小节关键字
            if (p.identLike() && expr instanceof SqlIdentifier) {
                SqlToken peeked = p.lexer.peek();
                if (peeked != null && (peeked.type() == SqlTokenType.COMMA
                        || peeked.type() == SqlTokenType.RPAREN)) {
                    expr = SqlIdentifier.of(((SqlIdentifier) expr).qualifiedName()
                            + " " + p.consumeIdentRaw());
                }
            }
            target.add(expr);
        } while (p.match(SqlTokenType.COMMA));
        if (paren) {
            p.expect(SqlTokenType.RPAREN);
        }
    }

    private boolean isByPeek() {
        SqlToken peek = p.lexer.peek();
        return peek != null && (peek.type() == SqlTokenType.BY
                || (peek.type() == SqlTokenType.IDENT && peek.textEqualsIgnoreCase("BY")));
    }

    private boolean isModelOptionToken() {
        if (p.is(SqlTokenType.ROWS) || p.is(SqlTokenType.ALL) || p.is(SqlTokenType.UNIQUE)) {
            return true;
        }
        if (!p.identLike()) {
            return false;
        }
        String t = p.token.text().toUpperCase();
        return "IGNORE".equals(t) || "KEEP".equals(t) || "NAV".equals(t)
                || "DIMENSION".equals(t) || "SINGLE".equals(t) || "REFERENCE".equals(t)
                || "RETURN".equals(t) || "UPDATED".equals(t) || "MAIN".equals(t);
    }

    private boolean isModelSectionStart() {
        return p.is(SqlTokenType.PARTITION) || p.isIdent("DIMENSION")
                || p.isIdent("MEASURES") || p.isIdent("RULES");
    }

    private boolean isModelStop() {
        return p.is(SqlTokenType.WHERE) || p.is(SqlTokenType.GROUP) || p.is(SqlTokenType.HAVING)
                || p.is(SqlTokenType.ORDER) || p.is(SqlTokenType.LIMIT) || p.is(SqlTokenType.OFFSET)
                || p.is(SqlTokenType.FETCH) || p.is(SqlTokenType.UNION) || p.is(SqlTokenType.INTERSECT)
                || p.is(SqlTokenType.EXCEPT) || p.is(SqlTokenType.MINUS) || p.is(SqlTokenType.WINDOW)
                || p.is(SqlTokenType.FOR) || p.is(SqlTokenType.LOCK) || p.is(SqlTokenType.START)
                || p.is(SqlTokenType.CONNECT)
                || (p.is(SqlTokenType.IDENT) && p.token.textEqualsIgnoreCase("OPTION"));
    }

    private void parseHiveDistributeSort(SqlSelect select) {
        if (p.isIdent("DISTRIBUTE")) {
            p.next();
            p.expect(SqlTokenType.BY);
            select.setDistributeBy(consumeCommaExprListRaw());
        }
        if (p.isIdent("CLUSTER")) {
            p.next();
            p.expect(SqlTokenType.BY);
            select.setClusterBy(consumeCommaExprListRaw());
        }
        if (p.isIdent("SORT")) {
            p.next();
            p.expect(SqlTokenType.BY);
            int start = p.token.start();
            java.util.ArrayList<SqlOrderByItem> tmp = new java.util.ArrayList<SqlOrderByItem>(2);
            parseOrderBy(tmp);
            select.setSortBy(p.lexer.rawSlice(start, p.token.start()).trim());
        }
    }

    private String consumeCommaExprListRaw() {
        int start = p.token.start();
        do {
            p.exprParser.parseExpr();
        } while (p.match(SqlTokenType.COMMA));
        return p.lexer.rawSlice(start, p.token.start()).trim();
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
