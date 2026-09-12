package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring / 配置绑定用的扁平设置，再转成 {@link SqlAutoOptions}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlAutoSettings {
    private boolean enabled = true;
    private String mode = "update";
    private List<String> packages = new ArrayList<String>(2);
    private List<String> entities = new ArrayList<String>(2);
    private String dialect;
    private String url;
    private String username;
    private String password;
    private String driver;
    private boolean failFast = true;
    private boolean alterColumn;
    private boolean dropExtraColumns;
    private boolean createIndex = true;
    private boolean quoteIdentifiers;
    private boolean showSql = true;
    private boolean dryRun;
    private String catalog;
    private String schema;

    /**
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 模式名
     */
    public String getMode() {
        return mode;
    }

    /**
     * @param mode 模式名
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * @return 扫描包
     */
    public List<String> getPackages() {
        return packages;
    }

    /**
     * @param packages 扫描包
     */
    public void setPackages(List<String> packages) {
        this.packages = packages == null ? new ArrayList<String>(0) : packages;
    }

    /**
     * @return 实体 FQCN
     */
    public List<String> getEntities() {
        return entities;
    }

    /**
     * @param entities 实体 FQCN
     */
    public void setEntities(List<String> entities) {
        this.entities = entities == null ? new ArrayList<String>(0) : entities;
    }

    /**
     * @return 方言名
     */
    public String getDialect() {
        return dialect;
    }

    /**
     * @param dialect 方言名
     */
    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    /**
     * @return JDBC URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * @param url JDBC URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * @return 密码
     */
    public String getPassword() {
        return password;
    }

    /**
     * @param password 密码
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * @return 驱动类
     */
    public String getDriver() {
        return driver;
    }

    /**
     * @param driver 驱动类
     */
    public void setDriver(String driver) {
        this.driver = driver;
    }

    /**
     * @return 失败即停
     */
    public boolean isFailFast() {
        return failFast;
    }

    /**
     * @param failFast 失败即停
     */
    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    /**
     * @return 是否改列
     */
    public boolean isAlterColumn() {
        return alterColumn;
    }

    /**
     * @param alterColumn 是否改列
     */
    public void setAlterColumn(boolean alterColumn) {
        this.alterColumn = alterColumn;
    }

    /**
     * @return 是否删多余列
     */
    public boolean isDropExtraColumns() {
        return dropExtraColumns;
    }

    /**
     * @param dropExtraColumns 是否删多余列
     */
    public void setDropExtraColumns(boolean dropExtraColumns) {
        this.dropExtraColumns = dropExtraColumns;
    }

    /**
     * @return 是否建索引
     */
    public boolean isCreateIndex() {
        return createIndex;
    }

    /**
     * @param createIndex 是否建索引
     */
    public void setCreateIndex(boolean createIndex) {
        this.createIndex = createIndex;
    }

    /**
     * @return 是否加引号
     */
    public boolean isQuoteIdentifiers() {
        return quoteIdentifiers;
    }

    /**
     * @param quoteIdentifiers 是否加引号
     */
    public void setQuoteIdentifiers(boolean quoteIdentifiers) {
        this.quoteIdentifiers = quoteIdentifiers;
    }

    /**
     * @return 是否打 SQL 日志
     */
    public boolean isShowSql() {
        return showSql;
    }

    /**
     * @param showSql 是否打日志
     */
    public void setShowSql(boolean showSql) {
        this.showSql = showSql;
    }

    /**
     * @return 只规划不执行；{@link SqlAuto#run(SqlAutoOptions)} 时不打开 JDBC
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * @param dryRun 只规划不执行
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * @return catalog
     */
    public String getCatalog() {
        return catalog;
    }

    /**
     * @param catalog catalog
     */
    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    /**
     * @return schema
     */
    public String getSchema() {
        return schema;
    }

    /**
     * @param schema schema
     */
    public void setSchema(String schema) {
        this.schema = schema;
    }

    /**
     * @return 选项
     */
    public SqlAutoOptions toOptions() {
        SqlAutoOptions o = SqlAutoOptions.defaults()
                .enabled(enabled)
                .mode(SqlAutoMode.fromName(mode))
                .packages(packages)
                .failFast(failFast)
                .alterColumn(alterColumn)
                .dropExtraColumns(dropExtraColumns)
                .createIndex(createIndex)
                .quoteIdentifiers(quoteIdentifiers)
                .showSql(showSql)
                .dryRun(dryRun)
                .url(url)
                .username(username)
                .password(password)
                .driver(driver)
                .catalog(catalog)
                .schema(schema);
        if (dialect != null && dialect.length() > 0) {
            o.dialect(SqlDialect.fromName(dialect));
        }
        if (entities != null && !entities.isEmpty()) {
            List<Class<?>> types = new ArrayList<Class<?>>(entities.size());
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = SqlAutoSettings.class.getClassLoader();
            }
            for (int i = 0; i < entities.size(); i++) {
                String name = entities.get(i);
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                try {
                    types.add(Class.forName(name.trim(), false, cl));
                } catch (ClassNotFoundException e) {
                    throw new SqlAutoException("entity class not found: " + name, e);
                }
            }
            o.entities(types);
        }
        return o;
    }
}
