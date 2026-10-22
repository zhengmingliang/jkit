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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Yun139FileStorage}（139 云盘全量引擎）对真实 REST 端点的行为：路由策略解析域名、
 * 列目录 / 下载 / 上传 / 建目录 / 删除 / 移动 / 重命名 / 复制全操作，签名头与错误映射。
 *
 * <p>用 JDK 内置 HTTPS 桩服务（keytool 现场生成自签名证书；没有 keytool 的环境整类跳过）。
 * 路由策略地址经包级构造器注入桩服务，业务域名由桩下发的 {@code httpsUrl} 指回桩本身，
 * 全流程不通外网、不用真实凭证。</p>
 *
 * @author 郑明亮
 */
public class Yun139FileStorageHttpTest {

    private static final String ROUTE_PATH = "/user/route/qryRoutePolicy";

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
        Path keystore = Files.createTempFile("jkit-yun139-https", ".p12");
        Files.deleteIfExists(keystore);
        String password = "changeit";
        String dname = "CN=localhost, OU=jkit-test, O=alianga, L=SH, ST=SH, C=CN";
        Process keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "yun139-test",
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

    // ------------------------------------------------------------------ 路由与列表

    @Test
    public void routePolicyResolvesPersonalHostThenLists() {
        personalRoute(exchange -> json(exchange, 200, env(TestMaps.map("items", Collections.emptyList()))));
        try (FileStorage storage = open()) {
            storage.list("");
            // 路由请求体：accountName 用登录账号
            String[] route = apiCalls(ROUTE_PATH).get(0);
            assertTrue("路由请求应带账号：" + route[2], route[2].contains("13800000000"));
            // 业务请求打到路由下发的域名（即桩自身）
            assertEquals(1, apiCalls("/file/list").size());
        }
    }

    @Test
    public void listPaginatesByCursorAndSortsDirsFirst() {
        personalRoute(exchange -> {
            Object cursor = pageCursor(exchange);
            Map<String, Object> data;
            if (cursor == null || String.valueOf(cursor).isEmpty()) {
                data = TestMaps.map("items", Arrays.asList(
                                itemFile("周报.docx", "f-week", 1234,
                                        "2026-05-01T10:00:00.000+08:00"),
                                itemDir("备份", "d-backup")),
                        "nextPageCursor", "c2");
            } else if ("c2".equals(cursor)) {
                data = TestMaps.map("items", Arrays.asList(itemDir("AI空间", "d-ai")));
            } else {
                data = TestMaps.map("items", Collections.emptyList());
            }
            json(exchange, 200, env(data));
        });
        try (FileStorage storage = open()) {
            List<StorageEntry> entries = storage.list("");
            assertEquals(Arrays.asList("AI空间", "备份", "周报.docx"), entries.stream()
                    .map(StorageEntry::name).collect(java.util.stream.Collectors.toList()));
            StorageEntry week = entries.get(2);
            assertFalse(week.isDirectory());
            assertEquals(1234, week.size());
            assertEquals("f-week", week.etag());
            assertEquals("f-week", week.attribute(Yun139FileStorage.ATTR_Y139_FILE_ID));
            assertEquals("/周报.docx", week.attribute(Yun139FileStorage.ATTR_Y139_CLOUD_PATH));
        }
    }

    @Test
    public void statMissingChildIsNotFoundAndRootNeedsNoNetwork() {
        personalRoute(exchange -> json(exchange, 200, env(TestMaps.map("items", Collections.emptyList()))));
        try (FileStorage storage = open()) {
            assertFalse(storage.exists("不存在.txt"));
            try {
                storage.stat("不存在.txt");
                fail("应抛 NOT_FOUND");
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.NOT_FOUND, e.code());
            }
            assertTrue(storage.stat("").isDirectory());
        }
    }

    // ------------------------------------------------------------------ 下载

    @Test
    public void downloadPrefersCdnUrlAndSendsNoAuthHeaders() {
        byte[] content = "hello 139".getBytes(StandardCharsets.UTF_8);
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("note.txt", "f-note", content.length, ISO_TIME)))));
            } else if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map("cdnUrl", baseUrl + "/raw/note.txt",
                        "url", baseUrl + "/raw/fallback")));
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
            assertTrue(storage.supportsRange());
            // 预签名 GET 不带任何鉴权头
            String[] raw = apiCalls("/raw/note.txt").get(0);
            assertTrue(!raw[3].contains("authorization"));
            assertEquals("https://share-cdn.example/x", "https://share-cdn.example/x");
        }
    }

    @Test
    public void downloadRangeSendsRangeHeader() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("nums.txt", "f-nums", 10, ISO_TIME)))));
            } else if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map("url", baseUrl + "/raw/nums.txt")));
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
        }
    }

    @Test
    public void presignReturnsDownloadUrl() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("share.txt", "f-share", 1, ISO_TIME)))));
            } else if (isApi(exchange, "/file/getDownloadUrl")) {
                json(exchange, 200, env(TestMaps.map("cdnUrl", "https://share.example/abc")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            assertEquals("https://share.example/abc",
                    storage.presign("share.txt", java.time.Duration.ofHours(1)));
        }
    }

    // ------------------------------------------------------------------ 上传

    @Test
    public void uploadRunsCreatePutCompleteWithSha256AndSignedBusinessHeaders() {
        byte[] content = "hello 139".getBytes(StandardCharsets.UTF_8);
        route(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith(ROUTE_PATH)) {
                json(exchange, 200, env(TestMaps.map("routePolicyList", Arrays.asList(
                        TestMaps.map("modName", "personal", "httpsUrl", baseUrl)))));
            } else if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map(
                        "fileId", "f-new",
                        "uploadId", "u-new",
                        "partInfos", Arrays.asList(TestMaps.map(
                                "partNumber", 1,
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

            // 业务请求带签名头全套
            String[] createCall = apiCalls("/file/create").get(0);
            assertTrue("业务请求应带 Basic Authorization：" + createCall[3],
                    createCall[3].contains("\"authorization\":[\"Basic test-auth-token\"]"));
            assertTrue("业务请求应带 mcloud-sign（ts,rand,sign 三段）",
                    createCall[3].contains("\"mcloud-sign\""));

            Map<String, Object> created = bodyOf("/file/create");
            assertEquals("note.txt", created.get("name"));
            assertEquals("file", created.get("type"));
            assertEquals("/", created.get("parentFileId"));
            assertEquals("SHA256", created.get("contentHashAlgorithm"));
            assertEquals(sha256(content), created.get("contentHash"));
            assertEquals("application/octet-stream", created.get("contentType"));

            // 分片 PUT：octet-stream、无鉴权头
            String[] putCall = apiCalls("/put/f-new").get(0);
            assertTrue("分片 PUT 应带 octet-stream：" + putCall[3],
                    putCall[3].contains("\"content-type\":[\"application/octet-stream\"]"));
            assertTrue(!putCall[3].contains("authorization"));

            assertArrayEquals(content, putCall[2].getBytes(StandardCharsets.UTF_8));

            Map<String, Object> completed = bodyOf("/file/complete");
            assertEquals("f-new", completed.get("fileId"));
            assertEquals("u-new", completed.get("uploadId"));
            assertEquals("SHA256", completed.get("contentHashAlgorithm"));
            assertEquals(sha256(content), completed.get("contentHash"));
        }
    }

    @Test
    public void uploadExistSkipsTransferAndComplete() {
        byte[] content = "dedup me".getBytes(StandardCharsets.UTF_8);
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map("fileId", "f-dup", "exist", true)));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.upload("dup.txt", content);
            assertTrue("秒传不得再 PUT", apiCalls("/put/").isEmpty());
            assertTrue("秒传不得再 complete", apiCalls("/file/complete").isEmpty());
        }
    }

    @Test
    public void uploadMissingUrlFallsBackToGetUploadUrl() {
        byte[] content = "small".getBytes(StandardCharsets.UTF_8);
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map("fileId", "f-gap", "uploadId", "u-gap",
                        "partInfos", Arrays.asList(TestMaps.map("partNumber", 1)))));
            } else if (isApi(exchange, "/file/getUploadUrl")) {
                json(exchange, 200, env(TestMaps.map("partInfos", Arrays.asList(TestMaps.map(
                        "partNumber", 1, "uploadUrl", baseUrl + "/put/gap")))));
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
            assertEquals("补取地址应带公共账号信息", "13800000000",
                    ((Map<?, ?>) urlBody.get("commonAccountInfo")).get("account"));
            assertEquals(1, apiCalls("/put/gap").size());
        }
    }

    @Test
    public void uploadRejectsEmptyFile() {
        personalRoute(exchange -> write(exchange, 599, new byte[0]));
        try (FileStorage storage = open()) {
            storage.upload("empty.txt", new byte[0]);
            fail("0 字节文件应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
            assertTrue(e.getMessage().contains("0 字节"));
        }
    }

    // ------------------------------------------------------------------ 目录与对象操作

    @Test
    public void mkdirCreatesFolderWithForceRename() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/create")) {
                json(exchange, 200, env(TestMaps.map("fileId", "d-new", "fileName", "2026")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.mkdir("2026");
            Map<String, Object> body = bodyOf("/file/create");
            assertEquals("2026", body.get("name"));
            assertEquals("folder", body.get("type"));
            assertEquals("/", body.get("parentFileId"));
            assertEquals("force_rename", body.get("fileRenameMode"));
        }
    }

    @Test
    public void deleteMovesToRecycleBin() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("gone.txt", "f-gone", 1, ISO_TIME)))));
            } else if (isApi(exchange, "/recyclebin/batchTrash")) {
                json(exchange, 200, env(TestMaps.map("taskId", "t-1")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.delete("gone.txt");
            assertEquals(Arrays.asList("f-gone"), bodyOf("/recyclebin/batchTrash").get("fileIds"));
            storage.delete("");
            fail("删根目录应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void renameSameParentUsesUpdate() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("old.txt", "f-old", 1, ISO_TIME)))));
            } else if (isApi(exchange, "/file/update")) {
                json(exchange, 200, env(TestMaps.map("fileId", "f-old", "fileName", "new.txt")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.rename("old.txt", "new.txt");
            Map<String, Object> body = bodyOf("/file/update");
            assertEquals("f-old", body.get("fileId"));
            assertEquals("new.txt", body.get("name"));
            assertTrue("同目录改名不应发起 batchMove", apiCalls("/file/batchMove").isEmpty());
        }
    }

    @Test
    public void renameCrossParentMovesThenUpdates() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("a.txt", "f-a", 1, ISO_TIME),
                        itemDir("target", "d-target")))));
            } else if (isApi(exchange, "/file/batchMove")) {
                json(exchange, 200, env(TestMaps.map("taskId", "m-1")));
            } else if (isApi(exchange, "/file/update")) {
                json(exchange, 200, env(TestMaps.map("fileId", "f-a")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.rename("a.txt", "target/b.txt");
            Map<String, Object> moved = bodyOf("/file/batchMove");
            assertEquals(Arrays.asList("f-a"), moved.get("fileIds"));
            assertEquals("d-target", moved.get("toParentFileId"));
            Map<String, Object> updated = bodyOf("/file/update");
            assertEquals("f-a", updated.get("fileId"));
            assertEquals("b.txt", updated.get("name"));
        }
    }

    @Test
    public void copyUsesBatchCopyAndRejectsRename() {
        personalRoute(exchange -> {
            if (isApi(exchange, "/file/list")) {
                json(exchange, 200, env(TestMaps.map("items", Arrays.asList(
                        itemFile("a.txt", "f-a", 1, ISO_TIME),
                        itemDir("target", "d-target")))));
            } else if (isApi(exchange, "/file/batchCopy")) {
                json(exchange, 200, env(TestMaps.map("taskId", "c-1")));
            } else {
                write(exchange, 599, new byte[0]);
            }
        });
        try (FileStorage storage = open()) {
            storage.copy("a.txt", "target/a.txt");
            Map<String, Object> body = bodyOf("/file/batchCopy");
            assertEquals(Arrays.asList("f-a"), body.get("fileIds"));
            assertEquals("d-target", body.get("toParentFileId"));

            storage.copy("a.txt", "target/b.txt");
            fail("复制改名应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
            assertTrue(e.getMessage().contains("不支持改名"));
        }
    }

    // ------------------------------------------------------------------ 错误映射

    @Test
    public void businessFailureAndHttp401Mapped() {
        // success=false → 业务失败（消息带出来）
        personalRoute(exchange -> json(exchange, 200, failedEnv("1", "file not found")));
        try (FileStorage storage = open()) {
            storage.list("任意目录");
            fail("业务失败应报错");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.SERVER_ERROR, e.code());
            assertTrue(e.getMessage().contains("file not found"));
        }

        // HTTP 401 → AUTH_FAILED，并给重新抓取 Authorization 的指引
        clearCalls();
        personalRoute(exchange -> write(exchange, 401, new byte[0]));
        try (FileStorage storage = open()) {
            storage.list("任意目录");
            fail("401 应报错");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue("应提示重新抓取：" + e.getMessage(),
                    e.getMessage().contains("Authorization"));
        }
    }

    // ------------------------------------------------------------------ 基础设施

    /** ISO 时间样本。 */
    private static final String ISO_TIME = "2026-05-01T10:00:00.000+08:00";

    /** 路由策略 + 业务 API 的组合路由（每个用例的桩入口）。 */
    private static void personalRoute(Consumer<HttpExchange> api) {
        route(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith(ROUTE_PATH)) {
                json(exchange, 200, env(TestMaps.map("routePolicyList", Arrays.asList(
                        TestMaps.map("modName", "personal", "httpsUrl", baseUrl)))));
                return;
            }
            api.accept(exchange);
        });
    }

    private static void route(HttpHandler handler) {
        ROUTE.set(handler);
    }

    private static FileStorage open() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(Yun139Profile.AUTHORIZATION, "test-auth-token");
        config.put(Yun139Profile.ACCOUNT, "13800000000");
        return new Yun139FileStorage(StorageConfig.of(config),
                baseUrl + ROUTE_PATH);
    }

    private static String path(HttpExchange exchange) {
        return exchange.getRequestURI().getPath();
    }

    private static boolean isApi(HttpExchange exchange, String api) {
        return api.equals(path(exchange));
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
    private static List<String[]> apiCalls(String prefixOrSuffix) {
        List<String[]> result = new ArrayList<>();
        for (String[] call : CALLS) {
            if (call[1].endsWith(prefixOrSuffix) || call[1].startsWith(prefixOrSuffix)) {
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

    private static Map<String, Object> itemFile(String name, String id, long size, String mtime) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("fileId", id);
        item.put("type", "file");
        item.put("size", size);
        item.put("updatedAt", mtime);
        return item;
    }

    private static Map<String, Object> itemDir(String name, String id) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("fileId", id);
        item.put("type", "folder");
        item.put("updatedAt", ISO_TIME);
        return item;
    }

    private static Map<String, Object> env(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", true);
        envelope.put("code", "0");
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
