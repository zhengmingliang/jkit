package com.alianga.jkit.http.codegen.lua;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Auth;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lua {@code socket.http}（luasocket，对齐 curlconverter {@code lua}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class LuaSocketHttpGenerator extends AbstractCodeGenerator {
    private static final Pattern VALID_KEY = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @Override
    public String id() {
        return "lua";
    }

    @Override
    public String language() {
        return "lua";
    }

    @Override
    public String library() {
        return "socket.http (luasocket)";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        Set<String> imports = new LinkedHashSet<String>();
        imports.add("http");

        Body body = req.body();
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        Auth auth = req.auth();
        boolean simpleGet = "GET".equalsIgnoreCase(req.method())
                && !body.isPresent()
                && headers.isEmpty()
                && auth == null;
        boolean simplePost = "POST".equalsIgnoreCase(req.method())
                && body.isPresent()
                && body.kind() != Body.Kind.FILE
                && body.kind() != Body.Kind.MULTIPART
                && auth == null
                && headers.size() == 1
                && "content-type".equalsIgnoreCase(headers.get(0).name())
                && "application/x-www-form-urlencoded"
                .equalsIgnoreCase(CurlGenSupport.mediaType(req));

        StringBuilder code = new StringBuilder();
        code.append("local body, code, headers, status = http.request");
        if (simpleGet) {
            code.append("(").append(CodeQuote.lua(req.url())).append(")\n");
        } else if (simplePost) {
            code.append("(\n");
            code.append("\t").append(CodeQuote.lua(req.url())).append(",\n");
            code.append("\t").append(CodeQuote.lua(body.text() == null ? "" : body.text())).append("\n");
            code.append(")\n");
        } else {
            code.append("{\n");
            if (!"GET".equalsIgnoreCase(req.method())) {
                code.append("\tmethod = ").append(CodeQuote.lua(req.method().toUpperCase(Locale.ROOT)))
                        .append(",\n");
            }
            code.append("\turl = ").append(CodeQuote.lua(req.url())).append(",\n");
            if (body.kind() == Body.Kind.MULTIPART) {
                notes.add("luasocket 无内置 multipart，请改用 curl 或自行拼装 body。");
            } else if (body.kind() == Body.Kind.FILE) {
                imports.add("ltn12");
                code.append("\tsource = ltn12.source.file(assert(io.open(")
                        .append(CodeQuote.lua(body.filePath())).append(", \"rb\"))),\n");
                notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
            } else if (body.isPresent()) {
                imports.add("ltn12");
                code.append("\tsource = ltn12.source.string(")
                        .append(CodeQuote.lua(body.text())).append("),\n");
            }
            boolean needHeaders = !headers.isEmpty()
                    || (auth != null && "basic".equals(auth.type()) && !req.hasHeader("Authorization"));
            if (needHeaders) {
                code.append("\theaders = {\n");
                for (Header h : headers) {
                    code.append("\t\t").append(reprKey(h.name())).append(" = ")
                            .append(CodeQuote.lua(h.value())).append(",\n");
                }
                if (auth != null && "basic".equals(auth.type()) && !req.hasHeader("Authorization")) {
                    imports.add("mime");
                    String raw = (auth.user() == null ? "" : auth.user()) + ":"
                            + (auth.password() == null ? "" : auth.password());
                    code.append("\t\tauthentication = \"Basic \" .. (mime.b64(")
                            .append(CodeQuote.lua(raw)).append(")),\n");
                }
                code.append("\t},\n");
            }
            code.append("}\n");
        }

        if (req.insecure()) {
            notes.add("socket.http 默认不校验 HTTPS 证书细节；如需严格校验请改用 luasec。");
        }
        if (req.proxy() != null) {
            notes.add("代理请设置 http_proxy / HTTPSPROXY 环境变量，或改用 http.PROXY。");
        }
        if (req.timeoutSec() != null || req.connectTimeoutSec() != null) {
            notes.add("超时可通过 http.TIMEOUT 全局设置，生成代码未改写全局。");
        }
        if (!req.followRedirects()) {
            notes.add("socket.http 默认不跟随重定向；如需跟随请自行处理 Location。");
        }

        StringBuilder src = new StringBuilder();
        for (String imp : imports) {
            if ("http".equals(imp)) {
                src.append("local http = require(\"socket.http\")\n");
            } else {
                src.append("local ").append(imp).append(" = require(\"").append(imp).append("\")\n");
            }
        }
        src.append('\n').append(code);
        src.append("print(code)\n");
        src.append("print(body)\n");

        List<String> deps = new ArrayList<String>();
        deps.add("luasocket (socket.http)");
        if (imports.contains("ltn12")) {
            deps.add("ltn12 (bundled with luasocket)");
        }
        if (imports.contains("mime")) {
            deps.add("mime (luasocket)");
        }
        notes.add(0, "使用 luasocket 的 socket.http，与 curlconverter lua 生成器一致。");
        return new GeneratedCode("curl_socket_http.lua", "lua", src.toString(), deps, notes);
    }

    private static String reprKey(String key) {
        if (key != null && VALID_KEY.matcher(key).matches()) {
            return key;
        }
        return "[" + CodeQuote.lua(key == null ? "" : key) + "]";
    }
}
