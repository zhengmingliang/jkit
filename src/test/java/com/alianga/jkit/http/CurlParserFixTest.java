package com.alianga.jkit.http;

import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 README 中列出的 curl 解析缺陷：短选项簇、--json、urlencode、@file、
 * multipart、-k 不改全局、toCurl 代理 host:port。
 */
public class CurlParserFixTest {

    @Test
    public void shortOptionClusterAndStuckMethodAreExpanded() {
        ParsedCurlRequest req = CurlParser.parseModel("curl -kLs -XPOST https://example.com/api");
        assertEquals("POST", req.method());
        assertTrue(req.insecure());
        assertTrue(req.followRedirects());
        assertEquals("https://example.com/api", req.url());
        assertTrue(req.unknownOptions().isEmpty());
    }

    @Test
    public void jsonFlagSetsContentTypeAndAccept() {
        ParsedCurlRequest req = CurlParser.parseModel(
                "curl --json '{\"a\":1}' https://example.com/x");
        assertEquals("POST", req.method());
        assertEquals(Body.Kind.JSON, req.body().kind());
        assertEquals("{\"a\":1}", req.body().text());
        assertEquals("application/json", req.header("Content-Type"));
        assertEquals("application/json", req.header("Accept"));
    }

    @Test
    public void dataUrlEncodePercentEncodesValue() {
        CurlRequest req = CurlParser.parse(
                "curl -G 'https://example.com/q' --data-urlencode 'q=hello world'");
        assertEquals("GET", req.getMethod());
        assertTrue(req.getUrl(), req.getUrl().contains("q=hello%20world"));
        assertFalse(req.getUrl().contains("q=hello world"));
    }

    @Test
    public void atFileIsModeledAsFileBodyNotLiteralText() {
        ParsedCurlRequest req = CurlParser.parseModel(
                "curl --data-binary @/tmp/payload.json https://example.com/upload");
        assertEquals(Body.Kind.FILE, req.body().kind());
        assertEquals("/tmp/payload.json", req.body().filePath());
        assertTrue(req.body().binary());
        HttpRequest http = CurlParser.parse(
                "curl --data-binary @/tmp/payload.json https://example.com/upload").toHttpRequest();
        assertEquals("/tmp/payload.json", http.getBodyFile().getPath().replace('\\', '/'));
    }

    @Test
    public void formKeepsMultipartPartsIncludingFileAndType() {
        ParsedCurlRequest req = CurlParser.parseModel(
                "curl -F 'name=Tom' -F 'doc=@/tmp/a.txt;type=text/plain;filename=a.txt' https://example.com");
        assertEquals(Body.Kind.MULTIPART, req.body().kind());
        assertEquals(2, req.body().parts().size());
        FormPart name = req.body().parts().get(0);
        assertEquals("name", name.name());
        assertFalse(name.file());
        assertEquals("Tom", name.value());
        FormPart doc = req.body().parts().get(1);
        assertTrue(doc.file());
        assertEquals("/tmp/a.txt", doc.filePath());
        assertEquals("text/plain", doc.contentType());
        assertEquals("a.txt", doc.filename());
    }

    @Test
    public void headersAreOrderedCaseInsensitiveAndAllowDuplicates() {
        ParsedCurlRequest req = CurlParser.parseModel(
                "curl https://example.com -H 'X-Test: first' -H 'x-test: second' -H 'Accept: a' -H 'Accept: b'");
        assertEquals(4, req.headers().size());
        assertEquals("second", req.header("X-TEST"));
        assertEquals("b", req.header("accept"));
    }

    @Test
    public void insecureIsPerRequestNotGlobal() {
        CurlRequest req = CurlParser.parse("curl -k https://example.com");
        HttpRequest http = req.toHttpRequest();
        assertTrue(http.isIgnoreSsl());
        assertTrue(http.isIgnoreSslSet());
    }

    @Test
    public void toCurlProxyUsesHostPortWithoutLeadingSlash() {
        HttpRequest request = HttpRequest.get("https://example.com")
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", 7890)));
        String curl = CurlRequest.toCurl(request);
        assertTrue(curl, curl.contains("127.0.0.1:7890"));
        assertFalse(curl, curl.contains("/127.0.0.1:7890"));
    }

    @Test
    public void getWithBodyEmitsExplicitMethod() {
        HttpRequest request = HttpRequest.get("https://example.com")
                .body("ping", StandardCharsets.UTF_8);
        String curl = CurlRequest.toCurl(request);
        assertTrue(curl, curl.contains("-X 'GET'"));
    }

    @Test
    public void hostWithoutSchemeIsHttp() {
        ParsedCurlRequest req = CurlParser.parseModel("curl localhost:8080/api");
        assertEquals("http://localhost:8080/api", req.url());
    }

    @Test
    public void oauth2BearerAndUnknownOptionWarnings() {
        ParsedCurlRequest req = CurlParser.parseModel(
                "curl --oauth2-bearer tok --foo-bar https://example.com");
        assertEquals("Bearer tok", req.header("Authorization") == null
                ? "Bearer " + req.auth().token() : req.header("Authorization"));
        assertFalse(req.unknownOptions().isEmpty());
        assertFalse(req.warnings().isEmpty());
    }

    @Test
    public void ansiCQuotedStringIsUnescaped() {
        ParsedCurlRequest req = CurlParser.parseModel("curl https://example.com -d $'a\\nb'");
        assertEquals("a\nb", req.body().text());
    }
}
