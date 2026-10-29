package com.alianga.jkit.storage;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-9：StorageConfig.toString() 把含敏感子串的键脱敏，防止异常 message /
 * 日志输出 / assert 失败时凭证泄漏。
 */
public class StorageConfigTest {

    /**
     * toString 中对敏感键应输出 "***"，非敏感键应原样保留。
     */
    @Test
    public void toStringMasksSensitiveKeys() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("endpoint", "https://oss-cn-hangzhou.aliyuncs.com");
        values.put("bucket", "my-bucket");
        values.put("accessKey", "AKIAIOSFODNN7EXAMPLE");
        values.put("secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        values.put("password", "p@ssw0rd-123");
        values.put("refreshToken", "refresh-token-abc");
        values.put("sessionToken", "session-token-xyz");
        values.put("authorization", "Bearer sk-1234");
        values.put("cookie", "session=abcd; key=efgh");
        values.put("apiKey", "api-key-123");
        values.put("privateKey", "-----BEGIN RSA PRIVATE KEY-----...");
        values.put("normalField", "normal-value");

        StorageConfig config = StorageConfig.of(values);
        String s = config.toString();

        // 敏感键必须被掩码
        assertTrue("should mask accessKey, got: " + s,
                s.contains("accessKey=\"***\""));
        assertTrue("should mask secretKey, got: " + s,
                s.contains("secretKey=\"***\""));
        assertTrue("should mask password, got: " + s,
                s.contains("password=\"***\""));
        assertTrue("should mask refreshToken, got: " + s,
                s.contains("refreshToken=\"***\""));
        assertTrue("should mask sessionToken, got: " + s,
                s.contains("sessionToken=\"***\""));
        assertTrue("should mask authorization, got: " + s,
                s.contains("authorization=\"***\""));
        assertTrue("should mask cookie, got: " + s,
                s.contains("cookie=\"***\""));
        assertTrue("should mask apiKey, got: " + s,
                s.contains("apiKey=\"***\""));
        assertTrue("should mask privateKey, got: " + s,
                s.contains("privateKey=\"***\""));

        // 非敏感键应原样保留
        assertTrue("endpoint should be readable, got: " + s,
                s.contains("endpoint=\"https://oss-cn-hangzhou.aliyuncs.com\""));
        assertTrue("bucket should be readable, got: " + s,
                s.contains("bucket=\"my-bucket\""));
        assertTrue("normalField should be readable, got: " + s,
                s.contains("normalField=\"normal-value\""));

        // 敏感值本身绝不能出现在输出里
        assertFalse("secretKey value leaked: " + s,
                s.contains("wJalrXUtnFEMI"));
        assertFalse("password value leaked: " + s,
                s.contains("p@ssw0rd-123"));
        assertFalse("refreshToken value leaked: " + s,
                s.contains("refresh-token-abc"));
        assertFalse("authorization value leaked: " + s,
                s.contains("sk-1234"));
    }

    /**
     * 空配置 toString 返回 "{}"。
     */
    @Test
    public void toStringEmptyIsBracePair() {
        assertEquals("{}", StorageConfig.empty().toString());
    }

    /**
     * 大小写不敏感：accesskey（无大写 K）/ SECRETKEY（全大写）/ PassWord 等都应被识别。
     */
    @Test
    public void toStringMasksCaseInsensitive() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accesskey", "lowercase-value");
        values.put("SECRETKEY", "uppercase-value");
        values.put("PassWord", "mixed-case-value");
        values.put("SESSION_TOKEN", "snake-case-value");

        StorageConfig config = StorageConfig.of(values);
        String s = config.toString();

        assertTrue(s.contains("accesskey=\"***\""));
        assertTrue(s.contains("SECRETKEY=\"***\""));
        assertTrue(s.contains("PassWord=\"***\""));
        assertTrue(s.contains("SESSION_TOKEN=\"***\""));

        assertFalse("accesskey value leaked", s.contains("lowercase-value"));
        assertFalse("SECRETKEY value leaked", s.contains("uppercase-value"));
        assertFalse("PassWord value leaked", s.contains("mixed-case-value"));
        assertFalse("SESSION_TOKEN value leaked", s.contains("snake-case-value"));
    }

    /**
     * null 值键输出 "null"，不脱敏。
     */
    @Test
    public void toStringNullValueShowsNull() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("password", null);
        values.put("endpoint", null);

        StorageConfig config = StorageConfig.of(values);
        String s = config.toString();
        // null 值不管键是否敏感都应输出 null
        assertTrue(s.contains("password=null"));
        assertTrue(s.contains("endpoint=null"));
    }

    /**
     * isSensitiveKey 边界：非敏感键不能误判。
     */
    @Test
    public void isSensitiveKeyDoesNotOverMatch() {
        assertFalse(StorageConfig.isSensitiveKey("endpoint"));
        assertFalse(StorageConfig.isSensitiveKey("bucket"));
        assertFalse(StorageConfig.isSensitiveKey("basePath"));
        assertFalse(StorageConfig.isSensitiveKey("root"));
        assertFalse(StorageConfig.isSensitiveKey("user"));            // user 不含任何敏感子串
        assertFalse(StorageConfig.isSensitiveKey(null));
        assertFalse(StorageConfig.isSensitiveKey(""));
    }

    /**
     * isSensitiveKey 边界：所有要捕获的敏感键。
     */
    @Test
    public void isSensitiveKeyMatchesExpected() {
        assertTrue(StorageConfig.isSensitiveKey("password"));
        assertTrue(StorageConfig.isSensitiveKey("passwd"));
        assertTrue(StorageConfig.isSensitiveKey("secret"));
        assertTrue(StorageConfig.isSensitiveKey("secretKey"));
        assertTrue(StorageConfig.isSensitiveKey("token"));
        assertTrue(StorageConfig.isSensitiveKey("accessToken"));
        assertTrue(StorageConfig.isSensitiveKey("refreshToken"));
        assertTrue(StorageConfig.isSensitiveKey("sessionToken"));
        assertTrue(StorageConfig.isSensitiveKey("credential"));
        assertTrue(StorageConfig.isSensitiveKey("apiKey"));
        assertTrue(StorageConfig.isSensitiveKey("api_key"));
        assertTrue(StorageConfig.isSensitiveKey("accessKey"));
        assertTrue(StorageConfig.isSensitiveKey("access_key"));
        assertTrue(StorageConfig.isSensitiveKey("privateKey"));
        assertTrue(StorageConfig.isSensitiveKey("private_key"));
        assertTrue(StorageConfig.isSensitiveKey("authorization"));
        assertTrue(StorageConfig.isSensitiveKey("cookie"));
        assertTrue(StorageConfig.isSensitiveKey("session"));
        assertTrue(StorageConfig.isSensitiveKey("sessionToken"));
    }

    /**
     * toString 调用多次结果一致（幂等）。
     */
    @Test
    public void toStringIsIdempotent() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("secretKey", "s3cr3t");
        values.put("endpoint", "http://x");
        StorageConfig config = StorageConfig.of(values);
        String first = config.toString();
        String second = config.toString();
        assertEquals(first, second);
    }

    /**
     * asMap 不受 toString 影响：完整数据仍可读取。
     */
    @Test
    public void asMapUnaffectedByToString() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("secretKey", "s3cr3t");
        values.put("endpoint", "http://x");
        StorageConfig config = StorageConfig.of(values);

        config.toString();   // 不应污染内部状态

        assertEquals("s3cr3t", config.get("secretKey"));
        assertEquals("http://x", config.get("endpoint"));
        assertNotNull(config.asMap());
        assertEquals("s3cr3t", config.asMap().get("secretKey"));
    }
}
