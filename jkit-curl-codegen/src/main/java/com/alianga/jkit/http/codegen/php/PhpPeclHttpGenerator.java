package com.alianga.jkit.http.codegen.php;

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
 * PHP pecl_http（ext/http，{@code http\Client}）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class PhpPeclHttpGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "php-pecl-http";
    }

    @Override
    public String language() {
        return "php";
    }

    @Override
    public String library() {
        return "pecl_http";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        notes.add("需要 pecl install pecl_http 并启用 extension=http.so（v3 才有 http\\Client）。");
        StringBuilder src = new StringBuilder();
        src.append("<?php\n");
        src.append("$client = new http\\Client;\n");

        List<String> options = new ArrayList<String>();
        if (req.timeoutSec() != null) {
            options.add("    'timeout' => " + req.timeoutSec());
        }
        if (req.connectTimeoutSec() != null) {
            options.add("    'connecttimeout' => " + req.connectTimeoutSec());
        }
        options.add("    'redirect' => " + (req.followRedirects() ? 5 : 0));
        if (req.insecure()) {
            options.add("    'ssl' => ['verifypeer' => false, 'verifyhost' => false]");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.proxy() != null) {
            options.add("    'proxy' => " + CodeQuote.php(req.proxy().hostPort()));
            if (req.proxy().user() != null) {
                options.add("    'proxyauth' => " + CodeQuote.php(req.proxy().user() + ":"
                        + (req.proxy().password() == null ? "" : req.proxy().password())));
            }
        }
        if (!options.isEmpty()) {
            src.append("$client->setOptions([\n");
            for (String opt : options) {
                src.append(opt).append(",\n");
            }
            src.append("]);\n");
        }

        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        src.append("$request = new http\\Client\\Request(")
                .append(CodeQuote.php(req.method().toUpperCase(Locale.ROOT))).append(", ")
                .append(CodeQuote.php(req.url()));
        if (headers.isEmpty()) {
            src.append(");\n");
        } else {
            src.append(", [\n");
            for (Header h : headers) {
                src.append("    ").append(CodeQuote.php(h.name())).append(" => ")
                        .append(CodeQuote.php(h.value())).append(",\n");
            }
            src.append("]);\n");
        }

        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 边界由 addForm 自动生成，不要手动写 Content-Type。");
            src.append("$body = new http\\Message\\Body;\n");
            src.append("$body->addForm(\n    [\n");
            List<FormPart> fields = new ArrayList<FormPart>();
            List<FormPart> files = new ArrayList<FormPart>();
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    files.add(p);
                } else {
                    fields.add(p);
                }
            }
            for (FormPart p : fields) {
                src.append("        ").append(CodeQuote.php(p.name())).append(" => ")
                        .append(CodeQuote.php(p.value() == null ? "" : p.value())).append(",\n");
            }
            src.append("    ],\n    [\n");
            for (FormPart p : files) {
                String mime = p.contentType() == null ? "application/octet-stream" : p.contentType();
                String fn = p.filename() == null ? "file" : p.filename();
                src.append("        ['name' => ").append(CodeQuote.php(p.name()))
                        .append(", 'file' => ").append(CodeQuote.php(p.filePath()))
                        .append(", 'type' => ").append(CodeQuote.php(mime))
                        .append(", 'filename' => ").append(CodeQuote.php(fn)).append("],\n");
            }
            src.append("    ]\n);\n");
            src.append("$request->setBody($body);\n");
        } else if (body.kind() == Body.Kind.FILE) {
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
            src.append("$body = new http\\Message\\Body;\n");
            src.append("$body->append(file_get_contents(").append(CodeQuote.php(body.filePath()))
                    .append("));\n");
            src.append("$request->setBody($body);\n");
        } else if (body.isPresent()) {
            src.append("$body = new http\\Message\\Body;\n");
            src.append("$body->append(").append(CodeQuote.php(body.text())).append(");\n");
            src.append("$request->setBody($body);\n");
        }

        src.append("\n$client->enqueue($request)->send();\n");
        src.append("$response = $client->getResponse($request);\n");
        src.append("printf(\"%d\\n\", $response->getResponseCode());\n");
        src.append("echo $response->getBody();\n");
        return new GeneratedCode("curl_pecl_http.php", "php", src.toString(),
                Collections.singletonList("ext-http (pecl_http >= 3)"), notes);
    }
}
