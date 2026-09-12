package com.alianga.jkit.http.encoding;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code gzip} / {@code x-gzip} 编解码。
 */
public final class GzipContentEncoding extends AbstractContentEncodingCodec {
    /**
     * 创建 gzip 编解码器，注册 {@code gzip} 与 {@code x-gzip} 两个编码名称。
     */
    public GzipContentEncoding() {
        super("gzip", "x-gzip");
    }

    @Override
    public boolean canEncode() {
        return true;
    }

    @Override
    public InputStream decode(InputStream in) throws IOException {
        // 声明 gzip 但正文为空（如服务端声明 Content-Encoding: gzip 却未发送任何字节）时，
        // JDK 8 的 GZIPInputStream 会在构造/首次读取头部时抛 EOFException，而 JDK 9+ 已能优雅返回 -1。
        // 这里用单字节回推判断：首字节即 EOF 视为空正文，直接返回 close 时仍能关闭底层流的空流，
        // 既消除 JDK 8 的异常，又保证 keep-alive 连接正常回收。
        PushbackInputStream pb = new PushbackInputStream(in, 1);
        int first = pb.read();
        if (first < 0) {
            return new EmptyDecodedStream(pb);
        }
        pb.unread(first);
        return new GZIPInputStream(pb);
    }

    @Override
    public OutputStream encode(OutputStream out) throws IOException {
        return new GZIPOutputStream(out);
    }

    /**
     * 空正文占位流：read 始终返回 -1，close 时关闭底层流（如连接输入流）以便 keep-alive 回收。
     */
    private static final class EmptyDecodedStream extends InputStream {
        private final Closeable delegate;
        private boolean closed;

        EmptyDecodedStream(Closeable delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return -1;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                delegate.close();
            }
        }
    }
}
