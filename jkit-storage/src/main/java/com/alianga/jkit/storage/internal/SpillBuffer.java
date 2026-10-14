package com.alianga.jkit.storage.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * 有上限的内存缓冲：写入不超过 {@code memoryLimit} 时留在堆里，超过即把已缓冲内容
 * 连同后续数据转写到临时文件。用于「stat 大小未知 / 不可信」的中转，保证堆占用有界。
 * 用完必须 {@link #close()} 以删除临时文件。
 *
 * @author 郑明亮
 */
public final class SpillBuffer extends OutputStream {
    private final long memoryLimit;
    private final String path;
    private ByteArrayOutputStream memory;
    private File file;
    private OutputStream fileOut;
    private long size;

    /**
     * @param memoryLimit 允许留在堆里的最大字节数；0 表示直接落盘
     * @param path        关联对象路径（异常信息用）
     */
    public SpillBuffer(long memoryLimit, String path) {
        this.memoryLimit = Math.max(0L, memoryLimit);
        this.path = path;
        this.memory = new ByteArrayOutputStream((int) Math.min(Math.max(this.memoryLimit, 64L), 64L * 1024L));
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (file == null && size + len > memoryLimit) {
            spill();
        }
        if (file != null) {
            fileOut.write(b, off, len);
        } else {
            memory.write(b, off, len);
        }
        size += len;
    }

    private void spill() throws IOException {
        file = TempFiles.create("jkit-storage-spill", -1L, path);
        fileOut = new java.io.BufferedOutputStream(Files.newOutputStream(file.toPath()), 64 * 1024);
        memory.writeTo(fileOut);
        memory = null;
    }

    /** @return 是否已转写到临时文件 */
    public boolean spilled() {
        return file != null;
    }

    /** @return 已写入字节数 */
    public long size() {
        return size;
    }

    /**
     * 写入完成后打开读取流（可多次调用）。
     *
     * @return 输入流
     * @throws IOException 读临时文件失败
     */
    public InputStream openInput() throws IOException {
        if (file == null) {
            return new ByteArrayInputStream(memory.toByteArray());
        }
        fileOut.flush();
        return Files.newInputStream(file.toPath());
    }

    @Override
    public void close() {
        if (fileOut != null) {
            try {
                fileOut.close();
            } catch (IOException ignore) {
                // 关闭失败不影响删除
            }
        }
        TempFiles.deleteQuietly(file);
    }
}
