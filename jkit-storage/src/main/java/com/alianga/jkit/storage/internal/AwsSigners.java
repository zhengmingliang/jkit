package com.alianga.jkit.storage.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * AWS 签名器：SigV4（主流）与 SigV2（老兼容服务），另含金山云 KS3 的
 * {@code KSYUN} 前缀变体（与 SigV2 同构，仅 Authorization 前缀不同）。
 * 全部基于 {@code javax.crypto} 标准 API 实现。
 *
 * @author 郑明亮
 */
public final class AwsSigners {
    /** SigV4 认证头负载：未签名的请求体（流式上传场景）。 */
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private AwsSigners() {
    }

    /**
     * 计算 SigV4 签名并返回完整 Authorization 头值。
     *
     * @param method         HTTP 方法
     * @param host           Host 头（含端口则带端口）
     * @param canonicalPath  规范化路径（逐段编码，保留 {@code /}）
     * @param query          查询参数（方法内部排序编码）
     * @param headers        请求头（host / content-type / x-amz-* 参与签名；方法内转小写处理）
     * @param payloadHash    请求体哈希（十六进制或 {@link #UNSIGNED_PAYLOAD}）
     * @param accessKey      AccessKeyId
     * @param secretKey      SecretAccessKey
     * @param region         区域
     * @param amzDate        x-amz-date 头值（yyyyMMdd'T'HHmmss'Z'）
     * @return Authorization 头值
     */
    public static String sigV4(String method, String host, String canonicalPath,
                               Map<String, String> query, Map<String, String> headers,
                               String payloadHash, String accessKey, String secretKey,
                               String region, String amzDate) {
        TreeMap<String, String> canonicalHeaders = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.US);
            if ("host".equals(name) || "content-type".equals(name) || name.startsWith("x-amz-")) {
                canonicalHeaders.put(name, entry.getValue().trim());
            }
        }
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-date", amzDate);
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        StringBuilder signedHeadersBuilder = new StringBuilder();
        StringBuilder canonicalHeaderLines = new StringBuilder();
        for (Map.Entry<String, String> entry : canonicalHeaders.entrySet()) {
            if (signedHeadersBuilder.length() > 0) {
                signedHeadersBuilder.append(';');
                canonicalHeaderLines.append('\n');
            }
            signedHeadersBuilder.append(entry.getKey());
            canonicalHeaderLines.append(entry.getKey()).append(':').append(entry.getValue());
        }
        String signedHeaders = signedHeadersBuilder.toString();

        TreeMap<String, String> sortedQuery = new TreeMap<String, String>();
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                sortedQuery.put(Urls.encodeQuery(entry.getKey()), Urls.encodeQuery(entry.getValue()));
            }
        }
        StringBuilder queryBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedQuery.entrySet()) {
            if (queryBuilder.length() > 0) {
                queryBuilder.append('&');
            }
            queryBuilder.append(entry.getKey()).append('=').append(entry.getValue());
        }

        String canonicalRequest = method + "\n"
                + canonicalPath + "\n"
                + queryBuilder + "\n"
                + canonicalHeaderLines + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = hmacSha256(hmacSha256(hmacSha256(hmacSha256(
                ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp),
                region), "s3"), "aws4_request");
        String signature = hex(hmacSha256(signingKey, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /**
     * 计算 SigV2 签名并返回完整 Authorization 头值。
     *
     * @param prefix      Authorization 前缀（AWS / KSYUN）
     * @param accessKey   AccessKeyId
     * @param secretKey   SecretAccessKey
     * @param method      HTTP 方法
     * @param contentType Content-Type 头（无则 null）
     * @param date        Date 头值（RFC 1123）
     * @param amzHeaders  参与签名的 x-amz-* 头（键将转小写；可为 null）
     * @param bucket      bucket
     * @param key         对象 key（无编码），根路径传空串
     * @return Authorization 头值
     */
    public static String sigV2(String prefix, String accessKey, String secretKey, String method,
                               String contentType, String date, Map<String, String> amzHeaders,
                               String bucket, String key) {
        StringBuilder toSign = new StringBuilder();
        toSign.append(method).append('\n');
        toSign.append('\n');
        toSign.append(contentType == null ? "" : contentType).append('\n');
        toSign.append(date).append('\n');
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        if (amzHeaders != null) {
            for (Map.Entry<String, String> entry : amzHeaders.entrySet()) {
                sorted.put(entry.getKey().toLowerCase(Locale.US), entry.getValue().trim());
            }
        }
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            toSign.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        toSign.append('/').append(bucket);
        if (key != null && !key.isEmpty()) {
            toSign.append('/').append(key);
        }
        String signature = base64(hmacSha1(secretKey.getBytes(StandardCharsets.UTF_8),
                toSign.toString()));
        return prefix + " " + accessKey + ":" + signature;
    }

    /**
     * 当前 UTC 时间戳（SigV4 x-amz-date 格式）。
     *
     * @return yyyyMMdd'T'HHmmss'Z'
     */
    public static String amzDateNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /**
     * 当前 UTC 日期部分。
     *
     * @param amzDate x-amz-date 头值
     * @return yyyyMMdd
     */
    public static String dateStampOf(String amzDate) {
        return amzDate.substring(0, 8);
    }

    /**
     * RFC 1123 GMT 时间（SigV2 Date 头）。
     *
     * @return 如 {@code Wed, 11 Oct 2026 08:00:00 GMT}
     */
    public static String httpDateNow() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    /**
     * SHA-256 十六进制。
     *
     * @param data 数据
     * @return 十六进制串（小写）
     */
    public static String sha256Hex(byte[] data) {
        return hex(digest("SHA-256", data));
    }

    /**
     * MD5 十六进制（腾讯云 COS S3 兼容签名要求 SecretKey 先做 MD5）。
     *
     * @param data 数据
     * @return 十六进制串（小写）
     */
    public static String md5Hex(byte[] data) {
        return hex(digest("MD5", data));
    }

    /**
     * HMAC-SHA256。
     *
     * @param key  密钥
     * @param data 数据
     * @return 摘要字节
     */
    public static byte[] hmacSha256(byte[] key, String data) {
        return hmac("HmacSHA256", key, data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * HMAC-SHA1。
     *
     * @param key  密钥
     * @param data 数据
     * @return 摘要字节
     */
    public static byte[] hmacSha1(byte[] key, String data) {
        return hmac("HmacSHA1", key, data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 字节转十六进制（小写）。
     *
     * @param bytes 字节
     * @return 十六进制串
     */
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * 字节转标准 Base64。
     *
     * @param bytes 字节
     * @return Base64 串
     */
    public static String base64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少摘要算法 " + algorithm, e);
        }
    }

    private static byte[] hmac(String algorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败: " + algorithm, e);
        }
    }
}
