package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StoragePaths;

import java.util.HashMap;
import java.util.Map;

/**
 * 扩展名 → Content-Type 映射：上传时自动补 Content-Type，
 * 覆盖常见文本、图片、音视频、文档与代码类型。
 *
 * @author 郑明亮
 */
public final class MimeTypes {
    private static final Map<String, String> TYPES = new HashMap<String, String>();

    static {
        text("txt", "text/plain");
        text("log", "text/plain");
        text("md", "text/markdown");
        text("csv", "text/csv");
        text("html", "text/html");
        text("htm", "text/html");
        text("css", "text/css");
        text("js", "application/javascript");
        text("mjs", "application/javascript");
        text("json", "application/json");
        text("xml", "application/xml");
        text("yaml", "application/yaml");
        text("yml", "application/yaml");
        text("properties", "text/plain");
        text("ini", "text/plain");
        text("conf", "text/plain");
        text("sql", "application/sql");
        text("sh", "application/x-sh");
        text("bat", "application/x-bat");
        text("java", "text/x-java-source");
        text("py", "text/x-python");
        text("go", "text/x-go");
        text("rs", "text/x-rust");
        text("c", "text/x-c");
        text("cpp", "text/x-c");
        text("h", "text/x-c");
        text("ts", "application/typescript");
        text("vue", "text/plain");
        text("php", "application/x-httpd-php");
        image("png", "image/png");
        image("jpg", "image/jpeg");
        image("jpeg", "image/jpeg");
        image("gif", "image/gif");
        image("bmp", "image/bmp");
        image("webp", "image/webp");
        image("svg", "image/svg+xml");
        image("ico", "image/x-icon");
        put("pdf", "application/pdf");
        put("zip", "application/zip");
        put("gz", "application/gzip");
        put("tgz", "application/gzip");
        put("tar", "application/x-tar");
        put("7z", "application/x-7z-compressed");
        put("rar", "application/vnd.rar");
        put("jar", "application/java-archive");
        put("class", "application/java-vm");
        put("exe", "application/vnd.microsoft.portable-executable");
        put("msi", "application/x-msdownload");
        put("apk", "application/vnd.android.package-archive");
        put("wasm", "application/wasm");
        put("doc", "application/msword");
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        put("xls", "application/vnd.ms-excel");
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        put("ppt", "application/vnd.ms-powerpoint");
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        put("mp3", "audio/mpeg");
        put("wav", "audio/wav");
        put("flac", "audio/flac");
        put("ogg", "audio/ogg");
        put("mp4", "video/mp4");
        put("avi", "video/x-msvideo");
        put("mov", "video/quicktime");
        put("mkv", "video/x-matroska");
        put("webm", "video/webm");
        put("ttf", "font/ttf");
        put("otf", "font/otf");
        put("woff", "font/woff");
        put("woff2", "font/woff2");
    }

    private MimeTypes() {
    }

    /**
     * 按路径扩展名推断 Content-Type。
     *
     * @param path 对象路径
     * @return Content-Type，未知类型返回 {@code application/octet-stream}
     */
    public static String of(String path) {
        String type = TYPES.get(StoragePaths.extension(path));
        return type == null ? "application/octet-stream" : type;
    }

    /**
     * 判断扩展名是否属于常见文本类型（界面「在线编辑」入口的提示依据）。
     *
     * @param path 对象路径
     * @return true 表示像文本文件
     */
    public static boolean looksLikeText(String path) {
        String type = TYPES.get(StoragePaths.extension(path));
        if (type == null) {
            return false;
        }
        return type.startsWith("text/") || type.contains("javascript") || type.contains("json")
                || type.contains("xml") || type.contains("yaml") || type.contains("sql")
                || type.contains("typescript") || type.contains("x-sh") || type.contains("x-c")
                || type.contains("x-python") || type.contains("x-go") || type.contains("x-rust")
                || type.contains("x-httpd-php") || type.contains("x-bat");
    }

    private static void put(String extension, String contentType) {
        TYPES.put(extension, contentType);
    }

    private static void text(String extension, String contentType) {
        TYPES.put(extension, contentType);
    }

    private static void image(String extension, String contentType) {
        TYPES.put(extension, contentType);
    }
}
