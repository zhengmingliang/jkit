package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StorageException;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 写入超过上限即抛冲突异常的输出流：大小未知的对象做有界读取时用，
 * 引擎看到运行时异常会作废会话（FTP/SFTP），不会把脏连接放回池里。
 *
 * @author 郑明亮
 */
public final class BoundedOutputStream extends FilterOutputStream {
    private final long max;
    private final String path;
    private long written;

    /**
     * @param out  目标流
     * @param max  允许写入的最大字节数
     * @param path 出错文案里的对象路径
     */
    public BoundedOutputStream(OutputStream out, long max, String path) {
        super(out);
        this.max = max;
        this.path = path;
    }

    @Override
    public void write(int b) throws IOException {
        check(1);
        out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        check(len);
        out.write(b, off, len);
    }

    private void check(int len) {
        if (written + len > max) {
            throw StorageException.conflict(
                    "对象超过允许读取上限 " + max + " 字节：" + path, path);
        }
        written += len;
    }
}
