package com.alianga.jkit;

import com.alianga.jkit.http.HttpEngine;
import com.alianga.jkit.http.HttpInterceptor;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.http.HttpResponseBody;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link HttpClient} 实例级配置隔离测试：多个客户端之间的超时、代理、SSL、CookieJar、
 * 拦截器、fakeIp 互不污染；{@link HttpClient#shared()} 与 {@link HttpUtils} 静态 API
 * 共享同一份全局默认。引擎为探针实现，把观察到的代理/超时/忽略SSL/请求头回写到响应头，
 * 从而断言每条请求走的是哪个实例的配置。
 */
public class HttpClientIsolationTest {

    private static final Proxy PROXY_A = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-a.test", 18080));
    private static final Proxy PROXY_B = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-b.test", 18081));
    private static final Proxy PROXY_GLOBAL = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-global.test", 18099));

    /** 探针引擎：把请求上观察到的配置回写到响应头，便于断言隔离性。 */
    private static final class ProbeEngine implements HttpEngine {
        private final String tag;
        ProbeEngine(String tag) {
            this.tag = tag;
        }
        @Override
        public String name() {
            return "probe-" + tag;
        }
        @Override
        public HttpResponse execute(HttpRequest request) throws IOException {
            Proxy p = request.getProxy();
            String proxyHost = p == null ? "none" : ((InetSocketAddress) p.address()).getHostString();
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            headers.put("X-Probe-Tag", Collections.singletonList(tag));
            headers.put("X-Probe-Proxy", Collections.singletonList(proxyHost));
            headers.put("X-Probe-Timeout", Collections.singletonList(String.valueOf(request.getConnectTimeoutMs())));
            headers.put("X-Probe-IgnoreSsl", Collections.singletonList(String.valueOf(request.isIgnoreSsl())));
            String forwarded = request.getHeaders().get("X-FORWARDED-FOR");
            headers.put("X-Probe-FakeIp", Collections.singletonList(forwarded == null ? "absent" : forwarded));
            return new HttpResponse(200, "OK", request.getUrl(), headers,
                    HttpResponseBody.ofBytes("ok".getBytes(), "text/plain"));
        }
    }

    private static HttpResponse runProbe(HttpClient client, String url) throws IOException {
        return client.execute(new HttpRequest("GET", url));
    }

    @Test
    public void twoInstancesDoNotPolluteEachOther() throws IOException {
        HttpClient a = HttpClient.builder()
                .connectTimeout(1111).readTimeout(2222)
                .proxy(PROXY_A)
                .engine(new ProbeEngine("A"))
                .build();
        HttpClient b = HttpClient.builder()
                .connectTimeout(3333).readTimeout(4444)
                .proxy(PROXY_B)
                .engine(new ProbeEngine("B"))
                .build();

        HttpResponse ra = runProbe(a, "http://svc-a.test/x");
        HttpResponse rb = runProbe(b, "http://svc-b.test/y");

        // A 的配置只出现在 A 的响应里
        assertEquals("A", ra.header("X-Probe-Tag"));
        assertEquals("proxy-a.test", ra.header("X-Probe-Proxy"));
        assertEquals("1111", ra.header("X-Probe-Timeout"));

        // B 的配置只出现在 B 的响应里，与 A 完全不同
        assertEquals("B", rb.header("X-Probe-Tag"));
        assertEquals("proxy-b.test", rb.header("X-Probe-Proxy"));
        assertEquals("3333", rb.header("X-Probe-Timeout"));

        assertNotEquals(ra.header("X-Probe-Proxy"), rb.header("X-Probe-Proxy"));
        assertNotEquals(ra.header("X-Probe-Timeout"), rb.header("X-Probe-Timeout"));
    }

    @Test
    public void mutatingOneInstanceDoesNotAffectTheOther() throws IOException {
        HttpClient a = HttpClient.builder()
                .connectTimeout(1111)
                .proxy(PROXY_A)
                .engine(new ProbeEngine("A"))
                .build();
        HttpClient b = HttpClient.builder()
                .connectTimeout(3333)
                .proxy(PROXY_B)
                .engine(new ProbeEngine("B"))
                .build();

        // 修改 A 的代理与超时
        a = HttpClient.builder()
                .connectTimeout(9999)
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-c.test", 19999)))
                .engine(new ProbeEngine("A"))
                .build();
        // 即便 A 改了，B 仍然稳定
        HttpResponse rb = runProbe(b, "http://svc-b.test/z");
        assertEquals("proxy-b.test", rb.header("X-Probe-Proxy"));
        assertEquals("3333", rb.header("X-Probe-Timeout"));
    }

    @Test
    public void builderSeedsFromGlobalDefaults() {
        // 全局默认连接超时被改后，新 builder 以之为基线
        HttpUtils.setConnectTimeout(7777);
        try {
            HttpClient built = HttpClient.builder().build();
            assertEquals(7777, built.config().getConnectTimeoutMs());
        } finally {
            HttpUtils.setConnectTimeout(60000);
        }
    }

    @Test
    public void fakeIpIsInstanceScoped() throws IOException {
        HttpClient withFake = HttpClient.builder()
                .fakeIp(true)
                .engine(new ProbeEngine("F"))
                .build();
        HttpClient withoutFake = HttpClient.builder()
                .fakeIp(false)
                .engine(new ProbeEngine("N"))
                .build();

        HttpResponse rf = runProbe(withFake, "http://svc.test/f");
        HttpResponse rn = runProbe(withoutFake, "http://svc.test/n");

        assertNotNull("开启 fakeIp 的实例应带上 X-FORWARDED-FOR", rf.header("X-Probe-FakeIp"));
        assertTrue(rf.header("X-Probe-FakeIp").contains("."));
        assertEquals("absent", rn.header("X-Probe-FakeIp"));
    }

    @Test
    public void interceptorsAreInstanceScoped() throws IOException {
        final AtomicInteger counterA = new AtomicInteger();
        HttpInterceptor interceptorA = new HttpInterceptor() {
            @Override
            public HttpResponse intercept(Chain chain) throws IOException {
                counterA.incrementAndGet();
                return chain.proceed(chain.request());
            }
        };
        HttpClient a = HttpClient.builder()
                .engine(new ProbeEngine("A"))
                .addInterceptor(interceptorA)
                .build();
        HttpClient b = HttpClient.builder()
                .engine(new ProbeEngine("B"))
                .build();

        runProbe(a, "http://svc.test/a");
        runProbe(b, "http://svc.test/b");

        assertEquals(1, counterA.get()); // 仅 A 注册了拦截器，B 不受影响
    }

    @Test
    public void sharedReflectsLiveGlobals() throws IOException {
        HttpUtils.setProxy(PROXY_GLOBAL);
        try {
            HttpUtils.setEngine(new ProbeEngine("G"));
            // shared() 与 HttpUtils 静态 API 共享全局：都应观察到全局代理与引擎
            HttpResponse viaShared = runProbe(HttpClient.shared(), "http://svc.test/shared");
            HttpResponse viaStatic = HttpUtils.getResponse("http://svc.test/static");

            assertEquals("proxy-global.test", viaShared.header("X-Probe-Proxy"));
            assertEquals("G", viaShared.header("X-Probe-Tag"));
            assertEquals("proxy-global.test", viaStatic.header("X-Probe-Proxy"));
        } finally {
            HttpUtils.setProxy(null);
            HttpUtils.setEngine(null);
        }
    }

    @Test
    public void scopeIsClearedAfterExecute() {
        // 执行结束后不应在 ThreadLocal 里残留客户端，避免影响后续静态调用
        HttpClient a = HttpClient.builder().engine(new ProbeEngine("A")).build();
        try {
            a.get("http://svc.test/x");
        } catch (IOException ignore) {
            // 探针引擎不会真正发请求，正常不会到这里
        }
        assertNull("执行后 ThreadLocal 应已清理", HttpUtils.active());
    }
}
