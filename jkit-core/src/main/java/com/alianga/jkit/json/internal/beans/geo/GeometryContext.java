package com.alianga.jkit.json.internal.beans.geo;

/**
 * 解析 WKT 几何字符串时使用的上下文，记录当前读取位置、解析模式以及最近读取到的坐标。
 */
public class GeometryContext {
    int offset;
    boolean strict;
    Number x;
    Number y;

    /**
     * 获取当前解析位置。
     *
     * @return 字符数组中已读到的下标
     */
    public int getOffset() {
        return offset;
    }

    /**
     * 设置当前解析位置。
     *
     * @param offset 字符数组中已读到的下标
     */
    public void setOffset(int offset) {
        this.offset = offset;
    }

    /**
     * 判断是否为严格模式。
     *
     * @return 严格模式下返回 {@code true}，否则返回 {@code false}
     */
    public boolean isStrict() {
        return strict;
    }

    /**
     * 设置是否为严格模式。
     *
     * @param strict 为 true 时启用严格模式
     */
    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    /**
     * 获取最近读取到的横坐标。
     *
     * @return 横坐标，含小数点时为 {@code Double}，否则为 {@code Long}；尚未读取过坐标时为 {@code null}
     */
    public Number getX() {
        return x;
    }

    /**
     * 设置最近读取到的横坐标。
     *
     * @param x 横坐标
     */
    public void setX(Number x) {
        this.x = x;
    }

    /**
     * 获取最近读取到的纵坐标。
     *
     * @return 纵坐标，含小数点时为 {@code Double}，否则为 {@code Long}；尚未读取过坐标时为 {@code null}
     */
    public Number getY() {
        return y;
    }

    /**
     * 设置最近读取到的纵坐标。
     *
     * @param y 纵坐标
     */
    public void setY(Number y) {
        this.y = y;
    }
}
