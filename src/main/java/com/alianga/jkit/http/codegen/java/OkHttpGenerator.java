package com.alianga.jkit.http.codegen.java;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.codegen.JavaEmit;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;
import com.alianga.jkit.http.curl.ParsedCurlRequest.ProxySpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 渲染成 OkHttp 4 同步代码。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class OkHttpGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "java-okhttp";
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String library() {
        return "OkHttp 4";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        TreeSet<String> imports = new TreeSet<String>();
        Collections.addAll(imports, "java.io.IOException", "okhttp3.OkHttpClient",
                "okhttp3.Request", "okhttp3.Response");

        StringBuilder client = new StringBuilder();
        client.append("OkHttpClient.Builder builder = new OkHttpClient.Builder();\n");
        client.append("        builder.followRedirects(").append(req.followRedirects()).append(");\n");
        if (req.timeoutSec() != null) {
            imports.add("java.util.concurrent.TimeUnit");
            client.append("        builder.callTimeout(").append(req.timeoutSec())
                    .append(", TimeUnit.SECONDS);\n");
        }
        ProxySpec proxy = req.proxy();
        if (proxy != null) {
            imports.add("java.net.InetSocketAddress");
            imports.add("java.net.Proxy");
            client.append("        builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(")
                    .append(JavaEmit.quote(proxy.host())).append(", ").append(proxy.port()).append(")));\n");
        }
        if (req.insecure()) {
            notes.add("已生成忽略证书校验的代码，仅用于开发环境。");
        }
        client.append("        OkHttpClient client = builder.build();");

        String bodyDecl = emitBody(req, imports, notes);
        String method = req.method().toUpperCase(Locale.ROOT);
        String methodCall;
        if (bodyDecl != null) {
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                    || "DELETE".equals(method)) {
                methodCall = "." + method.toLowerCase(Locale.ROOT) + "(body)";
            } else {
                methodCall = ".method(" + JavaEmit.quote(method) + ", body)";
            }
        } else if ("GET".equals(method)) {
            methodCall = ".get()";
        } else if ("HEAD".equals(method)) {
            methodCall = ".head()";
        } else if ("DELETE".equals(method)) {
            methodCall = ".delete()";
        } else {
            methodCall = ".method(" + JavaEmit.quote(method) + ", null)";
        }

        StringBuilder src = new StringBuilder();
        for (String i : imports) {
            src.append("import ").append(i).append(";\n");
        }
        src.append("\npublic class CurlOkHttpExample {\n");
        src.append("    public static void main(String[] args) throws Exception {\n");
        src.append("        ").append(client).append("\n");
        if (bodyDecl != null) {
            src.append("\n        ").append(bodyDecl.replace("\n", "\n        ")).append("\n");
        }
        src.append("\n        Request request = new Request.Builder()\n");
        src.append("                .url(").append(JavaEmit.quote(req.url())).append(")\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("                .addHeader(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(")\n");
        }
        src.append("                ").append(methodCall).append("\n");
        src.append("                .build();\n\n");
        src.append("        try (Response response = client.newCall(request).execute()) {\n");
        src.append("            System.out.println(response.body() != null ? response.body().string() : \"\");\n");
        src.append("        }\n");
        src.append("    }\n}\n");
        return new GeneratedCode("CurlOkHttpExample.java", "java", src.toString(),
                Arrays.asList("com.squareup.okhttp3:okhttp:4.12.0"), notes);
    }

    private static String emitBody(ParsedCurlRequest req, TreeSet<String> imports, List<String> notes) {
        Body body = req.body();
        String method = req.method().toUpperCase(Locale.ROOT);
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 边界由客户端自动生成，不要手动写 Content-Type。");
            imports.add("okhttp3.MultipartBody");
            imports.add("okhttp3.MediaType");
            imports.add("okhttp3.RequestBody");
            StringBuilder sb = new StringBuilder("RequestBody body = new MultipartBody.Builder()\n");
            sb.append("                .setType(MultipartBody.FORM)\n");
            for (FormPart part : body.parts()) {
                if (part.file()) {
                    imports.add("java.io.File");
                    String mime = part.contentType() == null ? "application/octet-stream" : part.contentType();
                    String fn = part.filename() == null ? "file" : part.filename();
                    sb.append("                .addFormDataPart(").append(JavaEmit.quote(part.name()))
                            .append(", ").append(JavaEmit.quote(fn)).append(",\n");
                    sb.append("                        RequestBody.create(new File(")
                            .append(JavaEmit.quote(part.filePath())).append("), MediaType.parse(")
                            .append(JavaEmit.quote(mime)).append(")))\n");
                } else {
                    sb.append("                .addFormDataPart(").append(JavaEmit.quote(part.name()))
                            .append(", ").append(JavaEmit.quote(part.value() == null ? "" : part.value()))
                            .append(")\n");
                }
            }
            sb.append("                .build();");
            return sb.toString();
        }
        if (body.kind() == Body.Kind.FILE) {
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
            imports.add("okhttp3.MediaType");
            imports.add("okhttp3.RequestBody");
            imports.add("java.io.File");
            return "RequestBody body = RequestBody.create(new File(" + JavaEmit.quote(body.filePath())
                    + "), MediaType.parse(" + JavaEmit.quote(CurlGenSupport.mediaType(req)) + "));";
        }
        if (body.isPresent() || "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            imports.add("okhttp3.MediaType");
            imports.add("okhttp3.RequestBody");
            if (!body.isPresent()) {
                return "RequestBody body = RequestBody.create(new byte[0], null);";
            }
            return "RequestBody body = RequestBody.create(" + JavaEmit.quote(body.text())
                    + ", MediaType.parse(" + JavaEmit.quote(CurlGenSupport.mediaType(req)) + "));";
        }
        return null;
    }
}
