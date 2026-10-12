package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Range 续传：本地引擎从偏移续读，与全量内容拼接一致。
 *
 * @author 郑明亮
 */
public class RangeDownloadTest {

    @Test
    public void localSupportsRangeAndResumes() throws Exception {
        Path dir = Files.createTempDirectory("range-test");
        byte[] content = new byte[10_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Files.write(dir.resolve("big.bin"), content);
        FileStorage storage = FileStorages.of("local",
                StorageConfig.empty().set("basePath", dir.toString()));
        assertTrue(storage.supportsRange());
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        storage.download("big.bin", 4_000, head);
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        storage.download("big.bin", 4_000L, tail);
        assertEquals(6_000, tail.size());
        byte[] expected = new byte[6_000];
        System.arraycopy(content, 4_000, expected, 0, 6_000);
        assertArrayEquals(expected, tail.toByteArray());
    }

    @Test
    public void zeroOffsetFallsBackToFullDownload() throws Exception {
        Path dir = Files.createTempDirectory("range-test");
        Files.writeString(dir.resolve("a.txt"), "hello range");
        FileStorage storage = FileStorages.of("local",
                StorageConfig.empty().set("basePath", dir.toString()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        storage.download("a.txt", 0L, out);
        assertEquals("hello range", out.toString());
    }
}
