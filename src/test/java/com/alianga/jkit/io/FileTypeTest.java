package com.alianga.jkit.io;

import com.alianga.jkit.Base64Utils;
import com.alianga.jkit.EncryptUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileTypeTest {

    /** PNG 文件签名 */
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    public void mimeResourcesArePresentOnClasspath() throws IOException {
        assertResourceLoadable("/filetype.properties");
        assertResourceLoadable("/mimetype.properties");
    }

    private void assertResourceLoadable(String resource) throws IOException {
        InputStream in = FileType.class.getResourceAsStream(resource);
        assertNotNull("资源缺失: " + resource, in);
        try {
            Properties properties = new Properties();
            properties.load(in);
            assertFalse("资源内容为空: " + resource, properties.isEmpty());
        } finally {
            in.close();
        }
    }

    @Test
    public void classInitializesAndFileHeaderTableIsFilled() {
        assertFalse(FileType.FILE_TYPE_MAP.isEmpty());
        assertEquals("png", FileType.FILE_TYPE_MAP.get("89504e470d0a1a0a0000"));
        assertEquals("jpg", FileType.FILE_TYPE_MAP.get("ffd8ffe000104a464946"));
        assertEquals("gif", FileType.FILE_TYPE_MAP.get("47494638396126026f01"));
    }

    @Test
    public void getSuffixByMimeType_resolvesCommonTypes() {
        assertEquals("png", FileType.getSuffixByMimeType("image/png"));
        assertEquals("jpeg", FileType.getSuffixByMimeType("image/jpeg"));
        assertEquals("jpg", FileType.getSuffixByMimeType("image/jpg"));
        assertEquals("gif", FileType.getSuffixByMimeType("image/gif"));
        assertEquals("bmp", FileType.getSuffixByMimeType("image/bmp"));
        assertEquals("svg", FileType.getSuffixByMimeType("image/svg+xml"));
        assertEquals("webp", FileType.getSuffixByMimeType("image/webp"));
        assertEquals("psd", FileType.getSuffixByMimeType("image/vnd.adobe.photoshop"));
        assertEquals("dwg", FileType.getSuffixByMimeType("image/vnd.dwg"));
    }

    @Test
    public void getSuffixByMimeType_neverStartsWithDot() {
        String[] mimeTypes = {"image/jpg", "image/bmp", "image/gif", "image/png", "image/x-png",
                "image/vnd.mozilla.apng", "image/jpeg", "image/vnd.wap.wbmp", "image/x-icon",
                "image/svg+xml", "image/webp", "application/x-photoshop",
                "image/vnd.adobe.photoshop", "image/psd", "image/tiff", "image/vnd.dwg"};
        for (String mimeType : mimeTypes) {
            String suffix = FileType.getSuffixByMimeType(mimeType);
            assertFalse(mimeType + " 的后缀不应为空", suffix.isEmpty());
            assertFalse(mimeType + " 的后缀不应带前导点: " + suffix, suffix.startsWith("."));
        }
    }

    @Test
    public void getSuffixByMimeType_unknownReturnsEmptyString() {
        assertEquals("", FileType.getSuffixByMimeType("application/x-not-exists"));
        assertEquals("", FileType.getSuffixByMimeType(""));
    }

    @Test
    public void getMimeTypeBySuffix_resolvesCommonSuffixes() {
        assertEquals("image/png", FileType.getMimeTypeBySuffix("png"));
        assertEquals("image/jpeg", FileType.getMimeTypeBySuffix("jpg"));
        assertEquals("image/gif", FileType.getMimeTypeBySuffix("gif"));
        assertEquals("image/webp", FileType.getMimeTypeBySuffix("webp"));
        assertEquals("image/vnd.dwg", FileType.getMimeTypeBySuffix("dwg"));
    }

    @Test
    public void getMimeTypeBySuffix_heicAndHeifNotSwapped() {
        assertEquals("image/heic", FileType.getMimeTypeBySuffix("heic"));
        assertEquals("image/heif", FileType.getMimeTypeBySuffix("heif"));
    }

    @Test
    public void getMimeTypeBySuffix_unknownReturnsEmptyString() {
        assertEquals("", FileType.getMimeTypeBySuffix("not-a-suffix"));
    }

    @Test
    public void mimeTypeAndSuffixRoundTrip() {
        String[] suffixes = {"png", "gif", "bmp", "tiff", "webp", "dwg", "psd"};
        for (String suffix : suffixes) {
            String mimeType = FileType.getMimeTypeBySuffix(suffix);
            assertFalse(suffix + " 应能查到 mimeType", mimeType.isEmpty());
            assertEquals(suffix + " 往返不一致", suffix, FileType.getSuffixByMimeType(mimeType));
        }
    }

    @Test
    public void getFileType_detectsPngByMagicNumber() throws IOException {
        assertEquals("png", FileType.getFileType(new ByteArrayInputStream(PNG_MAGIC)));
    }

    @Test
    public void getFileType_detectsGifByMagicNumber() {
        byte[] gif = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        assertEquals("gif", FileType.getFileType(new ByteArrayInputStream(gif)));
    }

    @Test
    public void getFileType_unknownHeaderReturnsNull() {
        byte[] unknown = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        assertNull(FileType.getFileType(new ByteArrayInputStream(unknown)));
    }

    @Test
    public void getFileType_fromPathReturnsResult() throws IOException {
        File file = File.createTempFile("jkit-filetype-", ".bin");
        try {
            Files.write(file.toPath(),
                    new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A});
            assertEquals("png", FileType.getFileType(file.getAbsolutePath()));
        } finally {
            file.delete();
        }
    }

    @Test
    public void getFileType_missingFileReturnsNullWithoutThrowing() {
        assertNull(FileType.getFileType("/tmp/jkit-definitely-not-exists-" + System.nanoTime()));
    }

    @Test
    public void base64DecodeFile_usesSuffixFromMimeMapping() throws IOException {
        File dir = Files.createTempDirectory("jkit-filetype-").toFile();
        try {
            byte[] payload = {(byte) 0x89, 0x50, 0x4E, 0x47};
            String dataUri = "data:image/png;base64," + Base64Utils.encodeToString(payload);
            File decoded = EncryptUtils.base64DecodeFile(dataUri, dir.getAbsolutePath());
            assertNotNull(decoded);
            assertTrue("应以 .png 结尾: " + decoded.getName(), decoded.getName().endsWith(".png"));
            assertFalse("文件名不应出现双点: " + decoded.getName(), decoded.getName().contains(".."));
            assertArrayEquals(payload, Files.readAllBytes(decoded.toPath()));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void base64DecodeFile_webpHasNoDoubleDot() throws IOException {
        File dir = Files.createTempDirectory("jkit-filetype-webp-").toFile();
        try {
            String dataUri = "data:image/webp;base64,"
                    + Base64Utils.encodeToString("webp".getBytes(StandardCharsets.UTF_8));
            File decoded = EncryptUtils.base64DecodeFile(dataUri, dir.getAbsolutePath());
            assertNotNull(decoded);
            assertTrue("应以 .webp 结尾: " + decoded.getName(), decoded.getName().endsWith(".webp"));
            assertFalse("文件名不应出现双点: " + decoded.getName(), decoded.getName().contains(".."));
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        dir.delete();
    }
}
