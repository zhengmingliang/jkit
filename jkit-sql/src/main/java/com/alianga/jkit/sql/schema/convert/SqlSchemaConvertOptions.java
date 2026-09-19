package com.alianga.jkit.sql.schema.convert;

import com.alianga.jkit.sql.SqlDialect;

import java.util.function.BiConsumer;

/**
 * 跨方言转换选项。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSchemaConvertOptions {
    /**
     * {@code UNSIGNED} 处理策略。
     */
    public enum UnsignedHandling {
        /** 升级到下一档有符号类型以覆盖同样的正数范围（默认）。 */
        UPSIZE,
        /** 保持原宽度，仅告警建议加 CHECK。 */
        ADD_CHECK,
        /** 丢弃 UNSIGNED 并告警。 */
        DROP_AND_WARN
    }

    /**
     * PostgreSQL 自增写法。
     */
    public enum PostgresIdentityStyle {
        /** {@code GENERATED ALWAYS AS IDENTITY}（默认）。 */
        IDENTITY,
        /** legacy {@code SERIAL} / {@code BIGSERIAL}。 */
        SERIAL
    }

    private UnsignedHandling unsignedHandling = UnsignedHandling.UPSIZE;
    private boolean stripDialectOptions = true;
    private PostgresIdentityStyle postgresIdentityStyle = PostgresIdentityStyle.IDENTITY;
    private ConversionWarning.Severity failOnSeverity;
    private boolean generateOracleSequence;
    private boolean parallelBatch;
    private boolean promoteLongVarchar = true;
    private boolean includeForeignKeys = true;
    private boolean includeAutoIncrement = true;
    private boolean quoteIdentifiers;
    private boolean keywordQuote;
    private BiConsumer<SqlDialect, String> keywordQuotedListener;

    private SqlSchemaConvertOptions() {
    }

    /**
     * @return 默认选项
     */
    public static SqlSchemaConvertOptions defaults() {
        return new SqlSchemaConvertOptions();
    }

    /**
     * @return UNSIGNED 策略
     */
    public UnsignedHandling unsignedHandling() {
        return unsignedHandling;
    }

    /**
     * @param unsignedHandling UNSIGNED 策略
     * @return this
     */
    public SqlSchemaConvertOptions unsignedHandling(UnsignedHandling unsignedHandling) {
        if (unsignedHandling != null) {
            this.unsignedHandling = unsignedHandling;
        }
        return this;
    }

    /**
     * @return 目标方言不支持时是否剥掉 CHARSET/COLLATE/ENGINE
     */
    public boolean stripDialectOptions() {
        return stripDialectOptions;
    }

    /**
     * @param stripDialectOptions 是否剥离方言专属表/列选项
     * @return this
     */
    public SqlSchemaConvertOptions stripDialectOptions(boolean stripDialectOptions) {
        this.stripDialectOptions = stripDialectOptions;
        return this;
    }

    /**
     * @return PG 自增写法
     */
    public PostgresIdentityStyle postgresIdentityStyle() {
        return postgresIdentityStyle;
    }

    /**
     * @param postgresIdentityStyle PG 自增写法
     * @return this
     */
    public SqlSchemaConvertOptions postgresIdentityStyle(PostgresIdentityStyle postgresIdentityStyle) {
        if (postgresIdentityStyle != null) {
            this.postgresIdentityStyle = postgresIdentityStyle;
        }
        return this;
    }

    /**
     * @return 达到该级别则抛错；null 表示只收集不阻断
     */
    public ConversionWarning.Severity failOnSeverity() {
        return failOnSeverity;
    }

    /**
     * @param failOnSeverity 阻断级别，null 表示不阻断
     * @return this
     */
    public SqlSchemaConvertOptions failOnSeverity(ConversionWarning.Severity failOnSeverity) {
        this.failOnSeverity = failOnSeverity;
        return this;
    }

    /**
     * @return Oracle ≤11g 是否生成 SEQUENCE+TRIGGER（默认 false，只告警）
     */
    public boolean generateOracleSequence() {
        return generateOracleSequence;
    }

    /**
     * @param generateOracleSequence 是否生成 SEQUENCE+TRIGGER
     * @return this
     */
    public SqlSchemaConvertOptions generateOracleSequence(boolean generateOracleSequence) {
        this.generateOracleSequence = generateOracleSequence;
        return this;
    }

    /**
     * @return 批量转换是否并行
     */
    public boolean parallelBatch() {
        return parallelBatch;
    }

    /**
     * @param parallelBatch 并行
     * @return this
     */
    public SqlSchemaConvertOptions parallelBatch(boolean parallelBatch) {
        this.parallelBatch = parallelBatch;
        return this;
    }

    /**
     * @return 超长 VARCHAR 是否提升为 TEXT/CLOB（默认 true，阈值按方言：MySQL/Oracle 4000）
     */
    public boolean promoteLongVarchar() {
        return promoteLongVarchar;
    }

    /**
     * @param promoteLongVarchar 是否提升
     * @return this
     */
    public SqlSchemaConvertOptions promoteLongVarchar(boolean promoteLongVarchar) {
        this.promoteLongVarchar = promoteLongVarchar;
        return this;
    }

    /**
     * @return 建表是否写 FOREIGN KEY
     */
    public boolean includeForeignKeys() {
        return includeForeignKeys;
    }

    /**
     * @param includeForeignKeys 是否写外键
     * @return this
     */
    public SqlSchemaConvertOptions includeForeignKeys(boolean includeForeignKeys) {
        this.includeForeignKeys = includeForeignKeys;
        return this;
    }

    /**
     * @return 是否生成自增子句
     */
    public boolean includeAutoIncrement() {
        return includeAutoIncrement;
    }

    /**
     * @param includeAutoIncrement 是否自增
     * @return this
     */
    /**
     * @return 是否给表名 / 列名等标识符加方言引号（用于列名撞目标库保留字的场景）
     */
    public boolean quoteIdentifiers() {
        return quoteIdentifiers;
    }

    /**
     * @param quoteIdentifiers 是否给标识符加方言引号
     * @return this
     */
    public SqlSchemaConvertOptions quoteIdentifiers(boolean quoteIdentifiers) {
        this.quoteIdentifiers = quoteIdentifiers;
        return this;
    }

    /**
     * @return 是否对撞目标库保留字的标识符自动加引号并告警（quote-identifiers 关闭时也生效）
     * @since 2.0.3
     */
    public boolean keywordQuote() {
        return keywordQuote;
    }

    /**
     * 开启后，标识符默认仍不加引号（大小写交给库折叠），但检测到是目标库保留字时
     * 自动加方言引号并打印一次告警。与 {@link #quoteIdentifiers(boolean)} 叠加：
     * 后者无条件全量加引号。
     *
     * @param keywordQuote 是否启用保留字自动引号
     * @return this
     * @since 2.0.3
     */
    public SqlSchemaConvertOptions keywordQuote(boolean keywordQuote) {
        this.keywordQuote = keywordQuote;
        return this;
    }

    /**
     * @return 保留字自动引号的告警回调，可空；入参为 (方言, 标识符原文)
     * @since 2.0.3
     */
    public BiConsumer<SqlDialect, String> keywordQuotedListener() {
        return keywordQuotedListener;
    }

    /**
     * 注册保留字自动引号的告警回调。同一标识符（同方言）全局只回调一次；
     * jkit-sql 自身不打日志，由宿主（如 jkit-sql-auto）决定输出方式。
     *
     * @param listener 回调，可空
     * @return this
     * @since 2.0.3
     */
    public SqlSchemaConvertOptions keywordQuotedListener(BiConsumer<SqlDialect, String> listener) {
        this.keywordQuotedListener = listener;
        return this;
    }

    public SqlSchemaConvertOptions includeAutoIncrement(boolean includeAutoIncrement) {
        this.includeAutoIncrement = includeAutoIncrement;
        return this;
    }
}
