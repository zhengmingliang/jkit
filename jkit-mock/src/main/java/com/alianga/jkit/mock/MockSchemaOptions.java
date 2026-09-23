package com.alianga.jkit.mock;

/**
 * 由 JSON Schema 生成样例数据的选项。
 *
 * <p>配合 {@link MockSchema} 使用，控制「非必填属性是否生成」「未限定长度时数组生成几条」
 * 「是否优先取 {@code default}」「{@code $ref} 递归深度上限」。</p>
 *
 * @author 郑明亮
 */
public final class MockSchemaOptions {
    private static final MockSchemaOptions DEFAULTS = builder().build();

    private final boolean includeOptional;
    private final int arrayMinItems;
    private final int arrayMaxItems;
    private final boolean useDefaults;
    private final int maxDepth;

    private MockSchemaOptions(Builder builder) {
        this.includeOptional = builder.includeOptional;
        this.arrayMinItems = Math.max(0, builder.arrayMinItems);
        this.arrayMaxItems = Math.max(this.arrayMinItems, builder.arrayMaxItems);
        this.useDefaults = builder.useDefaults;
        this.maxDepth = Math.max(1, builder.maxDepth);
    }

    /**
     * 默认选项：包含非必填属性、数组 1~3 条、不使用 default、最大深度 8。
     *
     * @return 默认选项
     */
    public static MockSchemaOptions defaults() {
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

    /**
     * 是否生成非必填（{@code required} 之外）的属性。
     *
     * @return true 表示生成
     */
    public boolean isIncludeOptional() {
        return includeOptional;
    }

    /**
     * 未声明 {@code minItems} 时数组的最少条数。
     *
     * @return 最少条数
     */
    public int getArrayMinItems() {
        return arrayMinItems;
    }

    /**
     * 未声明 {@code maxItems} 时数组的最多条数。
     *
     * @return 最多条数
     */
    public int getArrayMaxItems() {
        return arrayMaxItems;
    }

    /**
     * 属性声明了 {@code default} 时是否直接取该值。
     *
     * @return true 表示取 default
     */
    public boolean isUseDefaults() {
        return useDefaults;
    }

    /**
     * 嵌套与 {@code $ref} 解析的最大深度，超过后不再展开（防止循环引用死递归）。
     *
     * @return 最大深度
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** 构建器。 */
    public static final class Builder {
        private boolean includeOptional = true;
        private int arrayMinItems = 1;
        private int arrayMaxItems = 3;
        private boolean useDefaults;
        private int maxDepth = 8;

        /**
         * 是否生成非必填属性，默认 true（样例数据通常希望看到完整结构）。
         *
         * @param includeOptional true 表示生成
         * @return 构建器
         */
        public Builder includeOptional(boolean includeOptional) {
            this.includeOptional = includeOptional;
            return this;
        }

        /**
         * 未声明长度时数组的条数区间。
         *
         * @param min 最少条数
         * @param max 最多条数
         * @return 构建器
         */
        public Builder arrayItems(int min, int max) {
            this.arrayMinItems = min;
            this.arrayMaxItems = max;
            return this;
        }

        /**
         * 属性声明了 {@code default} 时是否直接取该值，默认 false（保持样例随机）。
         *
         * @param useDefaults true 表示取 default
         * @return 构建器
         */
        public Builder useDefaults(boolean useDefaults) {
            this.useDefaults = useDefaults;
            return this;
        }

        /**
         * 嵌套与 {@code $ref} 解析的最大深度。
         *
         * @param maxDepth 最大深度
         * @return 构建器
         */
        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        /**
         * 构建选项。
         *
         * @return 选项
         */
        public MockSchemaOptions build() {
            return new MockSchemaOptions(this);
        }
    }
}
