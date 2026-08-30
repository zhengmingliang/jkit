package com.alianga.jkit.json.internal.beans.geo;

/**
 * example data: MULTIPOINT(103 35, 104 34,105 35)
 */
public class MultiPoint extends AbstractPoints {
    /**
     * 构造几何类型为 {@code MULTIPOINT} 的多点对象。
     */
    public MultiPoint() {
        super(GeometryType.MULTIPOINT);
    }

}
