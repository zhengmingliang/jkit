package com.alianga.jkit.storage.internal;

import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * 多吉云管理 API 签名器测试：与 PicList / 官方文档的签名算法逐字节对齐
 * （{@code TOKEN ak:HMAC-SHA1(sk, apiPath + "\n" + body)}）。
 *
 * @author 郑明亮
 */
public class DogeSignersTest {

    private static String hmacSha1Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return AwsSigners.hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void tokenAuthMatchesReferenceAlgorithm() {
        String auth = DogeSigners.tokenAuth("ak-123", "sk-456", "/auth/tmp_token.json",
                "{\"channel\":\"OSS_FULL\",\"scopes\":[\"*\"]}".getBytes(StandardCharsets.UTF_8));
        String expected = "TOKEN ak-123:" + hmacSha1Hex("sk-456",
                "/auth/tmp_token.json\n{\"channel\":\"OSS_FULL\",\"scopes\":[\"*\"]}");
        assertEquals(expected, auth);
    }

    @Test
    public void tokenAuthWithNullBodySignsPathOnly() {
        String auth = DogeSigners.tokenAuth("ak", "sk", "/oss/bucket/list.json", null);
        String expected = "TOKEN ak:" + hmacSha1Hex("sk", "/oss/bucket/list.json\n");
        assertEquals(expected, auth);
    }

    @Test
    public void tokenAuthWithEmptyBodySignsPathOnly() {
        String auth = DogeSigners.tokenAuth("ak", "sk", "/oss/bucket/list.json", new byte[0]);
        String expected = "TOKEN ak:" + hmacSha1Hex("sk", "/oss/bucket/list.json\n");
        assertEquals(expected, auth);
    }

    @Test
    public void tokenAuthChineseBodySignsUtf8Bytes() {
        byte[] body = "{\"name\":\"桶\"}".getBytes(StandardCharsets.UTF_8);
        String auth = DogeSigners.tokenAuth("ak", "sk", "/oss/bucket/create.json", body);
        String expected = "TOKEN ak:" + hmacSha1Hex("sk",
                "/oss/bucket/create.json\n" + new String(body, StandardCharsets.UTF_8));
        assertEquals(expected, auth);
    }
}
