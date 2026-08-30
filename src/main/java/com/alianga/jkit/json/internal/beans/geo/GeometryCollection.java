package com.alianga.jkit.json.internal.beans.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * example data: GEOMETRYCOLLECTION(POINT(103 35), LINESTRING(103 35, 103 37))
 */
public class GeometryCollection extends AbstractMultiGeometry {
    /**
     * 构造一个空的几何集合对象。
     */
    public GeometryCollection() {
        super(GeometryType.GEOMETRYCOLLECTION);
    }

    private List<Geometry> elements = new ArrayList<Geometry>();

    /**
     * 向集合末尾追加一个几何元素。
     *
     * @param child 要追加的几何元素
     */
    public void add(Geometry child) {
        elements.add(child);
    }

    /**
     * 向集合末尾批量追加几何元素。
     *
     * @param childList 要追加的几何元素数组
     */
    public void addAll(Geometry... childList) {
        for (Geometry child : childList) {
            elements.add(child);
        }
    }

    /**
     * 向集合末尾批量追加几何元素。
     *
     * @param childList 要追加的几何元素列表
     */
    public void addAll(List<Geometry> childList) {
        elements.addAll(childList);
    }

    /**
     * 移除指定下标的几何元素。
     *
     * @param index 元素下标，从 0 开始
     * @return 被移除的几何元素
     * @throws IndexOutOfBoundsException 下标越界时抛出
     */
    public Geometry removeAt(int index) {
        return elements.remove(index);
    }

    /**
     * 移除集合中首个与给定对象相等的几何元素。
     *
     * @param geometry 要移除的几何元素
     * @return 移除成功时返回 {@code true}，元素不存在时返回 {@code false}
     */
    public boolean remove(Geometry geometry) {
        return elements.remove(geometry);
    }

    /**
     * 清空集合中的所有几何元素。
     */
    public void clear() {
        elements.clear();
    }

    @Override
    void appendBody(StringBuilder builder) {
        builder.append("(");
        int deleteDotIndex = -1;
        for (Geometry geometry : elements) {
            geometry.appendTo(builder);
            builder.append(",");
            deleteDotIndex = builder.length() - 1;
        }
        if (deleteDotIndex > -1) {
            builder.deleteCharAt(deleteDotIndex);
        }
        builder.append(")");
    }

    @Override
    protected void checkElementPrefix(char ch, int offset) {
    }

    @Override
    protected void readElement(char[] chars, GeometryContext geometryContext) {
        GeometryType geometryType = readGeometryType(chars, geometryContext);
        Geometry geometry = geometryType.newInstance();
        geometry.readBody(chars, geometryContext);
        add(geometry);
    }

    /**
     * 获取集合内的几何元素列表。
     *
     * @return 当前的几何元素列表，返回的是内部列表本身
     */
    public List<Geometry> getElements() {
        return elements;
    }

    /**
     * 直接替换集合内的几何元素列表。
     *
     * @param elements 新的几何元素列表
     */
    public void setElements(List<Geometry> elements) {
        this.elements = elements;
    }
}
