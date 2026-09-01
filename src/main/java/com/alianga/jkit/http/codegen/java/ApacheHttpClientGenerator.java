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
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Apache HttpClient 5 classic API。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ApacheHttpClientGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "java-apache";
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String library() {
        return "Apache HttpClient 5";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        TreeSet<String> imports = new TreeSet<String>();
        imports.add("org.apache.hc.client5.http.classic.methods.HttpUriRequestBase");
        imports.add("org.apache.hc.client5.http.impl.classic.CloseableHttpClient");
        imports.add("org.apache.hc.client5.http.impl.classic.HttpClients");
        imports.add("org.apache.hc.core5.http.io.entity.EntityUtils");
        imports.add("org.apache.hc.core5.http.message.StatusLine");

        StringBuilder body = new StringBuilder();
        body.append("HttpUriRequestBase request = new HttpUriRequestBase(")
                .append(JavaEmit.quote(req.method())).append(", java.net.URI.create(")
                .append(JavaEmit.quote(req.url())).append("));\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            if ("content-type".equalsIgnoreCase(h.name()) && req.body().kind() == Body.Kind.MULTIPART) {
                continue;
            }
            body.append("        request.addHeader(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(");\n");
        }
        if (req.body().kind() == Body.Kind.MULTIPART) {
            imports.add("org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder");
            imports.add("org.apache.hc.core5.http.ContentType");
            imports.add("java.io.File");
            body.append("        MultipartEntityBuilder mp = MultipartEntityBuilder.create();\n");
            for (FormPart part : req.body().parts()) {
                if (part.file()) {
                    String ct = part.contentType() == null
                            ? "ContentType.DEFAULT_BINARY"
                            : "ContentType.create(" + JavaEmit.quote(part.contentType()) + ")";
                    body.append("        mp.addBinaryBody(").append(JavaEmit.quote(part.name()))
                            .append(", new File(").append(JavaEmit.quote(part.filePath())).append("), ")
                            .append(ct).append(", ")
                            .append(JavaEmit.quote(part.filename() == null ? "file" : part.filename()))
                            .append(");\n");
                } else {
                    body.append("        mp.addTextBody(").append(JavaEmit.quote(part.name())).append(", ")
                            .append(JavaEmit.quote(part.value() == null ? "" : part.value())).append(");\n");
                }
            }
            body.append("        request.setEntity(mp.build());\n");
        } else if (req.body().kind() == Body.Kind.FILE) {
            imports.add("org.apache.hc.core5.http.io.entity.FileEntity");
            imports.add("org.apache.hc.core5.http.ContentType");
            imports.add("java.io.File");
            body.append("        request.setEntity(new FileEntity(new File(")
                    .append(JavaEmit.quote(req.body().filePath())).append("), ContentType.parse(")
                    .append(JavaEmit.quote(CurlGenSupport.mediaType(req))).append(")));\n");
        } else if (req.body().isPresent()) {
            imports.add("org.apache.hc.core5.http.io.entity.StringEntity");
            imports.add("org.apache.hc.core5.http.ContentType");
            body.append("        request.setEntity(new StringEntity(")
                    .append(JavaEmit.quote(req.body().text())).append(", ContentType.parse(")
                    .append(JavaEmit.quote(CurlGenSupport.mediaType(req))).append(")));\n");
        }

        StringBuilder src = new StringBuilder();
        for (String i : imports) {
            src.append("import ").append(i).append(";\n");
        }
        src.append("\npublic class CurlApacheExample {\n");
        src.append("    public static void main(String[] args) throws Exception {\n");
        src.append("        CloseableHttpClient client = HttpClients.createDefault();\n");
        src.append("        ").append(body);
        src.append("        try {\n");
        src.append("            client.execute(request, response -> {\n");
        src.append("                System.out.println(new StatusLine(response));\n");
        src.append("                System.out.println(EntityUtils.toString(response.getEntity()));\n");
        src.append("                return null;\n");
        src.append("            });\n");
        src.append("        } finally {\n");
        src.append("            client.close();\n");
        src.append("        }\n");
        src.append("    }\n}\n");
        if (req.insecure()) {
            notes.add("忽略证书请在 HttpClientBuilder 上配置 TrustAllStrategy，仅用于开发环境。");
        }
        return new GeneratedCode("CurlApacheExample.java", "java", src.toString(),
                Arrays.asList("org.apache.httpcomponents.client5:httpclient5:5.4.1"), notes);
    }
}
