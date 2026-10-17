package com.alianga.jkit.storage.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用假 S3 服务器：内存对象存储 + ListObjects 语义（prefix/delimiter/marker 翻页），
 * 同时记录收到的 Authorization 头供签名断言使用。仅覆盖 jkit-storage 用到的子集。
 *
 * @author 郑明亮
 */
final class FakeS3Server {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Map<String, String> lastContentTypes = new ConcurrentHashMap<>();
    /** 最近一次 ListObjects 的查询参数（断言 fetch-owner 等请求形态用）。 */
    final java.util.concurrent.atomic.AtomicReference<Map<String, String>> lastListQuery =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** 分片上传状态：uploadId → (partNumber → 数据)。完成后清空。 */
    final Map<String, Map<Integer, byte[]>> multipartParts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong uploadIds = new java.util.concurrent.atomic.AtomicLong();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String bucket;

    /**
     * 启动假服务器。
     *
     * @param bucket 仿真 bucket 名
     * @return 监听地址（http://127.0.0.1:端口）
     * @throws IOException 启动失败
     */
    String start(String bucket) throws IOException {
        this.bucket = bucket;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 停止服务器。
     */
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            handleInner(exchange);
        } catch (RuntimeException e) {
            System.out.println("[FakeS3] handler error: " + e);
            throw e;
        }
    }

    private void handleInner(HttpExchange exchange) throws IOException {
        try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizations.add(authorization);
            }
            String method = exchange.getRequestMethod();
            String rawPath = exchange.getRequestURI().getRawPath();
            String path = decodePath(rawPath);
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String prefix = "/" + bucket;
            if (path.equals(prefix)) {
                if ("GET".equals(method)) {
                    respondList(exchange, query);
                } else {
                    respond(exchange, 400, "unsupported");
                }
                return;
            }
            if (!path.startsWith(prefix + "/")) {
                respondXmlError(exchange, 404, "NoSuchBucket", "bucket 不存在");
                return;
            }
            String key = path.substring(prefix.length() + 1);
            if ("POST".equals(method) && query.containsKey("uploads")) {
                String uploadId = "upload-" + uploadIds.incrementAndGet();
                multipartParts.put(uploadId, new java.util.concurrent.ConcurrentHashMap<Integer, byte[]>());
                respond(exchange, 200, "<InitiateMultipartUploadResult><Bucket>" + bucket
                        + "</Bucket><Key>" + key + "</Key><UploadId>" + uploadId
                        + "</UploadId></InitiateMultipartUploadResult>");
                return;
            }
            if ("POST".equals(method) && query.containsKey("uploadId")) {
                handleComplete(exchange, key, query.get("uploadId"));
                return;
            }
            if ("DELETE".equals(method) && query.containsKey("uploadId")) {
                multipartParts.remove(query.get("uploadId"));
                respond(exchange, 204, "");
                return;
            }
            if (Boolean.parseBoolean(System.getProperty("fake.s3.deny"))) {
                if ("PUT".equals(method) || "POST".equals(method)) {
                    drain(exchange.getRequestBody());
                }
                respondXmlError(exchange, 403, "AccessDenied", "拒绝访问");
                return;
            }
            if ("PUT".equals(method) && query.containsKey("uploadId")) {
                handlePart(exchange, key, Integer.parseInt(query.getOrDefault("partNumber", "0")),
                        query.get("uploadId"));
                return;
            }
            switch (method) {
                case "PUT":
                    handlePut(exchange, key);
                    break;
                case "GET":
                    handleGet(exchange, key);
                    break;
                case "HEAD":
                    handleHead(exchange, key);
                    break;
                case "DELETE":
                    objects.remove(key);
                    respond(exchange, 204, "");
                    break;
                default:
                    respond(exchange, 405, "method not supported");
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePart(HttpExchange exchange, String key, int partNumber,
                            String uploadId) throws IOException {
        Map<Integer, byte[]> parts = multipartParts.get(uploadId);
        if (parts == null) {
            respondXmlError(exchange, 404, "NoSuchUpload", "分片上传不存在");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
        }
        parts.put(partNumber, out.toByteArray());
        exchange.getResponseHeaders().set("ETag", "\"part-" + partNumber + "\"");
        exchange.sendResponseHeaders(200, -1);
    }

    private void handleComplete(HttpExchange exchange, String key, String uploadId) throws IOException {
        Map<Integer, byte[]> parts = multipartParts.remove(uploadId);
        if (parts == null) {
            respondXmlError(exchange, 404, "NoSuchUpload", "分片上传不存在");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : new TreeMap<>(parts).values()) {
            out.writeBytes(part);
        }
        objects.put(key, out.toByteArray());
        respond(exchange, 200, "<CompleteMultipartUploadResult><Bucket>" + bucket
                + "</Bucket><Key>" + key + "</Key><ETag>\"multipart\"</ETag>"
                + "</CompleteMultipartUploadResult>");
    }

    private void handlePut(HttpExchange exchange, String key) throws IOException {
        String copySource = exchange.getRequestHeaders().getFirst("x-amz-copy-source");
        if (copySource != null) {
            String sourceKey = copySource.startsWith("/" + bucket + "/")
                    ? copySource.substring(("/" + bucket + "/").length()) : copySource;
            byte[] source = objects.get(decodePath("/" + sourceKey).substring(1));
            if (source == null) {
                respondXmlError(exchange, 404, "NoSuchKey", "复制源不存在");
                return;
            }
            objects.put(key, source.clone());
            respond(exchange, 200, "<CopyObjectResult><ETag>\"copy\"</ETag></CopyObjectResult>");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
        }
        objects.put(key, out.toByteArray());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null) {
            lastContentTypes.put(key, contentType);
        }
        respond(exchange, 200, "");
    }

    private void handleGet(HttpExchange exchange, String key) throws IOException {
        byte[] data = objects.get(key);
        if (data == null) {
            respondXmlError(exchange, 404, "NoSuchKey", "对象不存在");
            return;
        }
        exchange.getResponseHeaders().set("ETag", "\"fake-etag\"");
        exchange.sendResponseHeaders(200, data.length == 0 ? -1 : data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private void handleHead(HttpExchange exchange, String key) throws IOException {
        // com.sun server 对 HEAD 的 keep-alive 处理与 JDK 客户端不同步（复用即 EOF），
        // 测试环境的 HEAD 一律 Connection: close，真实 S3 服务无此问题
        exchange.getResponseHeaders().set("Connection", "close");
        byte[] data = objects.get(key);
        if (data == null) {
            exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.getResponseHeaders().set("ETag", "\"fake-etag\"");
        exchange.getResponseHeaders().set("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
        exchange.sendResponseHeaders(200, -1);
    }

    private static void drain(InputStream in) throws IOException {
        byte[] chunk = new byte[8192];
        while (in.read(chunk) >= 0) {
            // 排空请求体，避免提前响应导致客户端写请求体被 RST
        }
    }

    private void respondList(HttpExchange exchange, Map<String, String> query) throws IOException {
        lastListQuery.set(query);
        String prefix = query.getOrDefault("prefix", "");
        String delimiter = query.getOrDefault("delimiter", "");
        String marker = query.getOrDefault("marker", query.getOrDefault("continuation-token", ""));
        int maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000"));
        List<String> sorted = new ArrayList<>(objects.keySet());
        sorted.sort(String::compareTo);
        StringBuilder contents = new StringBuilder();
        StringBuilder prefixes = new StringBuilder();
        List<String> commonPrefixes = new ArrayList<>();
        int count = 0;
        boolean truncated = false;
        boolean listV2 = query.containsKey("list-type");
        String nextMarker = null;
        for (String key : sorted) {
            if (!key.startsWith(prefix) || key.equals(prefix)
                    || (!marker.isEmpty() && key.compareTo(marker) <= 0)) {
                continue;
            }
            String relative = key.substring(prefix.length());
            if (!delimiter.isEmpty()) {
                int at = relative.indexOf(delimiter);
                if (at >= 0) {
                    String common = prefix + relative.substring(0, at + delimiter.length());
                    if (!commonPrefixes.contains(common)) {
                        if (count >= maxKeys) {
                            truncated = true;
                            break;
                        }
                        commonPrefixes.add(common);
                        count++;
                    }
                    continue;
                }
            }
            if (count >= maxKeys) {
                truncated = true;
                nextMarker = key;
                break;
            }
            contents.append("<Contents><Key>").append(key).append("</Key><LastModified>")
                    .append("2026-10-11T00:00:00.000Z").append("</LastModified><ETag>&quot;e&quot;</ETag><Size>")
                    .append(objects.get(key).length).append("</Size>");
            // 与真实服务一致：V1 默认带 Owner；V2 仅 fetch-owner=true 时返回
            boolean fetchOwner = !listV2 || "true".equalsIgnoreCase(query.get("fetch-owner"));
            if (fetchOwner) {
                contents.append("<Owner><DisplayName>fake-owner</DisplayName><ID>uid-1</ID></Owner>");
            }
            contents.append("</Contents>");
            count++;
        }
        if (truncated && nextMarker == null && !commonPrefixes.isEmpty()) {
            nextMarker = commonPrefixes.get(commonPrefixes.size() - 1);
        }
        for (String common : commonPrefixes) {
            prefixes.append("<CommonPrefixes><Prefix>").append(common).append("</Prefix></CommonPrefixes>");
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>")
                .append("<Name>").append(bucket).append("</Name>")
                .append("<Prefix>").append(prefix).append("</Prefix>")
                .append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            xml.append(listV2 ? "<NextContinuationToken>" + nextMarker + "</NextContinuationToken>"
                    : "<NextMarker>" + nextMarker + "</NextMarker>");
        }
        xml.append(contents).append(prefixes).append("</ListBucketResult>");
        respond(exchange, 200, xml.toString());
    }

    private static void respondXmlError(HttpExchange exchange, int status, String code,
                                        String message) throws IOException {
        respond(exchange, status, "<Error><Code>" + code + "</Code><Message>" + message
                + "</Message></Error>");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 && status == 204 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** 路径解码：URLDecoder 会把 '+' 当空格，先把 '+' 保护起来。 */
    private static String decodePath(String rawPath) {
        return URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new TreeMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            String key = at < 0 ? pair : pair.substring(0, at);
            String value = at < 0 ? "" : pair.substring(at + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return query;
    }
}
