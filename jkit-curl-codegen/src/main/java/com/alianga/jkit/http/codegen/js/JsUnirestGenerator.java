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
 * Node.js unirest。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JsUnirestGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "js-unirest";
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public String library() {
        return "unirest";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("const unirest = require('unirest');\n\n");
        src.append("const req = unirest(").append(CodeQuote.js(req.method().toUpperCase(Locale.ROOT)))
                .append(", ").append(CodeQuote.js(req.url())).append(");\n\n");
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        if (!headers.isEmpty()) {
            src.append("req.headers({\n");
            for (Header h : headers) {
                src.append("  ").append(CodeQuote.js(h.name())).append(": ")
                        .append(CodeQuote.js(h.value())).append(",\n");
            }
            src.append("});\n\n");
        }
        if (req.insecure()) {
            src.append("req.strictSSL(false);\n");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        src.append("req.followRedirect(").append(req.followRedirects()).append(");\n");
        if (req.timeoutSec() != null) {
            src.append("req.timeout(").append(req.timeoutSec() * 1000).append(");\n");
        }
        if (req.proxy() != null) {
            src.append("req.proxy(").append(CodeQuote.js(req.proxy().scheme() + "://"
                    + req.proxy().hostPort())).append(");\n");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 边界由 unirest 自动生成，不要手动写 Content-Type。");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("req.attach(").append(CodeQuote.js(p.name())).append(", ")
                            .append(CodeQuote.js(p.filePath())).append(");\n");
                } else {
                    src.append("req.field(").append(CodeQuote.js(p.name())).append(", ")
                            .append(CodeQuote.js(p.value() == null ? "" : p.value())).append(");\n");
                }
            }
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("req.attach(").append(CodeQuote.js("file")).append(", ")
                    .append(CodeQuote.js(body.filePath())).append(");\n");
            notes.add("文件正文按 multipart 单字段上传；若服务端要求裸二进制，请改用 fs.createReadStream。");
        } else if (body.isPresent()) {
            src.append("req.send(").append(CodeQuote.js(body.text())).append(");\n");
        }
        src.append("\nreq.end(function (res) {\n");
        src.append("  if (res.error) {\n    throw new Error(res.error);\n  }\n");
        src.append("  console.log(res.code);\n");
        src.append("  console.log(res.body);\n");
        src.append("});\n");
        return new GeneratedCode("curl_unirest.js", "javascript", src.toString(),
                Collections.singletonList("unirest"), notes);
    }
}
