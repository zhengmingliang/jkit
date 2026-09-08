package com.alianga.jkit.http.codegen.php;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PHP Guzzle（对齐 curlconverter {@code php-guzzle}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class PhpGuzzleGenerator extends AbstractCodeGenerator {
    private static final Set<String> METHODS = new HashSet<String>(Arrays.asList(
            "GET", "DELETE", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"));

    @Override
    public String id() {
        return "php-guzzle";
    }

    @Override
    public String language() {
        return "php";
    }

    @Override
    public String library() {
        return "Guzzle";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        boolean needPsr7 = false;
        StringBuilder options = new StringBuilder();

        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        if (!headers.isEmpty()) {
            options.append("    'headers' => [\n");
            for (Header h : headers) {
                options.append("        ").append(CodeQuote.php(h.name())).append(" => ")
                        .append(CodeQuote.php(h.value())).append(",\n");
            }
            options.append("    ],\n");
        }

        Auth auth = req.auth();
        if (auth != null && "basic".equals(auth.type())) {
            options.append("    'auth' => [")
                    .append(CodeQuote.php(auth.user() == null ? "" : auth.user())).append(", ")
                    .append(CodeQuote.php(auth.password() == null ? "" : auth.password()))
                    .append("],\n");
        }

        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            options.append("    'multipart' => [\n");
            for (FormPart p : body.parts()) {
                options.append("        [\n");
                options.append("            'name' => ").append(CodeQuote.php(p.name())).append(",\n");
                if (p.file()) {
                    needPsr7 = true;
                    if (p.filename() != null) {
                        options.append("            'filename' => ").append(CodeQuote.php(p.filename()))
                                .append(",\n");
                    }
                    options.append("            'contents' => Psr7\\Utils::tryFopen(")
                            .append(CodeQuote.php(p.filePath())).append(", 'r'),\n");
                } else {
                    options.append("            'contents' => ")
                            .append(CodeQuote.php(p.value() == null ? "" : p.value())).append(",\n");
                }
                options.append("        ],\n");
            }
            options.append("    ],\n");
            notes.add("multipart 边界由 Guzzle 自动生成。");
        } else if (body.kind() == Body.Kind.FILE) {
            needPsr7 = true;
            options.append("    'body' => Psr7\\Utils::tryFopen(")
                    .append(CodeQuote.php(body.filePath())).append(", 'r'),\n");
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.kind() == Body.Kind.URLENCODED) {
            options.append("    'body' => ").append(CodeQuote.php(body.text() == null ? "" : body.text()))
                    .append(",\n");
        } else if (body.kind() == Body.Kind.JSON) {
            options.append("    'body' => ").append(CodeQuote.php(body.text() == null ? "" : body.text()))
                    .append(",\n");
        } else if (body.isPresent()) {
            options.append("    'body' => ").append(CodeQuote.php(body.text())).append(",\n");
        }

        if (req.proxy() != null) {
            String proxy = req.proxy().scheme() + "://" + req.proxy().hostPort();
            if (req.proxy().user() != null) {
                proxy = req.proxy().scheme() + "://" + req.proxy().user() + ":"
                        + (req.proxy().password() == null ? "" : req.proxy().password())
                        + "@" + req.proxy().hostPort();
            }
            options.append("    'proxy' => ").append(CodeQuote.php(proxy)).append(",\n");
        }
        if (req.timeoutSec() != null) {
            options.append("    'timeout' => ").append(req.timeoutSec()).append(",\n");
        }
        if (req.connectTimeoutSec() != null) {
            options.append("    'connect_timeout' => ").append(req.connectTimeoutSec()).append(",\n");
        }
        if (!req.followRedirects()) {
            options.append("    'allow_redirects' => false,\n");
        }
        if (req.insecure()) {
            options.append("    'verify' => false,\n");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }

        StringBuilder src = new StringBuilder();
        src.append("<?php\n");
        src.append("require 'vendor/autoload.php';\n\n");
        src.append("use GuzzleHttp\\Client;\n");
        if (needPsr7) {
            src.append("use GuzzleHttp\\Psr7;\n");
        }
        src.append("\n$client = new Client();\n\n");
        String method = req.method().toUpperCase(Locale.ROOT);
        if (METHODS.contains(method)) {
            src.append("$response = $client->").append(method.toLowerCase(Locale.ROOT)).append("(")
                    .append(CodeQuote.php(req.url()));
        } else {
            src.append("$response = $client->request(").append(CodeQuote.php(method)).append(", ")
                    .append(CodeQuote.php(req.url()));
        }
        if (options.length() > 0) {
            src.append(", [\n").append(options).append("]");
        }
        src.append(");\n");
        src.append("echo $response->getStatusCode(), PHP_EOL;\n");
        src.append("echo $response->getBody();\n");
        return new GeneratedCode("curl_guzzle.php", "php", src.toString(),
                Collections.singletonList("guzzlehttp/guzzle"), notes);
    }
}
