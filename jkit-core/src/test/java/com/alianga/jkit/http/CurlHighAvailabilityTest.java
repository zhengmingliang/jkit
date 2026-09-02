package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Curl 解析与本地执行的高可用回归测试。
 */
public class CurlHighAvailabilityTest {
    private static HttpServer server;
    private static String baseUrl;
    private static File uploadFile;

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", CurlHighAvailabilityTest::echo);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/echo");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/upload", CurlHighAvailabilityTest::upload);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        uploadFile = new File("target/curl-high-availability-upload.txt");
        try (FileOutputStream out = new FileOutputStream(uploadFile)) {
            out.write("curl-upload".getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (uploadFile != null) {
            uploadFile.delete();
        }
    }

    @Before
    public void reset() {
        HttpUtils.setProxy(null);
        HttpUtils.setConnectTimeout(60_000);
        HttpUtils.setReadTimeout(60_000);
        HttpUtils.supportHttps();
        HttpUtils.setEngine(null);
    }

    @Test
    public void parsesShellContinuationQuotesAndRepeatedData() {
        String curl = "curl \\\n  --request POST \\\n  --url 'https://example.com/api?q=a%20b' \\\n  --header 'X-Test: value:with:colon' \\\n  --header=Accept:application/json \\\n  --data-raw '{\"a\":1}' \\\n  --data-binary '{\"b\":2}'";
        CurlRequest request = CurlParser.parse(curl);
        assertEquals("POST", request.getMethod());
        assertEquals("https://example.com/api?q=a%20b", request.getUrl());
        assertEquals("value:with:colon", request.getHeaders().get("X-Test"));
        assertEquals("application/json", request.getHeaders().get("Accept"));
        assertEquals("{\"a\":1}&{\"b\":2}", request.getBody());
        assertEquals("application/x-www-form-urlencoded", request.getContentType());
    }

    @Test
    public void parsesSupportedOptionsAndPreservesValues() {
        CurlRequest request = CurlParser.parse(
                "curl --url=http://example.com --get --data-urlencode 'q=hello world' "
                        + "--user-agent 'jkit test' --referer 'https://from.example' "
                        + "--cookie 'sid=1; theme=dark' --proxy http://proxy.example:8080 "
                        + "--insecure --location");
        assertEquals("GET", request.getMethod());
        assertTrue(request.getUrl().contains("q=hello%20world"));
        assertEquals("jkit test", request.getHeaders().get("User-Agent"));
        assertEquals("https://from.example", request.getHeaders().get("Referer"));
        assertEquals("sid=1; theme=dark", request.getHeaders().get("Cookie"));
        assertTrue(request.isInsecure());
        assertTrue(request.isFollowRedirects());
    }

    @Test
    public void parsesHeadUploadFormAndLongOptions() {
        CurlRequest head = CurlParser.parse("curl --head https://example.com");
        assertEquals("HEAD", head.getMethod());

        CurlRequest form = CurlParser.parse("curl -F 'name=Tom' -F 'empty=' https://example.com");
        assertEquals("POST", form.getMethod());
        assertEquals("name=Tom&empty=", form.getBody());
        assertEquals(com.alianga.jkit.http.curl.ParsedCurlRequest.Body.Kind.MULTIPART,
                form.model().body().kind());
        assertEquals(2, form.model().body().parts().size());

        CurlRequest upload = CurlParser.parse("curl -T " + uploadFile.getAbsolutePath() + " http://example.com/upload");
        assertEquals("PUT", upload.getMethod());
        assertEquals(uploadFile.getAbsoluteFile(), upload.toHttpRequest().getBodyFile().getAbsoluteFile());
    }

    @Test
    public void malformedAndMissingArgumentsDoNotCrashParser() {
        assertThrowsIllegalArgument(() -> CurlParser.parse(null));
        CurlRequest empty = CurlParser.parse("curl");
        assertEquals("GET", empty.getMethod());
        assertEquals(null, empty.getUrl());
        assertEquals("", CurlParser.parse("curl -H").getHeaders().toString().equals("{}") ? "" : "x");
        assertEquals("", CurlParser.parse("curl --data").getBody());
        assertEquals("", CurlParser.parse("curl -H invalid").getHeaders().toString().equals("{}") ? "" : "x");
        try {
            empty.toHttpRequest();
            fail("expected missing URL");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("url"));
        }
    }

    @Test
    public void executesGetPostRedirectAndRawResponse() throws Exception {
        String get = HttpUtils.curlString("curl " + baseUrl + "/echo?x=1");
        assertTrue(get.contains("GET"));
        assertTrue(get.contains("body="));

        String post = HttpUtils.curlString("curl -X POST '" + baseUrl + "/echo' "
                + "-H 'Content-Type: application/json' --data-raw '{\"ok\":true}'");
        assertTrue(post.contains("POST"));
        assertTrue(post.contains("{\"ok\":true}"));

        HttpResponse response = HttpUtils.curl("curl -L " + baseUrl + "/redirect");
        try {
            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("GET"));
        } finally {
            response.close();
        }
    }

    @Test
    public void executesFormUploadAndBasicAuth() throws Exception {
        String form = HttpUtils.curlString("curl -X POST '" + baseUrl + "/echo' -F 'name=Tom'");
        assertTrue(form.contains("POST"));
        assertTrue(form.contains("name=Tom"));

        String upload = HttpUtils.curlString("curl -F 'document=@" + uploadFile.getAbsolutePath() + "' "
                + baseUrl + "/upload");
        assertTrue(upload.contains("curl-upload"));
        assertTrue(upload.contains("name=\"document\""));

        CurlRequest auth = CurlParser.parse("curl -u alice:secret " + baseUrl + "/echo");
        String authResponse = auth.executeString();
        assertTrue(authResponse.contains("Authorization=Basic "));
    }

    @Test
    public void parserTokenizesEmptyQuotedValuesAndEscapes() {
        List<String> tokens = CurlParser.tokenize("-H 'X-Empty: ' --data \"a=\\\"b\\\"\" plain\\ value");
        assertEquals(5, tokens.size());
        assertEquals("X-Empty: ", tokens.get(1));
        assertEquals("a=\"b\"", tokens.get(3));
        assertEquals("plain value", tokens.get(4));
    }

    private static void echo(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        StringBuilder result = new StringBuilder(exchange.getRequestMethod()).append('\n');
        result.append("body=").append(new String(body, StandardCharsets.UTF_8)).append('\n');
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                result.append(header.getKey()).append('=').append(header.getValue().get(0)).append('\n');
            }
        }
        send(exchange, 200, result.toString());
    }

    private static void upload(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        send(exchange, 200, new String(body, StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void assertThrowsIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
