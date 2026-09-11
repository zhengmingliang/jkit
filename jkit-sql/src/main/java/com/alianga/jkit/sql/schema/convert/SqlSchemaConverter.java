package com.alianga.jkit.sql.schema.convert;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.parse.SqlColumnDefinitionParser;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.FunctionAstRewriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 表结构跨方言转换门面。Phase 3：CREATE TABLE 列类型/约束 + 表级选项；
 * 其它语句先按目标方言 format（分页由现有 {@link SQL#format} 适配）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSchemaConverter {
    private SqlSchemaConverter() {
    }

    /**
     * @param sql 源 SQL
     * @param source 源方言
     * @param target 目标方言
     * @return 转换结果
     */
    public static ConversionResult convert(String sql, SqlDialect source, SqlDialect target) {
        return convert(sql, source, target, SqlSchemaConvertOptions.defaults());
    }

    /**
     * @param sql 源 SQL
     * @param source 源方言
     * @param target 目标方言
     * @param options 选项
     * @return 转换结果
     */
    public static ConversionResult convert(String sql, SqlDialect source, SqlDialect target,
                                           SqlSchemaConvertOptions options) {
        SqlDialect src = source == null ? SqlDialect.MYSQL : source;
        SqlDialect dst = target == null ? SqlDialect.MYSQL : target;
        SqlSchemaConvertOptions opt = options == null ? SqlSchemaConvertOptions.defaults() : options;
        if (sql == null || sql.trim().isEmpty()) {
            return new ConversionResult("", ConversionReport.empty());
        }
        if (src == dst) {
            return new ConversionResult(sql, ConversionReport.empty());
        }
        List<SqlStatement> stmts = SQL.parseAll(sql, src);
        ConversionReport.Builder report = new ConversionReport.Builder();
        List<SqlStatement> out = new ArrayList<SqlStatement>(stmts.size());
        for (int i = 0; i < stmts.size(); i++) {
            out.add(convertStatement(stmts.get(i), src, dst, opt, report));
        }
        ConversionReport built = report.build();
        if (built.hasSeverityAtLeast(opt.failOnSeverity())) {
            throw new SqlSchemaConversionException(
                    "conversion blocked by " + opt.failOnSeverity(), built);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append(';').append(' ');
            }
            sb.append(SQL.toSqlString(out.get(i), dst));
        }
        return new ConversionResult(sb.toString(), built);
    }

    /**
     * @param stmt 已解析语句（会 clone，不改入参）
     * @param source 源方言
     * @param target 目标方言
     * @param options 选项
     * @return 转换后的语句
     */
    public static SqlStatement convert(SqlStatement stmt, SqlDialect source, SqlDialect target,
                                       SqlSchemaConvertOptions options) {
        ConversionReport.Builder report = new ConversionReport.Builder();
        return convertStatement(stmt, source, target, options, report);
    }

    private static SqlStatement convertStatement(SqlStatement stmt, SqlDialect source,
                                                 SqlDialect target, SqlSchemaConvertOptions options,
                                                 ConversionReport.Builder report) {
        if (stmt == null) {
            return null;
        }
        SqlStatement copy = SQL.clone(stmt);
        if (copy instanceof SqlDdlStatement) {
            convertDdl((SqlDdlStatement) copy, source, target, options, report);
        }
        FunctionAstRewriter.rewrite(copy, source, target, report);
        return copy;
    }

    private static void convertDdl(SqlDdlStatement ddl, SqlDialect source, SqlDialect target,
                                   SqlSchemaConvertOptions options, ConversionReport.Builder report) {
        String objectType = ddl.objectType();
        if (objectType == null || !"TABLE".equalsIgnoreCase(objectType)) {
            return;
        }
        List<ColumnDefinition> cols = SqlColumnDefinitionParser.fromDdl(ddl, source);
        if (!cols.isEmpty()) {
            List<String> rewritten = ddl.columnDefinitions();
            rewritten.clear();
            SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();
            for (int i = 0; i < cols.size(); i++) {
                rewritten.add(ColumnDefinitionConverter.convert(
                        cols.get(i), source, target, options, registry, report));
            }
        }
        if (options.stripDialectOptions() && !supportsMysqlTableOptions(target)) {
            if (ddl.engine() != null) {
                report.warn(ConversionWarning.Severity.INFO, tableName(ddl),
                        "已去掉 ENGINE=" + ddl.engine());
                ddl.setEngine(null);
            }
            if (ddl.charset() != null) {
                report.warn(ConversionWarning.Severity.INFO, tableName(ddl),
                        "已去掉 CHARSET=" + ddl.charset());
                ddl.setCharset(null);
            }
            if (ddl.collate() != null) {
                report.warn(ConversionWarning.Severity.INFO, tableName(ddl),
                        "已去掉 COLLATE=" + ddl.collate());
                ddl.setCollate(null);
            }
        }
    }

    private static boolean supportsMysqlTableOptions(SqlDialect dialect) {
        return dialect == SqlDialect.MYSQL;
    }

    private static String tableName(SqlDdlStatement ddl) {
        if (ddl.names().isEmpty() || ddl.names().get(0) == null) {
            return "";
        }
        List<String> n = ddl.names().get(0).names();
        if (n == null || n.isEmpty()) {
            return "";
        }
        return n.get(n.size() - 1);
    }
}
