package com.alianga.jkit.http.codegen.rust;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rust reqwest（blocking）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class RustReqwestGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "rust-reqwest";
    }

    @Override
    public String language() {
        return "rust";
    }

    @Override
    public String library() {
        return "reqwest";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        List<String> deps = new ArrayList<String>();
        deps.add("reqwest = { version = \"0.12\", features = [\"blocking\", \"json\"] }");
        StringBuilder src = new StringBuilder();
        src.append("// Cargo.toml: reqwest = { version = \"0.12\", features = [\"blocking\", \"json\"] }\n");
        if (req.timeoutSec() != null) {
            src.append("use std::time::Duration;\n");
        }
        src.append("\nfn main() -> Result<(), Box<dyn std::error::Error>> {\n");
        StringBuilder builder = new StringBuilder();
        if (req.insecure()) {
            builder.append("        .danger_accept_invalid_certs(true)\n");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.followRedirects()) {
            builder.append("        .redirect(reqwest::redirect::Policy::limited(5))\n");
        } else {
            builder.append("        .redirect(reqwest::redirect::Policy::none())\n");
        }
        if (req.timeoutSec() != null) {
            builder.append("        .timeout(Duration::from_secs(").append(req.timeoutSec()).append("))\n");
        }
        if (req.proxy() != null) {
            builder.append("        .proxy(reqwest::Proxy::http(")
                    .append(CodeQuote.rust(req.proxy().scheme() + "://" + req.proxy().hostPort()))
                    .append(")?)\n");
        }
        if (builder.length() == 0) {
            src.append("    let client = reqwest::blocking::Client::new();\n");
        } else {
            src.append("    let client = reqwest::blocking::Client::builder()\n");
            src.append(builder);
            src.append("        .build()?;\n");
        }
        src.append("\n    let response = client\n");
        src.append("        ").append(methodCall(req)).append("\n");
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            src.append("        .header(").append(CodeQuote.rust(h.name())).append(", ")
                    .append(CodeQuote.rust(h.value())).append(")\n");
        }
        src.append(bodyChain(req, deps, notes));
        src.append("        .send()?;\n\n");
        src.append("    println!(\"status = {}\", response.status());\n");
        src.append("    println!(\"{}\", response.text()?);\n");
        src.append("    Ok(())\n}\n");
        src.append(formHelper(req));
        return new GeneratedCode("curl_reqwest.rs", "rust", src.toString(), deps, notes);
    }

    private static String methodCall(ParsedCurlRequest req) {
        String method = req.method().toUpperCase(Locale.ROOT);
        String url = CodeQuote.rust(req.url());
        if ("GET".equals(method) || "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method) || "HEAD".equals(method)) {
            return "." + method.toLowerCase(Locale.ROOT) + "(" + url + ")";
        }
        return ".request(reqwest::Method::from_bytes(b" + CodeQuote.rust(method) + ").unwrap(), " + url + ")";
    }

    private static String bodyChain(ParsedCurlRequest req, List<String> deps, List<String> notes) {
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            deps.add("multipart 需要额外开启 feature：features = [\"blocking\", \"multipart\"]");
            notes.add("multipart 边界由 reqwest 自动生成，不要手动写 Content-Type。");
            return "        .multipart(build_form()?)\n";
        }
        if (body.kind() == Body.Kind.FILE) {
            notes.add("正文来自本地文件，已按一次性读入内存处理，请确认路径在运行环境中可访问。");
            return "        .body(std::fs::read(" + CodeQuote.rust(body.filePath()) + ")?)\n";
        }
        if (body.isPresent()) {
            return "        .body(" + CodeQuote.rust(body.text()) + ")\n";
        }
        return "";
    }

    private static String formHelper(ParsedCurlRequest req) {
        Body body = req.body();
        if (body.kind() != Body.Kind.MULTIPART) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\nfn build_form() -> Result<reqwest::blocking::multipart::Form,"
                + " Box<dyn std::error::Error>> {\n");
        sb.append("    let mut form = reqwest::blocking::multipart::Form::new();\n");
        for (FormPart p : body.parts()) {
            if (p.file()) {
                sb.append("    form = form.file(").append(CodeQuote.rust(p.name())).append(", ")
                        .append(CodeQuote.rust(p.filePath())).append(")?;\n");
            } else {
                sb.append("    form = form.text(").append(CodeQuote.rust(p.name())).append(", ")
                        .append(CodeQuote.rust(p.value() == null ? "" : p.value())).append(");\n");
            }
        }
        sb.append("    Ok(form)\n}\n");
        return sb.toString();
    }
}
