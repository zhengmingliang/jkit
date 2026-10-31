package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.git.adapter.GiteeAdapter;

import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Gitee raw 下载的 URL 拼接。
 *
 * <p>历史 bug：GitTransport.query() 返回值自带前导 ?，GiteeAdapter.openRaw 又按
 * 「URL 里有没有 ?」补了一次分隔符，拼出 {@code ...a.txt??access_token=tok}——
 * 服务端解析出的参数名是 {@code ?access_token}，令牌等于没带：私有仓库的大文件
 * 下载（无内联 base64、走 raw 兜底）必然 401/404。回归锁：用本地桩服务断言
 * 收到的查询串逐字符正确。</p>
 *
 * @author 郑明亮
 */
public class GiteeRawUrlTest {
    private static final List<String> RAW_QUERIES = new ArrayList<>();
    private static HttpServer server;
    private static String baseUrl;

    @BeforeClass
    public static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/raw", exchange -> {
            RAW_QUERIES.add(exchange.getRequestURI().getRawQuery());
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
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

    private static GitSourceConfig config(String token) {
        return GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "gitee",
                GitSourceConfig.BASE_URL, baseUrl,
                GitSourceConfig.OWNER, "octocat",
                GitSourceConfig.REPO, "hello",
                GitSourceConfig.BRANCH, "main",
                GitSourceConfig.TOKEN, token)));
    }

    @Test
    public void queryStringHasNoLeadingQuestionMark() {
        assertEquals("access_token=tok", GitTransport.queryString(TestMaps.of("access_token", "tok")));
        // Map.of 不保证迭代顺序，多参数用 LinkedHashMap 锁定
        java.util.Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("a", "1");
        ordered.put("b", "2");
        assertEquals("a=1&b=2", GitTransport.queryString(ordered));
        assertEquals("", GitTransport.queryString(TestMaps.of()));
        assertEquals("", GitTransport.queryString(TestMaps.of("a", "")));
    }

    @Test
    public void queryKeepsLeadingQuestionMarkForPlainUrls() {
        // 既有调用方（contents/commits URL）依赖前导 ?，行为不得变化
        assertEquals("?ref=main", GitTransport.query(TestMaps.of("ref", "main")));
        assertEquals("", GitTransport.query(TestMaps.of()));
    }

    @Test
    public void appendQueryPicksCorrectSeparator() {
        assertEquals("https://x/a?access_token=t",
                GitTransport.appendQuery("https://x/a", TestMaps.of("access_token", "t")));
        assertEquals("https://x/a?ref=main&access_token=t",
                GitTransport.appendQuery("https://x/a?ref=main", TestMaps.of("access_token", "t")));
        assertEquals("https://x/a", GitTransport.appendQuery("https://x/a", TestMaps.of()));
    }

    @Test
    public void openRawSendsCleanAccessTokenParam() throws Exception {
        RAW_QUERIES.clear();
        GiteeAdapter adapter = new GiteeAdapter();
        try (GitTransport transport = new GitTransport();
             InputStream in = adapter.openRaw(transport, config("tok-123"),
                     "docs/a.txt", baseUrl + "/raw/docs/a.txt", "docs/a.txt")) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("应恰好发出一次 raw 请求", 1, RAW_QUERIES.size());
        String query = RAW_QUERIES.get(0);
        assertEquals("查询串必须是干净的 access_token=tok-123", "access_token=tok-123", query);
        assertFalse("绝不能再出现双问号", query.contains("??"));
        assertFalse("参数名不能带前导 ?", query.contains("?access_token"));
    }

    @Test
    public void openRawOnUrlWithExistingQueryAppendsWithAmpersand() throws Exception {
        RAW_QUERIES.clear();
        GiteeAdapter adapter = new GiteeAdapter();
        try (GitTransport transport = new GitTransport();
             InputStream in = adapter.openRaw(transport, config("tok-9"),
                     "docs/a.txt", baseUrl + "/raw/docs/a.txt?sign=abc", "docs/a.txt")) {
            assertTrue(in.readAllBytes().length > 0);
        }
        assertEquals("sign=abc&access_token=tok-9", RAW_QUERIES.get(0));
    }

    @Test
    public void anonymousRawHasNoTokenParam() throws Exception {
        RAW_QUERIES.clear();
        GiteeAdapter adapter = new GiteeAdapter();
        try (GitTransport transport = new GitTransport();
             InputStream in = adapter.openRaw(transport, config(""),
                     "docs/a.txt", baseUrl + "/raw/docs/a.txt", "docs/a.txt")) {
            assertTrue(in.readAllBytes().length > 0);
        }
        // 匿名（token 为空）时不追加任何查询参数
        assertEquals(null, RAW_QUERIES.get(0));
    }
}
