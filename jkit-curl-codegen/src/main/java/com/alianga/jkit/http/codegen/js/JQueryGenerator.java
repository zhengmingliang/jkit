package com.alianga.jkit.http.codegen.js;

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
 * 浏览器端 jQuery $.ajax。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JQueryGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "js-jquery";
    }

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public String library() {
        return "jQuery";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        notes.add("浏览器端运行，跨域请求需服务端返回 CORS 头。");
        StringBuilder src = new StringBuilder();
        src.append("$.ajax({\n");
        src.append("  url: ").append(CodeQuote.js(req.url())).append(",\n");
        src.append("  type: ").append(CodeQuote.js(req.method().toUpperCase(Locale.ROOT))).append(",\n");
        List<Header> headers = CurlGenSupport.visibleHeaders(req);
        if (!headers.isEmpty()) {
            src.append("  headers: {\n");
        for (Header h : headers) {
            if ("content-type".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("    ").append(CodeQuote.js(h.name())).append(": ")
                    .append(CodeQuote.js(h.value())).append(",\n");
        }
            src.append("  },\n");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("  data: buildFormData(),\n");
            src.append("  processData: false,\n");
            src.append("  contentType: false,\n");
            notes.add("multipart 用 FormData 组装，boundary 由浏览器自动生成。");
        } else if (body.kind() == Body.Kind.FILE) {
            notes.add("浏览器无法直接读取服务端路径，文件请来自 <input type=\"file\">。");
            src.append("  data: file,\n");
        } else if (body.isPresent()) {
            src.append("  data: ").append(CodeQuote.js(body.text())).append(",\n");
            if (body.kind() != Body.Kind.URLENCODED) {
                src.append("  processData: false,\n");
            }
            if (req.hasHeader("Content-Type")) {
                src.append("  contentType: ")
                        .append(CodeQuote.js(CurlGenSupport.mediaType(req))).append(",\n");
            }
        }
        if (req.timeoutSec() != null) {
            src.append("  timeout: ").append(req.timeoutSec() * 1000).append(",\n");
        }
        src.append("  success: function (data, textStatus, jqXHR) {\n");
        src.append("    console.log(jqXHR.status);\n");
        src.append("    console.log(data);\n");
        src.append("  },\n");
        src.append("  error: function (jqXHR, textStatus, errorThrown) {\n");
        src.append("    console.error(textStatus, errorThrown);\n");
        src.append("  }\n");
        src.append("});\n");

        if (body.kind() == Body.Kind.MULTIPART) {
            src.append("\nfunction buildFormData() {\n");
            src.append("  const form = new FormData();\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("  // 文件字段 ").append(p.name()).append(" 请换成 <input type=\"file\"> 选中的 File 对象\n");
                    src.append("  // form.append(").append(CodeQuote.js(p.name()))
                            .append(", file, ").append(CodeQuote.js(p.filename() == null
                                    ? "file" : p.filename())).append(");\n");
                } else {
                    src.append("  form.append(").append(CodeQuote.js(p.name())).append(", ")
                            .append(CodeQuote.js(p.value() == null ? "" : p.value())).append(");\n");
                }
            }
            src.append("  return form;\n}\n");
        }
        if (req.insecure()) {
            notes.add("浏览器无法关闭证书校验，请改用服务端代理或本地 Node 脚本。");
        }
        if (req.proxy() != null) {
            notes.add("浏览器不按请求配置代理，请在系统或浏览器网络设置里配置。");
        }
        return new GeneratedCode("curl_jquery.js", "javascript", src.toString(),
                Collections.singletonList("jquery"), notes);
    }
}
