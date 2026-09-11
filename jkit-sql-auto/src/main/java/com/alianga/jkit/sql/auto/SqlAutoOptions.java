package com.alianga.jkit.sql.auto;

import com.alianga.jkit.config.ConfigPropertyResolver;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 自动建表选项。可用链式 setter，或从 {@code jkit.sql.auto.*} 配置加载。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoOptions {
    private static final String P = "jkit.sql.auto.";

    private boolean enabled = true;
    private SqlAutoMode mode = SqlAutoMode.UPDATE;
    private final List<String> packages = new ArrayList<String>(2);
    private final List<Class<?>> entities = new ArrayList<Class<?>>(2);
    private SqlDialect dialect;
    private String url;
    private String username;
    private String password;
    private String driver;
    private DataSource dataSource;
    private boolean failFast = true;
    private boolean alterColumn;
    private boolean dropExtraColumns;
    private boolean createIndex = true;
    private boolean quoteIdentifiers;
    private boolean showSql = true;
    private boolean dryRun;
    private String catalog;
    private String schema;
    private SqlSchemaConvertOptions.PostgresIdentityStyle postgresIdentityStyle;
    private boolean foreignKeys = true;
    private boolean autoIncrement = true;

    private SqlAutoOptions() {
    }

    /**
     * @return 默认选项（{@link SqlAutoMode#UPDATE}，建索引，失败即停）
     */
    public static SqlAutoOptions defaults() {
        return new SqlAutoOptions();
    }

    /**
     * 从进程配置读取 {@code jkit.sql.auto.*}；数据源回落 {@code spring.datasource.*}。
     *
     * @return 选项
     */
    public static SqlAutoOptions fromConfig() {
        return fromConfig(ConfigPropertyResolver.instance());
    }

    /**
     * @param resolver 配置
     * @return 选项
     */
    public static SqlAutoOptions fromConfig(ConfigPropertyResolver resolver) {
        SqlAutoOptions o = defaults();
        if (resolver == null) {
            return o;
        }
        if (resolver.contains(P + "enabled")) {
            Boolean en = resolver.getBoolean(P + "enabled");
            o.enabled = en == null || en.booleanValue();
        }
        String mode = first(resolver, P + "mode", P + "ddl-auto");
        if (mode != null) {
            o.mode = SqlAutoMode.fromName(mode);
        }
        o.packages.addAll(stringList(resolver, P + "packages"));
        o.packages.addAll(stringList(resolver, P + "base-packages"));
        String one = first(resolver, P + "package", P + "base-package");
        if (one != null && one.length() > 0) {
            o.packages.add(one);
        }
        o.entities.addAll(classList(resolver, P + "entities"));
        String dialectName = first(resolver, P + "dialect", "spring.jpa.database-platform");
        if (dialectName != null && dialectName.length() > 0) {
            o.dialect = SqlDialect.fromName(dialectName);
        }
        o.url = first(resolver, P + "url", "spring.datasource.url");
        o.username = first(resolver, P + "username", "spring.datasource.username");
        o.password = first(resolver, P + "password", "spring.datasource.password");
        o.driver = first(resolver, P + "driver", P + "driver-class-name",
                "spring.datasource.driver-class-name");
        if (resolver.contains(P + "fail-fast")) {
            Boolean v = resolver.getBoolean(P + "fail-fast");
            o.failFast = v == null || v.booleanValue();
        }
        o.alterColumn = bool(resolver, P + "alter-column", false);
        o.dropExtraColumns = bool(resolver, P + "drop-extra-columns", false);
        if (resolver.contains(P + "create-index")) {
            Boolean v = resolver.getBoolean(P + "create-index");
            o.createIndex = v == null || v.booleanValue();
        }
        o.quoteIdentifiers = bool(resolver, P + "quote-identifiers", false);
        if (resolver.contains(P + "show-sql")) {
            Boolean v = resolver.getBoolean(P + "show-sql");
            o.showSql = v == null || v.booleanValue();
        }
        o.dryRun = bool(resolver, P + "dry-run", false);
        o.catalog = resolver.getString(P + "catalog");
        o.schema = resolver.getString(P + "schema");
        return o;
    }

    /**
     * @return 是否启用
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @param enabled 是否启用
     * @return this
     */
    public SqlAutoOptions enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * @return 模式
     */
    public SqlAutoMode mode() {
        return mode;
    }

    /**
     * @param mode 模式
     * @return this
     */
    public SqlAutoOptions mode(SqlAutoMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
        return this;
    }

    /**
     * @return 扫描包（可变，调用方不要改）
     */
    public List<String> packages() {
        return packages;
    }

    /**
     * @param basePackages 扫描包
     * @return this
     */
    public SqlAutoOptions packages(String... basePackages) {
        this.packages.clear();
        if (basePackages != null) {
            this.packages.addAll(Arrays.asList(basePackages));
        }
        return this;
    }

    /**
     * @param basePackages 扫描包
     * @return this
     */
    public SqlAutoOptions packages(List<String> basePackages) {
        this.packages.clear();
        if (basePackages != null) {
            this.packages.addAll(basePackages);
        }
        return this;
    }

    /**
     * @return 显式实体
     */
    public List<Class<?>> entities() {
        return entities;
    }

    /**
     * @param types 实体类
     * @return this
     */
    public SqlAutoOptions entities(Class<?>... types) {
        this.entities.clear();
        if (types != null) {
            this.entities.addAll(Arrays.asList(types));
        }
        return this;
    }

    /**
     * @param types 实体类
     * @return this
     */
    public SqlAutoOptions entities(List<Class<?>> types) {
        this.entities.clear();
        if (types != null) {
            this.entities.addAll(types);
        }
        return this;
    }

    /**
     * @return 方言，null 表示从 JDBC URL / DatabaseMetaData 推断
     */
    public SqlDialect dialect() {
        return dialect;
    }

    /**
     * @param dialect 方言
     * @return this
     */
    public SqlAutoOptions dialect(SqlDialect dialect) {
        this.dialect = dialect;
        return this;
    }

    /**
     * @return JDBC URL
     */
    public String url() {
        return url;
    }

    /**
     * @param url JDBC URL
     * @return this
     */
    public SqlAutoOptions url(String url) {
        this.url = url;
        return this;
    }

    /**
     * @return 用户名
     */
    public String username() {
        return username;
    }

    /**
     * @param username 用户名
     * @return this
     */
    public SqlAutoOptions username(String username) {
        this.username = username;
        return this;
    }

    /**
     * @return 密码
     */
    public String password() {
        return password;
    }

    /**
     * @param password 密码
     * @return this
     */
    public SqlAutoOptions password(String password) {
        this.password = password;
        return this;
    }

    /**
     * @return 驱动类名
     */
    public String driver() {
        return driver;
    }

    /**
     * @param driver 驱动类名
     * @return this
     */
    public SqlAutoOptions driver(String driver) {
        this.driver = driver;
        return this;
    }

    /**
     * @return 数据源，优先于 URL
     */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * @param dataSource 数据源
     * @return this
     */
    public SqlAutoOptions dataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    /**
     * @return 执行失败是否立即抛错
     */
    public boolean failFast() {
        return failFast;
    }

    /**
     * @param failFast 失败即停
     * @return this
     */
    public SqlAutoOptions failFast(boolean failFast) {
        this.failFast = failFast;
        return this;
    }

    /**
     * @return 已有列类型不一致时是否 {@code ALTER}
     */
    public boolean alterColumn() {
        return alterColumn;
    }

    /**
     * @param alterColumn 是否改列
     * @return this
     */
    public SqlAutoOptions alterColumn(boolean alterColumn) {
        this.alterColumn = alterColumn;
        return this;
    }

    /**
     * @return 是否删除实体里没有的列
     */
    public boolean dropExtraColumns() {
        return dropExtraColumns;
    }

    /**
     * @param dropExtraColumns 是否删多余列
     * @return this
     */
    public SqlAutoOptions dropExtraColumns(boolean dropExtraColumns) {
        this.dropExtraColumns = dropExtraColumns;
        return this;
    }

    /**
     * @return 是否补索引
     */
    public boolean createIndex() {
        return createIndex;
    }

    /**
     * @param createIndex 是否建索引
     * @return this
     */
    public SqlAutoOptions createIndex(boolean createIndex) {
        this.createIndex = createIndex;
        return this;
    }

    /**
     * @return 是否给标识符加方言引号
     */
    public boolean quoteIdentifiers() {
        return quoteIdentifiers;
    }

    /**
     * @param quoteIdentifiers 是否加引号
     * @return this
     */
    public SqlAutoOptions quoteIdentifiers(boolean quoteIdentifiers) {
        this.quoteIdentifiers = quoteIdentifiers;
        return this;
    }

    /**
     * @return 是否打印 SQL
     */
    public boolean showSql() {
        return showSql;
    }

    /**
     * @param showSql 是否打印
     * @return this
     */
    public SqlAutoOptions showSql(boolean showSql) {
        this.showSql = showSql;
        return this;
    }

    /**
     * @return 只规划不执行
     */
    public boolean dryRun() {
        return dryRun;
    }

    /**
     * @param dryRun 只规划
     * @return this
     */
    public SqlAutoOptions dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /**
     * @return JDBC catalog，可空
     */
    public String catalog() {
        return catalog;
    }

    /**
     * @param catalog catalog
     * @return this
     */
    public SqlAutoOptions catalog(String catalog) {
        this.catalog = catalog;
        return this;
    }

    /**
     * @return JDBC schema，可空
     */
    public String schema() {
        return schema;
    }

    /**
     * @return PG / OpenGauss 自增写法；null 表示用转换器默认 IDENTITY
     */
    public SqlSchemaConvertOptions.PostgresIdentityStyle postgresIdentityStyle() {
        return postgresIdentityStyle;
    }

    /**
     * OpenGauss 老版本不认 {@code GENERATED … IDENTITY}，可改成 {@code SERIAL}。
     *
     * @param postgresIdentityStyle 自增写法
     * @return this
     */
    public SqlAutoOptions postgresIdentityStyle(
            SqlSchemaConvertOptions.PostgresIdentityStyle postgresIdentityStyle) {
        this.postgresIdentityStyle = postgresIdentityStyle;
        return this;
    }

    /**
     * @return 是否在 CREATE TABLE 里写 FOREIGN KEY
     */
    public boolean foreignKeys() {
        return foreignKeys;
    }

    /**
     * GBase 8a 等不支持表内 FOREIGN KEY 算法时关掉。
     *
     * @param foreignKeys 是否生成外键
     * @return this
     */
    public SqlAutoOptions foreignKeys(boolean foreignKeys) {
        this.foreignKeys = foreignKeys;
        return this;
    }

    /**
     * @return 是否生成自增子句
     */
    public boolean autoIncrement() {
        return autoIncrement;
    }

    /**
     * DuckDB 等不认 AUTOINCREMENT / IDENTITY 时关掉，只保留主键。
     *
     * @param autoIncrement 是否自增
     * @return this
     */
    public SqlAutoOptions autoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
        return this;
    }

    /**
     * @param schema schema
     * @return this
     */
    public SqlAutoOptions schema(String schema) {
        this.schema = schema;
        return this;
    }

    private static String first(ConfigPropertyResolver resolver, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String v = resolver.getString(keys[i]);
            if (v != null && v.length() > 0) {
                return v;
            }
        }
        return null;
    }

    private static boolean bool(ConfigPropertyResolver resolver, String key, boolean defaultValue) {
        Boolean v = resolver.getBoolean(key);
        return v == null ? defaultValue : v.booleanValue();
    }

    private static List<String> stringList(ConfigPropertyResolver resolver, String key) {
        List<String> raw = resolver.getList(key, String.class);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            String s = raw.get(i);
            if (s == null) {
                continue;
            }
            String[] parts = s.split(",");
            for (int j = 0; j < parts.length; j++) {
                String p = parts[j].trim();
                if (p.length() > 0) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static List<Class<?>> classList(ConfigPropertyResolver resolver, String key) {
        List<String> names = stringList(resolver, key);
        List<Class<?>> out = new ArrayList<Class<?>>(names.size());
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SqlAutoOptions.class.getClassLoader();
        }
        for (int i = 0; i < names.size(); i++) {
            try {
                out.add(Class.forName(names.get(i), false, cl));
            } catch (ClassNotFoundException e) {
                throw new SqlAutoException("entity class not found: " + names.get(i), e);
            }
        }
        return out;
    }
}
