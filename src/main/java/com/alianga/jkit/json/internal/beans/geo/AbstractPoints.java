package com.alianga.jkit.json.internal.beans.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * @time 2023/5/15 22:10
 */
abstract class AbstractPoints extends Geometry {
    public AbstractPoints(GeometryType geometryType) {
        super(geometryType);
    }

    /**
     * 点集合，按几何字符串中出现的顺序保存
     */
    protected List<Point> points = new ArrayList<Point>();

    /**
     * 获取点集合。
     *
     * @return 当前的点集合，返回的是内部列表本身，对其修改会直接影响该几何对象
     */
    public List<Point> getPoints() {
        return points;
    }

    /**
     * 设置点集合，直接替换内部列表引用。
     *
     * @param points 新的点集合
     */
    public void setPoints(List<Point> points) {
        this.points = points;
    }

    /**
     * 向点集合末尾追加一个点。
     *
     * @param point 待追加的点
     */
    public void add(Point point) {
        points.add(point);
    }

    /**
     * 按数组顺序向点集合末尾批量追加点。
     *
     * @param pointList 待追加的点数组
     */
    public void addAll(Point... pointList) {
        for (Point point : pointList) {
            points.add(point);
        }
    }

    /**
     * 按列表顺序向点集合末尾批量追加点。
     *
     * @param pointList 待追加的点列表
     */
    public void addAll(List<Point> pointList) {
        points.addAll(pointList);
    }

    /**
     * 移除指定下标位置上的点。
     *
     * @param index 待移除点的下标
     * @return 被移除的点
     */
    public Point removeAt(int index) {
        return points.remove(index);
    }

    /**
     * 移除集合中第一个与指定点相等的元素。
     *
     * @param point 待移除的点
     * @return 移除成功返回 {@code true}，集合中不存在该点时返回 {@code false}
     */
    public boolean remove(Point point) {
        return points.remove(point);
    }

    /**
     * 清空点集合。
     */
    public void clear() {
        points.clear();
    }

    void appendBody(StringBuilder builder) {
        builder.append("(");
        int deleteDotIndex = -1;
        for (Point point : points) {
            builder.append(point.x).append(" ").append(point.y);
            builder.append(",");
            deleteDotIndex = builder.length() - 1;
        }
        if (deleteDotIndex > -1) {
            builder.deleteCharAt(deleteDotIndex);
        }
        builder.append(")");
    }

    @Override
    final void readBody(char[] chars, GeometryContext geometryContext) {
        setPoints(readPoints(chars, geometryContext));
    }
}
