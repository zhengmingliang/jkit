package com.alianga.jkit.http.codegen.http;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 原始 HTTP/1.1 请求报文（对齐 curlconverter {@code http}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class HttpMessageGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "http";
    }

    @Override
    public String language() {
        return "http";
    }

    @Override
    public String library() {
        return "HTTP/1.1";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        notes.add("原始 HTTP 报文无法表达代理、证书校验、超时与重定向策略。");
        URI uri = parseUri(req.url(), notes);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (uri.getPort() > 0) {
            host = host + ":" + uri.getPort();
        }

        StringBuilder src = new StringBuilder();
        src.append(req.method().toUpperCase(Locale.ROOT)).append(' ').append(path)
                .append(" HTTP/1.1\n");

        List<Header> headers = new ArrayList<Header>(CurlGenSupport.visibleHeaders(req));
        Set<String> names = new LinkedHashSet<String>();
        for (Header h : headers) {
            names.add(h.name().toLowerCase(Locale.ROOT));
        }
        if (!names.contains("host") && !host.isEmpty()) {
            headers.add(0, new Header("Host", host));
            names.add("host");
        }
        if (!names.contains("accept")) {
            headers.add(0, new Header("Accept", "*/*"));
        }
        if (req.compressed() && !names.contains("accept-encoding")) {
            headers.add(new Header("Accept-Encoding", "deflate, gzip"));
        }

        Body body = req.body();
        String bodyText = null;
        if (body.kind() == Body.Kind.MULTIPART) {
            String boundary = "----JkitFormBoundary7MA4YWxkTrZu0gW";
            boolean hasCt = false;
            for (int i = 0; i < headers.size(); i++) {
                Header h = headers.get(i);
                if ("content-type".equalsIgnoreCase(h.name())) {
                    headers.set(i, new Header(h.name(),
                            "multipart/form-data; boundary=" + boundary));
                    hasCt = true;
                    break;
                }
            }
            if (!hasCt) {
                headers.add(new Header("Content-Type",
                        "multipart/form-data; boundary=" + boundary));
            }
            bodyText = buildMultipart(body.parts(), boundary);
            notes.add("multipart 边界为固定示例值，导入前可按需替换。");
        } else if (body.kind() == Body.Kind.FILE) {
            bodyText = "<contents of " + body.filePath() + ">";
            notes.add("正文来自本地文件，报文中仅为占位说明，请替换为实际字节。");
        } else if (body.isPresent()) {
            bodyText = body.text();
        }

        if (bodyText != null) {
            boolean hasLen = false;
            for (Header h : headers) {
                if ("content-length".equalsIgnoreCase(h.name())) {
                    hasLen = true;
                    break;
                }
            }
            if (!hasLen && body.kind() != Body.Kind.FILE) {
                headers.add(new Header("Content-Length",
                        Integer.toString(bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
            }
        }

        for (Header h : headers) {
            src.append(h.name()).append(": ").append(h.value()).append('\n');
        }
        src.append('\n');
        if (bodyText != null) {
            src.append(bodyText);
            if (!bodyText.endsWith("\n")) {
                src.append('\n');
            }
        }
        return new GeneratedCode("request.http", "http", src.toString(),
                Collections.<String>emptyList(), notes);
    }

    private static String buildMultipart(List<FormPart> parts, String boundary) {
        StringBuilder sb = new StringBuilder();
        for (FormPart p : parts) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(p.name()).append('"');
            if (p.file()) {
                String fn = p.filename() == null ? "file" : p.filename();
                sb.append("; filename=\"").append(fn).append('"');
                sb.append("\r\n");
                if (p.contentType() != null) {
                    sb.append("Content-Type: ").append(p.contentType()).append("\r\n");
                }
                sb.append("\r\n");
                sb.append("<file:").append(p.filePath()).append(">");
            } else {
                sb.append("\r\n\r\n");
                sb.append(p.value() == null ? "" : p.value());
            }
            sb.append("\r\n");
        }
        sb.append("--").append(boundary).append("--");
        return sb.toString();
    }

    private static URI parseUri(String url, List<String> notes) {
        try {
            return URI.create(url);
        } catch (Exception e) {
            notes.add("URL 无法解析为 URI，Host/路径可能不完整。");
            return URI.create("http://localhost/");
        }
    }
}
