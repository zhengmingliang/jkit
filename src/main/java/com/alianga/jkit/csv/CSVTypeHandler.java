package com.alianga.jkit.csv;

import com.alianga.jkit.beans.ObjectUtils;

/**
 * @time 2024/1/12 9:21
 */
public abstract class CSVTypeHandler<T> {
    /**
     * 默认的 CSV 类型处理器，直接借助 {@link ObjectUtils#toType(Object, Class)} 完成字符串到目标类型的转换。
     */
    public static final class DefaultCSVTypeHandler extends CSVTypeHandler {
        @Override
        public Object handle(String input, Class type) {
            return ObjectUtils.toType(input, type);
        }
    }

    /**
     * 默认构造方法。
     */
    public CSVTypeHandler() {
    }

    /**
     * 将 CSV 单元格的原始字符串转换为目标类型的值。
     *
     * @param input CSV 单元格的原始字符串内容
     * @param type  期望转换的目标类型
     * @return 转换后的目标类型值
     * @throws Throwable 转换过程中抛出的任意异常
     */
    public abstract T handle(String input, Class<T> type) throws Throwable;

}
