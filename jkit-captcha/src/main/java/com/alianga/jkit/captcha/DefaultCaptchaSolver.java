package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 默认求解器。按背景选择分割器，再用字体模板做 Dice 匹配。
 */
final class DefaultCaptchaSolver implements CaptchaSolver {
    private static final double PLUS_OVERRIDE = 0.80;
    private static final double FORCED_CONFIDENCE = 0.92;
    /** 深色图仲裁门槛：字母均值比算术均值高出这个值时按字母图处理。字母图实测高 0.10 以上，真算术低 0.04 以上。 */
    private static final double DARK_LETTER_MARGIN = 0.05;

    private final TemplateBank bank;
    private final SpecRefiner refiner = new SpecRefiner();
    private final HaloBank halo = new HaloBank();

    DefaultCaptchaSolver(TemplateBank bank) {
        this.bank = bank;
    }

    @Override
    public boolean supports(CaptchaType type) {
        return type != null;
    }

    @Override
    public RecognizeResult recognize(BufferedImage image, RecognizeOptions options) {
        if (image == null) {
            throw new IllegalArgumentException("image");
        }
        if (options == null) {
            options = RecognizeOptions.auto();
        }
        long started = System.nanoTime();
        RgbImage rgb = RgbImage.from(image);
        Segmented segmented = segment(rgb, options);
        CaptchaType type = options.getType() == CaptchaType.AUTO ? segmented.type : options.getType();
        String alphabet = alphabetFor(type, options, segmented.narrow);
        List<GlyphHit> hits = segmented.halo
                ? classifyHalo(segmented.glyphs, alphabet)
                : classify(segmented.glyphs, alphabet, segmented.stroke, segmented.narrow);
        String text = GlyphHit.textOf(hits);
        // 深色图可能是算术也可能是字母：自动判断一律先按算术字母表分类，字母图会被强套上
        // 数字标签，减号被当干扰线擦掉的算术图又只剩裸数字算不出值。这里把两套字母表各打
        // 一遍分：字母均值明显更高的是字母图，翻转过去；否则维持算术路径，算式的运算符
        // 缺损交给 MinusInsert/OpRepair 在外层补回。这个仲裁是权威决策，不再做算不出值的重试。
        if (!segmented.halo && segmented.type == CaptchaType.ARITHMETIC
                && options.getType() == CaptchaType.AUTO && options.getAlphabet() == null) {
            List<GlyphHit> letters = classify(segmented.glyphs,
                    alphabetFor(CaptchaType.ALPHANUMERIC, options, segmented.narrow),
                    segmented.stroke, segmented.narrow);
            if (GlyphHit.confidence(letters) > GlyphHit.confidence(hits) + DARK_LETTER_MARGIN) {
                type = CaptchaType.ALPHANUMERIC;
                hits = letters;
                text = GlyphHit.textOf(hits);
            }
        }
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(text);
        String expression = value == null ? null : value.expression;
        Integer number = value == null ? null : Integer.valueOf(value.result);
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(text, expression, number, GlyphHit.confidence(hits), type, elapsed,
                RecognizeResult.freeze(hits));
    }

    private Segmented segment(RgbImage rgb, RecognizeOptions options) {
        if (HaloBank.matches(rgb)) {
            return new Segmented(HaloBank.segment(rgb), CaptchaType.ALPHANUMERIC, false, false, true);
        }
        int background = rgb.borderMedianLuminance();
        if (background < 80) {
            return new Segmented(new DarkSegmenter().segment(rgb), CaptchaType.ARITHMETIC, false, false, false);
        }
        if (OutlineSegmenter.matches(rgb)) {
            return new Segmented(new OutlineSegmenter().segment(rgb), CaptchaType.DISTORTED, true, false, false);
        }
        LightSegmenter light = new LightSegmenter();
        List<RawGlyph> glyphs = light.segment(rgb);
        CaptchaType type = light.splitWideComponent ? CaptchaType.DISTORTED : CaptchaType.ALPHANUMERIC;
        CaptchaType requested = options.getType();
        if (rgb.height >= 48 && (requested == CaptchaType.DISTORTED || requested == CaptchaType.AUTO)) {
            String waveAlphabet = alphabetFor(CaptchaType.ALPHANUMERIC, options);
            List<RawGlyph> straightened = betterWave(rgb, glyphs, waveAlphabet);
            if (straightened != null) {
                glyphs = straightened;
                type = CaptchaType.DISTORTED;
            }
        }
        boolean narrow = rgb.height >= 32 && rgb.height <= 46 && rgb.width >= 110 && rgb.width <= 260;
        return new Segmented(glyphs, type, false, narrow, false);
    }

    private List<GlyphHit> classifyHalo(List<RawGlyph> glyphs, String alphabet) {
        List<RawGlyph> kept = suppressOverlaps(glyphs);
        List<GlyphHit> hits = new ArrayList<GlyphHit>();
        for (int i = 0; i < kept.size(); i++) {
            RawGlyph glyph = kept.get(i);
            byte[] grid = GlyphNormalizer.toGrid(glyph.pixels, glyph.width, glyph.height);
            int holes = HoleCount.count(glyph.pixels, glyph.width, glyph.height);
            HaloBank.Scored best = halo.best(grid, holes, alphabet);
            if (best == null || best.score < 0.50) {
                continue;
            }
            hits.add(hit(glyph, best.label, best.score));
        }
        return hits;
    }

    private List<RawGlyph> betterWave(RgbImage image, List<RawGlyph> plain, String alphabet) {
        double base = quality(plain, alphabet);
        List<RawGlyph> best = null;
        double bestQuality = base + 0.02;
        int[][] waves = {{3, 2}, {2, 2}, {4, 2}};
        for (int i = 0; i < waves.length; i++) {
            RgbImage undone = WaveStraightener.undo(image, waves[i][0], waves[i][1]);
            List<RawGlyph> glyphs = new LightSegmenter().segment(undone);
            double score = quality(glyphs, alphabet);
            if (glyphs.size() >= 2 && score > bestQuality) {
                bestQuality = score;
                best = glyphs;
            }
        }
        return best;
    }

    private double quality(List<RawGlyph> glyphs, String alphabet) {
        if (glyphs.isEmpty()) {
            return 0;
        }
        double sum = 0;
        double lowest = 1;
        for (int i = 0; i < glyphs.size(); i++) {
            Pick pick = classifyOne(glyphs.get(i), alphabet, false, 0);
            sum += pick.score;
            if (pick.score < lowest) {
                lowest = pick.score;
            }
        }
        double mean = sum / glyphs.size();
        if (lowest < 0.62) {
            mean -= 0.2;
        }
        return mean;
    }

    private List<GlyphHit> classify(List<RawGlyph> glyphs, String alphabet, boolean stroke, boolean specCase) {
        List<RawGlyph> kept = suppressOverlaps(glyphs);
        int[] heights = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            heights[i] = kept.get(i).height;
        }
        Arrays.sort(heights);
        int medianHeight = heights.length == 0 ? 0 : heights[heights.length / 2];
        List<Pick> picks = new ArrayList<Pick>();
        for (int i = 0; i < kept.size(); i++) {
            Pick pick = classifyOne(kept.get(i), alphabet, stroke, medianHeight);
            if (pick.score < 0.50) {
                continue;
            }
            picks.add(pick);
        }
        adjustCase(picks);
        if (specCase) {
            adjustSpecCase(picks);
        }
        List<GlyphHit> hits = new ArrayList<GlyphHit>();
        for (int i = 0; i < picks.size(); i++) {
            Pick pick = picks.get(i);
            hits.add(hit(pick.glyph, pick.label, pick.score));
        }
        return hits;
    }

    private Pick classifyOne(RawGlyph glyph, String alphabet, boolean stroke, int medianHeight) {
        if (glyph.forced != 0 && alphabet.indexOf(glyph.forced) >= 0) {
            return new Pick(glyph, glyph.forced, FORCED_CONFIDENCE, (char) 0, 0);
        }
        String candidates = alphabetForHeight(glyph, alphabet);
        byte[] grid = GlyphNormalizer.toGrid(glyph.pixels, glyph.width, glyph.height);
        boolean plainOnly = !stroke && glyph.width <= 14 && glyph.height <= 15;
        List<TemplateBank.Scored> ranked = bank.rank(grid, candidates, 6, plainOnly, stroke);
        if (ranked.isEmpty()) {
            return new Pick(glyph, '?', 0, (char) 0, 0);
        }
        TemplateBank.Scored best = ranked.get(0);
        char label = best.label;
        double score = best.score;
        if (!stroke && candidates.indexOf('+') >= 0) {
            boolean plus = PlusShape.looksLikePlus(glyph.pixels, glyph.width, glyph.height);
            if (plus && best.score < PLUS_OVERRIDE) {
                label = '+';
                score = Math.max(best.score, PLUS_OVERRIDE);
            } else if (best.label == '+' && !plus && best.score < 0.85) {
                TemplateBank.Scored other = nextNonPlus(ranked);
                if (other != null) {
                    label = other.label;
                    score = other.score;
                }
            }
        }
        boolean arithmetic = candidates.indexOf('+') >= 0;
        if (!stroke && !arithmetic && glyph.height >= 14 && glyph.height <= 36 && ranked.size() > 1
                && best.score - ranked.get(1).score <= 0.06) {
            char refined = refiner.choose(glyph, ranked);
            for (int i = 0; i < ranked.size(); i++) {
                if (ranked.get(i).label == refined) {
                    label = refined;
                    score = ranked.get(i).score;
                    break;
                }
            }
        }
        if (!stroke) {
            TemplateBank.Scored holed = preferHoles(glyph, ranked, label, score);
            if (holed != null) {
                label = holed.label;
                score = holed.score;
            }
        }
        label = adjudicateNarrow(glyph, candidates, ranked, label);
        score = scoreOf(ranked, label, score);
        // 字高证据：0 顶在大写线，明显矮于中位数的字形不可能是 0，
        // 改判 ranked 里最好的 x 高度字母（o u c s e a z x n m w v r 这批矮身字母）。
        if (label == '0' && medianHeight > 0 && glyph.height <= medianHeight - 4) {
            char shorter = bestXHeight(ranked);
            if (shorter != 0) {
                label = shorter;
                score = scoreOf(ranked, shorter, score);
            }
        }
        char rival = 0;
        double rivalScore = 0;
        char flipped = flipCase(label);
        if (flipped != 0 && candidates.indexOf(flipped) >= 0) {
            List<TemplateBank.Scored> otherCase = bank.rank(grid, String.valueOf(flipped), 1, plainOnly, stroke);
            if (!otherCase.isEmpty()) {
                rival = flipped;
                rivalScore = otherCase.get(0).score;
            }
        }
        return new Pick(glyph, label, score, rival, rivalScore);
    }

    private static void adjustCase(List<Pick> picks) {
        if (picks.size() < 2) {
            return;
        }
        int[] heights = new int[picks.size()];
        for (int i = 0; i < picks.size(); i++) {
            heights[i] = picks.get(i).glyph.height;
        }
        int[] ordered = heights.clone();
        Arrays.sort(ordered);
        int median = ordered[ordered.length / 2];
        int cap = Integer.MAX_VALUE;
        for (int i = 0; i < picks.size(); i++) {
            Pick pick = picks.get(i);
            if (pick.glyph.height >= median && pick.glyph.y < cap) {
                cap = pick.glyph.y;
            }
        }
        if (cap == Integer.MAX_VALUE) {
            return;
        }
        for (int i = 0; i < picks.size(); i++) {
            Pick pick = picks.get(i);
            if (pick.rival == 0) {
                continue;
            }
            double gap = Math.abs(pick.score - pick.rivalScore);
            char lower = Character.toLowerCase(pick.label);
            if (!isShortBody(lower) || gap > 0.14) {
                continue;
            }
            boolean clearlyShort = pick.glyph.height <= median - 5;
            boolean clearlyLow = pick.glyph.y >= cap + 6;
            boolean clearlyTall = pick.glyph.height >= median - 1 && pick.glyph.y <= cap + 2;
            boolean wantLower;
            if (clearlyShort || clearlyLow) {
                wantLower = true;
            } else if (clearlyTall) {
                wantLower = false;
            } else {
                continue;
            }
            char want = wantLower ? Character.toLowerCase(pick.label) : Character.toUpperCase(pick.label);
            if (want == pick.rival) {
                pick.label = pick.rival;
                pick.score = pick.rivalScore;
            }
        }
    }

    /**
     * SpecCaptcha 字高约 28，大写顶在 9、高 21，x 高度的小写顶在 14、高 16。
     * 顶到不了大写线的字母改成小写；x 高度字母的盒子却顶到大写线时改成大写。
     */
    private static void adjustSpecCase(List<Pick> picks) {
        for (int i = 0; i < picks.size(); i++) {
            Pick pick = picks.get(i);
            char label = pick.label;
            int top = pick.glyph.y;
            int height = pick.glyph.height;
            if (label >= 'A' && label <= 'Z' && top >= 10) {
                pick.label = (char) (label + 32);
                continue;
            }
            int bottom = top + height;
            if (label >= 'a' && label <= 'z' && specShortBody(label) && top <= 9 && height >= 20 && bottom <= 33) {
                pick.label = (char) (label - 32);
            }
        }
    }

    private static boolean specShortBody(char label) {
        return label == 'a' || label == 'c' || label == 'e' || label == 'm' || label == 'n'
                || label == 'r' || label == 's' || label == 'u' || label == 'v' || label == 'w'
                || label == 'x' || label == 'z';
    }

    private static boolean isShortBody(char label) {
        return label == 'a' || label == 'c' || label == 'e' || label == 'm' || label == 'n'
                || label == 'o' || label == 'r' || label == 's' || label == 'u' || label == 'v'
                || label == 'w' || label == 'x' || label == 'y' || label == 'z';
    }

    private static char flipCase(char label) {
        if (label >= 'A' && label <= 'Z') {
            return Character.toLowerCase(label);
        }
        if (label >= 'a' && label <= 'z') {
            return Character.toUpperCase(label);
        }
        return 0;
    }

    private static TemplateBank.Scored preferHoles(RawGlyph glyph, List<TemplateBank.Scored> ranked,
                                                    char label, double score) {
        if (!Character.isLetter(label)) {
            return null;
        }
        int seen = HoleCount.count(glyph.pixels, glyph.width, glyph.height);
        int expected = HoleCount.expected(label);
        if (expected < 0 || expected == seen || seen > expected) {
            return null;
        }
        double margin = 0.08;
        for (int i = 0; i < ranked.size(); i++) {
            TemplateBank.Scored other = ranked.get(i);
            if (score - other.score > margin) {
                break;
            }
            if (HoleCount.expected(other.label) == seen) {
                return other;
            }
        }
        return null;
    }

    private static TemplateBank.Scored nextNonPlus(List<TemplateBank.Scored> ranked) {
        for (int i = 1; i < ranked.size(); i++) {
            if (ranked.get(i).label != '+') {
                return ranked.get(i);
            }
        }
        return null;
    }

    /**
     * 窄长条字形（i I l）的几何仲裁。竖条模板彼此本来就像，模板分拉不开；
     * 小写 i 的点断、大写 I 的上下衬线是分割连通域里稳定可测的形状证据
     * （噪点是独立连通域，不进字形框，不会伪造点断）。只在窄而高的连通域上生效，
     * 且模板分差超过护栏时不动，避免几何证据推翻强模板证据。
     */
    private static char adjudicateNarrow(RawGlyph glyph, String candidates, List<TemplateBank.Scored> ranked,
                                         char label) {
        if (glyph.width > 14 || glyph.height < 14 || glyph.pixels.length == 0) {
            return label;
        }
        boolean family = label == 'i' || label == 'I' || label == 'l' || label == 'L';
        if (!family) {
            return label;
        }
        char want;
        if (stemRuns(glyph) >= 2) {
            want = Character.isUpperCase(label) ? 'I' : 'i';
        } else if (label == 'i') {
            want = 'l';
        } else if (label == 'l') {
            want = hasSerifs(glyph) ? 'I' : 'l';
        } else {
            want = label;
        }
        if (want == label || candidates.indexOf(want) < 0) {
            return label;
        }
        double wantScore = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).label == want) {
                wantScore = ranked.get(i).score;
                break;
            }
        }
        if (wantScore < 0) {
            return label;
        }
        double best = ranked.get(0).score;
        if (best - wantScore > 0.30) {
            return label;
        }
        return want;
    }

    /** 主茎列（含左右各一列）上的垂直墨段数；小写 i 是点、隙、茎三段，l 是连续一段。 */
    private static int stemRuns(RawGlyph glyph) {
        int[] colInk = new int[glyph.width];
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                if (glyph.pixels[y * glyph.width + x] != 0) {
                    colInk[x]++;
                }
            }
        }
        int stem = 0;
        for (int x = 1; x < glyph.width; x++) {
            if (colInk[x] > colInk[stem]) {
                stem = x;
            }
        }
        int runs = 0;
        boolean inInk = false;
        for (int y = 0; y < glyph.height; y++) {
            boolean on = false;
            int left = Math.max(0, stem - 1);
            int right = Math.min(glyph.width - 1, stem + 1);
            for (int x = left; x <= right; x++) {
                if (glyph.pixels[y * glyph.width + x] != 0) {
                    on = true;
                    break;
                }
            }
            if (on && !inInk) {
                runs++;
            }
            inInk = on;
        }
        return runs;
    }

    /** 上下两端都比主茎宽出 4 像素以上，是 Verdana 大写 I 的衬线。 */
    private static boolean hasSerifs(RawGlyph glyph) {
        int[] colInk = new int[glyph.width];
        int stem = 0;
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                if (glyph.pixels[y * glyph.width + x] != 0) {
                    colInk[x]++;
                }
            }
        }
        for (int x = 1; x < glyph.width; x++) {
            if (colInk[x] > colInk[stem]) {
                stem = x;
            }
        }
        return rowWidth(glyph, 0) >= stemSpan(glyph, stem) + 4
                && rowWidth(glyph, glyph.height - 1) >= stemSpan(glyph, stem) + 4;
    }

    /** 一行内最左到最右墨的跨度，没有墨返回 0。 */
    private static int rowWidth(RawGlyph glyph, int y) {
        int left = -1;
        int right = -1;
        for (int x = 0; x < glyph.width; x++) {
            if (glyph.pixels[y * glyph.width + x] != 0) {
                if (left < 0) {
                    left = x;
                }
                right = x;
            }
        }
        return left < 0 ? 0 : right - left + 1;
    }

    /** 主茎列附近三列的墨宽。 */
    private static int stemSpan(RawGlyph glyph, int stem) {
        int count = 0;
        int left = Math.max(0, stem - 1);
        int right = Math.min(glyph.width - 1, stem + 1);
        for (int x = left; x <= right; x++) {
            if (colHasInk(glyph, x)) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static boolean colHasInk(RawGlyph glyph, int x) {
        for (int y = 0; y < glyph.height; y++) {
            if (glyph.pixels[y * glyph.width + x] != 0) {
                return true;
            }
        }
        return false;
    }

    private static double scoreOf(List<TemplateBank.Scored> ranked, char label, double fallback) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).label == label) {
                return ranked.get(i).score;
            }
        }
        return fallback;
    }

    /** ranked 里得分最高的 x 高度字母；没有返回 0。 */
    private static char bestXHeight(List<TemplateBank.Scored> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            char label = ranked.get(i).label;
            if ("ocuserazxnmwv".indexOf(label) >= 0) {
                return label;
            }
        }
        return 0;
    }

    private static String alphabetFor(CaptchaType type, RecognizeOptions options, boolean narrow) {
        String alphabet = alphabetFor(type, options);
        if (!narrow || options.getAlphabet() != null || type == CaptchaType.ARITHMETIC) {
            return alphabet;
        }
        return TemplateBank.SPEC_SAFE;
    }

    private static String alphabetFor(CaptchaType type, RecognizeOptions options) {
        String alphabet = options.getAlphabet();
        if (alphabet != null && alphabet.length() > 0) {
            return alphabet;
        }
        if (type == CaptchaType.ARITHMETIC) {
            return TemplateBank.ARITHMETIC;
        }
        return TemplateBank.ALPHANUMERIC;
    }

    private static String alphabetForHeight(RawGlyph glyph, String alphabet) {
        if (glyph.height < 14) {
            return alphabet;
        }
        StringBuilder builder = new StringBuilder(alphabet.length());
        for (int i = 0; i < alphabet.length(); i++) {
            char ch = alphabet.charAt(i);
            if (ch == '-' || ch == '=') {
                continue;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private static List<RawGlyph> suppressOverlaps(List<RawGlyph> glyphs) {
        boolean[] drop = new boolean[glyphs.size()];
        for (int i = 0; i < glyphs.size(); i++) {
            if (drop[i]) {
                continue;
            }
            for (int j = i + 1; j < glyphs.size(); j++) {
                if (drop[j] || !heavyOverlap(glyphs.get(i), glyphs.get(j))) {
                    continue;
                }
                if (glyphs.get(i).area < glyphs.get(j).area) {
                    drop[i] = true;
                    break;
                }
                drop[j] = true;
            }
        }
        List<RawGlyph> kept = new ArrayList<RawGlyph>();
        for (int i = 0; i < glyphs.size(); i++) {
            if (!drop[i]) {
                kept.add(glyphs.get(i));
            }
        }
        return kept;
    }

    private static boolean heavyOverlap(RawGlyph left, RawGlyph right) {
        int overlapX = Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x);
        int overlapY = Math.min(left.y + left.height, right.y + right.height) - Math.max(left.y, right.y);
        if (overlapX <= 0 || overlapY <= 0) {
            return false;
        }
        int smallWidth = Math.min(left.width, right.width);
        int smallHeight = Math.min(left.height, right.height);
        return overlapX * 10 > smallWidth * 6 && overlapY * 10 > smallHeight * 6;
    }

    private static GlyphHit hit(RawGlyph glyph, char label, double score) {
        return new GlyphHit(label, score, glyph.x, glyph.y, glyph.width, glyph.height);
    }

    private static final class Segmented {
        final List<RawGlyph> glyphs;
        final CaptchaType type;
        final boolean stroke;
        final boolean narrow;
        final boolean halo;

        Segmented(List<RawGlyph> glyphs, CaptchaType type, boolean stroke, boolean narrow, boolean halo) {
            this.glyphs = glyphs;
            this.type = type;
            this.stroke = stroke;
            this.narrow = narrow;
            this.halo = halo;
        }
    }

    private static final class Pick {
        final RawGlyph glyph;
        char label;
        double score;
        char rival;
        final double rivalScore;

        Pick(RawGlyph glyph, char label, double score, char rival, double rivalScore) {
            this.glyph = glyph;
            this.label = label;
            this.score = score;
            this.rival = rival;
            this.rivalScore = rivalScore;
        }
    }
}
