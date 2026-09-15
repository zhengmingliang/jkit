package com.alianga.jkit.http.codegen.csharp;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
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
        Body body = req.body();
        boolean multipart = body.kind() == Body.Kind.MULTIPART;
        boolean file = body.kind() == Body.Kind.FILE;

        StringBuilder src = new StringBuilder();
        src.append("using System.Net;\nusing System.Net.Http;\nusing System.Text;\n");
        if (multipart || file) {
            src.append("using System.IO;\n");
        }
        src.append("\n");
        if (req.proxy() != null) {
            src.append("var handler = new HttpClientHandler();\n");
            src.append("handler.Proxy = new WebProxy(")
                    .append(CodeQuote.csharp(CurlGenSupport.proxyUrl(req.proxy()))).append(");\n");
            src.append("var client = new HttpClient(handler);\n");
        } else {
            src.append("var client = new HttpClient();\n");
        }
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
        if (multipart) {
            src.append("var multipart = new MultipartFormDataContent();\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("{\n");
                    src.append("var fileContent = new ByteArrayContent(File.ReadAllBytes(")
                            .append(CodeQuote.csharp(p.filePath())).append("));\n");
                    if (p.contentType() != null) {
                        src.append("fileContent.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(")
                                .append(CodeQuote.csharp(p.contentType())).append(");\n");
                    }
                    src.append("multipart.Add(fileContent, ").append(CodeQuote.csharp(p.name())).append(", ")
                            .append(CodeQuote.csharp(p.filename() == null ? "file" : p.filename())).append(");\n");
                    src.append("}\n");
                } else {
                    src.append("multipart.Add(new StringContent(")
                            .append(CodeQuote.csharp(p.value() == null ? "" : p.value()))
                            .append(", Encoding.UTF8), ").append(CodeQuote.csharp(p.name())).append(");\n");
                }
            }
            src.append("request.Content = multipart;\n");
        } else if (file) {
            src.append("request.Content = new ByteArrayContent(File.ReadAllBytes(")
                    .append(CodeQuote.csharp(body.filePath())).append("));\n");
        } else if (body.isPresent()) {
            src.append("request.Content = new StringContent(").append(CodeQuote.csharp(body.text()))
                    .append(", Encoding.UTF8, ").append(CodeQuote.csharp(CurlGenSupport.mediaType(req)))
                    .append(");\n");
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
