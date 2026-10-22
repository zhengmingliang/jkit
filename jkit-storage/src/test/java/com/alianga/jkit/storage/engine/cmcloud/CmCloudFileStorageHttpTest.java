package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CmCloudFileStorage} 对真实 REST 端点的行为：列表 / 下载 / 断点续传 / 上传 /
 * 建目录 / 删除 / 复制 / 分享链接，以及错误码映射与「预签名通道不带业务鉴权头」这条硬约束。
 *
 * <p>用 JDK 内置 {@code com.sun.net.httpserver} 起一个本地 <b>HTTPS</b> 桩服务
 * （{@link CmCloudConfig} 要求网关必须 https，所以桩也得是 https：证书由 keytool 现场生成
 * 自签名证书，没有 keytool 的环境整类跳过）。不通外网、不用真实票据，
 * 同时能精确断言「每个接口发了什么」与「PUT 预签名地址到底带了什么头」。</p>
 *
 * @author 郑明亮
 */
public class CmCloudFileStorageHttpTest {

    private static final String PREFIX = CmCloudConfig.PERSONAL_SAAS_PREFIX;

    /** 桩服务收到的调用：{方法, 路径, 请求体, 请求头 JSON（键已小写）}。 */
    private static final List<String[]> CALLS = new ArrayList<>();
    /** 当前用例的路由（根 handler 转发到这里）。 */
    private static final AtomicReference<HttpHandler> ROUTE = new AtomicReference<>();
    /** 最近一次请求的原始请求体（流只可读一次，路由与断言都从这里取）。 */
    private static final AtomicReference<byte[]> LAST_BODY = new AtomicReference<>();

    private static HttpsServer server;
    private static String baseUrl;
    private static SSLContext savedDefaultSsl;
    private static javax.net.ssl.SSLSocketFactory savedSocketFactory;

    @BeforeClass
    public static void startStub() throws Exception {
        Path keystore = Files.createTempFile("jkit-cmcloud-https", ".p12");
        Files.deleteIfExists(keystore); // keytool 拒绝写入已存在的空 PKCS12 文件
        String password = "changeit";
        String dname = "CN=localhost, OU=jkit-test, O=alianga, L=SH, ST=SH, C=CN";
        Process keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "cmcloud-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", dname, "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password)
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assume.assumeTrue("keytool 生成自签名证书失败：" + output, keytool.waitFor() == 0);

        char[] pass = password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, pass);
        }
        Files.deleteIfExists(keystore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(store, pass);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters params) {
                params.setNeedClientAuth(false);
            }
        });
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            // 请求体只读一次（流不可重读）：先落到 LAST_BODY，路由与断言都从这里取
            byte[] raw = exchange.getRequestBody().readAllBytes();
            LAST_BODY.set(raw);
            String body = new String(raw, StandardCharsets.UTF_8);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders()
                    .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), new ArrayList<>(v)));
            CALLS.add(new String[]{method, path, body, JSON.toJsonString(headers)});
            HttpHandler target = ROUTE.get();
            if (target == null) {
                write(exchange, 599, new byte[0]);
                return;
            }
            target.handle(exchange);
        });
        server.start();
        baseUrl = "https://localhost:" + server.getAddress().getPort();

        // 让 JVM 默认 SSLContext 信任桩服务的自签名证书（JDK HttpClient 取默认上下文）
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(store);
        savedDefaultSsl = SSLContext.getDefault();
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);
        SSLContext.setDefault(clientContext);
        // HttpCore 走 HttpURLConnection：默认 socket 工厂是缓存单例，setDefault 之外必须显式替换
        savedSocketFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.getSocketFactory());
    }

    @AfterClass
    public static void stopStub() {
        SSLContext.setDefault(savedDefaultSsl);
        if (savedSocketFactory != null) {
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(savedSocketFactory);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Before
    public void clearCalls() {
        CALLS.clear();
        ROUTE.set(null);
    }

    // ------------------------------------------------------------------ 列目录

    @Test
    public void listRootSortsDirsFirstAndCarriesFileId() {
        route(exchange -> json(exchange, 200, env(TestMaps.map(
                "items", Arrays.asList(
                        itemFile("周报.docx", "f-week", 1234, 1717200000000L),
                        itemDir("备份", "d-backup", 0L),
                        itemDir("AI空间", "d-ai", 1717100000000L)),
                "nextPageCursor", ""))));
        try (FileStorage storage = open()) {
            List<StorageEntry> entries = storage.list("");
            assertEquals(Arrays.asList("AI空间", "备份", "周报.docx"), entries.stream()
                    .map(StorageEntry::name).collect(java.util.stream.Collectors.toList()));

            StorageEntry ai = entries.get(0);
            assertTrue(ai.isDirectory());
            // 与 Git 引擎同一约定：根的直接子项不带前导斜杠，下一层才是 "/父/子"
            assertEquals("AI空间", ai.path());
            assertEquals(1717100000000L, ai.lastModified());
            assertEquals("d-ai", ai.attribute(CmCloudFileStorage.ATTR_CM_FILE_ID));
            assertEquals("/AI空间", ai.attribute(CmCloudFileStorage.ATTR_CM_CLOUD_PATH));

            StorageEntry week = entries.get(2);
            assertFalse(week.isDirectory());
            assertEquals(1234, week.size());
            assertEquals("f-week", week.etag());
            // 根目录请求的 parentFileId 是平台约定的个人云根
            assertEquals("/", bodyOf("/file/list").get("parentFileId"));
        }
    }

    @Test
    public void listNestedResolvesFolderIdsLayerByLayer() {
        route(exchange -> {
            String parent = String.valueOf(body(exchange).get("parentFileId"));
            Map<String, Object> data;
            if ("/".equals(parent)) {
                data = TestMaps.map("items", Arrays.asList(itemDir("备份", "d-backup", 0L)));
            } else if ("d-backup".equals(parent)) {
                data = TestMaps.map("items", Arrays.asList(itemDir("2026", "d-2026", 0L)));
            } else if ("d-2026".equals(parent)) {
                data = TestMaps.map("items", Arrays.asList(itemFile("log.txt", "f-log", 9, 0L)));
            } else {
                data = TestMaps.map();
            }
            json(exchange, 200, env(data));
        });
        try (FileStorage storage = open()) {
            List<StorageEntry> entries = storage.list("备份/2026");
            assertEquals(1, entries.size());
            assertEquals("log.txt", entries.get(0).name());
            assertEquals("/备份/2026/log.txt",
                    entries.get(0).attribute(CmCloudFileStorage.ATTR_CM_CLOUD_PATH));
            // 三层目录解析：/ → d-backup → d-2026（最后一层才是真正的列内容请求）
            assertEquals(Arrays.asList("/", "d-backup", "d-2026"), parentIdsAsked());
        }
    }

    @Test
    public void listFollowsPageCursorUntilExhausted() {
        route(exchange -> {
            Object cursor = pageCursor(exchange);
            Map<String, Object> data;
            if (cursor == null) {
                data = TestMaps.map("items", Arrays.asList(itemFile("a.txt", "f-a", 1, 0L)),
                        "nextPageCursor", "cursor-2");
            } else if ("cursor-2".equals(cursor)) {
                data = TestMaps.map("items", Arrays.asList(itemFile("b.txt", "f-b", 2, 0L)),
                        "nextPageCursor", "cursor-3");
            } else {
                data = TestMaps.map("items", Arrays.asList(itemFile("c.txt", "f-c", 3, 0L)));
            }
            json(exchange, 200, env(data));
        });
        try (FileStorage storage = open()) {
            assertEquals(Arrays.asList("a.txt", "b.txt", "c.txt"), storage.list("").stream()
                    .map(StorageEntry::name).collect(java.util.stream.Collectors.toList()));
            assertEquals(3, apiCalls("/file/list").size());
        }
    }

    @Test
    public void statMissingChildIsNotFoundAndExistsFalse() {
        route(exchange -> json(exchange, 200, env(TestMaps.map("items", Collections.emptyList()))));
        try (FileStorage storage = open()) {
            assertFalse(storage.exists("不存在的文件.txt"));
            try {
                storage.stat("不存在的文件.txt");
                fail("应抛 NOT_FOUND");
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.NOT_FOUND, e.code());
            }
        }
    }

    @Test
    public void statRootIsAlwaysPresentWithoutNetwork() {
        try (FileStorage storage = open()) {
            StorageEntry root = storage.stat("");
            assertTrue(root.isDirectory());
            assertEquals("", root.path());
            assertTrue("根目录不应触网", CALLS.isEmpty());
        }
    }

    // ------------------------------------------------------------------ 下载

    @Test
    public void downloadUsesPresignedUrlAndReturnsContent() {
        byte[] content = "hello caiyun".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map("url", baseUrl + "/raw/note.txt")));
            } else if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("note.txt", "f-note", content.length, 0L)))));
            } else if ("/raw/note.txt".equals(path(exchange))) {
                write(exchange, 200, content);
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            storage.download("note.txt", out);
            assertArrayEquals(content, out.toByteArray());
            assertTrue("预签名地址支持 Range（断点续传）", storage.supportsRange());
            assertEquals(1, apiCalls("/file/getDownloadUrl").size());
        }
    }

    @Test
    public void downloadRangeSendsRangeHeaderWithoutAuthHeaders() {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map("url", baseUrl + "/raw/nums.txt")));
            } else if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("nums.txt", "f-nums", content.length, 0L)))));
            } else if ("/raw/nums.txt".equals(path(exchange))) {
                assertEquals("bytes=4-", exchange.getRequestHeaders().getFirst("Range"));
                write(exchange, 200, "456789".getBytes(StandardCharsets.UTF_8));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            storage.download("nums.txt", 4, out);
            assertEquals("456789", new String(out.toByteArray(), StandardCharsets.UTF_8));
            String[] rawCall = apiCalls("/raw/nums.txt").get(0);
            assertNoBusinessHeaders(rawCall[3]);
        }
    }

    @Test
    public void downloadRootRejected() {
        try (FileStorage storage = open()) {
            storage.download("", new ByteArrayOutputStream());
            fail("根目录下载应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void downloadWithoutUrlIsIoError() {
        route(exchange -> {
            if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map()));
            } else if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("x.txt", "f-x", 1, 0L)))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.download("x.txt", new ByteArrayOutputStream());
            fail("平台未返回下载地址应报错");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.IO_ERROR, e.code());
            assertTrue(e.getMessage().contains("下载地址"));
        }
    }

    @Test
    public void presignClampsTtlAndReturnsUrl() {
        route(exchange -> {
            if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map("url", "https://share.example/abc")));
            } else if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("share.txt", "f-share", 1, 0L)))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            assertEquals("https://share.example/abc", storage.presign("share.txt", Duration.ofDays(30)));
            assertEquals(86400L, ((Number) bodyOf("/file/getDownloadUrl").get("expireSec")).longValue());
        }
    }

    // ------------------------------------------------------------------ 上传

    @Test
    public void uploadRunsCreatePutCompleteAndSkipsAuthOnPresignedPut() {
        byte[] content = "hello caiyun".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map(
                        "fileId", "f-new",
                        "uploadId", "u-new",
                        "partInfos", Arrays.asList(TestMaps.map(
                                "partNumber", 1,
                                "partSize", content.length,
                                "uploadUrl", baseUrl + "/put/f-new")))));
            } else if ("/put/f-new".equals(path(exchange))) {
                write(exchange, 200, new byte[0]);
            } else if (isApi(exchange, "/file/complete")) {
                json(exchange, 200, env(TestMaps.map("fileId", "f-new")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("note.txt", content);
            Map<String, Object> created = bodyOf("/file/create");
            assertEquals("note.txt", created.get("name"));
            assertEquals("file", created.get("type"));
            assertEquals("/", created.get("parentFileId"));
            assertEquals(12L, ((Number) created.get("size")).longValue());

            String[] putCall = apiCalls("/put/f-new").get(0);
            assertNotNull("应 PUT 到预签名地址", putCall);
            assertNoBusinessHeaders(putCall[3]);
            // 平台二阶段上传契约：Content-Type 固定 octet-stream、显式 Date、
            // Content-Length 与请求体严格一致（chunked 会被预签名校验拒绝）
            assertTrue("PUT 应带 application/octet-stream：" + putCall[3],
                    putCall[3].contains("\"content-type\":[\"application/octet-stream\"]"));
            assertTrue("PUT 应带 Date 头", putCall[3].contains("\"date\""));
            assertEquals("Content-Length 应与请求体一致", 12,
                    Integer.parseInt(firstHeader(putCall[3], "content-length")));
            // PUT 用例内 LAST_BODY 就是 PUT 的请求体（本用例的最后一个请求）；
            // 但 complete 也在本用例里发过，改用 CALLS 里的第一手记录校验内容
            assertArrayEquals(content, putCall[2].getBytes(StandardCharsets.UTF_8));

            Map<String, Object> completed = bodyOf("/file/complete");
            assertEquals("f-new", completed.get("fileId"));
            assertEquals("u-new", completed.get("uploadId"));
            assertEquals("sha256", completed.get("contentHashAlgorithm"));
            assertEquals(sha256(content), completed.get("contentHash"));
        }
    }

    @Test
    public void uploadLargeFileChunksBy5MiBWithContentTypePerPart() {
        // 12MiB：切成 3 片（5MiB + 5MiB + 2MiB），逐片 PUT
        byte[] content = new byte[12 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        route(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map(
                        "fileId", "f-big",
                        "uploadId", "u-big",
                        "partInfos", Arrays.asList(
                                TestMaps.map("partNumber", 1, "partSize", 5 * 1024 * 1024,
                                        "uploadUrl", baseUrl + "/put/big-1"),
                                TestMaps.map("partNumber", 2, "partSize", 5 * 1024 * 1024,
                                        "uploadUrl", baseUrl + "/put/big-2"),
                                TestMaps.map("partNumber", 3, "partSize", 2 * 1024 * 1024,
                                        "uploadUrl", baseUrl + "/put/big-3")))));
            } else if (path(exchange).startsWith("/put/big-")) {
                write(exchange, 200, new byte[0]);
            } else if (isApi(exchange, "/file/complete")) {
                json(exchange, 200, env(TestMaps.map("fileId", "f-big")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("big.bin", content);
            assertEquals("应逐片 PUT 3 次", 3, apiCalls("/put/big-").size());
            // 逐片长度用 content-length 头断言；二进制内容不进 String 记录（会乱码），
            // 拼接一致性由「分片读满」逻辑与单片用例的字节级断言覆盖
            int index = 0;
            for (String[] put : apiCalls("/put/big-")) {
                long declared = Long.parseLong(firstHeader(put[3], "content-length"));
                long expected = index == 2 ? 2L * 1024 * 1024 : 5L * 1024 * 1024;
                assertEquals("第 " + (index + 1) + " 片的 Content-Length", expected, declared);
                index++;
            }
            // 每片都走 octet-stream + Date
            for (String[] put : apiCalls("/put/big-")) {
                assertTrue("分片 PUT 应带 octet-stream：" + put[3],
                        put[3].contains("\"content-type\":[\"application/octet-stream\"]"));
                assertTrue("分片 PUT 应带 Date 头", put[3].contains("\"date\""));
                assertNoBusinessHeaders(put[3]);
            }
            Map<String, Object> completed = bodyOf("/file/complete");
            assertEquals("u-big", completed.get("uploadId"));
            assertEquals(sha256(content), completed.get("contentHash"));
        }
    }

    /** 从桩记录的请求头 JSON 里取第一个头值（桩已把键转小写）。 */
    private static String firstHeader(String headerJson, String key) {
        String marker = "\"" + key + "\":[";
        int at = headerJson.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int start = headerJson.indexOf('"', at + marker.length());
        int end = headerJson.indexOf('"', start + 1);
        return headerJson.substring(start + 1, end);
    }

    @Test
    public void uploadMissingPartUrlFallsBackToGetUploadUrl() {
        byte[] content = "small".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (isApi(exchange, "/file/create")) {
                // create 响应缺 partInfos 上传地址：引擎应走 getUploadUrl 补取
                json(exchange, 200, env(TestMaps.map("fileId", "f-gap", "uploadId", "u-gap",
                        "partInfos", Arrays.asList(TestMaps.map("partNumber", 1)))));
            } else if (isApi(exchange, "/file/getUploadUrl")) {
                json(exchange, 200, env(TestMaps.map("partInfos", Arrays.asList(TestMaps.map(
                        "partNumber", 1, "partSize", content.length,
                        "uploadUrl", baseUrl + "/put/gap")))));
            } else if ("/put/gap".equals(path(exchange))) {
                write(exchange, 200, new byte[0]);
            } else if (isApi(exchange, "/file/complete")) {
                json(exchange, 200, env(TestMaps.map()));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("gap.txt", content);
            Map<String, Object> urlBody = bodyOf("/file/getUploadUrl");
            assertEquals("f-gap", urlBody.get("fileId"));
            assertEquals("u-gap", urlBody.get("uploadId"));
            assertEquals(1, apiCalls("/put/gap").size());
        }
    }

    @Test
    public void uploadRapidUploadSkipsTransferAndComplete() {
        byte[] content = "dedup me".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map(
                        "fileId", "f-dup",
                        "rapidUpload", true,
                        "partInfos", Arrays.asList(TestMaps.map("partNumber", 1,
                                "uploadUrl", baseUrl + "/put/never")))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("dup.txt", content);
            assertTrue("秒传不得再 PUT", apiCalls("/put/never").isEmpty());
            assertTrue("秒传不得再 complete", apiCalls("/file/complete").isEmpty());
        }
    }

    @Test
    public void uploadRejectsEmptyFile() {
        try (FileStorage storage = open()) {
            storage.upload("empty.txt", new byte[0]);
            fail("0 字节文件应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
            assertTrue(e.getMessage().contains("0 字节"));
        }
    }

    @Test
    public void uploadForbiddenCarriesObjectStorageXmlDetail() {
        byte[] content = "denied".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map(
                        "fileId", "f-denied",
                        "uploadId", "u-denied",
                        "partInfos", Arrays.asList(TestMaps.map(
                                "partNumber", 1, "partSize", content.length,
                                "uploadUrl", baseUrl + "/put/denied")))));
            } else if ("/put/denied".equals(path(exchange))) {
                // 对象存储 403：平铺 XML 错误（签名不匹配 / 越权等场景的真实形态）
                byte[] xml = ("<Error><Code>SignatureDoesNotMatch</Code><Message>"
                        + "The request signature we calculated does not match</Message>"
                        + "<RequestId>req-1</RequestId></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                write(exchange, 403, xml);
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("denied.txt", content);
            fail("403 应报错并带对象存储详情");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.IO_ERROR, e.code());
            assertTrue("应带对象存储错误码：" + e.getMessage(),
                    e.getMessage().contains("SignatureDoesNotMatch"));
            assertTrue("应带对象存储消息", e.getMessage().contains("does not match"));
        }
    }

    @Test
    public void uploadRejectsSizeMismatch() {
        route(exchange -> write(exchange, 599, new byte[0]));
        try (FileStorage storage = open()) {
            // 声明 10 字节、实际给 3 字节：必须在 create 之前就中止
            storage.upload("bad.txt", new java.io.ByteArrayInputStream("abc"
                    .getBytes(StandardCharsets.UTF_8)), 10);
            fail("大小不一致应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
            assertTrue(e.getMessage().contains("不一致"));
            assertTrue("不应发起 create", apiCalls("/file/create").isEmpty());
        }
    }

    @Test
    public void uploadWithoutAnyUrlIsIoError() {
        route(exchange -> {
            if (isApi(exchange, "/file/create")) {
                // create 响应既没有上传地址、getUploadUrl 也补不到：只能按 IO_ERROR 报错
                json(exchange, 200, env(TestMaps.map("fileId", "f-noput", "uploadId", "u-1",
                        "partInfos", Arrays.asList(TestMaps.map("partNumber", 1)))));
            } else if (isApi(exchange, "/file/getUploadUrl")) {
                json(exchange, 200, env(TestMaps.map("partInfos",
                        Arrays.asList(TestMaps.map("partNumber", 1)))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("x.txt", "abc".getBytes(StandardCharsets.UTF_8));
            fail("缺上传地址应报错");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.IO_ERROR, e.code());
            assertTrue(e.getMessage().contains("上传地址"));
        }
    }

    // -------------------------------------------------- 建目录 / 删除 / 复制

    @Test
    public void mkdirResolvesParentAndRootIsNoop() {
        route(exchange -> {
            if (isApi(exchange, "/file/createFolder")) {
                json(exchange, 200, env(TestMaps.map("fileId", "d-new")));
            } else if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(itemDir("备份", "d-backup", 0L)))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.mkdir("备份/2026");
            Map<String, Object> body = bodyOf("/file/createFolder");
            assertEquals("2026", body.get("name"));
            assertEquals("d-backup", body.get("parentFileId"));

            CALLS.clear();
            storage.mkdir("");
            assertTrue("根目录建目录应幂等且不触网", CALLS.isEmpty());
        }
    }

    @Test
    public void deletePollsUntilSucceed() {
        List<String> polls = new ArrayList<>();
        route(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("gone.txt", "f-gone", 1, 0L)))));
            } else if (isApi(exchange, "/file/batchDeleteAsync")) {
                json(exchange, 200, env(TestMaps.map("taskId", "task-1")));
            } else if (isApi(exchange, "/task/get")) {
                polls.add("poll");
                json(exchange, 200, env(TestMaps.map("taskInfo",
                        TestMaps.map("status", polls.size() == 1 ? "Running" : "Succeed"))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.delete("gone.txt");
            assertEquals("Running 之后必须继续轮询到终态", 2, polls.size());
            assertEquals(Arrays.asList("f-gone"), listValue("/file/batchDeleteAsync", "fileIds"));
        }
    }

    @Test
    public void deleteFailedTaskReported() {
        route(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("gone.txt", "f-gone", 1, 0L)))));
            } else if (isApi(exchange, "/file/batchDeleteAsync")) {
                json(exchange, 200, env(TestMaps.map("taskId", "task-2")));
            } else if (isApi(exchange, "/task/get")) {
                json(exchange, 200, env(TestMaps.map("taskInfo", TestMaps.map(
                        "status", "PartialSucceed", "message", "有 1 个文件被占用"))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.delete("gone.txt");
            fail("部分成功必须报错，不谎报成功");
        } catch (StorageException e) {
            assertTrue("实际：" + e.getMessage(), e.getMessage().contains("仅部分成功"));
            assertTrue(e.getMessage().contains("有 1 个文件被占用"));
        }
    }

    @Test
    public void deleteWithoutTaskIdIsUnknownConflict() {
        route(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("gone.txt", "f-gone", 1, 0L)))));
            } else if (isApi(exchange, "/file/batchDeleteAsync")) {
                json(exchange, 200, env(TestMaps.map()));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.delete("gone.txt");
            fail("无任务号必须按结果未知处理");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
            assertTrue(e.getMessage().contains("结果未知"));
        }
    }

    @Test
    public void deleteDirDelegatesToDeleteAndRootRejected() {
        route(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(itemDir("备份", "d-backup", 0L)))));
            } else if (isApi(exchange, "/file/batchDeleteAsync")) {
                json(exchange, 200, env(TestMaps.map("taskId", "t")));
            } else if (isApi(exchange, "/task/get")) {
                json(exchange, 200, env(TestMaps.map("taskInfo", TestMaps.map("status", "Succeed"))));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.deleteDir("备份");
            assertEquals(Arrays.asList("d-backup"), listValue("/file/batchDeleteAsync", "fileIds"));
            storage.deleteDir("");
            fail("删根目录应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void copyUsesBatchCopyAndRejectsRename() {
        route(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("a.txt", "f-a", 1, 0L),
                        itemDir("target", "d-target", 0L)))));
            } else if (isApi(exchange, "/file/batchCopySync")) {
                json(exchange, 200, env(TestMaps.map("taskId", "copy-1")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.copy("a.txt", "target/a.txt");
            Map<String, Object> body = bodyOf("/file/batchCopySync");
            assertEquals(Arrays.asList("f-a"), body.get("fileIds"));
            assertEquals("d-target", body.get("toParentFileId"));

            storage.copy("a.txt", "target/b.txt");
            fail("复制改名应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
            assertTrue(e.getMessage().contains("不支持改名"));
        }
    }

    @Test
    public void renameIsUnsupported() {
        try (FileStorage storage = open()) {
            storage.rename("a.txt", "b.txt");
            fail("重命名应明确不支持");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
            assertTrue(e.getMessage().contains("复制到目标目录后删除源"));
        }
    }

    // ------------------------------------------------------------------ 错误映射

    @Test
    public void scopeDeniedBusinessCodeMappedToAuthFailed() {
        route(exchange -> json(exchange, 200, failedEnv("16110013", "resource path not allowed")));
        try (FileStorage storage = open()) {
            storage.list("越权目录");
            fail("越权应报错");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage().contains("专属目录"));
        }
    }

    @Test
    public void expiredTokenBusinessMessageMappedToAuthFailed() {
        route(exchange -> json(exchange, 200, failedEnv("16110001", "creditToken invalid or expired")));
        try (FileStorage storage = open()) {
            storage.list("任意目录");
            fail("票据失效应报错");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue("应提示重新授权：" + e.getMessage(), e.getMessage().contains("重新"));
        }
    }

    @Test
    public void httpStatusMappedToUnifiedErrors() {
        for (int status : new int[]{401, 403, 429, 500, 404}) {
            clearCalls();
            route(exchange -> write(exchange, status, new byte[0]));
            try (FileStorage storage = open()) {
                storage.list("任意目录");
                fail("HTTP " + status + " 应报错");
            } catch (StorageException e) {
                StorageErrorCode expected;
                switch (status) {
                    case 401:
                    case 403:
                        expected = StorageErrorCode.AUTH_FAILED;
                        break;
                    case 429:
                        expected = StorageErrorCode.RATE_LIMITED;
                        break;
                    case 500:
                        expected = StorageErrorCode.SERVER_ERROR;
                        break;
                    default:
                        expected = StorageErrorCode.NOT_FOUND;
                        break;
                }
                assertEquals("HTTP " + status + " 的映射", expected, e.code());
            }
        }
    }

    @Test
    public void businessRequestsCarryFiveAuthHeaders() {
        route(exchange -> json(exchange, 200, env(TestMaps.map("items", Collections.emptyList()))));
        try (FileStorage storage = open()) {
            storage.list("");
            String[] call = apiCalls("/file/list").get(0);
            assertTrue(call[3].contains("\"credittoken\""));
            assertTrue(call[3].contains("\"skillid\""));
            assertTrue(call[3].contains("\"clientid\""));
            assertTrue(call[3].contains("\"strategyid\""));
            assertTrue(call[3].contains("\"clawuid\""));
            assertTrue(call[3].contains("\"version\""));
        }
    }

    // ------------------------------------------------------------------ 基础设施

    /** 断言预签名通道不带任何业务鉴权头（附加会被对象存储网关拒签）。 */
    private static void assertNoBusinessHeaders(String headerJson) {
        assertTrue("预签名请求不得携带 creditToken：" + headerJson,
                !headerJson.contains("credittoken"));
        assertTrue("预签名请求不得携带 clawUid", !headerJson.contains("clawuid"));
        assertTrue("预签名请求不得携带 skillId", !headerJson.contains("skillid"));
        assertTrue("预签名请求不得携带 clientId", !headerJson.contains("clientid"));
        assertTrue("预签名请求不得携带 strategyId", !headerJson.contains("strategyid"));
    }

    private static FileStorage open() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(CmCloudConfig.BASE_URL, baseUrl);
        config.put(CmCloudConfig.CREDIT_TOKEN, "test-credit-token");
        config.put(CmCloudConfig.SKILL_ID, "skill-1");
        config.put(CmCloudConfig.CLIENT_ID, "client-1");
        config.put(CmCloudConfig.STRATEGY_ID, "strategy-1");
        config.put(CmCloudConfig.CLAW_UID, "uid-1");
        return new CmCloudFileStorage(StorageConfig.of(config));
    }

    private static void route(HttpHandler handler) {
        ROUTE.set(handler);
    }

    private static String path(HttpExchange exchange) {
        return exchange.getRequestURI().getPath();
    }

    private static boolean isApi(HttpExchange exchange, String api) {
        String requestPath = path(exchange);
        return requestPath.startsWith(PREFIX) && api.equals(requestPath.substring(PREFIX.length()));
    }

    /** 当前请求的 JSON 体（桩已把每个请求的 body 录进 CALLS）。 */
    private static Map<String, Object> body(HttpExchange exchange) {
        for (int i = CALLS.size() - 1; i >= 0; i--) {
            if ("POST".equals(CALLS.get(i)[0])) {
                return parseBody(CALLS.get(i)[2]);
            }
        }
        return TestMaps.map();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(String text) {
        if (text == null || text.trim().isEmpty()) {
            return TestMaps.map();
        }
        Object parsed = JSON.parse(text);
        return parsed instanceof Map ? (Map<String, Object>) parsed : TestMaps.map();
    }

    /** @return 指定接口最后一次请求的 body */
    private static Map<String, Object> bodyOf(String api) {
        for (int i = CALLS.size() - 1; i >= 0; i--) {
            if (CALLS.get(i)[1].endsWith(api)) {
                return parseBody(CALLS.get(i)[2]);
            }
        }
        return TestMaps.map();
    }

    /** @return 指定接口的调用列表（{方法, 路径, 请求体, 请求头 JSON}） */
    private static List<String[]> apiCalls(String suffix) {
        List<String[]> result = new ArrayList<>();
        for (String[] call : CALLS) {
            if (call[1].endsWith(suffix) || call[1].startsWith(suffix)) {
                result.add(call);
            }
        }
        return result;
    }

    private static Object pageCursor(HttpExchange exchange) {
        Object pageInfo = body(exchange).get("pageInfo");
        if (pageInfo instanceof Map) {
            return ((Map<?, ?>) pageInfo).get("pageCursor");
        }
        return null;
    }

    private static List<String> parentIdsAsked() {
        List<String> ids = new ArrayList<>();
        for (String[] call : CALLS) {
            if (call[1].endsWith("/file/list")) {
                ids.add(String.valueOf(parseBody(call[2]).get("parentFileId")));
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listValue(String api, String key) {
        Object value = bodyOf(api).get(key);
        List<Object> raw = value instanceof List ? (List<Object>) value : Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static Map<String, Object> itemFile(String name, String id, long size, long mtime) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("fileId", id);
        item.put("type", "file");
        item.put("size", size);
        item.put("updatedAt", mtime);
        return item;
    }

    private static Map<String, Object> itemDir(String name, String id, long mtime) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("fileId", id);
        item.put("type", "folder");
        item.put("updatedAt", mtime);
        return item;
    }

    private static Map<String, Object> env(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", true);
        envelope.put("code", "0000");
        envelope.put("message", "success");
        envelope.put("data", data);
        return envelope;
    }

    private static Map<String, Object> failedEnv(String code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("code", code);
        envelope.put("message", message);
        return envelope;
    }

    private static void json(HttpExchange exchange, int status, Map<String, Object> envelope) {
        byte[] body = JSON.toJsonString(envelope).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        write(exchange, status, body);
    }

    private static void write(HttpExchange exchange, int status, byte[] body) {
        try {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException e) {
            throw new AssertionError("桩服务写响应失败", e);
        } finally {
            exchange.close();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return TestMaps.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
