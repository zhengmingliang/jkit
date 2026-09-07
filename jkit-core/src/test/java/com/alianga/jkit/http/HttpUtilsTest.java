package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.jdk.JdkUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpUtilsTest {
    private static HttpServer server;
    private static String baseUrl;
    private static File workDir;

    @BeforeClass
    public static void startServer() throws IOException {
        workDir = new File("target/http-utils-test");
        workDir.mkdirs();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> send(exchange, 200, "text/plain; charset=utf-8", "hello"));
        server.createContext("/query", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            send(exchange, 200, "text/plain; charset=utf-8", raw == null ? "" : raw);
        });
        server.createContext("/echo-headers", HttpUtilsTest::echoHeaders);
        server.createContext("/form", HttpUtilsTest::echoBody);
        server.createContext("/json", HttpUtilsTest::echoBody);
        server.createContext("/upload", HttpUtilsTest::echoUpload);
        server.createContext("/file", HttpUtilsTest::sendFile);
        server.createContext("/cookie", HttpUtilsTest::cookie);
        server.createContext("/status", HttpUtilsTest::status);
        server.createContext("/redirect", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Location", "/hello");
            exchange.sendResponseHeaders(302, 0);
            exchange.close();
        });
        server.createContext("/bytes", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] data = new byte[]{0, 1, 2, 3, 127, (byte) 255};
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.createContext("/echo-method", HttpUtilsTest::echoMethod);
        server.createContext("/range", HttpUtilsTest::range);
        server.createContext("/sse", HttpUtilsTest::sse);
        server.createContext("/openai-sse", HttpUtilsTest::openaiSse);
        server.createContext("/big", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] data = new byte[32];
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Before
    public void reset() {
        HttpUtils.debug = false;
        HttpUtils.printCurl = false;
        HttpUtils.fakeIp = false;
        HttpUtils.setProxy(null);
        HttpUtils.setCookieJar(new CookieJarImpl());
        HttpUtils.defaultMediaType = "application/json; charset=utf-8";
        HttpUtils.supportHttps();
        HttpUtils.setMaxBufferBytes(64L * 1024 * 1024);
        HttpUtils.setConnectTimeout(60_000);
        HttpUtils.setReadTimeout(60_000);
        HttpUtils.setHttp2(true);
    }

    @After
    public void restoreEngine() {
        HttpUtils.setEngine(null);
    }

    @Test
    public void getHello() throws Exception {
        assertEquals("hello", HttpUtils.get(baseUrl + "/hello"));
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/hello");
        try {
            assertEquals(200, response.code());
            assertTrue(response.isSuccessful());
            assertEquals("hello", response.body().string());
        } finally {
            response.close();
        }
    }

    @Test
    public void getWithQueryParams() throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "张三");
        params.put("age", 18);
        params.put("skip", null);
        params.put("ids", new Object[]{"1", "2"});
        params.put("tags", Arrays.asList("a", "b"));
        String query = HttpUtils.get(baseUrl + "/query", params);
        assertTrue(query.contains("name=" + HttpUtils.encodeValue("张三")));
        assertTrue(query.contains("age=18"));
        assertFalse(query.contains("skip="));
        assertTrue(query.contains("ids=1") && query.contains("ids=2"));
        assertTrue(query.contains("tags=a") && query.contains("tags=b"));
    }

    @Test
    public void getRequestParamString() {
        assertEquals("", HttpUtils.getRequestParamString(null));
        assertEquals("", HttpUtils.getRequestParamString(new HashMap<String, Object>()));
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("q", "a b");
        assertEquals("q=a+b", HttpUtils.getRequestParamString(params));
        assertEquals("x", HttpUtils.encodeValue("x"));
        assertEquals("", HttpUtils.encodeValue(null));
    }

    @Test
    public void getWithExistingQueryAndCustomHeaders() throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Token", "abc");
        headers.put("User-Agent", "jkit-test");
        String body = HttpUtils.get(baseUrl + "/echo-headers?from=1",
                mapOf("k", "v"), headers);
        assertTrue(body.contains("X-Token=abc") || body.toLowerCase().contains("x-token=abc"));
        assertTrue(body.contains("jkit-test"));
        String query = HttpUtils.get(baseUrl + "/query?keep=1", mapOf("k", "v"));
        assertTrue(query.contains("keep=1"));
        assertTrue(query.contains("k=v"));
    }

    @Test
    public void nullUrlThrows() {
        try {
            HttpUtils.getResponse(null, null, null);
            fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("URL"));
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void postForm() throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "Tom");
        params.put("age", 20);
        String body = HttpUtils.getStringFromPost(baseUrl + "/form", params);
        assertTrue(body.contains("application/x-www-form-urlencoded"));
        assertTrue(body.contains("name=Tom"));
        assertTrue(body.contains("age=20"));
        byte[] bytes = HttpUtils.getBytesFromPost(baseUrl + "/form", params);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("name=Tom"));
        InputStream stream = HttpUtils.getInputStreamFromPost(baseUrl + "/form", params);
        assertNotNull(stream);
        assertTrue(new String(readAll(stream), StandardCharsets.UTF_8).contains("name=Tom"));
    }

    @Test
    public void postJsonBody() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "Tom");
        payload.put("age", 18);
        String echoed = HttpUtils.sendRequestBody(baseUrl + "/json", payload);
        assertTrue(echoed.contains("application/json"));
        assertTrue(echoed.contains("\"name\":\"Tom\""));
        assertTrue(echoed.contains("\"age\":18"));

        String raw = HttpUtils.sendRequestBody(baseUrl + "/json", "{\"x\":1}", "application/json; charset=utf-8");
        assertTrue(raw.contains("{\"x\":1}"));

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Trace", "1");
        String withHeader = HttpUtils.sendRequestBody(baseUrl + "/json", "<xml/>", headers, "application/xml");
        assertTrue(withHeader.contains("application/xml"));
        assertTrue(withHeader.contains("<xml/>"));
    }

    @Test
    public void uploadMultipart() throws Exception {
        File file = new File(workDir, "upload.txt");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("hello-upload".getBytes(StandardCharsets.UTF_8));
        }
        UploadInfo info = new UploadInfo("file", file.getAbsolutePath(), "demo.txt");
        info.setMediaType("text/plain");
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("token", "secret");
        HttpResponse response = HttpUtils.upload(baseUrl + "/upload", info, extra);
        try {
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("filename=\"demo.txt\""));
            assertTrue(body.contains("hello-upload"));
            assertTrue(body.contains("token"));
            assertTrue(body.contains("secret"));
        } finally {
            response.close();
        }
    }

    @Test
    public void downloadSyncAndFileName() throws Exception {
        String path = HttpUtils.download(baseUrl + "/file", "saved.bin", workDir.getAbsolutePath());
        assertNotNull(path);
        File saved = new File(path);
        assertTrue(saved.isFile());
        assertEquals("file-content", new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));

        String fromHeader = HttpUtils.getFileName(baseUrl + "/file");
        assertEquals("hello.txt", fromHeader);

        HttpResponse response = HttpUtils.getResponse(baseUrl + "/file");
        try {
            assertEquals("hello.txt", HttpUtils.getFileName(response));
        } finally {
            response.close();
        }
    }

    @Test
    public void downloadAsyncWithCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<IOException> error = new AtomicReference<IOException>();
        AtomicLong processed = new AtomicLong();
        HttpUtils.downloadAsync(baseUrl + "/file", "async.bin", workDir.getAbsolutePath(),
                new HttpCallBack<String>() {
                    @Override
                    public void onFailure(HttpCall call, IOException e) {
                        error.set(e);
                        latch.countDown();
                    }

                    @Override
                    public void onProcess(long process, long total) {
                        processed.set(process);
                    }

                    @Override
                    public void onResponse(HttpCall call, HttpResponse response, String result) {
                        path.set(result);
                        latch.countDown();
                    }
                });
        assertTrue("async download timed out", latch.await(15, TimeUnit.SECONDS));
        assertNull(error.get() == null ? null : error.get().toString(), error.get());
        assertNotNull(path.get());
        assertTrue(new File(path.get()).isFile());
        assertTrue(processed.get() > 0);
    }

    @Test
    public void cookieJarRoundTrip() throws Exception {
        String first = HttpUtils.get(baseUrl + "/cookie");
        assertEquals("", first);
        String cookieHeader = HttpUtils.get(baseUrl + "/cookie");
        assertTrue(cookieHeader.contains("sid=abc"));

        HttpResponse response = HttpUtils.getResponse(baseUrl + "/cookie");
        try {
            String value = HttpUtils.getCookieValue(response);
            assertTrue(value.contains("sid=abc"));
        } finally {
            response.close();
        }
    }

    @Test
    public void debugAndPrintCurlDumpReadableRequest() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        HttpUtils.debug = true;
        HttpUtils.printCurl = true;
        try {
            System.setOut(new PrintStream(buf, true, "UTF-8"));
            HttpUtils.postJson(baseUrl + "/json", java.util.Collections.singletonMap("id", 1));
        } finally {
            System.setOut(original);
            HttpUtils.debug = false;
            HttpUtils.printCurl = false;
        }
        String dump = buf.toString("UTF-8");
        assertTrue(dump.contains("======= request ======="));
        assertTrue(dump.contains("======= CURL ======="));
        assertTrue(dump.contains("POST "));
        assertTrue(dump.contains("/json"));
        assertTrue(dump.contains("\"id\":1") || dump.contains("\"id\": 1"));
        assertTrue(dump.toLowerCase().contains("curl"));
        assertFalse(dump.contains("[123,"));
    }

    @Test
    public void debugDumpsBinaryBodyAsLengthNotByteArray() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        HttpUtils.debug = true;
        try {
            System.setOut(new PrintStream(buf, true, "UTF-8"));
            HttpResponse response = HttpUtils.execute(HttpRequest.post(baseUrl + "/json")
                    .contentType("application/octet-stream")
                    .body(new byte[]{0, 1, 2, 127, (byte) 255}));
            response.close();
        } finally {
            System.setOut(original);
            HttpUtils.debug = false;
        }
        String dump = buf.toString("UTF-8");
        assertTrue(dump.contains("<binary 5 bytes>"));
        assertFalse(dump.contains("[0, 1, 2"));
    }

    @Test
    public void fakeIpAndDefaultHeaders() throws Exception {
        Map<String, String> defaults = HttpUtils.getDefaultHeaders();
        assertTrue(defaults.containsKey("User-Agent"));
        assertNotNull(defaults.get("User-Agent"));

        HttpUtils.fakeIp = true;
        String headers = HttpUtils.get(baseUrl + "/echo-headers");
        assertTrue(headers.toUpperCase().contains("X-REAL-IP="));
        assertTrue(headers.toUpperCase().contains("X-FORWARDED-FOR="));
    }

    @Test
    public void errorStatusStillReturnsBody() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/status?code=404");
        try {
            assertEquals(404, response.code());
            assertFalse(response.isSuccessful());
            assertEquals("missing", response.body().string());
        } finally {
            response.close();
        }
    }

    @Test
    public void followRedirect() throws Exception {
        assertEquals("hello", HttpUtils.get(baseUrl + "/redirect"));
    }

    @Test
    public void binaryGetAndPostBytes() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/bytes");
        try {
            byte[] data = response.body().bytes();
            assertEquals(6, data.length);
            assertEquals((byte) 255, data[5]);
        } finally {
            response.close();
        }
    }

    @Test
    public void proxyFlags() {
        assertFalse(HttpUtils.isUseProxy());
        HttpUtils.setHttpProxy("127.0.0.1", 9);
        assertTrue(HttpUtils.isUseProxy());
        HttpUtils.setSocksProxy("127.0.0.1", 1080);
        assertTrue(HttpUtils.isUseProxy());
        HttpUtils.setProxy(Proxy.NO_PROXY);
        assertTrue(HttpUtils.isUseProxy());
        HttpUtils.setProxy(null);
        assertFalse(HttpUtils.isUseProxy());
    }

    @Test
    public void ignoreSniAndHttpsFlag() {
        String previous = System.getProperty("jsse.enableSNIExtension");
        try {
            HttpUtils.ignoreSNI();
            assertEquals("false", System.getProperty("jsse.enableSNIExtension"));
            HttpUtils.supportHttps();
        } finally {
            if (previous == null) {
                System.clearProperty("jsse.enableSNIExtension");
            } else {
                System.setProperty("jsse.enableSNIExtension", previous);
            }
        }
    }

    @Test
    public void engineSelection() throws Exception {
        String name = HttpUtils.getEngineName();
        assertTrue(name.equals(HttpEngines.URL_CONNECTION) || name.equals(HttpEngines.JDK_HTTP_CLIENT));
        if (JdkUtils.JAVA_VERSION >= 11) {
            HttpEngine jdk = HttpEngines.tryJdkHttpClient();
            assertNotNull("JDK 11+ should load java.net.http engine", jdk);
            assertEquals(HttpEngines.JDK_HTTP_CLIENT, jdk.name());
        }

        HttpEngine previous = HttpUtils.getHttpEngine();
        try {
            HttpUtils.setEngine(HttpEngines.urlConnection());
            assertEquals(HttpEngines.URL_CONNECTION, HttpUtils.getEngineName());
            assertEquals("hello", HttpUtils.get(baseUrl + "/hello"));
            String form = HttpUtils.getStringFromPost(baseUrl + "/form", mapOf("a", "1"));
            assertTrue(form.contains("a=1"));

            HttpEngine jdk = HttpEngines.tryJdkHttpClient();
            if (jdk != null) {
                HttpUtils.setEngine(jdk);
                assertEquals(HttpEngines.JDK_HTTP_CLIENT, HttpUtils.getEngineName());
                assertEquals("hello", HttpUtils.get(baseUrl + "/hello"));
                assertEquals("hello", HttpUtils.get(baseUrl + "/redirect"));
                Map<String, Object> json = new LinkedHashMap<String, Object>();
                json.put("ok", true);
                String echoed = HttpUtils.sendRequestBody(baseUrl + "/json", json);
                assertTrue(echoed.contains("\"ok\":true"));
            }
        } finally {
            HttpUtils.setEngine(previous);
        }
    }

    @Test
    public void downloadResumeAndPutDelete() throws Exception {
        File dest = new File(workDir, "resume.bin");
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write("ABCDE".getBytes(StandardCharsets.UTF_8));
        }
        String path = HttpUtils.download(baseUrl + "/range", dest, true);
        assertEquals("ABCDEFGHIJ", new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8));

        Future<String> future = HttpUtils.downloadAsync(baseUrl + "/file", new File(workDir, "future.bin"));
        String asyncPath = future.get(10, TimeUnit.SECONDS);
        assertTrue(new File(asyncPath).isFile());

        String put = HttpUtils.put(baseUrl + "/echo-method", "xyz", "text/plain");
        assertTrue(put.startsWith("PUT"));
        assertTrue(put.contains("xyz"));
        String deleted = HttpUtils.delete(baseUrl + "/echo-method");
        assertTrue(deleted.startsWith("DELETE"));
        assertTrue(HttpUtils.postJson(baseUrl + "/json",
                java.util.Collections.singletonMap("ok", true)).contains("\"ok\":true"));
        assertTrue(HttpUtils.postForm(baseUrl + "/form", mapOf("a", "1")).contains("a=1"));
        assertTrue(HttpUtils.basicAuth("u", "p").startsWith("Basic "));
        assertEquals("Bearer tok", HttpUtils.bearer("tok"));

        HttpResponse stream = HttpUtils.openStream(baseUrl + "/hello");
        try {
            assertEquals("hello", stream.body().string());
        } finally {
            stream.close();
        }
    }

    @Test
    public void headerOnlyAndDeleteBodyOverloads() throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Token", "abc");
        String echoed = HttpUtils.getResponse(baseUrl + "/echo-headers", headers).body().string();
        assertTrue(echoed.contains("abc"));

        HttpResponse postOnlyHeader = HttpUtils.getResponseFromPost(baseUrl + "/echo-method", headers);
        try {
            assertTrue(postOnlyHeader.body().string().startsWith("POST"));
        } finally {
            postOnlyHeader.close();
        }
        assertTrue(HttpUtils.post(baseUrl + "/echo-method").string().startsWith("POST"));

        String deleted = HttpUtils.delete(baseUrl + "/echo-method", "{\"id\":1}");
        assertTrue(deleted.startsWith("DELETE"));
        assertTrue(deleted.contains("{\"id\":1}"));
        String deletedJson = HttpUtils.deleteJson(baseUrl + "/echo-method",
                java.util.Collections.singletonMap("id", 2), headers);
        assertTrue(deletedJson.startsWith("DELETE"));
        assertTrue(deletedJson.contains("\"id\":2"));

        String patched = HttpUtils.patch(baseUrl + "/echo-method", "p", headers, "text/plain");
        assertTrue(patched.startsWith("PATCH"));
        assertTrue(HttpUtils.put(baseUrl + "/echo-method", headers).startsWith("PUT"));
        assertTrue(HttpUtils.postJson(baseUrl + "/json", java.util.Collections.singletonMap("a", 1), headers)
                .contains("\"a\":1"));

        HttpResponse stream = HttpUtils.openStream(baseUrl + "/echo-headers", headers);
        try {
            assertTrue(stream.body().string().contains("abc"));
        } finally {
            stream.close();
        }
    }

    @Test
    public void sseEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        HttpCall call = HttpUtils.sse(baseUrl + "/sse", new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                events.add(event);
            }

            @Override
            public void onClosed() {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(call != null);
        assertTrue(events.size() >= 2);
        assertEquals("hello", events.get(0).getData());
        assertEquals("ping", events.get(1).getEvent());
        assertEquals("1", events.get(1).getData());
    }

    @Test
    public void sseMergeOpenAiAndCurlExecute() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> deltas = new CopyOnWriteArrayList<String>();
        final String[] merged = {""};
        HttpUtils.sseMerge(baseUrl + "/openai-sse", SseMergeFormat.OPENAI, new SseMergeListener() {
            @Override
            public void onDelta(SseMergeResult snapshot) {
                if (!snapshot.getContentDelta().isEmpty()) {
                    deltas.add(snapshot.getContentDelta());
                }
                merged[0] = snapshot.getContent();
            }

            @Override
            public void onComplete(SseMergeResult result) {
                merged[0] = result.getContent();
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals("Hello", merged[0]);
        assertTrue(deltas.contains("Hel") || merged[0].equals("Hello"));

        String curl = "curl -X POST '" + baseUrl + "/echo-method' -H 'X-Token: from-curl' --data-raw 'hi-curl'";
        String body = HttpUtils.curlString(curl);
        assertTrue(body.startsWith("POST"));
        assertTrue(body.contains("hi-curl"));
    }

    @Test
    public void sseFromHttpRequestAndCurl() throws Exception {
        CountDownLatch sseLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        HttpRequest request = HttpRequest.post(baseUrl + "/sse")
                .header("X-Token", "custom")
                .contentType("application/json")
                .body("{\"q\":1}");
        HttpCall call = HttpUtils.sse(request, new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                events.add(event);
            }

            @Override
            public void onClosed() {
                sseLatch.countDown();
            }
        });
        assertTrue(sseLatch.await(10, TimeUnit.SECONDS));
        assertNotNull(call);
        assertTrue(events.size() >= 1);
        assertTrue(events.get(0).getData().contains("body:"));
        assertTrue(events.get(0).getData().contains("\"q\":1"));
        assertEquals("application/json", request.getContentType());
        assertNull(request.getHeader("Accept"));

        CountDownLatch mergeLatch = new CountDownLatch(1);
        final String[] merged = {""};
        String curl = "curl -X POST '" + baseUrl + "/openai-sse'"
                + " -H 'Authorization: Bearer tok'"
                + " -H 'Content-Type: application/json'"
                + " --data-raw '{\"stream\":true}'";
        HttpUtils.sseMerge(HttpUtils.parseCurl(curl), SseMergeFormat.OPENAI, new SseMergeListener() {
            @Override
            public void onDelta(SseMergeResult snapshot) {
                merged[0] = snapshot.getContent();
            }

            @Override
            public void onComplete(SseMergeResult result) {
                merged[0] = result.getContent();
                mergeLatch.countDown();
            }
        });
        assertTrue(mergeLatch.await(10, TimeUnit.SECONDS));
        assertEquals("Hello", merged[0]);

        CountDownLatch curlSseLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> curlEvents = new CopyOnWriteArrayList<SseEvent>();
        HttpRequest fromCurl = HttpUtils.curlToRequest(
                "curl '" + baseUrl + "/sse' -H 'X-Token: from-curl'");
        HttpUtils.sse(fromCurl, new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                curlEvents.add(event);
            }

            @Override
            public void onClosed() {
                curlSseLatch.countDown();
            }
        });
        assertTrue(curlSseLatch.await(10, TimeUnit.SECONDS));
        assertTrue(curlEvents.size() >= 2);
        assertEquals("hello", curlEvents.get(0).getData());
    }

    @Test
    public void sseWithRequestBody() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        HttpUtils.sseJson(baseUrl + "/sse", java.util.Collections.singletonMap("q", 1), new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                events.add(event);
            }

            @Override
            public void onClosed() {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(events.size() >= 1);
        assertTrue(events.get(0).getData().contains("body:"));
        assertTrue(events.get(0).getData().contains("\"q\":1"));
    }

    @Test
    public void maxBufferBytesAndSslFlag() throws Exception {
        HttpUtils.setMaxBufferBytes(8);
        try {
            HttpUtils.get(baseUrl + "/big");
            fail("expected too large");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("maxBufferBytes"));
        } finally {
            HttpUtils.setMaxBufferBytes(64L * 1024 * 1024);
        }
        HttpUtils.verifySsl();
        assertFalse(HttpUtils.isIgnoreSsl());
        HttpUtils.supportHttps();
        assertTrue(HttpUtils.isIgnoreSsl());
    }

    @Test
    public void contentDispositionRfc5987() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/file?star=1");
        try {
            assertEquals("测试.txt", HttpUtils.getFileName(response));
        } finally {
            response.close();
        }
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(k, v);
        return map;
    }

    private static void echoHeaders(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue().get(0)).append('\n');
        }
        send(exchange, 200, "text/plain; charset=utf-8", builder.toString());
    }

    private static void echoBody(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String text = (contentType == null ? "" : contentType) + "\n"
                + new String(body, StandardCharsets.UTF_8);
        send(exchange, 200, "text/plain; charset=utf-8", text);
    }

    private static void echoUpload(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        send(exchange, 200, "text/plain; charset=utf-8", new String(body, StandardCharsets.UTF_8));
    }

    private static void echoMethod(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        String text = exchange.getRequestMethod() + "\n" + new String(body, StandardCharsets.UTF_8);
        send(exchange, 200, "text/plain; charset=utf-8", text);
    }

    private static void range(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        byte[] all = "ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            int start = Integer.parseInt(spec.substring(0, dash));
            byte[] part = java.util.Arrays.copyOfRange(all, start, all.length);
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + start + "-" + (all.length - 1) + "/" + all.length);
            exchange.sendResponseHeaders(206, part.length);
            exchange.getResponseBody().write(part);
            exchange.close();
            return;
        }
        send(exchange, 200, "application/octet-stream", all);
    }

    private static void openaiSse(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        os.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
        os.write("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static void sse(HttpExchange exchange) throws IOException {
        byte[] req = readAll(exchange.getRequestBody());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        if (req.length > 0) {
            String payload = new String(req, StandardCharsets.UTF_8);
            os.write(("data: body:" + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
        os.write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
        os.write("event: ping\ndata: 1\n\n".getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static void sendFile(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.contains("star=1")) {
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.txt");
        } else {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"hello.txt\"");
        }
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        send(exchange, 200, "application/octet-stream", "file-content");
    }

    private static void cookie(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        exchange.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/");
        send(exchange, 200, "text/plain; charset=utf-8", cookie == null ? "" : cookie);
    }

    private static void status(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        int code = 200;
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.startsWith("code=")) {
            code = Integer.parseInt(query.substring("code=".length()));
        }
        send(exchange, code, "text/plain; charset=utf-8", code == 404 ? "missing" : "ok");
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        send(exchange, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
