package com.alianga.jkit.storage.internal;

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
     * 计算多吉云管理 API 的 Authorization 头值。
     *
     * @param accessKey AccessKey（多吉云控制台密钥管理获取）
     * @param secretKey SecretKey
     * @param apiPath   API 路径（如 {@code /auth/tmp_token.json}）
     * @param body      原始请求体字节（与实际发送的完全一致）
     * @return Authorization 头值
     */
    public static String tokenAuth(String accessKey, String secretKey, String apiPath, byte[] body) {
        byte[] toSign = new byte[apiPath.length() + 1
                + (body == null ? 0 : body.length)];
        System.arraycopy(apiPath.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0,
                toSign, 0, apiPath.length());
        toSign[apiPath.length()] = '\n';
        if (body != null && body.length > 0) {
            System.arraycopy(body, 0, toSign, apiPath.length() + 1, body.length);
        }
        return "TOKEN " + accessKey + ":"
                + AwsSigners.hex(AwsSigners.hmacSha1(
                        secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        new String(toSign, java.nio.charset.StandardCharsets.UTF_8)));
    }
}
