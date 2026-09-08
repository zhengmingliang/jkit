package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 解析门面，对标 Druid {@code SQLUtils} 与 JSqlParser {@code CCJSqlParserUtil}
 * 的常用入口：解析、格式化、抽表列、改写、{@link SqlBuilder} 构建。
 *
 * <p>解析器按线程复用，零第三方依赖，JDK 8+。</p>
 *
 * <pre>{@code
 * SqlStatement stmt = SQL.parse("SELECT id, name FROM users u WHERE u.age > 18");
 * SQL.tables(stmt);                 // [users]
 * SQL.format(stmt);
 * SQL.addLimit(stmt, 100);
 * SQL.setPage(stmt, 2, 20, SqlDialect.MYSQL);
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SQL {
    private static final ThreadLocal<SqlParser> PARSER = new ThreadLocal<SqlParser>() {
        /**
         * {@inheritDoc}
         */
        @Override
        protected SqlParser initialValue() {
            return new SqlParser();
        }
    };

    private SQL() {
    }

    /**
     * @return 新的 SELECT {@link SqlBuilder}（默认 {@code SELECT *}）
     * @since 2.0.1
     */
    public static SqlBuilder builder() {
        return SqlBuilder.select();
    }

    /**
     * 多谓词 AND（AST 级，无字符串拼接）。
     *
     * @param predicates 谓词
     * @return 合并表达式
     * @since 2.0.1
     */
    public static SqlExpr and(SqlExpr... predicates) {
        return SqlBuilder.andAll(predicates);
    }

    /**
     * 多谓词 OR。
     *
     * @param predicates 谓词
     * @return 合并表达式
     * @since 2.0.1
     */
    public static SqlExpr or(SqlExpr... predicates) {
        return SqlBuilder.orAll(predicates);
    }

    /**
     * 分号拼接多条语句。
     *
     * @param statements 语句列表
     * @return 紧凑 SQL
     * @since 2.0.1
     */
    public static String concat(List<SqlStatement> statements) {
        return SqlBuilder.concatStatements(statements, SqlDialect.MYSQL);
    }

    /**
     * 分号拼接多条语句。
     *
     * @param statements 语句列表
     * @param dialect 方言
     * @return 紧凑 SQL
     * @since 2.0.1
     */
    public static String concat(List<SqlStatement> statements, SqlDialect dialect) {
        return SqlBuilder.concatStatements(statements, dialect);
    }

    /**
     * 解析一条语句，方言默认 MySQL（含 GBase / MariaDB）。
     *
     * @param sql SQL
     * @return 语句
     */
    public static SqlStatement parse(String sql) {
        return parse(sql, SqlDialect.MYSQL);
    }

    /**
     * 解析一条语句。
     *
     * @param sql SQL
     * @param dialect 方言
     * @return 第一条语句
     */
    public static SqlStatement parse(String sql, SqlDialect dialect) {
        return parse(sql, dialect, SqlParseOptions.defaults());
    }

    /**
     * 解析一条语句（带选项）。
     *
     * @param sql SQL
     * @param dialect 方言
     * @param options 解析选项，null 视为默认
     * @return 第一条语句
     * @since 2.0.1
     */
    public static SqlStatement parse(String sql, SqlDialect dialect, SqlParseOptions options) {
        List<SqlStatement> all = parseAll(sql, dialect, options);
        if (all.isEmpty()) {
            // 仅注释 / 空白：不硬失败，返回 OTHER 空语句（语料 comment-only 边）
            SqlSimpleStatement empty = new SqlSimpleStatement();
            empty.setStatementType(SqlStatementType.OTHER);
            empty.setText("");
            return empty;
        }
        return all.get(0);
    }

    /**
     * 解析分号分隔的多条语句。
     *
     * @param sql SQL
     * @return 语句列表
     */
    public static List<SqlStatement> parseAll(String sql) {
        return parseAll(sql, SqlDialect.MYSQL);
    }

    /**
     * 解析分号分隔的多条语句。
     *
     * @param sql SQL
     * @param dialect 方言
     * @return 语句列表，无语句时为空列表
     */
    public static List<SqlStatement> parseAll(String sql, SqlDialect dialect) {
        return parseAll(sql, dialect, SqlParseOptions.defaults());
    }

    /**
     * 解析分号分隔的多条语句（带选项；一条失败则整批抛错）。
     *
     * @param sql SQL
     * @param dialect 方言
     * @param options 解析选项，null 视为默认
     * @return 语句列表，无语句时为空列表
     * @since 2.0.1
     */
    public static List<SqlStatement> parseAll(String sql, SqlDialect dialect, SqlParseOptions options) {
        return parseAll(sql, dialect, options, false);
    }

    /**
     * 解析分号分隔的多条语句；可选容错（审计场景）。
     *
     * <p>{@code tolerant=true} 时单条失败不抛错，改为 {@link com.alianga.jkit.sql.ast.SqlSimpleStatement}
     * 占位（{@code type=OTHER}，{@link com.alianga.jkit.sql.ast.SqlSimpleStatement#parseError()} 有值，
     * {@code text} 为失败片段），并继续下一条。</p>
     *
     * @param sql SQL
     * @param dialect 方言
     * @param tolerant 是否容错
     * @return 语句列表，无语句时为空列表
     * @since 2.0.1
     */
    public static List<SqlStatement> parseAll(String sql, SqlDialect dialect, boolean tolerant) {
        return parseAll(sql, dialect, SqlParseOptions.defaults(), tolerant);
    }

    /**
     * 解析分号分隔的多条语句（带选项与容错开关）。
     *
     * @param sql SQL
     * @param dialect 方言
     * @param options 解析选项，null 视为默认
     * @param tolerant 是否容错
     * @return 语句列表，无语句时为空列表
     * @since 2.0.1
     */
    public static List<SqlStatement> parseAll(String sql, SqlDialect dialect,
            SqlParseOptions options, boolean tolerant) {
        if (sql == null || sql.trim().isEmpty()) {
            return Collections.emptyList();
        }
        SqlParser parser = PARSER.get();
        parser.reset(sql, dialect, options);
        return parser.parseAll(tolerant);
    }

    /**
     * 格式化（换行缩进）。
     *
     * @param sql SQL
     * @return 格式化文本
     */
    public static String format(String sql) {
        return format(parse(sql), SqlDialect.MYSQL, true);
    }

    /**
     * 格式化语句。
     *
     * @param statement 语句
     * @return 格式化文本
     */
    public static String format(SqlStatement statement) {
        return format(statement, SqlDialect.MYSQL, true);
    }

    /**
     * 格式化语句。
     *
     * @param statement 语句
     * @param dialect 方言
     * @return 格式化文本
     */
    public static String format(SqlStatement statement, SqlDialect dialect) {
        return format(statement, dialect, true);
    }

    /**
     * 格式化语句。
     *
     * @param statement 语句
     * @param dialect 方言
     * @param pretty 是否换行缩进
     * @return SQL 文本
     */
    public static String format(SqlStatement statement, SqlDialect dialect, boolean pretty) {
        return new SqlFormatter(pretty, dialect).format(statement);
    }

    /**
     * 紧凑输出（单行）。
     *
     * @param statement 语句
     * @return SQL 文本
     */
    public static String toSqlString(SqlStatement statement) {
        return format(statement, SqlDialect.MYSQL, false);
    }

    /**
     * 紧凑输出。
     *
     * @param statement 语句
     * @param dialect 方言
     * @return SQL 文本
     */
    public static String toSqlString(SqlStatement statement, SqlDialect dialect) {
        return format(statement, dialect, false);
    }

    /**
     * 抽取表名，对标 JSqlParser {@code TablesNamesFinder}。
     *
     * @param sql SQL
     * @return 表名
     */
    public static List<String> tables(String sql) {
        return tables(parse(sql));
    }

    /**
     * 抽取表名。
     *
     * @param statement 语句
     * @return 表名
     */
    public static List<String> tables(SqlStatement statement) {
        return SqlSchemaStat.of(statement).tableNames();
    }

    /**
     * 表/列统计，对标 Druid {@code SchemaStatVisitor}。
     *
     * @param sql SQL
     * @return 统计
     */
    public static SqlSchemaStat stat(String sql) {
        return SqlSchemaStat.of(parse(sql));
    }

    /**
     * 表/列统计。
     *
     * @param statement 语句
     * @return 统计
     */
    public static SqlSchemaStat stat(SqlStatement statement) {
        return SqlSchemaStat.of(statement);
    }

    /**
     * 是否只读语句。
     *
     * @param statement 语句
     * @return 只读
     */
    public static boolean isReadOnly(SqlStatement statement) {
        return statement != null && statement.isReadOnly();
    }

    /**
     * 给 SELECT 补 LIMIT（先 {@link #clone(SqlStatement) 深拷贝} 再改，不污染原树）。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @return 带 LIMIT 的新语句（或非 SELECT 时为拷贝）
     */
    public static SqlStatement addLimit(SqlStatement statement, long rowCount) {
        return addLimit(statement, rowCount, SqlDialect.MYSQL);
    }

    /**
     * 给 SELECT 补 LIMIT（先深拷贝再改）。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @param dialect 方言
     * @return 带 LIMIT 的新语句
     */
    public static SqlStatement addLimit(SqlStatement statement, long rowCount, SqlDialect dialect) {
        SqlStatement copy = clone(statement, dialect);
        return SqlRewriter.addLimit(copy, rowCount, dialect);
    }

    /**
     * 读取 SELECT 行数上限（LIMIT rowCount 或 TOP）。
     *
     * @param statement 语句
     * @return 行数，无或非数字字面量时 null
     * @since 2.0.1
     */
    public static Long getLimit(SqlStatement statement) {
        return SqlRewriter.getLimit(statement);
    }

    /**
     * 读取 SELECT OFFSET。
     *
     * @param statement 语句
     * @return 偏移，无则 null
     * @since 2.0.1
     */
    public static Long getOffset(SqlStatement statement) {
        return SqlRewriter.getOffset(statement);
    }

    /**
     * 设置/替换行数上限（先深拷贝再改）。负值清空分页。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @return 新语句
     * @since 2.0.1
     */
    public static SqlStatement setLimit(SqlStatement statement, long rowCount) {
        return setLimit(statement, rowCount, SqlDialect.MYSQL);
    }

    /**
     * 设置/替换行数上限（先深拷贝再改）。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @param dialect 方言
     * @return 新语句
     * @since 2.0.1
     */
    public static SqlStatement setLimit(SqlStatement statement, long rowCount, SqlDialect dialect) {
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlStatement copy = clone(statement, d);
        return SqlRewriter.setLimit(copy, rowCount, d);
    }

    /**
     * 设置/替换 OFFSET（先深拷贝再改）。
     *
     * @param statement 语句
     * @param offset 偏移
     * @return 新语句
     * @since 2.0.1
     */
    public static SqlStatement setOffset(SqlStatement statement, long offset) {
        return setOffset(statement, offset, SqlDialect.MYSQL);
    }

    /**
     * 设置/替换 OFFSET（先深拷贝再改）。
     *
     * @param statement 语句
     * @param offset 偏移
     * @param dialect 方言
     * @return 新语句
     * @since 2.0.1
     */
    public static SqlStatement setOffset(SqlStatement statement, long offset, SqlDialect dialect) {
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlStatement copy = clone(statement, d);
        return SqlRewriter.setOffset(copy, offset, d);
    }

    /**
     * 按页码设置分页（先深拷贝再改）。{@code pageNo} 从 1 起。
     *
     * @param statement 语句
     * @param pageNo 页码
     * @param pageSize 页大小
     * @return 新语句
     * @since 2.0.1
     */
    public static SqlStatement setPage(SqlStatement statement, long pageNo, long pageSize) {
        return setPage(statement, pageNo, pageSize, SqlDialect.MYSQL);
    }

    /**
     * 按页码设置分页（先深拷贝再改）。方言感知：MySQL/PG {@code LIMIT/OFFSET}，
     * SQL Server {@code TOP} 或 {@code OFFSET FETCH}，Oracle {@code FETCH FIRST}。
     *
     * @param statement 语句
     * @param pageNo 页码（从 1 起）
     * @param pageSize 页大小
     * @param dialect 方言
     * @return 新语句
     * @since 2.0.1
     */
    public static SqlStatement setPage(SqlStatement statement, long pageNo, long pageSize,
            SqlDialect dialect) {
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlStatement copy = clone(statement, d);
        return SqlRewriter.setPage(copy, pageNo, pageSize, d);
    }

    /**
     * 解析谓词并 AND 到顶层 WHERE（先 {@link #clone(SqlStatement) 深拷贝} 再改，不污染原树）。
     *
     * <p><b>破坏性变更（2.0.1）</b>：旧实现就地修改并返回原对象；现与 {@link #addLimit}/{@link #setPage}
     * 一致，返回新语句，原 AST 不变。调用方需使用返回值。
     *
     * @param statement 语句
     * @param predicateSql 谓词 SQL，如 {@code tenant_id = ?}
     * @return 带新 WHERE 的拷贝；谓词为空时返回原对象
     */
    public static SqlStatement andWhere(SqlStatement statement, String predicateSql) {
        if (predicateSql == null || predicateSql.trim().isEmpty()) {
            return statement;
        }
        SqlSelect tmp = (SqlSelect) parse("SELECT 1 WHERE " + predicateSql);
        SqlStatement copy = clone(statement);
        return SqlRewriter.andWhere(copy, tmp.where());
    }

    /**
     * 替换物理表名（先深拷贝再改，不污染原树）。
     *
     * <p><b>破坏性变更（2.0.1）</b>：旧实现就地修改；现返回新语句，原 AST 不变。
     *
     * @param statement 语句
     * @param from 原简单名
     * @param to 新简单名
     * @return 替换后的拷贝
     */
    public static SqlStatement replaceTable(SqlStatement statement, String from, String to) {
        SqlStatement copy = clone(statement);
        return SqlRewriter.replaceTable(copy, from, to);
    }

    /**
     * 替换列名（忽略大小写），对称 {@link #replaceTable}（先深拷贝再改）。
     *
     * <p><b>破坏性变更（2.0.1）</b>：旧实现就地修改；现返回新语句，原 AST 不变。
     *
     * @param statement 语句
     * @param from 原列简单名
     * @param to 新列简单名
     * @return 替换后的拷贝
     * @since 2.0.1
     */
    public static SqlStatement replaceColumn(SqlStatement statement, String from, String to) {
        SqlStatement copy = clone(statement);
        return SqlRewriter.replaceColumn(copy, from, to);
    }

    /**
     * 抽取绑定参数，顺序与出现顺序一致。{@code ?} 记为 {@code "?"}，{@code :name} 记为 {@code ":name"}。
     *
     * @param sql SQL
     * @return 参数列表
     */
    public static List<String> parameters(String sql) {
        return parameters(parse(sql));
    }

    /**
     * 抽取绑定参数。
     *
     * @param statement 语句
     * @return 参数列表
     */
    public static List<String> parameters(SqlStatement statement) {
        final List<String> list = new ArrayList<String>(4);
        if (statement == null) {
            return list;
        }
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlLiteral) {
                    SqlLiteral lit = (SqlLiteral) node;
                    if (lit.kind() == SqlLiteral.Kind.BIND) {
                        list.add("?");
                    } else if (lit.kind() == SqlLiteral.Kind.NAMED_BIND) {
                        String name = lit.name();
                        list.add(name == null ? lit.value() : ":" + name);
                    }
                }
                return true;
            }
        });
        return list;
    }

    /**
     * 深拷贝语句（format → parse 往返；方言默认 MySQL）。
     *
     * @param statement 语句
     * @return 新 AST，null 入参返回 null
     * @since 2.0.1
     */
    public static SqlStatement clone(SqlStatement statement) {
        return clone(statement, SqlDialect.MYSQL);
    }

    /**
     * 深拷贝语句（format → parse 往返）。
     *
     * @param statement 语句
     * @param dialect 方言
     * @return 新 AST，null 入参返回 null
     * @since 2.0.1
     */
    public static SqlStatement clone(SqlStatement statement, SqlDialect dialect) {
        if (statement == null) {
            return null;
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        return parse(toSqlString(statement, d), d);
    }

    /**
     * 参数化归一：字面量变为 {@code ?}，便于 SQL 指纹 / 去重（对标 Druid ParameterizedOutputVisitor）。
     *
     * @param sql SQL
     * @return 参数化后的紧凑 SQL
     * @since 2.0.1
     */
    public static String parameterize(String sql) {
        return parameterize(sql, SqlDialect.MYSQL);
    }

    /**
     * 参数化归一。
     *
     * @param sql SQL
     * @param dialect 方言
     * @return 参数化后的紧凑 SQL
     * @since 2.0.1
     */
    public static String parameterize(String sql, SqlDialect dialect) {
        return SqlParameterizer.parameterize(parse(sql, dialect), dialect);
    }

    /**
     * 参数化已解析语句（不修改入参）。
     *
     * @param statement 语句
     * @return 参数化后的紧凑 SQL
     * @since 2.0.1
     */
    public static String parameterize(SqlStatement statement) {
        return SqlParameterizer.parameterize(statement, SqlDialect.MYSQL);
    }

    /**
     * 导出字面量参数值（{@code 'a'} / {@code 1}），与 {@link #parameters}（绑定占位符）不同。
     *
     * @param sql SQL
     * @return 值列表
     * @since 2.0.1
     */
    public static List<Object> exportParameterValues(String sql) {
        return exportParameterValues(parse(sql));
    }

    /**
     * 导出字面量参数值。
     *
     * @param statement 语句
     * @return 值列表（不修改 AST）
     * @since 2.0.1
     */
    public static List<Object> exportParameterValues(SqlStatement statement) {
        return SqlParameterizer.exportParameterValues(statement);
    }

    /**
     * WallFilter 子集检测（默认不拦截解析；仅显式调用）。
     *
     * @param sql SQL
     * @return 检测结果
     * @since 2.0.1
     */
    public static SqlWallResult wall(String sql) {
        return wall(sql, SqlDialect.MYSQL);
    }

    /**
     * WallFilter 子集检测。
     *
     * @param sql SQL
     * @param dialect 方言
     * @return 检测结果
     * @since 2.0.1
     */
    public static SqlWallResult wall(String sql, SqlDialect dialect) {
        return SqlWall.check(sql, dialect);
    }

    /**
     * 对已解析语句做 Wall AST 侧检查。
     *
     * @param statement 语句
     * @return 检测结果
     * @since 2.0.1
     */
    public static SqlWallResult wall(SqlStatement statement) {
        return SqlWall.check(statement);
    }

    /**
     * 字面量常量折叠（EvalVisitor 子集）。
     *
     * @param expr 表达式
     * @return 求值结果；不可求值时 {@code null}
     * @since 2.0.1
     */
    public static Object eval(SqlExpr expr) {
        return SqlEval.eval(expr);
    }
}
