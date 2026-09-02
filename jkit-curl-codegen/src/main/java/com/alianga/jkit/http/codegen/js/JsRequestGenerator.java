package com.alianga.jkit.http.codegen.js;

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
 * Node.js request（已停止维护，仅用于兼容老项目）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JsRequestGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "js-request";
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public String library() {
        return "request";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        notes.add("request 已于 2020 年停止维护，新项目建议用 axios / fetch / undici。");
        StringBuilder src = new StringBuilder();
        boolean needFs = req.body().kind() == Body.Kind.FILE
                || req.body().kind() == Body.Kind.MULTIPART;
        if (needFs) {
            src.append("const fs = require('fs');\n");
        }
        src.append("const request = require('request');\n\n");
        src.append("const options = {\n");
        src.append("  method: ").append(CodeQuote.js(req.method().toUpperCase(Locale.ROOT))).append(",\n");
        src.append("  url: ").append(CodeQuote.js(req.url())).append(",\n");
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        if (!headers.isEmpty()) {
            src.append("  headers: {\n");
            for (Header h : headers) {
                src.append("    ").append(CodeQuote.js(h.name())).append(": ")
                        .append(CodeQuote.js(h.value())).append(",\n");
            }
            src.append("  },\n");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("  formData: {\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("    ").append(CodeQuote.js(p.name())).append(": fs.createReadStream(")
                            .append(CodeQuote.js(p.filePath())).append("),\n");
                } else {
                    src.append("    ").append(CodeQuote.js(p.name())).append(": ")
                            .append(CodeQuote.js(p.value() == null ? "" : p.value())).append(",\n");
                }
            }
            src.append("  },\n");
            notes.add("multipart 边界由 request 自动生成，不要手动写 Content-Type。");
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("  body: fs.readFileSync(").append(CodeQuote.js(body.filePath())).append("),\n");
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            src.append("  body: ").append(CodeQuote.js(body.text())).append(",\n");
        }
        src.append("  followRedirect: ").append(req.followRedirects()).append(",\n");
        if (req.insecure()) {
            src.append("  strictSSL: false,\n");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.timeoutSec() != null) {
            src.append("  timeout: ").append(req.timeoutSec() * 1000).append(",\n");
        }
        if (req.proxy() != null) {
            src.append("  proxy: ").append(CodeQuote.js(req.proxy().scheme() + "://"
                    + req.proxy().hostPort())).append(",\n");
        }
        src.append("};\n\n");
        src.append("request(options, function (error, response, body) {\n");
        src.append("  if (error) {\n    throw new Error(error);\n  }\n");
        src.append("  console.log(response && response.statusCode);\n");
        src.append("  console.log(body);\n");
        src.append("});\n");
        return new GeneratedCode("curl_request.js", "javascript", src.toString(),
                Collections.singletonList("request"), notes);
    }
}
