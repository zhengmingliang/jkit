package com.alianga.jkit.csv;

import com.alianga.jkit.reflect.UnsafeHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CSV处理：读进来是 {@link CSVTable}（首行总是表头），写出支持对象列表。
 * <p>
 * 全量读入的 {@code read(...)} 适合中小文件；百万行、上百 MB 的文件用
 * {@link #readStream(File, CSVRowHandler)} 与 {@link #writer(File, String...)} 逐行处理，
 * 内存占用与文件大小无关。解析与转义都复用包内共享的实现，和 {@link CSVUtils} 是同一套代码。
 */
public final class CSV {
    /** csv 包写出固定用 \n 作为行分隔符 */
    static final String LINE_SEPARATOR = "\n";

    private CSV() {
    }

    /**
     * 读取文件返回CSV表格对象
     *
     * @param file 待读取的CSV文件
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(File file) {
        return read(file, Charset.defaultCharset());
    }

    /**
     * 指定编码
     *
     * @param file 待读取的CSV文件
     * @param charsetName 读取文件使用的字符集名称
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(File file, String charsetName) {
        return read(file, Charset.forName(charsetName));
    }

    /**
     * 指定编码
     *
     * @param file 待读取的CSV文件
     * @param charset 读取文件使用的字符集
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(File file, Charset charset) {
        return read(openStream(file), charset);
    }

    /**
     * 读取流返回CSV表格对象
     *
     * @param is 待读取的输入流，使用平台默认字符集解码
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(InputStream is) {
        return read(is, Charset.defaultCharset());
    }

    /**
     * 读取流返回CSV表格对象
     *
     * @param is 待读取的输入流
     * @param charsetName 解码输入流使用的字符集名称
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(InputStream is, String charsetName) {
        return read(is, Charset.forName(charsetName));
    }

    /**
     * 读取流返回CSV表格对象
     *
     * @param is 待读取的输入流，读取完毕后会被关闭
     * @param charset 解码输入流使用的字符集
     * @return 解析后的CSV表格对象，空行会被跳过
     */
    public static CSVTable read(InputStream is, Charset charset) {
        CSVTable csvTable = new CSVTable();
        Reader reader = new InputStreamReader(is, charset);
        try {
            csvTable.setRows(collectRows(csvTable, reader));
        } finally {
            close(reader);
        }
        return csvTable;
    }

    /**
     * 读取字节数组返回CSV表格对象，使用平台默认字符集解码
     *
     * @param data 完整的CSV内容字节数组
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(byte[] data) {
        return read(new String(data));
    }

    /**
     * 读取字节数组返回CSV表格对象，按指定字符集解码
     *
     * @param data 完整的CSV内容字节数组
     * @param charsetName 解码字节数组使用的字符集名称
     * @return 解析后的CSV表格对象
     */
    public static CSVTable read(byte[] data, String charsetName) {
        return read(new String(data, Charset.forName(charsetName)));
    }

    /**
     * 读取完整的CSV内容
     *
     * @param content 完整的CSV文本内容，支持双引号包裹与转义、引号内换行、空字段，不要求以换行结尾
     * @return 解析后的CSV表格对象，空行（含只有空格的行）会被跳过；内容为空时返回空表格
     */
    public static CSVTable read(String content) {
        CSVTable csvTable = new CSVTable();
        final List<CSVRow> csvRows = new ArrayList<CSVRow>();
        char[] chars = UnsafeHelper.getChars(content);
        newParser(rowCollector(csvTable, csvRows)).parse(chars, chars.length);
        csvTable.setRows(csvRows);
        return csvTable;
    }

    /**
     * 流式读取CSV文件（平台默认字符集）：首行当表头，之后每解析出一行数据回调一次，
     * 解析器只持有当前一行，内存占用与文件大小无关。
     *
     * @param file 待读取的CSV文件
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的数据行数，不含表头行
     */
    public static long readStream(File file, CSVRowHandler handler) {
        return readStream(file, Charset.defaultCharset(), handler);
    }

    /**
     * 流式读取CSV文件：首行当表头，之后每解析出一行数据回调一次。
     *
     * @param file 待读取的CSV文件
     * @param charsetName 读取文件使用的字符集名称
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的数据行数，不含表头行
     */
    public static long readStream(File file, String charsetName, CSVRowHandler handler) {
        return readStream(file, Charset.forName(charsetName), handler);
    }

    /**
     * 流式读取CSV文件：首行当表头，之后每解析出一行数据回调一次。
     *
     * @param file 待读取的CSV文件
     * @param charset 读取文件使用的字符集
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的数据行数，不含表头行
     */
    public static long readStream(File file, Charset charset, CSVRowHandler handler) {
        return readStream(openStream(file), charset, handler);
    }

    /**
     * 流式读取输入流：首行当表头，之后每解析出一行数据回调一次，读取完毕后关闭该流。
     *
     * @param is 待读取的输入流
     * @param charset 解码输入流使用的字符集
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的数据行数，不含表头行
     */
    public static long readStream(InputStream is, Charset charset, CSVRowHandler handler) {
        Reader reader = new InputStreamReader(is, charset);
        try {
            return readStream(reader, handler);
        } finally {
            close(reader);
        }
    }

    /**
     * 流式读取完整的CSV内容：首行当表头，之后每解析出一行数据回调一次。
     *
     * @param content 完整的CSV文本内容
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的数据行数，不含表头行
     */
    public static long readStream(String content, CSVRowHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        char[] chars = UnsafeHelper.getChars(content);
        CSVTable header = new CSVTable();
        long rows = newParser(streamAdapter(header, handler)).parse(chars, chars.length);
        return rows > 0 ? rows - 1 : 0;
    }

    /**
     * 流式读取字符流：首行当表头，之后每解析出一行数据回调一次，**不会**关闭该流。
     *
     * @param reader 待读取的字符流
     * @param handler 行回调，返回 {@code false} 可提前终止读取
     * @return 实际回调的数据行数，不含表头行
     */
    public static long readStream(Reader reader, CSVRowHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        CSVTable header = new CSVTable();
        try {
            long rows = newParser(streamAdapter(header, handler)).parse(reader);
            return rows > 0 ? rows - 1 : 0;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建流式写出器（平台默认字符集），可逐行 {@link CSVWriter#writeRow(List)} 或逐个
     * {@link CSVObjectWriter#writeObject(Object)}，不需要先把数据全部放进内存。
     *
     * @param file 目标文件，不存在时自动创建
     * @param columnNames 表头列名，不传则由首个写出的对象决定
     * @return 流式写出器，用完必须 {@link CSVWriter#close()}
     */
    public static CSVObjectWriter writer(File file, String... columnNames) {
        return writer(file, Charset.defaultCharset(), columnNames);
    }

    /**
     * 创建流式写出器，可逐行或逐对象写出，不需要先把数据全部放进内存。
     *
     * @param file 目标文件，不存在时自动创建
     * @param charset 写出使用的字符集
     * @param columnNames 表头列名，不传则由首个写出的对象决定
     * @return 流式写出器，用完必须 {@link CSVWriter#close()}
     */
    public static CSVObjectWriter writer(File file, Charset charset, String... columnNames) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录: " + parent);
            }
            return writer(new FileOutputStream(file), charset, columnNames);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建流式写出器，可逐行或逐对象写出，不需要先把数据全部放进内存。
     *
     * @param os 目标输出流，{@link CSVWriter#close()} 时会被关闭
     * @param charset 写出使用的字符集
     * @param columnNames 表头列名，不传则由首个写出的对象决定
     * @return 流式写出器，用完必须 {@link CSVWriter#close()}
     */
    public static CSVObjectWriter writer(OutputStream os, Charset charset, String... columnNames) {
        Writer writer = new BufferedWriter(new OutputStreamWriter(os, charset), 65536);
        boolean hasHeader = columnNames != null && columnNames.length > 0;
        List<String> header = hasHeader ? Arrays.asList(columnNames) : null;
        CSVObjectWriter csvWriter = new CSVObjectWriter(writer, LINE_SEPARATOR, header);
        if (hasHeader) {
            try {
                csvWriter.writeRow(columnNames);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return csvWriter;
    }

    /**
     * 将列表对象转为CSV文件
     *
     * @param objList 待写出的对象列表，首个元素的属性作为表头
     * @param file 输出的CSV文件，使用平台默认字符集编码
     */
    public static void writeObjectTo(List<?> objList, File file) {
        try {
            writeObjectTo(objList, new FileOutputStream(file), Charset.defaultCharset());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将列表对象转为CSV文件
     *
     * @param objList 待写出的对象列表，首个元素的属性作为表头
     * @param os 输出流，写出完成后会被关闭
     * @param charsetName 写出使用的字符集名称
     */
    public static void writeObjectTo(List<?> objList, OutputStream os, String charsetName) {
        writeObjectTo(objList, os, Charset.forName(charsetName));
    }

    /**
     * 将集合对象转为CSV字符串
     *
     * @param objList 待转换的对象列表，首个元素的属性作为表头
     * @return CSV格式的字符串；列表为 {@code null} 或空时返回 {@code null}
     */
    public static String toCSVString(List<?> objList) {
        if (objList == null || objList.size() == 0) {
            return null;
        }
        StringWriter stringWriter = new StringWriter();
        CSVObjectWriter writer = new CSVObjectWriter(stringWriter, LINE_SEPARATOR, null);
        try {
            for (Object obj : objList) {
                writer.writeObject(obj);
            }
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return stringWriter.toString();
    }

    /**
     * 将对象转为CSV文件
     *
     * @param objList 待写出的对象列表
     * @param os 输出流，写出完成后会被关闭
     * @param charset 写出使用的字符集
     */
    static void writeObjectTo(List<?> objList, OutputStream os, Charset charset) {
        if (objList == null || objList.size() == 0) {
            return;
        }
        CSVObjectWriter writer = writer(os, charset);
        try {
            for (Object obj : objList) {
                writer.writeObject(obj);
            }
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                writer.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 创建 csv 包语义的解析器：字段两侧空格去掉、只含空格的行按空行跳过、引号格式非法直接抛异常
     *
     * @param handler 行回调
     * @return 解析器
     */
    private static CSVParser newParser(CSVValuesHandler handler) {
        return new CSVParser(true, true, handler);
    }

    /**
     * 把解析出的字段列表收集成CSVRow
     *
     * @param csvTable 归属表格
     * @param csvRows 输出：收集到的行
     * @return 行回调
     */
    private static CSVValuesHandler rowCollector(final CSVTable csvTable, final List<CSVRow> csvRows) {
        return new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> values, long rowIndex) {
                csvRows.add(new CSVRow(csvTable, values));
                return true;
            }
        };
    }

    /**
     * 把首行当表头装进header，其余行包成绑定表头的CSVRow回调出去
     *
     * @param header 承载表头的表格
     * @param handler 用户的行回调
     * @return 行回调
     */
    private static CSVValuesHandler streamAdapter(final CSVTable header, final CSVRowHandler handler) {
        return new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> values, long rowIndex) {
                if (rowIndex == 0) {
                    header.setColumnNames(values);
                    return true;
                }
                return handler.handle(new CSVRow(header, values), rowIndex - 1);
            }
        };
    }

    /**
     * 读出全部行
     *
     * @param csvTable 归属表格
     * @param reader 源字符流
     * @return 全部行，首行是表头
     */
    private static List<CSVRow> collectRows(CSVTable csvTable, Reader reader) {
        List<CSVRow> csvRows = new ArrayList<CSVRow>();
        try {
            newParser(rowCollector(csvTable, csvRows)).parse(reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return csvRows;
    }

    private static InputStream openStream(File file) {
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static void close(Reader reader) {
        try {
            reader.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
