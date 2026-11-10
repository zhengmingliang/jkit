package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.SimpleJson;
import com.alianga.jkit.storage.internal.Urls;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Gist REST 客户端（纯 JDK，零第三方依赖）：Bearer 鉴权、统一错误映射、
 * 取 Gist（含大文件 {@code truncated} → {@code raw_url} 回源）、PATCH 增删改文件、
 * 列举账号下所有 Gist 供「自动解析」用。
 *
 * <p>所有请求都带 GitHub 要求的 {@code Accept} / {@code User-Agent} /
 * {@code X-GitHub-Api-Version} 头，并复用 {@link HttpCore#configured} 应用代理与超时配置。</p>
 *
 * @author 郑明亮
 */
public final class GistClient {
    /** 每次列举分页大小。 */
    private static final int PER_PAGE = 100;
    /** 列举分页硬上限（防御异常服务端无限翻页）。 */
    private static final int MAX_PAGES = 100;

    private final StorageConfig config;
    private final String apiBase;
    private final String token;
    /** 是否开启写入加密（读取按信封前缀判定，不依赖这个开关）。 */
    private final boolean encryptionOn;
    /** 写入用的加密口令；读取时若为空但遇到信封会明确报错。 */
    private final String password;

    /**
     * @param config 连接配置
     */
    public GistClient(StorageConfig config) {
        this.config = config;
        this.apiBase = GistConfig.apiBase(config.get(GistConfig.API_BASE));
        String raw = config.getOr(GistConfig.TOKEN, "");
        this.token = raw.trim();
        this.encryptionOn = GistConfig.encryptionOn(config);
        this.password = config.getOr(GistConfig.CONTENT_PASSWORD, "").trim();
    }

    /** @return 归一化后的 API 基础地址 */
    public String apiBase() {
        return apiBase;
    }

    /**
     * 取一个 Gist 的完整 {@code files} 映射（已解析为「扁平文件名 → 文件内容字节」）。
     *
     * <p>响应里 {@code truncated=true} 的文件只给了前 1 MB，按 {@code raw_url} 回源取全量；
     * 顶层 {@code truncated=true}（文件数 &gt; 300）会明确报错，不静默漏文件。</p>
     *
     * @param gistId Gist ID
     * @return 文件名 → 内容字节（有序）
     * @throws StorageException 不存在 / 鉴权失败 / 限流 / 顶层截断
     */
    public Map<String, byte[]> getGist(String gistId) {
        String url = apiBase + "/gists/" + Urls.encodeSegment(gistId);
        HttpCore.Response response = execute("GET", url, null);
        String text = readBody(response, gistId);
        Map<String, Object> json = parseObject(text, gistId);
        Map<String, Object> files = asMap(json.get("files"));
        if (files == null) {
            throw StorageException.io("Gist 响应缺少 files 字段", gistId, null);
        }
        if (Boolean.TRUE.equals(json.get("truncated"))) {
            throw StorageException.conflict("该 Gist 文件数超过 300，API 只返回了前 300 个文件，"
                    + "请改用仓库（Git 托管平台）或拆分 Gist", gistId);
        }
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, Object> entry : files.entrySet()) {
            Map<String, Object> file = asMap(entry.getValue());
            if (file == null) {
                continue;
            }
            String name = stringOf(file.get("filename"));
            if (name == null || name.isEmpty()) {
                name = entry.getKey();
            }
            String stored;
            if (Boolean.TRUE.equals(file.get("truncated"))) {
                String rawUrl = stringOf(file.get("raw_url"));
                if (rawUrl == null || rawUrl.isEmpty()) {
                    throw StorageException.io("Gist 文件被截断但缺少 raw_url: " + name, gistId, null);
                }
                stored = new String(getRaw(rawUrl, name), StandardCharsets.UTF_8);
            } else {
                stored = stringOf(file.get("content"));
            }
            // 解密按「信封前缀」判定，不看开关：旧明文、混合 gist、关了开关但留着口令都能读
            String encoded = GistContentCipher.isEncrypted(stored)
                    ? GistContentCipher.decrypt(password, stored, name) : stored;
            result.put(name, GistPaths.decodeContent(encoded));
        }
        return result;
    }

    /**
     * 列举当前账号的全部 Gist（自动翻页），用于「按 ID / 描述查找」。
     *
     * @return 每项含 id / description / fileNames / htmlUrl / updatedAt
     */
    public List<GistInfo> listGists() {
        List<GistInfo> result = new ArrayList<GistInfo>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = apiBase + "/gists?per_page=" + PER_PAGE + "&page=" + page;
            HttpCore.Response response = execute("GET", url, null);
            String text = readBody(response, "-");
            List<?> items = parseArray(text, "-");
            for (Object item : items) {
                Map<String, Object> gist = asMap(item);
                if (gist == null) {
                    continue;
                }
                List<String> names = new ArrayList<String>();
                Map<String, Object> files = asMap(gist.get("files"));
                if (files != null) {
                    names.addAll(files.keySet());
                }
                result.add(new GistInfo(stringOf(gist.get("id")), stringOf(gist.get("description")),
                        names, stringOf(gist.get("html_url")), stringOf(gist.get("updated_at"))));
            }
            if (items.size() < PER_PAGE) {
                break;
            }
        }
        return result;
    }

    /**
     * 新建一个 Gist（POST /gists），返回服务端分配的 ID。
     *
     * @param description 描述
     * @param publicGist  是否公开
     * @param seedFile    首个文件的扁平文件名与内容
     * @return 新 Gist 的 ID
     */
    public String createGist(String description, boolean publicGist, Map.Entry<String, byte[]> seedFile) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("description", description);
        body.put("public", publicGist);
        Map<String, Object> files = new LinkedHashMap<String, Object>();
        files.put(seedFile.getKey(), contentNode(seedFile.getValue(), seedFile.getKey()));
        body.put("files", files);
        HttpCore.Response response = executeJson("POST", apiBase + "/gists", SimpleJson.toJson(body));
        String text = readBody(response, "-");
        String id = stringOf(parseObject(text, "-").get("id"));
        if (id == null || id.isEmpty()) {
            throw StorageException.io("创建 Gist 成功但响应缺少 id", "-", null);
        }
        return id;
    }

    /**
     * 删除整个 Gist（DELETE /gists/{id}）。
     *
     * @param gistId Gist ID
     */
    public void deleteGist(String gistId) {
        String url = apiBase + "/gists/" + Urls.encodeSegment(gistId);
        HttpCore.Response response = execute("DELETE", url, null);
        readBody(response, gistId);
    }

    /**
     * PATCH 更新一个 Gist 的 {@code files} 映射：改内容 / 改名 / 删除。
     *
     * @param gistId   Gist ID
     * @param changes  文件名 → 变更（内容字节、新文件名，或 null 表示删除）
     */
    public void patch(String gistId, Map<String, FileChange> changes) {
        Map<String, Object> files = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, FileChange> entry : changes.entrySet()) {
            FileChange change = entry.getValue();
            if (change == null) {
                files.put(entry.getKey(), null);
            } else if (change.newName != null) {
                Map<String, Object> node = new LinkedHashMap<String, Object>();
                node.put("filename", change.newName);
                if (change.content != null) {
                    node.put("content", storedContent(change.content, entry.getKey()));
                }
                files.put(entry.getKey(), node);
            } else {
                files.put(entry.getKey(), contentNode(change.content, entry.getKey()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("files", files);
        HttpCore.Response response = executeJson("PATCH",
                apiBase + "/gists/" + Urls.encodeSegment(gistId), SimpleJson.toJson(body));
        readBody(response, gistId);
    }

    /** 一次 PATCH 里对单个文件的变更（{@code newName != null} 表示改名）。 */
    public static final class FileChange {
        private final byte[] content;
        private final String newName;

        private FileChange(byte[] content, String newName) {
            this.content = content;
            this.newName = newName;
        }

        /** @param bytes 新内容（null 表示只改名不写内容） @return 内容变更 */
        public static FileChange content(byte[] bytes) {
            return new FileChange(bytes, null);
        }

        /** @param newName 新文件名 @return 改名变更 */
        public static FileChange rename(String newName) {
            return new FileChange(null, newName);
        }

        /** @param bytes 新内容 @param newName 新文件名 @return 改名 + 改内容变更 */
        public static FileChange renameWithContent(String newName, byte[] bytes) {
            return new FileChange(bytes, newName);
        }
    }

    /** 列举结果里的一项 Gist 摘要。 */
    public static final class GistInfo {
        private final String id;
        private final String description;
        private final List<String> fileNames;
        private final String htmlUrl;
        private final String updatedAt;

        GistInfo(String id, String description, List<String> fileNames, String htmlUrl, String updatedAt) {
            this.id = id;
            this.description = description;
            this.fileNames = fileNames;
            this.htmlUrl = htmlUrl;
            this.updatedAt = updatedAt;
        }

        /** @return Gist ID */
        public String id() {
            return id;
        }

        /** @return 描述 */
        public String description() {
            return description;
        }

        /** @return 文件名清单 */
        public List<String> fileNames() {
            return fileNames;
        }

        /** @return 网页地址 */
        public String htmlUrl() {
            return htmlUrl;
        }

        /** @return 最后更新时间（ISO-8601） */
        public String updatedAt() {
            return updatedAt;
        }
    }

    /** 从 {@code raw_url} 回源取被截断文件的完整内容（raw 响应无需鉴权头也可，但带上无害）。 */
    private byte[] getRaw(String rawUrl, String name) {
        HttpCore.Response response = execute("GET", rawUrl, null);
        try {
            if (response.status() >= 400) {
                throw mapStatus(response.status(), "", name);
            }
            return response.bodyBytes();
        } finally {
            response.close();
        }
    }

    /**
     * 读取响应体一次，并按状态码做统一错误映射。{@code bodyText()} 会顺带关闭响应，
     * 因此所有请求都只经这里读一次，避免二次读取空响应体。
     */
    private static String readBody(HttpCore.Response response, String path) {
        int status = response.status();
        String text = response.bodyText();
        if (status >= 400) {
            throw mapStatus(status, githubMessage(text), path);
        }
        return text;
    }

    private Map<String, Object> contentNode(byte[] bytes, String objectPath) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("content", storedContent(bytes == null ? new byte[0] : bytes, objectPath));
        return node;
    }

    /**
     * 所有写点的唯一入口：明文字节 → UTF-8/Base64 编码 → （开启时）加密信封 →
     * 校验最终存储文本长度。这样 PATCH / 改名带内容 / createGist 三条路径不会漏加密。
     *
     * <p><b>为什么在这里校验 1 MB</b>：GitHub 的 1 MB 上限针对的是最终写进
     * {@code content} 的文本，而不是原始字节。二进制 Base64 会膨胀约 1/3，加密信封
     * 又叠加 JSON + Base64（salt/iv/tag/密文），一个接近 1 MB 的明文最终可能超过上限
     * 被 GitHub 截断或拒收。这里按 UTF-8 字节精确拦截，明确报 CONFLICT，不静默截断。</p>
     */
    private String storedContent(byte[] bytes, String objectPath) {
        String encoded = GistPaths.encodeContent(bytes == null ? new byte[0] : bytes);
        String stored = encryptionOn
                ? GistContentCipher.encrypt(password, encoded, objectPath) : encoded;
        long storedBytes = stored.getBytes(StandardCharsets.UTF_8).length;
        if (storedBytes > GistConfig.MAX_UPLOAD_BYTES) {
            throw StorageException.conflict("GitHub Gist 存储内容超过 1 MB 上限：明文 "
                    + (bytes == null ? 0 : bytes.length) + " 字节，编码"
                    + (encryptionOn ? "并加密" : "") + "后 " + storedBytes
                    + " 字节；请缩小文件或改用 Git 托管平台（仓库）", objectPath);
        }
        return stored;
    }

    private HttpCore.Response execute(String method, String url, String jsonBody) {
        return executeJson(method, url, jsonBody);
    }

    private HttpCore.Response executeJson(String method, String url, String jsonBody) {
        HttpCore.Request request = new HttpCore.Request(method, url)
                .header("Accept", GistConfig.ACCEPT)
                .header("User-Agent", GistConfig.USER_AGENT)
                .header("X-GitHub-Api-Version", GistConfig.API_VERSION);
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
            request.body(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return HttpCore.execute(HttpCore.configured(request, config));
    }

    /** HTTP 状态码 → 统一 StorageException；带 GitHub 简短消息，不吞原始原因。 */
    private static StorageException mapStatus(int status, String message, String path) {
        String detail = message == null || message.isEmpty() ? "" : "：" + message;
        switch (status) {
            case 401:
                return StorageException.authFailed("GitHub Gist 鉴权失败（令牌无效或已过期）" + detail);
            case 403:
                return StorageException.authFailed("GitHub Gist 无权限或触发限流（令牌需 gist 范围）" + detail);
            case 404:
                return StorageException.notFound(path);
            case 409:
                return StorageException.conflict("GitHub Gist 并发冲突" + detail, path);
            case 422:
                return StorageException.invalidConfig("GitHub Gist 拒绝请求（文件名含非法字符或内容非法）" + detail);
            case 429:
                return new StorageException(StorageErrorCode.RATE_LIMITED,
                        "GitHub Gist 触发限流，请稍后重试" + detail, path);
            default:
                if (status >= 500) {
                    return StorageException.serverError("GitHub Gist 服务端错误（" + status + "）" + detail, path);
                }
                return StorageException.io("GitHub Gist 请求失败（" + status + "）" + detail, path, null);
        }
    }

    private static String githubMessage(String body) {
        Map<String, Object> json = SimpleJson.parseObject(body);
        if (json == null) {
            return summarize(body);
        }
        String message = stringOf(json.get("message"));
        if (message == null || message.isEmpty()) {
            return summarize(body);
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private static String summarize(String body) {
        String text = body == null ? "" : body.trim();
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    private static Map<String, Object> parseObject(String text, String path) {
        Map<String, Object> json = SimpleJson.parseObject(text);
        if (json == null) {
            throw StorageException.io("Gist 响应无法解析：" + summarize(text), path, null);
        }
        return json;
    }

    private static List<?> parseArray(String text, String path) {
        // SimpleJson.parseObject 只认对象根；顶层数组走一次轻量包装再取
        Object parsed;
        try {
            parsed = SimpleJson.parseObject("{\"a\":" + text + "}");
            Map<?, ?> wrapper = (Map<?, ?>) parsed;
            Object value = wrapper.get("a");
            if (value instanceof List<?>) {
                return (List<?>) value;
            }
        } catch (RuntimeException ignored) {
            // 落到下面统一报错
        }
        throw StorageException.io("Gist 列表响应无法解析：" + summarize(text), path, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
