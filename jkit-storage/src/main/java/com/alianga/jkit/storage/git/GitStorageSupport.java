package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git 托管平台声明与注册：把 {@link GitStorageProvider} 以四个平台接入
 * {@code FileStorages}（github / gitee / gitea / gogs 各一张平台卡片，
 * 共用 {@code git} 引擎），连接管理界面的平台卡片、表单字段、测试连接随即自动可用。
 *
 * <p>四个平台的配置字段完全一致，差异只在默认值：GitHub / Gitee 预填公网
 * API 地址，Gitea / Gogs 的实例地址必填。注册幂等，可在界面初始化与测试启动时
 * 重复调用。</p>
 *
 * @author 郑明亮
 */
public final class GitStorageSupport {
    /** 引擎 id（四个平台共用，与 {@link GitStorageProvider#platform()} 一致）。 */
    public static final String ENGINE_ID = "git";

    /** 四个平台 id。 */
    public static final String GITHUB = "git-github";
    public static final String GITEE = "git-gitee";
    public static final String GITEA = "git-gitea";
    public static final String GOGS = "git-gogs";

    /** 平台分类：与 SVN 同属「协议」（界面分组用，不新增分类以免改动分组顺序与译文）。 */
    private static final String CATEGORY = "协议";

    /** 条目属性：最后修改人（Git 提交作者；通用键，详情面板按属主显示）。 */
    public static final String ATTR_OWNER = "owner";

    private static volatile boolean registered;

    private GitStorageSupport() {
    }

    /** 注册四个 Git 平台与共用引擎（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        FileStorages.register(platform(GITHUB, "GitHub", GitHost.GITHUB, "https://api.github.com"));
        FileStorages.register(platform(GITEE, "Gitee 码云", GitHost.GITEE, "https://gitee.com"));
        FileStorages.register(platform(GITEA, "Gitea（自建）", GitHost.GITEA, ""));
        FileStorages.register(platform(GOGS, "Gogs（自建）", GitHost.GOGS, ""));
        FileStorages.registerEngine(ENGINE_ID, new java.util.function.Function<StorageConfig, FileStorage>() {
            @Override
            public FileStorage apply(StorageConfig config) {
                return new GitStorageProvider(config);
            }
        });
        registered = true;
    }

    /** @return 四个平台的 id */
    public static List<String> platformIds() {
        return Collections.unmodifiableList(Arrays.asList(GITHUB, GITEE, GITEA, GOGS));
    }

    /**
     * @param platformId 平台 id
     * @return true 表示是本类注册的 Git 平台
     */
    public static boolean isGit(String platformId) {
        return GITHUB.equals(platformId) || GITEE.equals(platformId)
                || GITEA.equals(platformId) || GOGS.equals(platformId);
    }

    /**
     * 该平台是否支持配置 CDN 加速。
     *
     * <p>Gitee 返回 false：其生态没有可用的公共加速站点，给了配置项只会是「配了也不生效」，
     * 界面上不显示这一项（配置字段与「复制 CDN 加速链接」都不出现）。</p>
     *
     * @param platformId 平台 id
     * @return true 表示支持
     */
    public static boolean supportsCdn(String platformId) {
        return GITHUB.equals(platformId) || GITEA.equals(platformId) || GOGS.equals(platformId);
    }

    /**
     * @param host 平台
     * @return true 表示该平台支持 CDN 加速
     */
    public static boolean supportsCdn(GitHost host) {
        return host == GitHost.GITHUB || host == GitHost.GITEA || host == GitHost.GOGS;
    }

    /**
     * 连接的提交信息是否为「手动填写」模式（界面层据此决定要不要先弹窗）。
     *
     * <p>只按连接配置判断，不合并平台默认值：没显式配 {@code manual} 就是自动模式，
     * 与引擎侧 {@link GitSourceConfig#manualCommit()} 的判定保持一致。</p>
     *
     * @param config 连接配置键值对
     * @return true 表示手动模式
     */
    public static boolean manualCommit(Map<String, String> config) {
        String mode = config == null ? null : config.get(GitSourceConfig.COMMIT_MODE);
        return GitSourceConfig.MODE_MANUAL.equalsIgnoreCase(mode == null ? null : mode.trim());
    }

    private static StoragePlatform platform(String id, String displayName, GitHost host,
                                            String defaultBaseUrl) {
        // 平台类型写死进平台默认值：用户不填也不会落到别的平台
        Map<String, String> defaults = new LinkedHashMap<String, String>();
        defaults.put(GitSourceConfig.HOST, host.id());
        defaults.put(GitSourceConfig.BASE_URL, defaultBaseUrl);
        defaults.put(GitSourceConfig.BRANCH, "main");
        defaults.put(GitSourceConfig.COMMIT_MODE, GitSourceConfig.MODE_AUTO);
        defaults.put(GitSourceConfig.COMMIT_TEMPLATE, GitSourceConfig.DEFAULT_TEMPLATE);
        // 自建平台实例地址必填；公网平台有默认值，仍允许用户改（GitHub Enterprise）
        return new StoragePlatform(id, displayName, ENGINE_ID, CATEGORY, fields(host),
                StorageConfig.of(defaults));
    }

    private static List<StorageField> fields(GitHost host) {
        String baseUrlHint = host.selfHosted()
                ? "自建实例地址，如 https://git.example.com（不要带 /api/v1，程序会自己拼）"
                : "API 地址；GitHub Enterprise 填 https://git.example.com/api/v3";
        // 表单初值取 field.defaultValue()（不是平台默认值），公网平台在这里预填，
        // 省得用户手敲；自建留空，由引擎校验提示必填
        String baseUrlDefault;
        if (host == GitHost.GITHUB) {
            baseUrlDefault = "https://api.github.com";
        } else if (host == GitHost.GITEE) {
            baseUrlDefault = "https://gitee.com";
        } else {
            baseUrlDefault = "";
        }
        List<StorageField> fields = new ArrayList<StorageField>();
        fields.add(StorageField.optional(GitSourceConfig.BASE_URL, "实例地址", baseUrlDefault,
                baseUrlHint));
        fields.add(StorageField.required(GitSourceConfig.OWNER, "仓库属主",
                "如 octocat；个人仓库填用户名，组织仓库填组织名"));
        fields.add(StorageField.required(GitSourceConfig.REPO, "仓库名", "如 hello-world"));
        fields.add(StorageField.optional(GitSourceConfig.BRANCH, "分支", "main",
                "默认 main；老仓库常用 master"));
        fields.add(StorageField.secret(GitSourceConfig.TOKEN, "访问令牌", false,
                tokenHint(host)));
        fields.add(StorageField.optional(GitSourceConfig.ROOT_PATH, "起始目录", "",
                "仓库内子目录，如 docs；留空为仓库根"));
        fields.add(StorageField.optional(GitSourceConfig.COMMIT_MODE, "提交信息",
                GitSourceConfig.MODE_AUTO,
                "auto = 按模板自动生成；manual = 每次上传 / 删除前弹窗填写"));
        fields.add(StorageField.optional(GitSourceConfig.COMMIT_TEMPLATE, "提交信息模板",
                GitSourceConfig.DEFAULT_TEMPLATE,
                "占位符：{filename} {path} {time} {op} {branch}"));
        // CDN 加速：Gitee 没有可用加速站点，不给这一项（配了也不生效）
        if (supportsCdn(host)) {
            fields.add(StorageField.optional(GitSourceConfig.CDN_BASE, "CDN 加速域名",
                    cdnDefault(host), cdnHint(host)));
        }
        return Collections.unmodifiableList(fields);
    }

    private static String cdnDefault(GitHost host) {
        // jsDelivr 只加速 GitHub 公开仓库，GitHub 平台预填；自建两家留空由用户填自己的域名
        return host == GitHost.GITHUB ? GitSourceConfig.CDN_JSDELIVR : "";
    }

    private static String cdnHint(GitHost host) {
        if (host == GitHost.GITHUB) {
            return "留空则不启用；默认 jsDelivr（加速 GitHub 公开仓库），也可填其它加速站，"
                    + "如 https://fastly.jsdelivr.net、https://raw.githubusercontent.com";
        }
        return "自建实例前面挂了 CDN / 反代时填其域名，如 https://cdn.example.com；留空不启用";
    }

    private static String tokenHint(GitHost host) {
        switch (host) {
            case GITHUB:
                return "Settings → Developer settings → Personal access tokens；私有仓库需 repo，"
                        + "公开仓库只读可留空";
            case GITEE:
                return "设置 → 私人令牌；读写需勾选 projects";
            case GITEA:
                return "设置 → 应用 → 生成令牌；需仓库读写权限";
            case GOGS:
                return "设置 → 授权应用 → 生成令牌；需仓库读写权限";
            default:
                return "";
        }
    }
}
