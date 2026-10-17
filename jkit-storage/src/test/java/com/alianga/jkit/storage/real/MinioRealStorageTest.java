package com.alianga.jkit.storage.real;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.AwsSigners;
import com.alianga.jkit.storage.internal.HttpCore;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真实 S3 服务集成测试：在真正的 S3 实现上验证 {@code S3FileStorage} 的
 * SigV4 签名、列表语义、目录占位与 Multipart 上传——Fake 服务器按文档模拟，
 * 抓不出真实实现（AWS / MinIO / RustFS 等）的严格校验差异。
 *
 * <p>服务来源二选一，都不满足时整套用例自动跳过（Assume），CI 不受影响：</p>
 * <ol>
 *   <li><b>MinIO 单二进制</b>（首选）：PATH 中有 {@code minio}，或 {@code ~/minio/minio}；
 *       测试自行启动单机模式并等待就绪；</li>
 *   <li><b>已运行的 RustFS / 其它 S3 兼容服务</b>：通过系统属性
 *       {@code jkit.test.s3.endpoint / jkit.test.s3.accessKey / jkit.test.s3.secretKey /
 *       jkit.test.s3.region} 或环境变量 {@code RUSTFS_ENDPOINT / RUSTFS_ACCESS_KEY /
 *       RUSTFS_SECRET_KEY / RUSTFS_REGION} 指定，默认
 *       {@code http://127.0.0.1:9000 + testuser/testpassword}（本机 docker compose 的 RustFS）。
 *       以 PutBucket 探测可达性，不可达即跳过。</li>
 * </ol>
 *
 * <p>桶通过 PutBucket API 创建（两种服务端行为一致），桶名含纳秒时间戳，
 * 结束时清空并删除；清理失败只留垃圾不影响重跑。</p>
 *
 * @author 郑明亮
 */
public class MinioRealStorageTest {
    private static final long MB = 1024L * 1024L;

    private static Process server;
    private static Path dataDir;
    private static String endpoint;
    private static String accessKey;
    private static String secretKey;
    private static String region;
    private static String bucket;

    /** 引擎共用的行为矩阵：上传下载中文路径 / 列表 / 目录 / 重命名复制 / 删除 / NOT_FOUND。 */
    private static void exercise(FileStorage fs) throws Exception {
        byte[] data = "你好，真实 S3！real-object".getBytes(StandardCharsets.UTF_8);
        fs.upload("docs/中文说明.txt", data);
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(fs.readBytes("docs/中文说明.txt"), StandardCharsets.UTF_8));
        fs.upload("docs/inner/deep.bin", new byte[]{9, 8, 7});
        fs.upload("root.txt", "r".getBytes(StandardCharsets.UTF_8));

        assertTrue(fs.exists("docs"));
        assertTrue(fs.exists("docs/中文说明.txt"));
        assertFalse(fs.exists("no/such/object"));

        StorageEntry entry = fs.stat("docs/中文说明.txt");
        assertEquals(data.length, entry.size());
        assertFalse(entry.isDirectory());

        fs.mkdir("docs/empty");
        fs.mkdir("docs/empty");
        assertTrue(fs.exists("docs/empty"));

        List<StorageEntry> list = fs.list("docs");
        assertTrue(names(list).contains("中文说明.txt"));
        assertTrue(names(list).contains("inner"));
        assertTrue(names(list).contains("empty"));

        fs.rename("docs/inner/deep.bin", "docs/inner/deep2.bin");
        assertFalse(fs.exists("docs/inner/deep.bin"));
        assertTrue(fs.exists("docs/inner/deep2.bin"));
        fs.copy("docs/inner/deep2.bin", "docs/inner/deep3.bin");
        assertEquals(3, fs.readBytes("docs/inner/deep3.bin").length);

        fs.rename("docs/inner", "docs/inner2");
        assertTrue(fs.exists("docs/inner2/deep2.bin"));
        assertFalse(fs.exists("docs/inner"));
        fs.copy("docs/inner2", "docs/inner3");
        assertTrue(fs.exists("docs/inner3/deep2.bin"));

        fs.upload("empty.txt", new byte[0]);
        assertEquals(0, fs.stat("empty.txt").size());
        byte[] big = new byte[1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        fs.upload("big/large.bin", big);
        byte[] roundTrip = fs.readBytes("big/large.bin");
        assertEquals(big.length, roundTrip.length);
        assertEquals(big[123456], roundTrip[123456]);

        fs.delete("docs/inner3/deep2.bin");
        assertFalse(fs.exists("docs/inner3/deep2.bin"));
        fs.deleteDir("docs/inner3");
        assertFalse(fs.exists("docs/inner3"));

        try {
            fs.stat("ghost.txt");
            throw new AssertionError("应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    private static List<String> names(List<StorageEntry> entries) {
        List<String> names = new ArrayList<String>();
        for (StorageEntry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    private static StorageEntry byName(List<StorageEntry> entries, String name) {
        for (StorageEntry entry : entries) {
            if (entry.name().equals(name)) {
                return entry;
            }
        }
        throw new AssertionError("列表中缺少 " + name);
    }

    @BeforeClass
    public static void startServer() throws Exception {
        String binary = locateBinary();
        if (binary != null) {
            int port = freePort();
            dataDir = Files.createTempDirectory("minio-real");
            List<String> command = new ArrayList<String>();
            command.add(binary);
            command.add("server");
            command.add(dataDir.toString());
            command.add("--address");
            command.add("127.0.0.1:" + port);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("MINIO_ROOT_USER", "tester");
            builder.environment().put("MINIO_ROOT_PASSWORD", "testpass123");
            builder.redirectErrorStream(true);
            builder.redirectOutput(dataDir.resolve("server.log").toFile());
            server = builder.start();
            Assume.assumeTrue("MinIO 未能在 60s 内就绪，跳过真实 S3 集成测试",
                    waitPort(port, 60_000));
            endpoint = "http://127.0.0.1:" + port;
            accessKey = "tester";
            secretKey = "testpass123";
            region = "us-east-1";
        } else {
            endpoint = setting("jkit.test.s3.endpoint", "RUSTFS_ENDPOINT",
                    "http://127.0.0.1:9000");
            accessKey = setting("jkit.test.s3.accessKey", "RUSTFS_ACCESS_KEY", "testuser");
            secretKey = setting("jkit.test.s3.secretKey", "RUSTFS_SECRET_KEY", "testpassword");
            region = setting("jkit.test.s3.region", "RUSTFS_REGION", "us-east-1");
        }
        bucket = "jkit-s3-" + System.nanoTime();
        int status = signedRequest("PUT", "/" + bucket);
        Assume.assumeTrue("真实 S3 服务不可达或拒绝访问（HTTP " + status + "），跳过集成测试",
                status == 200 || status == 409);
    }

    @AfterClass
    public static void stopServer() {
        try {
            FileStorage fs = engine(0L);
            for (StorageEntry entry : fs.list("")) {
                if (entry.isDirectory()) {
                    fs.deleteDir(entry.path());
                } else {
                    fs.delete(entry.path());
                }
            }
        } catch (RuntimeException ignore) {
            // 清空失败不影响删除桶的尝试
        }
        signedRequest("DELETE", "/" + bucket);
        if (server != null) {
            server.destroy();
        }
        if (dataDir != null) {
            deleteRecursively(dataDir);
        }
    }

    /** ListObjectsV2 引擎（fetch-owner）。 */
    private static FileStorage engineV2() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("bucket", bucket);
        values.put("accessKey", accessKey);
        values.put("secretKey", secretKey);
        values.put("pathStyle", "true");
        values.put("listVersion", "2");
        return FileStorages.of("generic-s3", values);
    }

    /** 标准配置引擎（无分片）。 */
    private static FileStorage engine(long partSize) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("bucket", bucket);
        values.put("accessKey", accessKey);
        values.put("secretKey", secretKey);
        values.put("pathStyle", "true");
        if (partSize > 0L) {
            values.put("partSize", String.valueOf(partSize));
            values.put("multipartThreshold", String.valueOf(partSize));
        }
        return FileStorages.of("generic-s3", values);
    }

    private static String locateBinary() throws IOException {
        Process which = new ProcessBuilder("bash", "-c",
                "command -v minio || { test -x \"$HOME/minio/minio\" && echo \"$HOME/minio/minio\"; }")
                .redirectErrorStream(true).start();
        try {
            if (which.waitFor() == 0) {
                String path = new String(which.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                return path.isEmpty() ? null : path;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static String setting(String key, String env, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** 直签的桶级请求（PutBucket / DeleteBucket），网络异常返回 -1。 */
    private static int signedRequest(String method, String path) {
        try {
            String host = hostOf(endpoint);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            String amzDate = AwsSigners.amzDateNow();
            String payloadHash = AwsSigners.sha256Hex(new byte[0]);
            headers.put("x-amz-date", amzDate);
            headers.put("x-amz-content-sha256", payloadHash);
            headers.put("Authorization", AwsSigners.sigV4(method, host, path, null, headers,
                    payloadHash, accessKey, secretKey, region, amzDate));
            HttpCore.Request request = new HttpCore.Request(method, endpoint + path);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
            HttpCore.Response response = HttpCore.execute(request);
            int status = response.status();
            response.close();
            return status;
        } catch (StorageException e) {
            return -1;
        }
    }

    private static String hostOf(String raw) {
        String authority = raw.replaceFirst("^https?://", "");
        int slash = authority.indexOf('/');
        return slash < 0 ? authority : authority.substring(0, slash);
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder())
                        .toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignore) {
            // 临时目录清理失败不影响测试结果
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean waitPort(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server != null && !server.isAlive()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException e) {
                Thread.sleep(400);
            }
        }
        return false;
    }

    @Test
    public void realServerBehaviorMatrix() throws Exception {
        exercise(engine(0L));
    }

    /** ListObjectsV2 + fetch-owner：文件条目应带对象 owner（MinIO/RustFS/AWS 行为一致）。 */
    @Test
    public void listV2ReturnsObjectOwner() {
        FileStorage fs = engineV2();
        fs.upload("owner-dir/中文.txt", "owner".getBytes(StandardCharsets.UTF_8));
        List<StorageEntry> list = fs.list("owner-dir");
        StorageEntry file = byName(list, "中文.txt");
        String owner = file.attribute(StorageEntry.ATTR_OWNER);
        assertTrue("V2 fetch-owner 列表条目应带对象 owner", owner != null && !owner.isEmpty());
        fs.deleteDir("owner-dir");
    }

    /** 目录占位对象：mkdir 后 list / exists 都能看到目录。 */
    @Test
    public void directoryPlaceholderVisible() throws Exception {
        FileStorage fs = engine(0L);
        fs.mkdir("占位目录/子目录");
        assertTrue(fs.exists("占位目录"));
        List<StorageEntry> list = fs.list("");
        assertTrue(names(list).contains("占位目录"));
        assertTrue(names(fs.list("占位目录")).contains("子目录"));
        fs.deleteDir("占位目录");
        assertFalse(fs.exists("占位目录"));
    }

    /** 已知 12MB（3×5MB 分片 + 尾片 2MB）：字节精确回读。 */
    @Test
    public void multipartKnownSizeThreeParts() throws Exception {
        FileStorage fs = engine(5L * MB);
        byte[] data = new byte[(int) (12L * MB)];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 17 + i >> 5);
        }
        fs.upload("mp/big.bin", data);
        assertEquals(12L * MB, fs.stat("mp/big.bin").size());
        assertArrayEquals(data, fs.readBytes("mp/big.bin"));
    }

    /** 未知长度 6MB 流（2×5MB 分片 + 尾片 1MB）：字节精确回读。 */
    @Test
    public void multipartUnknownSizeTwoParts() throws Exception {
        FileStorage fs = engine(5L * MB);
        byte[] data = new byte[(int) (6L * MB)];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 23 + i >> 4);
        }
        fs.upload("mp/unknown.bin", new ByteArrayInputStream(data), -1L);
        assertArrayEquals(data, fs.readBytes("mp/unknown.bin"));
    }

    @Test
    public void wrongSecretKeyRejected() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("bucket", bucket);
        values.put("accessKey", accessKey);
        values.put("secretKey", "wrong-secret-key");
        values.put("pathStyle", "true");
        try {
            FileStorages.of("generic-s3", values).list("");
            throw new AssertionError("错误密钥应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }
}
