package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlRoutineParam;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;

/**
 * CREATE / DROP / ALTER / TRUNCATE / GRANT / REVOKE / COMMENT 解析协作类，共享 {@link SqlParser} 记号游标。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
final class SqlDdlParser {
    private final SqlParser p;

    SqlDdlParser(SqlParser parser) {
        this.p = parser;
    }

    private String consumeOptionValue() {
        if (p.is(SqlTokenType.STRING) || p.is(SqlTokenType.NUMBER) || p.identLike()
                || (p.token.type() != null && p.token.type().keyword())) {
            String v = p.token.text();
            p.next();
            return v;
        }
        throw p.error("expected table option value");
    }

    /**
     * 表级约束段：若含 {@code REFERENCES tbl} 则抽引用表，其余仍跳到同层逗号。
     */
    private void extractForeignKeyReferences(SqlDdlStatement ddl, int depth) {
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
            if (p.is(SqlTokenType.LPAREN)) {
                depth++;
                p.next();
            } else if (p.is(SqlTokenType.RPAREN)) {
                depth--;
                if (depth == 0) {
                    return;
                }
                p.next();
            } else if (p.is(SqlTokenType.COMMA) && depth == 1) {
                return;
            } else if (p.is(SqlTokenType.REFERENCES) && depth == 1) {
                p.next();
                if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                    ddl.referencedTables().add(p.parseName());
                }
            } else {
                p.next();
            }
        }
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

    SqlStatement parseAlter() {
        SqlDdlStatement ddl = new SqlDdlStatement();
        ddl.setStatementType(SqlStatementType.ALTER);
        p.next();
        if (p.identLike() || p.token.type().keyword()) {
            ddl.setObjectType(p.token.text().toUpperCase());
            p.next();
        }
        if (p.identLike()) {
            ddl.names().add(p.parseName());
        }
        if (p.is(SqlTokenType.RENAME)) {
            p.next();
            if (p.match(SqlTokenType.TO) || p.match(SqlTokenType.AS)) {
                ddl.setAlterAction("RENAME TO");
                ddl.setRenameTo(p.parseName());
            } else {
                ddl.setTail("RENAME " + p.consumeRawUntilSemi());
            }
        } else if (p.isIdent("ADD") || p.is(SqlTokenType.DROP) || p.isIdent("MODIFY") || p.isIdent("CHANGE")) {
            String action = p.token.text().toUpperCase();
            p.next();
            if (parseAlterIndexAction(ddl, action)) {
                return ddl;
            }
            if ("ADD".equals(action) && parseAlterConstraintAction(ddl)) {
                return ddl;
            }
            boolean columnKw = false;
            if (p.isIdent("COLUMN")) {
                p.next();
                columnKw = true;
            }
            if (p.identLike()) {
                ddl.columns().add(p.parseName());
            }
            // CHANGE old_col new_col <definition…>
            if ("CHANGE".equals(action) && p.identLike()) {
                ddl.columns().add(p.parseName());
            }
            ddl.setAlterAction(columnKw ? action + " COLUMN" : action);
            String rest = p.consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if ("CHANGE".equals(action) || "MODIFY".equals(action)
                        || ("ADD".equals(action) && !ddl.columns().isEmpty())) {
                    ddl.setColumnDefinition(rest);
                } else {
                    ddl.setTail(rest);
                }
            }
        } else if (!p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)) {
            ddl.setTail(p.consumeRawUntilSemi());
        }
        return ddl;
    }

    /**
     * 解析 ADD CONSTRAINT / ADD FOREIGN KEY / ADD PRIMARY KEY / ADD UNIQUE / ADD CHECK。
     *
     * @return 是否成功识别为约束动作
     */
    private boolean parseAlterConstraintAction(SqlDdlStatement ddl) {
        boolean constraintKw = false;
        if (p.is(SqlTokenType.CONSTRAINT)) {
            p.next();
            constraintKw = true;
            if (p.identLike()) {
                ddl.setConstraintName(p.parseName());
            }
        }
        String ctype = null;
        if (p.is(SqlTokenType.FOREIGN)) {
            p.next();
            p.expect(SqlTokenType.KEY);
            ctype = "FOREIGN KEY";
        } else if (p.is(SqlTokenType.PRIMARY)) {
            p.next();
            p.expect(SqlTokenType.KEY);
            ctype = "PRIMARY KEY";
        } else if (p.is(SqlTokenType.UNIQUE)) {
            p.next();
            if (p.is(SqlTokenType.KEY) || p.is(SqlTokenType.INDEX)) {
                p.next();
            }
            ctype = "UNIQUE";
        } else if (p.is(SqlTokenType.CHECK)) {
            p.next();
            ctype = "CHECK";
        } else if (constraintKw) {
            ddl.setAlterAction("ADD CONSTRAINT");
            String rest = p.consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                ddl.setTail(rest);
            }
            return true;
        } else {
            return false;
        }
        ddl.setConstraintType(ctype);
        ddl.setAlterAction(constraintKw ? "ADD CONSTRAINT" : "ADD " + ctype);
        if (p.match(SqlTokenType.LPAREN)) {
            if ("CHECK".equals(ctype)) {
                String inner = p.skipBalancedParensContent();
                ddl.setTail("(" + inner + ")");
            } else {
                do {
                    ddl.indexColumns().add(p.parseName());
                    while (!p.is(SqlTokenType.COMMA) && !p.is(SqlTokenType.RPAREN)
                            && !p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)) {
                        p.next();
                    }
                } while (p.match(SqlTokenType.COMMA));
                p.expect(SqlTokenType.RPAREN);
            }
        }
        if (p.is(SqlTokenType.REFERENCES)) {
            p.next();
            if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                ddl.referencedTables().add(p.parseName());
            }
        }
        if (!p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)) {
            String rest = p.consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if (ddl.tail() == null || ddl.tail().isEmpty()) {
                    ddl.setTail(rest);
                } else {
                    ddl.setTail(ddl.tail() + " " + rest);
                }
            }
        }
        return true;
    }

    /**
     * 解析 ADD/DROP INDEX|KEY；成功则填好 AST 并返回 true。
     */
    private boolean parseAlterIndexAction(SqlDdlStatement ddl, String action) {
        boolean unique = false;
        if ("ADD".equals(action) && p.is(SqlTokenType.UNIQUE)) {
            p.next();
            unique = true;
        }
        if (!(p.is(SqlTokenType.INDEX) || p.isIdent("KEY"))) {
            return false;
        }
        String indexKw = p.token.text().toUpperCase();
        p.next();
        String alterAction = action + (unique ? " UNIQUE " : " ") + indexKw;
        ddl.setAlterAction(alterAction);
        if (p.identLike()) {
            ddl.setIndexName(p.parseName());
        }
        if ("ADD".equals(action) && p.match(SqlTokenType.LPAREN)) {
            do {
                ddl.indexColumns().add(p.parseName());
                // 跳过长度 / ASC / DESC 等列修饰
                while (!p.is(SqlTokenType.COMMA) && !p.is(SqlTokenType.RPAREN)
                        && !p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)) {
                    p.next();
                }
            } while (p.match(SqlTokenType.COMMA));
            p.expect(SqlTokenType.RPAREN);
        }
        if (!p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)) {
            String rest = p.consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                ddl.setTail(rest);
            }
        }
        return true;
    }

    SqlStatement parseCommentOn() {
        p.expect(SqlTokenType.COMMENT);
        p.expect(SqlTokenType.ON);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("COMMENT ON");
        if (p.is(SqlTokenType.TABLE) || p.is(SqlTokenType.INDEX) || p.is(SqlTokenType.VIEW)
                || p.isIdent("COLUMN") || p.identLike()) {
            text.append(' ').append(p.token.text().toUpperCase());
            p.next();
        }
        if (p.identLike()) {
            stmt.setName(p.parseName());
            text.append(' ').append(stmt.name().qualifiedName());
        }
        if (p.match(SqlTokenType.IS)) {
            text.append(" IS");
        }
        if (!p.atStmtBreak()) {
            String rest = p.consumeRawUntilSemi();
            if (!rest.isEmpty()) {
                text.append(' ').append(rest);
            }
        }
        stmt.setText(text.toString());
        return stmt;
    }

    /**
     * MySQL {@code CREATE DEFINER = user PROCEDURE|FUNCTION|TRIGGER|EVENT|VIEW …}：跳过 DEFINER 子句。
     * {@code user} 形如 {@code `root`@`localhost`} / {@code 'u'@'%'} / {@code CURRENT_USER}。
     */
    private void skipDefinerClause() {
        if (!p.isIdent("DEFINER")) {
            return;
        }
        p.next();
        p.match(SqlTokenType.EQ);
        if (p.isIdent("CURRENT_USER")) {
            p.next();
            if (p.match(SqlTokenType.LPAREN)) {
                p.expect(SqlTokenType.RPAREN);
            }
            return;
        }
        // user [@ host]；词法上 `@` 常为单独 VARIABLE，host 为下一 IDENT/STRING
        if (p.identLike() || p.is(SqlTokenType.STRING) || p.is(SqlTokenType.VARIABLE)) {
            p.next();
        }
        if (p.is(SqlTokenType.VARIABLE)) {
            String v = p.token.text();
            p.next();
            if ("@".equals(v) && (p.identLike() || p.is(SqlTokenType.STRING))) {
                p.next();
            }
        }
    }

    SqlStatement parseCreate() {
        p.expect(SqlTokenType.CREATE);
        boolean orReplace = false;
        if (p.match(SqlTokenType.OR)) {
            p.expect(SqlTokenType.REPLACE);
            orReplace = true;
        }
        p.match(SqlTokenType.TEMPORARY);
        p.match(SqlTokenType.TEMP);
        p.match(SqlTokenType.UNIQUE);
        p.match(SqlTokenType.MATERIALIZED);
        skipDefinerClause();
        SqlDdlStatement ddl = new SqlDdlStatement();
        ddl.setStatementType(SqlStatementType.CREATE);
        ddl.setOrReplace(orReplace);
        if (p.is(SqlTokenType.TABLE) || p.is(SqlTokenType.VIEW) || p.is(SqlTokenType.INDEX)
                || p.is(SqlTokenType.DATABASE) || p.is(SqlTokenType.SCHEMA)
                || p.is(SqlTokenType.SEQUENCE) || p.is(SqlTokenType.PROCEDURE)
                || p.is(SqlTokenType.FUNCTION) || p.is(SqlTokenType.TRIGGER)
                || p.is(SqlTokenType.EVENT)) {
            ddl.setObjectType(p.token.text().toUpperCase());
            p.next();
        } else if (p.identLike()) {
            ddl.setObjectType(p.consumeIdentRaw().toUpperCase());
        }
        if (p.match(SqlTokenType.IF)) {
            p.expect(SqlTokenType.NOT);
            p.expect(SqlTokenType.EXISTS);
            ddl.setIfNotExists(true);
        }
        ddl.names().add(p.parseName());
        // CREATE INDEX name ON table；EVENT 的 ON SCHEDULE 不抽成对象名
        if (isIndexObject(ddl.objectType()) && p.match(SqlTokenType.ON)) {
            ddl.names().add(p.parseName());
        }
        boolean routine = isRoutineObject(ddl.objectType());
        String paramTail = null;
        if (p.match(SqlTokenType.LPAREN)) {
            if (routine) {
                paramTail = parseRoutineParameters(ddl);
            } else {
                parseCreateColumns(ddl);
                p.expect(SqlTokenType.RPAREN);
            }
        }
        if (p.match(SqlTokenType.AS) || p.is(SqlTokenType.SELECT) || p.is(SqlTokenType.WITH)) {
            p.match(SqlTokenType.AS);
            ddl.setQuery(p.parseStatement());
        } else if (!p.atStmtBreak()) {
            if ("TABLE".equalsIgnoreCase(ddl.objectType())) {
                parseCreateTableOptions(ddl);
            } else if (routine && isProcedureOrFunction(ddl.objectType())) {
                parseRoutineBody(ddl, paramTail);
            } else {
                String body = p.consumeRawAllowingBeginEnd();
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

    private static boolean isProcedureOrFunction(String objectType) {
        return "PROCEDURE".equalsIgnoreCase(objectType) || "FUNCTION".equalsIgnoreCase(objectType);
    }

    /**
     * 解析 {@code ( [IN|OUT|INOUT] name type… , … )}，返回含括号的原文。
     */
    private String parseRoutineParameters(SqlDdlStatement ddl) {
        int start = p.token.start() - 1; // 已消费 LPAREN，尽量从 '(' 起
        // token.start 已在括号内；用拼接保证 paramTail 形态
        StringBuilder raw = new StringBuilder("(");
        boolean first = true;
        if (!p.is(SqlTokenType.RPAREN)) {
            do {
                if (!first) {
                    raw.append(',');
                }
                first = false;
                SqlRoutineParam param = new SqlRoutineParam();
                int pStart = p.token.start();
                if (p.is(SqlTokenType.IN) || p.isIdent("OUT") || p.isIdent("INOUT")
                        || (p.identLike() && ("IN".equalsIgnoreCase(p.token.text())
                        || "OUT".equalsIgnoreCase(p.token.text())
                        || "INOUT".equalsIgnoreCase(p.token.text())))) {
                    // IN 是关键字；OUT/INOUT 多为 IDENT
                    if (p.is(SqlTokenType.IN) || p.token.textEqualsIgnoreCase("IN")
                            || p.token.textEqualsIgnoreCase("OUT")
                            || p.token.textEqualsIgnoreCase("INOUT")) {
                        param.setMode(p.token.text().toUpperCase());
                        p.next();
                    }
                }
                if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                    param.setName(p.parseName());
                }
                int typeStart = p.token.start();
                int depth = 0;
                while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
                    if (depth == 0 && (p.is(SqlTokenType.COMMA) || p.is(SqlTokenType.RPAREN))) {
                        break;
                    }
                    if (p.is(SqlTokenType.LPAREN)) {
                        depth++;
                    } else if (p.is(SqlTokenType.RPAREN)) {
                        depth--;
                    }
                    p.next();
                }
                String typeRaw = p.lexer.rawSlice(typeStart, p.token.start()).trim();
                if (!typeRaw.isEmpty()) {
                    param.setTypeRaw(typeRaw);
                }
                ddl.parameters().add(param);
                raw.append(p.lexer.rawSlice(pStart, p.token.start()).trim());
            } while (p.match(SqlTokenType.COMMA));
        }
        p.expect(SqlTokenType.RPAREN);
        raw.append(')');
        return raw.toString();
    }

    private void parseRoutineBody(SqlDdlStatement ddl, String paramTail) {
        int start = p.token.start();
        // 特性子句（RETURNS / DETERMINISTIC / COMMENT …）保留进 bodyRaw；碰到 BEGIN 再拆语句
        while (!p.is(SqlTokenType.EOF) && !p.atStmtBreak() && !p.is(SqlTokenType.BEGIN)
                && !isRoutineExecutableStart()) {
            if (p.is(SqlTokenType.LPAREN)) {
                p.next();
                p.skipBalancedParensContent();
            } else {
                p.next();
            }
        }
        if (p.is(SqlTokenType.BEGIN)) {
            p.next(); // BEGIN
            // 客户端 DELIMITER 可能是 ;;，但 BEGIN 体内语句仍以 ; 结束
            String savedDelim = p.stmtDelimiter;
            p.stmtDelimiter = ";";
            try {
                while (!p.is(SqlTokenType.EOF)) {
                    while (p.is(SqlTokenType.SEMICOLON)) {
                        p.next();
                    }
                    if (p.is(SqlTokenType.END)) {
                        SqlToken peek = p.lexer.peek();
                        if (peek != null && isCompoundEndSuffix(peek)) {
                            ddl.bodyStatements().add(consumeCompoundRemainderAsOther());
                            continue;
                        }
                        p.next(); // END
                        break;
                    }
                    if (p.is(SqlTokenType.IF) || p.is(SqlTokenType.CASE) || p.isIdent("WHILE")
                            || p.isIdent("LOOP") || p.isIdent("REPEAT")) {
                        ddl.bodyStatements().add(consumeCompoundStatementAsOther());
                    } else {
                        try {
                            ddl.bodyStatements().add(p.parseStatement());
                        } catch (SqlParseException ex) {
                            ddl.bodyStatements().add(consumeUntilSemiAsOther());
                        }
                    }
                    if (p.is(SqlTokenType.SEMICOLON)) {
                        p.next();
                    }
                }
            } finally {
                p.stmtDelimiter = savedDelim;
            }
        } else if (!p.atStmtBreak()) {
            try {
                ddl.bodyStatements().add(p.parseStatement());
            } catch (SqlParseException ex) {
                // 回落原文
                p.consumeRawAllowingBeginEnd();
            }
        }
        String bodyRaw = p.lexer.rawSlice(start, p.token.start()).trim();
        ddl.setBodyRaw(bodyRaw);
        if (paramTail != null) {
            ddl.setTail(bodyRaw.isEmpty() ? paramTail : paramTail + " " + bodyRaw);
        } else {
            ddl.setTail(bodyRaw);
        }
    }

    private boolean isRoutineExecutableStart() {
        return p.is(SqlTokenType.SELECT) || p.is(SqlTokenType.WITH) || p.is(SqlTokenType.INSERT)
                || p.is(SqlTokenType.UPDATE) || p.is(SqlTokenType.DELETE) || p.is(SqlTokenType.REPLACE)
                || p.is(SqlTokenType.SET) || p.is(SqlTokenType.CALL) || p.is(SqlTokenType.DECLARE)
                || p.is(SqlTokenType.IF) || p.is(SqlTokenType.CASE)
                || p.isIdent("WHILE") || p.isIdent("LOOP") || p.isIdent("REPEAT")
                || p.isIdent("LEAVE") || p.isIdent("ITERATE") || p.isIdent("RETURN");
    }

    private static boolean isCompoundEndSuffix(SqlToken peek) {
        if (peek == null || peek.type() == null) {
            return false;
        }
        if (peek.type() == SqlTokenType.IF || peek.type() == SqlTokenType.CASE) {
            return true;
        }
        String t = peek.text();
        return t != null && ("WHILE".equalsIgnoreCase(t) || "LOOP".equalsIgnoreCase(t)
                || "REPEAT".equalsIgnoreCase(t));
    }

    private SqlStatement consumeCompoundStatementAsOther() {
        int start = p.token.start();
        String text = p.consumeRawAllowingBeginEnd();
        // consumeRawAllowingBeginEnd 在 beginDepth=0 时遇分号会停，对 IF 不够；
        // 若仍停在 END IF 之前，继续用配对消费
        if (p.is(SqlTokenType.IF) || p.is(SqlTokenType.CASE) || p.isIdent("WHILE")
                || p.isIdent("LOOP") || p.isIdent("REPEAT")
                || (!p.is(SqlTokenType.END) && !p.atStmtBreak() && !p.is(SqlTokenType.EOF))) {
            // 上面已消费了开头，若 text 只吃到首个分号，补全到匹配 END xxx
            text = p.lexer.rawSlice(start, p.token.start()).trim();
            if (!isAtCompoundClose()) {
                text = consumeUntilCompoundClose(start);
            }
        } else {
            text = p.lexer.rawSlice(start, p.token.start()).trim();
        }
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        stmt.setText(text);
        return stmt;
    }

    private SqlStatement consumeCompoundRemainderAsOther() {
        // 当前为 END，且后跟 IF/CASE/… —— 说明外层误判；整段当 OTHER 不该走到这里。
        // 保险：把 END xxx 吃掉作为一个 OTHER。
        int start = p.token.start();
        p.next(); // END
        if (!p.is(SqlTokenType.EOF) && !p.atStmtBreak()) {
            p.next(); // IF/CASE/…
        }
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        stmt.setText(p.lexer.rawSlice(start, p.token.start()).trim());
        return stmt;
    }

    private boolean isAtCompoundClose() {
        return p.is(SqlTokenType.END) && isCompoundEndSuffix(p.lexer.peek());
    }

    private String consumeUntilCompoundClose(int start) {
        int ifDepth = 0;
        int whileDepth = 0;
        int loopDepth = 0;
        int repeatDepth = 0;
        int caseDepth = 0;
        // 起点已在复合语句内部某处；重新从 start 不方便，只能从当前向后配对
        // 简化：提高 begin 风格，遇到 END IF/WHILE/… 递减，全 0 且刚消费完 END xxx 停止
        // 因已部分消费，用「见到 END <suffix> 且各深度归零」困难；改为：
        // 从当前起，统计未关闭的 IF/WHILE/LOOP/REPEAT/CASE。
        while (!p.is(SqlTokenType.EOF)) {
            if (p.is(SqlTokenType.IF)) {
                ifDepth++;
                p.next();
            } else if (p.isIdent("WHILE")) {
                whileDepth++;
                p.next();
            } else if (p.isIdent("LOOP")) {
                loopDepth++;
                p.next();
            } else if (p.isIdent("REPEAT")) {
                repeatDepth++;
                p.next();
            } else if (p.is(SqlTokenType.CASE)) {
                caseDepth++;
                p.next();
            } else if (p.is(SqlTokenType.END)) {
                p.next();
                if (p.is(SqlTokenType.IF)) {
                    if (ifDepth > 0) {
                        ifDepth--;
                    }
                    p.next();
                    if (ifDepth == 0 && whileDepth == 0 && loopDepth == 0 && repeatDepth == 0 && caseDepth == 0) {
                        break;
                    }
                } else if (p.is(SqlTokenType.CASE)) {
                    if (caseDepth > 0) {
                        caseDepth--;
                    }
                    p.next();
                    if (ifDepth == 0 && whileDepth == 0 && loopDepth == 0 && repeatDepth == 0 && caseDepth == 0) {
                        break;
                    }
                } else if (p.isIdent("WHILE")) {
                    if (whileDepth > 0) {
                        whileDepth--;
                    }
                    p.next();
                    if (ifDepth == 0 && whileDepth == 0 && loopDepth == 0 && repeatDepth == 0 && caseDepth == 0) {
                        break;
                    }
                } else if (p.isIdent("LOOP")) {
                    if (loopDepth > 0) {
                        loopDepth--;
                    }
                    p.next();
                    if (ifDepth == 0 && whileDepth == 0 && loopDepth == 0 && repeatDepth == 0 && caseDepth == 0) {
                        break;
                    }
                } else if (p.isIdent("REPEAT")) {
                    if (repeatDepth > 0) {
                        repeatDepth--;
                    }
                    p.next();
                    if (ifDepth == 0 && whileDepth == 0 && loopDepth == 0 && repeatDepth == 0 && caseDepth == 0) {
                        break;
                    }
                } else {
                    // 裸 END：可能是 BEGIN 块结束，交还调用方
                    break;
                }
            } else if (p.is(SqlTokenType.LPAREN)) {
                p.next();
                p.skipBalancedParensContent();
            } else {
                p.next();
            }
        }
        return p.lexer.rawSlice(start, p.token.start()).trim();
    }

    private SqlStatement consumeUntilSemiAsOther() {
        int start = p.token.start();
        String rest = p.consumeRawUntilSemi();
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        String text = p.lexer.rawSlice(start, p.token.start()).trim();
        if (text.isEmpty()) {
            text = rest;
        }
        stmt.setText(text);
        return stmt;
    }

    private void parseCreateColumns(SqlDdlStatement ddl) {
        int depth = 1;
        if (p.is(SqlTokenType.RPAREN)) {
            return;
        }
        do {
            int start = p.token.start();
            if (p.is(SqlTokenType.PRIMARY) || p.is(SqlTokenType.UNIQUE) || p.is(SqlTokenType.KEY)
                    || p.is(SqlTokenType.CONSTRAINT) || p.is(SqlTokenType.INDEX)
                    || p.is(SqlTokenType.FOREIGN) || p.is(SqlTokenType.CHECK)) {
                extractForeignKeyReferences(ddl, depth);
            } else if (p.identLike()) {
                ddl.columns().add(p.parseName());
                skipBalancedComma(depth);
            } else {
                skipBalancedComma(depth);
            }
            // 当前停在分隔 COMMA 或闭合 RPAREN：用源切片保留类型/约束原文
            String def = p.lexer.rawSlice(start, p.token.start()).trim();
            if (!def.isEmpty()) {
                ddl.columnDefinitions().add(def);
            }
        } while (p.match(SqlTokenType.COMMA));
    }

    private void parseCreateTableOptions(SqlDdlStatement ddl) {
        StringBuilder unknown = new StringBuilder();
        while (!p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF)) {
            if (p.is(SqlTokenType.ENGINE)) {
                p.next();
                p.match(SqlTokenType.EQ);
                ddl.setEngine(consumeOptionValue());
            } else if (p.is(SqlTokenType.CHARSET)
                    || (p.is(SqlTokenType.CHARACTER) && p.lexer.peek().type() == SqlTokenType.SET)) {
                if (p.is(SqlTokenType.CHARACTER)) {
                    p.next();
                    p.expect(SqlTokenType.SET);
                } else {
                    p.next();
                }
                p.match(SqlTokenType.EQ);
                ddl.setCharset(consumeOptionValue());
            } else if (p.is(SqlTokenType.DEFAULT)
                    && (p.lexer.peek().type() == SqlTokenType.CHARSET
                    || p.lexer.peek().type() == SqlTokenType.CHARACTER
                    || p.lexer.peek().type() == SqlTokenType.COLLATE)) {
                p.next();
                if (p.is(SqlTokenType.CHARSET)
                        || (p.is(SqlTokenType.CHARACTER) && p.lexer.peek().type() == SqlTokenType.SET)) {
                    if (p.is(SqlTokenType.CHARACTER)) {
                        p.next();
                        p.expect(SqlTokenType.SET);
                    } else {
                        p.next();
                    }
                    p.match(SqlTokenType.EQ);
                    ddl.setCharset(consumeOptionValue());
                } else if (p.is(SqlTokenType.COLLATE)) {
                    p.next();
                    p.match(SqlTokenType.EQ);
                    ddl.setCollate(consumeOptionValue());
                }
            } else if (p.is(SqlTokenType.COLLATE)) {
                p.next();
                p.match(SqlTokenType.EQ);
                ddl.setCollate(consumeOptionValue());
            } else if (p.is(SqlTokenType.COMMENT)) {
                p.next();
                p.match(SqlTokenType.EQ);
                if (p.is(SqlTokenType.STRING)) {
                    ddl.setComment(p.token.text());
                    p.next();
                } else {
                    ddl.setComment(consumeOptionValue());
                }
            } else {
                if (unknown.length() > 0) {
                    unknown.append(' ');
                }
                unknown.append(p.token.text());
                p.next();
            }
        }
        if (unknown.length() > 0) {
            ddl.setTail(unknown.toString());
        }
    }

    SqlStatement parseDrop() {
        p.expect(SqlTokenType.DROP);
        SqlDdlStatement ddl = new SqlDdlStatement();
        ddl.setStatementType(SqlStatementType.DROP);
        p.match(SqlTokenType.TEMPORARY);
        if (p.identLike() || p.token.type().keyword()) {
            ddl.setObjectType(p.token.text().toUpperCase());
            p.next();
        }
        if (p.match(SqlTokenType.IF)) {
            p.expect(SqlTokenType.EXISTS);
            ddl.setIfExists(true);
        }
        do {
            ddl.names().add(p.parseName());
        } while (p.match(SqlTokenType.COMMA));
        p.match(SqlTokenType.CASCADE);
        p.match(SqlTokenType.RESTRICT);
        return ddl;
    }

    /**
     * GRANT / REVOKE：权限列表 + ON 对象 + TO/FROM 收件人（收件人进 text，{@code user@host} 紧凑）。
     */
    SqlStatement parseGrant() {
        boolean revoke = p.is(SqlTokenType.REVOKE);
        if (revoke) {
            p.expect(SqlTokenType.REVOKE);
        } else {
            p.expect(SqlTokenType.GRANT);
        }
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(revoke ? SqlStatementType.REVOKE : SqlStatementType.GRANT);
        StringBuilder priv = new StringBuilder();
        while (!p.is(SqlTokenType.ON) && !p.is(SqlTokenType.TO) && !p.is(SqlTokenType.FROM)
                && !p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.GO)) {
            if (priv.length() > 0) {
                priv.append(' ');
            }
            priv.append(p.token.text());
            p.next();
        }
        stmt.setPrivileges(priv.toString().trim());
        if (p.match(SqlTokenType.ON)) {
            stmt.setName(parseGrantObject());
        }
        if (!p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.GO)) {
            String rest = p.consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setText(rest);
            }
        }
        return stmt;
    }

    /**
     * GRANT 对象：{@code *.*} / {@code db.*} / {@code db.table} / 普通名。
     */
    private SqlIdentifier parseGrantObject() {
        if (p.match(SqlTokenType.STAR)) {
            SqlIdentifier id = SqlIdentifier.of("*");
            if (p.match(SqlTokenType.DOT)) {
                if (p.match(SqlTokenType.STAR)) {
                    id.addName("*");
                } else if (p.identLike() || (p.token.type() != null && p.token.type().keyword())) {
                    id.addName(SqlParser.unquote(p.consumeIdentRaw()));
                }
            }
            return id;
        }
        SqlIdentifier id = p.parseName();
        if (p.match(SqlTokenType.DOT) && p.match(SqlTokenType.STAR)) {
            id.addName("*");
        }
        return id;
    }

    SqlStatement parseTruncate() {
        p.expect(SqlTokenType.TRUNCATE);
        p.match(SqlTokenType.TABLE);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.TRUNCATE);
        stmt.setName(p.parseName());
        return stmt;
    }

    private void skipBalancedComma(int depth) {
        while (!p.is(SqlTokenType.EOF) && !p.is(SqlTokenType.SEMICOLON)) {
            if (p.is(SqlTokenType.LPAREN)) {
                depth++;
                p.next();
            } else if (p.is(SqlTokenType.RPAREN)) {
                depth--;
                if (depth == 0) {
                    return;
                }
                p.next();
            } else if (p.is(SqlTokenType.COMMA) && depth == 1) {
                return;
            } else {
                p.next();
            }
        }
    }
}
