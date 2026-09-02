package com.alianga.jkit.http;

import com.alianga.jkit.FileUtils;
import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.StringUtils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表单与 multipart 请求体构造。
 */
public final class HttpBodies {
    /**
     * 超过该大小的上传文件不再拼进内存，改为拼装到临时文件后流式发送。
     */
    public static final long SPOOL_THRESHOLD_BYTES = 1024L * 1024L;

    private static final Pattern HEADER_INJECTION = Pattern.compile("[\\r\\n]");
    private static final byte[] CRLF = new byte[]{'\r', '\n'};

    private HttpBodies() {
    }

    /**
     * 将参数编码为 {@code application/x-www-form-urlencoded} 请求体（UTF-8）。
     *
     * @param params 表单参数，{@code null} 或为空时返回空数组
     * @return 编码后的请求体
     */
    public static byte[] formUrlEncoded(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new byte[0];
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(HttpUtils.encodeValue(entry.getKey()));
            builder.append('=');
            builder.append(HttpUtils.encodeValue(String.valueOf(entry.getValue())));
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造 multipart/form-data 请求体（文件字段 + 可选文本字段）。
     * <p>
     * 字段名/文件名中的 CR/LF 会被剔除，避免头注入；文件不存在或为目录时抛出 {@link IOException}。
     * <p>
     * 大于 {@value #SPOOL_THRESHOLD_BYTES} 字节的文件会被拼装到临时文件并以流式方式发送，
     * 避免把整个文件读进内存（旧实现峰值内存约为文件的 2 倍）。此时
     * {@link MultipartPayload#bodyFile} 非空且 {@link MultipartPayload#temporary} 为 {@code true}，
     * 调用方发送完成后必须调用 {@link MultipartPayload#cleanup()}。
     *
     * @param uploadInfo 上传文件信息（{@code filePath} 必填）
     * @param params 附加文本字段，可为 {@code null}
     * @return multipart 载荷（Content-Type 与完整请求体）
     * @throws IOException 文件缺失或不可读
     */
    public static MultipartPayload multipart(UploadInfo uploadInfo, Map<String, String> params)
            throws IOException {
        String boundary = "----jkitFormBoundary" + Long.toHexString(System.nanoTime());
        if (uploadInfo == null || StringUtils.isBlank(uploadInfo.getFilePath())) {
            throw new IOException("upload file is required");
        }
        File file = new File(uploadInfo.getFilePath());
        if (!file.isFile()) {
            throw new IOException("upload file does not exist: " + file);
        }
        String uploadFileName = uploadInfo.getFileName();
        if (StringUtils.isBlank(uploadFileName)) {
            uploadFileName = file.getName();
        }
        String mediaType = uploadInfo.getMediaType();
        if (StringUtils.isBlank(mediaType)) {
            mediaType = FileUtils.getContentType(file);
        }
        if (StringUtils.isBlank(mediaType)) {
            mediaType = "application/octet-stream";
        }
        String fieldName = uploadInfo.getKey();
        if (StringUtils.isBlank(fieldName)) {
            fieldName = "file";
        }
        fieldName = safeHeaderValue(fieldName);
        uploadFileName = safeHeaderValue(uploadFileName);
        String contentType = "multipart/form-data; boundary=" + boundary;
        String dashBoundary = "--" + boundary;

        if (file.length() > SPOOL_THRESHOLD_BYTES) {
            File temp = File.createTempFile("jkit-multipart-", ".tmp");
            try {
                OutputStream out = new BufferedOutputStream(new FileOutputStream(temp));
                try {
                    writeMultipart(out, dashBoundary, fieldName, uploadFileName, mediaType, file, params);
                    out.flush();
                } finally {
                    HttpIo.closeQuietly(out);
                }
            } catch (IOException e) {
                if (!temp.delete()) {
                    temp.deleteOnExit();
                }
                throw e;
            }
            return new MultipartPayload(contentType, temp);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeMultipart(baos, dashBoundary, fieldName, uploadFileName, mediaType, file, params);
        return new MultipartPayload(contentType, baos.toByteArray());
    }

    private static void writeMultipart(OutputStream out, String dashBoundary, String fieldName,
                                       String uploadFileName, String mediaType, File file,
                                       Map<String, String> params) throws IOException {
        writePartHeader(out, dashBoundary, fieldName, uploadFileName, mediaType);
        HttpIo.copyFile(file, out, HttpConfig.shared().getDownloadBufferSize());
        out.write(CRLF);
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                writeTextPart(out, dashBoundary, entry.getKey(),
                        entry.getValue() == null ? "" : entry.getValue());
            }
        }
        out.write((dashBoundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String safeHeaderValue(String value) {
        return HEADER_INJECTION.matcher(value == null ? "" : value).replaceAll("");
    }

    private static void writePartHeader(OutputStream out, String dashBoundary,
                                        String name, String filename, String contentType)
            throws IOException {
        out.write(dashBoundary.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
        String disposition = "Content-Disposition: form-data; name=\"" + safeHeaderValue(name)
                + "\"; filename=\"" + safeHeaderValue(filename) + "\"\r\n";
        out.write(disposition.getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTextPart(OutputStream out, String dashBoundary,
                                      String name, String value) throws IOException {
        out.write(dashBoundary.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
        String disposition = "Content-Disposition: form-data; name=\"" + safeHeaderValue(name) + "\"\r\n\r\n";
        out.write(disposition.getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }

    /**
     * multipart 请求体载荷：小文件走内存 {@link #body}，大文件走临时文件 {@link #bodyFile}。
     */
    public static final class MultipartPayload {
        /**
         * 请求应使用的 {@code Content-Type}（含 boundary）。
         */
        public final String contentType;

        /**
         * 完整 multipart 请求体字节；走临时文件时为 {@code null}。
         */
        public final byte[] body;

        /**
         * 承载完整 multipart 请求体的临时文件；走内存时为 {@code null}。
         */
        public final File bodyFile;

        /**
         * {@link #bodyFile} 是否是本类创建的临时文件（需要调用方清理）。
         */
        public final boolean temporary;

        MultipartPayload(String contentType, byte[] body) {
            this.contentType = contentType;
            this.body = body;
            this.bodyFile = null;
            this.temporary = false;
        }

        MultipartPayload(String contentType, File bodyFile) {
            this.contentType = contentType;
            this.body = null;
            this.bodyFile = bodyFile;
            this.temporary = true;
        }

        /**
         * 删除临时文件。走内存时为空操作，可重复调用。
         */
        public void cleanup() {
            if (temporary && bodyFile != null && bodyFile.exists() && !bodyFile.delete()) {
                bodyFile.deleteOnExit();
            }
        }
    }
}
