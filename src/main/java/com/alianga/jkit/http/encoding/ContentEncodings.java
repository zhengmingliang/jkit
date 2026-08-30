package com.alianga.jkit.http.encoding;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ContentEncodingCodec} 注册表。内置 gzip、deflate、br；未知编码保持原流。
 */
public final class ContentEncodings {
    private static final CopyOnWriteArrayList<ContentEncodingCodec> CODECS =
            new CopyOnWriteArrayList<ContentEncodingCodec>();

    static {
        register(new GzipContentEncoding());
        register(new DeflateContentEncoding());
        register(new BrotliContentEncoding());
    }

    private ContentEncodings() {
    }

    /**
     * 注册或替换同名适配器（后注册覆盖先注册）。
     *
     * @param codec 适配器
     */
    public static void register(ContentEncodingCodec codec) {
        if (codec == null || codec.name() == null) {
            throw new IllegalArgumentException("codec and name are required");
        }
        for (int i = 0; i < CODECS.size(); i++) {
            if (CODECS.get(i).name().equalsIgnoreCase(codec.name())) {
                CODECS.set(i, codec);
                return;
            }
        }
        CODECS.add(codec);
    }

    /**
     * @return 当前已注册适配器（只读）
     */
    public static List<ContentEncodingCodec> codecs() {
        return Collections.unmodifiableList(new ArrayList<ContentEncodingCodec>(CODECS));
    }

    /**
     * 查找能处理该 token 的适配器。
     *
     * @param token 单个编码名
     * @return 适配器，未找到为 {@code null}
     */
    public static ContentEncodingCodec lookup(String token) {
        if (token == null) {
            return null;
        }
        for (ContentEncodingCodec codec : CODECS) {
            if (codec.matches(token)) {
                return codec;
            }
        }
        return null;
    }

    /**
     * 作为 {@code Accept-Encoding} 发送的默认列表（仅 {@link ContentEncodingCodec#canDecode()}）。
     *
     * @return 如 {@code gzip, deflate, br}
     */
    public static String acceptEncodingHeader() {
        StringBuilder builder = new StringBuilder();
        for (ContentEncodingCodec codec : CODECS) {
            if (!codec.canDecode()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(codec.name());
        }
        return builder.length() == 0 ? "identity" : builder.toString();
    }

    /**
     * 去掉本库无法解码的算法，避免服务端返回无法处理的压缩正文。
     *
     * @param value 原始 {@code Accept-Encoding}
     * @return 可解码算法列表，空输入时返回 {@link #acceptEncodingHeader()}
     */
    public static String sanitizeAcceptEncoding(String value) {
        if (value == null || value.trim().isEmpty()) {
            return acceptEncodingHeader();
        }
        StringBuilder out = new StringBuilder();
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i].trim();
            int semicolon = token.indexOf(';');
            if (semicolon >= 0) {
                token = token.substring(0, semicolon).trim();
            }
            if (token.isEmpty()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if ("identity".equals(lower) || lookup(lower) != null) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(lower.equals("brotli") ? "br" : lower);
            }
        }
        return out.length() == 0 ? acceptEncodingHeader() : out.toString();
    }

    /**
     * 按 {@code Content-Encoding} 从右到左依次解码。无法识别的算法跳过，原流继续返回。
     *
     * @param in 原始流
     * @param contentEncoding 响应头，可为多值
     * @return 解码流；无适配器时为原流
     * @throws IOException 已知算法解码失败
     */
    public static InputStream decode(InputStream in, String contentEncoding) throws IOException {
        if (in == null || contentEncoding == null || contentEncoding.trim().isEmpty()) {
            return in;
        }
        String[] parts = contentEncoding.split(",");
        InputStream current = in;
        for (int i = parts.length - 1; i >= 0; i--) {
            String token = parts[i].trim();
            int semicolon = token.indexOf(';');
            if (semicolon >= 0) {
                token = token.substring(0, semicolon).trim();
            }
            if (token.isEmpty() || "identity".equalsIgnoreCase(token)) {
                continue;
            }
            ContentEncodingCodec codec = lookup(token);
            if (codec == null || !codec.canDecode()) {
                continue;
            }
            current = codec.decode(current);
        }
        return current;
    }
}
