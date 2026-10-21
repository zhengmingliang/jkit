package com.alianga.jkit.storage.internal;

import java.nio.charset.StandardCharsets;

/**
 * 多吉云管理 API 签名器：{@code Authorization: TOKEN <accessKey>:<签名>}，
 * 签名是 {@code HMAC-SHA1(secretKey, apiPath + "\n" + body)} 的十六进制。
 *
 * <p>覆盖管理 API 的两种请求体形态：JSON（{@code tmp_token.json} 等带结构的接口）
 * 与表单编码（{@code bucket/list.json} 等简单接口）——签名时用发送的原始 body 字节。</p>
 *
 * @author 郑明亮
 */
public final class DogeSigners {
    private DogeSigners() {
    }

    /**
     * 计算签名（十六进制小写）。
     *
     * @param secretKey SecretKey
     * @param apiPath   API 路径（如 {@code /auth/tmp_token.json}）
     * @param body      原始请求体字节（与实际发送的完全一致，null 视为空）
     * @return 签名串
     */
    public static String signature(String secretKey, String apiPath, byte[] body) {
        byte[] pathBytes = apiPath.getBytes(StandardCharsets.UTF_8);
        byte[] data = body == null ? new byte[0] : body;
        byte[] toSign = new byte[pathBytes.length + 1 + data.length];
        System.arraycopy(pathBytes, 0, toSign, 0, pathBytes.length);
        toSign[pathBytes.length] = '\n';
        System.arraycopy(data, 0, toSign, pathBytes.length + 1, data.length);
        return AwsSigners.hex(AwsSigners.hmacSha1(
                secretKey.getBytes(StandardCharsets.UTF_8),
                new String(toSign, StandardCharsets.UTF_8)));
    }

    /**
     * 计算多吉云管理 API 的 Authorization 头值。
     *
     * @param accessKey AccessKey（多吉云控制台密钥管理获取）
     * @param secretKey SecretKey
     * @param apiPath   API 路径
     * @param body      原始请求体字节
     * @return Authorization 头值
     */
    public static String tokenAuth(String accessKey, String secretKey, String apiPath, byte[] body) {
        return "TOKEN " + accessKey + ":" + signature(secretKey, apiPath, body);
    }
}
