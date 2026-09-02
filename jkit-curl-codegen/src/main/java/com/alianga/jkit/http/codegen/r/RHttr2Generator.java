package com.alianga.jkit.http.codegen.r;

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
 * R httr2。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class RHttr2Generator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "r-httr2";
    }

    @Override
    public String language() {
        return "r";
    }

    @Override
    public String library() {
        return "httr2";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        List<String> deps = new ArrayList<String>();
        Collections.addAll(deps, "install.packages(\"httr2\")");
        StringBuilder src = new StringBuilder();
        src.append("library(httr2)\n\n");
        src.append("resp <- request(").append(CodeQuote.r(req.url())).append(") |>\n");
        src.append("  req_method(").append(CodeQuote.r(req.method().toUpperCase(Locale.ROOT))).append(") |>\n");
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        // 正文的 Content-Type 由 req_body_raw/req_body_file 的 type 参数设置，避免重复
        boolean skipContentType = req.body().isPresent();
        List<Header> headerOut = new ArrayList<Header>(headers.size());
        for (Header h : headers) {
            if (skipContentType && "content-type".equalsIgnoreCase(h.name())) {
                continue;
            }
            headerOut.add(h);
        }
        if (!headerOut.isEmpty()) {
            src.append("  req_headers(\n");
            for (int i = 0; i < headerOut.size(); i++) {
                Header h = headerOut.get(i);
                src.append("    `").append(h.name()).append("` = ").append(CodeQuote.r(h.value()));
                src.append(i == headerOut.size() - 1 ? "\n" : ",\n");
            }
            src.append("  ) |>\n");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            deps.add("install.packages(\"curl\")");
            src.append("  req_body_multipart(\n");
            for (int i = 0; i < body.parts().size(); i++) {
                FormPart p = body.parts().get(i);
                if (p.file()) {
                    src.append("    `").append(p.name()).append("` = curl::form_file(")
                            .append(CodeQuote.r(p.filePath())).append(")");
                } else {
                    src.append("    `").append(p.name()).append("` = ")
                            .append(CodeQuote.r(p.value() == null ? "" : p.value()));
                }
                src.append(i == body.parts().size() - 1 ? "\n" : ",\n");
            }
            src.append("  ) |>\n");
            notes.add("multipart 的 Content-Type 边界由 httr2 自动生成，不要手动设置。");
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("  req_body_file(").append(CodeQuote.r(body.filePath()))
                    .append(", type = ").append(CodeQuote.r(CurlGenSupport.mediaType(req)))
                    .append(") |>\n");
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            src.append("  req_body_raw(").append(CodeQuote.r(body.text()))
                    .append(", type = ").append(CodeQuote.r(CurlGenSupport.mediaType(req))).append(") |>\n");
        }
        if (req.timeoutSec() != null) {
            src.append("  req_timeout(seconds = ").append(req.timeoutSec()).append(") |>\n");
        }
        if (req.insecure()) {
            src.append("  req_options(ssl_verifypeer = 0) |>\n");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (!req.followRedirects()) {
            // httr2 的 req_perform() 默认跟随 301/302/303/307，这里显式关闭
            src.append("  req_options(followlocation = 0) |>\n");
        }
        if (req.proxy() != null) {
            notes.add("httr2 不支持按请求设置代理，请在运行前设置环境变量 "
                    + "http_proxy / https_proxy=" + req.proxy().scheme() + "://" + req.proxy().hostPort() + " 。");
        }
        src.append("  req_perform()\n\n");
        src.append("cat(resp_status(resp), \"\\n\")\n");
        src.append("cat(resp_body_string(resp), \"\\n\")\n");
        return new GeneratedCode("curl_httr2.R", "r", src.toString(), deps, notes);
    }
}
