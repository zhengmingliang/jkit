package com.alianga.jkit.sql.schema.registry;

/**
 * 某 canonical 类型在一个方言下的写法模板。
 *
 * <p>pattern 含 {@code %d} 才拼接精度/标度；不含则视为完整字面量，忽略传入的 precision/scale。
 * 有占位符但 precision 为空时，回落为去掉括号的裸类型名（{@code VARCHAR} 而非 {@code VARCHAR(null)}）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class DialectTypeForm {
    private final String pattern;
    private final int placeholders;

    private DialectTypeForm(String pattern) {
        this.pattern = pattern == null ? "" : pattern;
        this.placeholders = countPlaceholders(this.pattern);
    }

    /**
     * @param pattern 写法模板或完整字面量
     * @return 表单项
     */
    public static DialectTypeForm of(String pattern) {
        return new DialectTypeForm(pattern);
    }

    /**
     * @return 原始模板
     */
    public String pattern() {
        return pattern;
    }

    /**
     * @return {@code %d} 占位数量
     */
    public int placeholders() {
        return placeholders;
    }

    /**
     * @return 去掉括号参数的裸类型名
     */
    public String baseName() {
        int paren = pattern.indexOf('(');
        if (paren < 0) {
            return pattern;
        }
        return pattern.substring(0, paren);
    }

    /**
     * 渲染目标方言写法。
     *
     * @param precision 精度，可空
     * @param scale 标度，可空
     * @return 方言类型文本
     */
    public String render(Integer precision, Integer scale) {
        if (placeholders == 0) {
            return pattern;
        }
        if (placeholders == 1) {
            if (precision == null) {
                return baseName();
            }
            return String.format(pattern, precision);
        }
        if (precision == null && scale == null) {
            return baseName();
        }
        int p = precision == null ? 10 : precision.intValue();
        int s = scale == null ? 0 : scale.intValue();
        return String.format(pattern, Integer.valueOf(p), Integer.valueOf(s));
    }

    private static int countPlaceholders(String pattern) {
        int n = 0;
        int from = 0;
        while (true) {
            int i = pattern.indexOf("%d", from);
            if (i < 0) {
                return n;
            }
            n++;
            from = i + 2;
        }
    }
}
