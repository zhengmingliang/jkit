package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlCopyStatement;
import com.alianga.jkit.sql.ast.SqlExplainStatement;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFlushStatement;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlLoadDataStatement;
import com.alianga.jkit.sql.ast.SqlLockTablesStatement;
import com.alianga.jkit.sql.ast.SqlMaintenanceStatement;
import com.alianga.jkit.sql.ast.SqlPrepareStatement;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlSetStatement;
import com.alianga.jkit.sql.ast.SqlShowStatement;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStartTransactionStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlTableHandlerStatement;
import com.alianga.jkit.sql.ast.SqlTransactionControlStatement;
import com.alianga.jkit.sql.ast.SqlWithItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归下降 SQL 解析器。实例可 {@link #reset} 后复用，配合 {@link ThreadLocal} 降低分配。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlParser {
    /** 共享词法器（包内协作类直接读写）。 */
    final SqlLexer lexer = new SqlLexer();
    /** 当前记号。 */
    SqlToken token;
    /** 当前方言。 */
    SqlDialectSpec dialect;
    /** 自定义语句解析器注册表（default 分支兜底）。 */
    SqlStatementParsers statementParsers;
    /** 是否保留普通注释。 */
    boolean keepComments;
    /** 待挂到下一条语句的注释。 */
    List<String> pendingComments;
    /**
     * 当前批处理语句终止符（MySQL 客户端 {@code DELIMITER} 切换；默认 {@code ;}）。
     * SQL Server {@code GO} 始终可作为分隔，不受此字段影响。
     */
    String stmtDelimiter = ";";

    final SqlExprParser exprParser;
    final SqlSelectParser selectParser;
    final SqlDmlParser dmlParser;
    final SqlDdlParser ddlParser;

    /**
     * 创建可复用解析器实例。
     */
    public SqlParser() {
        this.exprParser = new SqlExprParser(this);
        this.selectParser = new SqlSelectParser(this);
        this.dmlParser = new SqlDmlParser(this);
        this.ddlParser = new SqlDdlParser(this);
    }

    /**
     * 绑定输入。
     *
     * @param sql SQL
     * @param dialect 方言（枚举或 {@link SqlDialectWrapper} 自定义能力）
     */
    public void reset(String sql, SqlDialectSpec dialect) {
        reset(sql, dialect, SqlParseOptions.defaults());
    }

    /**
     * 绑定输入与解析选项。
     *
     * @param sql SQL
     * @param dialect 方言（枚举或 {@link SqlDialectWrapper} 自定义能力）
     * @param options 选项，null 视为默认
     * @since 2.0.1
     */
    public void reset(String sql, SqlDialectSpec dialect, SqlParseOptions options) {
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        if (options == null) {
            options = SqlParseOptions.defaults();
        }
        this.keepComments = options.keepComments();
        this.pendingComments = null;
        this.stmtDelimiter = ";";
        this.statementParsers = options.statementParsers();
        lexer.reset(sql, this.dialect);
        lexer.setKeepComments(this.keepComments);
        lexer.setPipesAsConcat(options.pipesAsConcat());
        SqlPlaceholders ph = options.placeholders();
        if (ph == null || ph.isEmpty()) {
            lexer.setPlaceholderPatterns(null);
        } else {
            List<SqlPlaceholderPattern> list = ph.patterns();
            lexer.setPlaceholderPatterns(list.toArray(new SqlPlaceholderPattern[list.size()]));
        }
        next();
    }

    /**
     * 解析全部语句（一条失败则整批抛错）。
     *
     * @return 语句列表
     */
    public List<SqlStatement> parseAll() {
        return parseAll(false);
    }

    /**
     * 解析全部语句。
     *
     * @param tolerant {@code true} 时单条失败记为带 {@link SqlSimpleStatement#parseError()} 的占位并继续
     * @return 语句列表
     * @since 2.0.1
     */
    public List<SqlStatement> parseAll(boolean tolerant) {
        List<SqlStatement> list = new ArrayList<SqlStatement>(1);
        boolean afterSeparator = true;
        while (!is(SqlTokenType.EOF)) {
            while (isStmtSeparator() || isSoftStmtSeparator()) {
                if (isStmtSeparator()) {
                    consumeStmtSeparator();
                } else {
                    next(); // '|' or '/' 脚本分隔（JSqlParser 语料常见）
                }
                afterSeparator = true;
            }
            if (is(SqlTokenType.EOF)) {
                break;
            }
            int stmtStart = token.start();
            if (tolerant) {
                try {
                    list.add(parseStatement());
                    afterSeparator = false;
                } catch (SqlParseException ex) {
                    pendingComments = null;
                    skipToStmtEnd();
                    SqlSimpleStatement bad = new SqlSimpleStatement();
                    bad.setStatementType(SqlStatementType.OTHER);
                    bad.setParseError(ex.getMessage());
                    String raw = lexer.rawSlice(stmtStart, token.start()).trim();
                    bad.setText(raw.isEmpty() ? ex.snippet() : raw);
                    list.add(bad);
                    afterSeparator = false;
                }
            } else {
                try {
                    list.add(parseStatement());
                    afterSeparator = false;
                } catch (SqlParseException ex) {
                    // 分号后垃圾：可跨多段跳过直至下一条真实语句或 EOF（软停）
                    if (!list.isEmpty() && afterSeparator) {
                        pendingComments = null;
                        boolean advanced = false;
                        while (!is(SqlTokenType.EOF) && !lookingAtBatchStmtStart()) {
                            if (isStmtSeparator()) {
                                consumeStmtSeparator();
                                advanced = true;
                            } else if (isSoftStmtSeparator()) {
                                next();
                                advanced = true;
                            } else {
                                next();
                                advanced = true;
                            }
                        }
                        if (is(SqlTokenType.EOF)) {
                            break; // 尾部垃圾软停
                        }
                        if (lookingAtBatchStmtStart() && advanced) {
                            afterSeparator = true;
                            continue;
                        }
                    }
                    throw ex;
                }
            }
            if (isStmtSeparator()) {
                consumeStmtSeparator();
                afterSeparator = true;
            } else if (isSoftStmtSeparator()) {
                next();
                afterSeparator = true;
            }
        }
        return list;
    }

    /** 脚本级软分隔：单字符 {@code |} / {@code /}（非 {@code ||}）。 */
    private boolean isSoftStmtSeparator() {
        if (token == null || token.type() == null) {
            return false;
        }
        if (token.type() == SqlTokenType.BIT_OR && token.length() == 1) {
            return true;
        }
        if (token.type() == SqlTokenType.SLASH && token.length() == 1) {
            return true;
        }
        return false;
    }

    private boolean lookingAtBatchStmtStart() {
        if (token == null || token.type() == null) {
            return false;
        }
        SqlTokenType t = token.type();
        return t == SqlTokenType.SELECT || t == SqlTokenType.INSERT || t == SqlTokenType.UPDATE
                || t == SqlTokenType.DELETE || t == SqlTokenType.MERGE || t == SqlTokenType.WITH
                || t == SqlTokenType.CREATE || t == SqlTokenType.DROP || t == SqlTokenType.ALTER
                || t == SqlTokenType.REPLACE || t == SqlTokenType.CALL || t == SqlTokenType.GRANT
                || t == SqlTokenType.TRUNCATE || t == SqlTokenType.BEGIN || t == SqlTokenType.DECLARE;
    }

    private void skipToStmtEnd() {
        while (!is(SqlTokenType.EOF) && !isStmtSeparator()) {
            next();
        }
    }

    /**
     * 解析一条表达式，要求消费完整输入（词法层已跳过尾部空白/注释；多余记号抛错）。
     *
     * @return 表达式
     * @since 2.0.1
     */
    public SqlExpr parseExpression() {
        if (is(SqlTokenType.EOF)) {
            throw error("empty expression");
        }
        SqlExpr expr = exprParser.parseExpr();
        if (!is(SqlTokenType.EOF)) {
            throw error("unexpected token after expression: " + token.type());
        }
        return expr;
    }

    /**
     * 解析一条语句。
     *
     * @return 语句
     */
    public SqlStatement parseStatement() {
        SqlStatement stmt;
        if (is(SqlTokenType.WITH)) {
            stmt = parseWith();
        } else {
            stmt = parseStatementNoWith();
        }
        attachPendingComments(stmt);
        return stmt;
    }

    private SqlStatement parseStatementNoWith() {
        // MySQL 客户端命令：不参与服务端语法，吞掉以免批语料在 DELIMITER 行失败
        if (isIdent("DELIMITER")) {
            return parseDelimiter();
        }
        SqlTokenType t = token.type();
        switch (t) {
            case SELECT:
            case VALUES:
                return selectParser.parseSelect();
            case INSERT:
                return dmlParser.parseInsert(false);
            case REPLACE:
                if (lexer.peek().type() == SqlTokenType.LPAREN) {
                    // replace(...) 是 MySQL 字符串函数调用的表达式语句，不是 REPLACE INTO
                    SqlSelect exprSelect = new SqlSelect();
                    SqlSelectItem exprItem = new SqlSelectItem();
                    exprItem.setExpr(exprParser.parseExpr());
                    exprSelect.selectItems().add(exprItem);
                    return exprSelect;
                }
                return dmlParser.parseInsert(true);
            case UPDATE:
                return dmlParser.parseUpdate();
            case DELETE:
                return dmlParser.parseDelete();
            case MERGE:
                return dmlParser.parseMerge();
            case CREATE:
                return ddlParser.parseCreate();
            case DROP:
                return ddlParser.parseDrop();
            case ALTER:
            case RENAME:
                return ddlParser.parseAlter();
            case TRUNCATE:
                return ddlParser.parseTruncate();
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
            case LBRACE:
                // JDBC/ODBC 转义：{call sp(...)} 解包为 CALL 语句（内联解析，兼容 CALL 关键字/ident 两种词法）
                if (lexer.peek().type() == SqlTokenType.CALL
                        || (lexer.peek().type() == SqlTokenType.IDENT
                                && lexer.peek().textEqualsIgnoreCase("call"))) {
                    next();
                    next();
                    SqlSimpleStatement call = new SqlSimpleStatement();
                    call.setStatementType(SqlStatementType.CALL);
                    call.setName(parseName());
                    if (match(SqlTokenType.LPAREN)) {
                        call.setWithArguments(true);
                        if (!is(SqlTokenType.RPAREN)) {
                            do {
                                call.arguments().add(exprParser.parseExpr());
                            } while (match(SqlTokenType.COMMA));
                        }
                        expect(SqlTokenType.RPAREN);
                    }
                    expect(SqlTokenType.RBRACE);
                    return call;
                }
                throw error("unsupported statement starting with " + t);
            case GRANT:
            case REVOKE:
                return ddlParser.parseGrant();
            case BEGIN:
                return parseBeginBlock();
            case START:
                return parseStartTransaction();
            case COMMIT:
            case ROLLBACK:
            case SAVEPOINT:
            case RELEASE:
                return parseTxControl();
            case FLUSH:
                return parseFlush();
            case LOCK:
                return parseLockTables();
            case UNLOCK:
                return parseUnlockTables();
            case DECLARE:
                return parseDeclare();
            case ANALYZE:
            case VACUUM:
            case OPTIMIZE:
            case REPAIR:
            case CHECK:
                return parseMaintenance();
            case COMMENT:
                return ddlParser.parseCommentOn();
            case COPY:
                return parseCopy();
            case LOAD:
                return parseLoad();
            case HANDLER:
                return parseHandler();
            case PREPARE:
            case EXECUTE:
            case DEALLOCATE:
                return parsePrepareFamily();
            case LPAREN:
                return selectParser.parseSelect();
            default:
                SqlStatementParser custom = findCustomStatementParser();
                if (custom != null) {
                    SqlStatement stmt = custom.parse(new SqlParseContext(this));
                    if (stmt == null) {
                        throw error("custom statement parser returned null: " + token.text());
                    }
                    if (!atStmtBreak()) {
                        throw error("custom statement parser left unconsumed input at: " + token.text());
                    }
                    return stmt;
                }
                throw error("unsupported statement starting with " + t);
        }
    }

    /**
     * default 分支兜底：前导关键字命中注册表才返回解析器，否则 null。
     *
     * @return 自定义解析器，未注册返回 {@code null}
     */
    private SqlStatementParser findCustomStatementParser() {
        if (statementParsers == null || statementParsers.isEmpty()) {
            return null;
        }
        String keyword = token.text();
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        return statementParsers.find(keyword);
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

    /**
     * {@code EXPLAIN [ANALYZE] [FORMAT …] stmt} / {@code DESCRIBE t} → {@link SqlExplainStatement}。
     */
    private SqlStatement parseExplain() {
        SqlExplainStatement stmt = new SqlExplainStatement();
        if (is(SqlTokenType.DESC) || is(SqlTokenType.DESCRIBE)) {
            stmt.setDescribe(true);
            next();
            if (identLike()) {
                stmt.setName(parseName());
            }
            if (!atStmtBreak()) {
                String rest = consumeRawUntilSemi();
                if (rest != null && !rest.isEmpty()) {
                    stmt.setRaw(rest);
                }
            }
            return stmt;
        }
        expect(SqlTokenType.EXPLAIN);
        // MySQL / PG 简写：EXPLAIN ANALYZE …
        if (is(SqlTokenType.ANALYZE) || (identLike() && "ANALYZE".equalsIgnoreCase(token.text()))
                || (identLike() && "ANALYSE".equalsIgnoreCase(token.text()))) {
            next();
            stmt.setAnalyze(true);
        }
        // PG：EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) …
        if (is(SqlTokenType.LPAREN)) {
            next();
            do {
                if (is(SqlTokenType.RPAREN) || is(SqlTokenType.EOF)) {
                    break;
                }
                String opt = token.text() == null ? "" : token.text().toUpperCase();
                next();
                if ("ANALYZE".equals(opt) || "ANALYSE".equals(opt)) {
                    stmt.setAnalyze(true);
                    if (is(SqlTokenType.TRUE) || is(SqlTokenType.FALSE)
                            || (identLike() && ("TRUE".equalsIgnoreCase(token.text())
                            || "FALSE".equalsIgnoreCase(token.text())
                            || "ON".equalsIgnoreCase(token.text())
                            || "OFF".equalsIgnoreCase(token.text())))) {
                        String v = token.text().toUpperCase();
                        next();
                        if ("FALSE".equals(v) || "OFF".equals(v)) {
                            stmt.setAnalyze(false);
                        }
                    }
                } else if ("FORMAT".equals(opt)) {
                    match(SqlTokenType.EQ);
                    if (!is(SqlTokenType.RPAREN) && !is(SqlTokenType.COMMA) && !is(SqlTokenType.EOF)) {
                        stmt.setFormat(token.text().toUpperCase());
                        next();
                    }
                } else if (opt.length() > 0) {
                    StringBuilder one = new StringBuilder(opt);
                    if (match(SqlTokenType.EQ)) {
                        if (!is(SqlTokenType.RPAREN) && !is(SqlTokenType.COMMA) && !is(SqlTokenType.EOF)) {
                            one.append('=').append(token.text().toUpperCase());
                            next();
                        }
                    } else if (is(SqlTokenType.TRUE) || is(SqlTokenType.FALSE)
                            || (identLike() && ("TRUE".equalsIgnoreCase(token.text())
                            || "FALSE".equalsIgnoreCase(token.text())
                            || "ON".equalsIgnoreCase(token.text())
                            || "OFF".equalsIgnoreCase(token.text())))) {
                        one.append(' ').append(token.text().toUpperCase());
                        next();
                    }
                    stmt.options().add(one.toString());
                }
            } while (match(SqlTokenType.COMMA));
            expect(SqlTokenType.RPAREN);
        }
        // MySQL：EXPLAIN FORMAT[=]JSON …
        if (is(SqlTokenType.FORMAT) || (identLike() && "FORMAT".equalsIgnoreCase(token.text()))) {
            next();
            match(SqlTokenType.EQ);
            if (!atStmtBreak()) {
                stmt.setFormat(token.text().toUpperCase());
                next();
            }
        }
        if (isQueryStart() || is(SqlTokenType.INSERT) || is(SqlTokenType.UPDATE)
                || is(SqlTokenType.DELETE) || is(SqlTokenType.MERGE) || is(SqlTokenType.REPLACE)
                || is(SqlTokenType.WITH)) {
            stmt.setStatement(parseStatement());
        } else if (identLike()) {
            stmt.setName(parseName());
        }
        if (!atStmtBreak() && stmt.statement() == null && stmt.raw() == null) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(rest);
            }
        }
        return stmt;
    }

    /**
     * {@code SET} / {@code SET NAMES} / {@code SET CHARACTER SET} / 多赋值 → {@link SqlSetStatement}。
     */
    private SqlStatement parseSet() {
        expect(SqlTokenType.SET);
        SqlSetStatement stmt = new SqlSetStatement();
        if (is(SqlTokenType.SESSION) || is(SqlTokenType.GLOBAL) || is(SqlTokenType.LOCAL)
                || isIdent("SESSION") || isIdent("GLOBAL") || isIdent("LOCAL")) {
            stmt.setScope(token.text().toUpperCase());
            next();
        }
        // SET PASSWORD [FOR user] = '…'：吞尾，避免 FOR 残留成下一条语句
        if (isIdent("PASSWORD")) {
            stmt.setSetKind("PASSWORD");
            next();
            if (!atStmtBreak()) {
                String rest = consumeRawUntilSemi();
                if (rest != null && !rest.isEmpty()) {
                    stmt.setRaw(rest);
                }
            }
            return stmt;
        }
        // SET NAMES charset [COLLATE …]
        if (match(SqlTokenType.NAMES)) {
            stmt.setSetKind("NAMES");
            SqlSetStatement.Assignment a = new SqlSetStatement.Assignment();
            a.setName(SqlIdentifier.of("NAMES"));
            a.setEqualsSign(false);
            if (!atStmtBreak()) {
                a.setValue(exprParser.parsePrimary());
            }
            stmt.assignments().add(a);
            if (!atStmtBreak()) {
                String rest = consumeRawUntilSemi();
                if (rest != null && !rest.isEmpty()) {
                    stmt.setRaw(rest);
                }
            }
            return stmt;
        }
        // SET CHARACTER SET charset / SET CHARSET charset
        if (is(SqlTokenType.CHARACTER) || isIdent("CHARACTER") || isIdent("CHARSET")) {
            if (isIdent("CHARSET") || (identLike() && "CHARSET".equalsIgnoreCase(token.text()))) {
                next();
                stmt.setSetKind("CHARACTER SET");
            } else {
                next();
                if (is(SqlTokenType.SET) || isIdent("SET")
                        || (identLike() && "SET".equalsIgnoreCase(token.text()))) {
                    next();
                }
                stmt.setSetKind("CHARACTER SET");
            }
            SqlSetStatement.Assignment a = new SqlSetStatement.Assignment();
            a.setName(SqlIdentifier.of("CHARACTER SET"));
            a.setEqualsSign(false);
            if (!atStmtBreak()) {
                a.setValue(exprParser.parsePrimary());
            }
            stmt.assignments().add(a);
            if (!atStmtBreak()) {
                String rest = consumeRawUntilSemi();
                if (rest != null && !rest.isEmpty()) {
                    stmt.setRaw(rest);
                }
            }
            return stmt;
        }
        // SET a = 1 [, b = 2] …
        do {
            SqlSetStatement.Assignment a = new SqlSetStatement.Assignment();
            if (is(SqlTokenType.VARIABLE)) {
                a.setName(SqlIdentifier.of(token.text()));
                next();
            } else if (identLike() || (token.type() != null && token.type().keyword())) {
                a.setName(parseName());
            } else {
                break;
            }
            if (match(SqlTokenType.EQ) || match(SqlTokenType.ASSIGN)) {
                a.setEqualsSign(true);
                a.setValue(exprParser.parseExpr());
            } else {
                a.setEqualsSign(false);
            }
            stmt.assignments().add(a);
        } while (match(SqlTokenType.COMMA));
        if (!atStmtBreak() && stmt.raw() == null) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(rest);
            }
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

    /**
     * {@code SHOW …} / {@code SHOW CREATE TABLE|VIEW|DATABASE|…} → {@link SqlShowStatement}。
     */
    private SqlStatement parseShow() {
        expect(SqlTokenType.SHOW);
        SqlShowStatement stmt = new SqlShowStatement();
        if (match(SqlTokenType.CREATE)) {
            stmt.setShowKind("CREATE");
            if (is(SqlTokenType.TABLE) || is(SqlTokenType.VIEW) || is(SqlTokenType.DATABASE)
                    || is(SqlTokenType.INDEX) || is(SqlTokenType.PROCEDURE)
                    || is(SqlTokenType.FUNCTION) || is(SqlTokenType.TRIGGER)
                    || is(SqlTokenType.EVENT) || identLike()
                    || (token.type() != null && token.type().keyword())) {
                stmt.setObjectType(token.text().toUpperCase());
                next();
            }
            if (identLike()) {
                stmt.setName(parseName());
            }
        } else if (is(SqlTokenType.COLUMNS) || isIdent("FIELDS") || isIdent("INDEX")
                || is(SqlTokenType.INDEX) || is(SqlTokenType.TABLES) || is(SqlTokenType.DATABASES)
                || (identLike() && ("FIELDS".equalsIgnoreCase(token.text())
                || "STATUS".equalsIgnoreCase(token.text())
                || "VARIABLES".equalsIgnoreCase(token.text())
                || "PROCESSLIST".equalsIgnoreCase(token.text())
                || "WARNINGS".equalsIgnoreCase(token.text())
                || "ERRORS".equalsIgnoreCase(token.text())
                || "ENGINES".equalsIgnoreCase(token.text())
                || "COLLATION".equalsIgnoreCase(token.text())
                || "CHARSET".equalsIgnoreCase(token.text())
                || "CHARACTER".equalsIgnoreCase(token.text())
                || "GRANTS".equalsIgnoreCase(token.text())))) {
            stmt.setShowKind(token.text().toUpperCase());
            next();
            // SHOW CHARACTER SET
            if ("CHARACTER".equalsIgnoreCase(stmt.showKind())
                    && (isIdent("SET") || (identLike() && "SET".equalsIgnoreCase(token.text()))
                    || is(SqlTokenType.SET))) {
                next();
                stmt.setShowKind("CHARACTER SET");
            }
            if (is(SqlTokenType.FROM) || is(SqlTokenType.IN)) {
                stmt.setFromOrIn(token.text().toUpperCase());
                next();
                if (identLike()) {
                    stmt.setName(parseName());
                }
            }
        } else if (identLike() || (token.type() != null && token.type().keyword()
                && !is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF))) {
            // SHOW <other>
            stmt.setShowKind(token.text().toUpperCase());
            next();
            if (is(SqlTokenType.FROM) || is(SqlTokenType.IN)) {
                stmt.setFromOrIn(token.text().toUpperCase());
                next();
                if (identLike()) {
                    stmt.setName(parseName());
                }
            }
        }
        if (!is(SqlTokenType.SEMICOLON) && !is(SqlTokenType.EOF) && !atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(rest);
            }
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
                    stmt.arguments().add(exprParser.parseExpr());
                } while (match(SqlTokenType.COMMA));
            }
            expect(SqlTokenType.RPAREN);
        }
        return stmt;
    }

    private SqlStatement parseBeginBlock() {
        int start = token.start();
        expect(SqlTokenType.BEGIN);
        // BEGIN WORK / BEGIN TRANSACTION / 裸 BEGIN; → 事务，不吞后续批语句
        if (is(SqlTokenType.TRANSACTION) || isIdent("WORK") || isIdent("TRANSACTION")) {
            SqlStartTransactionStatement stmt = new SqlStartTransactionStatement();
            stmt.setBeginForm(true);
            if (isIdent("WORK") || (identLike() && "WORK".equalsIgnoreCase(token.text()))) {
                stmt.setWork(true);
            }
            next(); // WORK | TRANSACTION
            parseTransactionCharacteristics(stmt);
            return stmt;
        }
        if (atStmtBreak()) {
            SqlStartTransactionStatement stmt = new SqlStartTransactionStatement();
            stmt.setBeginForm(true);
            return stmt;
        }
        // 若紧跟事务特征（无 WORK/TRANSACTION 关键字），仍视为事务开启
        if (isTxCharacteristicStart()) {
            SqlStartTransactionStatement stmt = new SqlStartTransactionStatement();
            stmt.setBeginForm(true);
            parseTransactionCharacteristics(stmt);
            return stmt;
        }
        // 结构化 BEGIN … END
        try {
            return ddlParser.finishBlockBody(start, false, null, null);
        } catch (SqlParseException ex) {
            // fall through to raw
        }
        String body = lexer.rawSlice(start, token.start()).trim();
        if (body.isEmpty() || !body.toUpperCase().contains("END")) {
            // 尚未消费完：用配对吞掉
            String rest = trailingRawAllowingBeginEnd(1);
            body = rest.isEmpty() ? "BEGIN" : "BEGIN " + rest;
        }
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        stmt.setText(body);
        return stmt;
    }

    /**
     * {@code START TRANSACTION [characteristics…]} → {@link SqlStartTransactionStatement}。
     */
    private SqlStatement parseStartTransaction() {
        expect(SqlTokenType.START);
        SqlStartTransactionStatement stmt = new SqlStartTransactionStatement();
        stmt.setBeginForm(false);
        if (is(SqlTokenType.TRANSACTION) || isIdent("TRANSACTION")) {
            next();
        } else if (is(SqlTokenType.WITH) || isTxCharacteristicStart()) {
            // START 后直接跟特征（少见）或 WITH CONSISTENT SNAPSHOT
        } else if (!atStmtBreak()) {
            // 非事务 START（如不应到达的 START WITH）：回退全文
            String rest = consumeRawUntilSemi();
            stmt.setRaw(("START " + (rest == null ? "" : rest)).trim());
            return stmt;
        }
        parseTransactionCharacteristics(stmt);
        return stmt;
    }

    private boolean isTxCharacteristicStart() {
        if (is(SqlTokenType.WITH) || is(SqlTokenType.NOT) || is(SqlTokenType.ONLY)) {
            return true;
        }
        if (identLike() || (token.type() != null && token.type().keyword())) {
            String t = token.text();
            return "WITH".equalsIgnoreCase(t) || "READ".equalsIgnoreCase(t)
                    || "ISOLATION".equalsIgnoreCase(t) || "DEFERRABLE".equalsIgnoreCase(t)
                    || "NOT".equalsIgnoreCase(t);
        }
        return false;
    }

    /**
     * 解析事务特征：ISOLATION LEVEL / READ WRITE|ONLY / WITH CONSISTENT SNAPSHOT /
     * [NOT] DEFERRABLE；其余进 raw。
     */
    private void parseTransactionCharacteristics(SqlStartTransactionStatement stmt) {
        StringBuilder leftover = new StringBuilder();
        while (!atStmtBreak()) {
            if (matchCommaSep()) {
                continue;
            }
            if (is(SqlTokenType.WITH) || isIdent("WITH")
                    || (identLike() && "WITH".equalsIgnoreCase(token.text()))) {
                int wStart = token.start();
                next();
                if (isIdent("CONSISTENT") || (identLike() && "CONSISTENT".equalsIgnoreCase(token.text()))) {
                    next();
                    if (isIdent("SNAPSHOT") || (identLike() && "SNAPSHOT".equalsIgnoreCase(token.text()))) {
                        next();
                        stmt.setConsistentSnapshot(true);
                        continue;
                    }
                }
                // 非 CONSISTENT SNAPSHOT：并入 raw
                if (leftover.length() > 0) {
                    leftover.append(' ');
                }
                leftover.append(lexer.rawSlice(wStart, token.start()).trim());
                if (!atStmtBreak()) {
                    String rest = consumeRawUntilSemi();
                    if (rest != null && !rest.isEmpty()) {
                        leftover.append(' ').append(rest);
                    }
                }
                break;
            }
            if (isIdent("ISOLATION") || (identLike() && "ISOLATION".equalsIgnoreCase(token.text()))) {
                next();
                if (isIdent("LEVEL") || (identLike() && "LEVEL".equalsIgnoreCase(token.text()))
                        || (token.type() != null && token.type().keyword()
                        && "LEVEL".equalsIgnoreCase(token.text()))) {
                    next();
                }
                StringBuilder level = new StringBuilder();
                // READ UNCOMMITTED | READ COMMITTED | REPEATABLE READ | SERIALIZABLE
                int parts = 0;
                while (parts < 2 && !atStmtBreak() && !is(SqlTokenType.COMMA)
                        && (identLike() || (token.type() != null && token.type().keyword()))) {
                    String t = token.text().toUpperCase();
                    if ("READ".equals(t) || "UNCOMMITTED".equals(t) || "COMMITTED".equals(t)
                            || "REPEATABLE".equals(t) || "SERIALIZABLE".equals(t)) {
                        if (level.length() > 0) {
                            level.append(' ');
                        }
                        level.append(t);
                        next();
                        parts++;
                        if ("SERIALIZABLE".equals(t)) {
                            break;
                        }
                        continue;
                    }
                    break;
                }
                if (level.length() > 0) {
                    stmt.setIsolationLevel(level.toString());
                    continue;
                }
            }
            if (isIdent("READ") || (identLike() && "READ".equalsIgnoreCase(token.text()))
                    || (token.type() != null && token.type().keyword()
                    && "READ".equalsIgnoreCase(token.text()))) {
                next();
                if (is(SqlTokenType.ONLY) || isIdent("ONLY")
                        || (identLike() && "ONLY".equalsIgnoreCase(token.text()))) {
                    next();
                    stmt.setReadOnly(Boolean.TRUE);
                    continue;
                }
                if (isIdent("WRITE") || (identLike() && "WRITE".equalsIgnoreCase(token.text()))
                        || (token.type() != null && token.type().keyword()
                        && "WRITE".equalsIgnoreCase(token.text()))) {
                    next();
                    stmt.setReadOnly(Boolean.FALSE);
                    continue;
                }
                // READ 后非 WRITE/ONLY：残片
                if (leftover.length() > 0) {
                    leftover.append(' ');
                }
                leftover.append("READ");
                break;
            }
            if (isIdent("NOT") || (identLike() && "NOT".equalsIgnoreCase(token.text()))
                    || is(SqlTokenType.NOT)) {
                int nStart = token.start();
                next();
                if (isIdent("DEFERRABLE") || (identLike() && "DEFERRABLE".equalsIgnoreCase(token.text()))) {
                    next();
                    stmt.setDeferrable(Boolean.FALSE);
                    continue;
                }
                if (leftover.length() > 0) {
                    leftover.append(' ');
                }
                leftover.append(lexer.rawSlice(nStart, token.start()).trim());
                break;
            }
            if (isIdent("DEFERRABLE") || (identLike() && "DEFERRABLE".equalsIgnoreCase(token.text()))) {
                next();
                stmt.setDeferrable(Boolean.TRUE);
                continue;
            }
            // 未知：吞到分号
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if (leftover.length() > 0) {
                    leftover.append(' ');
                }
                leftover.append(rest);
            }
            break;
        }
        if (leftover.length() > 0) {
            stmt.setRaw(leftover.toString().trim());
        }
    }

    private boolean matchCommaSep() {
        if (is(SqlTokenType.COMMA)) {
            next();
            return true;
        }
        return false;
    }

    /**
     * {@code COMMIT}/{@code ROLLBACK [TO [SAVEPOINT] name]}/{@code SAVEPOINT}/{@code RELEASE}
     * → {@link SqlTransactionControlStatement}。
     */
    private SqlStatement parseTxControl() {
        String kind = token.text().toUpperCase();
        next();
        SqlTransactionControlStatement stmt = new SqlTransactionControlStatement();
        stmt.setKind(kind);
        StringBuilder leftover = new StringBuilder();
        if ("RELEASE".equals(kind)) {
            if (is(SqlTokenType.SAVEPOINT) || isIdent("SAVEPOINT")
                    || (identLike() && "SAVEPOINT".equalsIgnoreCase(token.text()))) {
                next();
            }
            if (identLike()) {
                stmt.setSavepoint(parseName());
            }
        } else if ("SAVEPOINT".equals(kind)) {
            if (identLike()) {
                stmt.setSavepoint(parseName());
            }
        } else {
            // COMMIT / ROLLBACK：可选 WORK
            if (isIdent("WORK") || (identLike() && "WORK".equalsIgnoreCase(token.text()))) {
                if (leftover.length() > 0) {
                    leftover.append(' ');
                }
                leftover.append("WORK");
                next();
            }
            if ("ROLLBACK".equals(kind)
                    && (is(SqlTokenType.TO) || isIdent("TO")
                    || (identLike() && "TO".equalsIgnoreCase(token.text())))) {
                next();
                stmt.setToSavepoint(true);
                if (is(SqlTokenType.SAVEPOINT) || isIdent("SAVEPOINT")
                        || (identLike() && "SAVEPOINT".equalsIgnoreCase(token.text()))) {
                    next();
                }
                if (identLike()) {
                    stmt.setSavepoint(parseName());
                }
            }
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if (leftover.length() > 0) {
                    leftover.append(' ');
                }
                leftover.append(rest);
            }
        }
        if (leftover.length() > 0) {
            stmt.setRaw(leftover.toString().trim());
        }
        return stmt;
    }

    /**
     * MySQL {@code FLUSH [LOCAL|NO_WRITE_TO_BINLOG] option[, …]} → {@link SqlFlushStatement}。
     */
    private SqlStatement parseFlush() {
        expect(SqlTokenType.FLUSH);
        SqlFlushStatement stmt = new SqlFlushStatement();
        if (is(SqlTokenType.LOCAL) || isIdent("LOCAL")
                || (identLike() && ("LOCAL".equalsIgnoreCase(token.text())
                || "NO_WRITE_TO_BINLOG".equalsIgnoreCase(token.text())))) {
            stmt.setNoWriteToBinlog(true);
            next();
        }
        if (atStmtBreak()) {
            return stmt;
        }
        do {
            if (is(SqlTokenType.COMMA)) {
                next();
                if (atStmtBreak()) {
                    break;
                }
            }
            if (!parseFlushOption(stmt)) {
                String rest = consumeRawUntilSemi();
                if (rest != null && !rest.isEmpty()) {
                    stmt.setRaw(rest);
                }
                break;
            }
        } while (!atStmtBreak() && is(SqlTokenType.COMMA));
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if (stmt.raw() != null && !stmt.raw().isEmpty()) {
                    stmt.setRaw(stmt.raw() + " " + rest);
                } else {
                    stmt.setRaw(rest);
                }
            }
        }
        return stmt;
    }

    /**
     * @return 是否成功解析一个 flush option
     */
    private boolean parseFlushOption(SqlFlushStatement stmt) {
        if (!(identLike() || (token.type() != null && token.type().keyword())
                || is(SqlTokenType.TABLES) || is(SqlTokenType.TABLE)
                || is(SqlTokenType.STATUS) || is(SqlTokenType.BINARY))) {
            return false;
        }
        String first = token.text().toUpperCase();
        // TABLES [tbl…] [WITH READ LOCK | FOR EXPORT]
        if (is(SqlTokenType.TABLES) || is(SqlTokenType.TABLE) || "TABLES".equals(first)
                || "TABLE".equals(first)) {
            next();
            stmt.options().add("TABLES");
            while (!atStmtBreak() && !is(SqlTokenType.COMMA) && !is(SqlTokenType.WITH)
                    && !isIdent("FOR") && !(identLike() && "FOR".equalsIgnoreCase(token.text()))
                    && identLike()) {
                // 表名（非修饰关键字）
                String peek = token.text().toUpperCase();
                if ("WITH".equals(peek) || "FOR".equals(peek) || "READ".equals(peek)
                        || "EXPORT".equals(peek) || "LOCK".equals(peek)) {
                    break;
                }
                stmt.tables().add(parseName());
                if (!match(SqlTokenType.COMMA)) {
                    break;
                }
            }
            if (is(SqlTokenType.WITH) || isIdent("WITH")
                    || (identLike() && "WITH".equalsIgnoreCase(token.text()))) {
                int mStart = token.start();
                next(); // WITH
                if (isIdent("READ") || (identLike() && "READ".equalsIgnoreCase(token.text()))) {
                    next();
                    if (isIdent("LOCK") || (identLike() && "LOCK".equalsIgnoreCase(token.text()))
                            || is(SqlTokenType.LOCK)) {
                        next();
                    }
                    stmt.setTablesModifier(lexer.rawSlice(mStart, token.start()).trim().toUpperCase()
                            .replaceAll("\\s+", " "));
                } else {
                    String rest = consumeRawUntilSemi();
                    stmt.setRaw(((stmt.raw() == null ? "" : stmt.raw() + " ")
                            + lexer.rawSlice(mStart, token.start()).trim()
                            + (rest == null || rest.isEmpty() ? "" : " " + rest)).trim());
                }
            } else if (isIdent("FOR") || (identLike() && "FOR".equalsIgnoreCase(token.text()))
                    || is(SqlTokenType.FOR)) {
                int mStart = token.start();
                next();
                if (isIdent("EXPORT") || (identLike() && "EXPORT".equalsIgnoreCase(token.text()))) {
                    next();
                    stmt.setTablesModifier("FOR EXPORT");
                } else {
                    String rest = consumeRawUntilSemi();
                    stmt.setRaw(((stmt.raw() == null ? "" : stmt.raw() + " ")
                            + lexer.rawSlice(mStart, token.start()).trim()
                            + (rest == null || rest.isEmpty() ? "" : " " + rest)).trim());
                }
            }
            return true;
        }
        // 双词：BINARY LOGS / ENGINE LOGS / ERROR LOGS / GENERAL LOGS / RELAY LOGS / SLOW LOGS
        next();
        String opt = first;
        if (!atStmtBreak() && !is(SqlTokenType.COMMA)
                && (identLike() || (token.type() != null && token.type().keyword()))) {
            String second = token.text().toUpperCase();
            if ("LOGS".equals(second) || "LOG".equals(second)
                    || ("LOGS".equals(second) || "COSTS".equals(second)
                    || "RESOURCES".equals(second))) {
                // BINARY LOGS / OPTIMIZER_COSTS already single / USER_RESOURCES
                if ("LOGS".equals(second) || "LOG".equals(second)) {
                    opt = first + " LOGS";
                    next();
                } else if ("COSTS".equals(second) || "RESOURCES".equals(second)) {
                    opt = first + " " + second;
                    next();
                }
            }
        }
        // RELAY LOGS FOR CHANNEL …
        if (opt.endsWith("LOGS") && (is(SqlTokenType.FOR) || isIdent("FOR")
                || (identLike() && "FOR".equalsIgnoreCase(token.text())))) {
            int cStart = token.start();
            next();
            while (!atStmtBreak() && !is(SqlTokenType.COMMA)) {
                next();
            }
            String channel = lexer.rawSlice(cStart, token.start()).trim();
            opt = opt + " " + channel;
        }
        stmt.options().add(opt);
        return true;
    }

    /**
     * MySQL {@code LOCK TABLES t READ, u WRITE, …} → {@link SqlLockTablesStatement}。
     */
    private SqlStatement parseLockTables() {
        int start = token.start();
        expect(SqlTokenType.LOCK);
        SqlLockTablesStatement stmt = new SqlLockTablesStatement();
        if (is(SqlTokenType.TABLES) || is(SqlTokenType.TABLE) || isIdent("TABLES") || isIdent("TABLE")) {
            next();
        }
        if (!atStmtBreak()) {
            do {
                SqlLockTablesStatement.LockItem item = new SqlLockTablesStatement.LockItem();
                if (identLike()) {
                    item.setTable(parseName());
                }
                if (is(SqlTokenType.AS)) {
                    next();
                    if (identLike() || (token.type() != null && token.type().keyword())) {
                        item.setAlias(parseAliasBare());
                    }
                } else if (identLike() && !isLockModeStart()) {
                    item.setAlias(parseAliasBare());
                }
                item.setLockMode(parseLockMode());
                stmt.items().add(item);
            } while (match(SqlTokenType.COMMA));
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(lexer.rawSlice(start, token.start()).trim());
            }
        }
        return stmt;
    }

    /**
     * MySQL {@code UNLOCK TABLES} → {@link SqlLockTablesStatement}（{@link SqlLockTablesStatement#unlock()}）。
     */
    private SqlStatement parseUnlockTables() {
        expect(SqlTokenType.UNLOCK);
        SqlLockTablesStatement stmt = new SqlLockTablesStatement();
        stmt.setUnlock(true);
        if (is(SqlTokenType.TABLES) || is(SqlTokenType.TABLE) || isIdent("TABLES") || isIdent("TABLE")) {
            next();
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(("UNLOCK TABLES " + rest).trim());
            }
        }
        return stmt;
    }

    private boolean isLockModeStart() {
        if (is(SqlTokenType.LOW_PRIORITY)) {
            return true;
        }
        if (!identLike() && !(token.type() != null && token.type().keyword())) {
            return false;
        }
        String t = token.text();
        return "READ".equalsIgnoreCase(t) || "WRITE".equalsIgnoreCase(t)
                || "LOW_PRIORITY".equalsIgnoreCase(t);
    }

    private String parseLockMode() {
        StringBuilder mode = new StringBuilder();
        if (is(SqlTokenType.LOW_PRIORITY) || (identLike() && "LOW_PRIORITY".equalsIgnoreCase(token.text()))) {
            mode.append("LOW_PRIORITY");
            next();
        }
        if (identLike() || (token.type() != null && token.type().keyword())) {
            String t = token.text();
            if ("READ".equalsIgnoreCase(t) || "WRITE".equalsIgnoreCase(t)) {
                if (mode.length() > 0) {
                    mode.append(' ');
                }
                mode.append(t.toUpperCase());
                next();
                if (mode.toString().endsWith("READ")
                        && (is(SqlTokenType.LOCAL) || isIdent("LOCAL")
                        || (identLike() && "LOCAL".equalsIgnoreCase(token.text())))) {
                    mode.append(" LOCAL");
                    next();
                }
            }
        }
        return mode.length() == 0 ? null : mode.toString();
    }

    /** 锁表别名：不消费 AS（调用方已处理）。 */
    private String parseAliasBare() {
        if (is(SqlTokenType.STRING)) {
            return unquote(consumeStringRaw());
        }
        if (identLike() || (token.type() != null && token.type().keyword())) {
            return parseName().qualifiedName();
        }
        return unquote(consumeIdentRaw());
    }

    /**
     * MySQL 客户端 {@code DELIMITER ;;} / {@code DELIMITER ;} / {@code DELIMITER $}：OTHER 占位，
     * 并切换 {@link #stmtDelimiter}，使后续 {@link #parseAll} / 过程体尾部按新终止符切分。
     */
    private SqlStatement parseDelimiter() {
        // 调用方已确认当前为 IDENT DELIMITER
        next();
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        String delim = extractAndConsumeDelimiterArg();
        if (delim == null || delim.isEmpty()) {
            delim = ";";
        }
        this.stmtDelimiter = delim;
        if (";".equals(delim)) {
            stmt.setText("DELIMITER");
        } else {
            stmt.setText("DELIMITER " + delim);
        }
        return stmt;
    }

    /**
     * 读取 {@code DELIMITER} 后第一个非空白连续串作为新终止符，并推进词法游标越过该串。
     */
    private String extractAndConsumeDelimiterArg() {
        if (is(SqlTokenType.EOF)) {
            return ";";
        }
        int start = token.start();
        String chunk = lexer.rawSlice(start, start + 64);
        int len = 0;
        while (len < chunk.length()) {
            char c = chunk.charAt(len);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                break;
            }
            len++;
        }
        if (len == 0) {
            return ";";
        }
        String delim = chunk.substring(0, len);
        int end = start + len;
        while (!is(SqlTokenType.EOF) && token.start() < end) {
            next();
        }
        return delim;
    }

    private SqlStatement parseDeclare() {
        int start = token.start();
        expect(SqlTokenType.DECLARE);
        // 匿名块 DECLARE [decls] BEGIN … END
        if (is(SqlTokenType.BEGIN) || declareRemainderHasBeginBlock()) {
            return parseAnonymousDeclareBlock(start);
        }
        // 会话式 / 单行 DECLARE x INT …
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        if (identLike() && !is(SqlTokenType.BEGIN)) {
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

    /**
     * 在未消费的前提下，判断 DECLARE 后是否为匿名块（出现 BEGIN…END，且 BEGIN 前无语句终止分号）。
     */
    private boolean declareRemainderHasBeginBlock() {
        String rest = lexer.rawSlice(token.start(), token.start() + 1_000_000);
        if (rest.isEmpty()) {
            return false;
        }
        int depth = 0;
        int i = 0;
        int n = rest.length();
        while (i < n) {
            char c = rest.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    char d = rest.charAt(i++);
                    if (d == q) {
                        break;
                    }
                    if (d == '\\' && i < n) {
                        i++;
                    }
                }
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (depth == 0 && c == ';') {
                return false;
            }
            if (depth == 0 && isWordAt(rest, i, "BEGIN")) {
                return true;
            }
            i++;
        }
        return false;
    }

    private static boolean isWordAt(String s, int i, String word) {
        int n = word.length();
        if (i + n > s.length()) {
            return false;
        }
        if (i > 0) {
            char p = s.charAt(i - 1);
            if (Character.isLetterOrDigit(p) || p == '_') {
                return false;
            }
        }
        for (int k = 0; k < n; k++) {
            if (Character.toUpperCase(s.charAt(i + k)) != word.charAt(k)) {
                return false;
            }
        }
        if (i + n < s.length()) {
            char e = s.charAt(i + n);
            if (Character.isLetterOrDigit(e) || e == '_') {
                return false;
            }
        }
        return true;
    }

    private SqlStatement parseAnonymousDeclareBlock(int start) {
        java.util.List<SqlStatement> declares = new java.util.ArrayList<SqlStatement>(2);
        String declareRaw = null;
        if (!is(SqlTokenType.BEGIN)) {
            int declStart = token.start();
            // DECLARE 与 BEGIN 之间：尽力抽一条变量声明，其余进 declareRaw
            try {
                if (identLike()) {
                    com.alianga.jkit.sql.ast.SqlDeclareStatement one =
                            new com.alianga.jkit.sql.ast.SqlDeclareStatement();
                    one.setKind(com.alianga.jkit.sql.ast.SqlDeclareStatement.Kind.VARIABLE);
                    one.names().add(parseName());
                    while (match(SqlTokenType.COMMA)) {
                        one.names().add(parseName());
                    }
                    int typeStart = token.start();
                    while (!is(SqlTokenType.EOF) && !is(SqlTokenType.BEGIN)
                            && !is(SqlTokenType.DEFAULT) && !is(SqlTokenType.SEMICOLON)) {
                        if (is(SqlTokenType.LPAREN)) {
                            next();
                            skipBalancedParensContent();
                        } else {
                            next();
                        }
                    }
                    String typeRaw = lexer.rawSlice(typeStart, token.start()).trim();
                    if (!typeRaw.isEmpty()) {
                        one.setTypeRaw(typeRaw);
                    }
                    if (is(SqlTokenType.DEFAULT)) {
                        next();
                        one.setDefaultValue(exprParser.parseExpr());
                    }
                    one.setRaw(lexer.rawSlice(declStart, token.start()).trim());
                    declares.add(one);
                }
            } catch (SqlParseException ignored) {
                // keep raw
            }
            if (!is(SqlTokenType.BEGIN)) {
                int rawStart = declares.isEmpty() ? declStart : token.start();
                while (!is(SqlTokenType.EOF) && !is(SqlTokenType.BEGIN)) {
                    next();
                }
                declareRaw = lexer.rawSlice(declStart, token.start()).trim();
            } else if (!declares.isEmpty()) {
                declareRaw = lexer.rawSlice(declStart, token.start()).trim();
            }
        }
        if (!is(SqlTokenType.BEGIN)) {
            // 回退：整段原文
            String rest = consumeRawAllowingBeginEnd();
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setStatementType(SqlStatementType.OTHER);
            stmt.setText(("DECLARE " + rest).trim());
            return stmt;
        }
        next(); // BEGIN
        try {
            return ddlParser.finishBlockBody(start, true, declareRaw, declares);
        } catch (SqlParseException ex) {
            String body = trailingRawAllowingBeginEnd(1);
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setStatementType(SqlStatementType.OTHER);
            StringBuilder sb = new StringBuilder("DECLARE");
            if (declareRaw != null && !declareRaw.isEmpty()) {
                sb.append(' ').append(declareRaw);
            }
            sb.append(" BEGIN");
            if (!body.isEmpty()) {
                sb.append(' ').append(body);
            }
            stmt.setText(sb.toString());
            return stmt;
        }
    }

    /**
     * {@code ANALYZE}/{@code VACUUM}/{@code OPTIMIZE|REPAIR|CHECK TABLE}
     * → {@link SqlMaintenanceStatement}。
     */
    private SqlStatement parseMaintenance() {
        String kind = token.text().toUpperCase();
        next();
        SqlMaintenanceStatement stmt = new SqlMaintenanceStatement();
        stmt.setKind(kind);
        StringBuilder options = new StringBuilder();
        // VACUUM [FULL] [ANALYZE] / ANALYZE [TABLE] / OPTIMIZE|REPAIR|CHECK TABLE
        while (is(SqlTokenType.ANALYZE) || is(SqlTokenType.TABLE) || is(SqlTokenType.FULL)
                || is(SqlTokenType.LOCAL) || isIdent("FREEZE") || isIdent("VERBOSE")
                || isIdent("NO_WRITE_TO_BINLOG")
                || (identLike() && ("FREEZE".equalsIgnoreCase(token.text())
                || "VERBOSE".equalsIgnoreCase(token.text())
                || "NO_WRITE_TO_BINLOG".equalsIgnoreCase(token.text())))) {
            if (options.length() > 0) {
                options.append(' ');
            }
            options.append(token.text().toUpperCase());
            next();
        }
        if (options.length() > 0) {
            stmt.setOptionsRaw(options.toString());
        }
        if (identLike()) {
            stmt.tables().add(parseName());
            while (match(SqlTokenType.COMMA)) {
                if (identLike()) {
                    stmt.tables().add(parseName());
                } else {
                    break;
                }
            }
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(rest);
            }
        }
        return stmt;
    }

    /**
     * PostgreSQL {@code COPY … FROM|TO …} → {@link SqlCopyStatement}。
     */
    private SqlStatement parseCopy() {
        int start = token.start();
        expect(SqlTokenType.COPY);
        SqlCopyStatement stmt = new SqlCopyStatement();
        // COPY (query) TO …
        if (is(SqlTokenType.LPAREN)) {
            int qStart = token.start();
            next();
            skipBalancedParensContent();
            if (is(SqlTokenType.RPAREN)) {
                next();
            }
            // slice includes outer parens content; store inner-ish raw
            stmt.setQuery(lexer.rawSlice(qStart, token.start()).trim());
        } else if (identLike()) {
            stmt.setTable(parseName());
            if (is(SqlTokenType.LPAREN)) {
                next();
                if (!is(SqlTokenType.RPAREN)) {
                    do {
                        if (identLike() || (token.type() != null && token.type().keyword())) {
                            stmt.columns().add(parseName());
                        } else {
                            break;
                        }
                    } while (match(SqlTokenType.COMMA));
                }
                if (is(SqlTokenType.RPAREN)) {
                    next();
                }
            }
        }
        if (is(SqlTokenType.FROM) || isIdent("FROM")
                || (identLike() && "FROM".equalsIgnoreCase(token.text()))) {
            next();
            stmt.setTo(false);
            parseCopySource(stmt);
        } else if (is(SqlTokenType.TO) || isIdent("TO")
                || (identLike() && "TO".equalsIgnoreCase(token.text()))) {
            next();
            stmt.setTo(true);
            parseCopySource(stmt);
        }
        // WITH ( … ) 或旧式 WITH option …
        if (is(SqlTokenType.WITH) || isIdent("WITH")
                || (identLike() && "WITH".equalsIgnoreCase(token.text()))) {
            int wStart = token.start();
            next();
            if (is(SqlTokenType.LPAREN)) {
                next();
                skipBalancedParensContent();
                if (is(SqlTokenType.RPAREN)) {
                    next();
                }
            } else {
                while (!atStmtBreak()) {
                    next();
                }
            }
            stmt.setWithClause(lexer.rawSlice(wStart, token.start()).trim());
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if (stmt.withClause() != null) {
                    stmt.setWithClause(stmt.withClause() + " " + rest);
                } else {
                    stmt.setRaw(rest);
                }
            }
        }
        if (stmt.table() == null && stmt.query() == null && stmt.source() == null
                && stmt.sourceKind() == null) {
            stmt.setRaw(lexer.rawSlice(start, token.start()).trim());
        }
        return stmt;
    }

    private void parseCopySource(SqlCopyStatement stmt) {
        if (isIdent("STDIN") || (identLike() && "STDIN".equalsIgnoreCase(token.text()))
                || (token.type() != null && token.type().keyword()
                && "STDIN".equalsIgnoreCase(token.text()))) {
            stmt.setSourceKind("STDIN");
            next();
            return;
        }
        if (isIdent("STDOUT") || (identLike() && "STDOUT".equalsIgnoreCase(token.text()))
                || (token.type() != null && token.type().keyword()
                && "STDOUT".equalsIgnoreCase(token.text()))) {
            stmt.setSourceKind("STDOUT");
            next();
            return;
        }
        if (isIdent("PROGRAM") || (identLike() && "PROGRAM".equalsIgnoreCase(token.text()))
                || (token.type() != null && token.type().keyword()
                && "PROGRAM".equalsIgnoreCase(token.text()))) {
            stmt.setSourceKind("PROGRAM");
            next();
            if (!atStmtBreak() && !is(SqlTokenType.WITH)) {
                stmt.setSource(exprParser.parsePrimary());
            }
            return;
        }
        if (!atStmtBreak() && !is(SqlTokenType.WITH)) {
            stmt.setSourceKind("FILE");
            stmt.setSource(exprParser.parsePrimary());
        }
    }

    /**
     * MySQL {@code LOAD DATA [LOCAL] INFILE … INTO TABLE …} → {@link SqlLoadDataStatement}。
     */
    private SqlStatement parseLoad() {
        int start = token.start();
        expect(SqlTokenType.LOAD);
        SqlLoadDataStatement stmt = new SqlLoadDataStatement();
        if (!isIdent("DATA") && !(identLike() && "DATA".equalsIgnoreCase(token.text()))) {
            // 非 LOAD DATA：回退全文
            String rest = consumeRawUntilSemi();
            stmt.setRaw(("LOAD " + (rest == null ? "" : rest)).trim());
            return stmt;
        }
        next(); // DATA
        if (is(SqlTokenType.LOW_PRIORITY)
                || (identLike() && ("LOW_PRIORITY".equalsIgnoreCase(token.text())
                || "CONCURRENT".equalsIgnoreCase(token.text())))) {
            stmt.setPriority(token.text().toUpperCase());
            next();
        }
        if (is(SqlTokenType.LOCAL) || isIdent("LOCAL")
                || (identLike() && "LOCAL".equalsIgnoreCase(token.text()))) {
            stmt.setLocal(true);
            next();
        }
        if (isIdent("INFILE") || (identLike() && "INFILE".equalsIgnoreCase(token.text()))) {
            next();
        }
        if (!atStmtBreak() && !is(SqlTokenType.INTO) && !is(SqlTokenType.REPLACE)
                && !is(SqlTokenType.IGNORE)) {
            stmt.setFileName(exprParser.parsePrimary());
        }
        if (is(SqlTokenType.REPLACE) || is(SqlTokenType.IGNORE)
                || (identLike() && ("REPLACE".equalsIgnoreCase(token.text())
                || "IGNORE".equalsIgnoreCase(token.text())))) {
            stmt.setDuplicateMode(token.text().toUpperCase());
            next();
        }
        if (is(SqlTokenType.INTO)) {
            next();
            if (is(SqlTokenType.TABLE)) {
                next();
            }
            if (identLike()) {
                stmt.setTable(parseName());
            }
        }
        if (is(SqlTokenType.PARTITION) || isIdent("PARTITION")) {
            int pStart = token.start();
            next();
            if (is(SqlTokenType.LPAREN)) {
                next();
                skipBalancedParensContent();
            }
            // 并入 tail 前缀
            String part = lexer.rawSlice(pStart, token.start()).trim();
            stmt.setTail(part);
        }
        if (is(SqlTokenType.CHARACTER) || is(SqlTokenType.CHARSET)
                || isIdent("CHARACTER") || isIdent("CHARSET")) {
            if (is(SqlTokenType.CHARACTER) || isIdent("CHARACTER")) {
                next();
                if (is(SqlTokenType.SET) || isIdent("SET")) {
                    next();
                }
            } else {
                next(); // CHARSET
            }
            if (identLike() || (token.type() != null && token.type().keyword())
                    || is(SqlTokenType.STRING)) {
                if (is(SqlTokenType.STRING)) {
                    stmt.setCharacterSet(unquote(consumeStringRaw()));
                } else {
                    stmt.setCharacterSet(consumeIdentRaw());
                }
            }
        }
        if (is(SqlTokenType.COLUMNS) || isIdent("FIELDS") || isIdent("COLUMNS")
                || (identLike() && ("FIELDS".equalsIgnoreCase(token.text())
                || "COLUMNS".equalsIgnoreCase(token.text())))) {
            int fStart = token.start();
            next();
            while (!atStmtBreak() && !isLoadLinesStart() && !isLoadIgnoreRowsStart()
                    && !is(SqlTokenType.SET) && !is(SqlTokenType.LPAREN)) {
                next();
            }
            stmt.setFieldsClause(lexer.rawSlice(fStart, token.start()).trim());
        }
        if (isLoadLinesStart()) {
            int lStart = token.start();
            next();
            while (!atStmtBreak() && !isLoadIgnoreRowsStart()
                    && !is(SqlTokenType.SET) && !is(SqlTokenType.LPAREN)) {
                next();
            }
            stmt.setLinesClause(lexer.rawSlice(lStart, token.start()).trim());
        }
        if (isLoadIgnoreRowsStart()) {
            int iStart = token.start();
            next(); // IGNORE
            if (is(SqlTokenType.NUMBER)) {
                next();
            }
            if (isIdent("LINES") || isIdent("ROWS")
                    || (identLike() && ("LINES".equalsIgnoreCase(token.text())
                    || "ROWS".equalsIgnoreCase(token.text())))) {
                next();
            }
            stmt.setIgnoreClause(lexer.rawSlice(iStart, token.start()).trim());
        }
        if (is(SqlTokenType.LPAREN)) {
            next();
            if (!is(SqlTokenType.RPAREN)) {
                do {
                    if (is(SqlTokenType.VARIABLE)) {
                        stmt.columns().add(SqlIdentifier.of(token.text()));
                        next();
                    } else if (identLike() || (token.type() != null && token.type().keyword())) {
                        stmt.columns().add(parseName());
                    } else {
                        break;
                    }
                } while (match(SqlTokenType.COMMA));
            }
            if (is(SqlTokenType.RPAREN)) {
                next();
            }
        }
        if (is(SqlTokenType.SET) || !atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                if (stmt.tail() != null && !stmt.tail().isEmpty()) {
                    stmt.setTail(stmt.tail() + " " + rest);
                } else {
                    stmt.setTail(rest);
                }
            }
        }
        if (stmt.table() == null && stmt.fileName() == null) {
            stmt.setRaw(lexer.rawSlice(start, token.start()).trim());
        }
        return stmt;
    }

    private boolean isLoadLinesStart() {
        return isIdent("LINES") || (identLike() && "LINES".equalsIgnoreCase(token.text()));
    }

    private boolean isLoadIgnoreRowsStart() {
        return is(SqlTokenType.IGNORE) || isIdent("IGNORE")
                || (identLike() && "IGNORE".equalsIgnoreCase(token.text()));
    }

    private SqlStatement parseHandler() {
        int start = token.start();
        expect(SqlTokenType.HANDLER);
        SqlTableHandlerStatement h = new SqlTableHandlerStatement();
        if (identLike()) {
            h.setTable(parseName());
        }
        if (isIdent("OPEN") || isIdent("READ") || isIdent("CLOSE")
                || (identLike() && ("OPEN".equalsIgnoreCase(token.text())
                || "READ".equalsIgnoreCase(token.text())
                || "CLOSE".equalsIgnoreCase(token.text())))) {
            h.setOperation(token.text().toUpperCase());
            next();
            if ("OPEN".equals(h.operation())) {
                if (is(SqlTokenType.AS)) {
                    next();
                }
                if (identLike()) {
                    h.setAlias(parseAlias());
                }
            } else if ("READ".equals(h.operation())) {
                parseTableHandlerRead(h);
            }
            // CLOSE：无附加子句
            if (is(SqlTokenType.WHERE)) {
                next();
                h.setWhere(exprParser.parseExpr());
            }
            if (is(SqlTokenType.LIMIT)) {
                h.setLimit(selectParser.parseLimit());
            }
        }
        if (!atStmtBreak()) {
            // 未识别残段并入 raw
            consumeRawUntilSemi();
        }
        h.setRaw(lexer.rawSlice(start, token.start()).trim());
        // 无表名时退回简单 OTHER，保持可解析
        if (h.table() == null && (h.operation() == null || h.raw() == null)) {
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setStatementType(SqlStatementType.OTHER);
            stmt.setText(h.raw() == null ? "HANDLER" : h.raw());
            return stmt;
        }
        return h;
    }

    private void parseTableHandlerRead(SqlTableHandlerStatement h) {
        if (isHandlerReadDirection()) {
            h.setReadDirection(token.text().toUpperCase());
            next();
            return;
        }
        if (identLike()) {
            // index_name { FIRST|NEXT|PREV|LAST | compare (values) }
            h.setIndexName(parseName());
            if (isHandlerReadDirection()) {
                h.setReadDirection(token.text().toUpperCase());
                next();
                return;
            }
            if (is(SqlTokenType.EQ) || is(SqlTokenType.LT) || is(SqlTokenType.GT)
                    || is(SqlTokenType.LE) || is(SqlTokenType.GE)
                    || is(SqlTokenType.NE) || is(SqlTokenType.NULL_SAFE_EQ)) {
                int keyStart = token.start();
                next();
                if (is(SqlTokenType.LPAREN)) {
                    next();
                    skipBalancedParensContent();
                } else if (!atStmtBreak() && !is(SqlTokenType.WHERE) && !is(SqlTokenType.LIMIT)) {
                    // 单值
                    next();
                }
                h.setKeyRaw(lexer.rawSlice(keyStart, token.start()).trim());
            }
        }
    }

    private boolean isHandlerReadDirection() {
        if (is(SqlTokenType.FIRST) || is(SqlTokenType.NEXT)) {
            return true;
        }
        if (!identLike()) {
            return false;
        }
        String t = token.text();
        return "PREV".equalsIgnoreCase(t) || "LAST".equalsIgnoreCase(t)
                || "FIRST".equalsIgnoreCase(t) || "NEXT".equalsIgnoreCase(t);
    }

    private SqlStatement parsePrepareFamily() {
        int start = token.start();
        String lead = token.text().toUpperCase();
        next();
        SqlPrepareStatement stmt = new SqlPrepareStatement();
        if ("DEALLOCATE".equals(lead)) {
            stmt.setKind(SqlPrepareStatement.Kind.DEALLOCATE);
            if (is(SqlTokenType.PREPARE)) {
                next();
            }
            if (identLike()) {
                stmt.setName(parseName());
            }
        } else if ("EXECUTE".equals(lead)) {
            if (isIdent("IMMEDIATE")
                    || (identLike() && "IMMEDIATE".equalsIgnoreCase(token.text()))) {
                next();
                stmt.setKind(SqlPrepareStatement.Kind.EXECUTE_IMMEDIATE);
                if (!atStmtBreak() && !is(SqlTokenType.USING)) {
                    stmt.setSource(exprParser.parsePrimary());
                }
            } else {
                stmt.setKind(SqlPrepareStatement.Kind.EXECUTE);
                if (identLike()) {
                    stmt.setName(parseName());
                }
            }
            if (is(SqlTokenType.USING)) {
                next();
                do {
                    stmt.usingBinds().add(exprParser.parsePrimary());
                } while (match(SqlTokenType.COMMA));
            }
        } else {
            // PREPARE name FROM expr（偶见 AS）
            stmt.setKind(SqlPrepareStatement.Kind.PREPARE);
            if (identLike()) {
                stmt.setName(parseName());
            }
            if (is(SqlTokenType.FROM) || is(SqlTokenType.AS)) {
                next();
                if (!atStmtBreak()) {
                    stmt.setSource(exprParser.parsePrimary());
                }
            }
        }
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (rest != null && !rest.isEmpty()) {
                stmt.setRaw(lexer.rawSlice(start, token.start()).trim());
            }
        }
        return stmt;
    }

    private void attachPendingComments(SqlStatement stmt) {
        if (!keepComments || pendingComments == null || pendingComments.isEmpty() || stmt == null) {
            return;
        }
        if (stmt.comments().isEmpty()) {
            stmt.setComments(pendingComments);
        } else {
            for (int i = 0; i < pendingComments.size(); i++) {
                stmt.addComment(pendingComments.get(i));
            }
        }
        pendingComments = null;
    }

    String parseAlias() {
        if (match(SqlTokenType.AS)) {
            if (is(SqlTokenType.STRING)) {
                return unquote(consumeStringRaw());
            }
            // 允许 AS schema.name 这类点号别名（Spider 语料常见）
            if (identLike() || (token.type() != null && token.type().keyword())) {
                return parseName().qualifiedName();
            }
            return unquote(consumeIdentRaw());
        }
        if (is(SqlTokenType.STRING)) {
            // MySQL：SELECT id "别名" / SELECT 1 'x' —— 双引号在 MYSQL 方言下是字符串记号
            return unquote(consumeStringRaw());
        }
        if (identLike() && !isAliasStop(token.type())
                && !isIdent("TABLESAMPLE") && !isIdent("SAMPLE")
                && !isIdent("OPTION")
                && !isIdent("MODEL") && !isIdent("MATCH_RECOGNIZE")
                && !isIdent("QUALIFY") && !isIdent("VERSIONS")
                && !is(SqlTokenType.FORCE) && !is(SqlTokenType.USE)
                && !is(SqlTokenType.IGNORE) && !is(SqlTokenType.PARTITION)) {
            // 无 AS 时也允许多段限定别名
            if (lexer.peek().type() == SqlTokenType.DOT) {
                return parseName().qualifiedName();
            }
            return unquote(consumeIdentRaw());
        }
        return null;
    }

    static boolean isAliasStop(SqlTokenType t) {
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
            case PARTITION:
            case APPLY:
            case OUTPUT:
            case WHEN:
            case MATCHED:
            case WITH:
            case PIVOT:
            case UNPIVOT:
            case SELECT:
            case INSERT:
            case UPDATE:
            case DELETE:
            case MERGE:
            case CREATE:
            case DROP:
            case ALTER:
            case REPLACE:
            case CALL:
            case GRANT:
            case REVOKE:
            case VALUES:
            case VALUE:
            case DEFAULT:
                return true;
            default:
                return false;
        }
    }

    static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    SqlIdentifier parseName() {
        SqlIdentifier id = new SqlIdentifier();
        String raw = consumeIdentPartRaw();
        if (isQuoted(raw)) {
            id.setQuoted(true);
        }
        id.addName(unquote(raw));
        while (true) {
            if (is(SqlTokenType.DOT)) {
                if (lexer.peek().type() == SqlTokenType.STAR) {
                    break;
                }
                next();
                // SQL Server：catalog..table（中间空 schema）
                if (is(SqlTokenType.DOT)) {
                    id.addName("");
                    continue;
                }
                String part = consumeIdentPartRaw();
                if (isQuoted(part)) {
                    id.setQuoted(true);
                }
                id.addName(unquote(part));
            } else if (is(SqlTokenType.NAMED_BIND) && lexer.lookahead(0) != null
                    && lexer.lookahead(0).type() == SqlTokenType.DOT) {
                // Informix：db:schema.table（:schema 被词法成 NAMED_BIND；须后接 . 以免吞掉三元 :）
                String part = token.text();
                if (part != null && part.startsWith(":")) {
                    part = part.substring(1);
                }
                id.addName(part);
                next();
            } else if (is(SqlTokenType.COLON) && lexer.peek() != null
                    && (identLikePeek(lexer.peek()))
                    && lexer.lookahead(1) != null
                    && lexer.lookahead(1).type() == SqlTokenType.DOT) {
                next();
                String part = consumeIdentPartRaw();
                if (isQuoted(part)) {
                    id.setQuoted(true);
                }
                id.addName(unquote(part));
            } else {
                break;
            }
        }
        if (is(SqlTokenType.VARIABLE) && token.text() != null && token.text().startsWith("@")) {
            // Oracle DB Link：fn@dblink / t@dblink
            id.setDblink(token.text().substring(1));
            next();
        }
        return id;
    }

    /**
     * 标识符或点号后的单引号名（MySQL 语料常见 {@code T.'Group'}；字符串记号当引用标识符）。
     */
    String consumeIdentPartRaw() {
        if (is(SqlTokenType.STRING)) {
            return consumeStringRaw();
        }
        return consumeIdentRaw();
    }

    boolean isQueryStart() {
        return is(SqlTokenType.SELECT) || is(SqlTokenType.WITH) || is(SqlTokenType.VALUES);
    }

    boolean identLike() {
        return is(SqlTokenType.IDENT) || isWordOperatorIdent()
                || (token.type() != null && token.type().keyword()
                && !isAliasStop(token.type()));
    }

    private static boolean identLikePeek(SqlToken tok) {
        if (tok == null || tok.type() == null) {
            return false;
        }
        return tok.type() == SqlTokenType.IDENT
                || (tok.type().keyword() && !isAliasStop(tok.type()));
    }

    /**
     * {@code PERCENT} 关键字与 {@code %} 运算符共用 {@link SqlTokenType#PERCENT}，
     * 后者 {@code keyword()==false}；词形式（长度大于 1）在标识符位置应可作列名。
     */
    boolean isWordOperatorIdent() {
        if (token == null || token.type() == null) {
            return false;
        }
        if (token.type() != SqlTokenType.PERCENT) {
            return false;
        }
        String t = token.text();
        return t != null && t.length() > 1;
    }

    /**
     * 运算符记号（{@code -} / {@code %} / {@code +}）与同名关键字（{@code MINUS} / {@code PERCENT}）共用 type；
     * 算术上下文只认符号形式（原文长度 1）。
     */
    boolean isSymbolOp(SqlTokenType type) {
        return is(type) && token.length() <= 1;
    }

    boolean isIdent(String word) {
        return is(SqlTokenType.IDENT) && token.textEqualsIgnoreCase(word);
    }

    String consumeIdentRaw() {
        if (!(is(SqlTokenType.IDENT) || isWordOperatorIdent() || token.type().keyword())) {
            throw error("expected identifier");
        }
        String raw = token.text();
        next();
        return raw;
    }

    String consumeStringRaw() {
        if (!is(SqlTokenType.STRING)) {
            throw error("expected string");
        }
        String raw = token.text();
        next();
        return raw;
    }

    boolean isStmtSeparator() {
        if (is(SqlTokenType.GO)) {
            return true;
        }
        if (is(SqlTokenType.EOF)) {
            return false;
        }
        String d = stmtDelimiter;
        if (d == null || d.isEmpty() || ";".equals(d)) {
            return is(SqlTokenType.SEMICOLON);
        }
        int start = token.start();
        int end = start + d.length();
        return d.equals(lexer.rawSlice(start, end));
    }

    /**
     * 消费当前语句终止符（含自定义多字符定界符如 {@code ;;} / {@code //}）。
     */
    private void consumeStmtSeparator() {
        if (is(SqlTokenType.GO)) {
            next();
            return;
        }
        String d = stmtDelimiter;
        if (d == null || d.isEmpty() || ";".equals(d)) {
            next();
            return;
        }
        int end = token.start() + d.length();
        while (!is(SqlTokenType.EOF) && token.start() < end) {
            next();
        }
    }

    boolean atStmtBreak() {
        return isStmtSeparator() || is(SqlTokenType.EOF);
    }

    /**
     * LPAREN 已消费；跳过到匹配的 RPAREN（含消费该 RPAREN），返回括号内原文。
     *
     * @return 括号内 token 文本（不含外层括号）
     */
    String skipBalancedParensContent() {
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

    String consumeRawAllowingBeginEnd() {
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
        String t = token.text();
        if (sb.length() > 0 && !noSpaceBeforeRawToken(sb, t)) {
            sb.append(' ');
        }
        sb.append(t);
    }

    /**
     * MySQL {@code 'u'@'%'} / {@code u@localhost} 收件人：{@code @} 两侧不加空格；
     * 逗号前不加空格（{@code (10 , 2)} → {@code (10, 2)}，与函数参数写法一致）。
     */
    private static boolean noSpaceBeforeRawToken(StringBuilder sb, String next) {
        if (next == null || next.isEmpty()) {
            return false;
        }
        if (next.charAt(0) == '@') {
            return true;
        }
        if (next.charAt(0) == ',') {
            return true;
        }
        return sb.charAt(sb.length() - 1) == '@';
    }

    String consumeRawUntilSemi() {
        StringBuilder sb = new StringBuilder();
        while (!atStmtBreak()) {
            appendRawToken(sb);
            next();
        }
        return sb.toString();
    }

    /**
     * 消费本条语句剩余部分到终止符为止（终止符留给调用方），返回原文切片（保留原始间距与大小写）。
     * 供 {@link SqlParseContext#consumeRest()} 使用。
     *
     * @return 剩余原文，空返回空串
     */
    String consumeRawSliceUntilStmtBreak() {
        if (atStmtBreak()) {
            return "";
        }
        int from = token.start();
        int to = token.end();
        while (!atStmtBreak()) {
            to = token.end();
            next();
        }
        return lexer.rawSlice(from, to);
    }

    String consumeRawUntilType(SqlTokenType end) {
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
            appendRawToken(sb);
            next();
        }
        return sb.toString();
    }

    String consumeRawUntilClause() {
        StringBuilder sb = new StringBuilder();
        while (!is(SqlTokenType.EOF) && !is(SqlTokenType.SEMICOLON) && !isAliasStop(token.type())) {
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

    static String unquote(String raw) {
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

    boolean is(SqlTokenType type) {
        return token.type() == type;
    }

    boolean match(SqlTokenType type) {
        if (is(type)) {
            next();
            return true;
        }
        return false;
    }

    void expect(SqlTokenType type) {
        if (!match(type)) {
            throw error("expected " + type + " but got " + token.type());
        }
    }

    void next() {
        token = lexer.next();
        if (keepComments) {
            while (token.type() == SqlTokenType.SQL_COMMENT) {
                if (pendingComments == null) {
                    pendingComments = new ArrayList<String>(2);
                }
                pendingComments.add(token.text());
                token = lexer.next();
            }
        }
    }

    SqlParseException error(String message) {
        return new SqlParseException(message, token.line(), token.column(),
                lexer.snippet(token.start()));
    }

}
