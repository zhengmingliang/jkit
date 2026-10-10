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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用假阿里云 OSS 服务器：内存对象存储 + ListObjects 语义 + 简单错误 XML。
 * Authorization / Date 头会记录下来供签名重算断言使用。
 *
 * @author 郑明亮
 */
final class FakeOssServer {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Map<String, String> lastDate = new ConcurrentHashMap<>();
    final Map<String, String> lastAuthorization = new ConcurrentHashMap<>();
    /** 分片上传状态：uploadId → (partNumber → 数据)。完成后清空。 */
    final Map<String, Map<Integer, byte[]>> multipartParts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong uploadIds = new java.util.concurrent.atomic.AtomicLong();
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
            String date = exchange.getRequestHeaders().getFirst("Date");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (date != null) {
                lastDate.put("last", date);
            }
            if (authorization != null) {
                lastAuthorization.put("last", authorization);
            }
            String method = exchange.getRequestMethod();
            String path = decode(exchange.getRequestURI().getRawPath());
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if (path.equals("/" + bucket) && "GET".equals(method)) {
                respondList(exchange, query);
                return;
            }
            if (!path.startsWith("/" + bucket + "/")) {
                respondXmlError(exchange, 404, "NoSuchBucket", "bucket 不存在");
                return;
            }
            String key = path.substring(("/" + bucket + "/").length());
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
                    respond(exchange, 405, "");
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
        String copySource = exchange.getRequestHeaders().getFirst("x-oss-copy-source");
        if (copySource != null) {
            String sourceKey = copySource.startsWith("/" + bucket + "/")
                    ? copySource.substring(("/" + bucket + "/").length()) : copySource;
            byte[] source = objects.get(decode("/" + sourceKey).substring(1));
            if (source == null) {
                respondXmlError(exchange, 404, "NoSuchKey", "复制源不存在");
                return;
            }
            objects.put(key, source.clone());
            respond(exchange, 200,
                    "<CopyObjectResult><ETag>\"copy\"</ETag><LastModified>"
                            + "2026-10-11T00:00:00.000Z</LastModified></CopyObjectResult>");
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
        if (data.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        }
    }

    private void handleHead(HttpExchange exchange, String key) throws IOException {
        byte[] data = objects.get(key);
        exchange.getResponseHeaders().set("Connection", "close");
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

    private void respondList(HttpExchange exchange, Map<String, String> query) throws IOException {
        String prefix = query.getOrDefault("prefix", "");
        String delimiter = query.getOrDefault("delimiter", "");
        String marker = query.getOrDefault("marker", "");
        int maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000"));
        StringBuilder contents = new StringBuilder();
        StringBuilder prefixes = new StringBuilder();
        java.util.List<String> commons = new java.util.ArrayList<>();
        int count = 0;
        boolean truncated = false;
        String nextMarker = null;
        for (String key : new TreeMap<>(objects).keySet()) {
            if (!key.startsWith(prefix) || key.equals(prefix)
                    || (!marker.isEmpty() && key.compareTo(marker) <= 0)) {
                continue;
            }
            String relative = key.substring(prefix.length());
            if (!delimiter.isEmpty()) {
                int at = relative.indexOf(delimiter);
                if (at >= 0) {
                    String common = prefix + relative.substring(0, at + delimiter.length());
                    if (!commons.contains(common)) {
                        if (count >= maxKeys) {
                            truncated = true;
                            break;
                        }
                        commons.add(common);
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
                    .append("2026-10-11T00:00:00.000Z</LastModified><ETag>&quot;e&quot;</ETag><Size>")
                    .append(objects.get(key).length).append("</Size></Contents>");
            count++;
        }
        if (truncated && nextMarker == null && !commons.isEmpty()) {
            nextMarker = commons.get(commons.size() - 1);
        }
        for (String common : commons) {
            prefixes.append("<CommonPrefixes><Prefix>").append(common).append("</Prefix></CommonPrefixes>");
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>")
                .append("<Name>").append(bucket).append("</Name>")
                .append("<Prefix>").append(prefix).append("</Prefix>")
                .append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated && nextMarker != null) {
            xml.append("<NextMarker>").append(nextMarker).append("</NextMarker>");
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
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String decode(String rawPath) {
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
