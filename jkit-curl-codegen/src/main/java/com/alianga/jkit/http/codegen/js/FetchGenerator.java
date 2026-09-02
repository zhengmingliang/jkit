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

/**
 * 浏览器 / Node 18+ fetch。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class FetchGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "js-fetch";
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public String library() {
        return "fetch";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("const headers = {\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("  ").append(CodeQuote.js(h.name())).append(": ")
                    .append(CodeQuote.js(h.value())).append(",\n");
        }
        src.append("};\n");
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("const body = new FormData();\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("body.append(").append(CodeQuote.js(p.name()))
                            .append(", /* file: ").append(p.filePath()).append(" */);\n");
                    notes.add("浏览器 FormData 文件请换成 File/Blob 对象。");
                } else {
                    src.append("body.append(").append(CodeQuote.js(p.name())).append(", ")
                            .append(CodeQuote.js(p.value() == null ? "" : p.value())).append(");\n");
                }
            }
        } else if (body.kind() == Body.Kind.FILE) {
            notes.add("fetch 读取本地文件需在 Node 中用 fs，或在浏览器中用 input[type=file]。");
            src.append("const body = undefined;\n");
        } else if (body.isPresent()) {
            src.append("const body = ").append(CodeQuote.js(body.text())).append(";\n");
        } else {
            src.append("const body = undefined;\n");
        }
        src.append("const response = await fetch(").append(CodeQuote.js(req.url())).append(", {\n");
        src.append("  method: ").append(CodeQuote.js(req.method())).append(",\n");
        src.append("  headers,\n");
        src.append("  body,\n");
        src.append("  redirect: ").append(req.followRedirects() ? "'follow'" : "'manual'").append(",\n");
        src.append("});\n");
        src.append("console.log(await response.text());\n");
        if (req.insecure()) {
            notes.add("fetch 无法在浏览器里关闭 TLS 校验。");
        }
        return new GeneratedCode("curl_fetch.js", "javascript", src.toString(),
                Collections.singletonList("fetch (浏览器 / Node 18+)"), notes);
    }
}
