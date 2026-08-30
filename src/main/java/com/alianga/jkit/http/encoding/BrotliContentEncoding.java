package com.alianga.jkit.http.encoding;

import com.alianga.jkit.http.encoding.brotli.BrotliInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@code br} / Brotli 解码（源码移植自 Google {@code org.brotli:dec:0.1.2}，MIT）。
 * 该版本仅含解码器，编码请自行 {@link ContentEncodings#register} 扩展。
 */
public final class BrotliContentEncoding extends AbstractContentEncodingCodec {
    /**
     * 构造 Brotli 编码实现，注册 {@code br} 及别名 {@code brotli}。
     */
    public BrotliContentEncoding() {
        super("br", "brotli");
    }

    @Override
    public InputStream decode(InputStream in) throws IOException {
        return new BrotliInputStream(in);
    }

    @Override
    public OutputStream encode(OutputStream out) {
        throw new UnsupportedOperationException("brotli encode is not bundled; register a custom codec");
    }
}
