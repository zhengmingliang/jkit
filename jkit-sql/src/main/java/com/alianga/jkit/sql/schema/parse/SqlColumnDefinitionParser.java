package com.alianga.jkit.sql.schema.parse;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.SqlLexer;
import com.alianga.jkit.sql.SqlParseException;
import com.alianga.jkit.sql.SqlToken;
import com.alianga.jkit.sql.SqlTokenType;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.schema.model.ColumnConstraint;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.model.SqlDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将原始列定义文本解析为结构化 {@link ColumnDefinition}。
 *
 * <p>复用现有 {@link SqlLexer}，不引入第二套 tokenizer，也不用正则猜测结构。
 * 解析失败时不抛异常，返回 {@code dataType=UNKNOWN, rawText=原文} 的降级结果。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlColumnDefinitionParser {
    private static final ConcurrentHashMap<String, ColumnDefinition> CACHE =
            new ConcurrentHashMap<String, ColumnDefinition>(64);

    private SqlColumnDefinitionParser() {
    }

    /**
     * 解析一条列定义或表级约束原文。
     *
     * @param rawDefinition 原文，可空
     * @param dialect 方言，null 视为 MySQL
     * @return 结构化结果，永不 null
     */
    public static ColumnDefinition parse(String rawDefinition, SqlDialectSpec dialect) {
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (rawDefinition == null) {
            return unknownColumn("", "");
        }
        String raw = rawDefinition.trim();
        if (raw.isEmpty()) {
            return unknownColumn("", raw);
        }
        String cacheKey = d.dialectId() + '\0' + raw;
        ColumnDefinition cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        ColumnDefinition parsed;
        try {
            parsed = new Parser(raw, d).parse();
        } catch (RuntimeException ignored) {
            parsed = unknownColumn("", raw);
        }
        if (CACHE.size() < 4096) {
            CACHE.putIfAbsent(cacheKey, parsed);
        }
        return parsed;
    }

    /**
     * 解析 CREATE TABLE 括号内全部片段。
     *
     * @param rawDefinitions 原文列表，可空
     * @param dialect 方言
     * @return 结构化列表
     */
    public static List<ColumnDefinition> parseAll(List<String> rawDefinitions, SqlDialectSpec dialect) {
        if (rawDefinitions == null || rawDefinitions.isEmpty()) {
            return new ArrayList<ColumnDefinition>(0);
        }
        List<ColumnDefinition> out = new ArrayList<ColumnDefinition>(rawDefinitions.size());
        for (int i = 0; i < rawDefinitions.size(); i++) {
            out.add(parse(rawDefinitions.get(i), dialect));
        }
        return out;
    }

    /**
     * 从 DDL 语句解析结构化列（不改 {@link SqlDdlStatement} 公开签名）。
     *
     * @param ddl CREATE/ALTER TABLE 语句，可空
     * @param dialect 方言
     * @return 结构化列表，ddl 为空时为空列表
     */
    public static List<ColumnDefinition> fromDdl(SqlDdlStatement ddl, SqlDialectSpec dialect) {
        if (ddl == null) {
            return new ArrayList<ColumnDefinition>(0);
        }
        return parseAll(ddl.columnDefinitions(), dialect);
    }

    private static ColumnDefinition unknownColumn(String name, String raw) {
        return new ColumnDefinition(name, SqlDataType.unknown(""),
                CollectionsEmpty.constraints(), raw, false);
    }

    private static final class CollectionsEmpty {
        private CollectionsEmpty() {
        }

        static List<ColumnConstraint> constraints() {
            return new ArrayList<ColumnConstraint>(0);
        }
    }

    private static final class Parser {
        private final String raw;
        private final SqlDialectSpec dialect;
        private final SqlLexer lexer;
        private SqlTokenType type;
        private String text;
        private int start;
        private boolean eof;

        Parser(String raw, SqlDialectSpec dialect) {
            this.raw = raw;
            this.dialect = dialect;
            this.lexer = new SqlLexer();
            this.lexer.reset(raw, dialect);
            advance();
        }

        ColumnDefinition parse() {
            if (eof) {
                return unknownColumn("", raw);
            }
            if (tableConstraintStart()) {
                return new ColumnDefinition("", SqlDataType.unknown(""),
                        new ArrayList<ColumnConstraint>(0), raw, true);
            }
            String columnName = unquote(text);
            advance();
            if (eof) {
                return new ColumnDefinition(columnName, SqlDataType.unknown(""),
                        new ArrayList<ColumnConstraint>(0), raw, false);
            }
            TypeParts typeParts = readType();
            List<ColumnConstraint> constraints = new ArrayList<ColumnConstraint>(4);
            EnumSet<SqlDataType.TypeAttribute> attrs = typeParts.attributes;
            while (!eof) {
                if (matchKeyword("UNSIGNED")) {
                    attrs.add(SqlDataType.TypeAttribute.UNSIGNED);
                } else if (matchKeyword("ZEROFILL")) {
                    attrs.add(SqlDataType.TypeAttribute.ZEROFILL);
                } else if (type == SqlTokenType.BINARY
                        || (type == SqlTokenType.IDENT && eq("BINARY"))) {
                    attrs.add(SqlDataType.TypeAttribute.BINARY_CHARSET);
                    advance();
                } else if (!readConstraint(constraints)) {
                    skipUnknown();
                }
            }
            applySerial(typeParts, constraints);
            SqlDataType dataType = new SqlDataType(typeParts.typeName, typeParts.precision,
                    typeParts.scale, attrs);
            return new ColumnDefinition(columnName, dataType, constraints, raw, false);
        }

        private void applySerial(TypeParts typeParts, List<ColumnConstraint> constraints) {
            String n = typeParts.typeName.toUpperCase(Locale.ROOT);
            if ("SERIAL".equals(n) || "SERIAL4".equals(n)) {
                typeParts.typeName = "INTEGER";
                addAuto(constraints, ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED);
            } else if ("BIGSERIAL".equals(n) || "SERIAL8".equals(n)) {
                typeParts.typeName = "BIGINT";
                addAuto(constraints, ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED);
            } else if ("SMALLSERIAL".equals(n) || "SERIAL2".equals(n)) {
                typeParts.typeName = "SMALLINT";
                addAuto(constraints, ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED);
            }
        }

        private void addAuto(List<ColumnConstraint> constraints,
                             ColumnConstraint.AutoIncrement.IdentityMode mode) {
            for (int i = 0; i < constraints.size(); i++) {
                if (constraints.get(i).kind() == ColumnConstraint.Kind.AUTO_INCREMENT) {
                    return;
                }
            }
            constraints.add(new ColumnConstraint.AutoIncrement(mode));
        }

        private TypeParts readType() {
            TypeParts parts = new TypeParts();
            if (eof) {
                parts.typeName = "";
                return parts;
            }
            String first = text;
            advance();
            String upper = first.toUpperCase(Locale.ROOT);
            if ("NATIONAL".equals(upper) && !eof
                    && (type == SqlTokenType.CHAR || type == SqlTokenType.CHARACTER
                    || eq("CHAR") || eq("CHARACTER"))) {
                parts.attributes.add(SqlDataType.TypeAttribute.NATIONAL);
                first = text;
                upper = first.toUpperCase(Locale.ROOT);
                advance();
            }
            if (("CHAR".equals(upper) || "CHARACTER".equals(upper) || "NCHAR".equals(upper))
                    && !eof && (type == SqlTokenType.VARYING || eq("VARYING"))) {
                first = "CHAR".equals(upper) || "CHARACTER".equals(upper)
                        ? "CHARACTER VARYING" : "NCHAR VARYING";
                if ("NCHAR".equals(upper) || parts.attributes.contains(
                        SqlDataType.TypeAttribute.NATIONAL)) {
                    parts.attributes.add(SqlDataType.TypeAttribute.NATIONAL);
                }
                advance();
            } else if ("DOUBLE".equals(upper) && !eof
                    && (type == SqlTokenType.PRECISION || eq("PRECISION"))) {
                first = "DOUBLE PRECISION";
                advance();
            } else if ("LONG".equals(upper) && !eof
                    && (eq("VARCHAR") || eq("VARBINARY") || eq("RAW")
                    || type == SqlTokenType.VARCHAR)) {
                first = "LONG " + text.toUpperCase(Locale.ROOT);
                advance();
            } else if (("TIMESTAMP".equals(upper) || "TIME".equals(upper)
                    || "DATETIME".equals(upper)) && !eof) {
                first = readTimeZoneSuffix(first);
            }
            if ("NCHAR".equals(upper) || "NVARCHAR".equals(upper)
                    || "NVARCHAR2".equals(upper) || "NCHAR VARYING".equalsIgnoreCase(first)) {
                parts.attributes.add(SqlDataType.TypeAttribute.NATIONAL);
            }
            if ("TIMESTAMPTZ".equals(upper)) {
                parts.attributes.add(SqlDataType.TypeAttribute.WITH_TIME_ZONE);
            }
            parts.typeName = first;
            if (type == SqlTokenType.LPAREN) {
                readPrecision(parts);
            }
            return parts;
        }

        private String readTimeZoneSuffix(String base) {
            if (eq("WITH") || eq("WITHOUT")) {
                boolean with = eq("WITH");
                advance();
                boolean local = false;
                if (!eof && eq("LOCAL")) {
                    local = true;
                    advance();
                }
                if (!eof && eq("TIME")) {
                    advance();
                    if (!eof && eq("ZONE")) {
                        advance();
                    }
                }
                if (with) {
                    return local ? base + " WITH LOCAL TIME ZONE" : base + " WITH TIME ZONE";
                }
                return base + " WITHOUT TIME ZONE";
            }
            return base;
        }

        private void readPrecision(TypeParts parts) {
            advance();
            if (type == SqlTokenType.NUMBER) {
                parts.precision = parseInt(text);
                advance();
                if (type == SqlTokenType.COMMA) {
                    advance();
                    if (type == SqlTokenType.NUMBER) {
                        parts.scale = parseInt(text);
                        advance();
                    }
                }
            } else if (eq("MAX")) {
                parts.typeName = parts.typeName + "(MAX)";
                advance();
            }
            int depth = 1;
            while (!eof && depth > 0) {
                if (type == SqlTokenType.LPAREN) {
                    depth++;
                } else if (type == SqlTokenType.RPAREN) {
                    depth--;
                }
                if (depth > 0) {
                    advance();
                }
            }
            if (type == SqlTokenType.RPAREN) {
                advance();
            }
        }

        private boolean readConstraint(List<ColumnConstraint> constraints) {
            if (type == SqlTokenType.NOT) {
                advance();
                if (type == SqlTokenType.NULL || eq("NULL")) {
                    advance();
                    constraints.add(ColumnConstraint.NotNull.INSTANCE);
                    return true;
                }
                return true;
            }
            if (type == SqlTokenType.NULL || eq("NULL")) {
                advance();
                constraints.add(ColumnConstraint.Nullable.INSTANCE);
                return true;
            }
            if (type == SqlTokenType.DEFAULT) {
                constraints.add(readDefault());
                return true;
            }
            if (type == SqlTokenType.AUTO_INCREMENT || eq("AUTO_INCREMENT")
                    || eq("AUTOINCREMENT")) {
                advance();
                constraints.add(new ColumnConstraint.AutoIncrement(
                        ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED));
                return true;
            }
            if (eq("IDENTITY")) {
                constraints.add(readIdentity(ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED));
                return true;
            }
            if (eq("GENERATED")) {
                return readGenerated(constraints);
            }
            if (type == SqlTokenType.PRIMARY) {
                advance();
                if (type == SqlTokenType.KEY || eq("KEY")) {
                    advance();
                }
                constraints.add(ColumnConstraint.InlinePrimaryKey.INSTANCE);
                return true;
            }
            if (type == SqlTokenType.UNIQUE || eq("UNIQUE")) {
                advance();
                if (type == SqlTokenType.KEY || eq("KEY")) {
                    advance();
                }
                constraints.add(ColumnConstraint.InlineUnique.INSTANCE);
                return true;
            }
            if (type == SqlTokenType.COMMENT || eq("COMMENT")) {
                advance();
                constraints.add(new ColumnConstraint.Comment(readStringOrIdent()));
                return true;
            }
            if (type == SqlTokenType.ON && peekEq("UPDATE")) {
                advance();
                advance();
                ExprSlice slice = readExprUntilConstraint();
                constraints.add(new ColumnConstraint.OnUpdate(
                        parseExpr(slice.text), slice.text));
                return true;
            }
            if (type == SqlTokenType.CHARACTER
                    || (eq("CHARSET") || type == SqlTokenType.CHARSET)) {
                if (type == SqlTokenType.CHARACTER) {
                    advance();
                    if (type == SqlTokenType.SET || eq("SET")) {
                        advance();
                    }
                } else {
                    advance();
                }
                if (type == SqlTokenType.EQ) {
                    advance();
                }
                constraints.add(new ColumnConstraint.CharacterSet(readStringOrIdent()));
                return true;
            }
            if (type == SqlTokenType.COLLATE || eq("COLLATE")) {
                advance();
                if (type == SqlTokenType.EQ) {
                    advance();
                }
                constraints.add(new ColumnConstraint.Collation(readStringOrIdent()));
                return true;
            }
            return false;
        }

        private boolean readGenerated(List<ColumnConstraint> constraints) {
            advance();
            ColumnConstraint.AutoIncrement.IdentityMode mode =
                    ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED;
            if (eq("ALWAYS")) {
                mode = ColumnConstraint.AutoIncrement.IdentityMode.ALWAYS;
                advance();
            } else if (eq("BY")) {
                advance();
                if (eq("DEFAULT")) {
                    mode = ColumnConstraint.AutoIncrement.IdentityMode.BY_DEFAULT;
                    advance();
                }
            }
            if (type == SqlTokenType.AS || eq("AS")) {
                advance();
            }
            if (eq("IDENTITY")) {
                constraints.add(readIdentity(mode));
                return true;
            }
            skipUnknown();
            return true;
        }

        private ColumnConstraint.AutoIncrement readIdentity(
                ColumnConstraint.AutoIncrement.IdentityMode mode) {
            advance();
            Integer seed = null;
            Integer increment = null;
            if (type == SqlTokenType.LPAREN) {
                advance();
                if (type == SqlTokenType.NUMBER) {
                    seed = parseInt(text);
                    advance();
                    if (type == SqlTokenType.COMMA) {
                        advance();
                        if (type == SqlTokenType.NUMBER) {
                            increment = parseInt(text);
                            advance();
                        }
                    }
                }
                skipUntilRparen();
            }
            return new ColumnConstraint.AutoIncrement(mode, seed, increment);
        }

        private ColumnConstraint.DefaultValue readDefault() {
            advance();
            ExprSlice slice = readExprUntilConstraint();
            return new ColumnConstraint.DefaultValue(parseExpr(slice.text), slice.text);
        }

        private ExprSlice readExprUntilConstraint() {
            if (eof) {
                return new ExprSlice("");
            }
            int from = start;
            int lastEnd = start;
            int depth = 0;
            boolean consumed = false;
            while (!eof) {
                // DEFAULT NULL：NULL 既是字面量也是约束关键字，至少吃掉默认值的第一段
                if (consumed && depth == 0 && isConstraintStart()) {
                    break;
                }
                if (type == SqlTokenType.LPAREN) {
                    depth++;
                } else if (type == SqlTokenType.RPAREN) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                lastEnd = lexer.position();
                consumed = true;
                advance();
            }
            if (lastEnd <= from) {
                return new ExprSlice("");
            }
            String slice = raw.substring(from, Math.min(lastEnd, raw.length())).trim();
            return new ExprSlice(slice);
        }

        private boolean isConstraintStart() {
            if (type == SqlTokenType.NOT || type == SqlTokenType.NULL
                    || type == SqlTokenType.DEFAULT || type == SqlTokenType.AUTO_INCREMENT
                    || type == SqlTokenType.PRIMARY || type == SqlTokenType.UNIQUE
                    || type == SqlTokenType.COMMENT || type == SqlTokenType.COLLATE
                    || type == SqlTokenType.CHARSET || type == SqlTokenType.CHARACTER
                    || type == SqlTokenType.UNSIGNED || type == SqlTokenType.ZEROFILL
                    || type == SqlTokenType.BINARY) {
                return true;
            }
            if (type == SqlTokenType.ON && peekEq("UPDATE")) {
                return true;
            }
            return eq("IDENTITY") || eq("GENERATED") || eq("AUTOINCREMENT")
                    || eq("CHARSET") || eq("UNSIGNED") || eq("ZEROFILL")
                    || eq("COLLATE") || eq("COMMENT");
        }

        private boolean tableConstraintStart() {
            if (type == SqlTokenType.PRIMARY || type == SqlTokenType.UNIQUE
                    || type == SqlTokenType.KEY || type == SqlTokenType.INDEX
                    || type == SqlTokenType.CONSTRAINT || type == SqlTokenType.FOREIGN
                    || type == SqlTokenType.CHECK) {
                return true;
            }
            return eq("FULLTEXT") || eq("SPATIAL") || eq("PERIOD") || eq("EXCLUDE");
        }

        private void skipUnknown() {
            if (eof) {
                return;
            }
            if (type == SqlTokenType.LPAREN) {
                skipUntilRparen();
                return;
            }
            advance();
            if (type == SqlTokenType.LPAREN) {
                skipUntilRparen();
            }
        }

        private void skipUntilRparen() {
            int depth = 0;
            while (!eof) {
                if (type == SqlTokenType.LPAREN) {
                    depth++;
                } else if (type == SqlTokenType.RPAREN) {
                    depth--;
                    advance();
                    if (depth <= 0) {
                        return;
                    }
                    continue;
                }
                advance();
            }
        }

        private String readStringOrIdent() {
            if (eof) {
                return "";
            }
            String value = text;
            if (type == SqlTokenType.STRING) {
                value = unquoteString(text);
            } else {
                value = unquote(text);
            }
            advance();
            return value;
        }

        private SqlExpr parseExpr(String expr) {
            if (expr == null || expr.trim().isEmpty()) {
                return null;
            }
            try {
                return SQL.parseExpr(expr, dialect);
            } catch (SqlParseException ignored) {
                return null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private boolean matchKeyword(String word) {
            if (eq(word)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean eq(String word) {
            return text != null && text.equalsIgnoreCase(word);
        }

        private boolean peekEq(String word) {
            SqlToken p = lexer.peek();
            return p != null && p.textEqualsIgnoreCase(word);
        }

        private void advance() {
            SqlToken t = lexer.next();
            type = t.type();
            text = t.text();
            start = t.start();
            eof = type == SqlTokenType.EOF;
        }

        private static Integer parseInt(String s) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String unquote(String raw) {
            if (raw == null || raw.length() < 2) {
                return raw == null ? "" : raw;
            }
            char a = raw.charAt(0);
            char b = raw.charAt(raw.length() - 1);
            if ((a == '`' && b == '`') || (a == '"' && b == '"') || (a == '[' && b == ']')) {
                return raw.substring(1, raw.length() - 1);
            }
            return raw;
        }

        private static String unquoteString(String raw) {
            if (raw == null || raw.length() < 2) {
                return raw == null ? "" : raw;
            }
            char a = raw.charAt(0);
            char b = raw.charAt(raw.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                return raw.substring(1, raw.length() - 1);
            }
            return raw;
        }
    }

    private static final class TypeParts {
        String typeName = "";
        Integer precision;
        Integer scale;
        final EnumSet<SqlDataType.TypeAttribute> attributes =
                EnumSet.noneOf(SqlDataType.TypeAttribute.class);
    }

    private static final class ExprSlice {
        final String text;

        ExprSlice(String text) {
            this.text = text == null ? "" : text;
        }
    }
}
