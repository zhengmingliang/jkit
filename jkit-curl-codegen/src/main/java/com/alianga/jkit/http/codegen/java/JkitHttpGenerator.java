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

/**
 * 生成 jkit 自身 {@link com.alianga.jkit.HttpUtils} / {@link com.alianga.jkit.http.HttpRequest} 调用。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JkitHttpGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "java-jkit";
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String library() {
        return "jkit HttpUtils";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        String method = req.method().toUpperCase(Locale.ROOT);
        StringBuilder src = new StringBuilder();
        src.append("import com.alianga.jkit.HttpUtils;\n");
        src.append("import com.alianga.jkit.http.HttpRequest;\n");
        src.append("import com.alianga.jkit.http.HttpResponse;\n\n");
        src.append("public class CurlJkitExample {\n");
        src.append("    public static void main(String[] args) throws Exception {\n");
        src.append("        HttpRequest request = new HttpRequest(")
                .append(JavaEmit.quote(method)).append(", ")
                .append(JavaEmit.quote(req.url())).append(");\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("        request.header(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(");\n");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.FILE) {
            src.append("        request.bodyFile(new java.io.File(")
                    .append(JavaEmit.quote(body.filePath())).append("));\n");
        } else if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 文件请用 HttpUtils.upload；此处仅拼接了文本字段。");
            StringBuilder form = new StringBuilder();
            for (int i = 0; i < body.parts().size(); i++) {
                if (body.parts().get(i).file()) {
                    continue;
                }
                if (form.length() > 0) {
                    form.append('&');
                }
                form.append(body.parts().get(i).name()).append('=')
                        .append(body.parts().get(i).value() == null ? "" : body.parts().get(i).value());
            }
            src.append("        request.body(").append(JavaEmit.quote(form.toString())).append(");\n");
            src.append("        request.contentType(\"application/x-www-form-urlencoded\");\n");
        } else if (body.isPresent()) {
            src.append("        request.body(").append(JavaEmit.quote(body.text())).append(");\n");
            src.append("        request.contentType(").append(JavaEmit.quote(CurlGenSupport.mediaType(req)))
                    .append(");\n");
        }
        src.append("        request.followRedirects(").append(req.followRedirects()).append(");\n");
        src.append("        request.ignoreSsl(").append(req.insecure()).append(");\n");
        ProxySpec proxy = req.proxy();
        if (proxy != null) {
            src.append("        request.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(")
                    .append(JavaEmit.quote(proxy.host())).append(", ").append(proxy.port()).append(")));\n");
        }
        if (req.connectTimeoutSec() != null) {
            src.append("        request.connectTimeoutMs(").append(req.connectTimeoutSec() * 1000).append(");\n");
        }
        if (req.timeoutSec() != null) {
            src.append("        request.readTimeoutMs(").append(req.timeoutSec() * 1000).append(");\n");
        }
        src.append("        HttpResponse response = HttpUtils.execute(request);\n");
        src.append("        try {\n");
        src.append("            System.out.println(response.code());\n");
        src.append("            System.out.println(response.body() == null ? \"\" : response.body().string());\n");
        src.append("        } finally {\n");
        src.append("            response.close();\n");
        src.append("        }\n");
        src.append("    }\n}\n");
        return new GeneratedCode("CurlJkitExample.java", "java", src.toString(),
                Arrays.asList("com.alianga:jkit"), notes);
    }
}
