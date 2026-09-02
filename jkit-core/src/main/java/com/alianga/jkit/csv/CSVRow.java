package com.alianga.jkit.csv;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.ReflectConsts;
import com.alianga.jkit.reflect.SetterInfo;

import java.util.*;

/**
 * CSV 表格中的一行数据。
 * <p>
 * 行内的单元格值按列顺序保存，可以按列下标或列名读取，也可以整行转换为 JavaBean 或 Map。
 */
public class CSVRow {
    final transient CSVTable csvTable;

    List<String> values;

    CSVRow(CSVTable csvTable, List<String> values) {
        this.values = values;
        this.csvTable = csvTable;
    }

    /**
     * 按列下标读取本行的单元格值。
     *
     * @param index 列下标，从 0 开始
     * @return 该列的字符串值
     * @throws IndexOutOfBoundsException 下标越界时抛出
     */
    public String get(int index) {
        return values.get(index);
    }

    /**
     * 按列名读取本行的单元格值，列名两端的空白会被忽略。
     *
     * @param name 列名
     * @return 该列的字符串值，列名不存在时返回 null
     */
    public String get(String name) {
        int index = csvTable.getColumnIndex(name.trim());
        if (index == -1) {
            return null;
        }
        return values.get(index);
    }

    /**
     * 转换为实体bean
     *
     * @param eClass 目标实体类型，必须是普通对象类型
     * @param <E>    目标实体类型
     * @return 按列名与 setter 名称匹配填充后的实体对象
     */
    public <E> E toBean(Class<E> eClass) {
        return toBean(eClass, null);
    }

    /**
     * 转换为实体bean
     *
     * @param eClass        目标实体类型，必须是普通对象类型
     * @param <E>           目标实体类型
     * @param columnMapping 列名到属性名的映射，可为 null 表示直接以列名作为属性名
     * @return 填充完成的实体对象
     */
    public <E> E toBean(Class<E> eClass, Map<String, String> columnMapping) {
        ReflectConsts.ClassCategory classCategory = ReflectConsts.getClassCategory(eClass);
        if (classCategory != ReflectConsts.ClassCategory.ObjectCategory) {
            throw new UnsupportedOperationException("class " + eClass + " is not supported ");
        }
        ClassStrucWrap classStrucWrap = ClassStrucWrap.get(eClass);
        List<String> columns = csvTable.getColumns().values;
        Map<String, CSVColumnMapper> annotationedColumnMap = validatedColumnAnnotationed(classStrucWrap, columns);
        try {
            E e = (E) classStrucWrap.newInstance();
            int columnIndex = 0;
            int size = values.size();
            for (String column : columns) {
                if (columnIndex < size) {
                    SetterInfo setterInfo;
                    boolean checkRequired = false;
                    Class<? extends CSVTypeHandler> typeHandlerCls = null;
                    if (annotationedColumnMap.containsKey(column)) {
                        CSVColumnMapper csvColumnMapper = annotationedColumnMap.get(column);
                        setterInfo = csvColumnMapper.setterInfo;
                        checkRequired = csvColumnMapper.csvColumn.required();
                        typeHandlerCls = csvColumnMapper.csvColumn.handler();
                    } else {
                        String name = columnMapping == null ? null : columnMapping.get(column);
                        setterInfo = classStrucWrap.getSetterInfo(name == null ? column : name);
                    }
                    String stringVal = values.get(columnIndex);
                    if (setterInfo != null) {
                        Object value;
                        Class<?> type = setterInfo.getParameterType();
                        if (typeHandlerCls == null || typeHandlerCls == CSVTypeHandler.DefaultCSVTypeHandler.class) {
                            value = ObjectUtils.toType(stringVal, type);
                        } else {
                            try {
                                CSVTypeHandler typeHandler = typeHandlerCls.newInstance();
                                value = typeHandler.handle(stringVal, type);
                                if (value != null) {
                                    if (!type.isPrimitive() && !type.isInstance(value)) {
                                        throw new CSVException(
                                                "value '" + value + "'  from handler is not matched type " + type);
                                    }
                                }
                            } catch (Throwable throwable) {
                                throw new CSVException(throwable.getMessage(), throwable);
                            }
                        }
                        if (checkRequired && value == null) {
                            throw new CSVException("value for column '" + column + "' is required but null");
                        }
                        setterInfo.invoke(e, value);
                    }
                }
                ++columnIndex;
            }
            return e;
        } catch (Exception ex) {
            throw ex instanceof RuntimeException ? (RuntimeException) ex : new RuntimeException(ex);
        }
    }

    private Map<String, CSVColumnMapper> validatedColumnAnnotationed(ClassStrucWrap classStrucWrap,
                                                                     List<String> columns) {
        Map<String, CSVColumnMapper> annotationedMap = new HashMap<String, CSVColumnMapper>();
        Set<SetterInfo> setterInfoSet = classStrucWrap.setterSet();
        for (SetterInfo setterInfo : setterInfoSet) {
            CSVColumn csvColumn = (CSVColumn) setterInfo.getAnnotation(CSVColumn.class);
            if (csvColumn == null) {
                continue;
            }
            String value = csvColumn.value().trim();
            if (value.length() == 0) {
                value = setterInfo.getName();
            }
            if (csvColumn.required() && columns.indexOf(value) == -1) {
                throw new CSVException("column '" + value + "' is required");
            }
            CSVColumnMapper csvColumnMapper = new CSVColumnMapper();
            csvColumnMapper.csvColumn = csvColumn;
            csvColumnMapper.setterInfo = setterInfo;
            annotationedMap.put(value, csvColumnMapper);
        }
        return annotationedMap;
    }

    /**
     * 转换为实体bean
     *
     * @return 以列名为键、单元格字符串为值的 LinkedHashMap，保持列顺序
     */
    public Map toMap() {
        Map map = new LinkedHashMap();
        List<String> columns = csvTable.getColumns().values;
        try {
            int columnIndex = 0;
            for (String column : columns) {
                map.put(column, values.get(columnIndex));
                ++columnIndex;
            }
            return map;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 获取本行的全部单元格值。
     *
     * @return 按列顺序排列的单元格值列表，返回的是内部列表本身，修改会直接反映到本行
     */
    public List<String> getValues() {
        return values;
    }

    /**
     * 查找指定单元格值在本行中首次出现的列下标。
     *
     * @param value 要查找的单元格值
     * @return 首次出现的列下标，不存在时返回 -1
     */
    public int indexOf(String value) {
        return values.indexOf(value);
    }

    /**
     * 覆盖本行指定列的单元格值。
     *
     * @param columnIndex 列下标，从 0 开始
     * @param value       新的单元格值
     * @throws IndexOutOfBoundsException 下标越界时抛出
     */
    public void set(int columnIndex, String value) {
        values.set(columnIndex, value);
    }

    static class CSVColumnMapper {
        CSVColumn csvColumn;
        SetterInfo setterInfo;
    }
}
