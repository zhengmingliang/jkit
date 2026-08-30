package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpHardeningTest {
    @Test
    public void secureSslApiTurnsVerificationOn() {
        HttpUtils.supportHttps();
        assertTrue(HttpUtils.isIgnoreSsl());
        HttpUtils.useSecureSsl();
        assertFalse(HttpUtils.isIgnoreSsl());
    }

    @Test
    public void retryPolicyIsIdempotencyAware() throws IOException {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoffMs(10)
                .maxBackoffMs(20)
                .retryOnIOException()
                .retryStatus(408, 429, 500, 502, 503, 504)
                .build();
        assertTrue(policy.shouldRetry(HttpRequest.get("http://localhost"), new IOException("timeout")));
        assertFalse(policy.shouldRetry(HttpRequest.post("http://localhost"), new IOException("timeout")));
        assertTrue(policy.shouldRetry(HttpRequest.get("http://localhost"), 503));
        assertEquals(10L, policy.backoffMs(1));
        assertEquals(20L, policy.backoffMs(2));
    }

    @Test
    public void responseCanRequireSuccess() throws Exception {
        HttpResponse response = new HttpResponse(503, "Unavailable", "http://localhost",
                null, HttpResponseBody.ofBytes("busy".getBytes("UTF-8"), "text/plain"));
        try {
            response.requireSuccessful();
        } catch (HttpStatusException e) {
            assertEquals(503, e.getStatusCode());
            assertEquals("http://localhost", e.getRequestUrl());
            assertTrue(e.getMessage().contains("503"));
            return;
        }
        throw new AssertionError("expected HttpStatusException");
    }

    @Test
    public void multipartRejectsMissingFile() {
        try {
            HttpBodies.multipart(new UploadInfo("file", "target/does-not-exist", "x.txt"), null);
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("does not exist"));
            return;
        }
        throw new AssertionError("expected IOException");
    }
}
