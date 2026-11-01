package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PlateCaseTest {

    @Test
    public void tallGlyphAmongShortOnesBecomesUpper() {
        GlyphHit tall = new GlyphHit('v', 0.9, 10, 10, 16, 24);
        GlyphHit sh = new GlyphHit('s', 0.9, 30, 16, 14, 16);
        GlyphHit cap = new GlyphHit('c', 0.9, 50, 10, 16, 24);
        String adjusted = PlateCase.adjust("vsc", Arrays.asList(tall, sh, cap), 58);
        Assert.assertEquals("VsC", adjusted);
    }

    @Test
    public void shortGlyphAmongTallOnesBecomesLower() {
        GlyphHit cap = new GlyphHit('N', 0.9, 10, 8, 18, 24);
        GlyphHit sh = new GlyphHit('A', 0.9, 30, 16, 16, 16);
        GlyphHit cap2 = new GlyphHit('M', 0.9, 50, 8, 18, 25);
        String adjusted = PlateCase.adjust("NAM", Arrays.asList(cap, sh, cap2), 50);
        Assert.assertEquals("NaM", adjusted);
    }

    @Test
    public void similarHeightsStay() {
        GlyphHit left = new GlyphHit('N', 0.9, 10, 9, 18, 21);
        GlyphHit right = new GlyphHit('n', 0.9, 30, 12, 16, 20);
        Assert.assertEquals("Nn", PlateCase.adjust("Nn", Arrays.asList(left, right), 40));
    }

    @Test
    public void mismatchedGlyphCountStays() {
        GlyphHit only = new GlyphHit('N', 0.9, 10, 16, 18, 16);
        Assert.assertEquals("NZ", PlateCase.adjust("NZ", Collections.singletonList(only), 52));
    }
}
