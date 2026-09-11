package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlOverExpr;
import com.alianga.jkit.sql.ast.SqlQueryExpr;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式递归下降解析协作类，共享 {@link SqlParser} 记号游标。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
final class SqlExprParser {
    private final SqlParser p;

    SqlExprParser(SqlParser parser) {
        this.p = parser;
    }

    private static SqlBinaryOp atOp(String text) {
        if ("<@".equals(text)) {
            return SqlBinaryOp.CONTAINED_BY;
        }
        return SqlBinaryOp.CONTAINS;
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

    /**
     * 当前是否 {@code (+)} Oracle 外连接标记（不消费记号）。
     *
     * @return 是否外连接标记
     */
    private boolean lookingAtOracleOuterJoin() {
        if (!p.is(SqlTokenType.LPAREN)) {
            return false;
        }
        SqlToken plus = p.lexer.peek();
        if (plus == null || plus.type() != SqlTokenType.PLUS) {
            return false;
        }
        char[] src = plus.src;
        if (src == null) {
            return false;
        }
        int i = plus.end();
        while (i < src.length) {
            char c = src[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            return c == ')';
        }
        return false;
    }

    private SqlExpr parseAdd() {
        SqlExpr left = parseMul();
        while (p.isSymbolOp(SqlTokenType.PLUS) || p.isSymbolOp(SqlTokenType.MINUS)) {
            SqlBinaryOp op = p.isSymbolOp(SqlTokenType.PLUS) ? SqlBinaryOp.PLUS : SqlBinaryOp.MINUS;
            p.next();
            // DB2：CURRENT_DATE + (1 DAY) / - 1 DAY（裸单位）
            if (p.is(SqlTokenType.LPAREN) && lookingAtParenInterval()) {
                p.next();
                SqlFunctionExpr iv = new SqlFunctionExpr();
                iv.setName(SqlIdentifier.of("INTERVAL"));
                iv.addArgument(parsePrimary());
                if (isIntervalUnitToken()) {
                    iv.addArgument(SqlIdentifier.of(consumeIntervalUnitRaw()));
                }
                p.expect(SqlTokenType.RPAREN);
                left = SqlBinaryExpr.of(left, op, iv);
            } else {
                SqlExpr right = parseMul();
                // CURRENT_DATE - 1 DAY
                if (isIntervalUnitToken()) {
                    SqlFunctionExpr iv = new SqlFunctionExpr();
                    iv.setName(SqlIdentifier.of("INTERVAL"));
                    iv.addArgument(right);
                    iv.addArgument(SqlIdentifier.of(consumeIntervalUnitRaw()));
                    right = iv;
                }
                left = SqlBinaryExpr.of(left, op, right);
            }
        }
        return left;
    }

    private boolean lookingAtParenInterval() {
        if (!p.is(SqlTokenType.LPAREN)) {
            return false;
        }
        SqlToken a = p.lexer.lookahead(0);
        SqlToken b = p.lexer.lookahead(1);
        if (a == null || b == null) {
            return false;
        }
        if (a.type() != SqlTokenType.NUMBER && a.type() != SqlTokenType.STRING
                && a.type() != SqlTokenType.BIND) {
            return false;
        }
        String u = b.text();
        return u != null && ("DAY".equalsIgnoreCase(u) || "DAYS".equalsIgnoreCase(u)
                || "YEAR".equalsIgnoreCase(u) || "YEARS".equalsIgnoreCase(u)
                || "MONTH".equalsIgnoreCase(u) || "MONTHS".equalsIgnoreCase(u)
                || "HOUR".equalsIgnoreCase(u) || "HOURS".equalsIgnoreCase(u)
                || "MINUTE".equalsIgnoreCase(u) || "SECOND".equalsIgnoreCase(u)
                || "WEEK".equalsIgnoreCase(u));
    }

    /** 位置游离的 optimizer hint（如 WHERE 中的 {@code /*+TDDL:MASTER*&#47;}），暂存后由语句层统一挂出。 */
    private final java.util.List<String> floatingHints = new java.util.ArrayList<String>(0);

    /**
     * 跳过当前位置连续的 hint 注释（语义等同注释，不影响条件结构）。
     */
    void skipFloatingHints() {
        while (p.is(SqlTokenType.HINT)) {
            floatingHints.add(p.token.text());
            p.next();
        }
    }

    /**
     * 取出暂存的游离 hint 并清空（语句层挂到 SELECT）。
     *
     * @return hint 原文列表，可能为空
     */
    java.util.List<String> drainFloatingHints() {
        if (floatingHints.isEmpty()) {
            return floatingHints;
        }
        java.util.List<String> copy = new java.util.ArrayList<String>(floatingHints);
        floatingHints.clear();
        return copy;
    }

    private SqlExpr parseAnd() {
        SqlExpr left = parseNot();
        while (true) {
            skipFloatingHints();
            if (!p.is(SqlTokenType.AND) && !p.is(SqlTokenType.AND_OP)) {
                break;
            }
            p.next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.AND, parseNot());
        }
        return left;
    }

    private SqlExpr parseBetween(SqlExpr left, boolean not) {
        p.expect(SqlTokenType.BETWEEN);
        SqlBetweenExpr b = new SqlBetweenExpr();
        b.setExpr(left);
        b.setNot(not);
        b.setBegin(parseBit());
        p.expect(SqlTokenType.AND);
        b.setEnd(parseBit());
        return b;
    }

    private SqlExpr parseBit() {
        SqlExpr left = parseAdd();
        while (p.is(SqlTokenType.BIT_AND) || p.is(SqlTokenType.BIT_OR) || p.is(SqlTokenType.BIT_XOR)
                || p.is(SqlTokenType.SHIFT_LEFT) || p.is(SqlTokenType.SHIFT_RIGHT)
                || p.is(SqlTokenType.CONCAT) || p.is(SqlTokenType.JSON_OP)) {
            SqlTokenType t = p.token.type();
            // 单 | 后接 SELECT/INSERT… 时视为脚本分隔，留给 parseAll
            if (t == SqlTokenType.BIT_OR && p.token.length() == 1 && lookingAtStmtKeyword(p.lexer.peek())) {
                break;
            }
            String opText = p.token.text();
            p.next();
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
        return left;
    }

    private boolean lookingAtJsonExistsOp() {
        if (!p.is(SqlTokenType.BIND)) {
            return false;
        }
        if (lookingAtTernary()) {
            return false;
        }
        SqlToken after = p.lexer.peek();
        if (after == null) {
            return false;
        }
        SqlTokenType t = after.type();
        if (t == SqlTokenType.COMMA || t == SqlTokenType.RPAREN || t == SqlTokenType.EOF
                || t == SqlTokenType.SEMICOLON || t == SqlTokenType.AND || t == SqlTokenType.OR
                || t == SqlTokenType.ORDER || t == SqlTokenType.GROUP || t == SqlTokenType.LIMIT
                || t == SqlTokenType.UNION || t == SqlTokenType.HAVING) {
            return false;
        }
        // ?& / ?|
        if (t == SqlTokenType.BIT_AND || t == SqlTokenType.BIT_OR) {
            return true;
        }
        // col ? :name / col ? $1 类绑定键
        if (t == SqlTokenType.NAMED_BIND || t == SqlTokenType.BIND || t == SqlTokenType.COLON) {
            return true;
        }
        // col ? 'key' / col ? ident / col ? (...)
        return t == SqlTokenType.STRING || t == SqlTokenType.IDENT || t == SqlTokenType.LPAREN
                || (after.type() != null && after.type().keyword());
    }

    /** {@code a ? b : c}：? 后可解析中段且深度 0 处有冒号（非 jsonb ? / 非 ?|）。 */
    private boolean lookingAtTernary() {
        if (!p.is(SqlTokenType.BIND)) {
            return false;
        }
        SqlToken after = p.lexer.peek();
        if (after == null) {
            return false;
        }
        SqlTokenType t0 = after.type();
        if (t0 == SqlTokenType.BIT_AND || t0 == SqlTokenType.BIT_OR
                || t0 == SqlTokenType.COMMA || t0 == SqlTokenType.RPAREN
                || t0 == SqlTokenType.EOF || t0 == SqlTokenType.SEMICOLON
                || t0 == SqlTokenType.NAMED_BIND || t0 == SqlTokenType.COLON) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < 64; i++) {
            SqlToken tok = p.lexer.lookahead(i);
            if (tok == null || tok.type() == SqlTokenType.EOF) {
                return false;
            }
            SqlTokenType t = tok.type();
            if (t == SqlTokenType.LPAREN || t == SqlTokenType.LBRACKET) {
                depth++;
                continue;
            }
            if (t == SqlTokenType.RPAREN || t == SqlTokenType.RBRACKET) {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (t == SqlTokenType.COLON) {
                return true;
            }
            if (t == SqlTokenType.COMMA || t == SqlTokenType.AND || t == SqlTokenType.OR
                    || t == SqlTokenType.SEMICOLON || t == SqlTokenType.FROM
                    || t == SqlTokenType.WHERE || t == SqlTokenType.UNION) {
                return false;
            }
        }
        return false;
    }

    private static boolean lookingAtStmtKeyword(SqlToken tok) {
        if (tok == null || tok.type() == null) {
            return false;
        }
        SqlTokenType t = tok.type();
        return t == SqlTokenType.SELECT || t == SqlTokenType.INSERT || t == SqlTokenType.UPDATE
                || t == SqlTokenType.DELETE || t == SqlTokenType.MERGE || t == SqlTokenType.WITH
                || t == SqlTokenType.CREATE || t == SqlTokenType.DROP || t == SqlTokenType.ALTER
                || t == SqlTokenType.REPLACE || t == SqlTokenType.CALL || t == SqlTokenType.GRANT;
    }

    private SqlCaseExpr parseCase() {
        p.expect(SqlTokenType.CASE);
        SqlCaseExpr cse = new SqlCaseExpr();
        if (!p.is(SqlTokenType.WHEN)) {
            cse.setValue(parseExpr());
        }
        while (p.match(SqlTokenType.WHEN)) {
            SqlExpr when = parseExpr();
            p.expect(SqlTokenType.THEN);
            cse.addWhenThen(when, parseExpr());
        }
        if (p.match(SqlTokenType.ELSE)) {
            cse.setElseExpr(parseExpr());
        }
        p.expect(SqlTokenType.END);
        return cse;
    }

    private SqlCastExpr parseCast() {
        // CAST / TRY_CAST / TRY_CONVERT
        if (p.is(SqlTokenType.CAST)) {
            p.next();
        } else {
            p.next(); // TRY_CAST / TRY_CONVERT ident
        }
        p.expect(SqlTokenType.LPAREN);
        SqlCastExpr cast = new SqlCastExpr();
        cast.setExpr(parseExpr());
        p.expect(SqlTokenType.AS);
        cast.setDataType(parseDataType());
        p.expect(SqlTokenType.RPAREN);
        return cast;
    }

    private SqlExpr parseBitOrScalarQuery() {
        if (p.isQueryStart()) {
            return SqlQueryExpr.of(p.parseStatement());
        }
        return parseBit();
    }

    private SqlExpr parseComparison() {
        SqlExpr left = parseBit();
        while (true) {
            skipFloatingHints();
            if (p.is(SqlTokenType.EQ) || p.is(SqlTokenType.ASSIGN)) {
                p.next();
                p.match(SqlTokenType.STAR); // 旧式 a =* b
                left = SqlBinaryExpr.of(left, SqlBinaryOp.EQ, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.STAR) && p.lexer.peek() != null
                    && p.lexer.peek().type() == SqlTokenType.EQ) {
                // 旧式外连接：a *= b
                p.next();
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.EQ, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.NE)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.NE, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.LT)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LT, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.GT)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.GT, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.LE)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LE, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.GE)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.GE, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.NULL_SAFE_EQ)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.NULL_SAFE_EQ, parseBitOrScalarQuery());
            } else if (p.is(SqlTokenType.AT_OP)) {
                String opText = p.token.text();
                p.next();
                left = SqlBinaryExpr.of(left, atOp(opText), parseBit());
            } else if (p.is(SqlTokenType.REGEX_OP)
                    || (p.dialect.supportsTildeRegex() && p.is(SqlTokenType.TILDE))) {
                String opText = p.token.text();
                p.next();
                left = SqlBinaryExpr.of(left, regexOp(opText), parseBit());
            } else if (p.is(SqlTokenType.IS)) {
                p.next();
                boolean not = p.match(SqlTokenType.NOT);
                if (p.match(SqlTokenType.DISTINCT)) {
                    p.expect(SqlTokenType.FROM);
                    left = SqlBinaryExpr.of(left,
                            not ? SqlBinaryOp.IS_NOT_DISTINCT_FROM : SqlBinaryOp.IS_DISTINCT_FROM,
                            parseBit());
                } else {
                    left = SqlBinaryExpr.of(left, not ? SqlBinaryOp.IS_NOT : SqlBinaryOp.IS, parseBit());
                }
            } else if (p.is(SqlTokenType.BIND) && lookingAtJsonExistsOp()) {
                // PG jsonb：col ? 'key' / col ?| array / col ?& array（? 与绑定同形）
                String op = "?";
                p.next();
                if (p.is(SqlTokenType.BIT_OR) && p.token.length() == 1) {
                    op = "?|";
                    p.next();
                } else if (p.is(SqlTokenType.BIT_AND) && p.token.length() == 1) {
                    op = "?&";
                    p.next();
                }
                SqlFunctionExpr fn = new SqlFunctionExpr();
                fn.setName(SqlIdentifier.of(op));
                fn.addArgument(left);
                fn.addArgument(parseBit());
                left = fn;
            } else if (p.is(SqlTokenType.LIKE) || p.is(SqlTokenType.ILIKE)
                    || p.is(SqlTokenType.REGEXP) || p.is(SqlTokenType.RLIKE)) {
                SqlBinaryOp op = p.is(SqlTokenType.ILIKE) ? SqlBinaryOp.ILIKE
                        : (p.is(SqlTokenType.LIKE) ? SqlBinaryOp.LIKE : SqlBinaryOp.REGEXP);
                p.next();
                left = SqlBinaryExpr.of(left, op, parseBit());
                if (p.match(SqlTokenType.ESCAPE)) {
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.ESCAPE, parseBit());
                }
            } else if (p.isIdent("SIMILAR")) {
                p.next();
                p.expect(SqlTokenType.TO);
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LIKE, parseBit());
                if (p.match(SqlTokenType.ESCAPE)) {
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.ESCAPE, parseBit());
                }
            } else if (p.is(SqlTokenType.BETWEEN)) {
                left = parseBetween(left, false);
            } else if (p.is(SqlTokenType.IN)) {
                left = parseIn(left, false);
            } else if (p.isIdent("INCLUDES") || p.isIdent("EXCLUDES")) {
                String op = p.token.text().toUpperCase();
                p.next();
                SqlFunctionExpr fn = new SqlFunctionExpr();
                fn.setName(SqlIdentifier.of(op));
                fn.addArgument(left);
                if (p.match(SqlTokenType.LPAREN)) {
                    if (!p.is(SqlTokenType.RPAREN)) {
                        do {
                            fn.addArgument(parseExpr());
                        } while (p.match(SqlTokenType.COMMA));
                    }
                    p.expect(SqlTokenType.RPAREN);
                } else {
                    fn.addArgument(parsePrimary());
                }
                left = fn;
            } else if (p.is(SqlTokenType.NOT)) {
                SqlToken peeked = p.lexer.peek();
                if (peeked.type() == SqlTokenType.LIKE) {
                    p.next();
                    p.next();
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.NOT_LIKE, parseBit());
                } else if (peeked.type() == SqlTokenType.IN) {
                    p.next();
                    left = parseIn(left, true);
                } else if (peeked.type() == SqlTokenType.BETWEEN) {
                    p.next();
                    left = parseBetween(left, true);
                } else if (peeked.type() == SqlTokenType.REGEXP || peeked.type() == SqlTokenType.RLIKE) {
                    p.next();
                    p.next();
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.NOT_REGEXP, parseBit());
                } else if (peeked.type() == SqlTokenType.ILIKE) {
                    p.next();
                    p.next();
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.NOT_ILIKE, parseBit());
                    if (p.match(SqlTokenType.ESCAPE)) {
                        left = SqlBinaryExpr.of(left, SqlBinaryOp.ESCAPE, parseBit());
                    }
                } else if (peeked.textEqualsIgnoreCase("SIMILAR")) {
                    p.next();
                    p.next();
                    p.expect(SqlTokenType.TO);
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.NOT_LIKE, parseBit());
                    if (p.match(SqlTokenType.ESCAPE)) {
                        left = SqlBinaryExpr.of(left, SqlBinaryOp.ESCAPE, parseBit());
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return left;
    }

    private SqlExpr parseConvert(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseExpr());
        if (p.match(SqlTokenType.USING)) {
            fn.setUsingCharset(true);
            if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                fn.addArgument(p.parseName());
            } else {
                fn.addArgument(parsePrimary());
            }
        } else if (p.match(SqlTokenType.COMMA)) {
            do {
                fn.addArgument(parseExpr());
            } while (p.match(SqlTokenType.COMMA));
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private String parseDataType() {
        StringBuilder sb = new StringBuilder();
        sb.append(p.consumeIdentRaw());
        // INTERVAL DAY TO SECOND / INTERVAL YEAR(2) TO MONTH
        if (SqlParser.equalsIgnoreCase(sb.toString(), "INTERVAL") && isIntervalUnitToken()) {
            sb.append(' ').append(consumeIntervalUnitRaw());
            if (p.match(SqlTokenType.TO)) {
                sb.append(" TO ");
                if (isIntervalUnitToken()) {
                    sb.append(consumeIntervalUnitRaw());
                } else {
                    sb.append(p.consumeIdentRaw());
                }
            }
            return sb.toString();
        }
        if (p.match(SqlTokenType.LPAREN)) {
            sb.append('(');
            sb.append(p.consumeRawUntilType(SqlTokenType.RPAREN));
            p.expect(SqlTokenType.RPAREN);
            sb.append(')');
        }
        while (p.is(SqlTokenType.UNSIGNED) || p.is(SqlTokenType.ZEROFILL) || p.is(SqlTokenType.VARYING)
                || p.is(SqlTokenType.PRECISION) || p.is(SqlTokenType.ZONE) || p.is(SqlTokenType.WITHOUT)
                || p.isIdent("TIME") || p.isIdent("SIGNED")) {
            sb.append(' ').append(p.token.text());
            p.next();
        }
        // MySQL：SIGNED INTEGER / UNSIGNED INTEGER / SIGNED INT
        String typeHead = sb.toString();
        if ((SqlParser.equalsIgnoreCase(typeHead, "SIGNED") || SqlParser.equalsIgnoreCase(typeHead, "UNSIGNED")
                || endsWithWord(typeHead, "SIGNED") || endsWithWord(typeHead, "UNSIGNED"))
                && (p.isIdent("INTEGER") || p.isIdent("INT")
                || (p.token != null && p.token.text() != null
                && ("INTEGER".equalsIgnoreCase(p.token.text()) || "INT".equalsIgnoreCase(p.token.text()))))) {
            sb.append(' ').append(p.token.text());
            p.next();
        }
        // MySQL：CHAR CHARACTER SET utf8；MySQL 8 索引表达式：CHAR(10) ARRAY
        if (p.match(SqlTokenType.CHARACTER)) {
            p.expect(SqlTokenType.SET);
            sb.append(' ').append("CHARACTER SET ").append(p.consumeIdentRaw());
        }
        // INTERVAL 单位也可写在类型名后：INTERVAL DAY(9) TO SECOND
        if (SqlParser.equalsIgnoreCase(sb.toString(), "INTERVAL") || isIntervalUnitToken()) {
            // already handled above when leading INTERVAL; keep TO chain for DATE TIME etc. no-op
        }
        if (p.match(SqlTokenType.TO) && isIntervalUnitToken()) {
            sb.append(" TO ").append(consumeIntervalUnitRaw());
        }
        while (p.is(SqlTokenType.ARRAY) || p.isIdent("ARRAY")) {
            sb.append(' ').append(p.token.text());
            p.next();
        }
        return sb.toString();
    }

    private static boolean endsWithWord(String s, String word) {
        if (s == null || word == null) {
            return false;
        }
        String u = s.toUpperCase();
        String w = word.toUpperCase();
        return u.equals(w) || u.endsWith(" " + w);
    }

    private String consumeIntervalUnitRaw() {
        String u = p.consumeIdentRaw();
        if (p.match(SqlTokenType.LPAREN)) {
            u = u + "(" + p.consumeRawUntilType(SqlTokenType.RPAREN) + ")";
            p.expect(SqlTokenType.RPAREN);
        }
        return u;
    }

    SqlExpr parseExpr() {
        return parseOr();
    }

    private SqlExpr parseExtract(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(p.parseName());
        p.expect(SqlTokenType.FROM);
        fn.addArgument(parseExpr());
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private String parseFrameBound() {
        if (p.match(SqlTokenType.UNBOUNDED)) {
            if (p.match(SqlTokenType.PRECEDING)) {
                return "UNBOUNDED PRECEDING";
            }
            p.expect(SqlTokenType.FOLLOWING);
            return "UNBOUNDED FOLLOWING";
        }
        if (p.match(SqlTokenType.CURRENT)) {
            p.expect(SqlTokenType.ROW);
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
        if (p.match(SqlTokenType.PRECEDING)) {
            return value + " PRECEDING";
        }
        if (p.match(SqlTokenType.FOLLOWING)) {
            return value + " FOLLOWING";
        }
        return value;
    }

    SqlExpr parseFunction(SqlIdentifier name) {
        String fnName = name.simpleName();
        if (SqlParser.equalsIgnoreCase(fnName, "EXTRACT")) {
            return parseExtract(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "TRIM")) {
            return parseTrim(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "SUBSTRING")) {
            return parseSubstring(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "POSITION")) {
            return parsePosition(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "CONVERT")
                || SqlParser.equalsIgnoreCase(fnName, "TRANSLATE")) {
            return parseConvert(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "GROUP_CONCAT") || SqlParser.equalsIgnoreCase(fnName, "STRING_AGG")) {
            return parseGroupConcatLike(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "WEIGHT_STRING")) {
            return parseWeightString(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "JSON_OBJECT")
                || SqlParser.equalsIgnoreCase(fnName, "JSON_ARRAY")
                || SqlParser.equalsIgnoreCase(fnName, "JSON_OBJECTAGG")
                || SqlParser.equalsIgnoreCase(fnName, "JSON_ARRAYAGG")
                || SqlParser.equalsIgnoreCase(fnName, "JSON_TABLE")
                || SqlParser.equalsIgnoreCase(fnName, "XMLSERIALIZE")
                || SqlParser.equalsIgnoreCase(fnName, "XMLPARSE")
                || SqlParser.equalsIgnoreCase(fnName, "XMLROOT")
                || SqlParser.equalsIgnoreCase(fnName, "XMLAGG")
                || SqlParser.equalsIgnoreCase(fnName, "XMLELEMENT")
                || SqlParser.equalsIgnoreCase(fnName, "XMLFOREST")
                || SqlParser.equalsIgnoreCase(fnName, "EXTRACTVALUE")) {
            return parseRawArgsFunction(name);
        }
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (p.match(SqlTokenType.DISTINCT) || p.match(SqlTokenType.UNIQUE)) {
            fn.setDistinct(true);
        } else {
            p.match(SqlTokenType.ALL); // count(ALL x)
        }
        if (!p.is(SqlTokenType.RPAREN) && !p.is(SqlTokenType.ORDER)) {
            do {
                skipFloatingHints();
                if (p.is(SqlTokenType.ORDER)) {
                    break;
                }
                if (p.is(SqlTokenType.STAR)) {
                    fn.addArgument(parsePrimary());
                } else if (p.isQueryStart()) {
                    fn.addArgument(SqlQueryExpr.of(p.parseStatement()));
                } else {
                    fn.addArgument(parseExpr());
                }
            } while (p.match(SqlTokenType.COMMA));
        }
        // PG/标准：ARRAY_AGG(x ORDER BY y) / STRING_AGG 已走专用路径
        if (p.match(SqlTokenType.ORDER)) {
            p.expect(SqlTokenType.BY);
            p.selectParser.parseOrderBy(fn.orderBy());
        }
        // MySQL：CHAR(888 USING utf8) / CONVERT 已走专用路径；其它函数同样接受 USING
        if (p.match(SqlTokenType.USING)) {
            fn.setUsingCharset(true);
            if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                fn.addArgument(p.parseName());
            } else {
                fn.addArgument(parsePrimary());
            }
        }
        // last_value(x IGNORE NULLS) / RESPECT NULLS
        if ((p.is(SqlTokenType.IGNORE) || p.isIdent("RESPECT"))
                && p.lexer.peek() != null && p.lexer.peek().textEqualsIgnoreCase("NULLS")) {
            fn.setAggOption(p.token.text().toUpperCase() + " NULLS");
            p.next();
            p.next();
        }
        p.expect(SqlTokenType.RPAREN);
        // ClickHouse 参数化聚合：windowFunnel(n)(ts, cond…) / quantile(0.9)(x)
        if (p.is(SqlTokenType.LPAREN)) {
            java.util.ArrayList<SqlExpr> params = new java.util.ArrayList<SqlExpr>(fn.arguments());
            fn.setParameters(params);
            fn.arguments().clear();
            p.next();
            if (!p.is(SqlTokenType.RPAREN)) {
                do {
                    if (p.is(SqlTokenType.STAR)) {
                        fn.addArgument(parsePrimary());
                    } else if (p.isQueryStart()) {
                        fn.addArgument(SqlQueryExpr.of(p.parseStatement()));
                    } else {
                        fn.addArgument(parseExpr());
                    }
                } while (p.match(SqlTokenType.COMMA));
            }
            p.expect(SqlTokenType.RPAREN);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "MATCH")) {
            parseMatchAgainst(fn);
        }
        parseFunctionTail(fn);
        return fn;
    }

    private void parseFunctionTail(SqlFunctionExpr fn) {
        if (p.isIdent("FILTER")) {
            p.next();
            p.expect(SqlTokenType.LPAREN);
            p.expect(SqlTokenType.WHERE);
            fn.setFilter(parseExpr());
            p.expect(SqlTokenType.RPAREN);
        }
        // Oracle：MAX(x) KEEP (DENSE_RANK LAST ORDER BY y)
        if (p.isIdent("KEEP")) {
            p.next();
            p.expect(SqlTokenType.LPAREN);
            // skipBalancedParensContent 已消费配对右括号
            String keep = p.skipBalancedParensContent();
            fn.setKeepClause("(" + keep + ")");
        }
        // SQL Server / PG：PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY x) [OVER (...)]
        if (p.isIdent("WITHIN")) {
            p.next();
            p.expect(SqlTokenType.GROUP);
            p.expect(SqlTokenType.LPAREN);
            p.expect(SqlTokenType.ORDER);
            p.expect(SqlTokenType.BY);
            p.selectParser.parseOrderBy(fn.orderBy());
            p.expect(SqlTokenType.RPAREN);
            fn.setWithinGroup(true);
        }
        // lag(x) IGNORE NULLS OVER (...)
        if (fn.aggOption() == null && (p.is(SqlTokenType.IGNORE) || p.isIdent("RESPECT"))
                && p.lexer.peek() != null && p.lexer.peek().textEqualsIgnoreCase("NULLS")) {
            fn.setAggOption(p.token.text().toUpperCase() + " NULLS");
            p.next();
            p.next();
        }
        if (p.match(SqlTokenType.OVER)) {
            fn.setOver(parseOver());
        }
    }

    private SqlExpr parseGroupConcatLike(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (p.match(SqlTokenType.DISTINCT)) {
            fn.setDistinct(true);
        }
        if (!p.is(SqlTokenType.RPAREN)) {
            fn.addArgument(parseExpr());
            while (p.match(SqlTokenType.COMMA)) {
                if (p.is(SqlTokenType.ORDER)) {
                    break;
                }
                fn.addArgument(parseExpr());
            }
            if (p.match(SqlTokenType.ORDER)) {
                p.expect(SqlTokenType.BY);
                p.selectParser.parseOrderBy(fn.orderBy());
            }
            if (p.isIdent("SEPARATOR")) {
                p.next();
                fn.setSeparator(parseExpr());
            }
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseIn(SqlExpr left, boolean not) {
        p.expect(SqlTokenType.IN);
        SqlInExpr in = new SqlInExpr();
        in.setExpr(left);
        in.setNot(not);
        // MyBatis 等：IN :types / IN ? 可不写括号（单一绑定即整个列表）
        if (p.is(SqlTokenType.NAMED_BIND) || p.is(SqlTokenType.BIND)) {
            List<SqlExpr> values = new ArrayList<SqlExpr>(1);
            values.add(parsePrimary());
            in.setValues(values);
            return in;
        }
        // 无括号子查询：col IN SELECT … / 单值 IN 1
        if (!p.is(SqlTokenType.LPAREN)) {
            if (p.isQueryStart()) {
                in.setSubquery(p.parseStatement());
                return in;
            }
            List<SqlExpr> values = new ArrayList<SqlExpr>(1);
            values.add(parsePrimary());
            in.setValues(values);
            return in;
        }
        p.expect(SqlTokenType.LPAREN);
        if (p.isQueryStart()) {
            in.setSubquery(p.parseStatement());
        } else {
            List<SqlExpr> values = new ArrayList<SqlExpr>(4);
            if (!p.is(SqlTokenType.RPAREN)) {
                do {
                    values.add(parseExpr());
                } while (p.match(SqlTokenType.COMMA));
            }
            in.setValues(values);
        }
        p.expect(SqlTokenType.RPAREN);
        return in;
    }

    private void parseMatchAgainst(SqlFunctionExpr fn) {
        if (!(p.is(SqlTokenType.AGAINST) || p.isIdent("AGAINST"))) {
            return;
        }
        p.next();
        p.expect(SqlTokenType.LPAREN);
        // 不能用 parseExpr：IN BOOLEAN MODE 会被当成 IN 谓词
        fn.setAgainst(parseBit());
        if (!p.is(SqlTokenType.RPAREN)) {
            String mod = p.consumeRawUntilType(SqlTokenType.RPAREN).trim();
            if (mod.length() > 0) {
                fn.setAgainstModifier(mod);
            }
        }
        p.expect(SqlTokenType.RPAREN);
    }

    private SqlExpr parseMul() {
        SqlExpr left = parseUnary();
        // 自定义 DELIMITER（如 //）与除法同形：语句终止处不再当二元运算符
        while (!p.atStmtBreak() && (p.is(SqlTokenType.STAR) || p.is(SqlTokenType.SLASH)
                || p.isSymbolOp(SqlTokenType.PERCENT) || p.is(SqlTokenType.DIV) || p.is(SqlTokenType.MOD))) {
            // 旧式外连接 a *= b：交给比较层，勿把 * 当乘
            if (p.is(SqlTokenType.STAR) && p.lexer.peek() != null
                    && p.lexer.peek().type() == SqlTokenType.EQ) {
                break;
            }
            SqlBinaryOp op;
            if (p.is(SqlTokenType.STAR)) {
                op = SqlBinaryOp.MUL;
            } else if (p.is(SqlTokenType.SLASH)) {
                op = SqlBinaryOp.DIV;
            } else if (p.is(SqlTokenType.DIV)) {
                op = SqlBinaryOp.INT_DIV;
            } else {
                op = SqlBinaryOp.MOD;
            }
            p.next();
            left = SqlBinaryExpr.of(left, op, parseUnary());
        }
        return left;
    }

    private SqlExpr parseNot() {
        if (p.is(SqlTokenType.NOT) || p.is(SqlTokenType.NOT_OP)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.NOT, parseNot());
        }
        return parseComparison();
    }

    private SqlExpr parseOr() {
        SqlExpr left = parseXor();
        while (true) {
            skipFloatingHints();
            if (!p.is(SqlTokenType.OR) && !p.is(SqlTokenType.OR_OP)) {
                break;
            }
            p.next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.OR, parseXor());
        }
        // 三元 a ? b : c（与 jsonb ? / JDBC ? 消歧见 lookingAtTernary）
        if (lookingAtTernary()) {
            p.next();
            SqlExpr mid = parseOr();
            p.expect(SqlTokenType.COLON);
            SqlFunctionExpr tern = new SqlFunctionExpr();
            tern.setName(SqlIdentifier.of("IF"));
            tern.addArgument(left);
            tern.addArgument(mid);
            tern.addArgument(parseOr());
            return tern;
        }
        return left;
    }

    SqlOverExpr parseOver() {
        SqlOverExpr over = new SqlOverExpr();
        if (!p.is(SqlTokenType.LPAREN) && p.identLike()) {
            over.setWindowName(p.parseName());
            return over;
        }
        p.expect(SqlTokenType.LPAREN);
        // 继承已有窗口：WINDOW w2 AS (w) / (w ORDER BY b) / (w PARTITION BY ... 非法但留给方言)
        if (p.identLike()
                && !p.is(SqlTokenType.PARTITION)
                && !p.is(SqlTokenType.ORDER)
                && !p.is(SqlTokenType.ROWS)
                && !p.isIdent("RANGE")
                && !p.isIdent("GROUPS")
                && !p.isIdent("DISTRIBUTE")
                && !p.isIdent("SORT")) {
            over.setExistingWindowName(p.parseName());
        }
        if (p.match(SqlTokenType.PARTITION)) {
            p.expect(SqlTokenType.BY);
            do {
                over.partitionBy().add(parseExpr());
            } while (p.match(SqlTokenType.COMMA));
        } else if (p.isIdent("DISTRIBUTE")) {
            // Spark / Databricks：OVER (DISTRIBUTE BY … SORT BY …)，语义对应 PARTITION/ORDER
            p.next();
            p.expect(SqlTokenType.BY);
            over.setSparkStyle(true);
            do {
                over.partitionBy().add(parseExpr());
            } while (p.match(SqlTokenType.COMMA));
        }
        if (p.match(SqlTokenType.ORDER)) {
            p.expect(SqlTokenType.BY);
            p.selectParser.parseOrderBy(over.orderBy());
        } else if (p.isIdent("SORT")) {
            p.next();
            p.expect(SqlTokenType.BY);
            over.setSparkStyle(true);
            p.selectParser.parseOrderBy(over.orderBy());
        }
        if (p.is(SqlTokenType.ROWS) || p.isIdent("RANGE")) {
            over.setFrameUnit(p.token.text().toUpperCase());
            p.next();
            if (p.match(SqlTokenType.BETWEEN)) {
                over.setFrameStart(parseFrameBound());
                p.expect(SqlTokenType.AND);
                over.setFrameEnd(parseFrameBound());
            } else {
                over.setFrameStart(parseFrameBound());
            }
        }
        p.expect(SqlTokenType.RPAREN);
        return over;
    }

    private SqlExpr parsePosition(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseExpr());
        if (p.match(SqlTokenType.IN)) {
            fn.addArgument(parseExpr());
        } else if (p.match(SqlTokenType.COMMA)) {
            fn.addArgument(parseExpr());
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    SqlExpr parsePrimary() {
        SqlExpr expr = parsePrimaryInner();
        while (p.match(SqlTokenType.DOT)) {
            if (p.match(SqlTokenType.STAR)) {
                SqlAllColumns all = new SqlAllColumns();
                if (expr instanceof SqlIdentifier) {
                    all.setOwner((SqlIdentifier) expr);
                }
                return all;
            }
            String part = p.consumeIdentPartRaw();
            if (expr instanceof SqlIdentifier) {
                ((SqlIdentifier) expr).addName(SqlParser.unquote(part));
            } else {
                SqlIdentifier id = new SqlIdentifier();
                id.addName(SqlParser.unquote(part));
                expr = id;
            }
        }
        // PL/SQL 游标/隐式游标属性：SQL%FOUND / c1%NOTFOUND / SQL%ROWCOUNT
        if (expr instanceof SqlIdentifier && p.isSymbolOp(SqlTokenType.PERCENT)
                && isCursorAttribute(p.lexer.peek())) {
            p.next(); // %
            String attr = p.consumeIdentRaw();
            SqlIdentifier id = (SqlIdentifier) expr;
            java.util.List<String> names = new java.util.ArrayList<String>(id.names());
            if (names.isEmpty()) {
                names.add("%" + attr);
            } else {
                int last = names.size() - 1;
                names.set(last, names.get(last) + "%" + attr);
            }
            id.setNames(names);
            return id;
        }
        // Oracle 外连接：col(+) / t.col(+)
        if (lookingAtOracleOuterJoin()) {
            p.next(); // (
            p.next(); // +
            p.expect(SqlTokenType.RPAREN);
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.ORACLE_OUTER_JOIN, expr);
        }
        if (p.is(SqlTokenType.LPAREN) && expr instanceof SqlIdentifier) {
            // 不直接 return：后面还要挂 ::type / [下标] / COLLATE 等后缀
            expr = parseFunction((SqlIdentifier) expr);
        }
        // 函数/子查询结果字段访问：f(args).f2.f3 / f(args).g(args)（标识符链已在上方 DOT 循环处理）
        while (!(expr instanceof SqlIdentifier) && p.is(SqlTokenType.DOT)
                && p.lexer.peek() != null
                && p.lexer.peek().type() != SqlTokenType.STAR) {
            p.next(); // DOT
            String part = p.consumeIdentPartRaw();
            SqlExpr field = SqlIdentifier.of(SqlParser.unquote(part));
            if (p.is(SqlTokenType.LPAREN)) {
                field = parseFunction((SqlIdentifier) field);
            }
            expr = SqlBinaryExpr.of(expr, SqlBinaryOp.MEMBER, field);
        }
        if (isArrayConstructorHead(expr)) {
            expr = parseArrayConstructor();
        }
        while (true) {
            boolean progressed = false;
            while (p.match(SqlTokenType.LBRACKET)) {
                SqlExpr index = parseExpr();
                p.expect(SqlTokenType.RBRACKET);
                expr = SqlBinaryExpr.of(expr, SqlBinaryOp.SUBSCRIPT, index);
                progressed = true;
            }
            while (p.is(SqlTokenType.DOT) && p.lexer.peek() != null
                    && p.lexer.peek().type() != SqlTokenType.STAR) {
                p.next();
                String part = p.consumeIdentPartRaw();
                SqlExpr field = SqlIdentifier.of(SqlParser.unquote(part));
                if (p.is(SqlTokenType.LPAREN)) {
                    field = parseFunction((SqlIdentifier) field);
                }
                if (expr instanceof SqlIdentifier) {
                    ((SqlIdentifier) expr).addName(SqlParser.unquote(part));
                    // 若已变函数调用则改走 MEMBER
                    if (field instanceof SqlFunctionExpr) {
                        expr = SqlBinaryExpr.of(SqlIdentifier.of(((SqlIdentifier) expr).qualifiedName()),
                                SqlBinaryOp.MEMBER, field);
                    }
                } else {
                    expr = SqlBinaryExpr.of(expr, SqlBinaryOp.MEMBER, field);
                }
                progressed = true;
            }
            if (!progressed) {
                break;
            }
        }
        if (p.match(SqlTokenType.COLLATE)) {
            expr = SqlBinaryExpr.of(expr, SqlBinaryOp.COLLATE, p.parseName());
        }
        // PG：expr AT TIME ZONE 'UTC'
        while (p.isIdent("AT") && p.lexer.peek() != null
                && (p.lexer.peek().type() == SqlTokenType.TIME
                || p.lexer.peek().textEqualsIgnoreCase("TIME"))) {
            p.next(); // AT
            p.next(); // TIME
            if (!(p.isIdent("ZONE") || (p.token.type() != null && p.token.textEqualsIgnoreCase("ZONE")))) {
                break;
            }
            p.next(); // ZONE
            SqlFunctionExpr atz = new SqlFunctionExpr();
            atz.setName(SqlIdentifier.of("AT TIME ZONE"));
            atz.addArgument(expr);
            atz.addArgument(parsePrimaryInner());
            expr = atz;
        }
        // PG ::type 绑定紧于算术（COUNT(*)::numeric / 2）
        while (p.match(SqlTokenType.CAST_OP)) {
            SqlCastExpr cast = new SqlCastExpr();
            cast.setExpr(expr);
            cast.setPostgresStyle(true);
            cast.setDataType(parseDataType());
            expr = cast;
        }
        return expr;
    }

    /**
     * 是否为数组构造起头：标识符 {@code ARRAY} 紧跟 {@code [}。
     *
     * <p>与 {@code col[1]} 下标区分：下标左边是普通列，ARRAY 在此是构造关键字。</p>
     */
    private boolean isArrayConstructorHead(SqlExpr expr) {
        return expr instanceof SqlIdentifier
                && "ARRAY".equalsIgnoreCase(((SqlIdentifier) expr).simpleName())
                && p.is(SqlTokenType.LBRACKET);
    }

    /**
     * PG / 标准数组构造 {@code ARRAY[1, 2, 3]}，元素可为任意表达式。
     */
    private SqlExpr parseArrayConstructor() {
        p.expect(SqlTokenType.LBRACKET);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(SqlIdentifier.of("ARRAY"));
        fn.setArrayConstructor(true);
        if (!p.is(SqlTokenType.RBRACKET)) {
            do {
                if (p.is(SqlTokenType.LBRACKET)) {
                    // ARRAY[[1,2],[3,4]] 嵌套数组字面量
                    fn.arguments().add(parseArrayConstructor());
                } else {
                    fn.arguments().add(parseExpr());
                }
            } while (p.match(SqlTokenType.COMMA));
        }
        p.expect(SqlTokenType.RBRACKET);
        // ARRAY[]::text[] 等类型转换已由上层 :: 处理
        return fn;
    }

    private SqlExpr parsePrimaryInner() {
        if (p.is(SqlTokenType.LBRACE)) {
            return parseJdbcEscape();
        }
        if (p.is(SqlTokenType.NULL)) {
            p.next();
            return SqlLiteral.of(SqlLiteral.Kind.NULL, "NULL");
        }
        if (p.is(SqlTokenType.TRUE) || p.is(SqlTokenType.FALSE)) {
            String v = p.token.text();
            p.next();
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, v);
        }
        if (p.is(SqlTokenType.NUMBER) || p.is(SqlTokenType.HEX) || p.is(SqlTokenType.BIT)) {
            SqlLiteral.Kind kind = p.is(SqlTokenType.NUMBER) ? SqlLiteral.Kind.NUMBER
                    : (p.is(SqlTokenType.HEX) ? SqlLiteral.Kind.HEX : SqlLiteral.Kind.BIT);
            SqlLiteral lit = SqlLiteral.of(kind, p.token.text());
            p.next();
            return lit;
        }
        if (p.identLike() && p.token.text() != null && p.token.text().length() > 1
                && p.token.text().charAt(0) == '_'
                && (p.lexer.peek().type() == SqlTokenType.STRING
                || p.lexer.peek().type() == SqlTokenType.HEX
                || p.lexer.peek().type() == SqlTokenType.BIT)) {
            // MySQL 字符集前缀：_latin1'string' / _utf32 X'...' / _utf32 0x...
            String prefix = p.token.text();
            p.next();
            SqlLiteral.Kind kind = p.is(SqlTokenType.HEX) ? SqlLiteral.Kind.HEX
                    : (p.is(SqlTokenType.BIT) ? SqlLiteral.Kind.BIT : SqlLiteral.Kind.STRING);
            SqlLiteral lit = SqlLiteral.of(kind, p.token.text());
            lit.setName(prefix);
            p.next();
            return lit;
        }
        if (p.is(SqlTokenType.STRING)) {
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.STRING, p.token.text());
            p.next();
            // MySQL 相邻字符串字面量隐式拼接：'a' 'b' / "%"'温'"%"
            while (p.is(SqlTokenType.STRING)) {
                lit = SqlLiteral.of(SqlLiteral.Kind.STRING, lit.value() + p.token.text());
                if (lit.name() == null && p.token.text() != null) {
                    // keep first prefix if any
                }
                p.next();
            }
            return lit;
        }
        if (p.is(SqlTokenType.BIND)) {
            p.next();
            return SqlLiteral.of(SqlLiteral.Kind.BIND, "?");
        }
        if (p.is(SqlTokenType.NAMED_BIND)) {
            String raw = p.token.text();
            p.next();
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.NAMED_BIND, raw);
            lit.setName(raw.startsWith(":") ? raw.substring(1) : raw);
            return lit;
        }
        if (p.is(SqlTokenType.VARIABLE)) {
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.VARIABLE, p.token.text());
            p.next();
            return lit;
        }
        if (p.is(SqlTokenType.STAR)) {
            p.next();
            return new SqlAllColumns();
        }
        if (p.is(SqlTokenType.CASE)) {
            return parseCase();
        }
        if (p.is(SqlTokenType.CAST) || p.isIdent("TRY_CAST") || p.isIdent("TRY_CONVERT")) {
            return parseCast();
        }
        if ((p.is(SqlTokenType.DATE) || p.is(SqlTokenType.TIME) || p.is(SqlTokenType.TIMESTAMP)
                || p.is(SqlTokenType.DATETIME))
                && p.lexer.peek().type() == SqlTokenType.STRING) {
            SqlFunctionExpr typed = new SqlFunctionExpr();
            typed.setName(SqlIdentifier.of(p.token.text().toUpperCase()));
            p.next();
            typed.addArgument(parsePrimaryInner());
            return typed;
        }
        // DB2 / 标准：CURRENT TIMESTAMP / CURRENT DATE / CURRENT TIME / CURRENT TIMEZONE
        if (p.is(SqlTokenType.CURRENT) || p.isIdent("CURRENT")) {
            SqlToken peeked = p.lexer.peek();
            if (peeked != null && (peeked.type() == SqlTokenType.TIMESTAMP || peeked.type() == SqlTokenType.DATE
                    || peeked.type() == SqlTokenType.TIME || peeked.textEqualsIgnoreCase("TIMEZONE")
                    || peeked.textEqualsIgnoreCase("USER") || peeked.textEqualsIgnoreCase("SCHEMA"))) {
                p.next();
                String second = p.token.text().toUpperCase();
                p.next();
                return SqlIdentifier.of("CURRENT " + second);
            }
        }
        if (p.is(SqlTokenType.INTERVAL)) {
            p.next();
            SqlFunctionExpr fn = new SqlFunctionExpr();
            fn.setName(SqlIdentifier.of("INTERVAL"));
            // INTERVAL '30 minutes' / INTERVAL 30 DAY / INTERVAL '1' HOUR / INTERVAL 6/4 HOUR_MINUTE
            // / INTERVAL '123-2' YEAR(3) TO MONTH / INTERVAL '4 5:12:10.222' DAY TO SECOND(3)
            if (p.is(SqlTokenType.STRING)) {
                fn.addArgument(parsePrimaryInner());
            } else {
                fn.addArgument(parseBit());
            }
            if (isIntervalUnitToken()) {
                fn.addArgument(SqlIdentifier.of(consumeIntervalUnitRaw()));
                if (p.match(SqlTokenType.TO) && isIntervalUnitToken()) {
                    fn.addArgument(SqlIdentifier.of("TO"));
                    fn.addArgument(SqlIdentifier.of(consumeIntervalUnitRaw()));
                }
            }
            return fn;
        }
        if (p.match(SqlTokenType.LPAREN)) {
            if (p.isQueryStart()) {
                SqlQueryExpr q = SqlQueryExpr.of(p.parseStatement());
                p.expect(SqlTokenType.RPAREN);
                return q;
            }
            SqlExpr first = parseExpr();
            if (p.match(SqlTokenType.COMMA)) {
                SqlListExpr list = new SqlListExpr();
                list.add(first);
                do {
                    list.add(parseExpr());
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
                return list;
            }
            // (RAND() * 12 MONTH) / (1 DAY) 已在加减路径处理；此处兜底括号内尾部单位
            if (isIntervalUnitToken()) {
                SqlFunctionExpr iv = new SqlFunctionExpr();
                iv.setName(SqlIdentifier.of("INTERVAL"));
                iv.addArgument(first);
                iv.addArgument(SqlIdentifier.of(consumeIntervalUnitRaw()));
                first = iv;
            }
            p.expect(SqlTokenType.RPAREN);
            return first;
        }
        if (p.identLike() || p.token.type().keyword()) {
            return p.parseName();
        }
        throw p.error("unexpected token " + p.token.type());
    }

    private boolean isCursorAttribute(SqlToken tok) {
        if (tok == null || tok.text() == null) {
            return false;
        }
        String t = tok.text();
        return SqlParser.equalsIgnoreCase(t, "FOUND")
                || SqlParser.equalsIgnoreCase(t, "NOTFOUND")
                || SqlParser.equalsIgnoreCase(t, "ROWCOUNT")
                || SqlParser.equalsIgnoreCase(t, "ISOPEN")
                || SqlParser.equalsIgnoreCase(t, "BULK_ROWCOUNT")
                || SqlParser.equalsIgnoreCase(t, "BULK_EXCEPTIONS");
    }

    private boolean isIntervalUnitToken() {
        // INTERVAL 单位可为关键字 DAY/YEAR/MONTH/HOUR…（token.keyword==true）
        if (!(p.identLike() || (p.token.type() != null && p.token.type().keyword()))) {
            return false;
        }
        String t = p.token.text();
        if (t == null) {
            return false;
        }
        return SqlParser.equalsIgnoreCase(t, "YEAR")
                || SqlParser.equalsIgnoreCase(t, "YEARS")
                || SqlParser.equalsIgnoreCase(t, "MONTH")
                || SqlParser.equalsIgnoreCase(t, "MONTHS")
                || SqlParser.equalsIgnoreCase(t, "WEEK")
                || SqlParser.equalsIgnoreCase(t, "WEEKS")
                || SqlParser.equalsIgnoreCase(t, "DAY")
                || SqlParser.equalsIgnoreCase(t, "DAYS")
                || SqlParser.equalsIgnoreCase(t, "HOUR")
                || SqlParser.equalsIgnoreCase(t, "HOURS")
                || SqlParser.equalsIgnoreCase(t, "MINUTE")
                || SqlParser.equalsIgnoreCase(t, "MINUTES")
                || SqlParser.equalsIgnoreCase(t, "SECOND")
                || SqlParser.equalsIgnoreCase(t, "SECONDS")
                || SqlParser.equalsIgnoreCase(t, "MICROSECOND")
                || SqlParser.equalsIgnoreCase(t, "MICROSECONDS")
                || SqlParser.equalsIgnoreCase(t, "QUARTER")
                || SqlParser.equalsIgnoreCase(t, "QUARTERS")
                || SqlParser.equalsIgnoreCase(t, "SECOND_MICROSECOND")
                || SqlParser.equalsIgnoreCase(t, "MINUTE_MICROSECOND")
                || SqlParser.equalsIgnoreCase(t, "MINUTE_SECOND")
                || SqlParser.equalsIgnoreCase(t, "HOUR_MICROSECOND")
                || SqlParser.equalsIgnoreCase(t, "HOUR_SECOND")
                || SqlParser.equalsIgnoreCase(t, "HOUR_MINUTE")
                || SqlParser.equalsIgnoreCase(t, "DAY_MICROSECOND")
                || SqlParser.equalsIgnoreCase(t, "DAY_SECOND")
                || SqlParser.equalsIgnoreCase(t, "DAY_MINUTE")
                || SqlParser.equalsIgnoreCase(t, "DAY_HOUR")
                || SqlParser.equalsIgnoreCase(t, "YEAR_MONTH");
    }

    /**
     * MySQL 8 {@code WEIGHT_STRING(expr [AS CHAR(n)|AS BINARY(n)] [LEVEL n [ASC|DESC]])}。
     * 普通调用按常规参数解析；出现 {@code AS 类型} / {@code LEVEL} 特殊尾段时，
     * 括号内容整体按原文保留为单个参数（诊断函数，不做结构化）。
     */
    private SqlExpr parseWeightString(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (p.match(SqlTokenType.RPAREN)) {
            return fn;
        }
        int mark = p.token.start();
        SqlExpr first = parseExpr();
        if (p.match(SqlTokenType.RPAREN)) {
            fn.addArgument(first);
            parseFunctionTail(fn);
            return fn;
        }
        if (p.is(SqlTokenType.AS) || p.isIdent("LEVEL")) {
            int depth = 1;
            while (depth > 0 && !p.is(SqlTokenType.EOF)) {
                if (p.is(SqlTokenType.LPAREN)) {
                    depth++;
                } else if (p.is(SqlTokenType.RPAREN)) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                p.next();
            }
            String raw = p.lexer.rawSlice(mark, p.token.start()).trim();
            p.expect(SqlTokenType.RPAREN);
            fn.addArgument(SqlIdentifier.of(raw));
            parseFunctionTail(fn);
            return fn;
        }
        fn.addArgument(first);
        while (p.match(SqlTokenType.COMMA)) {
            fn.addArgument(parseExpr());
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    /**
     * JDBC/ODBC 转义（JDBC 标准转义语法）：{@code {fn f(...)}} 解包为函数调用，
     * {@code {d '…'}} / {@code {t '…'}} / {@code {ts '…'}} 转 DATE/TIME/TIMESTAMP 类型字面量，
     * {@code {escape '…'}} 与未知花括号形式按透明分组解包。
     * 回写输出解包后的标准形式（转义包装不保留）。
     */
    private SqlExpr parseJdbcEscape() {
        p.expect(SqlTokenType.LBRACE);
        if (p.isIdent("fn")) {
            p.next();
            SqlExpr inner = parseExpr();
            p.expect(SqlTokenType.RBRACE);
            return inner;
        }
        if (p.isIdent("d") || p.isIdent("t") || p.isIdent("ts")) {
            String kw = p.token.text().toUpperCase();
            p.next();
            SqlFunctionExpr typed = new SqlFunctionExpr();
            typed.setName(SqlIdentifier.of("TS".equals(kw) ? "TIMESTAMP" : kw));
            typed.addArgument(parsePrimaryInner());
            p.expect(SqlTokenType.RBRACE);
            return typed;
        }
        if (p.isIdent("escape")) {
            p.next();
            SqlExpr inner = parseExpr();
            p.expect(SqlTokenType.RBRACE);
            return inner;
        }
        // 兜底：透明分组
        SqlExpr inner = parseExpr();
        p.expect(SqlTokenType.RBRACE);
        return inner;
    }

    /**
     * SQL/JSON 构造器与表函数（{@code JSON_OBJECT} / {@code JSON_ARRAY} / {@code JSON_TABLE} 等）：
     * 括号内是 SQL/JSON 专用文法（key:value、KEY…VALUE、ABSENT|NULL ON NULL、
     * WITH|WITHOUT UNIQUE KEYS、FORMAT JSON [ENCODING]、COLUMNS … PATH），
     * 不属于通用表达式，整体按原文保留为单个参数。
     * 注意：原文内的 {@code ?} 绑定不会进 {@code parameters()}。
     */
    private SqlExpr parseRawArgsFunction(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        int start = p.token.start();
        int depth = 1;
        while (depth > 0 && !p.is(SqlTokenType.EOF)) {
            if (p.is(SqlTokenType.LPAREN)) {
                depth++;
            } else if (p.is(SqlTokenType.RPAREN)) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            p.next();
        }
        String raw = p.lexer.rawSlice(start, p.token.start()).trim();
        p.expect(SqlTokenType.RPAREN);
        if (!raw.isEmpty()) {
            fn.addArgument(SqlIdentifier.of(raw));
        }
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseSubstring(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        fn.addArgument(parseExpr());
        if (p.match(SqlTokenType.FROM)) {
            fn.addArgument(parseExpr());
            if (p.match(SqlTokenType.FOR)) {
                fn.addArgument(parseExpr());
            }
        } else if (p.match(SqlTokenType.COMMA)) {
            do {
                fn.addArgument(parseExpr());
            } while (p.match(SqlTokenType.COMMA));
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseTrim(SqlIdentifier name) {
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (p.is(SqlTokenType.BOTH) || p.is(SqlTokenType.LEADING) || p.is(SqlTokenType.TRAILING)) {
            fn.addArgument(SqlIdentifier.of(p.token.text().toUpperCase()));
            p.next();
        }
        if (p.match(SqlTokenType.FROM)) {
            fn.addArgument(parseExpr());
        } else {
            fn.addArgument(parseExpr());
            if (p.match(SqlTokenType.FROM)) {
                fn.addArgument(parseExpr());
            } else if (p.match(SqlTokenType.COMMA)) {
                // SQLite：trim(X, Y) 去掉两端出现在 Y 中的字符
                do {
                    fn.addArgument(parseExpr());
                } while (p.match(SqlTokenType.COMMA));
            }
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseUnary() {
        if (p.isSymbolOp(SqlTokenType.MINUS)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.MINUS, parseUnary());
        }
        if (p.isSymbolOp(SqlTokenType.PLUS)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.PLUS, parseUnary());
        }
        if (p.is(SqlTokenType.TILDE)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.TILDE, parseUnary());
        }
        // MySQL：&test 变量式引用 / 位取址
        if (p.is(SqlTokenType.BIT_AND) && p.token.length() == 1) {
            p.next();
            SqlFunctionExpr amp = new SqlFunctionExpr();
            amp.setName(SqlIdentifier.of("&"));
            amp.addArgument(parseUnary());
            return amp;
        }
        if (p.is(SqlTokenType.BINARY)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.BINARY, parseUnary());
        }
        if (p.is(SqlTokenType.EXISTS)) {
            p.next();
            p.expect(SqlTokenType.LPAREN);
            SqlUnaryExpr u = SqlUnaryExpr.of(SqlUnaryExpr.Op.EXISTS, SqlQueryExpr.of(p.parseStatement()));
            p.expect(SqlTokenType.RPAREN);
            return u;
        }
        if (p.isIdent("PRIOR")) {
            p.next();
            SqlFunctionExpr prior = new SqlFunctionExpr();
            prior.setName(SqlIdentifier.of("PRIOR"));
            prior.addArgument(parseUnary());
            return prior;
        }
        if (p.isIdent("CONNECT_BY_ROOT")) {
            p.next();
            SqlFunctionExpr root = new SqlFunctionExpr();
            root.setName(SqlIdentifier.of("CONNECT_BY_ROOT"));
            root.addArgument(parseUnary());
            return root;
        }
        return parsePrimary();
    }

    private SqlExpr parseXor() {
        SqlExpr left = parseAnd();
        while (true) {
            skipFloatingHints();
            if (!p.is(SqlTokenType.XOR)) {
                break;
            }
            p.next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.XOR, parseAnd());
        }
        return left;
    }

    private static SqlBinaryOp regexOp(String text) {
        if ("~*".equals(text)) {
            return SqlBinaryOp.REGEX_MATCH_CI;
        }
        if ("!~*".equals(text)) {
            return SqlBinaryOp.REGEX_NOT_MATCH_CI;
        }
        if ("!~".equals(text)) {
            return SqlBinaryOp.REGEX_NOT_MATCH;
        }
        return SqlBinaryOp.REGEX_MATCH;
    }
}
