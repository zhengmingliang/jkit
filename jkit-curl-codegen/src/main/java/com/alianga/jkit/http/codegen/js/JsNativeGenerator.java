package com.alianga.jkit.http.codegen.js;

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
 * Node.js 原生 http/https，并用 follow-redirects 补上重定向跟随。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JsNativeGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "js-native";
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public String library() {
        return "http (follow-redirects)";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        boolean secure = req.url() != null && req.url().toLowerCase(Locale.ROOT).startsWith("https");
        boolean needFs = req.body().kind() == Body.Kind.FILE;
        StringBuilder src = new StringBuilder();
        if (needFs) {
            src.append("const fs = require('fs');\n");
        }
        src.append("const { ").append(secure ? "https" : "http")
                .append(" } = require('follow-redirects');\n\n");
        src.append("const url = ").append(CodeQuote.js(req.url())).append(";\n");
        src.append("const options = {\n");
        src.append("  method: ").append(CodeQuote.js(req.method().toUpperCase(Locale.ROOT))).append(",\n");
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        if (!headers.isEmpty()) {
            src.append("  headers: {\n");
            for (Header h : headers) {
                src.append("    ").append(CodeQuote.js(h.name())).append(": ")
                        .append(CodeQuote.js(h.value())).append(",\n");
            }
            src.append("  },\n");
        }
        src.append("  maxRedirects: ").append(req.followRedirects() ? 5 : 0).append(",\n");
        if (secure) {
            src.append("  rejectUnauthorized: ").append(!req.insecure()).append(",\n");
            if (req.insecure()) {
                notes.add("已关闭证书校验，仅用于开发环境。");
            }
        }
        src.append("};\n\n");
        src.append("const req = ").append(secure ? "https" : "http")
                .append(".request(url, options, function (res) {\n");
        src.append("  const chunks = [];\n");
        src.append("  res.on('data', function (chunk) {\n    chunks.push(chunk);\n  });\n");
        src.append("  res.on('end', function () {\n");
        src.append("    console.log(res.statusCode);\n");
        src.append("    console.log(Buffer.concat(chunks).toString());\n");
        src.append("  });\n");
        src.append("});\n\n");
        src.append("req.on('error', function (err) {\n  console.error(err);\n});\n");
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 需要自己拼 boundary，或改用 form-data 包。");
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("req.end(fs.readFileSync(").append(CodeQuote.js(body.filePath())).append("));\n");
            notes.add("正文来自本地文件，已一次性读入内存，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            src.append("req.write(").append(CodeQuote.js(body.text())).append(");\n");
            src.append("req.end();\n");
        } else {
            src.append("req.end();\n");
        }
        return new GeneratedCode("curl_native.js", "javascript", src.toString(),
                Collections.singletonList("follow-redirects"), notes);
    }
}
