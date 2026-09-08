package com.alianga.jkit.http.codegen.shell;

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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HTTPie CLI（对齐 curlconverter {@code httpie}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class HttpieGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "httpie";
    }

    @Override
    public String language() {
        return "shell";
    }

    @Override
    public String library() {
        return "HTTPie";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        List<String> flags = new ArrayList<String>();
        List<String> items = new ArrayList<String>();

        String method = req.method().toUpperCase(Locale.ROOT);
        Body body = req.body();
        boolean hasBody = body.isPresent() || body.kind() == Body.Kind.FILE || body.kind() == Body.Kind.MULTIPART;
        if (hasBody) {
            if (!"POST".equals(method)) {
                flags.add(method);
            }
        } else if (!"GET".equals(method)) {
            flags.add(method);
        }

        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            String name = h.name().replace("=", "\\=");
            String value = h.value();
            if (value.startsWith("=") || value.startsWith("@")) {
                value = "\\" + value;
            }
            items.add(CodeQuote.sh(name + ":" + value));
        }

        Auth auth = req.auth();
        if (auth != null && "basic".equals(auth.type())) {
            flags.add("-a " + CodeQuote.sh(
                    (auth.user() == null ? "" : auth.user()) + ":"
                            + (auth.password() == null ? "" : auth.password())));
        } else if (auth != null && "bearer".equals(auth.type()) && !req.hasHeader("Authorization")) {
            items.add(CodeQuote.sh("Authorization:Bearer " + auth.token()));
        }

        if (body.kind() == Body.Kind.MULTIPART) {
            flags.add("--multipart");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String path = p.filePath() == null ? "" : p.filePath();
                    items.add(CodeQuote.sh(p.name()) + "@" + CodeQuote.sh(path));
                } else {
                    items.add(CodeQuote.sh(p.name() + "=" + (p.value() == null ? "" : p.value())));
                }
            }
            notes.add("multipart 文件字段使用 name@path；自定义 filename 需手工调整。");
        } else if (body.kind() == Body.Kind.FILE) {
            items.add("@" + CodeQuote.sh(body.filePath()));
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.kind() == Body.Kind.URLENCODED) {
            flags.add("--form");
            flags.add("--raw " + CodeQuote.sh(body.text() == null ? "" : body.text()));
            notes.add("urlencoded 正文以 --raw 发出，避免 HTTPie 再次编码。");
        } else if (body.isPresent()) {
            flags.add("--raw " + CodeQuote.sh(body.text()));
        }

        if (req.followRedirects()) {
            flags.add("--follow");
        }
        if (req.insecure()) {
            flags.add("--verify=no");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.proxy() != null) {
            String proxy = req.proxy().scheme() + "://" + req.proxy().hostPort();
            if (req.proxy().user() != null) {
                proxy = req.proxy().scheme() + "://" + req.proxy().user() + ":"
                        + (req.proxy().password() == null ? "" : req.proxy().password())
                        + "@" + req.proxy().hostPort();
            }
            flags.add("--proxy=http:" + CodeQuote.sh(proxy));
            flags.add("--proxy=https:" + CodeQuote.sh(proxy));
        }
        if (req.connectTimeoutSec() != null) {
            flags.add("--timeout=" + req.connectTimeoutSec());
            notes.add("HTTPie --timeout 更接近连接超时，不是整次请求超时。");
        } else if (req.timeoutSec() != null) {
            flags.add("--timeout=" + req.timeoutSec());
            notes.add("HTTPie --timeout 更接近连接超时，不是整次请求超时。");
        }

        String command = "http";
        String url = req.url();
        if (url.regionMatches(true, 0, "https://", 0, 8)) {
            command = "https";
        }

        List<String> args = new ArrayList<String>();
        args.addAll(flags);
        args.add(CodeQuote.sh(url));
        boolean needSep = false;
        for (String item : items) {
            String raw = item.startsWith("'") ? item.substring(1) : item;
            if (raw.startsWith("-")) {
                needSep = true;
            }
            args.add(item);
        }
        if (needSep) {
            args.add(flags.size() + 1, "--");
        }

        int total = 0;
        for (String a : args) {
            total += a.length();
        }
        boolean multiline = args.size() > 3 || total > 75;
        StringBuilder src = new StringBuilder();
        src.append(command);
        for (int i = 0; i < args.size(); i++) {
            if (multiline) {
                src.append(" \\\n  ");
            } else {
                src.append(' ');
            }
            src.append(args.get(i));
        }
        src.append('\n');
        return new GeneratedCode("curl_httpie.sh", "shell", src.toString(),
                Collections.singletonList("httpie"), notes);
    }
}
