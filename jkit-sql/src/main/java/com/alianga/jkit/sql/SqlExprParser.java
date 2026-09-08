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
        while (p.is(SqlTokenType.PLUS) || p.is(SqlTokenType.MINUS)) {
            SqlBinaryOp op = p.is(SqlTokenType.PLUS) ? SqlBinaryOp.PLUS : SqlBinaryOp.MINUS;
            p.next();
            left = SqlBinaryExpr.of(left, op, parseMul());
        }
        return left;
    }

    private SqlExpr parseAnd() {
        SqlExpr left = parseNot();
        while (p.is(SqlTokenType.AND) || p.is(SqlTokenType.AND_OP)) {
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
                || p.is(SqlTokenType.CONCAT) || p.is(SqlTokenType.JSON_OP) || p.is(SqlTokenType.CAST_OP)) {
            SqlTokenType t = p.token.type();
            String opText = p.token.text();
            p.next();
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
        p.expect(SqlTokenType.CAST);
        p.expect(SqlTokenType.LPAREN);
        SqlCastExpr cast = new SqlCastExpr();
        cast.setExpr(parseExpr());
        p.expect(SqlTokenType.AS);
        cast.setDataType(parseDataType());
        p.expect(SqlTokenType.RPAREN);
        return cast;
    }

    private SqlExpr parseComparison() {
        SqlExpr left = parseBit();
        while (true) {
            if (p.is(SqlTokenType.EQ)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.EQ, parseBit());
            } else if (p.is(SqlTokenType.NE)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.NE, parseBit());
            } else if (p.is(SqlTokenType.LT)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LT, parseBit());
            } else if (p.is(SqlTokenType.GT)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.GT, parseBit());
            } else if (p.is(SqlTokenType.LE)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.LE, parseBit());
            } else if (p.is(SqlTokenType.GE)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.GE, parseBit());
            } else if (p.is(SqlTokenType.NULL_SAFE_EQ)) {
                p.next();
                left = SqlBinaryExpr.of(left, SqlBinaryOp.NULL_SAFE_EQ, parseBit());
            } else if (p.is(SqlTokenType.AT_OP)) {
                String opText = p.token.text();
                p.next();
                left = SqlBinaryExpr.of(left, atOp(opText), parseBit());
            } else if (p.is(SqlTokenType.REGEX_OP)
                    || ((p.dialect == SqlDialect.POSTGRES || p.dialect == SqlDialect.H2)
                    && p.is(SqlTokenType.TILDE))) {
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
            } else if (p.is(SqlTokenType.LIKE) || p.is(SqlTokenType.ILIKE)
                    || p.is(SqlTokenType.REGEXP) || p.is(SqlTokenType.RLIKE)) {
                SqlBinaryOp op = p.is(SqlTokenType.ILIKE) ? SqlBinaryOp.ILIKE
                        : (p.is(SqlTokenType.LIKE) ? SqlBinaryOp.LIKE : SqlBinaryOp.REGEXP);
                p.next();
                left = SqlBinaryExpr.of(left, op, parseBit());
                if (p.match(SqlTokenType.ESCAPE)) {
                    left = SqlBinaryExpr.of(left, SqlBinaryOp.ESCAPE, parseBit());
                }
            } else if (p.is(SqlTokenType.BETWEEN)) {
                left = parseBetween(left, false);
            } else if (p.is(SqlTokenType.IN)) {
                left = parseIn(left, false);
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
        if (p.match(SqlTokenType.LPAREN)) {
            sb.append('(');
            sb.append(p.consumeRawUntilType(SqlTokenType.RPAREN));
            p.expect(SqlTokenType.RPAREN);
            sb.append(')');
        }
        while (p.is(SqlTokenType.UNSIGNED) || p.is(SqlTokenType.ZEROFILL) || p.is(SqlTokenType.VARYING)
                || p.is(SqlTokenType.PRECISION) || p.is(SqlTokenType.ZONE) || p.is(SqlTokenType.WITHOUT)
                || p.isIdent("TIME")) {
            sb.append(' ').append(p.token.text());
            p.next();
        }
        return sb.toString();
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
        if (SqlParser.equalsIgnoreCase(fnName, "CONVERT")) {
            return parseConvert(name);
        }
        if (SqlParser.equalsIgnoreCase(fnName, "GROUP_CONCAT") || SqlParser.equalsIgnoreCase(fnName, "STRING_AGG")) {
            return parseGroupConcatLike(name);
        }
        p.expect(SqlTokenType.LPAREN);
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(name);
        if (p.match(SqlTokenType.DISTINCT)) {
            fn.setDistinct(true);
        }
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
        if (p.match(SqlTokenType.OVER)) {
            fn.setOver(parseOver());
        }
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
        while (p.is(SqlTokenType.STAR) || p.is(SqlTokenType.SLASH) || p.is(SqlTokenType.PERCENT)
                || p.is(SqlTokenType.DIV) || p.is(SqlTokenType.MOD)) {
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
        while (p.is(SqlTokenType.OR) || p.is(SqlTokenType.OR_OP)) {
            p.next();
            left = SqlBinaryExpr.of(left, SqlBinaryOp.OR, parseXor());
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
                && !p.isIdent("GROUPS")) {
            over.setExistingWindowName(p.parseName());
        }
        if (p.match(SqlTokenType.PARTITION)) {
            p.expect(SqlTokenType.BY);
            do {
                over.partitionBy().add(parseExpr());
            } while (p.match(SqlTokenType.COMMA));
        }
        if (p.match(SqlTokenType.ORDER)) {
            p.expect(SqlTokenType.BY);
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
            if (expr instanceof SqlIdentifier) {
                ((SqlIdentifier) expr).addName(SqlParser.unquote(p.consumeIdentRaw()));
            } else {
                SqlIdentifier id = new SqlIdentifier();
                id.addName(SqlParser.unquote(p.consumeIdentRaw()));
                expr = id;
            }
        }
        // Oracle 外连接：col(+) / t.col(+)
        if (lookingAtOracleOuterJoin()) {
            p.next(); // (
            p.next(); // +
            p.expect(SqlTokenType.RPAREN);
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.ORACLE_OUTER_JOIN, expr);
        }
        if (p.is(SqlTokenType.LPAREN) && expr instanceof SqlIdentifier) {
            return parseFunction((SqlIdentifier) expr);
        }
        while (p.match(SqlTokenType.LBRACKET)) {
            SqlExpr index = parseExpr();
            p.expect(SqlTokenType.RBRACKET);
            expr = SqlBinaryExpr.of(expr, SqlBinaryOp.SUBSCRIPT, index);
        }
        if (p.match(SqlTokenType.COLLATE)) {
            expr = SqlBinaryExpr.of(expr, SqlBinaryOp.COLLATE, p.parseName());
        }
        return expr;
    }

    private SqlExpr parsePrimaryInner() {
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
        if (p.is(SqlTokenType.STRING)) {
            SqlLiteral lit = SqlLiteral.of(SqlLiteral.Kind.STRING, p.token.text());
            p.next();
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
        if (p.is(SqlTokenType.CAST)) {
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
        if (p.is(SqlTokenType.INTERVAL)) {
            p.next();
            SqlFunctionExpr fn = new SqlFunctionExpr();
            fn.setName(SqlIdentifier.of("INTERVAL"));
            fn.addArgument(parsePrimary());
            if (p.identLike()) {
                fn.addArgument(SqlIdentifier.of(p.consumeIdentRaw()));
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
            p.expect(SqlTokenType.RPAREN);
            return first;
        }
        if (p.identLike() || p.token.type().keyword()) {
            return p.parseName();
        }
        throw p.error("unexpected token " + p.token.type());
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
            }
        }
        p.expect(SqlTokenType.RPAREN);
        parseFunctionTail(fn);
        return fn;
    }

    private SqlExpr parseUnary() {
        if (p.is(SqlTokenType.MINUS)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.MINUS, parseUnary());
        }
        if (p.is(SqlTokenType.PLUS)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.PLUS, parseUnary());
        }
        if (p.is(SqlTokenType.TILDE)) {
            p.next();
            return SqlUnaryExpr.of(SqlUnaryExpr.Op.TILDE, parseUnary());
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
        return parsePrimary();
    }

    private SqlExpr parseXor() {
        SqlExpr left = parseAnd();
        while (p.is(SqlTokenType.XOR)) {
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
