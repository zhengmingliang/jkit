package com.alianga.jkit.http.codegen.csharp;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * C# HttpClient。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CsharpHttpClientGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "csharp-httpclient";
    }

    @Override
    public String language() {
        return "csharp";
    }

    @Override
    public String library() {
        return "HttpClient";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("using System.Net.Http;\nusing System.Text;\n\n");
        src.append("var client = new HttpClient();\n");
        src.append("var request = new HttpRequestMessage(new HttpMethod(")
                .append(CodeQuote.csharp(req.method().toUpperCase(Locale.ROOT))).append("), ")
                .append(CodeQuote.csharp(req.url())).append(");\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name()) || "content-type".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("request.Headers.TryAddWithoutValidation(").append(CodeQuote.csharp(h.name()))
                    .append(", ").append(CodeQuote.csharp(h.value())).append(");\n");
        }
        Body body = req.body();
        if (body.isPresent() && body.kind() != Body.Kind.FILE && body.kind() != Body.Kind.MULTIPART) {
            src.append("request.Content = new StringContent(").append(CodeQuote.csharp(body.text()))
                    .append(", Encoding.UTF8, ").append(CodeQuote.csharp(CurlGenSupport.mediaType(req)))
                    .append(");\n");
        } else if (body.kind() == Body.Kind.MULTIPART || body.kind() == Body.Kind.FILE) {
            notes.add("文件/multipart 请使用 MultipartFormDataContent。");
        }
        src.append("var response = await client.SendAsync(request);\n");
        src.append("Console.WriteLine(await response.Content.ReadAsStringAsync());\n");
        if (req.insecure()) {
            notes.add("忽略证书请配置 HttpClientHandler.ServerCertificateCustomValidationCallback。");
        }
        return new GeneratedCode("CurlHttpClient.cs", "csharp", src.toString(),
                Collections.singletonList(".NET HttpClient"), notes);
    }
}
