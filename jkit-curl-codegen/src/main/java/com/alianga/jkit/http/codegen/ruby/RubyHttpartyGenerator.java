package com.alianga.jkit.http.codegen.ruby;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Auth;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ruby HTTParty（对齐 curlconverter {@code ruby-httparty}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class RubyHttpartyGenerator extends AbstractCodeGenerator {
    private static final Set<String> METHODS = new HashSet<String>(Arrays.asList(
            "GET", "HEAD", "POST", "PATCH", "PUT", "DELETE", "OPTIONS", "TRACE"));

    @Override
    public String id() {
        return "ruby-httparty";
    }

    @Override
    public String language() {
        return "ruby";
    }

    @Override
    public String library() {
        return "HTTParty";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        List<String> deps = new ArrayList<String>();
        deps.add("httparty");
        StringBuilder src = new StringBuilder();
        src.append("require 'httparty'\n");

        Body body = req.body();
        boolean needJson = body.kind() == Body.Kind.JSON;
        if (needJson) {
            src.append("require 'json'\n");
            deps.add("json");
        }
        src.append('\n');
        src.append("url = ").append(CodeQuote.ruby(req.url())).append("\n");

        Auth auth = req.auth();
        boolean useBasicAuth = auth != null && "basic".equals(auth.type());
        if (useBasicAuth) {
            src.append("auth = { username: ").append(CodeQuote.ruby(auth.user() == null ? "" : auth.user()))
                    .append(", password: ")
                    .append(CodeQuote.ruby(auth.password() == null ? "" : auth.password()))
                    .append(" }\n");
        } else if (auth != null && "bearer".equals(auth.type()) && !req.hasHeader("Authorization")) {
            // Authorization header via visibleHeaders
        }

        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        boolean explicitMultipart = false;
        if (body.kind() == Body.Kind.MULTIPART) {
            for (Header h : headers) {
                if ("content-type".equalsIgnoreCase(h.name())
                        && h.value().toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                    // HTTParty 自动生成 boundary，去掉手动 Content-Type
                    List<Header> filtered = new ArrayList<Header>();
                    for (Header x : headers) {
                        if (!"content-type".equalsIgnoreCase(x.name())) {
                            filtered.add(x);
                        }
                    }
                    headers = filtered;
                    break;
                }
            }
        }
        if (!headers.isEmpty()) {
            src.append("headers = {\n");
            for (Header h : headers) {
                src.append("  ").append(CodeQuote.ruby(h.name())).append(" => ")
                        .append(CodeQuote.ruby(h.value())).append(",\n");
            }
            src.append("}\n");
        }

        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("body = {\n");
            for (FormPart p : body.parts()) {
                src.append("  ").append(CodeQuote.ruby(p.name())).append(": ");
                if (p.file()) {
                    src.append("File.open(").append(CodeQuote.ruby(p.filePath())).append(")");
                    if (p.filename() != null) {
                        notes.add("HTTParty multipart 不支持自定义 filename，已忽略: " + p.filename());
                    }
                } else {
                    src.append(CodeQuote.ruby(p.value() == null ? "" : p.value()));
                    explicitMultipart = true;
                }
                src.append(",\n");
            }
            src.append("}\n");
            if (explicitMultipart) {
                notes.add("纯文本 multipart 字段已加 multipart: true。");
            }
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("body = File.binread(").append(CodeQuote.ruby(body.filePath())).append(")\n");
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            src.append("body = ").append(CodeQuote.ruby(body.text())).append("\n");
        }

        String method = req.method().toUpperCase(Locale.ROOT);
        if (METHODS.contains(method)) {
            src.append("res = HTTParty.").append(method.toLowerCase(Locale.ROOT)).append("(url");
        } else {
            notes.add("不支持的方法 " + method + "，回退为 GET + custom headers。");
            src.append("res = HTTParty.get(url");
        }
        if (useBasicAuth) {
            src.append(", basic_auth: auth");
        }
        if (!headers.isEmpty()) {
            src.append(", headers: headers");
        }
        if (body.kind() == Body.Kind.MULTIPART) {
            if (explicitMultipart) {
                src.append(", multipart: true");
            }
            src.append(", body: body");
        } else if (body.isPresent() || body.kind() == Body.Kind.FILE) {
            src.append(", body: body");
        }
        if (req.insecure()) {
            src.append(", verify: false");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.proxy() != null) {
            notes.add("HTTParty 代理请通过 http_proxy/https_proxy 环境变量或自定义 http_proxyaddr 配置。");
        }
        if (req.timeoutSec() != null) {
            src.append(", timeout: ").append(req.timeoutSec());
        }
        if (!req.followRedirects()) {
            src.append(", follow_redirects: false");
        }
        src.append(")\n");
        src.append("puts res.code\n");
        src.append("puts res.body\n");
        return new GeneratedCode("curl_httparty.rb", "ruby", src.toString(), deps, notes);
    }
}
