package com.alianga.jkit.http.encoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * {@code deflate} 编解码。
 */
public final class DeflateContentEncoding extends AbstractContentEncodingCodec {
    /**
     * 构造编码名称为 {@code deflate} 的编解码实例。
     */
    public DeflateContentEncoding() {
        super("deflate");
    }

    @Override
    public boolean canEncode() {
        return true;
    }

    @Override
    public InputStream decode(InputStream in) throws IOException {
        return new InflaterInputStream(in);
    }

    @Override
    public OutputStream encode(OutputStream out) throws IOException {
        return new DeflaterOutputStream(out);
    }
}
