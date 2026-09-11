package com.alianga.jkit.sql;

/**
 * 手写 SQL 词法分析器。在 {@code char[]} 上前进，关键字查找不分配字符串。
 *
 * <p>记号对象来自长度为 8 的环形池，解析器 {@link #next()} / {@link #peek()} 都走同一套。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlLexer {
    private char[] src;
    private int start;
    private int limit;
    private int pos;
    private int line;
    private int lineStart;
    private SqlDialectSpec dialect;
    private boolean keepComments;
    private boolean pipesAsConcat;
    private SqlPlaceholderPattern[] placeholderPatterns;
    private int executableDepth;

    private final SqlToken tokA = new SqlToken();
    private final SqlToken tokB = new SqlToken();
    private final SqlToken tokLook = new SqlToken();
    private final SqlToken tokPeekSave = new SqlToken();
    private SqlToken peekBuf;
    private boolean peeked;

    /**
     * 创建未绑定输入的词法器，随后调用 {@link #reset}。
     */
    public SqlLexer() {
    }

    /**
     * 绑定一段 SQL 文本。
     *
     * @param sql SQL
     * @param dialect 方言（枚举或 {@link SqlDialectWrapper} 自定义能力）
     */
    public void reset(String sql, SqlDialectSpec dialect) {
        if (sql == null) {
            sql = "";
        }
        char[] chars = sql.toCharArray();
        reset(chars, 0, chars.length, dialect);
    }

    /**
     * 绑定源缓冲切片。
     *
     * @param src 源
     * @param offset 起始
     * @param length 长度
     * @param dialect 方言（枚举或 {@link SqlDialectWrapper} 自定义能力）
     */
    public void reset(char[] src, int offset, int length, SqlDialectSpec dialect) {
        this.src = src;
        this.start = offset;
        this.limit = offset + length;
        this.pos = offset;
        this.line = 1;
        this.lineStart = offset;
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        this.peeked = false;
        this.peekBuf = null;
        this.executableDepth = 0;
    }

    /**
     * 是否保留普通注释为 {@link SqlTokenType#SQL_COMMENT}（默认 false）。
     * 可执行注释与优化器 hint 不受此开关影响。
     *
     * @param keepComments 保留注释
     */
    public void setKeepComments(boolean keepComments) {
        this.keepComments = keepComments;
    }

    /**
     * @return 是否保留普通注释
     */
    public boolean keepComments() {
        return keepComments;
    }

    /**
     * MySQL 下把 {@code ||} 当拼接（PIPES_AS_CONCAT）。默认 false。
     *
     * @param pipesAsConcat 拼接语义
     */
    public void setPipesAsConcat(boolean pipesAsConcat) {
        this.pipesAsConcat = pipesAsConcat;
    }

    /**
     * @return 是否把 {@code ||} 当拼接
     */
    public boolean pipesAsConcat() {
        return pipesAsConcat;
    }

    /**
     * 设置模板占位符匹配器；null 或空数组表示关闭（默认）。
     *
     * @param patterns 匹配器
     * @since 2.0.1
     */
    public void setPlaceholderPatterns(SqlPlaceholderPattern[] patterns) {
        if (patterns == null || patterns.length == 0) {
            this.placeholderPatterns = null;
        } else {
            this.placeholderPatterns = patterns;
        }
    }

    /**
     * @return 当前占位符匹配器，未启用时为 null
     * @since 2.0.1
     */
    public SqlPlaceholderPattern[] placeholderPatterns() {
        return placeholderPatterns;
    }

    /**
     * @return 当前方言
     */
    public SqlDialectSpec dialect() {
        return dialect;
    }

    /**
     * @return 当前绝对下标
     */
    public int position() {
        return pos;
    }

    /**
     * 读下一个记号。
     *
     * @return 记号，结束为 {@link SqlTokenType#EOF}
     */
    public SqlToken next() {
        if (peeked) {
            peeked = false;
            // 复制到 tokA：parser.token 可能仍引用上次返回的 peekBuf(tokB)，
            // 若直接返回 tokB，后续 peek() 的 scanInto(tokB) 会就地污染当前记号
            // （典型症状：DATE/TIMESTAMP 函数调用、MIN(Date) 等被打成 unexpected token）。
            tokA.copyFrom(peekBuf);
            peekBuf = null;
            return tokA;
        }
        scanInto(tokA);
        return tokA;
    }

    /**
     * 预读一个记号，不前进逻辑位置（下一次 {@link #next()} 返回同一个）。
     *
     * @return 预读记号
     */
    public SqlToken peek() {
        if (!peeked) {
            scanInto(tokB);
            peekBuf = tokB;
            peeked = true;
        }
        return peekBuf;
    }

    /**
     * 预读第 {@code n} 个后续记号（0=当前 peek / 下一 {@link #next()}），不前进逻辑位置。
     * 仅供解析器消歧；返回的记号在下次调用前有效。
     *
     * @param n 向前偏移（0 等价 {@link #peek()}）
     * @return 记号
     * @since 2.0.1
     */
    public SqlToken lookahead(int n) {
        if (n <= 0) {
            return peek();
        }
        int savedPos = pos;
        int savedLine = line;
        int savedLineStart = lineStart;
        boolean savedPeeked = peeked;
        int savedExec = executableDepth;
        if (peeked) {
            tokPeekSave.copyFrom(peekBuf);
        }
        try {
            // LA(0)=peek：若尚未 peek，先扫过 LA(0)（写入 tokLook 以免污染 peek 缓冲）
            if (!peeked) {
                scanInto(tokLook);
            }
            for (int i = 0; i < n; i++) {
                scanInto(tokLook);
            }
            return tokLook;
        } finally {
            pos = savedPos;
            line = savedLine;
            lineStart = savedLineStart;
            executableDepth = savedExec;
            peeked = savedPeeked;
            if (savedPeeked) {
                tokB.copyFrom(tokPeekSave);
                peekBuf = tokB;
            } else {
                peekBuf = null;
            }
        }
    }

    private void scanInto(SqlToken token) {
        skipSpaceAndComment();
        if (pos >= limit) {
            token.set(SqlTokenType.EOF, src, pos, pos, line, col());
            return;
        }
        int tLine = line;
        int tCol = col();
        int tStart = pos;
        if (tryScanPlaceholder(token, tLine, tCol, tStart)) {
            return;
        }
        char c = src[pos];
        if (c == '/' && pos + 1 < limit && src[pos + 1] == '*'
                && pos + 2 < limit && src[pos + 2] == '+') {
            scanHint(token, tLine, tCol, tStart);
            return;
        }
        if (keepComments && ((c == '-' && pos + 1 < limit && src[pos + 1] == '-')
                || (c == '#' && dialect.hashLineComment())
                || (c == '/' && pos + 1 < limit && src[pos + 1] == '*'))) {
            scanSqlComment(token, tLine, tCol, tStart);
            return;
        }
        if (pos + 1 < limit && src[pos + 1] == '\'') {
            if (c == 'N' || c == 'n') {
                pos++;
                scanString(token, tLine, tCol, tStart, '\'');
                return;
            }
            if (c == 'X' || c == 'x') {
                pos += 2;
                while (pos < limit && src[pos] != '\'') {
                    pos++;
                }
                if (pos < limit) {
                    pos++;
                }
                token.set(SqlTokenType.HEX, src, tStart, pos, tLine, tCol);
                return;
            }
            if (c == 'B' || c == 'b') {
                pos += 2;
                while (pos < limit && src[pos] != '\'') {
                    pos++;
                }
                if (pos < limit) {
                    pos++;
                }
                token.set(SqlTokenType.BIT, src, tStart, pos, tLine, tCol);
                return;
            }
        }
        if (c == '$' && pos + 1 < limit
                && (src[pos + 1] == '$' || isIdentStart(src[pos + 1]))) {
            // $$…$$ 或 $tag$…$tag$；单一 $ident 仍走下方运算符/IDENT 回退
            int look = pos + 1;
            if (src[look] != '$') {
                while (look < limit && src[look] != '$' && isIdentPart(src[look])) {
                    look++;
                }
                if (look >= limit || src[look] != '$') {
                    // 例如 $1 绑定：交给后续
                } else {
                    scanDollarString(token, tLine, tCol, tStart);
                    return;
                }
            } else {
                scanDollarString(token, tLine, tCol, tStart);
                return;
            }
        }
        if (isIdentStart(c)) {
            scanIdent(token, tLine, tCol, tStart);
            return;
        }
        if (c >= '0' && c <= '9') {
            scanNumberOrHex(token, tLine, tCol, tStart);
            return;
        }
        if (c == '.' && pos + 1 < limit && src[pos + 1] >= '0' && src[pos + 1] <= '9') {
            // 点号后若是「数字开头标识符」（如 t.1_id / test.52_user），只发 DOT，数字留给下次扫描提升为 IDENT。
            // 纯小数（.5 / .52 / .52e1）仍走 scanNumber。
            if (looksLikeDottedDigitLeadingIdent(pos + 1)) {
                scanOperator(token, tLine, tCol, tStart, c);
                return;
            }
            scanNumber(token, tLine, tCol, tStart);
            return;
        }
        if (c == '\'' || (c == '"' && dialect.doubleQuoteIsString())) {
            scanString(token, tLine, tCol, tStart, c);
            return;
        }
        if (c == '`' || (c == '"' && !dialect.doubleQuoteIsString())
                || (c == '[' && dialect.bracketIdentifiers())) {
            scanQuotedIdent(token, tLine, tCol, tStart, c);
            return;
        }
        if (c == '?') {
            pos++;
            // JDBC 位置绑定 ?1 / ?2
            while (pos < limit && src[pos] >= '0' && src[pos] <= '9') {
                pos++;
            }
            token.set(SqlTokenType.BIND, src, tStart, pos, tLine, tCol);
            return;
        }
        if (c == ':' && pos + 1 < limit
                && (isIdentStart(src[pos + 1]) || (src[pos + 1] >= '0' && src[pos + 1] <= '9'))) {
            // Oracle / JDBC：:name / :1 / :0
            pos++;
            if (src[pos] >= '0' && src[pos] <= '9') {
                while (pos < limit && src[pos] >= '0' && src[pos] <= '9') {
                    pos++;
                }
            } else {
                scanIdentBody();
            }
            token.set(SqlTokenType.NAMED_BIND, src, tStart, pos, tLine, tCol);
            return;
        }
        if (c == '@') {
            // PG jsonb/range：@> ；其余仍按 MySQL/SQL Server 变量 @var / @@var
            if (pos + 1 < limit && src[pos + 1] == '>') {
                pos += 2;
                token.set(SqlTokenType.AT_OP, src, tStart, pos, tLine, tCol);
                return;
            }
            pos++;
            if (pos < limit && src[pos] == '@') {
                pos++;
            }
            if (pos < limit && isIdentPart(src[pos])) {
                scanIdentBody();
            }
            token.set(SqlTokenType.VARIABLE, src, tStart, pos, tLine, tCol);
            return;
        }
        // SQL Server 临时表 #tmp / ##global：非 MySQL/H2 时 # 不是行注释
        if (c == '#' && !dialect.hashLineComment()
                && pos + 1 < limit
                && (isIdentStart(src[pos + 1]) || src[pos + 1] == '#')) {
            pos++;
            if (pos < limit && src[pos] == '#') {
                pos++;
            }
            while (pos < limit && isIdentPart(src[pos])) {
                pos++;
            }
            token.set(SqlTokenType.IDENT, src, tStart, pos, tLine, tCol);
            return;
        }
        scanOperator(token, tLine, tCol, tStart, c);
    }

    /**
     * 按配置尝试匹配模板占位符，成功则发出 IDENT。
     */
    private boolean tryScanPlaceholder(SqlToken token, int tLine, int tCol, int tStart) {
        SqlPlaceholderPattern[] patterns = this.placeholderPatterns;
        if (patterns == null) {
            return false;
        }
        for (int p = 0; p < patterns.length; p++) {
            int end = patterns[p].tryMatch(src, pos, limit);
            if (end > pos) {
                pos = end;
                token.set(SqlTokenType.IDENT, src, tStart, pos, tLine, tCol);
                return true;
            }
        }
        return false;
    }

    private SqlToken scanIdent(SqlToken token, int tLine, int tCol, int tStart) {
        scanIdentBody();
        SqlTokenType type = SqlKeywords.lookup(src, tStart, pos - tStart);
        token.set(type, src, tStart, pos, tLine, tCol);
        return token;
    }

    private void scanIdentBody() {
        pos++;
        while (pos < limit && isIdentPart(src[pos])) {
            pos++;
        }
    }

    private SqlToken scanQuotedIdent(SqlToken token, int tLine, int tCol, int tStart, char open) {
        char close = open == '[' ? ']' : open;
        pos++;
        while (pos < limit) {
            char c = src[pos];
            if (c == close) {
                if (open != '[' && pos + 1 < limit && src[pos + 1] == close) {
                    pos += 2;
                    continue;
                }
                pos++;
                break;
            }
            if (c == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        token.set(SqlTokenType.IDENT, src, tStart, pos, tLine, tCol);
        return token;
    }

    private SqlToken scanString(SqlToken token, int tLine, int tCol, int tStart, char quote) {
        pos++;
        while (pos < limit) {
            char c = src[pos];
            if (c == quote) {
                if (pos + 1 < limit && src[pos + 1] == quote) {
                    pos += 2;
                    continue;
                }
                pos++;
                break;
            }
            if (c == '\\' && dialect.backslashEscapes() && pos + 1 < limit) {
                pos += 2;
                continue;
            }
            if (c == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        token.set(SqlTokenType.STRING, src, tStart, pos, tLine, tCol);
        return token;
    }

    private SqlToken scanDollarString(SqlToken token, int tLine, int tCol, int tStart) {
        // PG：`$tag$content$tag$` / `$$content$$`（tag 可空）
        pos++; // 跳过首个 $
        int tagBodyStart = pos;
        while (pos < limit && src[pos] != '$' && isIdentPart(src[pos])) {
            pos++;
        }
        if (pos >= limit || src[pos] != '$') {
            // 不是合法 dollar-quote，回退成单字符 IDENT "$"
            pos = tStart + 1;
            token.set(SqlTokenType.IDENT, src, tStart, pos, tLine, tCol);
            return token;
        }
        // 开标签为 [tStart, pos]（含两侧 $）
        int tagLen = pos - tStart + 1;
        pos++; // 进入内容
        while (pos + tagLen <= limit) {
            if (src[pos] == '$' && regionEquals(pos, tStart, tagLen)) {
                pos += tagLen;
                token.set(SqlTokenType.STRING, src, tStart, pos, tLine, tCol);
                return token;
            }
            if (src[pos] == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        token.set(SqlTokenType.STRING, src, tStart, pos, tLine, tCol);
        return token;
    }

    private boolean regionEquals(int at, int tagStart, int tagLen) {
        for (int i = 0; i < tagLen; i++) {
            if (src[at + i] != src[tagStart + i]) {
                return false;
            }
        }
        return true;
    }

    private SqlToken scanNumberOrHex(SqlToken token, int tLine, int tCol, int tStart) {
        if (src[pos] == '0' && pos + 1 < limit) {
            char n = src[pos + 1];
            if (n == 'x' || n == 'X') {
                pos += 2;
                while (pos < limit && isHex(src[pos])) {
                    pos++;
                }
                token.set(SqlTokenType.HEX, src, tStart, pos, tLine, tCol);
                return token;
            }
            if (n == 'b' || n == 'B') {
                pos += 2;
                while (pos < limit && (src[pos] == '0' || src[pos] == '1')) {
                    pos++;
                }
                token.set(SqlTokenType.BIT, src, tStart, pos, tLine, tCol);
                return token;
            }
        }
        return scanNumber(token, tLine, tCol, tStart);
    }

    private SqlToken scanNumber(SqlToken token, int tLine, int tCol, int tStart) {
        boolean seenDot = false;
        while (pos < limit) {
            char c = src[pos];
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' && !seenDot) {
                seenDot = true;
                pos++;
            } else {
                break;
            }
        }
        if (pos < limit && (src[pos] == 'e' || src[pos] == 'E')) {
            int save = pos;
            pos++;
            if (pos < limit && (src[pos] == '+' || src[pos] == '-')) {
                pos++;
            }
            if (pos < limit && src[pos] >= '0' && src[pos] <= '9') {
                while (pos < limit && src[pos] >= '0' && src[pos] <= '9') {
                    pos++;
                }
            } else {
                pos = save;
            }
        }
        // Oracle / Java 风格数值后缀：25f / 0.5d / 1.0F / 1D / 1.DM / .5M
        if (pos < limit) {
            char s = src[pos];
            if (s == 'f' || s == 'F' || s == 'd' || s == 'D') {
                pos++;
                if (pos < limit && (src[pos] == 'm' || src[pos] == 'M')) {
                    pos++;
                }
            } else if (seenDot && (s == 'm' || s == 'M')) {
                pos++;
            }
        }
        // MySQL：裸标识符可以数字开头（2nd_col）。单字母 f/d/m 留给后缀或下一记号（Oracle 1m 别名）。
        if (!seenDot && pos < limit && isIdentStart(src[pos])) {
            int save = pos;
            while (pos < limit && isIdentPart(src[pos])) {
                pos++;
            }
            if (pos - save == 1) {
                char s = src[save];
                if (s == 'f' || s == 'F' || s == 'd' || s == 'D' || s == 'm' || s == 'M') {
                    pos = save;
                }
            }
            if (pos > save) {
                SqlTokenType type = SqlKeywords.lookup(src, tStart, pos - tStart);
                token.set(type, src, tStart, pos, tLine, tCol);
                return token;
            }
        }
        token.set(SqlTokenType.NUMBER, src, tStart, pos, tLine, tCol);
        return token;
    }

    private SqlToken scanOperator(SqlToken token, int tLine, int tCol, int tStart, char c) {
        pos++;
        SqlTokenType type;
        switch (c) {
            case ',':
                type = SqlTokenType.COMMA;
                break;
            case '.':
                type = SqlTokenType.DOT;
                break;
            case '*':
                type = SqlTokenType.STAR;
                break;
            case '+':
                type = SqlTokenType.PLUS;
                break;
            case '-':
                if (match('>')) {
                    if (match('>')) {
                        type = SqlTokenType.JSON_OP;
                    } else {
                        type = SqlTokenType.JSON_OP;
                    }
                } else if (match('#')) {
                    // PG jsonb：-# path
                    type = SqlTokenType.JSON_OP;
                } else {
                    type = SqlTokenType.MINUS;
                }
                break;
            case '/':
                type = SqlTokenType.SLASH;
                break;
            case '%':
                type = SqlTokenType.PERCENT;
                break;
            case '(':
                type = SqlTokenType.LPAREN;
                break;
            case ')':
                type = SqlTokenType.RPAREN;
                break;
            case '[':
                type = SqlTokenType.LBRACKET;
                break;
            case ']':
                type = SqlTokenType.RBRACKET;
                break;
            case '{':
                type = SqlTokenType.LBRACE;
                break;
            case '}':
                type = SqlTokenType.RBRACE;
                break;
            case ';':
                type = SqlTokenType.SEMICOLON;
                break;
            case ':':
                if (match(':')) {
                    type = SqlTokenType.CAST_OP;
                } else if (match('=')) {
                    type = SqlTokenType.ASSIGN;
                } else {
                    type = SqlTokenType.COLON;
                }
                break;
            case '=':
                if (match('>')) {
                    type = SqlTokenType.FAT_ARROW;
                } else {
                    match('=');
                    type = SqlTokenType.EQ;
                }
                break;
            case '<':
                if (match('=')) {
                    if (match('>')) {
                        type = SqlTokenType.NULL_SAFE_EQ;
                    } else {
                        type = SqlTokenType.LE;
                    }
                } else if (match('>')) {
                    type = SqlTokenType.NE;
                } else if (pos + 1 < limit && src[pos] == '-' && src[pos + 1] == '>') {
                    pos += 2;
                    type = SqlTokenType.AT_OP;
                } else if (pos + 1 < limit && src[pos] == '#' && src[pos + 1] == '>') {
                    pos += 2;
                    type = SqlTokenType.AT_OP;
                } else if (match('<')) {
                    type = SqlTokenType.SHIFT_LEFT;
                } else if (match('@')) {
                    type = SqlTokenType.AT_OP;
                } else if (matchSkippingSpace('=')) {
                    type = SqlTokenType.LE;
                } else if (matchSkippingSpace('>')) {
                    type = SqlTokenType.NE;
                } else {
                    type = SqlTokenType.LT;
                }
                break;
            case '>':
                if (match('=')) {
                    type = SqlTokenType.GE;
                } else if (match('>')) {
                    type = SqlTokenType.SHIFT_RIGHT;
                } else if (matchSkippingSpace('=')) {
                    type = SqlTokenType.GE;
                } else {
                    type = SqlTokenType.GT;
                }
                break;
            case '!':
                if (match('=')) {
                    type = SqlTokenType.NE;
                } else if (matchSkippingSpace('=')) {
                    type = SqlTokenType.NE;
                } else if (match('~')) {
                    match('*');
                    type = SqlTokenType.REGEX_OP;
                } else {
                    type = SqlTokenType.NOT_OP;
                }
                break;
            case '&':
                if (match('&')) {
                    type = SqlTokenType.AND_OP;
                } else if (match('>')) {
                    type = SqlTokenType.AT_OP;
                } else if (match('<')) {
                    type = SqlTokenType.AT_OP;
                } else {
                    type = SqlTokenType.BIT_AND;
                }
                break;
            case '|':
                if (match('|')) {
                    type = (dialect.pipesAsOr() && !pipesAsConcat)
                            ? SqlTokenType.OR_OP : SqlTokenType.CONCAT;
                } else if (matchSkippingSpace('|')) {
                    type = (dialect.pipesAsOr() && !pipesAsConcat)
                            ? SqlTokenType.OR_OP : SqlTokenType.CONCAT;
                } else {
                    type = SqlTokenType.BIT_OR;
                }
                break;
            case '^':
                if (match('=') || matchSkippingSpace('=')) {
                    type = SqlTokenType.NE; // Oracle ^=
                } else {
                    type = SqlTokenType.BIT_XOR;
                }
                break;
            case '~':
                if (match('*')) {
                    type = SqlTokenType.REGEX_OP;
                } else {
                    // PG 二元正则与按位取反同形；词法保留 TILDE，解析器按方言/位置区分
                    type = SqlTokenType.TILDE;
                }
                break;
            case '#':
                if (match('>') && match('>')) {
                    type = SqlTokenType.JSON_OP;
                } else if (src[pos - 1] == '>' || match('>')) {
                    type = SqlTokenType.JSON_OP;
                } else {
                    type = SqlTokenType.JSON_OP;
                }
                break;
            default:
                type = SqlTokenType.IDENT;
                break;
        }
        token.set(type, src, tStart, pos, tLine, tCol);
        return token;
    }

    private boolean match(char expect) {
        if (pos < limit && src[pos] == expect) {
            pos++;
            return true;
        }
        return false;
    }

    /** 允许运算符中间夹空白与块注释、行注释。 */
    private boolean matchSkippingSpace(char expect) {
        int savePos = pos;
        int saveLine = line;
        int saveLineStart = lineStart;
        while (pos < limit) {
            char ch = src[pos];
            if (ch == ' ' || ch == '\t' || ch == '\r') {
                pos++;
                continue;
            }
            if (ch == '\n') {
                pos++;
                line++;
                lineStart = pos;
                continue;
            }
            // 块注释
            if (ch == '/' && pos + 1 < limit && src[pos + 1] == '*') {
                pos += 2;
                while (pos + 1 < limit && !(src[pos] == '*' && src[pos + 1] == '/')) {
                    if (src[pos] == '\n') {
                        line++;
                        lineStart = pos + 1;
                    }
                    pos++;
                }
                if (pos + 1 < limit) {
                    pos += 2;
                }
                continue;
            }
            // 行注释
            if (ch == '-' && pos + 1 < limit && src[pos + 1] == '-') {
                pos += 2;
                while (pos < limit && src[pos] != '\n') {
                    pos++;
                }
                continue;
            }
            break;
        }
        if (pos < limit && src[pos] == expect) {
            pos++;
            return true;
        }
        pos = savePos;
        line = saveLine;
        lineStart = saveLineStart;
        return false;
    }

    private void skipSpaceAndComment() {
        while (pos < limit) {
            if (executableDepth > 0 && pos + 1 < limit && src[pos] == '*' && src[pos + 1] == '/') {
                pos += 2;
                executableDepth--;
                continue;
            }
            char c = src[pos];
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
                continue;
            }
            if (c == '\n') {
                pos++;
                line++;
                lineStart = pos;
                continue;
            }
            if (c == '-' && pos + 1 < limit && src[pos + 1] == '-') {
                if (keepComments) {
                    return;
                }
                pos += 2;
                skipToEol();
                continue;
            }
            // 行内 // 备注：`// text`（// 后须有空白+内容）。
            // 不吞 `DELIMITER //` / `SELECT 1 //`；遇 )];,; 等结构符即停（避免吃掉 `) as t`）。
            if (c == '/' && pos + 1 < limit && src[pos + 1] == '/') {
                int i = pos + 2;
                boolean hasSpace = i < limit && (src[i] == ' ' || src[i] == '\t');
                boolean hasText = false;
                if (hasSpace) {
                    int j = i;
                    while (j < limit && (src[j] == ' ' || src[j] == '\t')) {
                        j++;
                    }
                    hasText = j < limit && src[j] != '\n' && src[j] != '\r';
                }
                if (hasSpace && hasText) {
                    if (keepComments) {
                        return;
                    }
                    pos += 2;
                    while (pos < limit) {
                        char ch = src[pos];
                        if (ch == '\n' || ch == '\r') {
                            break;
                        }
                        if (ch == ')' || ch == ']' || ch == '}' || ch == ';' || ch == ',') {
                            break;
                        }
                        pos++;
                    }
                    continue;
                }
            }
            if (c == '#' && dialect.hashLineComment()) {
                if (keepComments) {
                    return;
                }
                // #{…} 一类模板占位优先于 # 行注释（tryMatch 不推进位置，命中交给 scanInto 发占位记号）
                SqlPlaceholderPattern[] pats = this.placeholderPatterns;
                if (pats != null) {
                    boolean hit = false;
                    for (int i = 0; i < pats.length; i++) {
                        if (pats[i].tryMatch(src, pos, limit) > pos) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        return;
                    }
                }
                pos++;
                skipToEol();
                continue;
            }
            if (c == '/' && pos + 1 < limit && src[pos + 1] == '*') {
                if (pos + 2 < limit && src[pos + 2] == '!') {
                    pos += 3;
                    while (pos < limit && src[pos] >= '0' && src[pos] <= '9') {
                        pos++;
                    }
                    executableDepth++;
                    continue;
                }
                if (pos + 2 < limit && src[pos + 2] == '+') {
                    return;
                }
                if (keepComments) {
                    return;
                }
                pos += 2;
                skipBlockComment();
                continue;
            }
            return;
        }
    }

    private void skipToEol() {
        while (pos < limit && src[pos] != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        while (pos + 1 < limit) {
            if (src[pos] == '*' && src[pos + 1] == '/') {
                pos += 2;
                return;
            }
            if (src[pos] == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        pos = limit;
    }

    private void scanHint(SqlToken token, int tLine, int tCol, int tStart) {
        pos += 3;
        while (pos + 1 < limit) {
            if (src[pos] == '*' && src[pos + 1] == '/') {
                pos += 2;
                token.set(SqlTokenType.HINT, src, tStart, pos, tLine, tCol);
                return;
            }
            if (src[pos] == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        pos = limit;
        token.set(SqlTokenType.HINT, src, tStart, pos, tLine, tCol);
    }

    private void scanSqlComment(SqlToken token, int tLine, int tCol, int tStart) {
        char c = src[pos];
        if (c == '-' || c == '#') {
            if (c == '-') {
                pos += 2;
            } else {
                pos++;
            }
            skipToEol();
            token.set(SqlTokenType.SQL_COMMENT, src, tStart, pos, tLine, tCol);
            return;
        }
        pos += 2;
        while (pos + 1 < limit) {
            if (src[pos] == '*' && src[pos + 1] == '/') {
                pos += 2;
                token.set(SqlTokenType.SQL_COMMENT, src, tStart, pos, tLine, tCol);
                return;
            }
            if (src[pos] == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        pos = limit;
        token.set(SqlTokenType.SQL_COMMENT, src, tStart, pos, tLine, tCol);
    }

    private int col() {
        return pos - lineStart + 1;
    }

    /**
     * 从 {@code at}（应为首个数字）向前窥探：数字串之后若紧跟标识符起始字符，且不是合法科学计数指数，
     * 则视为「点号后的数字开头标识符」（如 {@code .52_user}），调用方应只消费 DOT。
     */
    private boolean looksLikeDottedDigitLeadingIdent(int at) {
        int p = at;
        while (p < limit && src[p] >= '0' && src[p] <= '9') {
            p++;
        }
        if (p < limit && (src[p] == 'e' || src[p] == 'E')) {
            int exp = p + 1;
            if (exp < limit && (src[exp] == '+' || src[exp] == '-')) {
                exp++;
            }
            if (exp < limit && src[exp] >= '0' && src[exp] <= '9') {
                return false; // .52e1 / .52E+10 等纯小数
            }
        }
        // .5M / .5d / .5DM 是 Oracle 数值后缀，不是 t.1_id 形标识符
        if (p < limit && isIdentStart(src[p]) && isOracleNumericSuffixAt(p)) {
            return false;
        }
        return p < limit && isIdentStart(src[p]);
    }

    /** Oracle 数值字面量后缀起点：f/F/d/D/m/M 及 dm/DM。 */
    private boolean isOracleNumericSuffixAt(int at) {
        if (at >= limit) {
            return false;
        }
        char s = src[at];
        int p = at;
        if (s == 'f' || s == 'F' || s == 'd' || s == 'D') {
            p++;
            if (p < limit && (src[p] == 'm' || src[p] == 'M')) {
                p++;
            }
        } else if (s == 'm' || s == 'M') {
            p++;
        } else {
            return false;
        }
        return p >= limit || !isIdentPart(src[p]);
    }

    private boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$'
                || c > 127;
    }

    private boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '#';
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * 截取源缓冲绝对区间（不含 to），供容错 parseAll 保留失败语句原文。
     *
     * @param from 起始下标（含）
     * @param to 结束下标（不含）
     * @return 原文切片，越界时裁剪；空区间返回空串
     * @since 2.0.1
     */
    public String rawSlice(int from, int to) {
        if (src == null || to <= from) {
            return "";
        }
        if (from < start) {
            from = start;
        }
        if (to > limit) {
            to = limit;
        }
        if (to <= from) {
            return "";
        }
        return new String(src, from, to - from);
    }

    /**
     * 从当前记号截取附近原文，供报错使用。
     *
     * @param around 中心下标
     * @return 片段
     */
    public String snippet(int around) {
        int from = around - 24;
        if (from < start) {
            from = start;
        }
        int to = around + 24;
        if (to > limit) {
            to = limit;
        }
        if (to <= from) {
            return "";
        }
        return new String(src, from, to - from);
    }
}
