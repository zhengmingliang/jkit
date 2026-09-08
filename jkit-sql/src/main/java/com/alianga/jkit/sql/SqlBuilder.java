package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlWithItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 快速构建 / 拼接 SQL AST（零字符串拼接黑客），最终经 {@link SQL#format} 回写。
 *
 * <pre>{@code
 * String sql = SqlBuilder.select("id", "name")
 *         .from("users")
 *         .where("status = 1")
 *         .and("age > 18")
 *         .leftJoin("orders", "users.id = orders.uid")
 *         .rightJoin("depts", "users.dept = depts.id")
 *         .groupBy("users.id")
 *         .having("count(1) > 1")
 *         .orderBy("id")
 *         .limit(10)
 *         .toSql();
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlBuilder {
    private enum Kind {
        SELECT,
        INSERT,
        UPDATE,
        DELETE
    }

    private final Kind kind;
    private SqlDialect dialect = SqlDialect.MYSQL;

    private final List<SqlSelectItem> selectItems = new ArrayList<SqlSelectItem>(4);
    private SqlTable table;
    private String tableAlias;
    private SqlExpr where;
    private final List<SqlExpr> groupBy = new ArrayList<SqlExpr>(2);
    private SqlExpr having;
    private SqlTableSource fromSource;
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private Long limitRows;
    private Long offsetRows;
    private boolean distinct;
    private final List<SqlWithItem> withItems = new ArrayList<SqlWithItem>(2);
    private final List<String> unionOps = new ArrayList<String>(2);
    private final List<SqlSelect> unionSelects = new ArrayList<SqlSelect>(2);

    private final List<SqlIdentifier> insertColumns = new ArrayList<SqlIdentifier>(4);
    private final List<SqlExpr> insertValues = new ArrayList<SqlExpr>(4);
    private final List<SqlBinaryExpr> assignments = new ArrayList<SqlBinaryExpr>(4);

    private SqlBuilder(Kind kind) {
        this.kind = kind;
    }

    /**
     * @return SELECT 构建器（默认 {@code SELECT *}）
     */
    public static SqlBuilder select() {
        SqlBuilder b = new SqlBuilder(Kind.SELECT);
        b.selectItems.add(item(new SqlAllColumns()));
        return b;
    }

    /**
     * @param columns 列名（支持 {@code t.col}；{@code *} 表示全列）
     * @return SELECT 构建器
     */
    public static SqlBuilder select(String... columns) {
        SqlBuilder b = new SqlBuilder(Kind.SELECT);
        if (columns == null || columns.length == 0) {
            b.selectItems.add(item(new SqlAllColumns()));
            return b;
        }
        for (int i = 0; i < columns.length; i++) {
            b.selectItems.add(item(columnExpr(columns[i])));
        }
        return b;
    }

    /**
     * @param exprs SELECT 列表表达式
     * @return SELECT 构建器
     */
    public static SqlBuilder select(SqlExpr... exprs) {
        SqlBuilder b = new SqlBuilder(Kind.SELECT);
        if (exprs == null || exprs.length == 0) {
            b.selectItems.add(item(new SqlAllColumns()));
            return b;
        }
        for (int i = 0; i < exprs.length; i++) {
            b.selectItems.add(item(exprs[i]));
        }
        return b;
    }

    /**
     * @param table 表名
     * @return INSERT 构建器
     */
    public static SqlBuilder insertInto(String table) {
        SqlBuilder b = new SqlBuilder(Kind.INSERT);
        b.table = SqlTable.of(ident(table));
        return b;
    }

    /**
     * @param table 表名
     * @return UPDATE 构建器
     */
    public static SqlBuilder update(String table) {
        SqlBuilder b = new SqlBuilder(Kind.UPDATE);
        b.table = SqlTable.of(ident(table));
        return b;
    }

    /**
     * @param table 表名
     * @return DELETE 构建器
     */
    public static SqlBuilder deleteFrom(String table) {
        SqlBuilder b = new SqlBuilder(Kind.DELETE);
        b.table = SqlTable.of(ident(table));
        return b;
    }

    /**
     * @param dialect 方言（影响 {@link #toSql()}）
     * @return this
     */
    public SqlBuilder dialect(SqlDialect dialect) {
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        return this;
    }

    /**
     * @param table 表名（可 {@code schema.table}）
     * @return this
     */
    public SqlBuilder from(String table) {
        this.table = SqlTable.of(ident(table));
        this.fromSource = this.table;
        return this;
    }

    /**
     * @param table 表名
     * @param alias 别名
     * @return this
     */
    public SqlBuilder from(String table, String alias) {
        from(table);
        this.tableAlias = alias;
        return this;
    }

    /**
     * @param predicateSql 谓词片段，如 {@code status = 1}
     * @return this
     */
    public SqlBuilder where(String predicateSql) {
        return where(parsePredicate(predicateSql));
    }

    /**
     * @param predicate 谓词
     * @return this
     */
    public SqlBuilder where(SqlExpr predicate) {
        this.where = predicate;
        return this;
    }

    /**
     * AND 追加谓词（无 WHERE 时等同 {@link #where(String)}）。
     *
     * @param predicateSql 谓词片段
     * @return this
     */
    public SqlBuilder and(String predicateSql) {
        return and(parsePredicate(predicateSql));
    }

    /**
     * AND 追加谓词。
     *
     * @param predicate 谓词
     * @return this
     */
    public SqlBuilder and(SqlExpr predicate) {
        if (predicate == null) {
            return this;
        }
        this.where = this.where == null ? predicate : andAll(this.where, predicate);
        return this;
    }

    /**
     * LEFT JOIN。
     *
     * @param table 右表
     * @param onSql ON 谓词，如 {@code a.id = b.aid}
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder leftJoin(String table, String onSql) {
        return join(SqlJoin.Type.LEFT, table, null, onSql);
    }

    /**
     * RIGHT JOIN。
     *
     * @param table 右表
     * @param onSql ON 谓词
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder rightJoin(String table, String onSql) {
        return join(SqlJoin.Type.RIGHT, table, null, onSql);
    }

    /**
     * FULL JOIN。
     *
     * @param table 右表
     * @param onSql ON 谓词
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder fullJoin(String table, String onSql) {
        return join(SqlJoin.Type.FULL, table, null, onSql);
    }

    /**
     * CROSS JOIN（无 ON）。
     *
     * @param table 右表
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder crossJoin(String table) {
        return join(SqlJoin.Type.CROSS, table, null, null);
    }

    /**
     * INNER JOIN。
     *
     * @param table 右表
     * @param onSql ON 谓词
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder join(String table, String onSql) {
        return join(SqlJoin.Type.INNER, table, null, onSql);
    }

    /**
     * JOIN（可指定类型与别名）。
     *
     * @param type 连接类型
     * @param table 右表
     * @param alias 右表别名，可空
     * @param onSql ON 谓词
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder join(SqlJoin.Type type, String table, String alias, String onSql) {
        SqlTable right = SqlTable.of(ident(table));
        if (alias != null && !alias.isEmpty()) {
            right.setAlias(alias);
        }
        SqlJoin join = new SqlJoin();
        join.setJoinType(type == null ? SqlJoin.Type.INNER : type);
        join.setLeft(fromSource != null ? fromSource : this.table);
        join.setRight(right);
        if (onSql != null && !onSql.isEmpty()) {
            join.setCondition(parsePredicate(onSql));
        }
        this.fromSource = join;
        return this;
    }

    /**
     * {@code SELECT DISTINCT}。
     *
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder distinct() {
        this.distinct = true;
        return this;
    }

    /**
     * 追加 CTE：{@code WITH name AS (subquerySql)}。
     *
     * @param name CTE 名
     * @param subquerySql 子查询 SQL（不含外层括号）
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder with(String name, String subquerySql) {
        SqlWithItem item = new SqlWithItem();
        item.setName(ident(name));
        item.setQuery(SQL.parse(subquerySql, dialect));
        withItems.add(item);
        return this;
    }

    /**
     * 追加 CTE：{@code WITH name AS (…)}，子查询来自另一构建器。
     *
     * @param name CTE 名
     * @param subquery SELECT 构建器
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder with(String name, SqlBuilder subquery) {
        SqlWithItem item = new SqlWithItem();
        item.setName(ident(name));
        item.setQuery(subquery == null ? null : subquery.buildSelect());
        withItems.add(item);
        return this;
    }

    /**
     * {@code UNION} 右侧查询（右侧须为完整 SELECT 构建器）。
     *
     * @param other 右侧
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder union(SqlBuilder other) {
        return appendUnion("UNION", other);
    }

    /**
     * {@code UNION ALL} 右侧查询。
     *
     * @param other 右侧
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder unionAll(SqlBuilder other) {
        return appendUnion("UNION ALL", other);
    }

    private SqlBuilder appendUnion(String op, SqlBuilder other) {
        if (other == null) {
            throw new IllegalArgumentException("union other is null");
        }
        unionOps.add(op);
        unionSelects.add(other.buildSelect());
        return this;
    }

    /**
     * GROUP BY 列。
     *
     * @param columns 列名
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder groupBy(String... columns) {
        if (columns == null) {
            return this;
        }
        for (int i = 0; i < columns.length; i++) {
            this.groupBy.add(columnExpr(columns[i]));
        }
        return this;
    }

    /**
     * HAVING 谓词。
     *
     * @param predicateSql 谓词片段
     * @return this
     * @since 2.1.0
     */
    public SqlBuilder having(String predicateSql) {
        this.having = parsePredicate(predicateSql);
        return this;
    }

    /**
     * @param columns 排序列（默认 ASC）
     * @return this
     */
    public SqlBuilder orderBy(String... columns) {
        if (columns == null) {
            return this;
        }
        for (int i = 0; i < columns.length; i++) {
            SqlOrderByItem item = new SqlOrderByItem();
            item.setExpr(columnExpr(columns[i]));
            item.setAsc(true);
            orderBy.add(item);
        }
        return this;
    }

    /**
     * @param rowCount 行数
     * @return this
     */
    public SqlBuilder limit(long rowCount) {
        this.limitRows = Long.valueOf(rowCount);
        return this;
    }

    /**
     * @param offset 偏移
     * @return this
     */
    public SqlBuilder offset(long offset) {
        this.offsetRows = Long.valueOf(offset);
        return this;
    }

    /**
     * INSERT 列清单。
     *
     * @param columns 列名
     * @return this
     */
    public SqlBuilder columns(String... columns) {
        insertColumns.clear();
        if (columns != null) {
            for (int i = 0; i < columns.length; i++) {
                insertColumns.add(ident(columns[i]));
            }
        }
        return this;
    }

    /**
     * INSERT 一行 VALUES（Java 值转字面量）。
     *
     * @param values 值
     * @return this
     */
    public SqlBuilder values(Object... values) {
        insertValues.clear();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                insertValues.add(literal(values[i]));
            }
        }
        return this;
    }

    /**
     * INSERT 一行 VALUES（表达式）。
     *
     * @param exprs 表达式
     * @return this
     */
    public SqlBuilder valuesExpr(SqlExpr... exprs) {
        insertValues.clear();
        if (exprs != null) {
            insertValues.addAll(Arrays.asList(exprs));
        }
        return this;
    }

    /**
     * UPDATE SET 赋值。
     *
     * @param column 列名
     * @param value Java 值
     * @return this
     */
    public SqlBuilder set(String column, Object value) {
        return set(column, literal(value));
    }

    /**
     * UPDATE SET 赋值。
     *
     * @param column 列名
     * @param value 表达式
     * @return this
     */
    public SqlBuilder set(String column, SqlExpr value) {
        assignments.add(SqlBinaryExpr.of(ident(column), SqlBinaryOp.EQ, value));
        return this;
    }

    /**
     * @return 构建好的语句 AST
     */
    public SqlStatement build() {
        switch (kind) {
            case SELECT:
                return buildSelect();
            case INSERT:
                return buildInsert();
            case UPDATE:
                return buildUpdate();
            case DELETE:
                return buildDelete();
            default:
                throw new IllegalStateException("unknown kind " + kind);
        }
    }

    /**
     * @return SELECT AST
     */
    public SqlSelect buildSelect() {
        if (kind != Kind.SELECT) {
            throw new IllegalStateException("not a SELECT builder");
        }
        SqlSelect select = new SqlSelect();
        select.setDistinct(distinct);
        if (!withItems.isEmpty()) {
            select.setWithItems(new ArrayList<SqlWithItem>(withItems));
        }
        for (int i = 0; i < selectItems.size(); i++) {
            select.addSelectItem(selectItems.get(i));
        }
        if (tableAlias != null && table != null) {
            table.setAlias(tableAlias);
        }
        if (fromSource != null) {
            select.setFrom(fromSource);
        } else if (table != null) {
            select.setFrom(table);
        }
        select.setWhere(where);
        for (int i = 0; i < groupBy.size(); i++) {
            select.groupBy().add(groupBy.get(i));
        }
        select.setHaving(having);
        for (int i = 0; i < orderBy.size(); i++) {
            select.orderBy().add(orderBy.get(i));
        }
        applyLimit(select);
        if (!unionSelects.isEmpty()) {
            SqlSelect cursor = select;
            for (int i = 0; i < unionSelects.size(); i++) {
                cursor.setUnionOp(unionOps.get(i));
                cursor.setUnion(unionSelects.get(i));
                cursor = unionSelects.get(i);
            }
        }
        return select;
    }

    /**
     * 紧凑 SQL（当前方言）。
     *
     * @return SQL 文本
     */
    public String toSql() {
        return SQL.toSqlString(build(), dialect);
    }

    /**
     * @param dialect 方言
     * @return 紧凑 SQL
     */
    public String toSql(SqlDialect dialect) {
        return SQL.toSqlString(build(), dialect == null ? this.dialect : dialect);
    }

    /**
     * 多谓词 AND。
     *
     * @param predicates 谓词，null 忽略
     * @return 合并结果，全 null 时 null
     */
    public static SqlExpr andAll(SqlExpr... predicates) {
        SqlExpr acc = null;
        if (predicates == null) {
            return null;
        }
        for (int i = 0; i < predicates.length; i++) {
            if (predicates[i] == null) {
                continue;
            }
            acc = acc == null ? predicates[i] : SqlBinaryExpr.of(acc, SqlBinaryOp.AND, predicates[i]);
        }
        return acc;
    }

    /**
     * 多谓词 OR。
     *
     * @param predicates 谓词
     * @return 合并结果
     */
    public static SqlExpr orAll(SqlExpr... predicates) {
        SqlExpr acc = null;
        if (predicates == null) {
            return null;
        }
        for (int i = 0; i < predicates.length; i++) {
            if (predicates[i] == null) {
                continue;
            }
            acc = acc == null ? predicates[i] : SqlBinaryExpr.of(acc, SqlBinaryOp.OR, predicates[i]);
        }
        return acc;
    }

    /**
     * 解析谓词片段为表达式。
     *
     * @param predicateSql 如 {@code a = 1 AND b > 2}
     * @return 表达式
     */
    public static SqlExpr parsePredicate(String predicateSql) {
        if (predicateSql == null || predicateSql.trim().isEmpty()) {
            return null;
        }
        SqlSelect tmp = (SqlSelect) SQL.parse("SELECT 1 WHERE " + predicateSql);
        return tmp.where();
    }

    /**
     * 解析 SELECT 列表项（逗号分隔）。
     *
     * @param selectListSql 如 {@code id, name AS n}
     * @return 列表项
     */
    public static List<SqlSelectItem> parseSelectList(String selectListSql) {
        if (selectListSql == null || selectListSql.trim().isEmpty()) {
            return Collections.emptyList();
        }
        SqlSelect tmp = (SqlSelect) SQL.parse("SELECT " + selectListSql + " FROM dual");
        return new ArrayList<SqlSelectItem>(tmp.selectItems());
    }

    /**
     * 用分号拼接多条语句的紧凑 SQL。
     *
     * @param statements 语句
     * @param dialect 方言
     * @return 文本
     */
    public static String concatStatements(List<SqlStatement> statements, SqlDialect dialect) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < statements.size(); i++) {
            if (i > 0) {
                sb.append(';');
                sb.append(' ');
            }
            sb.append(SQL.toSqlString(statements.get(i), d));
        }
        return sb.toString();
    }

    /**
     * Java 值 → 字面量表达式。
     *
     * @param value 值，null → NULL
     * @return 字面量
     */
    public static SqlExpr literal(Object value) {
        if (value == null) {
            return SqlLiteral.of(SqlLiteral.Kind.NULL, null);
        }
        if (value instanceof SqlExpr) {
            return (SqlExpr) value;
        }
        if (value instanceof Number) {
            return SqlLiteral.of(SqlLiteral.Kind.NUMBER, value.toString());
        }
        if (value instanceof Boolean) {
            return SqlLiteral.of(SqlLiteral.Kind.BOOLEAN, ((Boolean) value).booleanValue() ? "true" : "false");
        }
        String raw = String.valueOf(value);
        String escaped = raw.replace("'", "''");
        return SqlLiteral.of(SqlLiteral.Kind.STRING, "'" + escaped + "'");
    }

    /**
     * 列/表标识符（支持 {@code a.b.c}）。
     *
     * @param name 名称
     * @return 标识符
     */
    public static SqlIdentifier ident(String name) {
        if (name == null || name.isEmpty()) {
            return SqlIdentifier.of("");
        }
        if (name.indexOf('.') < 0) {
            return SqlIdentifier.of(name);
        }
        SqlIdentifier id = new SqlIdentifier();
        String[] parts = name.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            id.addName(parts[i]);
        }
        return id;
    }

    private void applyLimit(SqlSelect select) {
        if (limitRows == null && offsetRows == null) {
            return;
        }
        if (dialect.supportsTop() && (offsetRows == null || offsetRows.longValue() == 0L)
                && limitRows != null) {
            select.setTop(SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(limitRows.longValue())));
            return;
        }
        SqlLimit limit = new SqlLimit();
        if (limitRows != null) {
            limit.setRowCount(SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(limitRows.longValue())));
        }
        if (offsetRows != null && offsetRows.longValue() > 0L) {
            limit.setOffset(SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(offsetRows.longValue())));
            if (dialect == SqlDialect.MYSQL && limitRows != null) {
                limit.setMysqlCommaStyle(true);
            }
            if (dialect.supportsTop()
                    || (dialect.supportsFetchFirst() && !dialect.supportsLimitOffset())) {
                limit.setFetchStyle(true);
            }
        } else if (dialect.supportsFetchFirst() && !dialect.supportsLimitOffset() && limitRows != null) {
            limit.setFetchStyle(true);
        }
        select.setLimit(limit);
    }

    private SqlInsert buildInsert() {
        SqlInsert insert = new SqlInsert();
        insert.setTable(table);
        for (int i = 0; i < insertColumns.size(); i++) {
            insert.columns().add(insertColumns.get(i));
        }
        if (!insertValues.isEmpty()) {
            insert.valuesList().add(new ArrayList<SqlExpr>(insertValues));
        }
        return insert;
    }

    private SqlUpdate buildUpdate() {
        SqlUpdate update = new SqlUpdate();
        update.setTable(table);
        for (int i = 0; i < assignments.size(); i++) {
            update.setList().add(assignments.get(i));
        }
        update.setWhere(where);
        return update;
    }

    private SqlDelete buildDelete() {
        SqlDelete delete = new SqlDelete();
        delete.setTable(table);
        delete.setWhere(where);
        return delete;
    }

    private static SqlSelectItem item(SqlExpr expr) {
        SqlSelectItem item = new SqlSelectItem();
        item.setExpr(expr);
        return item;
    }

    private static SqlExpr columnExpr(String column) {
        if (column == null) {
            return SqlIdentifier.of("");
        }
        String trimmed = column.trim();
        if ("*".equals(trimmed)) {
            return new SqlAllColumns();
        }
        int dotStar = trimmed.indexOf(".*");
        if (dotStar > 0 && dotStar == trimmed.length() - 2) {
            SqlAllColumns all = new SqlAllColumns();
            all.setOwner(ident(trimmed.substring(0, dotStar)));
            return all;
        }
        return ident(trimmed);
    }
}
