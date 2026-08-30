package com.alianga.jkit.http.encoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HTTP {@code Content-Encoding} / {@code Accept-Encoding} 编解码适配器。
 * 通过 {@link ContentEncodings#register(ContentEncodingCodec)} 扩展 zstd 等算法。
 */
public interface ContentEncodingCodec {
    /**
     * @return HTTP 编码名，如 {@code gzip}、{@code deflate}、{@code br}
     */
    String name();

    /**
     * 是否匹配响应头中的一个 token（忽略大小写，可含别名）。
     *
     * @param token 单个编码名，不含 q 值
     * @return 匹配时为 true
     */
    boolean matches(String token);

    /**
     * @return 是否可解码响应
     */
    boolean canDecode();

    /**
     * @return 是否可编码请求/正文
     */
    boolean canEncode();

    /**
     * 包装输入流进行解码。
     *
     * @param in 压缩流
     * @return 解码流
     * @throws IOException 解码器初始化失败
     */
    InputStream decode(InputStream in) throws IOException;

    /**
     * 包装输出流进行编码。
     *
     * @param out 原始输出
     * @return 编码流
     * @throws IOException 编码器初始化失败
     */
    OutputStream encode(OutputStream out) throws IOException;
}
