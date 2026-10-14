package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StorageException;

import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SpillBuffer：超限转写临时文件；转写前按预计大小检查剩余空间。
 *
 * @author 郑明亮
 */
public class SpillBufferTest {

    @Test
    public void spillsBeyondLimitAndReadsBack() throws Exception {
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        try (SpillBuffer buffer = new SpillBuffer(1024, "x")) {
            buffer.write(data, 0, 512);
            assertFalse(buffer.spilled());
            buffer.write(data, 512, data.length - 512);
            assertTrue(buffer.spilled());
            try (InputStream in = buffer.openInput()) {
                assertArrayEquals(data, in.readAllBytes());
            }
        }
    }

    @Test
    public void spillRejectedWhenExpectedSizeExceedsFreeSpace() throws Exception {
        try (SpillBuffer buffer = new SpillBuffer(16, "huge.bin").expectSize(Long.MAX_VALUE / 4)) {
            buffer.write(new byte[64], 0, 64);
            fail("预计大小远超剩余空间，应拒绝转写");
        } catch (StorageException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("空间不足"));
        }
    }
}
