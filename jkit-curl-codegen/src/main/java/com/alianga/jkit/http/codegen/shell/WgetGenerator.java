package com.alianga.jkit.http.codegen.shell;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * POSIX shell 下的 wget 命令。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class WgetGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "shell-wget";
    }

    @Override
    public String language() {
        return "shell";
    }

    @Override
    public String library() {
        return "wget";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        List<String> lines = new ArrayList<String>();
        lines.add("wget --quiet --output-document -");
        String method = req.method().toUpperCase(Locale.ROOT);
        Body body = req.body();
        if (!"GET".equals(method) || body.isPresent()) {
            lines.add("  --method=" + method);
        }
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            lines.add("  --header=" + CodeQuote.sh(h.name() + ": " + h.value()));
        }
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("wget 不支持 multipart/form-data，请改用 curl。");
        } else if (body.kind() == Body.Kind.FILE) {
            lines.add("  --body-file=" + CodeQuote.sh(body.filePath()));
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            lines.add("  --body-data=" + CodeQuote.sh(body.text()));
        }
        if (!req.followRedirects()) {
            lines.add("  --max-redirect=0");
        }
        if (req.insecure()) {
            lines.add("  --no-check-certificate");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.connectTimeoutSec() != null) {
            lines.add("  --dns-timeout=" + req.connectTimeoutSec());
            lines.add("  --connect-timeout=" + req.connectTimeoutSec());
        }
        if (req.timeoutSec() != null) {
            lines.add("  --read-timeout=" + req.timeoutSec());
        }
        if (req.proxy() != null) {
            String proxy = req.proxy().scheme() + "://" + req.proxy().hostPort();
            lines.add("  -e use_proxy=yes");
            lines.add("  -e http_proxy=" + CodeQuote.sh(proxy));
            lines.add("  -e https_proxy=" + CodeQuote.sh(proxy));
            if (req.proxy().user() != null) {
                lines.add("  --proxy-user=" + CodeQuote.sh(req.proxy().user()));
                lines.add("  --proxy-password="
                        + CodeQuote.sh(req.proxy().password() == null ? "" : req.proxy().password()));
            }
        }
        lines.add("  " + CodeQuote.sh(req.url()));

        StringBuilder src = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            src.append(lines.get(i));
            src.append(i == lines.size() - 1 ? "\n" : " \\\n");
        }
        notes.add("--method / --body-data 需要 wget 1.14 及以上（GNU wget）。");
        return new GeneratedCode("curl_wget.sh", "shell", src.toString(),
                Collections.<String>emptyList(), notes);
    }
}
