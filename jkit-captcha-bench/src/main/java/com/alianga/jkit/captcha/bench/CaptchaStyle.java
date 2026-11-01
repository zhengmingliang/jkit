package com.alianga.jkit.captcha.bench;

/**
 * 一张验证码的绘制风格。生成器按这张配置画出唯一的一张图，
 * 同一份配置 + 同一个种子可以重画出同一张图。
 */
public final class CaptchaStyle {
    /** 版面：白底、灰底、深色底、渐变底。 */
    public enum Surface {
        /** 纯白底。 */
        PLAIN_WHITE,
        /** 浅灰底，带轻微噪点。 */
        LIGHT_GRAY,
        /** 深色底，字为亮色。 */
        DARK,
        /** 横向渐变底。 */
        GRADIENT
    }

    /** 干扰线画法。 */
    public enum Lines {
        /** 不画线。 */
        NONE,
        /** 少量细直线。 */
        FEW,
        /** 密集直线。 */
        MANY,
        /** 曲线。 */
        CURVE
    }

    /** 背景纹理。 */
    public enum Texture {
        /** 无纹理。 */
        NONE,
        /** 随机噪点。 */
        NOISE,
        /** 随机椭圆斑块。 */
        BLOBS,
        /** 网格线。 */
        GRID
    }

    private final String fontFamily;
    private final int fontStyle;
    private final float fontSize;
    private final Surface surface;
    private final Lines lines;
    private final Texture texture;
    private final float noise;
    private final float rotation;
    private final float spacing;
    private final float scaleX;
    private final float shear;
    private final float wave;
    private final boolean outline;
    private final boolean alphaBlend;
    private final boolean colorPerChar;

    private CaptchaStyle(Builder builder) {
        this.fontFamily = builder.fontFamily;
        this.fontStyle = builder.fontStyle;
        this.fontSize = builder.fontSize;
        this.surface = builder.surface;
        this.lines = builder.lines;
        this.texture = builder.texture;
        this.noise = builder.noise;
        this.rotation = builder.rotation;
        this.spacing = builder.spacing;
        this.scaleX = builder.scaleX;
        this.shear = builder.shear;
        this.wave = builder.wave;
        this.outline = builder.outline;
        this.alphaBlend = builder.alphaBlend;
        this.colorPerChar = builder.colorPerChar;
    }

    /** @return 字体族名 */
    public String getFontFamily() {
        return fontFamily;
    }

    /** @return 字体样式，{@link java.awt.Font} 的 BOLD / ITALIC 组合 */
    public int getFontStyle() {
        return fontStyle;
    }

    /** @return 字号 */
    public float getFontSize() {
        return fontSize;
    }

    /** @return 版面 */
    public Surface getSurface() {
        return surface;
    }

    /** @return 干扰线画法 */
    public Lines getLines() {
        return lines;
    }

    /** @return 背景纹理 */
    public Texture getTexture() {
        return texture;
    }

    /** @return 噪点密度，0 到 1 */
    public float getNoise() {
        return noise;
    }

    /** @return 单字最大旋转角，单位为度 */
    public float getRotation() {
        return rotation;
    }

    /** @return 字距相对字宽的倍率 */
    public float getSpacing() {
        return spacing;
    }

    /** @return 横向缩放 */
    public float getScaleX() {
        return scaleX;
    }

    /** @return 斜切系数 */
    public float getShear() {
        return shear;
    }

    /** @return 正弦扭曲幅度，单位为像素，0 表示不扭曲 */
    public float getWave() {
        return wave;
    }

    /** @return 是否只描边不填充 */
    public boolean isOutline() {
        return outline;
    }

    /** @return 是否用半透明混合画字 */
    public boolean isAlphaBlend() {
        return alphaBlend;
    }

    /** @return 是否每个字符用不同颜色 */
    public boolean isColorPerChar() {
        return colorPerChar;
    }

    /**
     * 风格轴标签。键固定为字体 {@code f}、旋转 {@code r}、噪点 {@code n}、
     * 线条 {@code l}、纹理 {@code t}、字距 {@code g}、缩放 {@code s}。
     *
     * @return 可按逗号拆开的标签
     */
    public String tag() {
        return "f=" + fontFamily
                + ",r=" + Math.round(rotation)
                + ",n=" + Math.round(noise * 100f)
                + ",l=" + lines.name()
                + ",t=" + texture.name()
                + ",g=" + Math.round(spacing * 100f)
                + ",s=" + Math.round(scaleX * 100f);
    }

    /**
     * 从 {@link #tag()} 里取出一个轴的取值。
     *
     * @param tag 风格标签
     * @param key 轴键，例如 {@code f}、{@code r}
     * @return 取值；标签里没有这个键时返回空串
     */
    public static String axis(String tag, String key) {
        if (tag == null || key == null) {
            return "";
        }
        String[] parts = tag.split(",");
        String prefix = key + "=";
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith(prefix)) {
                return parts[i].substring(prefix.length());
            }
        }
        return "";
    }

    /** 逐步构造 {@link CaptchaStyle}。 */
    public static final class Builder {
        private String fontFamily = "Verdana";
        private int fontStyle = java.awt.Font.BOLD;
        private float fontSize = 32f;
        private Surface surface = Surface.PLAIN_WHITE;
        private Lines lines = Lines.NONE;
        private Texture texture = Texture.NONE;
        private float noise;
        private float rotation;
        private float spacing = 1f;
        private float scaleX = 1f;
        private float shear;
        private float wave;
        private boolean outline;
        private boolean alphaBlend;
        private boolean colorPerChar;

        /** @param value 字体族名 */
        public Builder fontFamily(String value) {
            this.fontFamily = value;
            return this;
        }

        /** @param value 字体样式 */
        public Builder fontStyle(int value) {
            this.fontStyle = value;
            return this;
        }

        /** @param value 字号 */
        public Builder fontSize(float value) {
            this.fontSize = value;
            return this;
        }

        /** @param value 版面 */
        public Builder surface(Surface value) {
            this.surface = value;
            return this;
        }

        /** @param value 干扰线画法 */
        public Builder lines(Lines value) {
            this.lines = value;
            return this;
        }

        /** @param value 背景纹理 */
        public Builder texture(Texture value) {
            this.texture = value;
            return this;
        }

        /** @param value 噪点密度 */
        public Builder noise(float value) {
            this.noise = value;
            return this;
        }

        /** @param value 单字最大旋转角 */
        public Builder rotation(float value) {
            this.rotation = value;
            return this;
        }

        /** @param value 字距倍率 */
        public Builder spacing(float value) {
            this.spacing = value;
            return this;
        }

        /** @param value 横向缩放 */
        public Builder scaleX(float value) {
            this.scaleX = value;
            return this;
        }

        /** @param value 斜切系数 */
        public Builder shear(float value) {
            this.shear = value;
            return this;
        }

        /** @param value 正弦扭曲幅度 */
        public Builder wave(float value) {
            this.wave = value;
            return this;
        }

        /** @param value 是否描边 */
        public Builder outline(boolean value) {
            this.outline = value;
            return this;
        }

        /** @param value 是否半透明 */
        public Builder alphaBlend(boolean value) {
            this.alphaBlend = value;
            return this;
        }

        /** @param value 是否逐字变色 */
        public Builder colorPerChar(boolean value) {
            this.colorPerChar = value;
            return this;
        }

        /** @return 构造好的风格 */
        public CaptchaStyle build() {
            return new CaptchaStyle(this);
        }
    }
}
