package com.alianga.jkit.image;

import java.awt.Color;
import java.awt.Font;
import java.io.OutputStream;

/**
 * Created by 郑明亮 on 2018/9/10 23:28.
 */

/**
 * @author 郑明亮 @email 1072307340@qq.com
 * @version 1.0
 * @time 2018/9/10 23:28
 * TODO
 */
public abstract class Captcha extends Randoms {
    /** 绘制验证码字符使用的字体。 */
    protected Font font = new Font("Verdana", Font.ITALIC | Font.BOLD, 28); // 字体
    /** 验证码随机字符个数。 */
    protected int len = 5; // 验证码随机字符长度
    /** 验证码图片宽度，单位像素。 */
    protected int width = 150; // 验证码显示跨度
    /** 验证码图片高度，单位像素。 */
    protected int height = 40; // 验证码显示高度
    private String chars; // 随机字符串

    /**
     * 生成随机字符数组
     *
     * @return 字符数组
     */
    protected char[] alphas() {
        char[] cs = new char[len];
        for (int i = 0; i < len; i++) {
            cs[i] = alpha();
        }
        chars = new String(cs);
        return cs;
    }

    /**
     * 获取绘制验证码字符使用的字体。
     *
     * @return 当前的字体，默认为 28 号加粗斜体 Verdana
     */
    public Font getFont() {
        return font;
    }

    /**
     * 设置绘制验证码字符使用的字体。
     *
     * @param font 新的字体
     */
    public void setFont(Font font) {
        this.font = font;
    }

    /**
     * 获取验证码随机字符个数。
     *
     * @return 当前的字符个数，默认 5
     */
    public int getLen() {
        return len;
    }

    /**
     * 设置验证码随机字符个数。
     *
     * @param len 新的字符个数
     */
    public void setLen(int len) {
        this.len = len;
    }

    /**
     * 获取验证码图片宽度。
     *
     * @return 当前的图片宽度，单位像素，默认 150
     */
    public int getWidth() {
        return width;
    }

    /**
     * 设置验证码图片宽度。
     *
     * @param width 新的图片宽度，单位像素
     */
    public void setWidth(int width) {
        this.width = width;
    }

    /**
     * 获取验证码图片高度。
     *
     * @return 当前的图片高度，单位像素，默认 40
     */
    public int getHeight() {
        return height;
    }

    /**
     * 设置验证码图片高度。
     *
     * @param height 新的图片高度，单位像素
     */
    public void setHeight(int height) {
        this.height = height;
    }

    /**
     * 给定范围获得随机颜色
     *
     * @param fc RGB 各通道的下界，大于 255 时按 255 处理
     * @param bc RGB 各通道的上界，大于 255 时按 255 处理
     * @return Color 随机颜色
     */
    protected Color color(int fc, int bc) {
        if (fc > 255) {
            fc = 255;
        }
        if (bc > 255) {
            bc = 255;
        }
        int r = fc + num(bc - fc);
        int g = fc + num(bc - fc);
        int b = fc + num(bc - fc);
        return new Color(r, g, b);
    }

    /**
     * 验证码输出,抽象方法，由子类实现
     *
     * @param os 输出流
     */
    public abstract void out(OutputStream os);

    /**
     * 获取随机字符串
     *
     * @return string
     */
    public String text() {
        return chars;
    }
}
