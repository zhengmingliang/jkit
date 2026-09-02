package com.alianga.jkit;

import com.alianga.jkit.crypto.Hash64;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class Hash64Test {
    @Test
    public void testKnownFnv1aValue() {
        assertEquals(0xa430d84680aabd0bL, Hash64.hash("hello"));
    }

    @Test
    public void testIncrementalHashMatchesWholeString() {
        long hash = Hash64.hash(Hash64.OFFSET_BASIS, "hel");
        hash = Hash64.hash(hash, "lo");
        assertEquals(Hash64.hash("hello"), hash);
    }

    @Test
    public void testAsciiStringMatchesBytes() {
        byte[] bytes = "jkit".getBytes(StandardCharsets.US_ASCII);
        assertEquals(Hash64.hash(bytes), Hash64.hash("jkit"));
    }

    @Test
    public void testDifferentInputsUsuallyDiffer() {
        assertNotEquals(Hash64.hash("jkit"), Hash64.hash("wast"));
    }
}
