package com.alianga.jkit.captcha.behavior;

/**
 * 滑动轨迹点。x/y 为相对滑动起点的位移（与起点相减即得服务端校验用的终点位移），
 * t 为相对按下时刻的毫秒数，type 为 down/move/up（大小写不敏感）。
 */
public final class TrackPoint {
    /** 事件类型：按下。 */
    public static final String TYPE_DOWN = "down";
    /** 事件类型：移动。 */
    public static final String TYPE_MOVE = "move";
    /** 事件类型：抬起。 */
    public static final String TYPE_UP = "up";

    private final float x;
    private final float y;
    private final float t;
    private final String type;

    public TrackPoint(float x, float y, float t, String type) {
        this.x = x;
        this.y = y;
        this.t = t;
        this.type = type;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getT() {
        return t;
    }

    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        return "{" + x + "," + y + "," + t + "," + type + "}";
    }
}
