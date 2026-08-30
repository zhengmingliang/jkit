package com.alianga.jkit.csv;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * 流式 CSV 写出器：逐行写出，不需要先把全部数据攒进内存，内存占用只和单行大小有关。
 * <p>
 * 转义规则按 CSV 规范：字段里出现 {@code ,}、{@code "}、{@code \n} 或 {@code \r} 时整体加引号，
 * 内部的 {@code "} 双写成 {@code ""}，其余字段原样输出，{@code null} 写成空串。
 * <p>
 * 实例是有状态的，不要跨线程共享；用完必须 {@link #close()}，否则缓冲区不落盘。
 * 由 {@link CSVUtils#writer(java.io.File, String...)}（平台行分隔符）与
 * {@link CSV#writer(java.io.File, String...)}（固定 {@code \n}）创建。
 */
public class CSVWriter implements Closeable, Flushable {
    private final Writer writer;
    private final String lineSeparator;
    private long rowCount;

    CSVWriter(Writer writer, String lineSeparator) {
        this.writer = writer;
        this.lineSeparator = lineSeparator;
    }

    /**
     * 写出一行。
     *
     * @param values 该行各列的值，{@code null} 元素写成空串
     * @throws IOException 写出失败时抛出
     */
    public void writeRow(List<String> values) throws IOException {
        if (values == null) {
            return;
        }
        int size = values.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                writer.write(CSVUtils.DEFAULT_DELIMITER);
            }
            writeValue(writer, values.get(i));
        }
        writer.write(lineSeparator);
        ++rowCount;
    }

    /**
     * 写出一行。
     *
     * @param values 该行各列的值，{@code null} 元素写成空串
     * @throws IOException 写出失败时抛出
     */
    public void writeRow(String... values) throws IOException {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(CSVUtils.DEFAULT_DELIMITER);
            }
            writeValue(writer, values[i]);
        }
        writer.write(lineSeparator);
        ++rowCount;
    }

    /**
     * 已写出的行数（含表头行）。
     *
     * @return 已写出的行数
     */
    public long getRowCount() {
        return rowCount;
    }

    @Override
    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * 写出一行到任意 Appendable，供 {@link CSVTable} 序列化复用
     *
     * @param appendable 输出目标
     * @param values 该行各列的值
     * @param lineSeparator 行分隔符
     * @throws IOException 写出失败时抛出
     */
    static void writeRow(Appendable appendable, List<String> values, String lineSeparator) throws IOException {
        int i = 0;
        for (String value : values) {
            if (i++ > 0) {
                appendable.append(CSVUtils.DEFAULT_DELIMITER);
            }
            writeValue(appendable, value);
        }
        appendable.append(lineSeparator);
    }

    /**
     * 写入一个字段，字段中出现逗号、双引号或换行时整体加引号，双引号按 CSV 规范写成两个
     *
     * @param appendable 输出目标
     * @param value 字段内容，{@code null} 写成空串
     * @throws IOException 写出失败时抛出
     */
    static void writeValue(Appendable appendable, String value) throws IOException {
        if (value == null) {
            return;
        }
        int len = value.length();
        int begin = 0;
        int j = 0;
        boolean escape = false;
        char ch = 0;
        for (; ;) {
            // 遇到逗号、双引号或换行需要转义
            while (j < len && (ch = value.charAt(j)) != CSVUtils.DEFAULT_DELIMITER && ch != '"' && ch != '\n'
                    && ch != '\r') {
                ++j;
            }
            if (j == len) {
                appendable.append(value, begin, j);
                if (escape) {
                    appendable.append('"');
                }
                return;
            }
            ++j;
            if (!escape) {
                escape = true;
                appendable.append('"');
            }
            appendable.append(value, begin, j);
            if (ch == '"') {
                appendable.append('"');
            }
            begin = j;
        }
    }
}
