package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 移动云盘 REST 传输层：复用 jkit-storage 内部 {@link HttpCore}（JDK {@code HttpURLConnection}，
 * 零第三方依赖），与其余引擎共用同一套代理 / 超时 / 连接复用语义。
 *
 * <p>两类请求走两种通道：</p>
 * <ul>
 *   <li><b>业务接口</b>（{@code POST} JSON 到网关）：携带 IDaaS 五项鉴权头与平台公共头，
 *   响应是统一信封（{@code success} / {@code code} / {@code message} / {@code data}），
 *   成功判定与错误翻译在 {@link #postJson} 内完成，调用方拿到的就是 {@code data} 对象。</li>
 *   <li><b>预签名地址</b>（上传 {@code PUT} / 下载 {@code GET}）：地址本身已含签名，
 *   <b>不得附加任何业务鉴权头</b>（改写签名参数或加头都会被对象存储网关拒绝）。</li>
 * </ul>
 *
 * <p><b>票据绝不落日志</b>：异常文案只带状态码与响应体摘要，鉴权头只在发请求时构造。
 * 与 Git / SVN 引擎一致，引擎实例由 {@code Sessions} 每次操作新建、用完即关。</p>
 *
 * @author 郑明亮
 */
final class CmCloudTransport implements AutoCloseable {
    /** 连接超时。 */
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    /** 业务接口读超时（列目录 / 建目录 / 删任务轮询等小请求）。 */
    private static final int REQUEST_TIMEOUT_MS = 60_000;
    /** 预签名上传 PUT / 下载 GET 读超时：大文件单请求可能很久，10 分钟是「够用但不至于永久挂着」的折中。 */
    private static final int UPLOAD_TIMEOUT_MS = 600_000;
    /** 上传 PUT 的 Date 头格式（平台二阶段上传契约要求 GMT RFC 1123 格式）。 */
    private static final DateTimeFormatter GMT_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US);

    CmCloudTransport() {
        // 无连接需要预热：HttpCore 每次请求独立开连接，超时在请求上设置
    }

    /**
     * 调用业务接口：POST JSON 信封请求并校验平台回执。
     *
     * @param config 连接配置（提供网关地址与鉴权头）
     * @param api    接口路径（如 {@code /file/list}）
     * @param body   业务请求体
     * @param path   出错时的逻辑路径（进异常文案）
     * @return 平台回执的 {@code data} 对象；平台未给 data 时返回空对象
     * @throws StorageException 鉴权失败 / 平台业务失败 / 网络失败 / 响应非法
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> postJson(CmCloudConfig config, String api, Map<String, Object> body,
                                 String path) {
        String url = config.apiUrl(api);
        String json = JSON.toJsonString(body);
        HttpCore.Request request = new HttpCore.Request("POST", url)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .header("version", "2.0.0")
                .header("creditToken", config.creditToken())
                .header("skillId", config.skillId())
                .header("clientId", config.clientId())
                .header("strategyId", config.strategyId())
                .header("clawUid", config.clawUid())
                .body(json.getBytes(StandardCharsets.UTF_8))
                .timeouts(null, CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(request, config.raw()));
        int status = response.status();
        String text = response.bodyText();
        response.close();
        if (status < 200 || status >= 300) {
            throw CmCloudErrors.http(status, text, path);
        }
        Object parsed;
        try {
            parsed = JSON.parse(text);
        } catch (RuntimeException e) {
            throw StorageException.io("移动云盘响应无法解析：" + summarize(text), path, e);
        }
        if (!(parsed instanceof Map)) {
            throw StorageException.io("移动云盘返回了非对象响应：" + summarize(text), path, null);
        }
        Map<String, Object> envelope = (Map<String, Object>) parsed;
        // 成功判定与 Python 授权工具同口径：success 显式 false 或 code 非 0000/0 都按失败处理
        Object success = envelope.get("success");
        if (success != null && !"true".equalsIgnoreCase(String.valueOf(success).trim())) {
            throw CmCloudErrors.business(str(envelope, "code"), str(envelope, "message"), path);
        }
        Object code = envelope.get("code");
        if (code != null) {
            String codeText = String.valueOf(code).trim();
            if (!"0".equals(codeText) && !"0000".equalsIgnoreCase(codeText)) {
                throw CmCloudErrors.business(codeText, str(envelope, "message"), path);
            }
        }
        Object data = envelope.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 把分片数据 PUT 到预签名上传地址。
     *
     * <p>预签名地址不带任何业务鉴权头；平台「独立空间 SaaS 二阶段分片上传」契约要求：</p>
     * <ul>
     *   <li>显式 {@code Content-Length} 且与请求体严格一致（走 chunked 会被预签名校验拒绝）；
     *   因此分片数据先整段读入内存（单片上限 5MiB，内存可控）再发；</li>
     *   <li>{@code Content-Type} 固定 {@code application/octet-stream}——默认补的
     *   {@code application/x-www-form-urlencoded} 同样会被拒；</li>
     *   <li>显式 {@code Date}（GMT 格式）。</li>
     * </ul>
     *
     * @param uploadUrl 预签名上传地址（来自 file/create 或 file/getUploadUrl 响应）
     * @param data      分片数据（字节数必须与提交的 partSize 一致）
     * @param path      出错时的逻辑路径
     * @throws StorageException 上传失败（4xx/5xx 或网络失败）
     */
    void putUpload(String uploadUrl, byte[] data, String path) {
        if (Strs.isBlank(uploadUrl)) {
            throw StorageException.io("平台未返回上传地址，无法继续上传", path, null);
        }
        if (data == null || data.length == 0) {
            throw StorageException.io("上传分片为空，无法发送", path, null);
        }
        HttpCore.Request request = new HttpCore.Request("PUT", uploadUrl.trim())
                .header("Content-Type", "application/octet-stream")
                .header("Date", GMT_DATE.format(ZonedDateTime.now(ZoneOffset.UTC)))
                .body(data)
                .timeouts(null, CONNECT_TIMEOUT_MS, UPLOAD_TIMEOUT_MS);
        // body(byte[]) 由 HttpCore 按数组长度设定长 Content-Length（非 chunked）
        HttpCore.Response response = HttpCore.execute(request);
        int status = response.status();
        if (status < 200 || status >= 300) {
            // 对象存储的错误是 XML（Code/Message）或网关 HTML：取得到就带出来，排障少一轮
            byte[] errorBody = response.bodyBytes();
            throw new StorageException(StorageErrorCode.IO_ERROR,
                    "上传数据被对象存储拒绝（HTTP " + status + "）" + objectStorageDetail(errorBody), path);
        }
        response.close();
    }

    /** 从对象存储错误响应体提取 {@code <Code>} / {@code <Message>}（XML）；取不到给空串。 */
    static String objectStorageDetail(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("<")) {
            // 网关 / 反代的 HTML 错误页不带出来
            return "";
        }
        String code = xmlField(text, "Code");
        String message = xmlField(text, "Message");
        if (code == null && message == null) {
            return "";
        }
        StringBuilder out = new StringBuilder("：");
        if (code != null) {
            out.append(code);
        }
        if (message != null && !Strs.isBlank(message)) {
            if (out.length() > 1) {
                out.append(" / ");
            }
            out.append(clip(message.trim(), 160));
        }
        return out.toString();
    }

    /** 取 XML 单标签文本（不含嵌套；对象存储错误是平铺结构）。 */
    private static String xmlField(String text, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int at = text.indexOf(open);
        if (at < 0) {
            return null;
        }
        int start = at + open.length();
        int end = text.indexOf(close, start);
        if (end < 0) {
            return null;
        }
        return text.substring(start, end).trim();
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /**
     * 打开预签名下载地址的输入流（支持 Range 断点续传）。
     *
     * @param url    预签名下载地址（来自 file/getDownloadUrl 响应）
     * @param offset 起始偏移；0 表示全量
     * @param path   出错时的逻辑路径
     * @return 输入流（调用方负责关闭）；非 2xx 抛异常
     */
    InputStream openDownload(String url, long offset, String path) {
        HttpCore.Request request = new HttpCore.Request("GET", url.trim())
                .timeouts(null, CONNECT_TIMEOUT_MS, UPLOAD_TIMEOUT_MS);
        if (offset > 0) {
            request.header("Range", "bytes=" + offset + "-");
        }
        HttpCore.Response response = HttpCore.execute(request);
        int status = response.status();
        if (status < 200 || status >= 300) {
            // 先把错误响应体读完（也顺手关掉连接），再抛异常
            response.bodyBytes();
            throw CmCloudErrors.http(status, "", path);
        }
        return new DownloadStream(response);
    }

    /** 取字符串字段（缺失返回 null）。 */
    static String str(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 取长整型字段（缺失或非数字返回默认值）。 */
    static long num(Map<String, Object> source, String key, long defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        Object value = source.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !Strs.isBlank((String) value)) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // 平台给了非数字：按未知处理
            }
        }
        return defaultValue;
    }

    /** 响应体摘要（异常文案用，防止长 HTML 错误页进弹窗）。 */
    private static String summarize(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    @Override
    public void close() {
        // HttpCore 每次请求独立开连接，无需持有资源
    }

    /** 构造 JSON 请求体（保持键序，便于排障时对照文档）。 */
    static Map<String, Object> body() {
        return new LinkedHashMap<>();
    }

    /** 下载流：关闭时连带关闭底层响应，让连接归还或断开（不泄漏 socket）。139 引擎复用同一个实现。 */
    static final class DownloadStream extends java.io.FilterInputStream {
        private final HttpCore.Response response;

        DownloadStream(HttpCore.Response response) {
            super(response.body());
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                response.close();
            }
        }
    }
}
