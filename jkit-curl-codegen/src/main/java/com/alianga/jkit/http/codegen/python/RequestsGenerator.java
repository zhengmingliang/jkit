package com.alianga.jkit.http.codegen.python;

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
 * Python requests。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class RequestsGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "py-requests";
    }

    @Override
    public String language() {
        return "python";
    }

    @Override
    public String library() {
        return "requests";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("import requests\n\n");
        src.append("headers = {\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("    ").append(CodeQuote.py(h.name())).append(": ")
                    .append(CodeQuote.py(h.value())).append(",\n");
        }
        src.append("}\n");
        Body body = req.body();
        String extra = "";
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("files = {\n");
            src.append("}\n");
            src.append("data = {\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("# files[").append(CodeQuote.py(p.name())).append("] = open(")
                            .append(CodeQuote.py(p.filePath())).append(", 'rb')\n");
                } else {
                    src.append("    ").append(CodeQuote.py(p.name())).append(": ")
                            .append(CodeQuote.py(p.value() == null ? "" : p.value())).append(",\n");
                }
            }
            src.append("}\n");
            extra = ", data=data";
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("data = open(").append(CodeQuote.py(body.filePath())).append(", 'rb')\n");
            extra = ", data=data";
        } else if (body.isPresent()) {
            src.append("data = ").append(CodeQuote.py(body.text())).append("\n");
            extra = ", data=data";
        }
        src.append("resp = requests.request(").append(CodeQuote.py(req.method().toUpperCase(Locale.ROOT)))
                .append(", ").append(CodeQuote.py(req.url()))
                .append(", headers=headers").append(extra);
        src.append(", allow_redirects=").append(req.followRedirects() ? "True" : "False");
        if (req.insecure()) {
            src.append(", verify=False");
        }
        if (req.proxy() != null) {
            src.append(", proxies={'http': ").append(CodeQuote.py("http://" + req.proxy().hostPort()))
                    .append(", 'https': ").append(CodeQuote.py("http://" + req.proxy().hostPort())).append("}");
        }
        src.append(")\n");
        src.append("print(resp.status_code)\nprint(resp.text)\n");
        return new GeneratedCode("curl_requests.py", "python", src.toString(),
                Collections.singletonList("requests"), notes);
    }
}
