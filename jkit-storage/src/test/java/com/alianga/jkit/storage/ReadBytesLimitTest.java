package com.alianga.jkit.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link FileStorage#readBytes(String, long)} 上限保护：超限必须中止且不读完整文件进堆。
 *
 * @author 郑明亮
 */
public class ReadBytesLimitTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void cappedReadAllowsWithinLimit() throws Exception {
        FileStorage fs = FileStorages.of("local",
                StorageConfig.empty().set("basePath", folder.getRoot().getAbsolutePath()));
        byte[] data = "hello-limit".getBytes(StandardCharsets.UTF_8);
        fs.upload("a.txt", data);
        assertArrayEquals(data, fs.readBytes("a.txt", data.length));
        assertArrayEquals(data, fs.readBytes("a.txt", data.length + 10));
    }

    @Test
    public void cappedReadRejectsOverLimit() throws Exception {
        FileStorage fs = FileStorages.of("local",
                StorageConfig.empty().set("basePath", folder.getRoot().getAbsolutePath()));
        byte[] data = new byte[64 * 1024];
        Arrays.fill(data, (byte) 7);
        fs.upload("big.bin", data);
        try {
            fs.readBytes("big.bin", 1024);
            fail("应因超过上限抛冲突");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
    }

    @Test
    public void localCopyStreamsLargeFile() throws Exception {
        FileStorage fs = FileStorages.of("local",
                StorageConfig.empty().set("basePath", folder.getRoot().getAbsolutePath()));
        // 不在单元测试里造数百兆；用 4MB 验证 copy 路径可用，大文件 OOM 由 box memstress 覆盖
        byte[] data = new byte[4 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        fs.upload("src.bin", data);
        fs.copy("src.bin", "dst.bin");
        assertArrayEquals(data, fs.readBytes("dst.bin"));
        assertEquals(data.length, Files.size(folder.getRoot().toPath().resolve("dst.bin")));
    }
}
