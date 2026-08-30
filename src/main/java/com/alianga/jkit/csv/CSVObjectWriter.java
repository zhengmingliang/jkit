package com.alianga.jkit.csv;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.GetterInfo;
import com.alianga.jkit.reflect.ReflectConsts;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 支持直接写出对象的流式 CSV 写出器，是 {@link CSV#writeObjectTo(List, java.io.File)} 的流式版本：
 * 数据可以一批一批（例如分页查库）地推进来，不必先攒成一个完整的 {@code List}。
 * <p>
 * 首次 {@link #writeObject(Object)} 时按该对象解析列（JavaBean 走 getter 与
 * {@link CSVColumn}，{@code Map} 走 key）：创建时没给列名就自动补写表头；给了列名则**按列名对齐**，
 * 顺序以给定的表头为准，对象里没有的列写成空串。
 * <p>
 * 值用 {@code String.valueOf(...)} 转换，所以 {@code null} 属性会写成字符串 {@code null}，
 * 与 {@link CSV#writeObjectTo(List, java.io.File)} 保持一致。
 */
public final class CSVObjectWriter extends CSVWriter {
    private final List<String> header;
    /** 取值用的属性名，与写出顺序一一对应；元素为 null 表示该列在对象里不存在 */
    private List<String> objectKeys;

    CSVObjectWriter(Writer writer, String lineSeparator, List<String> header) {
        super(writer, lineSeparator);
        this.header = header;
    }

    /**
     * 写出一个对象，支持 JavaBean 与 {@code Map}。
     *
     * @param obj 待写出的对象，{@code null} 时直接忽略
     * @throws IOException 写出失败时抛出
     */
    public void writeObject(Object obj) throws IOException {
        if (obj == null) {
            return;
        }
        if (objectKeys == null) {
            List<String> columnNames = new ArrayList<String>();
            List<String> keys = new ArrayList<String>();
            getColumnNames(obj, columnNames, keys);
            if (header == null) {
                objectKeys = keys;
                writeRow(columnNames);
            } else {
                // 已经写过表头，按列名把属性重排到表头的顺序上
                objectKeys = new ArrayList<String>(header.size());
                for (String column : header) {
                    int index = columnNames.indexOf(column);
                    objectKeys.add(index == -1 ? null : keys.get(index));
                }
            }
        }
        List<String> values = new ArrayList<String>(objectKeys.size());
        for (String key : objectKeys) {
            values.add(key == null ? "" : String.valueOf(ObjectUtils.get(obj, key)));
        }
        writeRow(values);
    }

    /**
     * 解析对象的列名与取值用的属性名
     *
     * @param obj 样本对象
     * @param csvColumnNames 输出：表头列名
     * @param objectKeys 输出：取值用的属性名（与列名一一对应）
     */
    static void getColumnNames(Object obj, List<String> csvColumnNames, List<String> objectKeys) {
        Class<?> aClass = obj.getClass();
        ReflectConsts.ClassCategory classCategory = ReflectConsts.getClassCategory(aClass);
        if (classCategory == ReflectConsts.ClassCategory.MapCategory) {
            Set<?> keySet = ((Map<?, ?>) obj).keySet();
            for (Object key : keySet) {
                csvColumnNames.add(String.valueOf(key));
            }
            objectKeys.addAll(csvColumnNames);
        } else if (classCategory == ReflectConsts.ClassCategory.ObjectCategory) {
            ClassStrucWrap classStrucWrap = ClassStrucWrap.get(aClass);
            List<GetterInfo> getterInfos = classStrucWrap.getGetterInfos(classStrucWrap.isForceUseFields());
            for (GetterInfo getterInfo : getterInfos) {
                CSVColumn csvColumn = (CSVColumn) getterInfo.getAnnotation(CSVColumn.class);
                String name;
                if (csvColumn != null && (name = csvColumn.value().trim()).length() > 0) {
                    csvColumnNames.add(name);
                } else {
                    csvColumnNames.add(getterInfo.getName());
                }
                objectKeys.add(getterInfo.getName());
            }
        }
    }
}
