package com.alianga.jkit.csv;

import java.util.List;

/**
 * 流式读取的行回调，拿到的是这一行的原始字段列表。
 * <p>
 * 用于 {@link CSVUtils#readStream(java.io.File, CSVValuesHandler)} 一族方法；需要按列名取值或
 * 转 JavaBean 时用 {@link CSVRowHandler} 与 {@link CSV#readStream(java.io.File, CSVRowHandler)}。
 */
public interface CSVValuesHandler {
    /**
     * 处理解析出的一行。
     *
     * @param values 本行的字段列表，字段已去除外层引号并还原 {@code ""} 转义；每行都是新的列表，
     *               归调用方所有，但不要把所有行都存下来，否则又变回全量占内存
     * @param rowIndex 行下标，从 0 开始；带表头的文件里 0 就是表头行
     * @return {@code true} 继续读取，{@code false} 提前终止
     */
    boolean handle(List<String> values, long rowIndex);
}
