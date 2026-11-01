package com.alianga.jkit.captcha;

import java.util.Arrays;
import java.util.List;

/**
 * 同一张图里，用字框高度的相对差距拨正短身字母的大小写。
 *
 * <p>大写短身字母比同图的 x 高度小写高出一截。只在这截差距够大时改，
 * 不绑某一种画布高度。带升部或降部的字母高度重叠，不动。
 */
final class PlateCase {
    /** 短身字母：大写明显更高，小写停在 x 高度。 */
    private static final String TALL_WHEN_UPPER = "ACEMNRVZ";
    private static final int GAP = 4;

    private PlateCase() {
    }

    static String adjust(String text, List<GlyphHit> glyphs, int imageHeight) {
        if (text == null || text.isEmpty() || imageHeight <= 0 || glyphs == null
                || glyphs.size() != text.length() || text.length() < 2) {
            return text == null ? "" : text;
        }
        int[] heights = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            heights[i] = glyphs.get(i).getHeight();
        }
        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        int median = sorted[sorted.length / 2];
        boolean shorter = false;
        for (int i = 0; i < heights.length; i++) {
            if (heights[i] <= median - GAP) {
                shorter = true;
                break;
            }
        }
        if (!shorter) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (TALL_WHEN_UPPER.indexOf(Character.toUpperCase(ch)) < 0) {
                continue;
            }
            int height = heights[i];
            if (ch >= 'a' && ch <= 'z' && height >= median) {
                builder.setCharAt(i, Character.toUpperCase(ch));
            } else if (ch >= 'A' && ch <= 'Z' && height <= median - GAP) {
                builder.setCharAt(i, Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }
}
