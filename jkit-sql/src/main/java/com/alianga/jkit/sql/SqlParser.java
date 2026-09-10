package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlPrepareStatement;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlTableHandlerStatement;
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
    SqlDialect dialect;
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
     * @param dialect 方言
     */
    public void reset(String sql, SqlDialect dialect) {
        reset(sql, dialect, SqlParseOptions.defaults());
    }

    /**
     * 绑定输入与解析选项。
     *
     * @param sql SQL
     * @param dialect 方言
     * @param options 选项，null 视为默认
     * @since 2.0.1
     */
    public void reset(String sql, SqlDialect dialect, SqlParseOptions options) {
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        if (options == null) {
            options = SqlParseOptions.defaults();
        }
        this.keepComments = options.keepComments();
        this.pendingComments = null;
        this.stmtDelimiter = ";";
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
        while (!is(SqlTokenType.EOF)) {
            while (isStmtSeparator()) {
                consumeStmtSeparator();
            }
            if (is(SqlTokenType.EOF)) {
                break;
            }
            int stmtStart = token.start();
            if (tolerant) {
                try {
                    list.add(parseStatement());
                } catch (SqlParseException ex) {
                    pendingComments = null;
                    skipToStmtEnd();
                    SqlSimpleStatement bad = new SqlSimpleStatement();
                    bad.setStatementType(SqlStatementType.OTHER);
                    bad.setParseError(ex.getMessage());
                    String raw = lexer.rawSlice(stmtStart, token.start()).trim();
                    bad.setText(raw.isEmpty() ? ex.snippet() : raw);
                    list.add(bad);
                }
            } else {
                list.add(parseStatement());
            }
            if (isStmtSeparator()) {
                consumeStmtSeparator();
            }
        }
        return list;
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
        // SET PASSWORD [FOR user] = '…'：吞尾，避免 FOR 残留成下一条语句
        if (isIdent("PASSWORD")) {
            StringBuilder text = new StringBuilder("PASSWORD");
            next();
            if (!atStmtBreak()) {
                String rest = consumeRawUntilSemi();
                if (!rest.isEmpty()) {
                    text.append(' ').append(rest);
                }
            }
            stmt.setText(text.toString());
            return stmt;
        }
        if (match(SqlTokenType.NAMES)) {
            stmt.setName(SqlIdentifier.of("NAMES"));
            stmt.setValue(exprParser.parsePrimary());
            return stmt;
        }
        if (is(SqlTokenType.VARIABLE)) {
            stmt.setName(SqlIdentifier.of(token.text()));
            next();
        } else {
            stmt.setName(parseName());
        }
        if (match(SqlTokenType.EQ) || match(SqlTokenType.ASSIGN)) {
            stmt.setValue(exprParser.parseExpr());
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
        if (is(SqlTokenType.TRANSACTION) || isIdent("WORK")) {
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setStatementType(SqlStatementType.OTHER);
            StringBuilder text = new StringBuilder("BEGIN");
            text.append(' ').append(token.text().toUpperCase());
            next();
            if (!atStmtBreak()) {
                String rest = consumeRawUntilSemi();
                if (!rest.isEmpty()) {
                    text.append(' ').append(rest);
                }
            }
            stmt.setText(text.toString());
            return stmt;
        }
        if (atStmtBreak()) {
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setStatementType(SqlStatementType.OTHER);
            stmt.setText("BEGIN");
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

    private SqlStatement parseStartTransaction() {
        expect(SqlTokenType.START);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("START");
        if (is(SqlTokenType.TRANSACTION) || isIdent("TRANSACTION")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        } else if (is(SqlTokenType.WITH)) {
            // 不应把 SELECT 的 START WITH 当语句；此处仅顶层 START
            text.append(' ').append(consumeRawUntilSemi());
            stmt.setText(text.toString().trim());
            return stmt;
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

    private SqlStatement parseTxControl() {
        String kind = token.text().toUpperCase();
        next();
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder(kind);
        if ("SAVEPOINT".equals(kind) && identLike()) {
            stmt.setName(parseName());
            text.append(' ').append(stmt.name().qualifiedName());
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

    private SqlStatement parseFlush() {
        expect(SqlTokenType.FLUSH);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("FLUSH");
        if (!atStmtBreak()) {
            String rest = consumeRawUntilSemi();
            if (!rest.isEmpty()) {
                text.append(' ').append(rest);
            }
        }
        stmt.setText(text.toString());
        return stmt;
    }

    /**
     * MySQL {@code LOCK TABLES t READ, u WRITE, …}：OTHER + 全文，抽第一张表名。
     */
    private SqlStatement parseLockTables() {
        expect(SqlTokenType.LOCK);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("LOCK");
        if (is(SqlTokenType.TABLES) || is(SqlTokenType.TABLE) || isIdent("TABLES") || isIdent("TABLE")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        }
        if (identLike()) {
            stmt.setName(parseName());
            text.append(' ').append(stmt.name().qualifiedName());
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

    /**
     * MySQL {@code UNLOCK TABLES}。
     */
    private SqlStatement parseUnlockTables() {
        expect(SqlTokenType.UNLOCK);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("UNLOCK");
        if (is(SqlTokenType.TABLES) || is(SqlTokenType.TABLE) || isIdent("TABLES") || isIdent("TABLE")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
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

    private SqlStatement parseCopy() {
        expect(SqlTokenType.COPY);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("COPY");
        if (identLike()) {
            stmt.setName(parseName());
            text.append(' ').append(stmt.name().qualifiedName());
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

    /**
     * MySQL {@code LOAD DATA [LOCAL] INFILE … INTO TABLE …}：OTHER + 全文，抽目标表名。
     */
    private SqlStatement parseLoad() {
        expect(SqlTokenType.LOAD);
        SqlSimpleStatement stmt = new SqlSimpleStatement();
        stmt.setStatementType(SqlStatementType.OTHER);
        StringBuilder text = new StringBuilder("LOAD");
        // LOAD DATA [LOCAL] INFILE 'path' INTO TABLE t …
        if (isIdent("DATA")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        }
        if (is(SqlTokenType.LOCAL) || isIdent("LOCAL")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        }
        if (isIdent("INFILE")) {
            text.append(' ').append(token.text().toUpperCase());
            next();
        }
        if (is(SqlTokenType.STRING)) {
            text.append(' ').append(token.text());
            next();
        }
        if (is(SqlTokenType.INTO)) {
            text.append(' ').append(token.text().toUpperCase());
            next();
            if (is(SqlTokenType.TABLE)) {
                text.append(' ').append(token.text().toUpperCase());
                next();
            }
            if (identLike()) {
                stmt.setName(parseName());
                text.append(' ').append(stmt.name().qualifiedName());
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
        while (is(SqlTokenType.DOT)) {
            if (lexer.peek().type() == SqlTokenType.STAR) {
                break;
            }
            next();
            String part = consumeIdentPartRaw();
            if (isQuoted(part)) {
                id.setQuoted(true);
            }
            id.addName(unquote(part));
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
     * MySQL {@code 'u'@'%'} / {@code u@localhost} 收件人：{@code @} 两侧不加空格。
     */
    private static boolean noSpaceBeforeRawToken(StringBuilder sb, String next) {
        if (next == null || next.isEmpty()) {
            return false;
        }
        if (next.charAt(0) == '@') {
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
