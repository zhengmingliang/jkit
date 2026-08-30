package com.alianga.jkit.json.internal.beans.geo;

import java.util.List;

/**
 * @time 2023/5/15 23:43
 */
public class PolygonPlane extends AbstractPoints {
    /**
     * 构造一个不含任何点的多边形平面。
     */
    public PolygonPlane() {
        super(GeometryType.POLYGON);
    }

    PolygonPlane(List<Point> points) {
        super(GeometryType.POLYGON);
        this.points = points;
    }

}
