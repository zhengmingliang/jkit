package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PlateCaseTest {

    @Test
    public void shortUppercaseOnPlateBecomesLower() {
        GlyphHit glyph = new GlyphHit('N', 0.9, 10, 16, 18, 20);
        String adjusted = PlateCase.adjust("N", Collections.singletonList(glyph), PlateCase.PLATE_HEIGHT);
        Assert.assertEquals("n", adjusted);
    }

    @Test
    public void tallUppercaseStaysUpper() {
        GlyphHit glyph = new GlyphHit('A', 0.9, 10, 12, 20, 23);
        String adjusted = PlateCase.adjust("A", Collections.singletonList(glyph), PlateCase.PLATE_HEIGHT);
        Assert.assertEquals("A", adjusted);
    }

    @Test
    public void xHeightLowercaseStaysLower() {
        GlyphHit glyph = new GlyphHit('n', 0.9, 10, 17, 16, 18);
        String adjusted = PlateCase.adjust("n", Collections.singletonList(glyph), PlateCase.PLATE_HEIGHT);
        Assert.assertEquals("n", adjusted);
    }

    @Test
    public void otherHeightsAreLeftAlone() {
        GlyphHit glyph = new GlyphHit('N', 0.9, 10, 16, 18, 20);
        Assert.assertEquals("N", PlateCase.adjust("N", Collections.singletonList(glyph), 40));
        Assert.assertEquals("N", PlateCase.adjust("N", Collections.singletonList(glyph), 48));
    }

    @Test
    public void mismatchedGlyphCountIsLeftAlone() {
        GlyphHit first = new GlyphHit('N', 0.9, 10, 16, 18, 20);
        GlyphHit second = new GlyphHit('Z', 0.9, 30, 16, 18, 19);
        Assert.assertEquals("NZV", PlateCase.adjust("NZV", Arrays.asList(first, second), PlateCase.PLATE_HEIGHT));
    }
}
