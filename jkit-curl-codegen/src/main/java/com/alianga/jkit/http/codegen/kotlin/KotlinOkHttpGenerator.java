package com.alianga.jkit.http.codegen.kotlin;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
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
        // okhttp 4.x 把 MediaType.parse / RequestBody.create 都标了废弃（Kotlin 下 parse 直接是 ERROR 级），
        // 只能用扩展函数 toMediaType / toRequestBody / asRequestBody，否则生成的代码编译不过。
        src.append("import okhttp3.*\n")
                .append("import okhttp3.MediaType.Companion.toMediaType\n")
                .append("import okhttp3.RequestBody.Companion.asRequestBody\n")
                .append("import okhttp3.RequestBody.Companion.toRequestBody\n")
                .append("import java.io.File\n");
        if (req.insecure()) {
            src.append("import java.security.SecureRandom\n")
                    .append("import java.security.cert.X509Certificate\n")
                    .append("import javax.net.ssl.SSLContext\n")
                    .append("import javax.net.ssl.TrustManager\n")
                    .append("import javax.net.ssl.X509TrustManager\n");
        }
        src.append("\nfun main() {\n");
        if (req.insecure()) {
            // -k 必须真的把校验关掉，只写注释等于静默改变语义
            src.append("    // -k：忽略证书校验，仅用于开发环境\n")
                    .append("    val trustAll = object : X509TrustManager {\n")
                    .append("        override fun checkClientTrusted(chain: Array<out X509Certificate>,"
                            + " authType: String) {\n        }\n")
                    .append("        override fun checkServerTrusted(chain: Array<out X509Certificate>,"
                            + " authType: String) {\n        }\n")
                    .append("        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()\n")
                    .append("    }\n")
                    .append("    val sslContext = SSLContext.getInstance(\"SSL\")\n")
                    .append("    sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())\n");
        }
        src.append("    val client = OkHttpClient.Builder()\n");
        src.append("        .followRedirects(").append(req.followRedirects()).append(")\n");
        if (req.proxy() != null) {
            src.append("        .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(")
                    .append(CodeQuote.kotlin(req.proxy().host())).append(", ").append(req.proxy().port())
                    .append(")))\n");
            if (req.proxy().user() != null) {
                src.append("        .proxyAuthenticator { _, response ->\n");
                src.append("            response.request.newBuilder()\n");
                src.append("                .header(\"Proxy-Authorization\", Credentials.basic(")
                        .append(CodeQuote.kotlin(req.proxy().user())).append(", ")
                        .append(CodeQuote.kotlin(req.proxy().password() == null ? "" : req.proxy().password()))
                        .append("))\n");
                src.append("                .build()\n        }\n");
            }
        }
        if (req.insecure()) {
            src.append("        .sslSocketFactory(sslContext.socketFactory, trustAll)\n")
                    .append("        .hostnameVerifier { _, _ -> true }\n");
        }
        src.append("        .build()\n");
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("    val body = MultipartBody.Builder().setType(MultipartBody.FORM)\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String fileType = p.contentType() == null ? "application/octet-stream" : p.contentType();
                    src.append("        .addFormDataPart(").append(CodeQuote.kotlin(p.name())).append(", ")
                            .append(CodeQuote.kotlin(p.filename() == null ? "file" : p.filename()))
                            .append(", File(").append(CodeQuote.kotlin(p.filePath()))
                            .append(").asRequestBody(").append(CodeQuote.kotlin(fileType))
                            .append(".toMediaType()))\n");
                } else {
                    src.append("        .addFormDataPart(").append(CodeQuote.kotlin(p.name())).append(", ")
                            .append(CodeQuote.kotlin(p.value() == null ? "" : p.value())).append(")\n");
                }
            }
            src.append("        .build()\n");
        } else if (body.isPresent() || body.kind() == Body.Kind.FILE) {
            if (body.kind() == Body.Kind.FILE) {
                // okhttp 的签名是 create(MediaType?, ...)，mediaType 必须放在第一个参数
                src.append("    val body = File(").append(CodeQuote.kotlin(body.filePath()))
                        .append(").asRequestBody(").append(CodeQuote.kotlin(CurlGenSupport.mediaType(req)))
                        .append(".toMediaType())\n");
            } else {
                src.append("    val body = ").append(CodeQuote.kotlin(body.text()))
                        .append(".toRequestBody(").append(CodeQuote.kotlin(CurlGenSupport.mediaType(req)))
                        .append(".toMediaType())\n");
            }
        }
        src.append("    val request = Request.Builder().url(").append(CodeQuote.kotlin(req.url())).append(")\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("        .addHeader(").append(CodeQuote.kotlin(h.name())).append(", ")
                    .append(CodeQuote.kotlin(h.value())).append(")\n");
        }
        if (body.isPresent() || body.kind() == Body.Kind.FILE || body.kind() == Body.Kind.MULTIPART) {
            src.append("        .method(").append(CodeQuote.kotlin(method)).append(", body)\n");
        } else {
            src.append("        .method(").append(CodeQuote.kotlin(method)).append(", null)\n");
        }
        src.append("        .build()\n");
        src.append("    client.newCall(request).execute().use { println(it.body?.string() ?: \"\") }\n");
        src.append("}\n");
        if (req.insecure()) {
            notes.add("-k：已生成忽略证书与主机名校验的代码，仅用于开发环境。");
        }
        return new GeneratedCode("curl_okhttp.kt", "kotlin", src.toString(),
                Arrays.asList("com.squareup.okhttp3:okhttp:4.12.0"), notes);
    }
}
