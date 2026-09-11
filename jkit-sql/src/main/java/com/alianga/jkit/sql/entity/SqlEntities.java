package com.alianga.jkit.sql.entity;

import com.alianga.jkit.sql.SqlBuilder;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;
import com.alianga.jkit.sql.schema.model.ColumnConstraint;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.AutoIncrementStrategy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 实体扫描 + 按方言生成 DDL / DML。结合 {@link SqlDataTypeRegistry} 与 {@link SqlBuilder}。
 *
 * <pre>{@code
 * List<Class<?>> entities = SqlEntities.scan("com.example.entity");
 * String ddl = SqlEntities.createTable(User.class, SqlDialect.POSTGRES);
 * String ins = SqlEntities.insert(user, SqlDialect.MYSQL);
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlEntities {
    private SqlEntities() {
    }

    /**
     * 扫描一个包。
     *
     * @param basePackage 根包
     * @return 实体类
     */
    public static List<Class<?>> scan(String basePackage) {
        return new SqlEntityScanner(basePackage).scan();
    }

    /**
     * 扫描多个包。
     *
     * @param basePackages 根包
     * @return 实体类
     */
    public static List<Class<?>> scan(List<String> basePackages) {
        return new SqlEntityScanner(basePackages).scan();
    }

    /**
     * @param type 实体类
     * @return 映射
     */
    public static SqlEntityModel inspect(Class<?> type) {
        return SqlEntityMapper.inspect(type);
    }

    /**
     * {@code CREATE TABLE}。
     *
     * @param type 实体类
     * @param dialect 目标方言
     * @return DDL
     */
    public static String createTable(Class<?> type, SqlDialect dialect) {
        return createTable(inspect(type), dialect);
    }

    /**
     * {@code CREATE TABLE}。
     *
     * @param model 映射
     * @param dialect 目标方言
     * @return DDL
     */
    public static String createTable(SqlEntityModel model, SqlDialect dialect) {
        return createTable(model, dialect, true);
    }

    /**
     * {@code CREATE TABLE}，可选是否附带 {@code CREATE INDEX}。
     *
     * @param model 映射
     * @param dialect 目标方言
     * @param includeIndexes 是否在同一批里拼索引
     * @return DDL
     */
    public static String createTable(SqlEntityModel model, SqlDialect dialect, boolean includeIndexes) {
        return createTable(model, dialect, includeIndexes, SqlSchemaConvertOptions.defaults());
    }

    /**
     * {@code CREATE TABLE}，可覆盖转换选项（例如 PG 自增用 SERIAL）。
     *
     * @param model 映射
     * @param dialect 目标方言
     * @param includeIndexes 是否在同一批里拼索引
     * @param convertOptions 转换选项，null 则用默认
     * @return DDL
     */
    public static String createTable(SqlEntityModel model, SqlDialect dialect, boolean includeIndexes,
                                     SqlSchemaConvertOptions convertOptions) {
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();
        SqlSchemaConvertOptions options = convertOptions == null
                ? SqlSchemaConvertOptions.defaults() : convertOptions;
        ConversionReport.Builder report = new ConversionReport.Builder();
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(model.tableName()).append(" (");
        List<SqlEntityColumn> cols = model.columns();
        List<SqlEntityColumn> ids = model.idColumns();
        boolean tablePk = ids.size() > 1;
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderColumn(cols.get(i), d, registry, options, report, !tablePk));
        }
        if (tablePk) {
            sb.append(", PRIMARY KEY (");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ids.get(i).columnName());
            }
            sb.append(')');
        }
        if (options.includeForeignKeys()) {
            for (int i = 0; i < cols.size(); i++) {
                SqlEntityColumn c = cols.get(i);
                if (c.referencesTable() != null) {
                    sb.append(", FOREIGN KEY (").append(c.columnName()).append(") REFERENCES ")
                            .append(c.referencesTable()).append('(')
                            .append(c.referencesColumn() == null ? "id" : c.referencesColumn())
                            .append(')');
                }
            }
        }
        sb.append(')');
        appendTableComment(sb, model, d);
        if (includeIndexes) {
            List<String> indexes = model.indexes();
            for (int i = 0; i < indexes.size(); i++) {
                sb.append("; ").append(indexSql(model.tableName(), indexes.get(i)));
            }
            List<String> extras = extraSql(model, d, options);
            for (int i = 0; i < extras.size(); i++) {
                sb.append("; ").append(extras.get(i));
            }
        }
        return sb.toString();
    }

    /**
     * 建表后的附录：{@code COMMENT ON}、无 IDENTITY 方言的 SEQUENCE。
     *
     * @param model 映射
     * @param dialect 方言
     * @param convertOptions 转换选项
     * @return 附录 SQL，可能为空
     */
    public static List<String> extraSql(SqlEntityModel model, SqlDialect dialect,
                                        SqlSchemaConvertOptions convertOptions) {
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlSchemaConvertOptions options = convertOptions == null
                ? SqlSchemaConvertOptions.defaults() : convertOptions;
        List<String> out = new ArrayList<String>(4);
        if (!inlinesTableComment(d) && model.comment() != null && model.comment().length() > 0) {
            String c = commentOnSql(d, "TABLE", model.tableName(), null, model.comment());
            if (c != null) {
                out.add(c);
            }
        }
        if (!inlinesColumnComment(d)) {
            List<SqlEntityColumn> cols = model.columns();
            for (int i = 0; i < cols.size(); i++) {
                SqlEntityColumn col = cols.get(i);
                if (col.comment() == null || col.comment().isEmpty()) {
                    continue;
                }
                String c = commentOnSql(d, "COLUMN", model.tableName(), col.columnName(), col.comment());
                if (c != null) {
                    out.add(c);
                }
            }
        }
        if (options.includeAutoIncrement()) {
            List<SqlEntityColumn> cols = model.columns();
            for (int i = 0; i < cols.size(); i++) {
                SqlEntityColumn col = cols.get(i);
                if (!col.autoIncrement()) {
                    continue;
                }
                String seq = sequenceSql(model.tableName(), col, d);
                if (seq == null) {
                    continue;
                }
                int from = 0;
                while (from < seq.length()) {
                    int slash = seq.indexOf("\n/\n", from);
                    String piece;
                    if (slash < 0) {
                        piece = seq.substring(from).trim();
                        from = seq.length();
                    } else {
                        piece = seq.substring(from, slash).trim();
                        from = slash + 3;
                    }
                    if (!piece.isEmpty()) {
                        out.add(piece);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 无 IDENTITY 的方言用序列代替自增。支持的返回 SQL，否则 null。
     *
     * @param table 表名
     * @param column 列
     * @param dialect 方言
     * @return SQL 或 null
     */
    public static String sequenceSql(String table, SqlEntityColumn column, SqlDialect dialect) {
        if (table == null || column == null || !column.autoIncrement()) {
            return null;
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (!needsSequenceFallback(d)) {
            return null;
        }
        String col = column.columnName();
        String seq = sequenceName(table, col);
        if (d == SqlDialect.ORACLE) {
            String trg = table + "_" + col + "_bi";
            return "CREATE SEQUENCE " + seq
                    + "\n/\nCREATE OR REPLACE TRIGGER " + trg
                    + " BEFORE INSERT ON " + table
                    + " FOR EACH ROW WHEN (NEW." + col + " IS NULL) BEGIN SELECT "
                    + seq + ".NEXTVAL INTO :NEW." + col + " FROM DUAL; END;";
        }
        return "CREATE SEQUENCE " + seq + " START WITH 1 INCREMENT BY 1";
    }

    /**
     * 该方言无 IDENTITY / AUTO_INCREMENT 时用序列代替自增。
     *
     * @param dialect 方言
     * @return 是否需要 SEQUENCE
     */
    public static boolean needsSequenceFallback(SqlDialect dialect) {
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        return d == SqlDialect.ORACLE;
    }

    /**
     * 自增序列名 {@code {table}_{column}_seq}。
     *
     * @param table 表名
     * @param column 列名
     * @return 序列名
     */
    public static String sequenceName(String table, String column) {
        return table + "_" + column + "_seq";
    }

    private static boolean inlinesTableComment(SqlDialect dialect) {
        return dialect == SqlDialect.MYSQL || dialect == SqlDialect.HIVE
                || dialect == SqlDialect.CLICKHOUSE || dialect == SqlDialect.PRESTO;
    }

    private static boolean inlinesColumnComment(SqlDialect dialect) {
        return dialect == SqlDialect.MYSQL || dialect == SqlDialect.H2
                || dialect == SqlDialect.HIVE || dialect == SqlDialect.CLICKHOUSE
                || dialect == SqlDialect.PRESTO;
    }

    private static void appendTableComment(StringBuilder sb, SqlEntityModel model, SqlDialect dialect) {
        if (model.comment() == null || model.comment().isEmpty()) {
            return;
        }
        String body = escapeComment(model.comment());
        if (dialect == SqlDialect.MYSQL || dialect == SqlDialect.HIVE
                || dialect == SqlDialect.CLICKHOUSE) {
            sb.append(" COMMENT '").append(body).append('\'');
        } else if (dialect == SqlDialect.PRESTO) {
            sb.append(" WITH (comment = '").append(body).append("')");
        }
    }

    private static String commentOnSql(SqlDialect dialect, String kind, String table, String column,
                                       String comment) {
        if (comment == null || comment.isEmpty()) {
            return null;
        }
        String body = escapeComment(comment);
        if (dialect == SqlDialect.SQLSERVER) {
            if ("TABLE".equals(kind)) {
                return "EXEC sp_addextendedproperty N'MS_Description', N'" + body
                        + "', N'SCHEMA', N'dbo', N'TABLE', N'" + table + "'";
            }
            return "EXEC sp_addextendedproperty N'MS_Description', N'" + body
                    + "', N'SCHEMA', N'dbo', N'TABLE', N'" + table + "', N'COLUMN', N'" + column + "'";
        }
        if (dialect == SqlDialect.SQLITE || dialect == SqlDialect.HIVE
                || dialect == SqlDialect.PRESTO || dialect == SqlDialect.CLICKHOUSE) {
            return null;
        }
        if ("TABLE".equals(kind)) {
            return "COMMENT ON TABLE " + table + " IS '" + body + "'";
        }
        return "COMMENT ON COLUMN " + table + "." + column + " IS '" + body + "'";
    }

    private static String escapeComment(String comment) {
        return comment.replace("'", "''");
    }

    /**
     * 批量 INSERT，多行 VALUES。
     *
     * @param entities 实体列表
     * @param dialect 方言
     * @return DML；空列表返回空串
     */
    public static String insertBatch(List<?> entities, SqlDialect dialect) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(insert(entities.get(i), dialect));
        }
        return sb.toString();
    }

    /**
     * 单列定义文本（含类型 / NOT NULL / 自增 / 可选内联主键）。
     * {@code ALTER TABLE … ADD} 应传 {@code inlinePk=false}。
     *
     * @param column 列
     * @param dialect 方言
     * @param inlinePk 是否在列上写 {@code PRIMARY KEY}（单主键建表时为 true）
     * @return 列定义
     */
    public static String columnSql(SqlEntityColumn column, SqlDialect dialect, boolean inlinePk) {
        return columnSql(column, dialect, inlinePk, SqlSchemaConvertOptions.defaults());
    }

    /**
     * 单列定义文本，可覆盖转换选项。
     *
     * @param column 列
     * @param dialect 方言
     * @param inlinePk 是否内联主键
     * @param convertOptions 转换选项
     * @return 列定义
     */
    public static String columnSql(SqlEntityColumn column, SqlDialect dialect, boolean inlinePk,
                                   SqlSchemaConvertOptions convertOptions) {
        if (column == null) {
            return "";
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlSchemaConvertOptions options = convertOptions == null
                ? SqlSchemaConvertOptions.defaults() : convertOptions;
        return renderColumn(column, d, SqlDataTypeRegistry.builtins(),
                options, new ConversionReport.Builder(), inlinePk);
    }

    /**
     * 单列类型写法（不含列名与约束），供结构对比。
     *
     * @param column 列
     * @param dialect 方言
     * @return 类型文本
     */
    public static String columnTypeSql(SqlEntityColumn column, SqlDialect dialect) {
        if (column == null) {
            return "";
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (column.rawType() != null && column.rawType().length() > 0) {
            return column.rawType();
        }
        return SqlDataTypeRegistry.builtins().toDialect(
                column.canonical(), d, column.precision(), column.scale());
    }

    /**
     * {@code CREATE INDEX}。{@code spec} 为 {@code name:col1,col2} 或 {@code col1,col2}。
     *
     * @param tableName 表名
     * @param spec 索引定义
     * @return DDL；spec 空则空串
     */
    public static String createIndex(String tableName, String spec) {
        if (tableName == null || tableName.isEmpty() || spec == null || spec.trim().isEmpty()) {
            return "";
        }
        return indexSql(tableName, spec.trim());
    }

    private static String indexSql(String table, String spec) {
        String name = table + "_idx";
        String cols = spec;
        int colon = spec.indexOf(':');
        if (colon > 0) {
            name = spec.substring(0, colon).trim();
            cols = spec.substring(colon + 1).trim();
        }
        if (!cols.startsWith("(")) {
            cols = "(" + cols + ")";
        }
        return "CREATE INDEX " + name + " ON " + table + " " + cols;
    }

    /**
     * 扫描包后拼接全部 {@code CREATE TABLE}（分号分隔）。
     *
     * @param basePackage 根包
     * @param dialect 方言
     * @return DDL 批
     */
    public static String createTables(String basePackage, SqlDialect dialect) {
        return createTables(scan(basePackage), dialect);
    }

    /**
     * 按外键依赖排序后拼接 {@code CREATE TABLE}（被引用表在前）。
     *
     * @param types 实体类
     * @param dialect 方言
     * @return DDL 批
     */
    public static String createTables(List<Class<?>> types, SqlDialect dialect) {
        List<Class<?>> ordered = orderByForeignKeys(types);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(createTable(ordered.get(i), dialect));
        }
        return sb.toString();
    }

    /**
     * 按外键把被引用表排在前面。环或外部引用保持原相对顺序追加在末尾。
     *
     * @param types 实体类
     * @return 新列表，不改入参
     */
    public static List<Class<?>> orderByForeignKeys(List<Class<?>> types) {
        if (types == null || types.size() <= 1) {
            return types == null ? new ArrayList<Class<?>>(0) : new ArrayList<Class<?>>(types);
        }
        List<SqlEntityModel> models = new ArrayList<SqlEntityModel>(types.size());
        for (int i = 0; i < types.size(); i++) {
            models.add(inspect(types.get(i)));
        }
        List<Class<?>> remaining = new ArrayList<Class<?>>(types);
        List<Class<?>> out = new ArrayList<Class<?>>(types.size());
        while (!remaining.isEmpty()) {
            int before = remaining.size();
            for (int i = 0; i < remaining.size(); ) {
                Class<?> c = remaining.get(i);
                if (fkSatisfied(c, models, out, remaining)) {
                    out.add(c);
                    remaining.remove(i);
                } else {
                    i++;
                }
            }
            if (remaining.size() == before) {
                out.addAll(remaining);
                break;
            }
        }
        return out;
    }

    private static boolean fkSatisfied(Class<?> type, List<SqlEntityModel> models,
                                       List<Class<?>> done, List<Class<?>> remaining) {
        SqlEntityModel me = modelOf(type, models);
        if (me == null) {
            return true;
        }
        List<SqlEntityColumn> cols = me.columns();
        for (int i = 0; i < cols.size(); i++) {
            String ref = cols.get(i).referencesTable();
            if (ref == null || ref.isEmpty() || ref.equalsIgnoreCase(me.tableName())) {
                continue;
            }
            Class<?> dep = typeOfTable(ref, models);
            if (dep == null || dep == type) {
                continue;
            }
            if (containsClass(done, dep)) {
                continue;
            }
            if (containsClass(remaining, dep)) {
                return false;
            }
        }
        return true;
    }

    private static SqlEntityModel modelOf(Class<?> type, List<SqlEntityModel> models) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).type() == type) {
                return models.get(i);
            }
        }
        return null;
    }

    private static Class<?> typeOfTable(String table, List<SqlEntityModel> models) {
        for (int i = 0; i < models.size(); i++) {
            if (table.equalsIgnoreCase(models.get(i).tableName())) {
                return models.get(i).type();
            }
        }
        return null;
    }

    private static boolean containsClass(List<Class<?>> list, Class<?> type) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code DROP TABLE}。
     *
     * @param type 实体类
     * @param dialect 方言（当前仅影响标识符，语句形态相同）
     * @return DDL
     */
    public static String dropTable(Class<?> type, SqlDialect dialect) {
        return "DROP TABLE " + inspect(type).tableName();
    }

    /**
     * {@code INSERT}，自增主键且值为 null 时跳过该列。
     *
     * @param entity 实体实例
     * @param dialect 方言
     * @return DML
     */
    public static String insert(Object entity, SqlDialect dialect) {
        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }
        SqlEntityModel model = inspect(entity.getClass());
        List<String> names = new ArrayList<String>(model.columns().size());
        List<Object> values = new ArrayList<Object>(model.columns().size());
        List<SqlEntityColumn> cols = model.columns();
        for (int i = 0; i < cols.size(); i++) {
            SqlEntityColumn c = cols.get(i);
            Object v = read(c.field(), entity);
            if (c.autoIncrement() && v == null) {
                continue;
            }
            names.add(c.columnName());
            values.add(v);
        }
        return SqlBuilder.insertInto(model.tableName())
                .dialect(dialect == null ? SqlDialect.MYSQL : dialect)
                .columns(names.toArray(new String[names.size()]))
                .values(values.toArray())
                .toSql();
    }

    /**
     * {@code INSERT INTO t (cols) VALUES (?,?,...)} 占位。
     *
     * @param type 实体类
     * @param dialect 方言
     * @return DML
     */
    public static String insertPlaceholders(Class<?> type, SqlDialect dialect) {
        SqlEntityModel model = inspect(type);
        List<SqlEntityColumn> cols = model.insertColumns();
        if (cols.isEmpty()) {
            cols = model.columns();
        }
        String[] names = new String[cols.size()];
        SqlExpr[] ph = new SqlExpr[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            names[i] = cols.get(i).columnName();
            ph[i] = SqlLiteral.of(SqlLiteral.Kind.BIND, "?");
        }
        return SqlBuilder.insertInto(model.tableName())
                .dialect(dialect == null ? SqlDialect.MYSQL : dialect)
                .columns(names)
                .valuesExpr(ph)
                .toSql();
    }

    /**
     * 按主键 {@code UPDATE}，其余非空字段写入 SET。
     *
     * @param entity 实体
     * @param dialect 方言
     * @return DML
     */
    public static String updateById(Object entity, SqlDialect dialect) {
        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }
        SqlEntityModel model = inspect(entity.getClass());
        SqlEntityColumn id = model.idColumn();
        if (id == null) {
            throw new IllegalArgumentException("no @SqlId / @Id on " + entity.getClass().getName());
        }
        SqlBuilder b = SqlBuilder.update(model.tableName())
                .dialect(dialect == null ? SqlDialect.MYSQL : dialect);
        List<SqlEntityColumn> cols = model.columns();
        for (int i = 0; i < cols.size(); i++) {
            SqlEntityColumn c = cols.get(i);
            if (c.primaryKey()) {
                continue;
            }
            Object v = read(c.field(), entity);
            if (v != null) {
                b.set(c.columnName(), v);
            }
        }
        Object idVal = read(id.field(), entity);
        return b.where(id.columnName() + " = " + literalSql(idVal)).toSql();
    }

    /**
     * {@code DELETE FROM t WHERE id = ?}。
     *
     * @param type 实体类
     * @param id 主键值
     * @param dialect 方言
     * @return DML
     */
    public static String deleteById(Class<?> type, Object id, SqlDialect dialect) {
        SqlEntityModel model = inspect(type);
        SqlEntityColumn idCol = model.idColumn();
        if (idCol == null) {
            throw new IllegalArgumentException("no @SqlId / @Id on " + type.getName());
        }
        return SqlBuilder.deleteFrom(model.tableName())
                .dialect(dialect == null ? SqlDialect.MYSQL : dialect)
                .where(idCol.columnName() + " = " + literalSql(id))
                .toSql();
    }

    /**
     * {@code SELECT * FROM t WHERE id = ?}。
     *
     * @param type 实体类
     * @param id 主键
     * @param dialect 方言
     * @return DML
     */
    public static String selectById(Class<?> type, Object id, SqlDialect dialect) {
        SqlEntityModel model = inspect(type);
        SqlEntityColumn idCol = model.idColumn();
        if (idCol == null) {
            throw new IllegalArgumentException("no @SqlId / @Id on " + type.getName());
        }
        List<SqlEntityColumn> cols = model.columns();
        String[] names = new String[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            names[i] = cols.get(i).columnName();
        }
        return SqlBuilder.select(names)
                .from(model.tableName())
                .dialect(dialect == null ? SqlDialect.MYSQL : dialect)
                .where(idCol.columnName() + " = " + literalSql(id))
                .toSql();
    }

    /**
     * {@code SELECT cols FROM t}。
     *
     * @param type 实体类
     * @param dialect 方言
     * @return DML
     */
    public static String selectAll(Class<?> type, SqlDialect dialect) {
        SqlEntityModel model = inspect(type);
        List<SqlEntityColumn> cols = model.columns();
        String[] names = new String[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            names[i] = cols.get(i).columnName();
        }
        return SqlBuilder.select(names)
                .from(model.tableName())
                .dialect(dialect == null ? SqlDialect.MYSQL : dialect)
                .toSql();
    }

    private static String renderColumn(SqlEntityColumn col, SqlDialect dialect,
                                       SqlDataTypeRegistry registry, SqlSchemaConvertOptions options,
                                       ConversionReport.Builder report, boolean inlinePk) {
        Integer p = col.precision();
        Integer s = col.scale();
        String type = col.rawType() != null && col.rawType().length() > 0
                ? col.rawType()
                : registry.toDialect(col.canonical(), dialect, p, s);
        if (col.autoIncrement() && options.includeAutoIncrement()) {
            AutoIncrementStrategy.Result auto = AutoIncrementStrategy.apply(
                    new ColumnConstraint.AutoIncrement(
                            ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED),
                    col.canonical(), col.columnName(), dialect, options, report);
            if (auto.overrideType() != null) {
                type = auto.overrideType();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(col.columnName()).append(' ').append(type);
            if (dialect == SqlDialect.SQLITE && inlinePk && col.primaryKey()) {
                // SQLite: INTEGER PRIMARY KEY AUTOINCREMENT，AUTOINCREMENT 必须在 PRIMARY KEY 之后。
                sb.append(" PRIMARY KEY");
                if (auto.clause() != null) {
                    sb.append(' ').append(auto.clause());
                }
                appendComment(sb, col, dialect);
                return sb.toString();
            }
            if (!col.nullable()) {
                sb.append(" NOT NULL");
            }
            if (auto.clause() != null) {
                sb.append(' ').append(auto.clause());
            }
            if (inlinePk && col.primaryKey()) {
                sb.append(" PRIMARY KEY");
            }
            appendComment(sb, col, dialect);
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(col.columnName()).append(' ').append(type);
        if (!col.nullable()) {
            sb.append(" NOT NULL");
        }
        if (inlinePk && col.primaryKey()) {
            sb.append(" PRIMARY KEY");
        } else if (col.unique()) {
            sb.append(" UNIQUE");
        }
        appendComment(sb, col, dialect);
        return sb.toString();
    }

    private static void appendComment(StringBuilder sb, SqlEntityColumn col, SqlDialect dialect) {
        if (col.comment() == null || col.comment().isEmpty()) {
            return;
        }
        if (!inlinesColumnComment(dialect)) {
            return;
        }
        sb.append(" COMMENT '").append(escapeComment(col.comment())).append('\'');
    }

    private static Object read(Field field, Object entity) {
        if (field == null) {
            return null;
        }
        try {
            if (!field.isAccessible()) {
                field.setAccessible(true);
            }
            return field.get(entity);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static String literalSql(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
}
