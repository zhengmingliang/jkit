package com.alianga.jkit.sql.auto;

/**
 * 库里已有的一列（来自 {@code DatabaseMetaData.getColumns}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoLiveColumn {
    private final String name;
    private final String typeName;
    private final int dataType;
    private final int size;
    private final int decimalDigits;
    private final int nullable;

    /**
     * @param name 列名
     * @param typeName 方言类型名
     * @param dataType {@link java.sql.Types}
     * @param size 长度
     * @param decimalDigits 小数位
     * @param nullable {@link java.sql.DatabaseMetaData#columnNullable} 等
     */
    public SqlAutoLiveColumn(String name, String typeName, int dataType, int size,
                             int decimalDigits, int nullable) {
        this.name = name == null ? "" : name;
        this.typeName = typeName == null ? "" : typeName;
        this.dataType = dataType;
        this.size = size;
        this.decimalDigits = decimalDigits;
        this.nullable = nullable;
    }

    /**
     * @return 列名
     */
    public String name() {
        return name;
    }

    /**
     * @return 类型名
     */
    public String typeName() {
        return typeName;
    }

    /**
     * @return JDBC 类型码
     */
    public int dataType() {
        return dataType;
    }

    /**
     * @return 长度
     */
    public int size() {
        return size;
    }

    /**
     * @return 小数位
     */
    public int decimalDigits() {
        return decimalDigits;
    }

    /**
     * @return 可空码
     */
    public int nullable() {
        return nullable;
    }

    /**
     * @return 是否可空
     */
    public boolean isNullable() {
        return nullable != 0;
    }
}
