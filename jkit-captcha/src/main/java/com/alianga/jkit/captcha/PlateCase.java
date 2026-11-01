package com.alianga.jkit.captcha;

import java.util.List;

/**
 * 高度 47 的浅色板上，用字框高度把短身字母的大小写拨正。
 *
 * <p>这类板上大写字高大约 23 以上、顶在 12 以内，x 高度的小写大约高 20 以内、顶在 15 以下。
 * 只处理上下两档分得开的字母。带升部或降部的字（比如 b、g、y）高度和对方重叠，不动。
 * 其他高度的图沿用原来的结果，避免改到 SpecCaptcha 和 130×48 的样例。
 */
final class PlateCase {
    static final int PLATE_HEIGHT = 47;
    /** 短身字母：大写明显更高，小写停在 x 高度。 */
    private static final String TALL_WHEN_UPPER = "ACEMNRVZ";
    private static final int LOWER_MAX_HEIGHT = 20;
    private static final int LOWER_MIN_TOP = 15;
    private static final int UPPER_MIN_HEIGHT = 24;
    private static final int UPPER_MAX_TOP = 12;

    private PlateCase() {
    }

    static String adjust(String text, List<GlyphHit> glyphs, int imageHeight) {
        if (text == null || text.isEmpty() || imageHeight != PLATE_HEIGHT || glyphs == null
                || glyphs.size() != text.length()) {
            return text == null ? "" : text;
        }
        StringBuilder builder = new StringBuilder(text);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (TALL_WHEN_UPPER.indexOf(Character.toUpperCase(ch)) < 0) {
                continue;
            }
            GlyphHit glyph = glyphs.get(i);
            if (ch >= 'A' && ch <= 'Z' && glyph.getHeight() <= LOWER_MAX_HEIGHT && glyph.getY() >= LOWER_MIN_TOP) {
                builder.setCharAt(i, Character.toLowerCase(ch));
            } else if (ch >= 'a' && ch <= 'z' && glyph.getHeight() >= UPPER_MIN_HEIGHT
                    && glyph.getY() <= UPPER_MAX_TOP) {
                builder.setCharAt(i, Character.toUpperCase(ch));
            }
        }
        return builder.toString();
    }
}
