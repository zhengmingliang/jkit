package com.alianga.jkit.http.codegen.python;

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
 * Python httpx。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class HttpxGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "py-httpx";
    }

    @Override
    public String language() {
        return "python";
    }

    @Override
    public String library() {
        return "httpx";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("import httpx\n\n");
        src.append("headers = {\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("    ").append(CodeQuote.py(h.name())).append(": ")
                    .append(CodeQuote.py(h.value())).append(",\n");
        }
        src.append("}\n");
        src.append("with httpx.Client(follow_redirects=")
                .append(req.followRedirects() ? "True" : "False")
                .append(req.insecure() ? ", verify=False" : "")
                .append(") as client:\n");
        src.append("    resp = client.request(").append(CodeQuote.py(req.method().toUpperCase(Locale.ROOT)))
                .append(", ").append(CodeQuote.py(req.url())).append(", headers=headers");
        Body body = req.body();
        if (body.isPresent() && body.kind() != Body.Kind.MULTIPART && body.kind() != Body.Kind.FILE) {
            src.append(", content=").append(CodeQuote.py(body.text()));
        } else if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 请使用 files=/data= 参数。");
        }
        src.append(")\n");
        src.append("    print(resp.status_code)\n    print(resp.text)\n");
        return new GeneratedCode("curl_httpx.py", "python", src.toString(),
                Collections.singletonList("httpx"), notes);
    }
}
