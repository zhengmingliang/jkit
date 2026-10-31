package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link GitCdn}：CDN 加速链接构造（纯字符串计算，不发请求）。
 *
 * <p>GitHub 走 jsDelivr 的 {@code /gh/} 形态（已实测可用），Gitea / Gogs 沿用各自
 * raw 路径（给自建 CDN / 反代用）；Gitee 不支持加速，一律返回 null。</p>
 *
 * @author 郑明亮
 */
public class GitCdnTest {
    private static GitSourceConfig config(String host, String baseUrl, String cdn) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(GitSourceConfig.HOST, host);
        values.put(GitSourceConfig.BASE_URL, baseUrl);
        values.put(GitSourceConfig.OWNER, "octocat");
        values.put(GitSourceConfig.REPO, "hello");
        values.put(GitSourceConfig.BRANCH, "master");
        if (cdn != null) {
            values.put(GitSourceConfig.CDN_BASE, cdn);
        }
        return GitSourceConfig.from(StorageConfig.of(values));
    }

    private static GitSourceConfig github(String cdn) {
        return config("github", "https://api.github.com", cdn);
    }

    @Test
    public void githubUsesJsdelivrGhForm() {
        // 实测可用：/gh/{owner}/{repo}@{branch}/{path}
        String url = GitCdn.linkOf(github("https://cdn.jsdelivr.net"), "docs/a.txt");
        assertEquals("https://cdn.jsdelivr.net/gh/octocat/hello@master/docs/a.txt", url);
    }

    @Test
    public void trailingSlashOnCdnBaseIsTrimmed() {
        String url = GitCdn.linkOf(github("https://cdn.jsdelivr.net/"), "a.txt");
        assertEquals("https://cdn.jsdelivr.net/gh/octocat/hello@master/a.txt", url);
    }

    @Test
    public void multipleTrailingSlashesTrimmed() {
        String url = GitCdn.linkOf(github("https://cdn.jsdelivr.net///"), "a.txt");
        assertEquals("https://cdn.jsdelivr.net/gh/octocat/hello@master/a.txt", url);
    }

    @Test
    public void chinesePathIsPercentEncoded() {
        // 中文文件名必须编码：实测 jsDelivr 接受百分号编码路径
        String url = GitCdn.linkOf(github("https://cdn.jsdelivr.net"), "20211012/下降.png");
        assertTrue(url.startsWith("https://cdn.jsdelivr.net/gh/octocat/hello@master/20211012/"));
        assertFalse("链接里不应残留中文", url.contains("下降"));
        assertTrue(url.contains("%"));
    }

    @Test
    public void spaceInPathBecomesPercentTwenty() {
        String url = GitCdn.linkOf(github("https://cdn.jsdelivr.net"), "a b.txt");
        assertTrue(url.endsWith("/a%20b.txt"));
        assertFalse(url.contains(" "));
    }

    @Test
    public void giteaAndGogsUseRawStylePath() {
        // 自建实例前面挂 CDN / 反代：路径与各自 raw 一致
        assertEquals("https://cdn.example.com/octocat/hello/raw/master/a.txt",
                GitCdn.linkOf(config("gitea", "https://git.example.com",
                        "https://cdn.example.com"), "a.txt"));
        assertEquals("https://cdn.example.com/octocat/hello/raw/master/a.txt",
                GitCdn.linkOf(config("gogs", "https://gogs.example.com",
                        "https://cdn.example.com"), "a.txt"));
    }

    @Test
    public void giteeNeverGetsCdnLink() {
        // Gitee 没有可用加速站点：即使配了域名也不给链接，界面上也不显示这一项
        assertNull(GitCdn.linkOf(config("gitee", "https://gitee.com",
                "https://cdn.example.com"), "a.txt"));
        assertFalse(GitStorageSupport.supportsCdn(GitStorageSupport.GITEE));
    }

    @Test
    public void supportsCdnCoversGithubGiteaGogsOnly() {
        assertTrue(GitStorageSupport.supportsCdn(GitStorageSupport.GITHUB));
        assertTrue(GitStorageSupport.supportsCdn(GitStorageSupport.GITEA));
        assertTrue(GitStorageSupport.supportsCdn(GitStorageSupport.GOGS));
        assertFalse(GitStorageSupport.supportsCdn(GitStorageSupport.GITEE));
        assertFalse("非 Git 平台不支持", GitStorageSupport.supportsCdn("svn"));
    }

    @Test
    public void noCdnBaseMeansNoLink() {
        assertNull(GitCdn.linkOf(github(null), "a.txt"));
        assertNull(GitCdn.linkOf(github(""), "a.txt"));
        assertNull(GitCdn.linkOf(github("   "), "a.txt"));
    }

    @Test
    public void emptyPathMeansNoLink() {
        // 根目录没有对应的文件，不能拼出只有目录的链接
        assertNull(GitCdn.linkOf(github("https://cdn.jsdelivr.net"), ""));
        assertNull(GitCdn.linkOf(github("https://cdn.jsdelivr.net"), null));
    }

    @Test
    public void rootPathIsFoldedIntoCdnLink() {
        GitSourceConfig config = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.BASE_URL, "https://api.github.com",
                GitSourceConfig.OWNER, "octocat",
                GitSourceConfig.REPO, "hello",
                GitSourceConfig.BRANCH, "master",
                GitSourceConfig.ROOT_PATH, "docs",
                GitSourceConfig.CDN_BASE, "https://cdn.jsdelivr.net")));
        // 逻辑路径 a.txt → 仓库内 docs/a.txt
        assertEquals("https://cdn.jsdelivr.net/gh/octocat/hello@master/docs/a.txt",
                GitCdn.linkOf(config, config.repoPath("a.txt")));
    }

    @Test
    public void garbageCdnBaseIsTreatedAsDisabled() {
        // 历史脏数据：下拉的「显示名」被当成值存了进来（实测出现过的 bug）。
        // 这类值一律按「未启用」处理，绝不拼出形如「jsDelivr（cdn.jsdelivr.net）/gh/...」的坏链接
        String[] garbage = {
                "jsDelivr（cdn.jsdelivr.net）",
                "jsDelivr · Fastly（fastly.jsdelivr.net）",
                "GitHub raw（不加速，直连）",
                "不启用（留空）",
                "__custom__",
                "cdn.jsdelivr.net",
        };
        for (String bad : garbage) {
            GitSourceConfig config = github(bad);
            assertFalse("脏值 " + bad + " 不应被视为启用 CDN", config.cdnEnabled());
            assertNull("脏值 " + bad + " 不应拼出链接", GitCdn.linkOf(config, "a.txt"));
        }
    }

    @Test
    public void validCdnBaseStillWorks() {
        assertEquals("https://cdn.jsdelivr.net/gh/octocat/hello@master/a.txt",
                GitCdn.linkOf(github("https://cdn.jsdelivr.net"), "a.txt"));
        assertEquals("http://cdn.example.com/gh/octocat/hello@master/a.txt",
                GitCdn.linkOf(github("http://cdn.example.com"), "a.txt"));
    }

    @Test
    public void cdnBaseExposedAndEnabledFlag() {
        GitSourceConfig enabled = github("https://cdn.jsdelivr.net");
        assertTrue(enabled.cdnEnabled());
        assertEquals("https://cdn.jsdelivr.net", enabled.cdnBase());
        GitSourceConfig disabled = github(null);
        assertFalse(disabled.cdnEnabled());
        assertEquals("", disabled.cdnBase());
    }

    @Test
    public void onlySupportedPlatformsDeclareCdnField() {
        GitStorageSupport.register();
        for (String id : GitStorageSupport.platformIds()) {
            com.alianga.jkit.storage.StoragePlatform platform =
                    com.alianga.jkit.storage.FileStorages.platform(id);
            boolean declared = platform.fields().stream()
                    .anyMatch(f -> GitSourceConfig.CDN_BASE.equals(f.key()));
            assertEquals("平台 " + id + " 的 CDN 字段声明应与 supportsCdn 一致",
                    GitStorageSupport.supportsCdn(id), declared);
        }
    }
}
