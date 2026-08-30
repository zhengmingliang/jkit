package com.alianga.jkit.json.internal.beans.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * example data: MULTILINESTRING((103 35, 104 35), (105 36, 105 37))
 */
public class MultiLineString extends AbstractMultiGeometry {
    /**
     * 创建一个空的 MULTILINESTRING 几何对象。
     */
    public MultiLineString() {
        super(GeometryType.MULTILINESTRING);
    }

    private List<LineString> elements = new ArrayList<LineString>();

    /**
     * 追加一条线串。
     *
     * @param lineString 待追加的线串
     */
    public void add(LineString lineString) {
        elements.add(lineString);
    }

    /**
     * 批量追加线串。
     *
     * @param lineStringList 待追加的线串数组
     */
    public void addAll(LineString... lineStringList) {
        for (LineString lineString : lineStringList) {
            elements.add(lineString);
        }
    }

    /**
     * 批量追加线串。
     *
     * @param lineStringList 待追加的线串集合
     */
    public void addAll(List<LineString> lineStringList) {
        elements.addAll(lineStringList);
    }

    /**
     * 按下标移除线串。
     *
     * @param index 待移除线串的下标
     * @return 被移除的线串
     */
    public LineString removeAt(int index) {
        return elements.remove(index);
    }

    /**
     * 移除指定的线串。
     *
     * @param lineString 待移除的线串
     * @return 移除成功时返回 {@code true}，集合中不存在该线串时返回 {@code false}
     */
    public boolean remove(LineString lineString) {
        return elements.remove(lineString);
    }

    /**
     * 清空所有线串。
     */
    public void clear() {
        elements.clear();
    }

    @Override
    void appendBody(StringBuilder builder) {
        builder.append("(");
        int deleteDotIndex = -1;
        for (LineString lineString : elements) {
            lineString.appendBody(builder);
            builder.append(",");
            deleteDotIndex = builder.length() - 1;
        }
        if (deleteDotIndex > -1) {
            builder.deleteCharAt(deleteDotIndex);
        }
        builder.append(")");
    }

    protected void readElement(char[] chars, GeometryContext geometryContext) {
        elements.add(new LineString(readPoints(chars, geometryContext)));
    }

    /**
     * 获取全部线串。
     *
     * @return 当前持有的线串集合（非副本），默认为空集合
     */
    public List<LineString> getElements() {
        return elements;
    }

    /**
     * 设置全部线串，替换原有集合。
     *
     * @param elements 新的线串集合
     */
    public void setElements(List<LineString> elements) {
        this.elements = elements;
    }
}
