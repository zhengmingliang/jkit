package com.alianga.jkit.notify;

import com.alianga.jkit.Base64Utils;
import com.alianga.jkit.EncryptUtils;
import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.RandomUtils;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.collection.Collections;
import com.alianga.jkit.collection.Maps;
import com.alianga.jkit.io.ByteUtils;
import com.alianga.jkit.json.JSON;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Throwaway: OLD local NotifyUtils logic vs NEW jkit-core delegations.
 * Evidence for behavioral equivalence after refactor.
 */
public class NotifyUtilsCoreEquivTest {

    // ===== OLD implementations recovered from HEAD NotifyUtils =====

    static String oldBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String oldUrlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    static String oldJsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    static String oldRenderTemplate(String template, Map<String, ?> vars) {
        if (template == null || vars == null || vars.isEmpty() || template.indexOf('$') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, start);
            String key = template.substring(start + 2, end).trim();
            Object value = oldLookupVar(vars, key);
            out.append(value == null ? "" : String.valueOf(value));
            i = end + 1;
        }
        return out.toString();
    }

    static Object oldLookupVar(Map<String, ?> vars, String key) {
        if (key.isEmpty()) {
            return null;
        }
        if (vars.containsKey(key)) {
            return vars.get(key);
        }
        int dot = key.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        Object current = vars.get(key.substring(0, dot));
        int from = dot + 1;
        while (from <= key.length()) {
            int next = key.indexOf('.', from);
            String part = next < 0 ? key.substring(from) : key.substring(from, next);
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(part);
            if (next < 0) {
                return current;
            }
            from = next + 1;
        }
        return current;
    }

    static String oldDigestHex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data == null ? new byte[0] : data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String oldSha256HexFile(File file) {
        if (file == null) {
            return oldDigestHex(new byte[0]);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] buf = new byte[8192];
                long remain = file.length();
                while (remain > 0) {
                    int want = (int) Math.min(buf.length, remain);
                    int read = in.read(buf, 0, want);
                    if (read < 0) {
                        break;
                    }
                    digest.update(buf, 0, read);
                    remain -= read;
                }
            } finally {
                in.close();
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String oldHexLower(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    /** Old blank check used in parseDataSize / mimeFromSuffix: null || trim().isEmpty() */
    static boolean oldIsBlankViaTrim(String spec) {
        return spec == null || spec.trim().isEmpty();
    }

    /** Old empty check: == null ? "" : fragment for wrap; isEmpty() for length==0 */
    static boolean oldIsEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** Reconstruct pre-core form body: LinkedHashMap order, URLEncoder, skip null values */
    static String oldFormEncode(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(oldUrlEncode(e.getKey())).append('=').append(oldUrlEncode(e.getValue()));
        }
        return sb.toString();
    }

    static void assertEq(String label, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            Assert.fail(label + "\n  OLD: " + expected + "\n  NEW: " + actual);
        }
    }

    @Test
    public void base64_same() {
        byte[][] samples = new byte[][]{
                new byte[0],
                "hello".getBytes(StandardCharsets.UTF_8),
                "中文😊".getBytes(StandardCharsets.UTF_8),
                new byte[]{0, 1, 2, (byte) 0xff, (byte) 0x80, 0x7f},
        };
        for (byte[] s : samples) {
            assertEq("base64 " + java.util.Arrays.toString(s), oldBase64(s), NotifyUtils.base64(s));
            assertEq("base64 via Base64Utils", oldBase64(s), Base64Utils.encodeToString(s));
        }
        System.out.println("PASS base64: empty/ascii/unicode/binary");
    }

    @Test
    public void urlEncode_same() {
        String[] samples = {"", " ", "a&b", "中文", "a=b&c=d", "hello world", "100%", "+"};
        for (String s : samples) {
            assertEq("urlEncode [" + s + "]", oldUrlEncode(s), NotifyUtils.urlEncode(s));
            assertEq("encodeValue", oldUrlEncode(s), HttpUtils.encodeValue(s));
        }
        // null: OLD NPE, NEW returns ""
        boolean oldNpe = false;
        try {
            oldUrlEncode(null);
        } catch (NullPointerException e) {
            oldNpe = true;
        }
        String newNull = HttpUtils.encodeValue(null);
        Assert.assertTrue("OLD urlEncode(null) NPE", oldNpe);
        Assert.assertEquals("NEW encodeValue(null)=\"\"", "", newNull);
        System.out.println("PASS urlEncode non-null; DIFF null: old NPE vs new \"\"");
    }

    @Test
    public void formEncode_orderAndEncoding() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("b", "2");
        m.put("a", "1");
        m.put("space", "x y");
        m.put("amp", "a&b");
        m.put("cn", "你好");
        m.put("empty", "");
        m.put("skip", null);
        String old = oldFormEncode(m);
        String neu = NotifyUtils.formEncode(m);
        assertEq("formEncode multi", old, neu);

        Map<String, String> empty = new LinkedHashMap<String, String>();
        assertEq("formEncode empty map", "", NotifyUtils.formEncode(empty));
        assertEq("formEncode null", "", NotifyUtils.formEncode(null));

        // getRequestParamString empty value kept (not skipped) — same as oldFormEncode
        Map<String, Object> obj = Maps.newLinkedHashMap();
        obj.put("k", "");
        Assert.assertEquals("k=", HttpUtils.getRequestParamString(obj));
        System.out.println("PASS formEncode/getRequestParamString order+encoding; null values skipped");
    }

    @Test
    public void jsonEscape_compare() {
        String[] samples = {
                "",
                "plain",
                "say \"hi\"",
                "a\\b",
                "line\nbreak",
                "tab\there",
                "cr\r",
                "back\b",
                "form\f",
                "ctrl\u0001",
                "中文",
                "slash/path",
                "u2028\u2028",
                "mix \" \\ \n 中",
        };
        int diffs = 0;
        StringBuilder report = new StringBuilder();
        for (String s : samples) {
            String o = oldJsonEscape(s);
            String n = NotifyUtils.jsonEscape(s);
            if (!o.equals(n)) {
                diffs++;
                report.append("DIFF jsonEscape input=").append(escapeForLog(s))
                        .append("\n  OLD=").append(o)
                        .append("\n  NEW=").append(n).append("\n");
            }
        }
        Assert.assertEquals("", oldJsonEscape(null));
        Assert.assertEquals("", NotifyUtils.jsonEscape(null));
        if (diffs > 0) {
            System.out.println("jsonEscape DIFFERENCES (" + diffs + "):\n" + report);
        } else {
            System.out.println("PASS jsonEscape all samples equal");
        }
        // Don't fail the suite yet — collect evidence; caller will fix if needed
        Assert.assertEquals("jsonEscape should match old for all samples; see stdout", 0, diffs);
    }

    private static String escapeForLog(String s) {
        if (s == null) {
            return "null";
        }
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @Test
    public void renderTemplate_compare() {
        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put("name", "Bob");
        vars.put("empty", "");
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("city", "SH");
        vars.put("user", nested);
        vars.put("user.city", "FLAT"); // flat key wins in OLD via containsKey

        String[] templates = {
                "hello ${name}",
                "missing ${nope} end",
                "nested ${user.city}",
                "no dollar",
                "",
                "${name}${name}",
                "space ${ name }",
                "regex meta ${name} value with $1",
                "backslash \\${name}",
                "hyphen ${foo-bar}",
                "chinese ${你好}",
                "${}",
                "unclosed ${name",
                "dollar $alone",
        };
        vars.put("foo-bar", "HB");
        vars.put("你好", "NIHAO");

        int diffs = 0;
        StringBuilder report = new StringBuilder();
        for (String t : templates) {
            String o = oldRenderTemplate(t, vars);
            String n = NotifyUtils.renderTemplate(t, vars);
            if (o == null ? n != null : !o.equals(n)) {
                diffs++;
                report.append("DIFF template=").append(t)
                        .append("\n  OLD=").append(o)
                        .append("\n  NEW=").append(n).append("\n");
            }
        }
        // null / empty vars
        Assert.assertEquals(oldRenderTemplate("x ${name}", null), NotifyUtils.renderTemplate("x ${name}", null));
        Assert.assertEquals(oldRenderTemplate(null, vars), NotifyUtils.renderTemplate(null, vars));
        Assert.assertEquals(oldRenderTemplate("x ${name}", new LinkedHashMap<String, Object>()),
                NotifyUtils.renderTemplate("x ${name}", new LinkedHashMap<String, Object>()));

        // value containing regex-special chars
        Map<String, Object> v2 = new LinkedHashMap<String, Object>();
        v2.put("x", "$1 \\ ${oops}");
        assertEq("regex-special value", oldRenderTemplate("A${x}B", v2), NotifyUtils.renderTemplate("A${x}B", v2));

        if (diffs > 0) {
            System.out.println("renderTemplate DIFFERENCES (" + diffs + "):\n" + report);
        } else {
            System.out.println("PASS renderTemplate all samples equal");
        }
        Assert.assertEquals("renderTemplate should match old; see stdout", 0, diffs);
    }

    @Test
    public void uuid_formatOnly() {
        String u = NotifyUtils.uuid();
        Assert.assertEquals(32, u.length());
        Assert.assertTrue(Pattern.matches("[0-9a-f]{32}", u));
        String u2 = RandomUtils.getUUID();
        Assert.assertEquals(32, u2.length());
        Assert.assertTrue(Pattern.matches("[0-9a-f]{32}", u2));
        // Note: old NotifyUtils had no uuid(); RandomUtils strips hyphens from UUID.randomUUID()
        String jdk = UUID.randomUUID().toString().replace("-", "");
        Assert.assertEquals(32, jdk.length());
        System.out.println("PASS uuid format 32 hex lowercase (RandomUtils.getUUID); no prior local uuid to compare equality");
    }

    @Test
    public void sha256Hex_file() throws Exception {
        File tmp = File.createTempFile("notify-sha", ".bin");
        try {
            byte[] content = ("sha256-compare-中文\n").getBytes(StandardCharsets.UTF_8);
            byte[] extra = new byte[]{0, (byte) 0xff};
            byte[] merged = new byte[content.length + extra.length];
            System.arraycopy(content, 0, merged, 0, content.length);
            System.arraycopy(extra, 0, merged, content.length, extra.length);
            content = merged;
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(content);
            fos.close();
            String old = oldSha256HexFile(tmp);
            String neu = NotifyUtils.sha256Hex(tmp);
            String core = EncryptUtils.sha256(tmp);
            assertEq("sha256Hex(File)", old, neu);
            assertEq("EncryptUtils.sha256", old, core);
            assertEq("sha256Hex(null)", oldDigestHex(new byte[0]), NotifyUtils.sha256Hex((File) null));
            System.out.println("PASS sha256Hex(File) old==NotifyUtils==EncryptUtils: " + old);
        } finally {
            tmp.delete();
        }
    }

    @Test
    public void hexLower_same() {
        byte[][] samples = new byte[][]{
                new byte[0],
                new byte[]{0},
                new byte[]{0x0a, 0x0b, (byte) 0xff, (byte) 0x80},
                "abc".getBytes(StandardCharsets.UTF_8),
        };
        for (byte[] s : samples) {
            assertEq("hex " + java.util.Arrays.toString(s), oldHexLower(s), ByteUtils.toHexStringLower(s));
        }
        // null: old would NPE; ByteUtils returns null
        Assert.assertNull(ByteUtils.toHexStringLower(null));
        System.out.println("PASS hex lower for non-null; DIFF null: old NPE vs ByteUtils null");
    }

    @Test
    public void blankEmpty_compare() {
        // StringUtils.isEmpty / defaultString match old null/"" semantics used in NotifyUtils.
        Assert.assertTrue(StringUtils.isEmpty(null));
        Assert.assertTrue(StringUtils.isEmpty(""));
        Assert.assertFalse(StringUtils.isEmpty(" "));
        Assert.assertEquals("", StringUtils.defaultString(null));
        Assert.assertEquals("x", StringUtils.defaultString("x"));

        // Core StringUtils.isBlank uses Character.isWhitespace — NOT always equal to String.trim().
        // U+00A0 NBSP: neither trim nor Character.isWhitespace treats as blank.
        // U+2000 EN QUAD: trim keeps it; Character.isWhitespace => isBlank true.
        // NotifyUtils.parseDataSize / mimeFromSuffix restored to trim()-based checks.
        Assert.assertFalse(oldIsBlankViaTrim("\u00A0"));
        Assert.assertFalse(StringUtils.isBlank("\u00A0"));
        Assert.assertFalse(oldIsBlankViaTrim("\u2000"));
        Assert.assertTrue(StringUtils.isBlank("\u2000"));
        System.out.println("NOTE core isBlank != trim for U+2000 (isBlank=true, trimBlank=false); U+00A0 same (both false)");

        // NotifyUtils sites restored to trim semantics: blank ASCII still rejected the same way
        for (String blank : new String[]{null, "", " ", "\t", "\n", "  "}) {
            try {
                NotifyUtils.parseDataSize(blank);
                Assert.fail("expected IAE for blank=" + escapeForLog(blank));
            } catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().contains("data size is required"));
            }
        }
        // exotic whitespace is NOT blank under restored trim gate → falls through to invalid size
        try {
            NotifyUtils.parseDataSize("\u2000");
            Assert.fail("expected invalid data size for U+2000");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("invalid data size")
                    || e.getMessage().contains("data size is required"));
            // With trim()-based gate, U+2000 is not blank → "invalid data size: ..."
            Assert.assertTrue("restored trim semantics: " + e.getMessage(),
                    e.getMessage().startsWith("invalid data size"));
        }
        System.out.println("PASS isEmpty/defaultString; parseDataSize uses trim blank (not StringUtils.isBlank)");
    }

    private static String codepoints(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("U+%04X", (int) s.charAt(i)));
        }
        return sb.toString();
    }

    @Test
    public void mapsAndCollections_same() {
        Map<String, Object> m1 = NotifyUtils.map();
        Map<String, Object> m2 = Maps.newLinkedHashMap();
        Assert.assertTrue(m1 instanceof LinkedHashMap);
        Assert.assertTrue(m2 instanceof LinkedHashMap);
        Assert.assertTrue(Collections.isEmpty((Map<?, ?>) null));
        Assert.assertTrue(Collections.isEmpty(new LinkedHashMap<String, Object>()));
        LinkedHashMap<String, Object> filled = new LinkedHashMap<String, Object>();
        filled.put("a", 1);
        Assert.assertFalse(Collections.isEmpty(filled));
        System.out.println("PASS Maps.newLinkedHashMap / Collections.isEmpty");
    }
}
