package com.alianga.jkit.http.codegen.har;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;
import com.alianga.jkit.http.curl.ParsedCurlRequest.QueryParam;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HAR 1.2 JSON（单条 entry，对齐 curlconverter {@code har}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class HarGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "har";
    }

    @Override
    public String language() {
        return "har";
    }

    @Override
    public String library() {
        return "HAR 1.2";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        if (req.insecure()) {
            notes.add("HAR 无法表达 -k / insecure。");
        }
        if (req.proxy() != null) {
            notes.add("HAR 无法表达代理设置。");
        }
        if (req.timeoutSec() != null || req.connectTimeoutSec() != null) {
            notes.add("HAR 无法表达超时。");
        }
        if (req.body().kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 以 text 占位写出，导入工具可能无法还原文件字段。");
        }
        if (req.body().kind() == Body.Kind.FILE) {
            notes.add("文件正文仅写入路径占位，请替换为实际内容后再导入。");
        }

        StringBuilder src = new StringBuilder();
        src.append("{\n");
        src.append("    \"log\": {\n");
        src.append("        \"version\": \"1.2\",\n");
        src.append("        \"creator\": {\n");
        src.append("            \"name\": \"jkit-curl-codegen\",\n");
        src.append("            \"version\": \"2.0.1\"\n");
        src.append("        },\n");
        src.append("        \"entries\": [\n");
        src.append("            {\n");
        src.append("                \"request\": ");
        appendRequest(src, req, "                ");
        src.append("\n");
        src.append("            }\n");
        src.append("        ]\n");
        src.append("    }\n");
        src.append("}\n");
        return new GeneratedCode("request.har.json", "har", src.toString(),
                Collections.<String>emptyList(), notes);
    }

    private static void appendRequest(StringBuilder src, ParsedCurlRequest req, String indent) {
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        List<NameValue> cookies = extractCookies(headers);
        List<NameValue> query = extractQuery(req);
        Body body = req.body();

        src.append("{\n");
        src.append(indent).append("    \"method\": ").append(CodeQuote.json(req.method().toUpperCase(Locale.ROOT)))
                .append(",\n");
        src.append(indent).append("    \"url\": ").append(CodeQuote.json(req.url())).append(",\n");
        src.append(indent).append("    \"httpVersion\": \"HTTP/1.1\",\n");
        src.append(indent).append("    \"cookies\": ");
        appendNameValues(src, cookies, indent + "    ");
        src.append(",\n");
        src.append(indent).append("    \"headers\": ");
        List<NameValue> headerPairs = new ArrayList<NameValue>();
        for (Header h : headers) {
            if ("cookie".equalsIgnoreCase(h.name()) && !cookies.isEmpty()) {
                continue;
            }
            headerPairs.add(new NameValue(h.name(), h.value()));
        }
        appendNameValues(src, headerPairs, indent + "    ");
        src.append(",\n");
        src.append(indent).append("    \"queryString\": ");
        appendNameValues(src, query, indent + "    ");
        src.append(",\n");
        if (body.isPresent() || body.kind() == Body.Kind.FILE || body.kind() == Body.Kind.MULTIPART) {
            src.append(indent).append("    \"postData\": ");
            appendPostData(src, req, indent + "    ");
            src.append(",\n");
        }
        int bodySize = -1;
        if (body.isPresent() && body.text() != null) {
            bodySize = body.text().getBytes(StandardCharsets.UTF_8).length;
        }
        src.append(indent).append("    \"headersSize\": -1,\n");
        src.append(indent).append("    \"bodySize\": ").append(bodySize).append("\n");
        src.append(indent).append("}");
    }

    private static void appendPostData(StringBuilder src, ParsedCurlRequest req, String indent) {
        Body body = req.body();
        String mime = CurlGenSupport.mediaType(req);
        src.append("{\n");
        src.append(indent).append("    \"mimeType\": ").append(CodeQuote.json(mime == null ? "" : mime))
                .append(",\n");
        if (body.kind() == Body.Kind.URLENCODED && body.text() != null) {
            List<NameValue> params = parseUrlEncoded(body.text());
            if (!params.isEmpty()) {
                src.append(indent).append("    \"params\": ");
                appendNameValues(src, params, indent + "    ");
                src.append("\n");
                src.append(indent).append("}");
                return;
            }
        }
        String text;
        if (body.kind() == Body.Kind.FILE) {
            text = "<file:" + body.filePath() + ">";
        } else if (body.kind() == Body.Kind.MULTIPART) {
            text = "<multipart; parts=" + body.parts().size() + ">";
        } else {
            text = body.text() == null ? "" : body.text();
        }
        src.append(indent).append("    \"text\": ").append(CodeQuote.json(text)).append("\n");
        src.append(indent).append("}");
    }

    private static void appendNameValues(StringBuilder src, List<NameValue> items, String indent) {
        if (items.isEmpty()) {
            src.append("[]");
            return;
        }
        src.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            NameValue nv = items.get(i);
            src.append(indent).append("    {\n");
            src.append(indent).append("        \"name\": ").append(CodeQuote.json(nv.name)).append(",\n");
            src.append(indent).append("        \"value\": ").append(CodeQuote.json(nv.value)).append("\n");
            src.append(indent).append("    }");
            if (i < items.size() - 1) {
                src.append(',');
            }
            src.append('\n');
        }
        src.append(indent).append("]");
    }

    private static List<NameValue> extractCookies(List<Header> headers) {
        List<NameValue> out = new ArrayList<NameValue>();
        for (Header h : headers) {
            if (!"cookie".equalsIgnoreCase(h.name())) {
                continue;
            }
            String[] parts = h.value().split(";");
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int eq = p.indexOf('=');
                if (eq < 0) {
                    out.add(new NameValue(p, ""));
                } else {
                    out.add(new NameValue(p.substring(0, eq).trim(), p.substring(eq + 1).trim()));
                }
            }
        }
        return out;
    }

    private static List<NameValue> extractQuery(ParsedCurlRequest req) {
        List<NameValue> out = new ArrayList<NameValue>();
        for (QueryParam q : req.query()) {
            out.add(new NameValue(q.name(), q.value()));
        }
        try {
            URI uri = URI.create(req.url());
            String raw = uri.getRawQuery();
            if (raw != null && !raw.isEmpty() && out.isEmpty()) {
                out.addAll(parseUrlEncoded(raw));
            }
        } catch (Exception ignored) {
            // keep model query only
        }
        return out;
    }

    private static List<NameValue> parseUrlEncoded(String text) {
        List<NameValue> out = new ArrayList<NameValue>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] pairs = text.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.add(new NameValue(decode(pair), ""));
            } else {
                out.add(new NameValue(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1))));
            }
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%20"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static final class NameValue {
        final String name;
        final String value;

        NameValue(String name, String value) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
        }
    }
}
