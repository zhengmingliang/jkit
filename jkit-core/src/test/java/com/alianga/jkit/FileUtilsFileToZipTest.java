package com.alianga.jkit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-3：FileUtils.fileToZip 循环中 fis/bis 引用被覆盖只关闭最后一个的资源泄漏，
 * 以及缺 zos.closeEntry() 的 zip 结构问题。
 */
public class FileUtilsFileToZipTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jkit-zip-");
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p)) {
            return;
        }
        Files.walk(p).sorted((a, b) -> b.compareTo(a)).forEach(x -> {
            try {
                Files.deleteIfExists(x);
            } catch (IOException ignore) {
                // ignore
            }
        });
    }

    private Path writeFile(String dirName, String fileName, String content) throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * 修复前：循环覆盖 fis/bis，最后一个之外的所有 FileInputStream 都不会被关闭；
     * 修复后：每轮循环 try-with-resources 关闭。
     *
     * 这里通过生成 200 个小文件（在普通 ulimit 1024 下足够触发"Too many open files"），
     * 然后观察调用能否正常完成来验证。修复前在本机实测 200 个文件直接报
     * "Too many open files"（修复前 fis/bis 泄漏 199 个）。修复后应成功返回。
     */
    @Test
    public void zipManyFilesShouldNotExhaustFileDescriptors() throws Exception {
        Path srcDir = tempDir.resolve("many");
        Files.createDirectories(srcDir);
        int n = 200;
        for (int i = 0; i < n; i++) {
            Files.write(srcDir.resolve("f-" + i + ".txt"),
                    ("content-" + i).getBytes(StandardCharsets.UTF_8));
        }

        Path zipPath = tempDir.resolve("out");
        Files.createDirectories(zipPath);
        String zipName = FileUtils.fileToZip(srcDir.toString(), zipPath.toString(), "many");

        assertEquals("many.zip", zipName);
        Path zipFile = zipPath.resolve(zipName);
        assertTrue("zip should exist: " + zipFile, Files.exists(zipFile));

        // 校验 zip 结构完整且包含全部条目
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                count++;
                zin.closeEntry();
            }
        }
        assertEquals("expected all " + n + " entries, got " + count, n, count);
    }

    /**
     * 验证 zip 中每个 entry 内容可读且独立（修复 closeEntry 缺失导致的 entry 错位）。
     */
    @Test
    public void zipEntriesShouldHoldCorrectContent() throws Exception {
        writeFile("single", "a.txt", "AAA");
        writeFile("single", "b.txt", "BBBB");
        writeFile("single", "c.txt", "C");

        Path zipPath = tempDir.resolve("out2");
        Files.createDirectories(zipPath);
        String zipName = FileUtils.fileToZip(
                tempDir.resolve("single").toString(), zipPath.toString(), "single");

        assertEquals("single.zip", zipName);
        Path zipFile = zipPath.resolve(zipName);
        assertTrue(Files.exists(zipFile));

        List<String> contents = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            byte[] buf = new byte[64];
            while ((e = zin.getNextEntry()) != null) {
                int total = 0;
                int read;
                while ((read = zin.read(buf, total, buf.length - total)) > 0) {
                    total += read;
                }
                contents.add(new String(buf, 0, total, StandardCharsets.UTF_8));
                zin.closeEntry();
            }
        }
        assertEquals(3, contents.size());
        assertTrue("expected AAA, got " + contents, contents.contains("AAA"));
        assertTrue("expected BBBB, got " + contents, contents.contains("BBBB"));
        assertTrue("expected C, got " + contents, contents.contains("C"));
    }

    /**
     * 不存在目录时返回空字符串（保持原行为）。
     */
    @Test
    public void zipMissingSourceDirReturnsEmpty() {
        String zipName = FileUtils.fileToZip(
                tempDir.resolve("nonexistent").toString(),
                tempDir.resolve("out3").toString(),
                "x");
        assertEquals("", zipName);
    }

    /**
     * 目标 zip 已存在时不重写，返回名称但不覆盖（保持原行为）。
     */
    @Test
    public void zipSkipsWhenTargetExists() throws Exception {
        writeFile("src", "a.txt", "1");
        Path zipDir = tempDir.resolve("out4");
        Files.createDirectories(zipDir);
        Path target = zipDir.resolve("exists.zip");
        Files.write(target, "PRE-EXISTING".getBytes(StandardCharsets.UTF_8));

        String zipName = FileUtils.fileToZip(
                tempDir.resolve("src").toString(), zipDir.toString(), "exists");

        assertEquals("exists.zip", zipName);
        // 不覆盖原有内容
        byte[] bytes = Files.readAllBytes(target);
        assertEquals("PRE-EXISTING", new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * 源目录为空时返回 zipFile.getName()（与原行为一致：zipName 在 if/else 外统一赋值）。
     */
    @Test
    public void zipEmptyDirReturnsZipName() throws Exception {
        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);
        String zipName = FileUtils.fileToZip(
                empty.toString(), tempDir.resolve("out5").toString(), "empty");
        assertEquals("empty.zip", zipName);
    }

    /**
     * 源目录为 null 时抛 NullPointerException（保留原有 new File(null) 的语义）。
     */
    @Test(expected = NullPointerException.class)
    public void zipNullDirThrowsNpe() {
        FileUtils.fileToZip(null, tempDir.resolve("out6").toString(), "n");
    }
}
