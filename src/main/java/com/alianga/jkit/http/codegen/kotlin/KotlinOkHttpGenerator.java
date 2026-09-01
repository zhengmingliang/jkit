package com.alianga.jkit.http.codegen.kotlin;

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
import java.util.Locale;

/**
 * Kotlin + OkHttp。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class KotlinOkHttpGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "kotlin-okhttp";
    }

    @Override
    public String language() {
        return "kotlin";
    }

    @Override
    public String library() {
        return "OkHttp";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        String method = req.method().toUpperCase(Locale.ROOT);
        StringBuilder src = new StringBuilder();
        src.append("import okhttp3.*\nimport java.io.File\n\n");
        src.append("fun main() {\n");
        src.append("    val client = OkHttpClient.Builder()\n");
        src.append("        .followRedirects(").append(req.followRedirects()).append(")\n");
        src.append("        .build()\n");
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("    val body = MultipartBody.Builder().setType(MultipartBody.FORM)\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("        .addFormDataPart(").append(JavaEmit.quote(p.name())).append(", ")
                            .append(JavaEmit.quote(p.filename() == null ? "file" : p.filename()))
                            .append(", RequestBody.create(File(").append(JavaEmit.quote(p.filePath()))
                            .append("), MediaType.parse(\"application/octet-stream\")))\n");
                } else {
                    src.append("        .addFormDataPart(").append(JavaEmit.quote(p.name())).append(", ")
                            .append(JavaEmit.quote(p.value() == null ? "" : p.value())).append(")\n");
                }
            }
            src.append("        .build()\n");
        } else if (body.isPresent() || body.kind() == Body.Kind.FILE) {
            if (body.kind() == Body.Kind.FILE) {
                src.append("    val body = RequestBody.create(File(")
                        .append(JavaEmit.quote(body.filePath()))
                        .append("), MediaType.parse(").append(JavaEmit.quote(CurlGenSupport.mediaType(req)))
                        .append("))\n");
            } else {
                src.append("    val body = RequestBody.create(").append(JavaEmit.quote(body.text()))
                        .append(", MediaType.parse(").append(JavaEmit.quote(CurlGenSupport.mediaType(req)))
                        .append("))\n");
            }
        }
        src.append("    val request = Request.Builder().url(").append(JavaEmit.quote(req.url())).append(")\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("        .addHeader(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(")\n");
        }
        if (body.isPresent() || body.kind() == Body.Kind.FILE || body.kind() == Body.Kind.MULTIPART) {
            src.append("        .method(").append(JavaEmit.quote(method)).append(", body)\n");
        } else {
            src.append("        .method(").append(JavaEmit.quote(method)).append(", null)\n");
        }
        src.append("        .build()\n");
        src.append("    client.newCall(request).execute().use { println(it.body?.string() ?: \"\") }\n");
        src.append("}\n");
        if (req.insecure()) {
            notes.add("忽略证书请在 OkHttpClient.Builder 上配置 TrustManager。");
        }
        return new GeneratedCode("curl_okhttp.kt", "kotlin", src.toString(),
                Arrays.asList("com.squareup.okhttp3:okhttp:4.12.0"), notes);
    }
}
