package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;

/**
 * CREATE / DROP / ALTER / TRUNCATE / GRANT / COMMENT 解析协作类，共享 {@link SqlParser} 记号游标。
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
                paramTail = "(" + p.skipBalancedParensContent() + ")";
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

    SqlStatement parseGrant() {
        p.expect(SqlTokenType.GRANT);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.GRANT);
        StringBuilder priv = new StringBuilder();
        while (!p.is(SqlTokenType.ON) && !p.is(SqlTokenType.TO) && !p.is(SqlTokenType.EOF)
                && !p.is(SqlTokenType.SEMICOLON) && !p.is(SqlTokenType.GO)) {
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
