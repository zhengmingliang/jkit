package com.alianga.jkit.http.encoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        return new GZIPInputStream(in);
    }

    @Override
    public OutputStream encode(OutputStream out) throws IOException {
        return new GZIPOutputStream(out);
    }
}
