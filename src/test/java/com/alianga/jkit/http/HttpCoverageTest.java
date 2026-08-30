package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.encoding.ContentEncodingCodec;
import com.alianga.jkit.http.encoding.ContentEncodings;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * HTTP 公共辅助 API 的边界与异常分支测试。
 */
public class HttpCoverageTest {
    @Test
    public void ioHelpersHandleNullInvalidAndBoundaries() throws Exception {
        assertEquals(0, HttpIo.readAll(null).length);
        assertEquals(0, HttpIo.copy(null, new ByteArrayOutputStream(), 1));
        assertEquals("", new String(HttpIo.readAll(new ByteArrayInputStream(new byte[0]), 1), StandardCharsets.UTF_8));
        try {
            HttpIo.readAll(new ByteArrayInputStream(new byte[]{1, 2}), 1);
            fail("expected size limit");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("maxBufferBytes"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(3, HttpIo.copy(new ByteArrayInputStream(new byte[]{1, 2, 3}), out, 0));
        assertEquals(3, out.size());
        assertNull(HttpIo.toUri(null));
        assertEquals("http://example.com/a", HttpIo.toUri("http://example.com/a").toString());
        assertNull(HttpIo.toUri("%%%"));
        assertTrue(HttpIo.parseCookies(null).isEmpty());
        List<HttpCookie> cookies = HttpIo.parseCookies(Arrays.asList(null, "bad cookie", "sid=abc; Path=/"));
        assertEquals(1, cookies.size());
        assertEquals("sid", cookies.get(0).getName());
        assertEquals(StandardCharsets.UTF_8, HttpIo.charsetFromContentType(null));
        assertEquals(StandardCharsets.UTF_8, HttpIo.charsetFromContentType("text/plain; charset=bad"));
        assertEquals("gzip, deflate, br", HttpIo.sanitizeAcceptEncoding("gzip, deflate, br"));
        assertEquals("br", HttpIo.sanitizeAcceptEncoding("br, zstd"));
        assertTrue(HttpIo.sanitizeAcceptEncoding(null).contains("gzip"));
        assertTrue(HttpIo.sanitizeAcceptEncoding(null).contains("br"));
        assertEquals("gzip, br", HttpIo.sanitizeAcceptEncoding("gzip;q=1.0, br;q=0.8"));
        HttpRequest encodingReq = HttpRequest.get("http://example.com")
                .header("Accept-Encoding", "gzip, deflate, br");
        HttpIo.applySupportedAcceptEncoding(encodingReq);
        assertEquals("gzip, deflate, br", encodingReq.getHeader("Accept-Encoding"));
        InputStream raw = new ByteArrayInputStream(new byte[]{1, 2, 3});
        assertSame(raw, HttpIo.decodeContentEncoding(raw, "zstd"));
        byte[] brotliHello = new byte[]{(byte) 139, 5, (byte) 128, 98, 114, 111, 116, 108, 105, 45, 104, 101, 108,
                108, 111, 3};
        InputStream decodedBr = HttpIo.decodeContentEncoding(new ByteArrayInputStream(brotliHello), "br");
        assertEquals("brotli-hello", new String(HttpIo.readAll(decodedBr), StandardCharsets.UTF_8));
        ByteArrayOutputStream gzipOut = new ByteArrayOutputStream();
        ContentEncodingCodec gzip = ContentEncodings.lookup("gzip");
        OutputStream encoded = gzip.encode(gzipOut);
        encoded.write("hi-gzip".getBytes(StandardCharsets.UTF_8));
        encoded.close();
        assertEquals("hi-gzip", new String(HttpIo.readAll(gzip.decode(new ByteArrayInputStream(gzipOut.toByteArray()))),
                StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, HttpIo.charsetFromContentType("text/plain; charset=\"UTF-8\""));
        assertEquals(-1, HttpIo.parseContentLength(null));
        assertEquals(-1, HttpIo.parseContentLength("abc"));
        assertEquals(12, HttpIo.parseContentLength(" 12 "));
        assertTrue(HttpIo.isRestrictedHttpClientHeader(null));
        assertTrue(HttpIo.isRestrictedHttpClientHeader("Host"));
        assertFalse(HttpIo.isRestrictedHttpClientHeader("X-Test"));
    }

    @Test
    public void retryPolicyCoversBuilderAndMethodBranches() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(0)
                .initialBackoffMs(-1)
                .maxBackoffMs(1)
                .retryNonIdempotent(true)
                .retryStatus(500)
                .retryOnIOException()
                .build();
        assertEquals(1, policy.getMaxAttempts());
        assertTrue(policy.shouldRetry(HttpRequest.post("http://localhost"), new IOException("x")));
        assertTrue(policy.shouldRetry(HttpRequest.post("http://localhost"), 500));
        assertFalse(policy.shouldRetry(null, new IOException("x")));
        assertFalse(policy.shouldRetry(HttpRequest.get("http://localhost"), (IOException) null));
        assertEquals(0, policy.backoffMs(0));
        assertEquals(0, policy.backoffMs(1));
        RetryPolicy bounded = RetryPolicy.builder().initialBackoffMs(2).maxBackoffMs(4).build();
        assertEquals(2, bounded.backoffMs(1));
        assertEquals(4, bounded.backoffMs(2));
        assertEquals(4, bounded.backoffMs(10));
        assertTrue(RetryPolicy.none().getMaxAttempts() == 1);
    }

    @Test
    public void configNormalizesValuesAndNullPolicy() {
        HttpConfig config = HttpConfig.shared();
        assertSame(config, config.setConnectTimeoutMs(-1));
        assertEquals(0, config.getConnectTimeoutMs());
        config.setReadTimeoutMs(-1);
        assertEquals(0, config.getReadTimeoutMs());
        config.setMaxBufferBytes(-1);
        assertEquals(-1, config.getMaxBufferBytes());
        config.setDownloadBufferSize(1);
        assertEquals(1024, config.getDownloadBufferSize());
        config.setProxyAuth("u", "p");
        assertEquals("u", config.getProxyUsername());
        assertEquals("p", config.getProxyPassword());
        config.setProxyAuth(null, null);
        assertNull(config.getProxyUsername());
        config.setRetryPolicy(null);
        assertEquals(1, config.getRetryPolicy().getMaxAttempts());
        config.setHttp2(false).setHttp2(true);
        assertTrue(config.isHttp2());
    }

    @Test
    public void responseBodyEmptyAndStreamConsumptionBranches() throws Exception {
        HttpResponseBody empty = HttpResponseBody.ofBytes(null, null);
        assertTrue(empty.isEmpty());
        assertEquals("", empty.string());
        assertEquals(0, empty.jsonMap().size());
        assertNull(empty.jsonNode());
        assertNull(empty.json(String.class));

        HttpResponseBody scalar = HttpResponseBody.ofBytes("1".getBytes(StandardCharsets.UTF_8), "application/json");
        assertTrue(scalar.jsonMap().isEmpty());

        HttpResponseBody stream = HttpResponseBody.ofStream(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), null, -1, null);
        assertFalse(stream.isEmpty());
        assertEquals("abc", stream.string());
        assertTrue(stream.isConsumed());
        try {
            stream.byteStream();
            fail("expected consumed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("不能重复读取"));
        }
    }

    @Test
    public void sseParserHandlesCommentsFieldsInvalidRetryAndRawLines() throws Exception {
        final AtomicInteger comments = new AtomicInteger();
        final java.util.ArrayList<SseEvent> events = new java.util.ArrayList<SseEvent>();
        SseListener listener = new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                events.add(event);
            }

            @Override
            public void onComment(String comment) {
                comments.incrementAndGet();
            }
        };
        String source = ": ping\n"
                + "retry: bad\n"
                + "event: update\n"
                + "id: 7\n"
                + "data: one\n"
                + "data: two\n\n"
                + "{\"raw\":true}\n"
                + "\n"
                + "[DONE]\n";
        SseClient.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), listener, null);
        assertEquals(1, comments.get());
        assertEquals(3, events.size());
        assertEquals("update", events.get(0).getEvent());
        assertEquals("one\ntwo", events.get(0).getData());
        assertEquals("7", events.get(0).getId());
        assertEquals("{\"raw\":true}", events.get(1).getData());
        assertEquals("[DONE]", events.get(2).getData());
    }

    @Test
    public void sseParserDispatchesJsonDataLinesWithoutBlankSeparator() throws Exception {
        final java.util.ArrayList<SseEvent> events = new java.util.ArrayList<SseEvent>();
        SseListener listener = new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                events.add(event);
            }
        };
        String source = "data: {\"choices\":[{\"delta\":{\"reasoning\":\"Thinking\"},\"finish_reason\":\"\"}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"\"}]}\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
                + "data: [DONE]\n";
        SseClient.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), listener, null);
        assertEquals(4, events.size());
        assertTrue(events.get(0).getData().contains("reasoning"));
        assertTrue(events.get(1).getData().contains("Hello"));
        assertTrue(events.get(2).getData().contains("stop"));
        assertEquals("[DONE]", events.get(3).getData());

        SseMerger merger = new SseMerger(SseMergeFormat.OPENAI);
        for (SseEvent event : events) {
            merger.accept(event);
        }
        assertEquals("Hello", merger.getContent());
        assertEquals("Thinking", merger.getThinking());
        assertTrue(merger.isDone());
    }

    @Test
    public void httpRequestCopyAndSseOverloadGuards() {
        HttpRequest original = HttpRequest.post("https://example.com/sse")
                .header("Authorization", "Bearer tok")
                .contentType("application/json")
                .body("{\"stream\":true}")
                .query("n", 1)
                .cookie("sid", "abc")
                .tag("trace");
        HttpRequest copy = original.copy();
        copy.header("X-Extra", "1").body("changed");
        assertNull(original.getHeader("X-Extra"));
        assertEquals("{\"stream\":true}", new String(original.getBody(), StandardCharsets.UTF_8));
        assertEquals("Bearer tok", copy.getHeader("Authorization"));
        assertEquals("trace", copy.getTag());
        assertEquals(1, copy.getQueries().get("n"));
        assertEquals("abc", copy.getCookies().get("sid"));

        SseListener listener = new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
            }
        };
        try {
            HttpUtils.sse((HttpRequest) null, listener);
            fail("expected request required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("request"));
        }
        try {
            HttpUtils.sse(HttpRequest.get("https://example.com/sse"), null);
            fail("expected listener required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("listener"));
        }
        try {
            HttpUtils.sseReconnect((CurlRequest) null, listener);
            fail("expected request required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("request"));
        }
        try {
            HttpUtils.sseMerge(HttpRequest.get("https://example.com/sse"), null, new SseMergeListener() {
                @Override
                public void onDelta(SseMergeResult snapshot) {
                }
            });
            fail("expected format required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("format"));
        }
    }

    @Test
    public void cookieMatchingHelpersCoverNullAndPathBoundaries() {
        assertTrue(CookieJarImpl.domainMatch(null, "example.com"));
        assertFalse(CookieJarImpl.domainMatch("example.com", null));
        assertTrue(CookieJarImpl.domainMatch(".example.com", "api.example.com"));
        assertFalse(CookieJarImpl.domainMatch("example.com", "badexample.com"));
        assertTrue(CookieJarImpl.pathMatch("/", null));
        assertTrue(CookieJarImpl.pathMatch("/a/b", "/a"));
        assertFalse(CookieJarImpl.pathMatch("/abc", "/a"));
        assertTrue(CookieJarImpl.pathMatch("/a/b", "/a/"));
        CookieJarImpl jar = new CookieJarImpl();
        assertFalse(jar.remove(null));
        assertFalse(jar.remove("x", null));
        assertEquals(Collections.emptyList(), jar.loadForRequest(null));
    }

    @Test
    public void multipartDefaultsAndFormEmptyBranches() throws Exception {
        assertEquals(0, HttpBodies.formUrlEncoded(null).length);
        assertEquals(0, HttpBodies.formUrlEncoded(Collections.<String, Object>emptyMap()).length);
        java.io.File file = java.io.File.createTempFile("jkit", ".unknown");
        try {
            UploadInfo info = new UploadInfo(null, file.getAbsolutePath(), null);
            HttpBodies.MultipartPayload payload = HttpBodies.multipart(info, null);
            assertTrue(payload.contentType.startsWith("multipart/form-data; boundary="));
            assertTrue(new String(payload.body, StandardCharsets.UTF_8).contains("filename=\"" + file.getName()));
        } finally {
            file.delete();
        }
        try {
            HttpBodies.multipart(null, null);
            fail("expected missing file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("required"));
        }
    }
}
