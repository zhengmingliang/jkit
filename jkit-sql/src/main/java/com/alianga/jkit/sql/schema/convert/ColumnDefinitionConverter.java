package com.alianga.jkit.sql.schema.convert;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.model.ColumnConstraint;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.model.SqlDataType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.AutoIncrementStrategy;
import com.alianga.jkit.sql.schema.rewrite.DefaultValueCoercer;

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
    public static String convert(ColumnDefinition column, SqlDialect source, SqlDialect target,
                                 SqlSchemaConvertOptions options, SqlDataTypeRegistry registry,
                                 ConversionReport.Builder report) {
        if (column == null) {
            return "";
        }
        if (column.tableConstraint()) {
            return convertTableConstraint(column, target, report);
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
        String typeText = registry.toDialect(afterUnsigned, target, precision, scale);
        AutoIncrementStrategy.Result auto = null;
        ColumnConstraint.AutoIncrement autoC = column.find(ColumnConstraint.AutoIncrement.class);
        if (autoC != null) {
            auto = AutoIncrementStrategy.apply(autoC, afterUnsigned, column.columnName(),
                    target, options, report);
            if (auto.overrideType() != null) {
                typeText = auto.overrideType();
            }
        }
        String rendered = render(column, typeText, afterUnsigned, auto, target, options, report);
        report.converted();
        return rendered;
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
                                 SqlDialect target, SqlSchemaConvertOptions options,
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
                    if (target != SqlDialect.MYSQL) {
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
            SqlExpr coerced = DefaultValueCoercer.coerce(def.expr(), from, canonical, target);
            String body = DefaultValueCoercer.render(coerced, def.rawText(), target);
            if (!body.isEmpty()) {
                sb.append(" DEFAULT ").append(body);
            }
        }
        if (auto != null && auto.clause() != null) {
            sb.append(' ').append(auto.clause());
        }
        if (pk) {
            sb.append(" PRIMARY KEY");
        }
        if (unique) {
            sb.append(" UNIQUE");
        }
        if (comment != null && target == SqlDialect.MYSQL) {
            sb.append(" COMMENT '").append(escape(comment.text())).append('\'');
        } else if (comment != null) {
            report.warn(ConversionWarning.Severity.INFO, column.columnName(),
                    "列 COMMENT 在 " + target + " 需改用 COMMENT ON COLUMN，已去掉内联注释");
        }
        if (target == SqlDialect.MYSQL && column.dataType().has(SqlDataType.TypeAttribute.UNSIGNED)
                && options.unsignedHandling() != SqlSchemaConvertOptions.UnsignedHandling.UPSIZE) {
            sb.append(" UNSIGNED");
        }
        return sb.toString();
    }

    private static void handleCharset(String column, ColumnConstraint c, SqlDialect target,
                                      SqlSchemaConvertOptions options,
                                      ConversionReport.Builder report) {
        if (supportsColumnCharset(target)) {
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

    private static String convertTableConstraint(ColumnDefinition column, SqlDialect target,
                                                 ConversionReport.Builder report) {
        String raw = column.rawText() == null ? "" : column.rawText().trim();
        String u = raw.toUpperCase(Locale.ROOT);
        if ((u.startsWith("UNIQUE KEY") || u.startsWith("UNIQUE INDEX")) && target != SqlDialect.MYSQL) {
            int paren = raw.indexOf('(');
            if (paren >= 0) {
                report.converted();
                return "UNIQUE " + raw.substring(paren);
            }
        }
        if (mysqlOnlyTableConstraint(raw) && !supportsMysqlIndex(target)) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "",
                    "已去掉 MySQL 表内索引（请手工 CREATE INDEX）: " + raw);
            report.unchanged();
            return "";
        }
        report.unchanged();
        return raw;
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

    private static String ident(String name, SqlDialect target) {
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
