package com.alianga.jkit.sql.schema.convert;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.model.ColumnConstraint;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.model.SqlDataType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.AutoIncrementStrategy;
import com.alianga.jkit.sql.schema.rewrite.DefaultValueCoercer;
import com.alianga.jkit.sql.schema.rewrite.FunctionAstRewriter;

import java.util.List;
import java.util.Locale;

/**
 * 单列：canonical 类型 + 自增/默认值/UNSIGNED/字符集策略 → 目标方言列定义文本。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ColumnDefinitionConverter {
    private ColumnDefinitionConverter() {
    }

    /**
     * @param column 源列
     * @param source 源方言
     * @param target 目标方言
     * @param options 选项
     * @param registry 类型表
     * @param report 报告
     * @return 目标列定义文本
     */
    public static String convert(ColumnDefinition column, SqlDialectSpec source, SqlDialectSpec target,
                                 SqlSchemaConvertOptions options, SqlDataTypeRegistry registry,
                                 ConversionReport.Builder report) {
        return convert(column, source, target, options, registry, report, "");
    }

    /**
     * @param column 源列
     * @param source 源方言
     * @param target 目标方言
     * @param options 选项
     * @param registry 类型表
     * @param report 报告
     * @param tableName 表名（附录 CREATE INDEX / SEQUENCE）
     * @return 目标列定义文本
     */
    public static String convert(ColumnDefinition column, SqlDialectSpec source, SqlDialectSpec target,
                                 SqlSchemaConvertOptions options, SqlDataTypeRegistry registry,
                                 ConversionReport.Builder report, String tableName) {
        if (column == null) {
            return "";
        }
        if (column.tableConstraint()) {
            return convertTableConstraint(column, target, report, tableName);
        }
        CanonicalType canonical = registry.fromDialect(column.dataType(), source);
        if (canonical == CanonicalType.UNKNOWN) {
            report.unchanged();
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, column.columnName(),
                    "无法识别类型 " + column.dataType().rawTypeName() + "，保留原文");
            return column.rawText();
        }
        CanonicalType afterUnsigned = applyUnsigned(canonical, column, options, report);
        Integer precision = column.dataType().precision();
        Integer scale = column.dataType().scale();
        afterUnsigned = promoteVarchar(afterUnsigned, precision, target, options);
        if (afterUnsigned == CanonicalType.TEXT) {
            precision = null;
            scale = null;
        }
        String typeText = registry.toDialect(afterUnsigned, target, precision, scale);
        AutoIncrementStrategy.Result auto = null;
        ColumnConstraint.AutoIncrement autoC = column.find(ColumnConstraint.AutoIncrement.class);
        if (autoC != null) {
            auto = AutoIncrementStrategy.apply(autoC, afterUnsigned, column.columnName(),
                    target, options, report);
            if (auto.overrideType() != null) {
                typeText = auto.overrideType();
            }
            if (auto.dropped() && options.generateOracleSequence()
                    && target.typeFamily() == SqlDialect.ORACLE
                    && tableName != null && !tableName.isEmpty()) {
                report.extraSql(oracleSequenceSql(tableName, column.columnName()));
            }
        }
        String rendered = render(column, typeText, afterUnsigned, auto, source, target, options, report);
        report.converted();
        return rendered;
    }

    private static CanonicalType promoteVarchar(CanonicalType canonical, Integer precision,
                                                SqlDialectSpec target, SqlSchemaConvertOptions options) {
        if (!options.promoteLongVarchar() || canonical != CanonicalType.VARCHAR) {
            return canonical;
        }
        int limit = varcharTextLimit(target.typeFamily());
        if (limit <= 0) {
            return canonical;
        }
        int p = precision == null ? 0 : precision.intValue();
        if (p == 0 || p > limit) {
            return CanonicalType.TEXT;
        }
        return canonical;
    }

    private static int varcharTextLimit(SqlDialect target) {
        switch (target) {
            case MYSQL:
            case ORACLE:
            case ORACLE12:
            case DAMENG:
                return 4000;
            case SQLSERVER:
                return 8000;
            default:
                return 0;
        }
    }

    private static String oracleSequenceSql(String table, String column) {
        String seq = table + "_" + column + "_seq";
        String trg = table + "_" + column + "_bi";
        return "CREATE SEQUENCE " + seq
                + "; CREATE OR REPLACE TRIGGER " + trg
                + " BEFORE INSERT ON " + table
                + " FOR EACH ROW WHEN (NEW." + column + " IS NULL) BEGIN SELECT "
                + seq + ".NEXTVAL INTO :NEW." + column + " FROM DUAL; END;";
    }

    private static CanonicalType applyUnsigned(CanonicalType canonical, ColumnDefinition column,
                                               SqlSchemaConvertOptions options,
                                               ConversionReport.Builder report) {
        if (!column.dataType().has(SqlDataType.TypeAttribute.UNSIGNED)
                || !canonical.integerFamily()) {
            return canonical;
        }
        SqlSchemaConvertOptions.UnsignedHandling handling = options.unsignedHandling();
        String loc = column.columnName();
        if (handling == SqlSchemaConvertOptions.UnsignedHandling.DROP_AND_WARN) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, loc, "已丢弃 UNSIGNED");
            return canonical;
        }
        if (handling == SqlSchemaConvertOptions.UnsignedHandling.ADD_CHECK) {
            report.warn(ConversionWarning.Severity.INFO, loc,
                    "UNSIGNED 未升档，建议 CHECK (" + loc + " >= 0)");
            return canonical;
        }
        CanonicalType up = upsize(canonical);
        if (up == canonical && canonical == CanonicalType.BIGINT) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, loc,
                    "BIGINT UNSIGNED 无法再升档，正数上界可能溢出");
            return canonical;
        }
        report.warn(ConversionWarning.Severity.INFO, loc,
                "UNSIGNED " + canonical + " 升档为 " + up);
        return up;
    }

    private static CanonicalType upsize(CanonicalType type) {
        switch (type) {
            case TINYINT:
                return CanonicalType.SMALLINT;
            case SMALLINT:
            case YEAR:
                return CanonicalType.INT;
            case MEDIUMINT:
                return CanonicalType.INT;
            case INT:
                return CanonicalType.BIGINT;
            default:
                return type;
        }
    }

    private static String render(ColumnDefinition column, String typeText, CanonicalType canonical,
                                 AutoIncrementStrategy.Result auto,
                                 SqlDialectSpec source, SqlDialectSpec target, SqlSchemaConvertOptions options,
                                 ConversionReport.Builder report) {
        StringBuilder sb = new StringBuilder();
        sb.append(ident(column.columnName(), target));
        sb.append(' ');
        sb.append(typeText);
        List<ColumnConstraint> constraints = column.constraints();
        boolean notNull = false;
        boolean nullable = false;
        boolean pk = false;
        boolean unique = false;
        ColumnConstraint.DefaultValue def = null;
        ColumnConstraint.Comment comment = null;
        for (int i = 0; i < constraints.size(); i++) {
            ColumnConstraint c = constraints.get(i);
            switch (c.kind()) {
                case NOT_NULL:
                    notNull = true;
                    break;
                case NULLABLE:
                    nullable = true;
                    break;
                case INLINE_PRIMARY_KEY:
                    pk = true;
                    break;
                case INLINE_UNIQUE:
                    unique = true;
                    break;
                case DEFAULT_VALUE:
                    def = (ColumnConstraint.DefaultValue) c;
                    break;
                case COMMENT:
                    comment = (ColumnConstraint.Comment) c;
                    break;
                case CHARACTER_SET:
                case COLLATION:
                    handleCharset(column.columnName(), c, target, options, report);
                    break;
                case ON_UPDATE:
                    if (target.typeFamily() != SqlDialect.MYSQL) {
                        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, column.columnName(),
                                "ON UPDATE 在 " + target + " 无列级等价语法，已去掉");
                    }
                    break;
                case AUTO_INCREMENT:
                    break;
                default:
                    break;
            }
        }
        if (notNull) {
            sb.append(" NOT NULL");
        } else if (nullable) {
            sb.append(" NULL");
        }
        if (def != null) {
            CanonicalType from = canonical;
            SqlExpr rewritten = FunctionAstRewriter.rewriteExpr(def.expr(), source, target, report);
            SqlExpr coerced = DefaultValueCoercer.coerce(rewritten, from, canonical, target);
            String body = DefaultValueCoercer.render(coerced, def.rawText(), target);
            if (!body.isEmpty()) {
                sb.append(" DEFAULT ").append(body);
            }
        }
        if (auto != null && auto.clause() != null && !auto.afterPrimaryKey()) {
            sb.append(' ').append(auto.clause());
        }
        if (pk) {
            sb.append(" PRIMARY KEY");
            if (auto != null && auto.clause() != null && auto.afterPrimaryKey()) {
                sb.append(' ').append(auto.clause());
            }
        } else if (auto != null && auto.clause() != null && auto.afterPrimaryKey()) {
            report.warn(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED, column.columnName(),
                    "该方言的自增子句必须排在 PRIMARY KEY 之后，该列无主键约束，已去掉");
        }
        if (unique) {
            sb.append(" UNIQUE");
        }
        if (comment != null && target.typeFamily() == SqlDialect.MYSQL) {
            sb.append(" COMMENT '").append(escape(comment.text())).append('\'');
        } else if (comment != null) {
            report.warn(ConversionWarning.Severity.INFO, column.columnName(),
                    "列 COMMENT 在 " + target + " 需改用 COMMENT ON COLUMN，已去掉内联注释");
        }
        if (target.typeFamily() == SqlDialect.MYSQL && column.dataType().has(SqlDataType.TypeAttribute.UNSIGNED)
                && options.unsignedHandling() != SqlSchemaConvertOptions.UnsignedHandling.UPSIZE) {
            sb.append(" UNSIGNED");
        }
        return sb.toString();
    }

    private static void handleCharset(String column, ColumnConstraint c, SqlDialectSpec target,
                                      SqlSchemaConvertOptions options,
                                      ConversionReport.Builder report) {
        if (supportsColumnCharset(target.typeFamily())) {
            return;
        }
        if (options.stripDialectOptions()) {
            report.warn(ConversionWarning.Severity.INFO, column,
                    "已去掉列级 " + (c.kind() == ColumnConstraint.Kind.CHARACTER_SET
                            ? "CHARACTER SET" : "COLLATE"));
        } else {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, column,
                    "目标方言无列级字符集/排序规则，原文未写入");
        }
    }

    private static boolean supportsColumnCharset(SqlDialect dialect) {
        return dialect == SqlDialect.MYSQL || dialect == SqlDialect.HIVE
                || dialect == SqlDialect.CLICKHOUSE;
    }

    private static String convertTableConstraint(ColumnDefinition column, SqlDialectSpec target,
                                                 ConversionReport.Builder report, String tableName) {
        String raw = column.rawText() == null ? "" : column.rawText().trim();
        String u = raw.toUpperCase(Locale.ROOT);
        if ((u.startsWith("UNIQUE KEY") || u.startsWith("UNIQUE INDEX"))
                && target.typeFamily() != SqlDialect.MYSQL) {
            int paren = raw.indexOf('(');
            if (paren >= 0) {
                report.converted();
                return "UNIQUE " + raw.substring(paren);
            }
        }
        if (mysqlOnlyTableConstraint(raw) && !supportsMysqlIndex(target.typeFamily())) {
            String idx = toCreateIndex(raw, tableName);
            if (idx != null) {
                report.extraSql(idx);
                report.warn(ConversionWarning.Severity.INFO, tableName,
                        "表内 KEY 已改为附录: " + idx);
            } else {
                report.warn(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED, tableName == null ? "" : tableName,
                        "FULLTEXT/SPATIAL 无法自动转为目标方言索引，已去掉: " + raw);
            }
            report.unchanged();
            return "";
        }
        report.unchanged();
        return raw;
    }

    static String toCreateIndex(String raw, String tableName) {
        if (tableName == null || tableName.isEmpty() || raw == null) {
            return null;
        }
        String t = raw.trim();
        String u = t.toUpperCase(Locale.ROOT);
        int start = 0;
        if (u.startsWith("FULLTEXT") || u.startsWith("SPATIAL")) {
            return null;
        }
        if (u.startsWith("KEY ")) {
            start = 4;
        } else if (u.startsWith("INDEX ")) {
            start = 6;
        } else {
            return null;
        }
        String rest = t.substring(start).trim();
        int paren = rest.indexOf('(');
        if (paren < 0) {
            return null;
        }
        String namePart = rest.substring(0, paren).trim();
        String cols = rest.substring(paren);
        String idxName = namePart;
        int sp = namePart.indexOf(' ');
        if (sp > 0) {
            idxName = namePart.substring(0, sp);
        }
        if (idxName.isEmpty() || idxName.startsWith("(")) {
            idxName = tableName + "_idx";
        }
        return "CREATE INDEX " + idxName + " ON " + tableName + " " + cols;
    }

    private static boolean supportsMysqlIndex(SqlDialect dialect) {
        return dialect == SqlDialect.MYSQL;
    }

    private static boolean mysqlOnlyTableConstraint(String raw) {
        if (raw == null) {
            return false;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return u.startsWith("KEY ") || u.startsWith("INDEX ")
                || u.startsWith("FULLTEXT") || u.startsWith("SPATIAL");
    }

    private static String ident(String name, SqlDialectSpec target) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (simpleIdent(name)) {
            return name;
        }
        return target.quoteIdent(name);
    }

    private static boolean simpleIdent(String name) {
        char c0 = name.charAt(0);
        if (!(c0 == '_' || (c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z'))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("'", "''");
    }
}
