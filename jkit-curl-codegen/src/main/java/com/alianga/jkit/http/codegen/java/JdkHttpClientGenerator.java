package com.alianga.jkit.http.codegen.java;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.codegen.JavaEmit;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;
import com.alianga.jkit.http.curl.ParsedCurlRequest.ProxySpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * java.net.http.HttpClient（Java 11+）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JdkHttpClientGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "java-jdk";
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String library() {
        return "JDK HttpClient";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        TreeSet<String> imports = new TreeSet<String>();
        imports.add("java.net.URI");
        imports.add("java.net.http.HttpClient");
        imports.add("java.net.http.HttpRequest");
        imports.add("java.net.http.HttpResponse");
        imports.add("java.time.Duration");

        StringBuilder setup = new StringBuilder();
        setup.append("HttpClient.Builder clientBuilder = HttpClient.newBuilder();\n");
        setup.append("        clientBuilder.followRedirects(HttpClient.Redirect.")
                .append(req.followRedirects() ? "NORMAL" : "NEVER").append(");\n");
        if (req.connectTimeoutSec() != null) {
            setup.append("        clientBuilder.connectTimeout(Duration.ofSeconds(")
                    .append(req.connectTimeoutSec()).append("));\n");
        }
        ProxySpec proxy = req.proxy();
        if (proxy != null) {
            imports.add("java.net.InetSocketAddress");
            imports.add("java.net.ProxySelector");
            setup.append("        clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(")
                    .append(JavaEmit.quote(proxy.host())).append(", ").append(proxy.port()).append(")));\n");
        }
        if (req.insecure()) {
            notes.add("JDK HttpClient 忽略证书需要自定义 SSLContext。");
        }
        setup.append("        HttpClient client = clientBuilder.build();\n");

        String method = req.method().toUpperCase(Locale.ROOT);
        String publisher = "HttpRequest.BodyPublishers.noBody()";
        if (req.body().kind() == Body.Kind.FILE) {
            imports.add("java.nio.file.Paths");
            publisher = "HttpRequest.BodyPublishers.ofFile(Paths.get("
                    + JavaEmit.quote(req.body().filePath()) + "))";
        } else if (req.body().kind() == Body.Kind.MULTIPART) {
            notes.add("JDK HttpClient 没有内置 multipart 构造器，生产环境建议 OkHttp / Apache。");
            publisher = "HttpRequest.BodyPublishers.ofString(\"\")";
        } else if (req.body().isPresent()) {
            publisher = "HttpRequest.BodyPublishers.ofString(" + JavaEmit.quote(req.body().text()) + ")";
        }

        setup.append("        HttpRequest.Builder req = HttpRequest.newBuilder()\n");
        setup.append("                .uri(URI.create(").append(JavaEmit.quote(req.url())).append("))\n");
        if (req.timeoutSec() != null) {
            setup.append("                .timeout(Duration.ofSeconds(").append(req.timeoutSec()).append("))\n");
        }
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            setup.append("                .header(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(")\n");
        }
        if ("GET".equals(method) && publisher.contains("noBody()")) {
            setup.append("                .GET();\n");
        } else if ("POST".equals(method)) {
            setup.append("                .POST(").append(publisher).append(");\n");
        } else if ("PUT".equals(method)) {
            setup.append("                .PUT(").append(publisher).append(");\n");
        } else if ("DELETE".equals(method) && publisher.contains("noBody()")) {
            setup.append("                .DELETE();\n");
        } else {
            setup.append("                .method(").append(JavaEmit.quote(method)).append(", ")
                    .append(publisher).append(");\n");
        }
        setup.append("        HttpRequest httpRequest = req.build();");

        StringBuilder src = new StringBuilder();
        for (String i : imports) {
            src.append("import ").append(i).append(";\n");
        }
        src.append("\npublic class CurlJdkExample {\n");
        src.append("    public static void main(String[] args) throws Exception {\n");
        src.append("        ").append(setup).append("\n\n");
        src.append("        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());\n");
        src.append("        System.out.println(response.statusCode());\n");
        src.append("        System.out.println(response.body());\n");
        src.append("    }\n}\n");
        return new GeneratedCode("CurlJdkExample.java", "java", src.toString(),
                Arrays.asList("Java 11+（java.net.http，无需第三方库）"), notes);
    }
}
