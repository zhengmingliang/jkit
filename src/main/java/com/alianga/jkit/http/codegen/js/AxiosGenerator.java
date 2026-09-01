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

/**
 * axios。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class AxiosGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "js-axios";
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public String library() {
        return "axios";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("import axios from 'axios';\n\n");
        src.append("const response = await axios({\n");
        src.append("  method: ").append(CodeQuote.js(req.method())).append(",\n");
        src.append("  url: ").append(CodeQuote.js(req.url())).append(",\n");
        src.append("  headers: {\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("    ").append(CodeQuote.js(h.name())).append(": ")
                    .append(CodeQuote.js(h.value())).append(",\n");
        }
        src.append("  },\n");
        src.append("  maxRedirects: ").append(req.followRedirects() ? 5 : 0).append(",\n");
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 请改用 FormData 作为 data。");
        } else if (body.isPresent()) {
            src.append("  data: ").append(CodeQuote.js(body.text())).append(",\n");
        }
        if (req.insecure()) {
            notes.add("Node 下可用 httpsAgent: new https.Agent({ rejectUnauthorized: false })。");
        }
        src.append("});\n");
        src.append("console.log(response.data);\n");
        return new GeneratedCode("curl_axios.js", "javascript", src.toString(),
                Collections.singletonList("axios"), notes);
    }
}
