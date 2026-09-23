package com.alianga.jkit.mock;

/**
 * SQL INSERT 输出选项：数据类型模式、批量插入、标识符引号、建表语句与表名。
 *
 * <p>两种模式共用：字段模式经 {@link MockDataProducer#setSqlOptions(MockSqlOptions)}
 * 生效，模板模式由界面直接调用 {@link MockDataFormatter#toSql(List, MockSqlOptions)}。</p>
 *
 * @author 郑明亮
 */
public final class MockSqlOptions {
    /** 列类型推断模式。 */
    public enum TypeMode {
        /** 按首条记录的值推断（整数 BIGINT / 小数 DOUBLE / 布尔 BOOLEAN / 其余 VARCHAR）。 */
        AUTO,
        /** 全部 VARCHAR(255)，与旧版行为一致。 */
        VARCHAR,
        /** 全部 TEXT。 */
        TEXT
    }

    /** 标识符（表名 / 列名）引号包裹方式。 */
    public enum Quote {
        /** 不包裹。 */
        NONE,
        /** MySQL 风格反引号 {@code `name`}。 */
        BACKTICK,
        /** ANSI 风格双引号 {@code "name"}。 */
        DOUBLE_QUOTE
    }

    private static final MockSqlOptions DEFAULTS = builder().build();

    private final TypeMode typeMode;
    private final boolean batch;
    private final Quote quote;
    private final String tableName;
    private final boolean createTable;

    private MockSqlOptions(Builder builder) {
        this.typeMode = builder.typeMode;
        this.batch = builder.batch;
        this.quote = builder.quote;
        this.tableName = builder.tableName;
        this.createTable = builder.createTable;
    }

    /**
     * 默认选项：自动类型、逐条 INSERT、不加引号、表名 fake_data、含建表语句。
     *
     * @return 默认选项
     */
    public static MockSqlOptions defaults() {
        return DEFAULTS;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public TypeMode getTypeMode() {
        return typeMode;
    }

    public boolean isBatch() {
        return batch;
    }

    public Quote getQuote() {
        return quote;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isCreateTable() {
        return createTable;
    }

    /** 构建器。 */
    public static final class Builder {
        private TypeMode typeMode = TypeMode.AUTO;
        private boolean batch;
        private Quote quote = Quote.NONE;
        private String tableName = "fake_data";
        private boolean createTable = true;

        /**
         * 设置列类型模式。
         *
         * @param typeMode 类型模式
         * @return 构建器
         */
        public Builder typeMode(TypeMode typeMode) {
            this.typeMode = typeMode == null ? TypeMode.AUTO : typeMode;
            return this;
        }

        /**
         * 是否合并为一条批量 INSERT。
         *
         * @param batch true 批量
         * @return 构建器
         */
        public Builder batch(boolean batch) {
            this.batch = batch;
            return this;
        }

        /**
         * 设置标识符引号。
         *
         * @param quote 引号方式
         * @return 构建器
         */
        public Builder quote(Quote quote) {
            this.quote = quote == null ? Quote.NONE : quote;
            return this;
        }

        /**
         * 设置表名。
         *
         * @param tableName 表名
         * @return 构建器
         */
        public Builder tableName(String tableName) {
            if (tableName != null && !tableName.trim().isEmpty()) {
                this.tableName = tableName.trim();
            }
            return this;
        }

        /**
         * 是否输出 CREATE TABLE 建表语句。
         *
         * @param createTable true 含建表
         * @return 构建器
         */
        public Builder createTable(boolean createTable) {
            this.createTable = createTable;
            return this;
        }

        /**
         * 构建选项。
         *
         * @return 选项
         */
        public MockSqlOptions build() {
            return new MockSqlOptions(this);
        }
    }
}
