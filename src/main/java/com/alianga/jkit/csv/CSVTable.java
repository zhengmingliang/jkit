package com.alianga.jkit.csv;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * CSV 表格模型，由一行表头（列名）与若干数据行组成，支持读写、转实体与序列化输出。
 */
public final class CSVTable {
    private final CSVRow columns;
    private final List<CSVRow> rows;

    CSVTable(List<CSVRow> csvRows) {
        columns = csvRows.get(0);
        rows = csvRows.subList(1, csvRows.size());
    }

    CSVTable() {
        columns = new CSVRow(this, new ArrayList<String>());
        rows = new ArrayList<CSVRow>();
    }

    CSVTable(String[] columnNames) {
        columns = new CSVRow(this, Arrays.asList(columnNames));
        rows = new ArrayList<CSVRow>();
    }

    /**
     * 创建一个既无列名也无数据行的空表格。
     *
     * @return 新创建的空 CSVTable
     */
    public static CSVTable create() {
        return new CSVTable();
    }

    /**
     * 创建一个只含表头的空表格。
     *
     * @param columnNames 列名数组
     * @return 新创建的 CSVTable，其表头为给定列名且不含数据行
     */
    public static CSVTable create(String[] columnNames) {
        return new CSVTable(columnNames);
    }

    void setRows(List<CSVRow> csvRows) {
        if (csvRows.size() > 0) {
            rows.clear();
            CSVRow header = csvRows.get(0);
            List<CSVRow> dataRows = csvRows.subList(1, csvRows.size());
            columns.values = header.values;
            rows.addAll(dataRows);
        }
    }

    /**
     * 整体替换表头列名。
     *
     * @param columnNames 新的列名数组
     */
    public void setColumnNames(String[] columnNames) {
        columns.values = Arrays.asList(columnNames);
    }

    /**
     * 整体替换表头列名。
     *
     * @param columnNames 新的列名列表
     */
    public void setColumnNames(List<String> columnNames) {
        columns.values = columnNames;
    }

    /**
     * 在表格末尾追加一行数据。
     *
     * @param values 该行各列的值，顺序与表头一致
     */
    public void addRow(List<String> values) {
        rows.add(new CSVRow(this, values));
    }

    /**
     * 替换指定下标处的数据行。
     *
     * @param index  数据行下标，不含表头，从 0 开始
     * @param values 新的行数据，顺序与表头一致
     */
    public void set(int index, List<String> values) {
        rows.set(index, new CSVRow(this, values));
    }

    /**
     * 清空所有数据行，表头保持不变。
     */
    public void clearRows() {
        rows.clear();
    }

    /**
     * 移除指定下标处的数据行。
     *
     * @param index 数据行下标，不含表头，从 0 开始
     */
    public void removeAt(int index) {
        rows.remove(index);
    }

    /**
     * 获取表头行。
     *
     * @return 当前的表头行对象，其值即各列列名
     */
    public CSVRow getColumns() {
        return columns;
    }

    /**
     * 获取全部数据行。
     *
     * @return 当前的数据行列表，不含表头
     */
    public List<CSVRow> getRows() {
        return rows;
    }

    /**
     * 获取指定下标处的数据行。
     *
     * @param index 数据行下标，不含表头，从 0 开始
     * @return 该下标对应的数据行
     */
    public CSVRow getRow(int index) {
        return rows.get(index);
    }

    /**
     * 获取数据行数量。
     *
     * @return 数据行数量，不含表头
     */
    public int size() {
        return rows.size();
    }

    /**
     * 查询列名在表头中的下标。
     *
     * @param column 列名
     * @return 该列名对应的下标，列名不存在时返回 -1
     */
    public int getColumnIndex(String column) {
        return columns.indexOf(column);
    }

    /**
     * 转化为列表数据
     *
     * @param eClass 目标实体类型
     * @param <E> 实体类型
     * @return 转换后的实体列表，无数据行时为空列表
     */
    public <E> List<E> asEntityList(Class<E> eClass) {
        return asEntityList(eClass, null);
    }

    /**
     * 转化为列表数据
     *
     * @param eClass 目标实体类型
     * @param columnMapping 列名到属性名的映射
     * @param <E> 实体类型
     * @return 转换后的实体列表
     */
    public <E> List<E> asEntityList(Class<E> eClass, Map<String, String> columnMapping) {
        List<E> list = new ArrayList<E>();
        for (CSVRow csvRow : rows) {
            list.add(csvRow.toBean(eClass, columnMapping));
        }
        return list;
    }

    /**
     * 覆盖指定单元格的值。
     *
     * @param rowIndex    数据行下标，不含表头，从 0 开始
     * @param columnIndex 列下标，从 0 开始
     * @param value       新的单元格值
     */
    public void setValue(int rowIndex, int columnIndex, String value) {
        rows.get(rowIndex).set(columnIndex, value);
    }

    /**
     * 将表格内容（含表头）以系统默认字符集写入文件，文件不存在时自动创建。
     *
     * @param file 目标文件
     */
    public void writeTo(File file) {
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            writeTo(new FileOutputStream(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将表格内容（含表头）以系统默认字符集写入输出流，写完后关闭该流。
     *
     * @param os 目标输出流
     */
    public void writeTo(OutputStream os) {
        writeTo(os, Charset.defaultCharset());
    }

    /**
     * 将表格内容（含表头）以指定字符集写入输出流，写完后关闭该流。
     *
     * @param os          目标输出流
     * @param charsetName 字符集名称，如 {@code UTF-8}
     */
    public void writeTo(OutputStream os, String charsetName) {
        writeTo(os, Charset.forName(charsetName));
    }

    void writeTo(OutputStream os, Charset charset) {
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(os, charset));
        try {
            writeTo(bufferedWriter);
            bufferedWriter.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                bufferedWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    void writeTo(Appendable appendable) {
        try {
            CSVWriter.writeRow(appendable, columns.values, CSV.LINE_SEPARATOR);
            for (CSVRow row : rows) {
                CSVWriter.writeRow(appendable, row.values, CSV.LINE_SEPARATOR);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将表格序列化为 CSV 文本，首行为表头。
     *
     * @return 包含表头与全部数据行的 CSV 字符串
     */
    public String toCSVString() {
        StringBuilder builder = new StringBuilder();
        writeTo(builder);
        return builder.toString();
    }
}
