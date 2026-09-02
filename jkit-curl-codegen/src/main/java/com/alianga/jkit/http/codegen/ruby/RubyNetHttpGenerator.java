package com.alianga.jkit.http.codegen.ruby;

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
 * Ruby Net::HTTP。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class RubyNetHttpGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "ruby-nethttp";
    }

    @Override
    public String language() {
        return "ruby";
    }

    @Override
    public String library() {
        return "Net::HTTP";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("require 'net/http'\n");
        src.append("require 'uri'\n");
        if (req.insecure()) {
            src.append("require 'openssl'\n");
        }
        if (req.body().kind() == Body.Kind.MULTIPART) {
            src.append("require 'securerandom'\n");
        }
        src.append("\nuri = URI(").append(CodeQuote.ruby(req.url())).append(")\n");
        if (req.proxy() != null) {
            src.append("http = Net::HTTP.new(uri.host, uri.port, ")
                    .append(CodeQuote.ruby(req.proxy().host())).append(", ").append(req.proxy().port())
                    .append(")\n");
            notes.add("Net::HTTP 的内置代理只对 http 明文生效，https 请改用 Net::HTTP::Proxy + CONNECT 或 http_proxy 环境变量。");
        } else {
            src.append("http = Net::HTTP.new(uri.host, uri.port)\n");
        }
        src.append("http.use_ssl = uri.scheme == ").append(CodeQuote.ruby("https")).append("\n");
        if (req.insecure()) {
            src.append("http.verify_mode = OpenSSL::SSL::VERIFY_NONE\n");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.connectTimeoutSec() != null) {
            src.append("http.open_timeout = ").append(req.connectTimeoutSec()).append("\n");
        }
        if (req.timeoutSec() != null) {
            src.append("http.read_timeout = ").append(req.timeoutSec()).append("\n");
        }
        if (req.proxy() != null && req.proxy().user() != null) {
            src.append("http.proxy_user = ").append(CodeQuote.ruby(req.proxy().user())).append("\n");
            src.append("http.proxy_pass = ")
                    .append(CodeQuote.ruby(req.proxy().password() == null ? "" : req.proxy().password()))
                    .append("\n");
        }
        src.append("\n").append(emitRequest(req, notes));
        src.append("response = http.request(request)\n");
        src.append("puts response.code\n");
        src.append("puts response.body\n");
        return new GeneratedCode("curl_net_http.rb", "ruby", src.toString(),
                Collections.<String>emptyList(), notes);
    }

    private static String emitRequest(ParsedCurlRequest req, List<String> notes) {
        String method = req.method().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        if (isStandardMethod(method)) {
            sb.append("request = Net::HTTP::").append(method.charAt(0))
                    .append(method.substring(1).toLowerCase(Locale.ROOT)).append(".new(uri)\n");
        } else {
            sb.append("request = Net::HTTPGenericRequest.new(").append(CodeQuote.ruby(method))
                    .append(", true, true, uri)\n");
        }
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            sb.append("request[").append(CodeQuote.ruby(h.name())).append("] = ")
                    .append(CodeQuote.ruby(h.value())).append("\n");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 边界由 SecureRandom 生成，不要手动写 Content-Type。");
            sb.append("boundary = ").append(CodeQuote.ruby("----JkitFormBoundary"))
                    .append(" + SecureRandom.hex(16)\n");
            sb.append("parts = []\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String mime = p.contentType() == null ? "application/octet-stream" : p.contentType();
                    String fn = p.filename() == null ? "file" : p.filename();
                    sb.append("parts << \"--#{boundary}\\r\\n\"\n");
                    sb.append("parts << ").append(CodeQuote.ruby("Content-Disposition: form-data; name=\""
                            + p.name() + "\"; filename=\"" + fn + "\"\r\n")).append("\n");
                    sb.append("parts << ").append(CodeQuote.ruby("Content-Type: " + mime + "\r\n\r\n"))
                            .append("\n");
                    sb.append("parts << File.binread(").append(CodeQuote.ruby(p.filePath())).append(")\n");
                    sb.append("parts << ").append(CodeQuote.ruby("\r\n")).append("\n");
                } else {
                    sb.append("parts << \"--#{boundary}\\r\\n\"\n");
                    sb.append("parts << ").append(CodeQuote.ruby("Content-Disposition: form-data; name=\""
                            + p.name() + "\"\r\n\r\n")).append("\n");
                    sb.append("parts << ").append(CodeQuote.ruby(p.value() == null ? "" : p.value()))
                            .append("\n");
                    sb.append("parts << ").append(CodeQuote.ruby("\r\n")).append("\n");
                }
            }
            sb.append("parts << \"--#{boundary}--\\r\\n\"\n");
            sb.append("request.content_type = \"multipart/form-data; boundary=#{boundary}\"\n");
            sb.append("request.body = parts.join\n");
            return sb.toString();
        }
        if (body.kind() == Body.Kind.FILE) {
            notes.add("正文来自本地文件，已按二进制一次性读入，请确认路径在运行环境中可访问。");
            sb.append("request.body = File.binread(").append(CodeQuote.ruby(body.filePath())).append(")\n");
            return sb.toString();
        }
        if (body.isPresent()) {
            sb.append("request.body = ").append(CodeQuote.ruby(body.text())).append("\n");
        }
        return sb.toString();
    }

    private static boolean isStandardMethod(String method) {
        return "GET".equals(method) || "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method) || "HEAD".equals(method)
                || "OPTIONS".equals(method);
    }
}
