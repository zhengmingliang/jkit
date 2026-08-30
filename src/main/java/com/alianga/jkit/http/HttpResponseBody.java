package com.alianga.jkit.http;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.json.JSONNode;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * HTTP 响应体，替代原 OkHttp {@code ResponseBody}。
 * <p>
 * 支持字符串/字节/JSON 解析、落盘与流转发；流式响应体只能消费一次，
 * 重复读取会抛出 {@link IllegalStateException}，缓冲响应体可重复读取。
 *
 * @author 郑明亮
 */
public class HttpResponseBody implements Closeable {
    private final byte[] bytes;
    private final InputStream stream;
    private final String contentType;
    private final long contentLength;
    private final Closeable resource;
    /** 流式响应体是否已被消费。volatile：响应体可能被跨线程传递（异步回调、SSE）。 */
    private volatile boolean consumed;

    private HttpResponseBody(byte[] bytes, InputStream stream, String contentType,
                             long contentLength, Closeable resource) {
        this.bytes = bytes;
        this.stream = stream;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.resource = resource;
    }

    /**
     * 已缓冲的响应体。
     *
     * @param bytes 正文
     * @param contentType Content-Type
     * @return 响应体
     */
    public static HttpResponseBody ofBytes(byte[] bytes, String contentType) {
        byte[] data = bytes == null ? new byte[0] : bytes;
        return new HttpResponseBody(data, null, contentType, data.length, null);
    }

    /**
     * 流式响应体，调用 {@link #close()} 时关闭底层连接。
     *
     * @param stream 输入流
     * @param contentType Content-Type
     * @param contentLength 声明长度，未知为 {@code -1}
     * @param resource 需要随响应体一起关闭的资源
     * @return 响应体
     */
    public static HttpResponseBody ofStream(InputStream stream, String contentType,
                                            long contentLength, Closeable resource) {
        return new HttpResponseBody(null, stream, contentType, contentLength, resource);
    }

    /**
     * 将正文解码为字符串（按 Content-Type 中的 charset，缺省 UTF-8）。
     *
     * @return 字符串正文
     * @throws IOException 读取失败
     */
    public String string() throws IOException {
        return string(null);
    }

    /**
     * 将正文解码为字符串。会剥离开头的 BOM（UTF-8 / UTF-16 LE/BE），
     * 否则字符串首字符会是不可见的 {@code \uFEFF}，与后续 JSON / 数字解析冲突。
     *
     * @param charset 指定字符集，{@code null} 时按 Content-Type 解析（缺省 UTF-8）
     * @return 字符串正文
     * @throws IOException 读取失败
     */
    public String string(Charset charset) throws IOException {
        byte[] data = bytes();
        Charset cs = charset == null ? charsetForBytes(data) : charset;
        int offset = bomLength(data, cs);
        return new String(data, offset, data.length - offset, cs.name());
    }

    /**
     * 有 BOM 时以 BOM 判定字符集，优先级高于 Content-Type：BOM 是字节流自带的事实。
     */
    private Charset charsetForBytes(byte[] data) {
        if (data.length >= 2) {
            if ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                return Charset.forName("UTF-16BE");
            }
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                return Charset.forName("UTF-16LE");
            }
        }
        return charset();
    }

    /**
     * @return 需要跳过的 BOM 字节数，无 BOM 为 0
     */
    private static int bomLength(byte[] data, Charset cs) {
        String name = cs.name().toUpperCase(java.util.Locale.ROOT);
        if (name.startsWith("UTF-8") && data.length >= 3
                && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (name.startsWith("UTF-16") && data.length >= 2) {
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) {
                return 2;
            }
        }
        return 0;
    }

    /**
     * 解析 JSON 并绑定到实体类。
     *
     * @param type 目标类型
     * @param <T> 实体类型
     * @return 实体，正文为空时返回 {@code null}
     * @throws IOException 读取失败
     */
    public <T> T json(Class<T> type) throws IOException {
        byte[] data = bytes();
        if (data.length == 0) {
            return null;
        }
        return JSON.parseObject(data, type);
    }

    /**
     * 解析为 {@link JSONNode} 节点树，可继续用 xpath 提取。
     *
     * @return 节点树，正文为空时返回 {@code null}
     * @throws IOException 读取失败
     */
    public JSONNode jsonNode() throws IOException {
        byte[] data = bytes();
        if (data.length == 0) {
            return null;
        }
        return JSONNode.parse(data);
    }

    /**
     * 解析为 JSON 对象 Map。
     *
     * @return Map，正文为空或不是对象时为空 Map
     * @throws IOException 读取失败
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> jsonMap() throws IOException {
        byte[] data = bytes();
        if (data.length == 0) {
            return new java.util.LinkedHashMap<String, Object>();
        }
        Object parsed = JSON.parse(data);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        return new java.util.LinkedHashMap<String, Object>();
    }

    /**
     * @return 按 Content-Type 解析出的字符集（缺省 UTF-8）
     */
    public Charset charset() {
        return HttpIo.charsetFromContentType(contentType);
    }

    /**
     * @return 正文是否为空（缓冲模式看字节长度，流式模式看声明长度）
     */
    public boolean isEmpty() {
        if (bytes != null) {
            return bytes.length == 0;
        }
        return contentLength == 0;
    }

    /**
     * @return 字节正文
     * @throws IOException 读取失败
     */
    public byte[] bytes() throws IOException {
        if (bytes != null) {
            return bytes;
        }
        ensureNotConsumed();
        try {
            return HttpIo.readAll(stream);
        } finally {
            close();
        }
    }

    /**
     * @return 字节流；缓冲模式下为内存流
     */
    public InputStream byteStream() {
        if (stream != null) {
            ensureNotConsumed();
            return stream;
        }
        return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
    }

    /**
     * 将正文转发到输出流（不额外占用整块内存），完成后关闭底层连接。
     *
     * @param out 目标输出流
     * @return 写出的字节数
     * @throws IOException 读取或写出失败
     */
    public long transferTo(OutputStream out) throws IOException {
        if (bytes != null) {
            out.write(bytes);
            return bytes.length;
        }
        ensureNotConsumed();
        try {
            return HttpIo.copy(stream, out, 8192);
        } finally {
            close();
        }
    }

    /**
     * 将正文保存到文件（父目录不存在时自动创建），完成后关闭底层连接。
     *
     * @param file 目标文件
     * @return 目标文件
     * @throws IOException 读取或写出失败
     */
    public File saveTo(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create directory: " + parent);
        }
        FileOutputStream fos = new FileOutputStream(file);
        try {
            transferTo(fos);
            fos.flush();
        } finally {
            HttpIo.closeQuietly(fos);
        }
        return file;
    }

    /**
     * 将正文保存到指定路径。
     *
     * @param path 目标路径
     * @return 目标文件
     * @throws IOException 读取或写出失败
     */
    public File saveTo(String path) throws IOException {
        return saveTo(new File(path));
    }

    /**
     * @return 流式正文是否已被消费（流式响应体只能消费一次）
     */
    public boolean isConsumed() {
        return consumed;
    }

    private void ensureNotConsumed() {
        if (consumed) {
            throw new IllegalStateException("流式响应体已被消费，不能重复读取；如需多次使用请先调用 bytes()");
        }
        consumed = true;
    }

    /**
     * @return Content-Type，可能为 {@code null}
     */
    public String contentType() {
        return contentType;
    }

    /**
     * @return 内容长度；未知为 {@code -1}
     */
    public long contentLength() {
        return contentLength;
    }

    @Override
    public void close() {
        HttpIo.closeQuietly(stream);
        HttpIo.closeQuietly(resource);
    }
}
