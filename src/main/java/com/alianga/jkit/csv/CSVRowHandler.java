package com.alianga.jkit.csv;

/**
 * 流式读取的行回调，拿到的是绑定了表头的 {@link CSVRow}，因此可以直接
 * {@link CSVRow#get(String)} 按列名取值、{@link CSVRow#toBean(Class)} 转实体、
 * {@link CSVRow#toMap()} 转 Map。
 * <p>
 * 用于 {@link CSV#readStream(java.io.File, CSVRowHandler)} 一族方法。首行按 {@code csv} 包的
 * 惯例当表头，不会回调；回调只针对数据行。
 */
public interface CSVRowHandler {
    /**
     * 处理解析出的一行数据。
     *
     * @param row 本行数据，已绑定表头；每行都是新对象，归调用方所有，但不要把所有行都存下来
     * @param rowIndex 数据行下标，从 0 开始，不含表头行
     * @return {@code true} 继续读取，{@code false} 提前终止
     */
    boolean handle(CSVRow row, long rowIndex);
}
