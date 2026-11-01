package com.alianga.jkit.captcha.behavior;

import com.alianga.jkit.json.JSON;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tianai-captcha 开源协议客户端（配合开源版 demo 服务端或自建同协议服务）。
 *
 * <p>接口契约来自开源 web-sdk：
 * <ul>
 *   <li>取验证码：{@code POST genUrl}，空 JSON 体，返回
 *       {@code {code, data:{id, type, backgroundImage, templateImage, backgroundImageWidth, ...}}}，
 *       图片字段为 base64（可带 data:image 前缀）或可下载 URL；</li>
 *   <li>校验：{@code POST checkUrl}，body 为
 *       {@code {id, data:{bgImageWidth, bgImageHeight, templateImageWidth, templateImageHeight,
 *       startTime, stopTime, trackList:[{x,y,t,type}], data}}}；</li>
 *   <li>返回 {@code {code:200}} 即通过，4001 基本校验失败，50001 行为轨迹失败，4000 已失效。</li>
 * </ul>
 *
 * <p>坐标系自洽即可：轨迹与 bgImageWidth 使用同一坐标系（自然像素或显示像素），
 * 服务端按「终点位移 / bgImageWidth」与生成时记录的百分比比对，容差默认 2%。
 */
public final class TianaiCaptchaClient {
    /** 校验通过返回码。 */
    public static final int CODE_SUCCESS = 200;
    /** 基本校验失败（位置不对）。 */
    public static final int CODE_BASIC_CHECK_FAIL = 4001;
    /** 验证码已失效（超过缓存有效期）。 */
    public static final int CODE_EXPIRED = 4000;
    /** 行为轨迹校验失败。 */
    public static final int CODE_TRACK_FAIL = 50001;

    /** 默认请求超时（毫秒）。 */
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String userAgent;

    public TianaiCaptchaClient() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_TIMEOUT_MS, DEFAULT_USER_AGENT);
    }

    public TianaiCaptchaClient(int connectTimeoutMs, int readTimeoutMs, String userAgent) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.userAgent = userAgent;
    }

    /** 取验证码挑战。 */
    public TianaiChallenge generate(String genUrl) throws IOException {
        String body = post(genUrl, "{}");
        Map<?, ?> root = asMap(JSON.parseObject(body));
        int code = intOf(root.get("code"), -1);
        if (code != CODE_SUCCESS) {
            throw new IOException("generate 失败 code=" + code + " body=" + abbreviate(body));
        }
        Map<?, ?> data = asMap(root.get("data"));
        TianaiChallenge challenge = new TianaiChallenge();
        challenge.id = String.valueOf(data.get("id"));
        challenge.type = String.valueOf(data.get("type"));
        challenge.backgroundImageWidth = intOf(data.get("backgroundImageWidth"), 0);
        challenge.backgroundImageHeight = intOf(data.get("backgroundImageHeight"), 0);
        challenge.templateImageWidth = intOf(data.get("templateImageWidth"), 0);
        challenge.templateImageHeight = intOf(data.get("templateImageHeight"), 0);
        challenge.background = decodeImage(String.valueOf(data.get("backgroundImage")));
        challenge.template = decodeImage(String.valueOf(data.get("templateImage")));
        return challenge;
    }

    /**
     * 提交轨迹校验。bgImageWidth/Height、trackList 必须同一坐标系。
     *
     * @return 服务端返回码（200 通过；4001 位置不对；50001 行为轨迹不过；4000 过期）
     */
    public int check(String checkUrl, String id, float bgImageWidth, float bgImageHeight,
                     float templateImageWidth, float templateImageHeight,
                     List<TrackPoint> trackList, long startMs, long stopMs) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bgImageWidth", bgImageWidth);
        data.put("bgImageHeight", bgImageHeight);
        data.put("templateImageWidth", templateImageWidth);
        data.put("templateImageHeight", templateImageHeight);
        data.put("startTime", startMs);
        data.put("stopTime", stopMs);
        data.put("trackList", trackList);
        data.put("data", null);
        Map<String, Object> send = new LinkedHashMap<>();
        send.put("id", id);
        send.put("data", data);
        String body = post(checkUrl, JSON.toJsonString(send));
        Map<?, ?> root = asMap(JSON.parseObject(body));
        return intOf(root.get("code"), -1);
    }

    private String post(String url, String jsonBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            connection.setRequestProperty("User-Agent", userAgent);
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = readAll(input);
            if (status >= 400) {
                throw new IOException("HTTP " + status + " for " + url + ": " + new String(bytes, StandardCharsets.UTF_8));
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** 图片字段三态：data URI / 裸 base64 / URL。 */
    private BufferedImage decodeImage(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IOException("图片字段为空");
        }
        byte[] bytes;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
            try {
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestProperty("User-Agent", userAgent);
                int status = connection.getResponseCode();
                if (status != 200) {
                    throw new IOException("下载图片失败 HTTP " + status);
                }
                bytes = readAll(connection.getInputStream());
            } finally {
                connection.disconnect();
            }
        } else {
            int comma = value.indexOf(";base64,");
            String base64 = comma >= 0 ? value.substring(comma + ";base64,".length()) : value;
            bytes = Base64.getMimeDecoder().decode(base64);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("无法解码验证码图片");
        }
        return image;
    }

    private static Map<?, ?> asMap(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("JSON 响应结构不符: " + value);
        }
        return (Map<?, ?>) value;
    }

    private static int intOf(Object value, int defaultValue) {
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static byte[] readAll(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read = input.read(buffer);
        while (read >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
            }
            read = input.read(buffer);
        }
        return out.toByteArray();
    }

    /** 取验证码得到的挑战数据。 */
    public static final class TianaiChallenge {
        /** 服务端缓存的验证码 id（即 secretKey）。 */
        public String id;
        /** 验证码类型，如 SLIDER。 */
        public String type;
        /** 背景图（自然尺寸）。 */
        public BufferedImage background;
        /** 模板条（自然尺寸，含 alpha）。 */
        public BufferedImage template;
        /** 背景图宽。 */
        public int backgroundImageWidth;
        /** 背景图高。 */
        public int backgroundImageHeight;
        /** 模板条宽。 */
        public int templateImageWidth;
        /** 模板条高。 */
        public int templateImageHeight;
    }
}
