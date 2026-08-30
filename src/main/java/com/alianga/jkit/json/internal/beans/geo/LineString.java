package com.alianga.jkit.json.internal.beans.geo;

import java.util.List;

/**
 * example data: LINESTRING(103 35,103 36,104 36,105 37)
 */
public class LineString extends AbstractPoints {
    /**
     * 创建一个几何类型为 {@code LINESTRING} 的空线串对象。
     */
    public LineString() {
        super(GeometryType.LINESTRING);
    }

    LineString(List<Point> points) {
        super(GeometryType.POLYGON);
        this.points = points;
    }

}
