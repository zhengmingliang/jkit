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
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();
        SqlSchemaConvertOptions options = SqlSchemaConvertOptions.defaults();
        ConversionReport.Builder report = new ConversionReport.Builder();
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(model.tableName()).append(" (");
        List<SqlEntityColumn> cols = model.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderColumn(cols.get(i), d, registry, options, report));
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * 扫描包后拼接全部 {@code CREATE TABLE}（分号分隔）。
     *
     * @param basePackage 根包
     * @param dialect 方言
     * @return DDL 批
     */
    public static String createTables(String basePackage, SqlDialect dialect) {
        List<Class<?>> types = scan(basePackage);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(createTable(types.get(i), dialect));
        }
        return sb.toString();
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
                                       ConversionReport.Builder report) {
        Integer p = col.precision();
        Integer s = col.scale();
        String type = registry.toDialect(col.canonical(), dialect, p, s);
        if (col.autoIncrement()) {
            AutoIncrementStrategy.Result auto = AutoIncrementStrategy.apply(
                    new ColumnConstraint.AutoIncrement(
                            ColumnConstraint.AutoIncrement.IdentityMode.UNSPECIFIED),
                    col.canonical(), col.columnName(), dialect, options, report);
            if (auto.overrideType() != null) {
                type = auto.overrideType();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(col.columnName()).append(' ').append(type);
            if (!col.nullable()) {
                sb.append(" NOT NULL");
            }
            if (auto.clause() != null) {
                sb.append(' ').append(auto.clause());
            }
            if (col.primaryKey()) {
                sb.append(" PRIMARY KEY");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(col.columnName()).append(' ').append(type);
        if (!col.nullable()) {
            sb.append(" NOT NULL");
        }
        if (col.primaryKey()) {
            sb.append(" PRIMARY KEY");
        } else if (col.unique()) {
            sb.append(" UNIQUE");
        }
        return sb.toString();
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
