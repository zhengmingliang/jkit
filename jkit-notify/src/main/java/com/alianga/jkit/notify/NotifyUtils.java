package com.alianga.jkit.notify;

import com.alianga.jkit.Base64Utils;
import com.alianga.jkit.EncryptUtils;
import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.IOUtils;
import com.alianga.jkit.RandomUtils;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.collection.Collections;
import com.alianga.jkit.collection.Maps;
import com.alianga.jkit.io.ByteUtils;
import com.alianga.jkit.io.FileType;
import com.alianga.jkit.json.JSON;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 渠道实现的公共小工具：签名、编码、URL 拼接与容错的 JSON 取值。
 *
 * <p>面向自定义渠道实现者开放（实现 {@link NotificationChannel} 时可直接复用），
 * 不属于普通调用方的稳定 API，行为可能随渠道适配需要调整。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class NotifyUtils {
    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 内容被长度上限截断时追加的可见标记。
     */
    public static final String TRUNCATE_SUFFIX = "...[内容过长已截断]";

    private NotifyUtils() {
    }

    /**
     * HmacSHA256 签名。
     *
     * @param secret 密钥
     * @param data 待签数据
     * @return 签名字节
     */
    public static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("hmacSha256 unavailable", e);
        }
    }

    /**
     * Base64 编码（无换行）。
     *
     * @param bytes 原始字节
     * @return Base64 字符串
     */
    public static String base64(byte[] bytes) {
        return Base64Utils.encodeToString(bytes);
    }

    /**
     * URL 编码（UTF-8）。
     *
     * @param value 原始字符串
     * @return 编码结果
     */
    public static String urlEncode(String value) {
        return HttpUtils.encodeValue(value);
    }

    /**
     * 在 URL 后追加查询参数，自动选择 {@code ?} 或 {@code &} 连接符。
     *
     * @param url 原 URL，可能已含查询串
     * @param name 参数名（不做编码）
     * @param value 参数值（不做编码）
     * @return 拼接后的 URL
     */
    public static String appendQuery(String url, String name, String value) {
        String separator = url.indexOf('?') >= 0 ? "&" : "?";
        return url + separator + name + "=" + value;
    }

    /**
     * 把对象编成紧凑 JSON 字符串。渠道 payload 优先走这里，避免手工拼接转义出错。
     *
     * @param value Map / List / 标量
     * @return JSON 字符串；{@code null} 返回 {@code "null"}
     */
    public static String toJson(Object value) {
        return JSON.toJsonString(value);
    }

    /**
     * 有序 Map，便于稳定输出 JSON 键序（方便测试断言）。
     *
     * @return 空的 LinkedHashMap
     */
    public static Map<String, Object> map() {
        return Maps.newLinkedHashMap();
    }

    /**
     * 有序字符串 Map（表单编码等场景使用）。
     *
     * @return 空的 LinkedHashMap
     * @since 2.0.1
     */
    public static Map<String, String> strMap() {
        return Maps.newLinkedHashMap();
    }

    /**
     * 把值转成 JSON 字符串字面量内容（不含引号），用于手工拼装 JSON 场景，
     * 防止正文里的引号、换行破坏结构。
     *
     * @param value 原始值
     * @return 转义后的内容
     */
    public static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        // 复用 jkit-core JSON 字符串转义，再去掉外侧引号
        String json = JSON.toJsonString(value);
        return json.substring(1, json.length() - 1);
    }

    /**
     * 计算字符串的 UTF-8 字节长度。
     *
     * <p>钉钉、企微等平台的长度上限按**字节**而非字符计，一个中文占 3 字节，
     * 按 {@code String.length()} 判断会严重高估可发送量。
     *
     * @param text 文本，{@code null} 返回 0
     * @return UTF-8 字节数
     */
    public static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 按 UTF-8 字节上限截断文本，保证不切断多字节字符。
     *
     * <p>超限时在结尾追加截断标记，且**含标记在内**不超过 {@code maxBytes}——
     * 直接按字节数组切片会把一个汉字劈成两半，产出乱码字节序列，部分平台会因此整条拒收。
     *
     * @param text 原文，{@code null} 原样返回
     * @param maxBytes 字节上限，非正数表示不限制
     * @return 未超限时返回原文；超限时返回截断并带标记的文本
     */
    public static String truncateUtf8(String text, int maxBytes) {
        if (text == null || maxBytes <= 0 || utf8Length(text) <= maxBytes) {
            return text;
        }
        int budget = maxBytes - utf8Length(TRUNCATE_SUFFIX);
        if (budget <= 0) {
            // 上限比标记本身还短，退化为纯截断
            return truncateToBytes(text, maxBytes);
        }
        return truncateToBytes(text, budget) + TRUNCATE_SUFFIX;
    }

    /**
     * 把 Markdown 转成 HTML 片段。
     *
     * <p>SMTP 等不原生渲染 Markdown 的渠道用它把 {@link MessageType#MARKDOWN} 转成
     * {@code text/html}。钉钉 / 企微 / 飞书 / Server酱本身支持 markdown，不要走这步。
     * 覆盖标题、嵌套列表、GFM 表格、CLI 宽表、缩进围栏代码块、链接、加粗等常用子集，
     * 不是完整 CommonMark。
     *
     * @param markdown 原文，{@code null} 或空串返回空串
     * @return HTML 片段（不含 html 文档壳）
     */
    public static String markdownToHtml(String markdown) {
        return Markdown.toHtml(markdown);
    }

    /**
     * 把 Markdown 转成带文档壳的 HTML，便于邮件客户端预览。
     *
     * @param markdown 原文
     * @param responsive {@code true} 时带 viewport 与移动端/PC 适配样式
     * @return 完整 HTML 文档；原文为空时返回空串
     */
    public static String markdownToDocument(String markdown, boolean responsive) {
        String fragment = markdownToHtml(markdown);
        if (StringUtils.isEmpty(fragment)) {
            return "";
        }
        return wrapHtmlDocument(fragment, responsive);
    }

    /**
     * 给 HTML 片段套文档壳（charset + 可选响应式样式）。
     *
     * @param fragment HTML 片段
     * @param responsive 是否适配手机与桌面预览
     * @return 完整 HTML 文档
     */
    public static String wrapHtmlDocument(String fragment, boolean responsive) {
        String body = StringUtils.defaultString(fragment);
        if (!responsive) {
            return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body>"
                    + body + "</body></html>";
        }
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>"
                + "html{-webkit-text-size-adjust:100%}"
                + "body{margin:0 auto;padding:16px;max-width:720px;font-family:-apple-system,"
                + "BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Hiragino Sans GB',"
                + "'Microsoft YaHei',sans-serif;line-height:1.65;color:#222;font-size:16px;"
                + "word-wrap:break-word;overflow-wrap:anywhere}"
                + "h1,h2,h3,h4{line-height:1.3;margin:1.2em 0 .5em}"
                + "h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}"
                + "img,table,pre,video{max-width:100%}"
                + "img{height:auto}"
                + "pre{background:#f6f8fa;padding:12px;overflow:auto;border-radius:6px;"
                + "font-size:13px;white-space:pre;box-sizing:border-box}"
                + "code{font-family:ui-monospace,Menlo,Consolas,monospace;background:#f6f8fa;"
                + "padding:0 .3em}"
                + "pre code{background:none;padding:0}"
                + "table{border-collapse:collapse;margin:12px 0;display:block;overflow-x:auto}"
                + "th,td{border:1px solid #d0d7de;padding:6px 10px;text-align:left}"
                + "th{background:#f6f8fa}"
                + "blockquote{border-left:4px solid #d0d7de;margin:0;padding:0 12px;color:#57606a}"
                + "@media (min-width:768px){body{padding:24px 32px;font-size:15px}"
                + "pre{font-size:13px}}"
                + "@media (max-width:480px){body{padding:12px;font-size:16px}"
                + "table,th,td{font-size:14px}pre{font-size:12px;padding:8px}}"
                + "</style></head><body>"
                + body
                + "</body></html>";
    }

    /**
     * 解析带单位的容量：{@code 10MB}、{@code 512KB}、{@code 1.5G}、纯数字按字节。
     *
     * <p>单位按 1024 进位（KB/MB/GB，也接受 KiB/MiB/GiB 与 K/M/G）。空白忽略，大小写不敏感。
     *
     * @param spec 容量描述
     * @return 字节数
     * @throws IllegalArgumentException 格式非法或结果非正
     */
    public static long parseDataSize(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("data size is required");
        }
        String trimmed = spec.trim().replace("_", "").replace(" ", "");
        String upper = trimmed.toUpperCase(Locale.ROOT);
        long unit = 1L;
        String number = upper;
        if (upper.endsWith("KIB")) {
            unit = 1024L;
            number = upper.substring(0, upper.length() - 3);
        } else if (upper.endsWith("MIB")) {
            unit = 1024L * 1024;
            number = upper.substring(0, upper.length() - 3);
        } else if (upper.endsWith("GIB")) {
            unit = 1024L * 1024 * 1024;
            number = upper.substring(0, upper.length() - 3);
        } else if (upper.endsWith("KB")) {
            unit = 1024L;
            number = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("MB")) {
            unit = 1024L * 1024;
            number = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("GB")) {
            unit = 1024L * 1024 * 1024;
            number = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("B")) {
            unit = 1L;
            number = upper.substring(0, upper.length() - 1);
        } else if (upper.endsWith("K")) {
            unit = 1024L;
            number = upper.substring(0, upper.length() - 1);
        } else if (upper.endsWith("M")) {
            unit = 1024L * 1024;
            number = upper.substring(0, upper.length() - 1);
        } else if (upper.endsWith("G")) {
            unit = 1024L * 1024 * 1024;
            number = upper.substring(0, upper.length() - 1);
        }
        if (StringUtils.isEmpty(number)) {
            throw new IllegalArgumentException("invalid data size: " + spec);
        }
        try {
            double value = Double.parseDouble(number);
            long bytes = (long) Math.floor(value * unit);
            if (bytes <= 0) {
                throw new IllegalArgumentException("data size must be positive: " + spec);
            }
            return bytes;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid data size: " + spec, e);
        }
    }

    /**
     * 渲染 {@code ${key}} / {@code ${a.b}} 占位符。
     *
     * <p>缺键写成空串，避免告警正文里留下未替换的占位符。值内若再出现 {@code ${...}} 不会二次展开。
     *
     * @param template 模板，{@code null} 返回 {@code null}
     * @param vars 变量，可为 {@code null}
     * @return 渲染结果
     */
    public static String renderTemplate(String template, Map<String, ?> vars) {
        // Keep local implementation: StringUtils.replaceGroupRegex is NOT equivalent
        // (backslash escape, hyphen/unicode keys, empty ${} differ). See NotifyUtilsCoreEquivTest.
        if (template == null || Collections.isEmpty(vars) || template.indexOf('$') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, start);
            String key = template.substring(start + 2, end).trim();
            Object value = lookupVar(vars, key);
            out.append(value == null ? "" : String.valueOf(value));
            i = end + 1;
        }
        return out.toString();
    }

    private static Object lookupVar(Map<String, ?> vars, String key) {
        if (key.isEmpty()) {
            return null;
        }
        if (vars.containsKey(key)) {
            return vars.get(key);
        }
        int dot = key.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        Object current = vars.get(key.substring(0, dot));
        int from = dot + 1;
        while (from <= key.length()) {
            int next = key.indexOf('.', from);
            String part = next < 0 ? key.substring(from) : key.substring(from, next);
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(part);
            if (next < 0) {
                return current;
            }
            from = next + 1;
        }
        return current;
    }

    /**
     * 按 RFC 2045 把输入流编成 Base64，每 76 字符折 CRLF，不把整段读进内存。
     *
     * @param in 输入
     * @param out 输出
     * @throws IOException 读写失败
     */
    public static void writeFoldedBase64(InputStream in, Appendable out) throws IOException {
        if (in == null) {
            return;
        }
        byte[] raw = new byte[57];
        boolean first = true;
        int read = in.read(raw);
        while (read >= 0) {
            if (read == 0) {
                read = in.read(raw);
                continue;
            }
            byte[] chunk = read == raw.length ? raw : java.util.Arrays.copyOf(raw, read);
            if (!first) {
                out.append("\r\n");
            }
            first = false;
            out.append(foldBase64(Base64Utils.encodeToString(chunk)));
            read = in.read(raw);
        }
    }

    /**
     * 按 RFC 2045 把 Base64 按 76 字符折行。
     *
     * @param base64 单行 Base64
     * @return 折行结果
     */
    public static String foldBase64(String base64) {
        if (StringUtils.isEmpty(base64)) {
            return "";
        }
        StringBuilder out = new StringBuilder(base64.length() + base64.length() / 76 * 2 + 2);
        for (int i = 0; i < base64.length(); i += 76) {
            int end = Math.min(i + 76, base64.length());
            if (i > 0) {
                out.append("\r\n");
            }
            out.append(base64, i, end);
        }
        return out.toString();
    }

    /**
     * 流式计算文件一段的 SHA-256。
     *
     * @param file 文件
     * @param offset 起始偏移
     * @param length 字节数，超过剩余长度时读到 EOF
     * @return hex
     */
    public static String sha256Hex(File file, long offset, long length) {
        if (file == null) {
            return sha256Hex(new byte[0]);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(file);
            try {
                skipFully(in, offset);
                byte[] buf = new byte[8192];
                long remain = length;
                while (remain > 0) {
                    int want = (int) Math.min(buf.length, remain);
                    int read = in.read(buf, 0, want);
                    if (read < 0) {
                        break;
                    }
                    digest.update(buf, 0, read);
                    remain -= read;
                }
            } finally {
                IOUtils.close(in);
            }
            return ByteUtils.toHexStringLower(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param file 整个文件
     * @return SHA-256 hex
     */
    public static String sha256Hex(File file) {
        if (file == null) {
            return sha256Hex(new byte[0]);
        }
        try {
            return EncryptUtils.sha256(file);
        } catch (IOException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void skipFully(InputStream in, long count) throws IOException {
        long remain = count;
        while (remain > 0) {
            long skipped = in.skip(remain);
            if (skipped > 0) {
                remain -= skipped;
                continue;
            }
            if (in.read() < 0) {
                return;
            }
            remain--;
        }
    }

    /**
     * 根据文件名后缀与文件头自动识别 MIME。
     *
     * <p>优先用 {@link FileType#getMimeTypeBySuffix(String)}；识别不出再读文件头
     * {@link FileType#getFileType(java.io.InputStream)}，并补常见后缀（zip/pdf/json 等）。
     *
     * @param filename 文件名，可为 {@code null}
     * @param content 文件内容，可为 {@code null}
     * @return MIME；无法识别时为 {@code application/octet-stream}
     */
    public static String detectMimeType(String filename, byte[] content) {
        String fromName = mimeFromFilename(filename);
        if (fromName != null) {
            return fromName;
        }
        String fromMagic = mimeFromMagic(content);
        if (fromMagic != null) {
            return fromMagic;
        }
        return "application/octet-stream";
    }

    private static String mimeFromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return mimeFromSuffix(name.substring(dot + 1));
    }

    private static String mimeFromMagic(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        String suffix = FileType.getFileType(new ByteArrayInputStream(content));
        if (suffix == null) {
            return null;
        }
        String key = suffix.toLowerCase(Locale.ROOT);
        // PK 头（zip/jar/docx/xlsx）默认 3 字节无法区分，魔数路径一律按 zip
        if ("zip".equals(key) || "jar".equals(key) || "docx".equals(key)
                || "xlsx".equals(key) || "pptx".equals(key) || "war".equals(key)) {
            return "application/zip";
        }
        return mimeFromSuffix(suffix);
    }

    private static String mimeFromSuffix(String suffix) {
        if (suffix == null || suffix.trim().isEmpty()) {
            return null;
        }
        String key = suffix.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        String mapped = FileType.getMimeTypeBySuffix(key);
        if (StringUtils.isNotEmpty(mapped)) {
            return mapped;
        }
        if ("zip".equals(key) || "jar".equals(key) || "war".equals(key) || "ear".equals(key)
                || "docx".equals(key) || "xlsx".equals(key) || "pptx".equals(key)) {
            if ("docx".equals(key)) {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            if ("xlsx".equals(key)) {
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            if ("pptx".equals(key)) {
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }
            return "application/zip";
        }
        if ("7z".equals(key)) {
            return "application/x-7z-compressed";
        }
        if ("rar".equals(key)) {
            return "application/vnd.rar";
        }
        if ("pdf".equals(key)) {
            return "application/pdf";
        }
        if ("txt".equals(key) || "log".equals(key) || "md".equals(key) || "markdown".equals(key)) {
            return "text/plain";
        }
        if ("csv".equals(key)) {
            return "text/csv";
        }
        if ("html".equals(key) || "htm".equals(key)) {
            return "text/html";
        }
        if ("json".equals(key)) {
            return "application/json";
        }
        if ("xml".equals(key)) {
            return "application/xml";
        }
        if ("gz".equals(key) || "gzip".equals(key)) {
            return "application/gzip";
        }
        if ("tar".equals(key)) {
            return "application/x-tar";
        }
        return null;
    }

    /**
     * 按码点逐个累加字节数，在字符边界处停下（代理对整体保留，不劈开 emoji）。
     */
    private static String truncateToBytes(String text, int maxBytes) {
        int bytes = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            int cpBytes = utf8Length(text.substring(index, index + charCount));
            if (bytes + cpBytes > maxBytes) {
                break;
            }
            bytes += cpBytes;
            index += charCount;
        }
        return text.substring(0, index);
    }

    /**
     * 按块切分数组（邮件附件拆包）。
     *
     * @param data 原始字节，{@code null} 返回空列表
     * @param chunkSize 分块大小，非正数时整段作为一块
     * @return 分块列表
     */
    public static List<byte[]> splitBytes(byte[] data, long chunkSize) {
        List<byte[]> chunks = new ArrayList<byte[]>();
        if (data == null || data.length == 0) {
            return chunks;
        }
        int size = chunkSize <= 0 ? data.length : (int) Math.min(chunkSize, Integer.MAX_VALUE);
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(size, data.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            chunks.add(chunk);
            offset += length;
        }
        return chunks;
    }

    /**
     * SHA-256 十六进制小写（附件拆包校验用）。
     *
     * @param data 原始字节
     * @return hex 字符串
     */
    public static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    /**
     * MD5 十六进制小写（企微图片消息要求）。
     *
     * @param data 原始字节
     * @return hex 字符串
     */
    public static String md5Hex(byte[] data) {
        return digestHex("MD5", data);
    }

    private static String digestHex(String algorithm, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(data == null ? new byte[0] : data);
            return ByteUtils.toHexStringLower(hash);
        } catch (Exception e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    /**
     * 从 JSON 对象里取整型字段（容错：解析失败或字段不存在返回 {@code null}）。
     *
     * @param json JSON 字符串
     * @param key 字段名
     * @return 整型值
     */
    public static Integer jsonInt(String json, String key) {
        Object value = jsonValue(json, key);
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        return null;
    }

    /**
     * 从 JSON 对象里取字符串字段（容错：解析失败或字段不存在返回 {@code null}）。
     *
     * @param json JSON 字符串
     * @param key 字段名
     * @return 字符串值
     */
    public static String jsonString(String json, String key) {
        Object value = jsonValue(json, key);
        return value == null ? null : String.valueOf(value);
    }

    private static Object jsonValue(String json, String key) {
        if (StringUtils.isEmpty(json)) {
            return null;
        }
        try {
            Map<?, ?> map = JSON.parseObject(json);
            return map == null ? null : map.get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 容错 JSON 解析：失败返回 {@code null}，不抛异常。
     *
     * <p>给非标准 JSON 响应（如 Telegram 混排 HTML 错误页）做兜底。
     *
     * @param json JSON 字符串，可为 {@code null}
     * @return 解析结果；失败为 {@code null}
     * @since 2.0.1
     */
    public static Object parseJson(String json) {
        if (StringUtils.isEmpty(json)) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把键值对编成 {@code application/x-www-form-urlencoded} 请求体。
     *
     * <p>华为云短信等只收表单编码的渠道使用；值为 {@code null} 的键跳过。
     *
     * @param params 键值对，可为 {@code null}
     * @return 编码后的请求体；无有效参数时为空串
     * @since 2.0.1
     */
    public static String formEncode(Map<String, String> params) {
        if (Collections.isEmpty(params)) {
            return "";
        }
        Map<String, Object> asObject = Maps.newLinkedHashMap();
        asObject.putAll(params);
        return HttpUtils.getRequestParamString(asObject);
    }

    /**
     * 解析收件人参数：逗号 / 分号 / 空白分隔的多个手机号拆成列表。
     *
     * @param raw 收件人，可以是单个号码、逗号分隔串或集合
     * @return 手机号列表；{@code null} 或全空白时为空列表
     * @since 2.0.1
     */
    public static List<String> parseReceivers(Object raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof Iterable) {
            for (Object item : (Iterable<?>) raw) {
                if (item != null) {
                    addReceiver(out, String.valueOf(item));
                }
            }
        } else if (raw instanceof Object[]) {
            for (Object item : (Object[]) raw) {
                if (item != null) {
                    addReceiver(out, String.valueOf(item));
                }
            }
        } else {
            addReceiver(out, String.valueOf(raw));
        }
        return out;
    }

    private static void addReceiver(List<String> out, String text) {
        if (text == null) {
            return;
        }
        for (String part : text.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (StringUtils.isNotBlank(trimmed)) {
                out.add(trimmed);
            }
        }
    }

    /**
     * 随机 UUID（去掉连字符），短信签名 nonce 用。
     *
     * @return 32 位十六进制串
     * @since 2.0.1
     */

    /**
     * 返回第一个非空（非 {@code null} 且非空串）字符串。
     *
     * @param values 候选
     * @return 首个非空值；全空时为 {@code null}
     * @since 2.0.1
     */
    public static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /**
     * HTML 实体转义（{@code &amp; &lt; &gt; &quot;}），Markdown→HTML 与 Telegram HTML 模式共用。
     *
     * @param text 原文，可为 {@code null}
     * @return 转义后的文本；{@code null}/空串返回空串
     * @since 2.0.1
     */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    public static String uuid() {
        return RandomUtils.getUUID();
    }
}
