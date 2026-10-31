package com.alianga.jkit.storage.git;

import java.util.Locale;

/**
 * Git 托管平台：决定 baseUrl、鉴权头、API 路径与 JSON 字段差异。
 *
 * <p>四家平台的 REST 语义高度一致（都是「仓库内容 + 提交」模型），差异只在四处：
 * <ol>
 *   <li>baseUrl：GitHub / Gitee 固定公网，Gitea / Gogs 自建；</li>
 *   <li>鉴权头：GitHub 用 {@code Bearer}，Gitea / Gogs 常用 {@code token}，Gitee 用查询参数；</li>
 *   <li>API 路径：GitHub / Gitee 是 {@code /repos/{owner}/{repo}/...}，
 *       Gitea / Gogs 是 {@code /api/v1/repos/{owner}/{repo}/...}；</li>
 *   <li>JSON 字段：提交作者字段嵌套不同（{@code commit.author} / {@code author.Login} / {@code author.username}）</li>
 * </ol>
 * 这些差异全部收在 {@code adapter} 包里，本枚举只负责「id ↔ 平台」的映射。</p>
 *
 * @author 郑明亮
 */
public enum GitHost {
    /** GitHub（含 GitHub Enterprise：实例地址填企业域名即可）。 */
    GITHUB("github", "GitHub", true),
    /** Gitee（码云）。 */
    GITEE("gitee", "Gitee", false),
    /** Gitea（自建）。 */
    GITEA("gitea", "Gitea", true),
    /** Gogs（自建）。 */
    GOGS("gogs", "Gogs", true);

    private final String id;
    private final String displayName;
    /** true 表示必须填写实例地址（GitHub / Gitee 有公网默认值）。 */
    private final boolean selfHosted;

    GitHost(String id, String displayName, boolean selfHosted) {
        this.id = id;
        this.displayName = displayName;
        this.selfHosted = selfHosted;
    }

    /** @return 平台 id（连接配置里的 host 值） */
    public String id() {
        return id;
    }

    /** @return 平台显示名 */
    public String displayName() {
        return displayName;
    }

    /** @return true 表示实例地址必填（自建平台） */
    public boolean selfHosted() {
        return selfHosted;
    }

    /**
     * 按 id 解析平台（大小写、空白不敏感）。
     *
     * @param raw 原始值
     * @return 平台
     * @throws IllegalArgumentException 未知平台
     */
    public static GitHost of(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (GitHost host : values()) {
            if (host.id.equals(value) || host.name().equalsIgnoreCase(value)) {
                return host;
            }
        }
        throw new IllegalArgumentException("未知的 Git 托管平台：" + raw
                + "（可选 github / gitee / gitea / gogs）");
    }
}
