package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 解析门面，对标 Druid {@code SQLUtils} 与 JSqlParser {@code CCJSqlParserUtil}
 * 的常用入口：解析、格式化、抽表列、改写。
 *
 * <p>解析器按线程复用，零第三方依赖，JDK 8+。</p>
 *
 * <pre>{@code
 * SqlStatement stmt = SQL.parse("SELECT id, name FROM users u WHERE u.age > 18");
 * SQL.tables(stmt);                 // [users]
 * SQL.format(stmt);
 * SQL.addLimit(stmt, 100);
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.1.0
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
     * @since 2.1.0
     */
    public static SqlStatement parse(String sql, SqlDialect dialect, SqlParseOptions options) {
        List<SqlStatement> all = parseAll(sql, dialect, options);
        if (all.isEmpty()) {
            throw new SqlParseException("empty SQL", 1, 1, "");
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
     * 解析分号分隔的多条语句（带选项）。
     *
     * @param sql SQL
     * @param dialect 方言
     * @param options 解析选项，null 视为默认
     * @return 语句列表，无语句时为空列表
     * @since 2.1.0
     */
    public static List<SqlStatement> parseAll(String sql, SqlDialect dialect, SqlParseOptions options) {
        if (sql == null || sql.trim().isEmpty()) {
            return Collections.emptyList();
        }
        SqlParser parser = PARSER.get();
        parser.reset(sql, dialect, options);
        return parser.parseAll();
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
     * 给 SELECT 补 LIMIT。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @return 原对象（就地修改）
     */
    public static SqlStatement addLimit(SqlStatement statement, long rowCount) {
        return SqlRewriter.addLimit(statement, rowCount, SqlDialect.MYSQL);
    }

    /**
     * 给 SELECT 补 LIMIT。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement addLimit(SqlStatement statement, long rowCount, SqlDialect dialect) {
        return SqlRewriter.addLimit(statement, rowCount, dialect);
    }

    /**
     * 解析谓词并 AND 到顶层 WHERE。
     *
     * @param statement 语句
     * @param predicateSql 谓词 SQL，如 {@code tenant_id = ?}
     * @return 原对象
     */
    public static SqlStatement andWhere(SqlStatement statement, String predicateSql) {
        if (predicateSql == null || predicateSql.trim().isEmpty()) {
            return statement;
        }
        SqlSelect tmp = (SqlSelect) parse("SELECT 1 WHERE " + predicateSql);
        return SqlRewriter.andWhere(statement, tmp.where());
    }

    /**
     * 替换物理表名。
     *
     * @param statement 语句
     * @param from 原简单名
     * @param to 新简单名
     * @return 原对象
     */
    public static SqlStatement replaceTable(SqlStatement statement, String from, String to) {
        return SqlRewriter.replaceTable(statement, from, to);
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
}
