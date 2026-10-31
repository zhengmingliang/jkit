package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Git 存储连接配置：平台 + 实例地址 + owner / repo / 分支 + 令牌 + 仓库内起始目录 + 提交信息策略。
 *
 * <p>逻辑路径与其余存储引擎一致：{@code ""}（或 {@code /}）为根，段间以 {@code /} 分隔；
 * 根会再叠加配置里的 {@code rootPath}（如 {@code docs/}），因此「存储根」可以是仓库的某个子目录。</p>
 *
 * <p>令牌属于密钥：配置里由 {@code ConnectionStore} 加密存放，本类的 {@link #toString()}
 * 与任何异常文案都不包含它。</p>
 *
 * @author 郑明亮
 */
public final class GitSourceConfig {
    /** 配置键：平台类型（github / gitee / gitea / gogs）。 */
    public static final String HOST = "host";
    /** 配置键：实例地址（Gitea / Gogs / 自建必填）。 */
    public static final String BASE_URL = "baseUrl";
    /** 配置键：仓库属主。 */
    public static final String OWNER = "owner";
    /** 配置键：仓库名。 */
    public static final String REPO = "repo";
    /** 配置键：分支。 */
    public static final String BRANCH = "branch";
    /** 配置键：访问令牌（密钥字段）。 */
    public static final String TOKEN = "token";
    /** 配置键：仓库内起始目录。 */
    public static final String ROOT_PATH = "rootPath";
    /** 配置键：提交信息模式（auto / manual）。 */
    public static final String COMMIT_MODE = "commitMode";
    /** 配置键：自动模式的提交信息模板。 */
    public static final String COMMIT_TEMPLATE = "commitTemplate";
    /** 配置键：CDN 加速域名（留空表示不启用）。 */
    public static final String CDN_BASE = "cdnBase";

    /** 内置 CDN：jsDelivr（加速 GitHub 公开仓库）。 */
    public static final String CDN_JSDELIVR = "https://cdn.jsdelivr.net";

    /** 自动模式：按模板生成提交信息。 */
    public static final String MODE_AUTO = "auto";
    /** 手动模式：每次上传 / 删除前弹窗询问。 */
    public static final String MODE_MANUAL = "manual";
    /**
     * 默认提交信息模板。
     *
     * <p>用 {@code {op}} 而不是写死 "upload"：新建目录、删除、重命名同样走这套模板，
     * 写死会让删除的提交也显示成「upload: xxx」，在仓库历史里误导人。</p>
     */
    public static final String DEFAULT_TEMPLATE = "{op}: {filename} @ {time}";

    private final GitHost host;
    private final String baseUrl;
    private final String owner;
    private final String repo;
    private final String branch;
    private final String token;
    private final List<String> rootSegments;
    private final String commitMode;
    private final String commitTemplate;
    private final String cdnBase;

    private GitSourceConfig(GitHost host, String baseUrl, String owner, String repo, String branch,
                            String token, List<String> rootSegments, String commitMode,
                            String commitTemplate, String cdnBase) {
        this.host = host;
        this.baseUrl = baseUrl;
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
        this.token = token;
        this.rootSegments = rootSegments;
        this.commitMode = commitMode;
        this.commitTemplate = commitTemplate;
        this.cdnBase = cdnBase;
    }

    /**
     * 解析并校验连接配置。
     *
     * @param config 引擎配置（已由 {@code FileStorages.of} 叠加平台默认值）
     * @return 合法配置
     * @throws StorageException INVALID_CONFIG：平台未知 / 缺实例地址 / 缺 owner 或 repo
     */
    public static GitSourceConfig from(StorageConfig config) {
        String rawHost = trimToEmpty(config.getOr(HOST, ""));
        GitHost host;
        try {
            host = GitHost.of(rawHost.isEmpty() ? GitHost.GITHUB.id() : rawHost);
        } catch (IllegalArgumentException e) {
            throw StorageException.invalidConfig(e.getMessage());
        }
        String baseUrl = normalizeBaseUrl(config.getOr(BASE_URL, defaultBaseUrl(host)));
        if (baseUrl.isEmpty()) {
            throw StorageException.invalidConfig(host.displayName() + " 连接缺少实例地址（baseUrl）");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw StorageException.invalidConfig("实例地址必须以 http:// 或 https:// 开头：" + baseUrl);
        }
        String owner = trimToEmpty(config.getOr(OWNER, ""));
        if (owner.isEmpty()) {
            throw StorageException.invalidConfig("Git 连接缺少仓库属主（owner）");
        }
        String repo = trimToEmpty(config.getOr(REPO, ""));
        if (repo.isEmpty()) {
            throw StorageException.invalidConfig("Git 连接缺少仓库名（repo）");
        }
        String branch = trimToEmpty(config.getOr(BRANCH, "main"));
        if (branch.isEmpty()) {
            branch = "main";
        }
        String token = config.get(TOKEN);
        // 匿名只读：公开仓库可以不配令牌，写操作时由 Adapter 返回 401，界面再提示
        if (token == null) {
            token = "";
        }
        List<String> rootSegments = splitSegments(config.getOr(ROOT_PATH, ""));
        String mode = trimToEmpty(config.getOr(COMMIT_MODE, MODE_AUTO)).toLowerCase(Locale.ROOT);
        if (!MODE_MANUAL.equals(mode)) {
            mode = MODE_AUTO;
        }
        String template = config.getOr(COMMIT_TEMPLATE, DEFAULT_TEMPLATE);
        if (template.trim().isEmpty()) {
            template = DEFAULT_TEMPLATE;
        }
        // CDN 域名必须真是 http(s) 开头：历史上曾把下拉的「显示名」存进来，
        // 这类值拼出来的链接形如「jsDelivr（cdn.jsdelivr.net）/gh/...」，完全不可用。
        // 这里直接判定为「未启用」，不抛异常——配置脏不该让整个连接打不开。
        String cdn = normalizeBaseUrl(config.getOr(CDN_BASE, ""));
        if (!cdn.isEmpty() && !cdn.startsWith("http://") && !cdn.startsWith("https://")) {
            cdn = "";
        }
        return new GitSourceConfig(host, baseUrl, owner, repo, branch, token,
                rootSegments, mode, template, cdn);
    }

    /** 平台默认实例地址：自建平台没有默认值（返回空串，由必填校验拦下）。 */
    private static String defaultBaseUrl(GitHost host) {
        if (host == GitHost.GITHUB) {
            return "https://api.github.com";
        }
        if (host == GitHost.GITEE) {
            return "https://gitee.com";
        }
        return "";
    }

    /** 去掉结尾斜杠，避免拼出 {@code //repos}。 */
    private static String normalizeBaseUrl(String raw) {
        String value = trimToEmpty(raw);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static List<String> splitSegments(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(normalized.split("/"))));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public GitHost host() {
        return host;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String owner() {
        return owner;
    }

    public String repo() {
        return repo;
    }

    public String branch() {
        return branch;
    }

    /** @return 访问令牌；未配置返回空串（公开仓库匿名只读） */
    public String token() {
        return token;
    }

    /** @return true 表示配置了令牌 */
    public boolean authenticated() {
        return !token.isEmpty();
    }

    /** @return 提交信息模式（{@link #MODE_AUTO} / {@link #MODE_MANUAL}） */
    public String commitMode() {
        return commitMode;
    }

    /** @return true 表示手动模式（每次写操作前询问提交信息） */
    public boolean manualCommit() {
        return MODE_MANUAL.equals(commitMode);
    }

    /** @return 提交信息模板 */
    public String commitTemplate() {
        return commitTemplate;
    }

    /** @return CDN 加速域名（已去尾斜杠）；未配置返回空串 */
    public String cdnBase() {
        return cdnBase;
    }

    /** @return true 表示配置了 CDN 加速域名 */
    public boolean cdnEnabled() {
        return !cdnBase.isEmpty();
    }

    /** @return 起始目录段（仓库内前缀，可能为空） */
    public List<String> rootSegments() {
        return rootSegments;
    }

    /**
     * 逻辑路径 → 仓库内实际路径（叠加 {@code rootPath}）。
     *
     * @param logicalPath 逻辑路径
     * @return 仓库内路径；根且无 rootPath 时为空串
     */
    public String repoPath(String logicalPath) {
        String relative = StoragePaths.normalize(logicalPath);
        if (rootSegments.isEmpty()) {
            return relative;
        }
        String prefix = String.join("/", rootSegments);
        return relative.isEmpty() ? prefix : prefix + "/" + relative;
    }

    /** 不含令牌的诊断文本。 */
    @Override
    public String toString() {
        String root = rootSegments.isEmpty() ? "" : "/" + String.join("/", rootSegments);
        return host.id() + "[" + baseUrl + " " + owner + "/" + repo + "@" + branch + root + "]";
    }
}
