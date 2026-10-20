package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 响应模板变量：变量求值、未解析保留原文 + X-Mock-Warning、JSON 转义、
 * {@code templated=false} 行为不变、字面量 {@code {{}} 转义、随机种子可复现。
 *
 * @author 郑明亮
 * @since 2.0.5
 */
public class MockResponseTemplateTest {

    private final MockHttpServer server = new MockHttpServer();

    @After
    public void tearDown() {
        server.stop();
    }

    /** 覆盖请求类变量 + 动态值 + faker 的整体渲染（含 headers 大小写不敏感与 body 下标路径）。 */
    @Test
    public void rendersRequestDynamicAndFakerVariables() {
        MockRequestContext ctx = sampleCtx();
        List<String> errors = new ArrayList<>();
        String rendered = MockResponseTemplate.render(
                "POST {{request.method}} on {{request.path}} #{{request.pathParams.id}}"
                        + " q={{request.query.a}} t={{request.headers.x-token}}"
                        + " b={{request.body.a.b}} i={{request.body.arr[0].id}}",
                ctx, null, errors);
        assertTrue(errors.isEmpty());
        assertEquals("POST POST on /users/7 #7 q=1 t=token-abc b=h\\\"i\\n i=9",
                rendered);
        assertTrue("uuid 无分隔结构",
                MockResponseTemplate.render("{{uuid}}", ctx, null, errors)
                        .matches("[0-9a-f-]{36}"));
        assertTrue("{{now yyyy/MM/dd}} 应按 pattern 渲染",
                MockResponseTemplate.render("{{now yyyy/MM/dd}}", ctx, null, errors)
                        .matches("\\d{4}/\\d{2}/\\d{2}"));
        assertTrue("{{timestamp}} 为秒级数字",
                MockResponseTemplate.render("{{timestamp}}", ctx, null, errors)
                        .startsWith("17"));
        String rolled = MockResponseTemplate.render("{{randomInt 5 9}}", ctx, null, errors);
        assertTrue("区间内", rolled.matches("[5-9]"));
    }

    /** faker 四类变量 + 种子可复现：同种子同值，不同种子不同值。 */
    @Test
    public void fakerValuesAreReproducibleWithSeed() {
        MockRequestContext ctx = sampleCtx();
        String name1 = MockResponseTemplate.render("{{faker.name seed=42}}", ctx, null);
        String name2 = MockResponseTemplate.render("{{faker.name seed=42}}", ctx, null);
        String month1 = MockResponseTemplate.render("{{faker.city seed=42}}", ctx, null);
        String month2 = MockResponseTemplate.render("{{faker.city seed=42}}", ctx, null);
        String email = MockResponseTemplate.render("{{faker.email seed=42}}", ctx, null);
        String phone = MockResponseTemplate.render("{{faker.phone seed=42}}", ctx, null);
        assertEquals("同种子一遍值相同", name1, name2);
        assertEquals("同种子一遍值相同", month1, month2);
        assertFalse("城市不大可能有两枚不同种子同值", month1.equals(
                MockResponseTemplate.render("{{faker.city seed=43}}", ctx, null)));
        assertFalse("姓名不大可能有两枚不同种子同值", name1.equals(
                MockResponseTemplate.render("{{faker.name seed=43}}", ctx, null)));
        assertTrue("邮箱应带 @domain", email.matches(".*@[a-z0-9.]+"));
        assertTrue("手机号 11 位", phone.matches("1\\d{10}"));
    }

    /** 取不到值的变量：保留原文文本 + 记录 unknown-template-variable 原因。 */
    @Test
    public void unresolvedVariablesStayRawWithWarning() {
        MockRequestContext ctx = sampleCtx();
        List<String> errors = new ArrayList<>();
        String rendered = MockResponseTemplate.render(
                "{\"a\":\"{{request.query.missing}}\",\"b\":\"{{no.such}}\"}", ctx, null, errors);
        assertEquals("{\"a\":\"{{request.query.missing}}\",\"b\":\"{{no.such}}\"}", rendered);
        assertTrue("应记未知变量原因", errors.contains(
                "unknown-template-variable: request.query.missing"));
        assertTrue(errors.contains("unknown-template-variable: no.such"));
    }

    /** 引擎集成：templated 路由未命中变量在响应头放 X-Mock-Warning。 */
    @Test
    public void templatedRouteAddsMockWarningHeader() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/tpl").statusCode(200)
                .contentType("application/json; charset=utf-8")
                .body("{\"q\":\"{{request.query.nothing}}\"}")
                .templated(true)
                .build()));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/tpl"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue("X-Mock-Warning 说明未知变量",
                resp.headers().firstValue("X-Mock-Warning").orElse("")
                        .contains("unknown-template-variable"));
        assertEquals("未知变量保留原文", "{\"q\":\"{{request.query.nothing}}\"}", resp.body());
    }

    /** JSON 转义：值中的引号 / 换行替换后 JSON 结构不破坏。 */
    @Test
    public void escapedValuesKeepJsonIntact() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("POST").path("/json").statusCode(200)
                .contentType("application/json; charset=utf-8")
                .body("{\"msg\":\"{{request.body.msg}}\"}")
                .templated(true)
                .build()));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/json"))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"msg\":\"line1\\nline2 \\\"quoted\\\"\"}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        Object parsed = com.alianga.jkit.json.JSON.parse(resp.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed;
        assertEquals("line1\nline2 \"quoted\"", body.get("msg"));
    }

    /**
     * templated=false：行为与历史渲染器完全一致（未知占位符同样替换为空串——这正是历史语义），
     * 且不额外重扫 / 不加 X-Mock-Warning；未闭合的 {{ 字面量不自历史以来就不会被吃掉。
     */
    @Test
    public void nonTemplatedRouteBehavesExactlyLikeHistory() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/raw").statusCode(200)
                .contentType("application/json; charset=utf-8")
                .body("{\"a\":\"{{faker.name}}\",\"b\":\"{{query.x}}\",\"c\":\"{{loose\"}")
                .build()));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/raw"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("未开启模板变量：输出与历史渲染器逐字一致",
                MockTemplateRenderer.render(
                        "{\"a\":\"{{faker.name}}\",\"b\":\"{{query.x}}\",\"c\":\"{{loose\"}", null),
                resp.body());
        assertFalse("非 templated 路由不打 X-Mock-Warning",
                resp.headers().firstValue("X-Mock-Warning").isPresent());
    }

    /** 字面量转义：\{{ 不进占位符，输出 {{。 */
    @Test
    public void literalBraceEscaping() {
        MockRequestContext ctx = sampleCtx();
        String rendered = MockResponseTemplate.render("打开 \\{{faker.name}} 关闭 {{faker.name seed=7}}",
                ctx, null);
        assertEquals("打开 {{faker.name}} 关闭 " + MockResponseTemplate.render(
                "{{faker.name seed=7}}", ctx, null), rendered);
    }

    /** 头值也可渲染：request.headers 取值 + 种子都市效果。 */
    @Test
    public void headerValuesRenderWithVariables() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/hed").statusCode(200)
                .contentType("application/json; charset=utf-8").body("{}")
                .header("X-Req-Id", "{{request.headers.x-probe}}")
                // JDK HttpServer 响应头以 ISO-8859-1 编码,非 ASCII 头值会乱码:头的模板变量测试只用 ASCII 值
                .header("X-Roll", "{{randomInt 1 5 seed=7}}")
                .templated(true)
                .build()));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/hed"))
                        .header("X-Probe", "ui-test")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("头值按模板渲染", "ui-test",
                resp.headers().firstValue("X-Req-Id").orElse(""));
        assertEquals("头值 randomInt 与种子",
                MockResponseTemplate.render("{{randomInt 1 5 seed=7}}", null, null),
                resp.headers().firstValue("X-Roll").orElse(""));
    }

    /** 解析缓存：同一模板高频渲染稳定命中（行为一致性，不做内部计数）。 */
    @Test
    public void cachedTemplateRendersConsistently() {
        MockRequestContext ctx = sampleCtx();
        String first = MockResponseTemplate.render("q={{request.query.a}}#{{uuid seed=3}}", ctx, null);
        for (int i = 0; i < 100; i++) {
            assertEquals("模板已缓存,同一 seed 渲染一致", first,
                    MockResponseTemplate.render("q={{request.query.a}}#{{uuid seed=3}}",
                            ctx, null));
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** 构造一份样本请求上下文：POST /users/7?a=1，带 x-token 头与 JSON 体。 */
    private static MockRequestContext sampleCtx() {
        Map<String, String> pathParams = new LinkedHashMap<>();
        pathParams.put("id", "7");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Token", Collections.singletonList("token-abc"));
        byte[] body = "{\"a\":{\"b\":\"h\\\"i\\n\"},\"arr\":[{\"id\":9}]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MockRequestContext.of("POST", "/users/7", pathParams, "a=1",
                headers, body, Collections.<String, String>emptyMap(), null);
    }
}
