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
                : classify(segmented.glyphs, alphabet, segmented.stroke);
        String text = textOf(hits);
        if (!segmented.halo && options.getType() == CaptchaType.AUTO && type == CaptchaType.ARITHMETIC
                && options.getAlphabet() == null && ArithmeticEvaluator.evaluate(text) == null) {
            type = CaptchaType.ALPHANUMERIC;
            hits = classify(segmented.glyphs, alphabetFor(type, options, segmented.narrow), segmented.stroke);
            text = textOf(hits);
        }
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(text);
        String expression = value == null ? null : value.expression;
        Integer number = value == null ? null : Integer.valueOf(value.result);
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(text, expression, number, confidence(hits), type, elapsed,
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
            Pick pick = classifyOne(glyphs.get(i), alphabet, false);
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

    private List<GlyphHit> classify(List<RawGlyph> glyphs, String alphabet, boolean stroke) {
        List<RawGlyph> kept = suppressOverlaps(glyphs);
        List<Pick> picks = new ArrayList<Pick>();
        for (int i = 0; i < kept.size(); i++) {
            Pick pick = classifyOne(kept.get(i), alphabet, stroke);
            if (pick.score < 0.50) {
                continue;
            }
            picks.add(pick);
        }
        adjustCase(picks);
        List<GlyphHit> hits = new ArrayList<GlyphHit>();
        for (int i = 0; i < picks.size(); i++) {
            Pick pick = picks.get(i);
            hits.add(hit(pick.glyph, pick.label, pick.score));
        }
        return hits;
    }

    private Pick classifyOne(RawGlyph glyph, String alphabet, boolean stroke) {
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

    private static String textOf(List<GlyphHit> hits) {
        StringBuilder builder = new StringBuilder(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            builder.append(hits.get(i).getValue());
        }
        return builder.toString();
    }

    private static double confidence(List<GlyphHit> hits) {
        if (hits.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < hits.size(); i++) {
            sum += hits.get(i).getConfidence();
        }
        return sum / hits.size();
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
