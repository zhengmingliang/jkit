package com.alianga.jkit.storage.git.adapter;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.git.GitCommit;
import com.alianga.jkit.storage.git.GitEntry;
import com.alianga.jkit.storage.git.GitSourceConfig;
import com.alianga.jkit.storage.git.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 四家平台共用的实现：Contents API 的解析、base64 解码、提交记录解析。
 *
 * <p>子类只需给出三处差异——{@link #apiPrefix}（路径前缀）、{@link #authHeaders}
 * （鉴权头）、{@link #commitAuthor}（作者字段嵌套）；其余逻辑（列目录 / 读文件 /
 * 解 base64 / 提交解析）在这里实现一次。</p>
 *
 * @author 郑明亮
 */
abstract class AbstractGitAdapter implements GitHostAdapter {
    @Override
    public String contentsUrl(GitSourceConfig config) {
        return config.baseUrl() + apiPrefix(config) + "/contents";
    }

    @Override
    public String commitsUrl(GitSourceConfig config) {
        return config.baseUrl() + apiPrefix(config) + "/commits";
    }

    /**
     * 仓库 API 前缀（如 {@code /repos/o/r} 或 {@code /api/v1/repos/o/r}）。
     *
     * @param config 连接配置
     * @return 前缀（以 / 开头）
     */
    protected abstract String apiPrefix(GitSourceConfig config);

    /**
     * 从提交对象里取作者名：各家嵌套不同（{@code commit.author.name} /
     * {@code author.login} / {@code author.username} / {@code author.full_name}）。
     *
     * @param commit 单个提交对象
     * @return 作者名；取不到返回 null
     */
    protected abstract String commitAuthor(Map<String, Object> commit);

    @Override
    public List<GitEntry> parseEntries(String body, String path, boolean expectFile) {
        if (expectFile) {
            Map<String, Object> one = Json.object(body, path);
            return Collections.singletonList(toEntry(one));
        }
        List<Map<String, Object>> items = Json.array(body, path);
        List<GitEntry> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            result.add(toEntry(item));
        }
        return result;
    }

    private static GitEntry toEntry(Map<String, Object> item) {
        String name = Json.str(item, "name");
        String entryPath = Json.str(item, "path");
        String type = Json.str(item, "type");
        long size = Json.num(item, "size", -1L);
        String sha = Json.str(item, "sha");
        String downloadUrl = Json.str(item, "download_url");
        return new GitEntry(name == null ? "" : name, entryPath == null ? "" : entryPath,
                type == null ? GitEntry.TYPE_FILE : type, size, sha, downloadUrl);
    }

    @Override
    public byte[] parseContent(String body, String path) {
        Map<String, Object> one = Json.object(body, path);
        String content = Json.str(one, "content");
        if (content == null || content.trim().isEmpty()) {
            // 目录、或超过平台内联上限的大文件：没有 content 字段
            return null;
        }
        String encoding = Json.str(one, "encoding");
        if (encoding != null && !"base64".equalsIgnoreCase(encoding)) {
            throw StorageException.unsupported("Git 返回了不支持的内容编码：" + encoding);
        }
        try {
            // 平台返回的 base64 带换行，MIMEDecoder 会直接拒绝，必须先剥掉
            return Base64.getMimeDecoder().decode(content.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw StorageException.io("文件内容 base64 解码失败", path, e);
        }
    }

    @Override
    public byte[] parseContentObject(Map<String, Object> meta, String path) {
        String content = Json.str(meta, "content");
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        String encoding = Json.str(meta, "encoding");
        if (encoding != null && !"base64".equalsIgnoreCase(encoding)) {
            throw StorageException.unsupported("Git 返回了不支持的内容编码：" + encoding);
        }
        try {
            // 平台返回的 base64 带换行，MIMEDecoder 会直接拒绝，必须先剥掉
            return Base64.getMimeDecoder().decode(content.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw StorageException.io("文件内容 base64 解码失败", path, e);
        }
    }

    @Override
    public String parseDownloadUrl(String body) {
        Map<String, Object> one = Json.object(body, "");
        return Json.str(one, "download_url");
    }

    @Override
    public List<GitCommit> parseCommits(String body, String path) {
        List<Map<String, Object>> items = Json.array(body, path);
        List<GitCommit> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            result.add(toCommit(item));
        }
        return result;
    }

    private GitCommit toCommit(Map<String, Object> item) {
        String sha = Json.str(item, "sha");
        long time = commitTime(item);
        String message = firstLine(commitMessage(item));
        if (message == null) {
            message = "";
        }
        return new GitCommit(sha == null ? "" : sha, commitAuthor(item), time, message);
    }

    /**
     * 提交说明：GitHub / Gogs 放在 {@code commit.message}，Gitea 也可能直接放在顶层
     * {@code message}（取决于版本与序列化形态），两处都找一遍。
     */
    private static String commitMessage(Map<String, Object> item) {
        Map<String, Object> commit = Json.obj(item, "commit");
        String nested = commit == null ? null : Json.str(commit, "message");
        if (nested != null && !nested.trim().isEmpty()) {
            return nested;
        }
        return Json.str(item, "message");
    }

    /** 提交时间：GitHub / Gogs 在 {@code commit.author.date}，Gitea 在 {@code created}。 */
    private static long commitTime(Map<String, Object> item) {
        Map<String, Object> commit = Json.obj(item, "commit");
        if (commit != null) {
            Map<String, Object> author = Json.obj(commit, "author");
            String date = author == null ? null : Json.str(author, "date");
            long parsed = parseIso(date);
            if (parsed != GitCommit.TIME_UNKNOWN) {
                return parsed;
            }
            Map<String, Object> committer = Json.obj(commit, "committer");
            parsed = parseIso(committer == null ? null : Json.str(committer, "date"));
            if (parsed != GitCommit.TIME_UNKNOWN) {
                return parsed;
            }
        }
        return parseIso(Json.str(item, "created"));
    }

    /**
     * ISO-8601 → epoch 毫秒；解析不了返回 {@link GitCommit#TIME_UNKNOWN}。
     *
     * <p>平台时区分两种写法：带偏移（{@code 2024-05-01T10:00:00Z}）用 OFFSET_DATE_TIME，
     * 不带偏移（Gitea 偶发 {@code 2024-05-01 10:00:00}）按本地时区补。</p>
     */
    static long parseIso(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return GitCommit.TIME_UNKNOWN;
        }
        String text = raw.trim();
        try {
            if (text.endsWith("Z")) {
                text = text.substring(0, text.length() - 1) + "+00:00";
            }
            if (text.length() >= 19 && text.charAt(10) == 'T') {
                return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli();
            }
            if (text.length() >= 19) {
                return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
        } catch (java.time.format.DateTimeParseException ignored) {
            // 时间格式不认识：界面显示「-」，其余字段照常展示
        }
        return GitCommit.TIME_UNKNOWN;
    }

    /** 提交说明只取首行：多行 message 在表格里会把行高撑爆。 */
    static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        int at = message.indexOf('\n');
        String line = at < 0 ? message : message.substring(0, at);
        return line.trim();
    }

    /** 作者名兜底：嵌套取不到时退到平铺字段，全部取不到才返回 null。 */
    static String authorFrom(Map<String, Object> item, String... nested) {
        Map<String, Object> author = Json.obj(item, "author");
        if (author != null) {
            for (String key : nested) {
                String value = Json.str(author, key);
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    /** 提交里的 {@code commit.author.name}（GitHub / Gogs / Gitee 用）。 */
    static String nestedCommitAuthor(Map<String, Object> item) {
        Map<String, Object> commit = Json.obj(item, "commit");
        if (commit == null) {
            return null;
        }
        Map<String, Object> author = Json.obj(commit, "author");
        String name = author == null ? null : Json.str(author, "name");
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return authorFrom(item, "login", "username", "full_name", "name");
    }

    @Override
    public String pageSizeParam() {
        return "per_page";
    }

    @Override
    public String encodePath(String repoPath) {
        if (repoPath == null || repoPath.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String segment : repoPath.split("/", -1)) {
            out.append('/').append(pctEncode(segment));
        }
        return out.toString();
    }

    /**
     * raw / CDN 路径编码：按 {@code /} 分段逐段百分号编码，保留段分隔符。
     *
     * <p>rawUrl 此前把 branch 与 repoPath 各段裸拼：文件名含空格 / # / % / 中文时
     * raw 兜底下载要么被 URI 解析拒绝（见 GitTransport.uri 的收口），要么 # 之后
     * 被当成 fragment 截断——私有仓大文件恰好都走这条路。contents API 的
     * {@link #encodePath} 早就编码了，这里补齐同一语义（区别：无前导斜杠，
     * 斜杠保留为分隔符）。</p>
     *
     * @param path 仓库内路径或分支名（可含斜杠，如 feature/x）
     * @return 逐段编码后的路径；null / 空原样返回
     */
    protected static String encodeRawPath(String path) {
        if (path == null || path.isEmpty()) {
            return path == null ? "" : path;
        }
        StringBuilder out = new StringBuilder(path.length() + 16);
        for (String segment : path.split("/", -1)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(pctEncode(segment));
        }
        return out.toString();
    }

    /** 路径段编码：空格为 {@code %20}（URLEncoder 给 {@code +}，Git API 不认），斜杠必须编码。 */
    static String pctEncode(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            boolean safe = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~';
            if (safe) {
                out.append((char) b);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
