package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.alianga.jkit.storage.internal.AwsSigners;
import com.alianga.jkit.storage.internal.Urls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 预签名上传直传（Fake 服务器语义级）：URL 直传 → 引擎读回校验；
 * SigV4 签名正确性由 {@code MinioRealStorageTest} 真实服务锁，OSS V1 由本类独立重算锁。
 *
 * @author 郑明亮
 */
public class PresignUploadTest {

    private FakeS3Server s3Server;
    private String s3Endpoint;
    private FakeOssServer ossServer;
    private String ossEndpoint;

    @Before
    public void startServers() throws Exception {
        s3Server = new FakeS3Server();
        s3Endpoint = s3Server.start("testbucket");
        ossServer = new FakeOssServer();
        ossEndpoint = ossServer.start("testbucket");
    }

    @After
    public void stopServers() {
        s3Server.stop();
        ossServer.stop();
    }

    private FileStorage s3() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", s3Endpoint);
        values.put("bucket", "testbucket");
        values.put("accessKey", "testak");
        values.put("secretKey", "testsk");
        values.put("region", "us-east-1");
        values.put("pathStyle", "true");
        return FileStorages.of("minio", values);
    }

    private FileStorage oss() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", ossEndpoint);
        values.put("bucket", "testbucket");
        values.put("accessKey", "testak");
        values.put("secretKey", "testsk");
        values.put("basePath", "/");
        values.put("pathStyle", "true");
        return FileStorages.of("aliyun-oss", values);
    }

    @Test
    public void s3PresignUploadRoundTrip() throws Exception {
        FileStorage storage = s3();
        try {
            assertTrue("能力声明应标记预签名上传",
                    FileStorages.capabilitiesOf("s3").supportsPresignUpload());
            String url = storage.presignUpload("demo/presign.txt", Duration.ofMinutes(10));
            assertTrue("应为 SigV4 查询签名: " + url,
                    url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
            assertTrue(url.contains("X-Amz-SignedHeaders=host"));
            putByUrl(url, "PRESIGN-PUT".getBytes(StandardCharsets.UTF_8));
            assertEquals("PRESIGN-PUT",
                    new String(storage.readBytes("demo/presign.txt"), StandardCharsets.UTF_8));
        } finally {
            storage.close();
        }
    }

    @Test
    public void ossPresignUploadSignatureIndependentlyVerified() throws Exception {
        FileStorage storage = oss();
        try {
            String url = storage.presignUpload("demo/presign.txt", Duration.ofMinutes(10));
            // 独立重算 V1 签名：PUT\n\n\nExpires\n/bucket/key
            long expires = Long.parseLong(param(url, "Expires"));
            String toSign = "PUT\n\n\n" + expires + "\n/testbucket/demo/presign.txt";
            String expected = AwsSigners.base64(AwsSigners.hmacSha1(
                    "testsk".getBytes(StandardCharsets.UTF_8), toSign));
            assertEquals(expected, param(url, "Signature"));
            putByUrl(url, "OSS-PRESIGN".getBytes(StandardCharsets.UTF_8));
            assertEquals("OSS-PRESIGN",
                    new String(storage.readBytes("demo/presign.txt"), StandardCharsets.UTF_8));
        } finally {
            storage.close();
        }
    }

    private static void putByUrl(String url, byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream out = connection.getOutputStream();
        out.write(body);
        out.close();
        int status = connection.getResponseCode();
        if (status >= 400) {
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            try {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = connection.getErrorStream().read(chunk)) >= 0) {
                    error.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // 错误体读不到就用状态码
            }
            throw new AssertionError("PUT " + status + ": "
                    + new String(error.toByteArray(), StandardCharsets.UTF_8));
        }
        connection.getInputStream().close();
    }

    private static String param(String url, String name) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            if (Urls.decode(pair.substring(0, at)).equals(name)) {
                return Urls.decode(pair.substring(at + 1));
            }
        }
        throw new AssertionError("no param " + name + " in " + url);
    }
}
