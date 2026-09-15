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
 * PHP curl 扩展。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class PhpCurlGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "php-curl";
    }

    @Override
    public String language() {
        return "php";
    }

    @Override
    public String library() {
        return "curl";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("<?php\n$ch = curl_init(").append(CodeQuote.php(req.url())).append(");\n");
        src.append("curl_setopt($ch, CURLOPT_CUSTOMREQUEST, ")
                .append(CodeQuote.php(req.method().toUpperCase(Locale.ROOT))).append(");\n");
        src.append("curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);\n");
        src.append("curl_setopt($ch, CURLOPT_FOLLOWLOCATION, ")
                .append(req.followRedirects() ? "true" : "false").append(");\n");
        if (req.insecure()) {
            src.append("curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);\n");
            src.append("curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 0);\n");
        }
        if (req.proxy() != null) {
            src.append("curl_setopt($ch, CURLOPT_PROXY, ")
                    .append(CodeQuote.php(req.proxy().scheme() + "://" + req.proxy().hostPort())).append(");\n");
            if ("socks5".equalsIgnoreCase(req.proxy().scheme())) {
                src.append("curl_setopt($ch, CURLOPT_PROXYTYPE, CURLPROXY_SOCKS5);\n");
            }
            if (req.proxy().user() != null) {
                src.append("curl_setopt($ch, CURLOPT_PROXYUSERPWD, ")
                        .append(CodeQuote.php(req.proxy().user()
                                + (req.proxy().password() == null ? "" : ":" + req.proxy().password())))
                        .append(");\n");
            }
        }
        src.append("$headers = [\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("    ").append(CodeQuote.php(h.name() + ": " + h.value())).append(",\n");
        }
        src.append("];\ncurl_setopt($ch, CURLOPT_HTTPHEADER, $headers);\n");
        Body body = req.body();
        if (body.isPresent() && body.kind() != Body.Kind.FILE && body.kind() != Body.Kind.MULTIPART) {
            src.append("curl_setopt($ch, CURLOPT_POSTFIELDS, ").append(CodeQuote.php(body.text())).append(");\n");
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("curl_setopt($ch, CURLOPT_POSTFIELDS, file_get_contents(")
                    .append(CodeQuote.php(body.filePath())).append("));\n");
        } else if (body.kind() == Body.Kind.MULTIPART) {
            src.append("$postFields = [\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("    ").append(CodeQuote.php(p.name())).append(" => new CURLFile(")
                            .append(CodeQuote.php(p.filePath()));
                    if (p.contentType() != null) {
                        src.append(", ").append(CodeQuote.php(p.contentType()));
                        if (p.filename() != null) {
                            src.append(", ").append(CodeQuote.php(p.filename()));
                        }
                    } else if (p.filename() != null) {
                        src.append(", '', ").append(CodeQuote.php(p.filename()));
                    }
                    src.append("),\n");
                } else {
                    src.append("    ").append(CodeQuote.php(p.name())).append(" => ")
                            .append(CodeQuote.php(p.value() == null ? "" : p.value())).append(",\n");
                }
            }
            src.append("];\ncurl_setopt($ch, CURLOPT_POSTFIELDS, $postFields);\n");
        }
        src.append("$response = curl_exec($ch);\n");
        src.append("echo curl_getinfo($ch, CURLINFO_HTTP_CODE), PHP_EOL, $response;\n");
        src.append("curl_close($ch);\n");
        return new GeneratedCode("curl.php", "php", src.toString(),
                Collections.singletonList("ext-curl"), notes);
    }
}
