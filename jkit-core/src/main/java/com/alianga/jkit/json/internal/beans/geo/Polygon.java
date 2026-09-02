package com.alianga.jkit.json.internal.beans.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * example data: POLYGON((103 35,103 36,104 36,105 37,105 37,105 37,103 35))
 * <p>
 * note: Generally, it is a closed path, and there are no restrictions here
 */
public class Polygon extends AbstractMultiGeometry {
    List<PolygonPlane> elements = new ArrayList<PolygonPlane>();

    /**
     * 构造一个不含任何面的多边形。
     */
    public Polygon() {
        super(GeometryType.POLYGON);
    }

    /**
     * 追加一个多边形平面（外环或内环）。
     *
     * @param plane 待追加的多边形平面
     */
    public void add(PolygonPlane plane) {
        elements.add(plane);
    }

    /**
     * 按顺序批量追加多个多边形平面。
     *
     * @param elements 待追加的多边形平面数组
     */
    public void addAll(PolygonPlane... elements) {
        for (PolygonPlane plane : elements) {
            this.elements.add(plane);
        }
    }

    /**
     * 按顺序批量追加多个多边形平面。
     *
     * @param elements 待追加的多边形平面列表
     */
    public void addAll(List<PolygonPlane> elements) {
        this.elements.addAll(elements);
    }

    /**
     * 移除指定下标处的多边形平面。
     *
     * @param index 待移除元素的下标
     * @return 被移除的多边形平面
     */
    public PolygonPlane removeAt(int index) {
        return elements.remove(index);
    }

    /**
     * 移除首个与给定对象相等的多边形平面。
     *
     * @param plane 待移除的多边形平面
     * @return 移除成功时返回 {@code true}，元素不存在时返回 {@code false}
     */
    public boolean remove(PolygonPlane plane) {
        return elements.remove(plane);
    }

    /**
     * 清空所有多边形平面。
     */
    public void clear() {
        elements.clear();
    }

    @Override
    void appendBody(StringBuilder builder) {
        builder.append("(");
        int deleteDotIndex = -1;
        for (PolygonPlane plane : elements) {
            plane.appendBody(builder);
            builder.append(",");
            deleteDotIndex = builder.length() - 1;
        }
        if (deleteDotIndex > -1) {
            builder.deleteCharAt(deleteDotIndex);
        }
        builder.append(")");
    }

    protected void readElement(char[] chars, GeometryContext geometryContext) {
        elements.add(new PolygonPlane(readPoints(chars, geometryContext)));
    }

    /**
     * 获取构成该多边形的所有平面。
     *
     * @return 当前的多边形平面列表，默认为空列表
     */
    public List<PolygonPlane> getElements() {
        return elements;
    }

    /**
     * 整体替换构成该多边形的平面列表。
     *
     * @param elements 新的多边形平面列表
     */
    public void setElements(List<PolygonPlane> elements) {
        this.elements = elements;
    }
}
