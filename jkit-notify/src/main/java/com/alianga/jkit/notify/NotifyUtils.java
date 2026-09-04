package com.alianga.jkit.notify;

import com.alianga.jkit.json.JSON;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * URL 编码（UTF-8）。
     *
     * @param value 原始字符串
     * @return 编码结果
     */
    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
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
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
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
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            Map<?, ?> map = JSON.parseObject(json);
            return map == null ? null : map.get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
