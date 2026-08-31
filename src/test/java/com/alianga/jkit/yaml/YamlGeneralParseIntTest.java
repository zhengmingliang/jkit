package com.alianga.jkit.yaml;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class YamlGeneralParseIntTest extends YamlGeneral {

    @Test
    public void parseInt_parsesValidInput() {
        assertEquals(2024, parseInt("2024-08-29".toCharArray(), 0, 4, 10));
        assertEquals(8, parseInt("2024-08-29".toCharArray(), 5, 2, 10));
        assertEquals(-12, parseInt("-12".toCharArray(), 0, 3, 10));
        assertEquals(12, parseInt("+12".toCharArray(), 0, 3, 10));
        assertEquals(255, parseInt("ff".toCharArray(), 0, 2, 16));
        assertEquals(0, parseInt("".toCharArray(), 0, 0, 10));
    }

    @Test
    public void parseInt_illegalInputThrowsNumberFormatException() {
        assertNumberFormatException("*12".toCharArray(), 0, 3, 10);
        assertNumberFormatException("-".toCharArray(), 0, 1, 10);
        assertNumberFormatException("1a".toCharArray(), 0, 2, 10);
        assertNumberFormatException("99999999999".toCharArray(), 0, 11, 10);
    }

    @Test(expected = NumberFormatException.class)
    public void parseInt_nullBufferThrowsNumberFormatException() {
        parseInt(null, 0, 1, 10);
    }

    private void assertNumberFormatException(char[] buf, int fromIndex, int len, int radix) {
        try {
            parseInt(buf, fromIndex, len, radix);
            fail("应抛出 NumberFormatException: " + new String(buf, fromIndex, len));
        } catch (NumberFormatException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(new String(buf, fromIndex, len)));
        }
    }
}
