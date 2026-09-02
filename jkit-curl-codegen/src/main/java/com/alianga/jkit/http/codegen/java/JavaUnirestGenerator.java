package com.alianga.jkit.http.codegen.java;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.codegen.JavaEmit;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 渲染成 Java Unirest（kong.unirest）代码。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JavaUnirestGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "java-unirest";
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String library() {
        return "Unirest";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        TreeSet<String> imports = new TreeSet<String>();
        Collections.addAll(imports, "kong.unirest.HttpResponse", "kong.unirest.Unirest");

        String method = req.method().toUpperCase(Locale.ROOT);
        boolean withBody = "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);

        StringBuilder src = new StringBuilder();
        src.append("public class CurlUnirestExample {\n");
        src.append("    public static void main(String[] args) throws Exception {\n");
        StringBuilder config = new StringBuilder();
        if (req.insecure()) {
            config.append("\n                .verifySsl(false)");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        config.append("\n                .followRedirects(").append(req.followRedirects()).append(")");
        if (req.connectTimeoutSec() != null) {
            config.append("\n                .connectTimeout(").append(req.connectTimeoutSec() * 1000)
                    .append(")");
        }
        if (req.timeoutSec() != null) {
            config.append("\n                .socketTimeout(").append(req.timeoutSec() * 1000).append(")");
        }
        if (req.proxy() != null) {
            config.append("\n                .proxy(").append(JavaEmit.quote(req.proxy().host()))
                    .append(", ").append(req.proxy().port()).append(")");
            if (req.proxy().user() != null) {
                notes.add("带账号的代理请用 Unirest.config().proxy(host, port, user, password)。");
            }
        }
        if (config.length() > 0) {
            src.append("        Unirest.config()").append(config).append(";\n\n");
        }
        src.append("        HttpResponse<String> response = ").append(startCall(req, method)).append("\n");
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            src.append("                .header(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(")\n");
        }
        src.append(emitBody(req, withBody, imports, notes));
        src.append("                .asString();\n");
        src.append("        System.out.println(response.getStatus());\n");
        src.append("        System.out.println(response.getBody());\n");
        src.append("        Unirest.shutDown();\n");
        src.append("    }\n}\n");

        StringBuilder out = new StringBuilder();
        for (String i : imports) {
            out.append("import ").append(i).append(";\n");
        }
        out.append("\n").append(src);
        return new GeneratedCode("CurlUnirestExample.java", "java", out.toString(),
                Collections.singletonList("com.konghq:unirest-java:3.14.5"), notes);
    }

    private static String startCall(ParsedCurlRequest req, String method) {
        String url = JavaEmit.quote(req.url());
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method)) {
            return "Unirest." + method.toLowerCase(Locale.ROOT) + "(" + url + ")";
        }
        return "Unirest.request(" + JavaEmit.quote(method) + ", " + url + ")";
    }

    private static String emitBody(ParsedCurlRequest req, boolean withBody,
                                   TreeSet<String> imports, List<String> notes) {
        if (!withBody) {
            return "";
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            imports.add("java.io.File");
            notes.add("multipart 边界由 Unirest 自动生成，不要手动写 Content-Type。");
            StringBuilder sb = new StringBuilder();
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    sb.append("                .field(").append(JavaEmit.quote(p.name()))
                            .append(", new File(").append(JavaEmit.quote(p.filePath())).append("))\n");
                } else {
                    sb.append("                .field(").append(JavaEmit.quote(p.name()))
                            .append(", ").append(JavaEmit.quote(p.value() == null ? "" : p.value()))
                            .append(")\n");
                }
            }
            return sb.toString();
        }
        if (body.kind() == Body.Kind.FILE) {
            imports.add("java.nio.charset.StandardCharsets");
            imports.add("java.nio.file.Files");
            imports.add("java.nio.file.Paths");
            notes.add("正文来自本地文件，已按 UTF-8 文本一次性读入；二进制文件请改传 byte[]。");
            return "                .body(new String(Files.readAllBytes(Paths.get("
                    + JavaEmit.quote(body.filePath()) + ")), StandardCharsets.UTF_8))\n";
        }
        if (body.isPresent()) {
            String text = body.text() == null ? "" : body.text();
            return "                .body(" + JavaEmit.quote(text) + ")\n";
        }
        return "                .body(\"\")\n";
    }
}
