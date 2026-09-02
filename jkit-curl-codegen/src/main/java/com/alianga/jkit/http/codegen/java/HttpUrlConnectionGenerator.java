package com.alianga.jkit.http.codegen.java;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.codegen.JavaEmit;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 渲染成 JDK 自带 HttpURLConnection 代码，无任何第三方依赖。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class HttpUrlConnectionGenerator extends AbstractCodeGenerator {
    private static final List<String> ALLOWED_METHODS =
            Collections.unmodifiableList(Arrays.asList(
                    "GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE"));

    @Override
    public String id() {
        return "java-httpurlconnection";
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String library() {
        return "HttpURLConnection";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        TreeSet<String> imports = new TreeSet<String>();
        Collections.addAll(imports, "java.io.ByteArrayOutputStream", "java.io.InputStream",
                "java.io.OutputStream", "java.net.HttpURLConnection", "java.net.URL",
                "java.nio.charset.StandardCharsets");

        String method = req.method().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            notes.add("HttpURLConnection 不支持 " + method + "，运行时会抛 ProtocolException；"
                    + "请改用 OkHttp / Apache HttpClient，或让服务端支持 X-HTTP-Method-Override。");
        }
        Body body = req.body();
        boolean sendBody = body.isPresent() || "POST".equals(method) || "PUT".equals(method);

        StringBuilder src = new StringBuilder();
        src.append("public class CurlHttpUrlConnectionExample {\n");
        src.append("    public static void main(String[] args) throws Exception {\n");
        src.append("        java.net.URL target = new java.net.URL(")
                .append(JavaEmit.quote(req.url())).append(");\n");
        if (req.proxy() != null) {
            imports.add("java.net.InetSocketAddress");
            imports.add("java.net.Proxy");
            src.append("        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,\n");
            src.append("                new java.net.InetSocketAddress(")
                    .append(JavaEmit.quote(req.proxy().host())).append(", ")
                    .append(req.proxy().port()).append("));\n");
            if (req.proxy().user() != null) {
                imports.add("java.net.Authenticator");
                imports.add("java.net.PasswordAuthentication");
                src.append("        java.net.Authenticator.setDefault(new java.net.Authenticator() {\n");
                src.append("            protected java.net.PasswordAuthentication getPasswordAuthentication() {\n");
                src.append("                return new java.net.PasswordAuthentication(")
                        .append(JavaEmit.quote(req.proxy().user())).append(", ")
                        .append(JavaEmit.quote(req.proxy().password() == null ? "" : req.proxy().password()))
                        .append(".toCharArray());\n");
                src.append("            }\n        });\n");
            }
            src.append("        HttpURLConnection conn = (HttpURLConnection) target.openConnection(proxy);\n");
        } else {
            src.append("        HttpURLConnection conn = (HttpURLConnection) target.openConnection();\n");
        }
        src.append("        conn.setRequestMethod(").append(JavaEmit.quote(method)).append(");\n");
        if (req.connectTimeoutSec() != null) {
            src.append("        conn.setConnectTimeout(").append(req.connectTimeoutSec() * 1000).append(");\n");
        } else {
            src.append("        conn.setConnectTimeout(10000);\n");
        }
        if (req.timeoutSec() != null) {
            src.append("        conn.setReadTimeout(").append(req.timeoutSec() * 1000).append(");\n");
        } else {
            src.append("        conn.setReadTimeout(30000);\n");
        }
        src.append("        conn.setInstanceFollowRedirects(").append(req.followRedirects()).append(");\n");
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            src.append("        conn.setRequestProperty(").append(JavaEmit.quote(h.name())).append(", ")
                    .append(JavaEmit.quote(h.value())).append(");\n");
        }
        if (req.insecure()) {
            src.append("        if (conn instanceof javax.net.ssl.HttpsURLConnection) {\n");
            src.append("            javax.net.ssl.HttpsURLConnection https"
                    + " = (javax.net.ssl.HttpsURLConnection) conn;\n");
            src.append("            https.setSSLSocketFactory(TrustAll.socketFactory());\n");
            src.append("            https.setHostnameVerifier(TrustAll.hostnameVerifier());\n");
            src.append("        }\n");
            notes.add("已生成信任全部证书的代码，仅用于开发环境。");
        }
        if (sendBody) {
            src.append("        conn.setDoOutput(true);\n");
            if (body.kind() == Body.Kind.MULTIPART) {
                src.append("        String boundary = \"----JkitFormBoundary\" + System.currentTimeMillis();\n");
                src.append("        conn.setRequestProperty(\"Content-Type\","
                        + " \"multipart/form-data; boundary=\" + boundary);\n");
            } else if (!req.hasHeader("Content-Type")) {
                src.append("        conn.setRequestProperty(\"Content-Type\", ")
                        .append(JavaEmit.quote(CurlGenSupport.mediaType(req))).append(");\n");
            }
        }
        src.append("        conn.setDoInput(true);\n\n");
        src.append(emitWriteBody(req, sendBody, imports, notes));
        src.append("\n        int code = conn.getResponseCode();\n");
        src.append("        try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {\n");
        src.append("            System.out.println(\"HTTP \" + code);\n");
        src.append("            System.out.println(readAll(in));\n");
        src.append("        } finally {\n            conn.disconnect();\n        }\n");
        src.append("    }\n\n");
        src.append("    private static String readAll(InputStream in) throws Exception {\n");
        src.append("        if (in == null) {\n            return \"\";\n        }\n");
        src.append("        ByteArrayOutputStream bos = new ByteArrayOutputStream();\n");
        src.append("        byte[] buf = new byte[8192];\n");
        src.append("        int n;\n");
        src.append("        while ((n = in.read(buf)) != -1) {\n");
        src.append("            bos.write(buf, 0, n);\n        }\n");
        src.append("        return new String(bos.toByteArray(), StandardCharsets.UTF_8);\n");
        src.append("    }\n");
        src.append(emitMultipartHelpers(req, imports));
        if (req.insecure()) {
            src.append(emitTrustAll());
        }
        src.append("}\n");

        StringBuilder out = new StringBuilder();
        for (String i : imports) {
            out.append("import ").append(i).append(";\n");
        }
        out.append("\n").append(src);
        return new GeneratedCode("CurlHttpUrlConnectionExample.java", "java", out.toString(),
                Collections.<String>emptyList(), notes);
    }

    private static String emitWriteBody(ParsedCurlRequest req, boolean sendBody,
                                        TreeSet<String> imports, List<String> notes) {
        if (!sendBody) {
            return "";
        }
        Body body = req.body();
        StringBuilder sb = new StringBuilder();
        sb.append("        try (OutputStream out = conn.getOutputStream()) {\n");
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 按 RFC 7578 手工拼装，boundary 由代码生成。");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String mime = p.contentType() == null ? "application/octet-stream" : p.contentType();
                    String fn = p.filename() == null ? "file" : p.filename();
                    sb.append("            writeFile(out, boundary, ").append(JavaEmit.quote(p.name()))
                            .append(", ").append(JavaEmit.quote(fn)).append(", ")
                            .append(JavaEmit.quote(p.filePath())).append(", ")
                            .append(JavaEmit.quote(mime)).append(");\n");
                } else {
                    sb.append("            writeField(out, boundary, ").append(JavaEmit.quote(p.name()))
                            .append(", ").append(JavaEmit.quote(p.value() == null ? "" : p.value()))
                            .append(");\n");
                }
            }
            sb.append("            out.write((\"--\" + boundary + \"--\\r\\n\")"
                    + ".getBytes(StandardCharsets.UTF_8));\n");
        } else if (body.kind() == Body.Kind.FILE) {
            imports.add("java.io.File");
            imports.add("java.io.FileInputStream");
            notes.add("正文来自本地文件，已按流式上传处理，请确认路径在运行环境中可访问。");
            sb.append("            try (InputStream file = new FileInputStream(new File(")
                    .append(JavaEmit.quote(body.filePath())).append("))) {\n");
            sb.append("                byte[] buf = new byte[8192];\n");
            sb.append("                int n;\n");
            sb.append("                while ((n = file.read(buf)) != -1) {\n");
            sb.append("                    out.write(buf, 0, n);\n                }\n");
            sb.append("            }\n");
        } else if (body.isPresent()) {
            String text = body.text() == null ? "" : body.text();
            sb.append("            byte[] payload = ").append(JavaEmit.quote(text))
                    .append(".getBytes(StandardCharsets.UTF_8);\n");
            sb.append("            out.write(payload);\n");
        } else {
            sb.append("            out.write(new byte[0]);\n");
        }
        sb.append("            out.flush();\n");
        sb.append("        }\n");
        return sb.toString();
    }

    private static String emitMultipartHelpers(ParsedCurlRequest req, TreeSet<String> imports) {
        Body body = req.body();
        if (body.kind() != Body.Kind.MULTIPART) {
            return "";
        }
        boolean hasFile = false;
        boolean hasField = false;
        for (FormPart p : body.parts()) {
            if (p.file()) {
                hasFile = true;
            } else {
                hasField = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (hasField) {
            sb.append("\n    private static void writeField(OutputStream out, String boundary,")
                    .append(" String name, String value) throws Exception {\n");
            sb.append("        out.write((\"--\" + boundary + \"\\r\\n\").getBytes(StandardCharsets.UTF_8));\n");
            sb.append("        out.write((\"Content-Disposition: form-data; name=\\\"\" + name + \"\\\"\\r\\n\\r\\n\")")
                    .append(".getBytes(StandardCharsets.UTF_8));\n");
            sb.append("        out.write(value.getBytes(StandardCharsets.UTF_8));\n");
            sb.append("        out.write(\"\\r\\n\".getBytes(StandardCharsets.UTF_8));\n");
            sb.append("    }\n");
        }
        if (hasFile) {
            imports.add("java.io.File");
            imports.add("java.io.FileInputStream");
            sb.append("\n    private static void writeFile(OutputStream out, String boundary, String name,")
                    .append("\n            String filename, String path, String contentType) throws Exception {\n");
            sb.append("        out.write((\"--\" + boundary + \"\\r\\n\").getBytes(StandardCharsets.UTF_8));\n");
            sb.append("        out.write((\"Content-Disposition: form-data; name=\\\"\" + name")
                    .append(" + \"\\\"; filename=\\\"\" + filename + \"\\\"\\r\\n\")")
                    .append(".getBytes(StandardCharsets.UTF_8));\n");
            sb.append("        out.write((\"Content-Type: \" + contentType + \"\\r\\n\\r\\n\")")
                    .append(".getBytes(StandardCharsets.UTF_8));\n");
            sb.append("        try (InputStream file = new FileInputStream(new File(path))) {\n");
            sb.append("            byte[] buf = new byte[8192];\n");
            sb.append("            int n;\n");
            sb.append("            while ((n = file.read(buf)) != -1) {\n");
            sb.append("                out.write(buf, 0, n);\n            }\n");
            sb.append("        }\n");
            sb.append("        out.write(\"\\r\\n\".getBytes(StandardCharsets.UTF_8));\n");
            sb.append("    }\n");
        }
        return sb.toString();
    }

    private static String emitTrustAll() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n    static final class TrustAll {\n");
        sb.append("        private TrustAll() {\n        }\n\n");
        sb.append("        static javax.net.ssl.SSLSocketFactory socketFactory() throws Exception {\n");
        sb.append("            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[] {\n");
        sb.append("                new javax.net.ssl.X509TrustManager() {\n");
        sb.append("                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {\n");
        sb.append("                        return new java.security.cert.X509Certificate[0];\n");
        sb.append("                    }\n\n");
        sb.append("                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain,"
                + " String authType) {\n                    }\n\n");
        sb.append("                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain,"
                + " String authType) {\n                    }\n");
        sb.append("                }\n            };\n");
        sb.append("            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance(\"TLS\");\n");
        sb.append("            ctx.init(null, trustAll, new java.security.SecureRandom());\n");
        sb.append("            return ctx.getSocketFactory();\n");
        sb.append("        }\n\n");
        sb.append("        static javax.net.ssl.HostnameVerifier hostnameVerifier() {\n");
        sb.append("            return new javax.net.ssl.HostnameVerifier() {\n");
        sb.append("                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {\n");
        sb.append("                    return true;\n                }\n            };\n");
        sb.append("        }\n");
        sb.append("    }\n");
        return sb.toString();
    }
}
