package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageException;

/**
 * GitHub Gist 平台的配置常量与校验。
 *
 * <p>一条连接对应一个 Gist（与「一个连接 = 一个 bucket / 仓库」的既有约定一致）：
 * 用户先在 gist.github.com 建好一个 Gist，把地址里的 ID 填进 {@link #GIST_ID}，
 * 之后的浏览 / 编辑 / 上传 / 删除都落在这个 Gist 的 {@code files} 映射上。</p>
 *
 * <p>Gist 是<b>扁平的文本片段</b>存储：文件名不能含 {@code /}，没有真正的目录，
 * 单个文件经 API 读写上限约 1 MB（更大的文件响应体会 {@code truncated}，需走
 * {@code raw_url}）。因此本引擎用可逆的扁平文件名编码模拟目录树，并把单文件上传
 * 上限如实声明为 {@link #MAX_UPLOAD_BYTES}。</p>
 *
 * @author 郑明亮
 */
public final class GistConfig {
    /** 平台 id 与引擎 id。 */
    public static final String PLATFORM_ID = "github-gist";
    /** 平台显示名。 */
    public static final String DISPLAY_NAME = "GitHub Gist";
    /** 平台分类（界面分组用）。 */
    public static final String CATEGORY = "公有云";

    /** 访问令牌（经典 PAT 需勾选 {@code gist} 范围）。 */
    public static final String TOKEN = "token";
    /** Gist ID（gist.github.com 地址最后一段）。 */
    public static final String GIST_ID = "gistId";
    /** API 基础地址（GitHub Enterprise 覆盖为 {@code https://host/api/v3}）。 */
    public static final String API_BASE = "apiBase";

    /** 公网 API 默认地址。 */
    public static final String DEFAULT_API_BASE = "https://api.github.com";
    /** GitHub REST 建议的 Accept 头。 */
    public static final String ACCEPT = "application/vnd.github+json";
    /** GitHub REST API 版本头。 */
    public static final String API_VERSION = "2022-11-28";
    /** GitHub 要求所有请求带 User-Agent。 */
    public static final String USER_AGENT = "jkit-storage";

    /**
     * 单文件上传上限（字节）：GitHub Gist 经 REST 内联读写的内容上限为 1 MB，
     * 超过后响应体会被截断。刻意取保守值，避免把大文件转成 JSON 字符串撑爆堆，
     * 也让 {@code FileStorages.capabilitiesOf(...).maxUploadBytes()} 如实可查。
     */
    public static final long MAX_UPLOAD_BYTES = 1024L * 1024L;

    private GistConfig() {
    }

    /**
     * 校验连接配置：令牌与 Gist ID 必填。
     *
     * @param config 连接配置
     * @throws StorageException 配置不合法时抛 INVALID_CONFIG
     */
    public static void validate(StorageConfig config) {
        if (config == null) {
            throw StorageException.invalidConfig("GitHub Gist 配置不能为空");
        }
        config.require(TOKEN);
        config.require(GIST_ID);
    }

    /**
     * 归一化 API 基础地址：去尾斜杠，空值回落公网默认地址。
     *
     * @param raw 原始值
     * @return 归一化后的地址
     */
    public static String apiBase(String raw) {
        String value = raw == null || raw.trim().isEmpty() ? DEFAULT_API_BASE : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 从用户粘贴的内容里提取 Gist ID：允许直接粘整条 gist.github.com 地址，
     * 自动取最后一段路径并去掉查询串 / 锚点，避免「把 URL 当 ID」这类常见填错。
     *
     * @param raw 原始值（ID 或完整地址）
     * @return Gist ID
     * @throws StorageException 提取结果为空或含非法字符时抛 INVALID_CONFIG
     */
    public static String normalizeGistId(String raw) {
        String value = raw == null ? "" : raw.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        if (value.isEmpty()) {
            throw StorageException.invalidConfig("缺少 Gist ID：请填 gist.github.com 地址最后一段，"
                    + "如 https://gist.github.com/octocat/6cad326836d38bd3a7ae 里的 6cad326836d38bd3a7ae");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!valid) {
                throw StorageException.invalidConfig("Gist ID 含非法字符「" + c + "」：应为字母数字"
                        + "组成的 ID（可直接粘贴完整 gist.github.com 地址）");
            }
        }
        return value;
    }
}
