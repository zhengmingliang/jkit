package com.alianga.jkit.http;

import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.codegen.GeneratorRegistry;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 各语言 / SDK 代码生成器回归。
 */
public class CurlCodegenTest {

    private static final String[] IDS = {
            "java-okhttp", "java-apache", "java-jdk", "java-jkit",
            "kotlin-okhttp", "js-fetch", "js-axios",
            "py-requests", "py-httpx", "go-nethttp",
            "csharp-httpclient", "php-curl"
    };

    private static final String SAMPLE = "curl -kLs -XPOST 'https://example.com/v1/chat' "
            + "-H 'Authorization: Bearer tok' "
            + "--json '{\"q\":\"hi\"}' "
            + "-x 127.0.0.1:7890";

    @Test
    public void registryContainsAllAdvertisedGenerators() {
        Set<String> ids = new HashSet<String>();
        for (com.alianga.jkit.http.codegen.CodeGenerator g : GeneratorRegistry.get().list()) {
            ids.add(g.id());
        }
        assertTrue(ids.containsAll(Arrays.asList(IDS)));
        assertEquals(12, IDS.length);
    }

    @Test
    public void everyGeneratorEmitsUrlMethodAndJsonBody() {
        ParsedCurlRequest model = CurlParser.parseModel(SAMPLE);
        for (String id : IDS) {
            GeneratedCode code = GeneratorRegistry.get().generate(id, model);
            assertNotNull(id, code.source());
            assertFalse(id, code.source().trim().isEmpty());
            assertTrue(id + " missing url\n" + code.source(),
                    code.source().contains("https://example.com/v1/chat"));
            assertTrue(id + " missing json\n" + code.source(),
                    code.source().contains("hi") || code.source().contains("\\\"q\\\""));
        }
    }

    @Test
    public void jkitGeneratorUsesPerRequestIgnoreSslNotGlobalSetter() {
        GeneratedCode code = CurlParser.generate("java-jkit", SAMPLE);
        assertTrue(code.source().contains("ignoreSsl(true)"));
        assertFalse(code.source().contains("setIgnoreSsl"));
        assertTrue(code.source().contains("HttpUtils.execute"));
    }

    @Test
    public void okHttpAndFetchIncludeHeaders() {
        GeneratedCode ok = CurlParser.generate("java-okhttp", SAMPLE);
        assertTrue(ok.source().contains("addHeader"));
        assertTrue(ok.source().contains("OkHttpClient"));
        GeneratedCode fetch = CurlParser.generate("js-fetch", SAMPLE);
        assertTrue(fetch.source().contains("fetch("));
        assertTrue(fetch.language().equals("javascript"));
    }

    @Test
    public void pythonGoCsharpPhpCompileShape() {
        assertTrue(CurlParser.generate("py-requests", SAMPLE).source().contains("requests.request"));
        assertTrue(CurlParser.generate("py-httpx", SAMPLE).source().contains("httpx.Client"));
        assertTrue(CurlParser.generate("go-nethttp", SAMPLE).source().contains("http.NewRequest"));
        assertTrue(CurlParser.generate("csharp-httpclient", SAMPLE).source().contains("HttpClient"));
        assertTrue(CurlParser.generate("php-curl", SAMPLE).source().contains("curl_init"));
        assertTrue(CurlParser.generate("kotlin-okhttp", SAMPLE).source().contains("fun main"));
        assertTrue(CurlParser.generate("java-apache", SAMPLE).source().contains("HttpUriRequestBase"));
        assertTrue(CurlParser.generate("java-jdk", SAMPLE).source().contains("java.net.http.HttpClient"));
    }

    @Test
    public void multipartAppearsInOkHttpAndCurlRoundTrip() {
        String curl = "curl -F 'a=1' -F 'f=@/tmp/x.bin;filename=x.bin' https://example.com/up";
        ParsedCurlRequest model = CurlParser.parseModel(curl);
        String ok = GeneratorRegistry.get().generate("java-okhttp", model).source();
        assertTrue(ok.contains("MultipartBody"));
        assertTrue(ok.contains("/tmp/x.bin"));
        String round = CurlRequest.toCurl(model);
        assertTrue(round.contains("-F"));
        assertTrue(round.contains("@/tmp/x.bin"));
    }
}
