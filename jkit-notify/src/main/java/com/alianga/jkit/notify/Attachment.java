package com.alianga.jkit.notify;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 邮件附件。内存附件在构造时拷贝；文件附件只保留路径，发送时按块读取。
 *
 * <p>未指定 Content-Type 时按文件名后缀与文件头自动识别（复用 jkit-core {@code FileType}）。
 * 超大附件可配合 {@link ChannelConfig#autoSplit(boolean)} 按块拆成多封邮件发送。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class Attachment {
    private final String filename;
    private final String contentType;
    private final byte[] memory;
    private final File file;
    private final long offset;
    private final long length;

    private Attachment(String filename, String contentType, byte[] memory, File file, long offset, long length) {
        this.filename = filename;
        this.contentType = contentType;
        this.memory = memory;
        this.file = file;
        this.offset = offset;
        this.length = length;
    }

    /**
     * 从本地文件构造附件：不把整文件读进内存，发送时流式读取。MIME 按文件名识别。
     *
     * @param file 文件
     * @return 附件
     */
    public static Attachment of(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file is required");
        }
        String name = file.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "attachment.bin";
        }
        return new Attachment(name, NotifyUtils.detectMimeType(name, null), null, file, 0L, file.length());
    }

    /**
     * 构造内存附件，按文件名与内容自动识别 MIME。
     *
     * @param filename 文件名
     * @param content 文件内容，会被拷贝
     * @return 附件
     */
    public static Attachment of(String filename, byte[] content) {
        String name = filename == null || filename.trim().isEmpty() ? "attachment.bin" : filename.trim();
        byte[] data = content == null ? new byte[0] : content.clone();
        return new Attachment(name, NotifyUtils.detectMimeType(name, data), data, null, 0L, data.length);
    }

    /**
     * 构造内存附件。{@code contentType} 为空时自动识别。
     *
     * @param filename 文件名，空时用 {@code attachment.bin}
     * @param content 文件内容，{@code null} 视为空数组；传入后拷贝
     * @param contentType MIME 类型，空时自动识别
     * @return 附件
     */
    public static Attachment of(String filename, byte[] content, String contentType) {
        String name = filename == null || filename.trim().isEmpty() ? "attachment.bin" : filename.trim();
        byte[] data = content == null ? new byte[0] : content.clone();
        String type = contentType == null || contentType.trim().isEmpty()
                ? NotifyUtils.detectMimeType(name, data) : contentType.trim();
        return new Attachment(name, type, data, null, 0L, data.length);
    }

    /**
     * 切出一段（拆包用）。文件附件仍指向原文件，不拷贝字节。
     *
     * @param from 起始偏移（相对本附件）
     * @param size 长度
     * @param partFilename 分片文件名
     * @return 切片附件
     */
    public Attachment slice(long from, long size, String partFilename) {
        long start = offset + Math.max(0L, from);
        long max = Math.max(0L, length - Math.max(0L, from));
        long len = Math.min(Math.max(0L, size), max);
        String name = partFilename == null || partFilename.trim().isEmpty() ? filename : partFilename.trim();
        if (memory != null) {
            int begin = (int) Math.min(start, memory.length);
            int end = (int) Math.min(start + len, memory.length);
            byte[] part = new byte[end - begin];
            System.arraycopy(memory, begin, part, 0, part.length);
            return new Attachment(name, contentType, part, null, 0L, part.length);
        }
        return new Attachment(name, contentType, null, file, start, len);
    }

    /**
     * 打开只读流，调用方负责关闭。
     *
     * @return 输入流
     * @throws IOException 打开失败
     */
    public InputStream openStream() throws IOException {
        if (memory != null) {
            return new ByteArrayInputStream(memory);
        }
        FileInputStream in = new FileInputStream(file);
        NotifyUtils.skipFully(in, offset);
        return new BoundedInputStream(in, length);
    }

    /**
     * @return 文件名
     */
    public String filename() {
        return filename;
    }

    /**
     * @return 内容副本；文件附件会按切片读取
     */
    public byte[] content() {
        return contentBytes().clone();
    }

    /**
     * 内存附件返回内部数组；文件附件读取切片。拆包路径请用 {@link #openStream()}。
     *
     * @return 字节
     */
    public byte[] contentBytes() {
        if (memory != null) {
            return memory;
        }
        try {
            InputStream in = openStream();
            try {
                byte[] data = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
                int filled = 0;
                while (filled < data.length) {
                    int read = in.read(data, filled, data.length - filled);
                    if (read < 0) {
                        break;
                    }
                    filled += read;
                }
                if (filled == data.length) {
                    return data;
                }
                byte[] slim = new byte[filled];
                System.arraycopy(data, 0, slim, 0, filled);
                return slim;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("read attachment failed: " + filename, e);
        }
    }

    /**
     * @return MIME 类型
     */
    public String contentType() {
        return contentType;
    }

    /**
     * @return 字节数
     */
    public long size() {
        return length;
    }

    /**
     * SHA-256。文件附件流式计算，不把整文件载入内存。
     *
     * @return hex
     */
    public String sha256Hex() {
        if (memory != null) {
            return NotifyUtils.sha256Hex(memory);
        }
        return NotifyUtils.sha256Hex(file, offset, length);
    }

    /**
     * @return 是否文件支撑（流式）
     */
    public boolean fileBacked() {
        return file != null;
    }

    /**
     * @return 文件名与大小的调试描述
     */
    @Override
    public String toString() {
        return "Attachment{filename=" + filename + ", size=" + length + ", type=" + contentType + "}";
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remain;

        private BoundedInputStream(InputStream in, long remain) {
            super(in);
            this.remain = remain;
        }

        @Override
        public int read() throws IOException {
            if (remain <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remain--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remain <= 0) {
                return -1;
            }
            int want = (int) Math.min(len, remain);
            int read = super.read(buffer, off, want);
            if (read > 0) {
                remain -= read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remain));
            remain -= skipped;
            return skipped;
        }
    }
}
