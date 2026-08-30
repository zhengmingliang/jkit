package com.alianga.jkit.json.internal.beans.geo;

/**
 * example data: POINT(103 35)
 */
public class Point extends Geometry {
    /**
     * 构造一个坐标为 (0, 0) 的点。
     */
    public Point() {
        this(0, 0);
    }

    Number x;
    Number y;

    /**
     * 使用指定坐标构造点。
     *
     * @param x 横坐标（经度）
     * @param y 纵坐标（纬度）
     */
    public Point(Number x, Number y) {
        super(GeometryType.POINT);
        this.x = x;
        this.y = y;
    }

    /**
     * 创建指定坐标的点。
     *
     * @param x 横坐标（经度）
     * @param y 纵坐标（纬度）
     * @return 新创建的 Point 对象
     */
    public static Point of(Number x, Number y) {
        return new Point(x, y);
    }

    /**
     * 获取横坐标。
     *
     * @return 当前的横坐标，未设置时可能为 {@code null}
     */
    public Number getX() {
        return x;
    }

    /**
     * 设置横坐标。
     *
     * @param x 横坐标（经度）
     */
    public void setX(Number x) {
        this.x = x;
    }

    /**
     * 获取纵坐标。
     *
     * @return 当前的纵坐标，未设置时可能为 {@code null}
     */
    public Number getY() {
        return y;
    }

    /**
     * 设置纵坐标。
     *
     * @param y 纵坐标（纬度）
     */
    public void setY(Number y) {
        this.y = y;
    }

    @Override
    void readBody(char[] chars, GeometryContext geometryContext) {
        int offset = geometryContext.offset;
        // trim()
        while ((chars[++offset]) == ' ') {
            // 去空格
        }
        geometryContext.offset = offset;
        readPoint(chars, geometryContext);
        offset = geometryContext.offset;
        if (chars[offset] != ')') {
            throw new IllegalArgumentException(
                    "Geometry syntax error, offset " + offset + " , expected ')', actual '" + chars[offset] + "'");
        }
        this.x = geometryContext.x;
        this.y = geometryContext.y;
    }

    @Override
    void appendBody(StringBuilder builder) {
        builder.append("(").append(x).append(" ").append(y).append(")");
    }
}
