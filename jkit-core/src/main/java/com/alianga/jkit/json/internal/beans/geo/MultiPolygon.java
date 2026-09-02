package com.alianga.jkit.json.internal.beans.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * example data: MULTIPOLYGON(((103 35,104 35,104 36,103 36,103 35)),((103 36,104 36,104 37,103 36)))
 */
public class MultiPolygon extends AbstractMultiGeometry {
    List<Polygon> elements = new ArrayList<Polygon>();

    /**
     * 构造空的 MULTIPOLYGON 几何对象
     */
    public MultiPolygon() {
        super(GeometryType.MULTIPOLYGON);
    }

    /**
     * 追加一个多边形元素
     *
     * @param polygon 待追加的多边形
     */
    public void add(Polygon polygon) {
        elements.add(polygon);
    }

    /**
     * 批量追加多边形元素
     *
     * @param polygons 待追加的多边形数组
     */
    public void addAll(Polygon... polygons) {
        for (Polygon polygon : polygons) {
            elements.add(polygon);
        }
    }

    /**
     * 批量追加多边形元素
     *
     * @param polygons 待追加的多边形集合
     */
    public void addAll(List<Polygon> polygons) {
        elements.addAll(polygons);
    }

    /**
     * 移除指定下标位置的多边形元素
     *
     * @param index 元素下标，越界时抛出 {@link IndexOutOfBoundsException}
     * @return 被移除的多边形
     */
    public Polygon removeAt(int index) {
        return elements.remove(index);
    }

    /**
     * 移除指定的多边形元素
     *
     * @param polygon 待移除的多边形
     * @return 存在该元素并移除成功时返回 {@code true}，否则返回 {@code false}
     */
    public boolean remove(Polygon polygon) {
        return elements.remove(polygon);
    }

    /**
     * 清空全部多边形元素
     */
    public void clear() {
        elements.clear();
    }

    @Override
    void appendBody(StringBuilder builder) {
        builder.append("(");
        int deleteDotIndex = -1;
        for (Polygon polygon : elements) {
            polygon.appendBody(builder);
            builder.append(",");
            deleteDotIndex = builder.length() - 1;
        }
        if (deleteDotIndex > -1) {
            builder.deleteCharAt(deleteDotIndex);
        }
        builder.append(")");
    }

    @Override
    protected void readElement(char[] chars, GeometryContext geometryContext) {
        Polygon polygon = new Polygon();
        polygon.readBody(chars, geometryContext);
        add(polygon);
    }

    /**
     * 获取全部多边形元素
     *
     * @return 当前的多边形集合，默认为空集合
     */
    public List<Polygon> getElements() {
        return elements;
    }

    /**
     * 设置全部多边形元素
     *
     * @param elements 新的多边形集合
     */
    public void setElements(List<Polygon> elements) {
        this.elements = elements;
    }
}
