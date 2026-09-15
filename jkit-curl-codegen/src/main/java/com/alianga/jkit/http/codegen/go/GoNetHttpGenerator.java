package com.alianga.jkit.http.codegen.go;

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
        Body body = req.body();
        boolean multipart = body.kind() == Body.Kind.MULTIPART;
        boolean file = body.kind() == Body.Kind.FILE;
        boolean raw = body.isPresent() && !multipart && !file;

        StringBuilder imports = new StringBuilder("package main\n\nimport (\n\t\"fmt\"\n\t\"io\"\n\t\"net/http\"\n");
        if (multipart) {
            imports.append("\t\"bytes\"\n\t\"mime/multipart\"\n\t\"os\"\n");
        }
        if (file) {
            imports.append("\t\"os\"\n");
        }
        if (req.proxy() != null) {
            imports.append("\t\"net/url\"\n");
        }
        if (raw) {
            imports.append("\t\"strings\"\n");
        }
        imports.append(")\n\n");

        StringBuilder src = new StringBuilder(imports);
        src.append("func main() {\n");
        if (multipart) {
            src.append("\tbody := &bytes.Buffer{}\n");
            src.append("\twriter := multipart.NewWriter(body)\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("\t{\n\t\tf, err := os.Open(").append(CodeQuote.go(p.filePath())).append(")\n");
                    src.append("\t\tif err != nil { panic(err) }\n");
                    src.append("\t\tpart, err := writer.CreateFormFile(").append(CodeQuote.go(p.name()))
                            .append(", ").append(CodeQuote.go(p.filename() == null ? "file" : p.filename()))
                            .append(")\n");
                    src.append("\t\tif err != nil { panic(err) }\n");
                    src.append("\t\tio.Copy(part, f)\n\t\tf.Close()\n\t}\n");
                } else {
                    src.append("\twriter.WriteField(").append(CodeQuote.go(p.name())).append(", ")
                            .append(CodeQuote.go(p.value() == null ? "" : p.value())).append(")\n");
                }
            }
            src.append("\twriter.Close()\n");
            src.append("\treq, err := http.NewRequest(").append(CodeQuote.go(req.method().toUpperCase(Locale.ROOT)))
                    .append(", ").append(CodeQuote.go(req.url())).append(", body)\n");
        } else if (file) {
            src.append("\tbody, err := os.Open(").append(CodeQuote.go(body.filePath())).append(")\n");
            src.append("\tif err != nil { panic(err) }\n\tdefer body.Close()\n");
            src.append("\treq, err := http.NewRequest(").append(CodeQuote.go(req.method().toUpperCase(Locale.ROOT)))
                    .append(", ").append(CodeQuote.go(req.url())).append(", body)\n");
        } else if (raw) {
            src.append("\tbody := strings.NewReader(").append(CodeQuote.go(body.text())).append(")\n");
            src.append("\treq, err := http.NewRequest(").append(CodeQuote.go(req.method().toUpperCase(Locale.ROOT)))
                    .append(", ").append(CodeQuote.go(req.url())).append(", body)\n");
        } else {
            src.append("\treq, err := http.NewRequest(").append(CodeQuote.go(req.method().toUpperCase(Locale.ROOT)))
                    .append(", ").append(CodeQuote.go(req.url())).append(", nil)\n");
        }
        src.append("\tif err != nil { panic(err) }\n");
        if (multipart) {
            src.append("\treq.Header.Set(\"Content-Type\", writer.FormDataContentType())\n");
        }
        for (Header h : CurlGenSupport.headersWithAuth(req)) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            src.append("\treq.Header.Set(").append(CodeQuote.go(h.name())).append(", ")
                    .append(CodeQuote.go(h.value())).append(")\n");
        }
        if (req.proxy() != null) {
            src.append("\tproxyURL, err := url.Parse(")
                    .append(CodeQuote.go(CurlGenSupport.proxyUrl(req.proxy()))).append(")\n");
            src.append("\tif err != nil { panic(err) }\n");
            src.append("\tclient := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}\n");
        } else {
            src.append("\tclient := &http.Client{}\n");
        }
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
