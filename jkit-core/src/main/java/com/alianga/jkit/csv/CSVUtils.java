package com.alianga.jkit.csv;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 CSV 读写工具（纯 JDK 实现，零第三方依赖），数据模型就是 {@code List<List<String>>}。
 * <p>
 * 由 ZmlTools 的 {@code CSVUtils} 迁移而来，原实现依赖 Apache Commons CSV；
 * 此处使用 JDK 原生 IO 重新实现，支持：
 * <ul>
 * <li>字段包含逗号、双引号或换行时自动加引号，引号按 CSV 规范双写转义；</li>
 * <li>解析时正确处理带引号字段内的逗号、换行（{@code \r\n}/{@code \n}/{@code \r}）与转义引号；</li>
 * <li>空行自动忽略；可选表头（写）与首行表头（读）。</li>
 * </ul>
 * <p>
 * 两种用法：{@link #read(File)} / {@link #write(File, List, String...)} 把数据全量放在内存里，
 * 适合中小文件；{@link #readStream(File, CSVValuesHandler)} 与 {@link #writer(File, String...)}
 * 逐行处理，内存占用与文件大小无关，百万行、上百 MB 的文件用这一组。
 * <p>
 * 与同包的 {@link CSV} 共用 {@link CSVParser} 解析器与 {@link CSVWriter} 转义实现，区别只在数据
 * 模型和默认值：这里默认 UTF-8、不区分表头、字段两侧空格原样保留、引号格式非法时按宽松方式收下；
 * {@link CSV} 默认平台字符集、首行总是表头、去掉字段两侧空格、引号格式非法直接抛异常。
 *
 * @since 1.0.0
 */
public class CSVUtils {
    /** 默认编码 */
    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    /** 默认列分隔符 */
    public static final char DEFAULT_DELIMITER = ',';

    private CSVUtils() {
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 将行数据写入 CSV 文件（UTF-8），可选表头。
     *
     * @param file 目标文件
     * @param rows 数据行
     * @param headers 表头（可为空，为空则不写表头行）
     * @throws IOException IO 异常
     */
    public static void write(File file, List<List<String>> rows, String... headers) throws IOException {
        write(file, DEFAULT_CHARSET, rows, headers);
    }

    /**
     * 将行数据写入 CSV 文件，可选表头。
     *
     * @param file 目标文件
     * @param charset 文件编码
     * @param rows 数据行
     * @param headers 表头（可为空，为空则不写表头行）
     * @throws IOException IO 异常
     */
    public static void write(File file, Charset charset, List<List<String>> rows, String... headers)
            throws IOException {
        CSVWriter writer = writer(file, charset, headers);
        try {
            if (rows != null) {
                for (List<String> row : rows) {
                    writer.writeRow(row);
                }
            }
            writer.flush();
        } finally {
            writer.close();
        }
    }

    /**
     * 创建流式写出器（UTF-8），逐行写出、不需要先把全部数据放进内存，适合百万行级别的大文件。
     *
     * @param file 目标文件，父目录不存在时自动创建
     * @param headers 表头（可为空，为空则不写表头行）
     * @return 流式写出器，用完必须 {@link CSVWriter#close()}（推荐 try-with-resources）
     * @throws IOException 目录创建失败或文件不可写时抛出
     */
    public static CSVWriter writer(File file, String... headers) throws IOException {
        return writer(file, DEFAULT_CHARSET, headers);
    }

    /**
     * 创建流式写出器，逐行写出、不需要先把全部数据放进内存，适合百万行级别的大文件。
     * <p>
     * 行尾用平台行分隔符（与 {@link #write(File, Charset, List, String...)} 一致）；需要固定
     * {@code \n} 的场景用 {@link CSV#writer(File, Charset, String...)}。
     *
     * @param file 目标文件，父目录不存在时自动创建
     * @param charset 文件编码
     * @param headers 表头（可为空，为空则不写表头行）
     * @return 流式写出器，用完必须 {@link CSVWriter#close()}（推荐 try-with-resources）
     * @throws IOException 目录创建失败或文件不可写时抛出
     */
    public static CSVWriter writer(File file, Charset charset, String... headers) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), charset), 65536);
        CSVWriter writer = new CSVWriter(out, System.lineSeparator());
        if (headers != null && headers.length > 0) {
            writer.writeRow(headers);
        }
        return writer;
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 读取 CSV 文件（UTF-8），返回所有行的字段列表。
     *
     * @param file 源文件
     * @return 每行一个 {@link List}，字段已去除外层引号；文件不存在返回空列表
     * @throws IOException IO 异常
     */
    public static List<List<String>> read(File file) throws IOException {
        return read(file, DEFAULT_CHARSET);
    }

    /**
     * 读取 CSV 文件，返回所有行的字段列表。
     * <p>
     * 结果全量放在内存里，百万行级别的大文件请改用
     * {@link #readStream(File, Charset, CSVValuesHandler)} 逐行处理。
     *
     * @param file 源文件
     * @param charset 文件编码
     * @return 每行一个 {@link List}，字段已去除外层引号；文件不存在返回空列表
     * @throws IOException IO 异常
     */
    public static List<List<String>> read(File file, Charset charset) throws IOException {
        final List<List<String>> rows = new ArrayList<List<String>>();
        readStream(file, charset, new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> values, long rowIndex) {
                rows.add(values);
                return true;
            }
        });
        return rows;
    }

    /**
     * 读取 CSV 文件，将首行作为表头，返回 {@code 列名 -> 值} 的有序 Map 列表。
     *
     * @param file 源文件（UTF-8）
     * @return 数据行列表，每行为有序 {@link Map}；无表头或文件不存在时返回空列表
     * @throws IOException IO 异常
     */
    public static List<Map<String, String>> readWithHeader(File file) throws IOException {
        return readWithHeader(file, DEFAULT_CHARSET);
    }

    /**
     * 读取 CSV 文件，将首行作为表头，返回 {@code 列名 -> 值} 的有序 Map 列表。
     *
     * @param file 源文件
     * @param charset 文件编码
     * @return 数据行列表，每行为有序 {@link Map}；无表头或文件不存在时返回空列表
     * @throws IOException IO 异常
     */
    public static List<Map<String, String>> readWithHeader(File file, Charset charset) throws IOException {
        final List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        final List<String> header = new ArrayList<String>();
        readStream(file, charset, new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> values, long rowIndex) {
                if (rowIndex == 0) {
                    header.addAll(values);
                    return true;
                }
                Map<String, String> map = new LinkedHashMap<String, String>();
                for (int i = 0; i < header.size(); i++) {
                    map.put(header.get(i), i < values.size() ? values.get(i) : "");
                }
                result.add(map);
                return true;
            }
        });
        return result;
    }

    /**
     * 流式读取 CSV 文件（UTF-8）：每解析出一行就回调一次，解析器只持有一行数据，
     * 内存占用与文件大小无关，适合百万行级别的大文件。
     *
     * @param file 源文件，为 {@code null} 或不存在时不回调
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的行数（含表头行）
     * @throws IOException IO 异常
     */
    public static long readStream(File file, CSVValuesHandler handler) throws IOException {
        return readStream(file, DEFAULT_CHARSET, handler);
    }

    /**
     * 流式读取 CSV 文件：每解析出一行就回调一次，解析器只持有一行数据，
     * 内存占用与文件大小无关，适合百万行级别的大文件。
     * <p>
     * 解析规则与 {@link #read(File, Charset)} 完全一致（同一套解析器，{@code read} 就是把回调
     * 结果收集成 {@code List}）：支持引号内的逗号、换行与 {@code ""} 转义，忽略空行。
     *
     * @param file 源文件，为 {@code null} 或不存在时不回调
     * @param charset 文件编码
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的行数（含表头行）
     * @throws IOException IO 异常
     */
    public static long readStream(File file, Charset charset, CSVValuesHandler handler) throws IOException {
        if (file == null || !file.exists()) {
            return 0;
        }
        Reader reader = new InputStreamReader(new FileInputStream(file), charset);
        try {
            return readStream(reader, handler);
        } finally {
            reader.close();
        }
    }

    /**
     * 流式读取字符流：每解析出一行就回调一次，读取结束后**不会**关闭该流。
     *
     * @param reader 源字符流
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的行数（含表头行）
     * @throws IOException IO 异常
     */
    public static long readStream(Reader reader, CSVValuesHandler handler) throws IOException {
        if (reader == null) {
            return 0;
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        // 宽松语义：保留字段两侧空格、引号格式非法也照样收下
        return new CSVParser(false, false, handler).parse(reader);
    }
}
