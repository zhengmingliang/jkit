package com.alianga.jkit.storage.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 临时文件剩余空间要求随大小缩放：小文件在小 tmpfs 上仍可中转。
 *
 * @author 郑明亮
 */
public class TempFilesTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    public void unknownSizeNeedsOnlySmallReserve() {
        assertEquals(4 * MIB, TempFiles.requiredBytes(-1L));
        assertEquals(4 * MIB, TempFiles.requiredBytes(0L));
    }

    @Test
    public void marginScalesWithSize() {
        assertEquals("小文件余量取下限 4MiB", MIB + 4 * MIB, TempFiles.requiredBytes(MIB));
        assertEquals("100MiB 余量 10MiB", 110 * MIB, TempFiles.requiredBytes(100 * MIB));
        assertEquals("大文件余量封顶 64MiB", 10240 * MIB + 64 * MIB, TempFiles.requiredBytes(10240 * MIB));
        assertEquals(Long.MAX_VALUE, TempFiles.requiredBytes(Long.MAX_VALUE));
    }
}
