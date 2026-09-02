package com.alianga.jkit.json.internal.beans.geo;

/**
 * 几何对象类型枚举，每个枚举项负责创建对应的几何对象实例
 */
public enum GeometryType {
    /**
     * 几何集合
     */
    GEOMETRYCOLLECTION {
        @Override
        Geometry newInstance() {
            return new GeometryCollection();
        }
    },
    /**
     * 线串
     */
    LINESTRING {
        @Override
        Geometry newInstance() {
            return new LineString();
        }
    },
    /**
     * 多线串
     */
    MULTILINESTRING {
        @Override
        Geometry newInstance() {
            return new MultiLineString();
        }
    },
    /**
     * 多点
     */
    MULTIPOINT {
        @Override
        Geometry newInstance() {
            return new MultiPoint();
        }
    },
    /**
     * 多面
     */
    MULTIPOLYGON {
        @Override
        Geometry newInstance() {
            return new MultiPolygon();
        }
    },
    /**
     * 点
     */
    POINT {
        @Override
        Geometry newInstance() {
            return new Point();
        }
    },
    /**
     * 面
     */
    POLYGON {
        @Override
        Geometry newInstance() {
            return new Polygon();
        }
    };

    Geometry newInstance() {
        throw new UnsupportedOperationException();
    }
}
