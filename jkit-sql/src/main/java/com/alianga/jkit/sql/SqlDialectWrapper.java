package com.alianga.jkit.sql;

/**
 * 方言能力包装层：委托给一个基方言（枚举或另一规约），按需覆写个别能力，
 * 用于「接近某内置方言但有一两处差异」的场景（如 MySQL 开了 ANSI_QUOTES、
 * 某网关不支持逗号分页）。
 *
 * <p>用法：匿名子类或具名子类覆写 {@link SqlDialectSpec} 的能力方法：</p>
 *
 * <pre>{@code
 * SqlDialectSpec noBackslash = new SqlDialectWrapper(SqlDialect.MYSQL) {
 *     @Override
 *     public boolean backslashEscapes() {
 *         return false; // NO_BACKSLASH_ESCAPES
 *     }
 * };
 * SqlStatement stmt = SQL.parse("SELECT 'a\\'b'", noBackslash);
 * }</pre>
 *
 * <p>原语能力（引号开闭以外的开关、分页形态、{@link #maxIdentifierLength()}、
 * {@link #dialectId()} / {@link #typeFamily()}）
 * 单次委托给基方言。派生方法不要在本类覆写：{@link #identQuoteClose()}、
 * {@link #quoteIdent(String)}、{@link #pipesAreConcat()}、{@link #preferredLimitStyle()}、
 * {@link #fitIdentifier(String)}
 * 走接口默认实现，会读到子类已覆写的原语（例如只改 {@link #identQuoteOpen()} 为 {@code '['}
 * 时，闭引号自动变成 {@code ']'}）。</p>
 *
 * <p>覆写时注意保持能力之间的自洽
 * （如 {@link #supportsLimitOffset()} 关闭时不宜打开 {@link #supportsCommaLimitOffset()}）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlDialectWrapper implements SqlDialectSpec {
    private final SqlDialectSpec base;

    /**
     * @param base 基方言，null 视为 {@link SqlDialect#MYSQL}
     */
    public SqlDialectWrapper(SqlDialectSpec base) {
        this.base = base == null ? SqlDialect.MYSQL : base;
    }

    /**
     * @return 被委托的基方言
     */
    protected SqlDialectSpec base() {
        return base;
    }

    @Override
    public char identQuoteOpen() {
        return base.identQuoteOpen();
    }

    @Override
    public boolean pipesAsOr() {
        return base.pipesAsOr();
    }

    @Override
    public boolean doubleQuoteIsString() {
        return base.doubleQuoteIsString();
    }

    @Override
    public boolean hashLineComment() {
        return base.hashLineComment();
    }

    @Override
    public boolean backslashEscapes() {
        return base.backslashEscapes();
    }

    @Override
    public boolean bracketIdentifiers() {
        return base.bracketIdentifiers();
    }

    @Override
    public boolean supportsTildeRegex() {
        return base.supportsTildeRegex();
    }

    @Override
    public boolean supportsLimitOffset() {
        return base.supportsLimitOffset();
    }

    @Override
    public boolean supportsTop() {
        return base.supportsTop();
    }

    @Override
    public boolean supportsFetchFirst() {
        return base.supportsFetchFirst();
    }

    @Override
    public boolean supportsRownum() {
        return base.supportsRownum();
    }

    @Override
    public boolean supportsCommaLimitOffset() {
        return base.supportsCommaLimitOffset();
    }

    @Override
    public int maxIdentifierLength() {
        return base.maxIdentifierLength();
    }

    @Override
    public String dialectId() {
        return base.dialectId();
    }

    @Override
    public SqlDialect typeFamily() {
        return base.typeFamily();
    }
}
