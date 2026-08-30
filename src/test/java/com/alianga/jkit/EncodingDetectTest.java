package com.alianga.jkit;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EncodingDetectTest {
    private static final String CN = "中文编码检测：你好，世界！文件读写与配置项。";
    private static final String TW = "這是一段繁體中文測試，包含臺灣香港常用字：的了是不我們。";
    private static final String JP = "こんにちは、世界。日本語のShift_JISとEUC-JPの判定テストです。";
    private static final String KR = "안녕하세요. 한국어 EUC-KR 인코딩 검출 테스트입니다.";
    private static final String ASCII = "hello encoding detect 123";

    @Test
    public void testAsciiAndEmpty() {
        assertEquals(StandardCharsets.US_ASCII, EncodingDetect.detect(new byte[0]));
        assertEquals(StandardCharsets.US_ASCII, EncodingDetect.detect((byte[]) null));
        assertTrue(EncodingDetect.nameEquals(EncodingDetect.detect(ASCII.getBytes(StandardCharsets.US_ASCII)),
                "US-ASCII"));
        assertEquals("UTF-8", EncodingDetect.getJavaEncode(null));
    }

    @Test
    public void testUtf8ChineseNotGbk() {
        byte[] utf8 = CN.getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, EncodingDetect.detect(utf8));
        assertEquals(CN, EncodingDetect.decode(utf8));
        assertTrue(EncodingDetect.isUtf8(utf8, utf8.length));
    }

    @Test
    public void testGbkWhenInvalidUtf8() {
        Charset gbk = Charset.forName("GBK");
        byte[] bytes = CN.getBytes(gbk);
        assertTrue("GBK sample should not be valid UTF-8", !EncodingDetect.isUtf8(bytes, bytes.length));
        Charset detected = EncodingDetect.detect(bytes);
        assertTrue("expected GBK, got " + detected, EncodingDetect.nameEquals(detected, "GBK"));
        assertEquals(CN, EncodingDetect.decode(bytes));
    }

    @Test
    public void testBig5() {
        Charset big5 = Charset.forName("Big5");
        byte[] bytes = TW.getBytes(big5);
        assertTrue(!EncodingDetect.isUtf8(bytes, bytes.length));
        Charset detected = EncodingDetect.detect(bytes);
        assertTrue("expected Big5, got " + detected, EncodingDetect.nameEquals(detected, "Big5"));
        assertEquals(TW, new String(bytes, EncodingDetect.detect(bytes)));
    }

    @Test
    public void testShiftJis() {
        Charset sjis = Charset.forName("Shift_JIS");
        byte[] bytes = JP.getBytes(sjis);
        Charset detected = EncodingDetect.detect(bytes);
        assertTrue("expected Shift_JIS, got " + detected, EncodingDetect.nameEquals(detected, "Shift_JIS"));
        assertEquals(JP, EncodingDetect.decode(bytes));
    }

    @Test
    public void testEucKr() {
        Charset eucKr = Charset.forName("EUC-KR");
        byte[] bytes = KR.getBytes(eucKr);
        assertTrue(EncodingDetect.nameEquals(EncodingDetect.detect(bytes), "EUC-KR"));
        assertEquals(KR, EncodingDetect.decode(bytes));
    }

    @Test
    public void testEucJp() {
        Charset eucJp = Charset.forName("EUC-JP");
        byte[] bytes = JP.getBytes(eucJp);
        Charset detected = EncodingDetect.detect(bytes);
        assertTrue("expected EUC-JP, got " + detected, EncodingDetect.nameEquals(detected, "EUC-JP"));
        assertEquals(JP, new String(bytes, detected));
    }

    @Test
    public void testBomUtf8() {
        byte[] text = CN.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[3 + text.length];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bom, 3, text.length);
        assertEquals(StandardCharsets.UTF_8, EncodingDetect.detect(bom));
        assertEquals(CN, EncodingDetect.decode(bom));
    }

    @Test
    public void testBomUtf16() {
        byte[] le = CN.getBytes(StandardCharsets.UTF_16LE);
        byte[] be = CN.getBytes(StandardCharsets.UTF_16BE);
        byte[] leBom = new byte[2 + le.length];
        leBom[0] = (byte) 0xFF;
        leBom[1] = (byte) 0xFE;
        System.arraycopy(le, 0, leBom, 2, le.length);
        byte[] beBom = new byte[2 + be.length];
        beBom[0] = (byte) 0xFE;
        beBom[1] = (byte) 0xFF;
        System.arraycopy(be, 0, beBom, 2, be.length);
        assertEquals(StandardCharsets.UTF_16LE, EncodingDetect.detect(leBom));
        assertEquals(StandardCharsets.UTF_16BE, EncodingDetect.detect(beBom));
        assertEquals(CN, EncodingDetect.decode(leBom));
        assertEquals(CN, EncodingDetect.decode(beBom));
    }

    @Test
    public void testBomUtf32() {
        Charset utf32be = Charset.forName("UTF-32BE");
        byte[] payload = CN.getBytes(utf32be);
        byte[] bom = new byte[4 + payload.length];
        bom[0] = 0x00;
        bom[1] = 0x00;
        bom[2] = (byte) 0xFE;
        bom[3] = (byte) 0xFF;
        System.arraycopy(payload, 0, bom, 4, payload.length);
        assertTrue(EncodingDetect.nameEquals(EncodingDetect.detect(bom), "UTF-32BE"));
    }

    @Test
    public void testUtf16LeWithoutBom() {
        String ascii = "Hello UTF16LE text for detection!!";
        byte[] data = ascii.getBytes(StandardCharsets.UTF_16LE);
        assertEquals(StandardCharsets.UTF_16LE, EncodingDetect.detect(data));
    }

    @Test
    public void testIso88591() {
        byte[] latin = "cafe\u00e9 au lait".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(!EncodingDetect.isUtf8(latin, latin.length));
        Charset detected = EncodingDetect.detect(latin);
        assertTrue(detected.equals(StandardCharsets.ISO_8859_1)
                || EncodingDetect.nameEquals(detected, "windows-1252"));
    }

    @Test
    public void testWindows1252() {
        byte[] data = "price: 100\u20AC".getBytes(Charset.forName("windows-1252"));
        Charset detected = EncodingDetect.detect(data);
        assertTrue(EncodingDetect.nameEquals(detected, "windows-1252")
                || detected.equals(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void testXmlDeclarationGbk() throws Exception {
        Charset gbk = Charset.forName("GBK");
        String xml = "<?xml version=\"1.0\" encoding=\"GBK\"?><a>hello</a>";
        byte[] bytes = xml.getBytes(StandardCharsets.US_ASCII);
        assertTrue(EncodingDetect.nameEquals(EncodingDetect.detect(bytes), "GBK"));
        File file = File.createTempFile("jkit-enc-", ".xml");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes);
        assertTrue(EncodingDetect.nameEquals(EncodingDetect.detect(file), "GBK"));
        assertEquals("GBK", EncodingDetect.getJavaEncode(file.getAbsolutePath()));
    }

    @Test
    public void testHtmlMetaUtf8() {
        String html = "<meta charset=\"UTF-8\"><p>abc</p>";
        assertEquals(StandardCharsets.UTF_8,
                EncodingDetect.detect(html.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testIso2022Jp() {
        Charset iso = Charset.forName("ISO-2022-JP");
        byte[] bytes = JP.getBytes(iso);
        assertTrue(EncodingDetect.nameEquals(EncodingDetect.detect(bytes), "ISO-2022-JP"));
        assertEquals(JP, new String(bytes, iso));
    }

    @Test
    public void testFileAndStreamAndCompatApi() throws Exception {
        File file = File.createTempFile("jkit-enc-", ".txt");
        file.deleteOnExit();
        Files.write(file.toPath(), CN.getBytes(StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, EncodingDetect.detect(file));
        assertEquals("UTF-8", EncodingDetect.getJavaEncode(file.getAbsolutePath()));
        assertEquals("UTF-8", EncodingDetect.getUrlEncode(CN.getBytes(StandardCharsets.UTF_8)));
        assertEquals(StandardCharsets.UTF_8,
                EncodingDetect.detect(new ByteArrayInputStream(CN.getBytes(StandardCharsets.UTF_8))));
        assertEquals(CN.trim(), EncodingDetect.decode(file).trim());
        String viaFileUtils = FileUtils.readTxtFile(file, null);
        assertTrue(viaFileUtils.contains("你好"));
    }

    @Test
    public void testMissingFile() throws Exception {
        assertEquals("UTF-8", EncodingDetect.getJavaEncode("/no/such/jkit-encoding-file.txt"));
        assertEquals(StandardCharsets.UTF_8, EncodingDetect.detect((File) null));
    }

    @Test
    public void testInvalidUtf8Rejected() {
        byte[] bad = new byte[] {(byte) 0xC0, (byte) 0x80};
        assertTrue(!EncodingDetect.isUtf8(bad, bad.length));
    }

    @Test
    public void testGbkRoundTripFileUtils() throws Exception {
        Charset gbk = Charset.forName("GBK");
        File file = File.createTempFile("jkit-gbk-", ".txt");
        file.deleteOnExit();
        Files.write(file.toPath(), CN.getBytes(gbk));
        String content = FileUtils.readTxtFile(file, null);
        assertTrue(content.contains("你好"));
        assertTrue(content.contains("世界"));
    }
}
