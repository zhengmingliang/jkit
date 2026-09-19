package com.alianga.jkit.sql;

/**
 * 行级注入的全局默认与请求级当前配置（切面 / 拦截器）。
 *
 * <p>解析顺序：{@link #current()} → {@link #getDefault()}。拦截器里
 * {@link #setCurrent} 后 {@link SQL#inject}，{@code finally} 里 {@link #clear}。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlInject {
    private static volatile SqlInjectConfig defaults;
    private static final ThreadLocal<SqlInjectConfig> CURRENT = new ThreadLocal<SqlInjectConfig>();

    private SqlInject() {
    }

    /**
     * 启动时设置全局默认（表白名单、列名、动态取值）。
     *
     * @param config 配置，null 清空
     */
    public static void setDefault(SqlInjectConfig config) {
        defaults = config;
    }

    /**
     * @return 全局默认，未设时 null
     */
    public static SqlInjectConfig getDefault() {
        return defaults;
    }

    /**
     * 本线程当前配置（请求级覆盖全局默认）。
     *
     * @param config 配置，null 等同 {@link #clear()}
     */
    public static void setCurrent(SqlInjectConfig config) {
        if (config == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(config);
        }
    }

    /**
     * @return 当前线程配置，没有则全局默认，都没有则 null
     */
    public static SqlInjectConfig current() {
        SqlInjectConfig local = CURRENT.get();
        return local != null ? local : defaults;
    }

    /**
     * 清掉本线程配置，回落到全局默认。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
