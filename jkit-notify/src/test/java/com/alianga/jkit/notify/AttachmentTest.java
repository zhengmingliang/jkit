package com.alianga.jkit.notify;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Attachment#contentBytes()} 文件支撑路径：按块读取、超大附件快速失败。
 */
public class AttachmentTest {

    @Test
    public void fileBackedContentBytesMatchesSlice() throws Exception {
        byte[] data = new byte[100000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        File file = File.createTempFile("jkit-attach", ".bin");
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(data);
            out.close();
            Attachment attachment = Attachment.of(file);
            assertTrue(attachment.contentBytes() != data);
            assertTrue(Arrays.equals(data, attachment.contentBytes()));

            Attachment slice = attachment.slice(1000, 5000, "part.bin");
            byte[] expected = Arrays.copyOfRange(data, 1000, 6000);
            assertTrue(Arrays.equals(expected, slice.contentBytes()));
            assertEquals(5000, slice.size());
        } finally {
            file.delete();
        }
    }

    @Test
    public void oversizedFileFailsFastInsteadOfOom() throws Exception {
        // 稀疏文件：length 报 3GB 但不占磁盘，contentBytes 应立即拒绝而不是先分配 3GB
        File file = File.createTempFile("jkit-huge", ".bin");
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.setLength(3L * 1024 * 1024 * 1024);
            raf.close();
            Attachment attachment = Attachment.of(file);
            try {
                attachment.contentBytes();
                fail("expected IllegalStateException");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("openStream"));
            }
        } finally {
            file.delete();
        }
    }

    @Test
    public void memoryAttachmentReturnsIndependentContent() {
        byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
        Attachment attachment = Attachment.of("a.txt", raw);
        raw[0] = 'X';
        assertEquals('h', attachment.content()[0]);
    }
}
