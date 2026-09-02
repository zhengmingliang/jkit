package com.alianga.jkit.http.codegen.swift;

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
 * Swift URLSession。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SwiftUrlSessionGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "swift-urlsession";
    }

    @Override
    public String language() {
        return "swift";
    }

    @Override
    public String library() {
        return "URLSession";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("import Foundation\n\n");

        Body body = req.body();
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        if (!headers.isEmpty()) {
            src.append("let headers = [\n");
            for (Header h : headers) {
                src.append("    ").append(CodeQuote.swift(h.name())).append(": ")
                        .append(CodeQuote.swift(h.value())).append(",\n");
            }
            src.append("]\n\n");
        }
        src.append(emitBody(req, notes));

        src.append("var request = URLRequest(url: URL(string: ")
                .append(CodeQuote.swift(req.url())).append(")!)\n");
        src.append("request.httpMethod = ").append(CodeQuote.swift(req.method().toUpperCase(Locale.ROOT)))
                .append("\n");
        if (!headers.isEmpty()) {
            src.append("request.allHTTPHeaderFields = headers\n");
        }
        if (req.timeoutSec() != null) {
            src.append("request.timeoutInterval = ").append(req.timeoutSec()).append(".0\n");
        }
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("request.setValue(\"multipart/form-data; boundary=\\(boundary)\",")
                    .append(" forHTTPHeaderField: \"Content-Type\")\n");
            src.append("request.httpBody = body\n");
        } else if (body.kind() == Body.Kind.FILE) {
            src.append("request.httpBody = body\n");
        } else if (body.isPresent()) {
            src.append("request.httpBody = body\n");
        }

        boolean needsConfig = req.proxy() != null;
        if (needsConfig || req.insecure()) {
            src.append("\nlet configuration = URLSessionConfiguration.default\n");
            if (req.proxy() != null) {
                src.append("configuration.connectionProxyDictionary = [\n");
                src.append("    kCFNetworkProxiesHTTPEnable as AnyHashable: true,\n");
                src.append("    kCFNetworkProxiesHTTPProxy as AnyHashable: ")
                        .append(CodeQuote.swift(req.proxy().host())).append(",\n");
                src.append("    kCFNetworkProxiesHTTPPort as AnyHashable: ").append(req.proxy().port())
                        .append("\n]\n");
                notes.add("connectionProxyDictionary 只在 iOS / macOS 上生效，Linux 版 Foundation 不处理。");
            }
        }
        if (req.insecure()) {
            src.append("\nfinal class InsecureDelegate: NSObject, URLSessionDelegate {\n");
            src.append("    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,\n");
            src.append("                    completionHandler: @escaping (URLSession.AuthChallengeDisposition,"
                    + " URLCredential?) -> Void) {\n");
            src.append("        if let trust = challenge.protectionSpace.serverTrust {\n");
            src.append("            completionHandler(.useCredential, URLCredential(trust: trust))\n");
            src.append("        } else {\n");
            src.append("            completionHandler(.performDefaultHandling, nil)\n");
            src.append("        }\n");
            src.append("    }\n}\n");
            notes.add("已生成信任全部证书的代码，仅用于开发环境。");
        }
        src.append("\nlet session: URLSession\n");
        if (req.insecure()) {
            src.append("if #available(macOS 10.15, *) {\n");
            src.append("    session = URLSession(configuration: ")
                    .append(needsConfig ? "configuration" : "URLSessionConfiguration.default")
                    .append(", delegate: InsecureDelegate(), delegateQueue: nil)\n");
            src.append("} else {\n");
            src.append("    session = URLSession(configuration: ")
                    .append(needsConfig ? "configuration" : "URLSessionConfiguration.default")
                    .append(")\n}\n");
        } else {
            src.append("session = URLSession(configuration: ")
                    .append(needsConfig ? "configuration" : "URLSessionConfiguration.default").append(")\n");
        }

        src.append("\nlet semaphore = DispatchSemaphore(value: 0)\n");
        src.append("let task = session.dataTask(with: request) { data, response, error in\n");
        src.append("    defer { semaphore.signal() }\n");
        src.append("    if let error = error {\n");
        src.append("        print(\"error: \\(error)\")\n");
        src.append("        return\n");
        src.append("    }\n");
        src.append("    if let http = response as? HTTPURLResponse {\n");
        src.append("        print(\"status: \\(http.statusCode)\")\n");
        src.append("    }\n");
        src.append("    if let data = data, let text = String(data: data, encoding: .utf8) {\n");
        src.append("        print(text)\n");
        src.append("    }\n");
        src.append("}\n");
        src.append("task.resume()\n");
        src.append("semaphore.wait()\n");
        return new GeneratedCode("CurlUrlSession.swift", "swift", src.toString(),
                Collections.<String>emptyList(), notes);
    }

    private static String emitBody(ParsedCurlRequest req, List<String> notes) {
        Body body = req.body();
        StringBuilder sb = new StringBuilder();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 边界由代码随机生成，不要手动写 Content-Type。");
            sb.append("let boundary = \"Boundary-\\(UUID().uuidString)\"\n");
            sb.append("var body = Data()\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String mime = p.contentType() == null ? "application/octet-stream" : p.contentType();
                    String fn = p.filename() == null ? "file" : p.filename();
                    sb.append("body.append(\"--\\(boundary)\\r\\n\".data(using: .utf8)!)\n");
                    sb.append("body.append(\"Content-Disposition: form-data; name=")
                            .append("\\\"").append(p.name()).append("\\\"; filename=\\\"").append(fn)
                            .append("\\\"\\r\\n\".data(using: .utf8)!)\n");
                    sb.append("body.append(\"Content-Type: ").append(mime)
                            .append("\\r\\n\\r\\n\".data(using: .utf8)!)\n");
                    sb.append("body.append(try! Data(contentsOf: URL(fileURLWithPath: ")
                            .append(CodeQuote.swift(p.filePath())).append(")))\n");
                    sb.append("body.append(\"\\r\\n\".data(using: .utf8)!)\n");
                } else {
                    sb.append("body.append(\"--\\(boundary)\\r\\n\".data(using: .utf8)!)\n");
                    sb.append("body.append(\"Content-Disposition: form-data; name=\\\"")
                            .append(p.name()).append("\\\"\\r\\n\\r\\n\".data(using: .utf8)!)\n");
                    sb.append("body.append(").append(CodeQuote.swift(p.value() == null ? "" : p.value()))
                            .append(".data(using: .utf8)!)\n");
                    sb.append("body.append(\"\\r\\n\".data(using: .utf8)!)\n");
                }
            }
            sb.append("body.append(\"--\\(boundary)--\\r\\n\".data(using: .utf8)!)\n\n");
            return sb.toString();
        }
        if (body.kind() == Body.Kind.FILE) {
            notes.add("正文来自本地文件，已按一次性读入内存处理，请确认路径在运行环境中可访问。");
            sb.append("let body = try! Data(contentsOf: URL(fileURLWithPath: ")
                    .append(CodeQuote.swift(body.filePath())).append("))\n\n");
            return sb.toString();
        }
        if (body.isPresent()) {
            sb.append("let body = ").append(CodeQuote.swift(body.text()))
                    .append(".data(using: .utf8)\n\n");
            return sb.toString();
        }
        return "";
    }
}
