package com.alianga.jkit.json.internal.beans.geo;

/**
 * 几何集合类
 */
abstract class AbstractMultiGeometry extends Geometry {
    public AbstractMultiGeometry(GeometryType geometryType) {
        super(geometryType);
    }

    @Override
    final void readBody(char[] chars, GeometryContext geometryContext) {
        int offset = geometryContext.offset;
        char ch;
        // 读取points集合
        while (true) {
            // trim()
            while ((ch = chars[++offset]) == ' ') {
                // 跳过
            }
            checkElementPrefix(ch, offset);
            geometryContext.offset = offset;
            readElement(chars, geometryContext);
            offset = geometryContext.offset;
            while ((ch = chars[++offset]) == ' ') {
                // 跳过
            }
            if (ch == ')') {
                // 结束标记
                geometryContext.offset = offset;
                return;
            } else {
                if (ch != ',') {
                    throw new IllegalArgumentException(
                            "Geometry syntax error, offset " + offset + " , expected ',', actual '" + ch + "'");
                }
            }
        }
    }

    /**
     * 通常以(开始，只有GeometryCollection例外
     *
     * @param ch     当前读取到的字符
     * @param offset 当前字符在源字符数组中的位置，用于异常信息定位
     */
    protected void checkElementPrefix(char ch, int offset) {
        if (ch != '(') {
            throw new IllegalArgumentException(
                    "Geometry syntax error, offset " + offset + " , expected '(', actual '" + ch + "'");
        }
    }

    /**
     * 读取集合中的单个子几何元素，由子类按自身元素类型实现。
     *
     * @param chars           几何字符串的字符数组
     * @param geometryContext 解析上下文，读取前后通过其 offset 传递并推进读取位置
     */
    protected abstract void readElement(char[] chars, GeometryContext geometryContext);

}
