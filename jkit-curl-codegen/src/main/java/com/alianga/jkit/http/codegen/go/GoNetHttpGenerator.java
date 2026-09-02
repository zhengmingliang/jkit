package com.alianga.jkit.http.codegen.go;

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
 * Go net/http。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class GoNetHttpGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "go-nethttp";
    }

    @Override
    public String language() {
        return "go";
    }

    @Override
    public String library() {
        return "net/http";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();
        src.append("package main\n\nimport (\n\t\"fmt\"\n\t\"io\"\n\t\"net/http\"\n\t\"strings\"\n)\n\n");
        src.append("func main() {\n");
        Body body = req.body();
        if (body.isPresent() && body.kind() != Body.Kind.FILE && body.kind() != Body.Kind.MULTIPART) {
            src.append("\tbody := strings.NewReader(").append(CodeQuote.go(body.text())).append(")\n");
            src.append("\treq, err := http.NewRequest(").append(CodeQuote.go(req.method().toUpperCase(Locale.ROOT)))
                    .append(", ").append(CodeQuote.go(req.url())).append(", body)\n");
        } else {
            if (body.kind() == Body.Kind.MULTIPART || body.kind() == Body.Kind.FILE) {
                notes.add("文件/multipart 请用 mime/multipart 自行构造。");
            }
            src.append("\treq, err := http.NewRequest(").append(CodeQuote.go(req.method().toUpperCase(Locale.ROOT)))
                    .append(", ").append(CodeQuote.go(req.url())).append(", nil)\n");
        }
        src.append("\tif err != nil { panic(err) }\n");
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("\treq.Header.Set(").append(CodeQuote.go(h.name())).append(", ")
                    .append(CodeQuote.go(h.value())).append(")\n");
        }
        src.append("\tclient := &http.Client{}\n");
        src.append("\tresp, err := client.Do(req)\n");
        src.append("\tif err != nil { panic(err) }\n");
        src.append("\tdefer resp.Body.Close()\n");
        src.append("\tb, _ := io.ReadAll(resp.Body)\n");
        src.append("\tfmt.Println(resp.StatusCode)\n\tfmt.Println(string(b))\n}\n");
        if (req.insecure()) {
            notes.add("忽略证书请设置 http.Transport.TLSClientConfig.InsecureSkipVerify。");
        }
        return new GeneratedCode("curl_nethttp.go", "go", src.toString(),
                Collections.singletonList("Go 标准库 net/http"), notes);
    }
}
