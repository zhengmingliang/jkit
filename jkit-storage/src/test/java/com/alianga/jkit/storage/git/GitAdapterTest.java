package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.git.adapter.GiteaAdapter;
import com.alianga.jkit.storage.git.adapter.GiteeAdapter;
import com.alianga.jkit.storage.git.adapter.GithubAdapter;
import com.alianga.jkit.storage.git.adapter.GitHostAdapter;
import com.alianga.jkit.storage.git.adapter.GogsAdapter;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 四家平台适配器的差异点：鉴权头、API 路径、作者字段、分页参数、base64 解码。
 *
 * <p>这些是「平台差异只放 Adapter」这条约束的守护测试：新增平台时改这里，
 * 上层 {@code GitStorageProvider} 不需要动。</p>
 *
 * @author 郑明亮
 */
public class GitAdapterTest {
    private static GitSourceConfig config(String host, String baseUrl) {
        return GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, host,
                GitSourceConfig.BASE_URL, baseUrl,
                GitSourceConfig.OWNER, "octocat",
                GitSourceConfig.REPO, "hello",
                GitSourceConfig.BRANCH, "main",
                GitSourceConfig.TOKEN, "tok-123")));
    }

    // ---------------------------------------------------------------- 鉴权

    @Test
    public void githubUsesBearer() {
        GithubAdapter adapter = new GithubAdapter();
        assertEquals("Bearer tok-123",
                adapter.authHeaders(config("github", "https://api.github.com")).get("Authorization"));
    }

    @Test
    public void giteaAndGogsUseTokenScheme() {
        assertEquals("token tok-123", new GiteaAdapter()
                .authHeaders(config("gitea", "https://git.example.com")).get("Authorization"));
        assertEquals("token tok-123", new GogsAdapter()
                .authHeaders(config("gogs", "https://gogs.example.com")).get("Authorization"));
    }

    @Test
    public void giteeUsesQueryParamInsteadOfHeader() {
        GiteeAdapter adapter = new GiteeAdapter();
        GitSourceConfig cfg = config("gitee", "https://gitee.com");
        assertTrue(adapter.authHeaders(cfg).isEmpty());
        assertEquals("tok-123", adapter.authParams(cfg).get("access_token"));
    }

    @Test
    public void anonymousSendsNoCredential() {
        GitSourceConfig anonymous = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.BASE_URL, "https://api.github.com",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r")));
        assertTrue(new GithubAdapter().authHeaders(anonymous).isEmpty());
        assertTrue(new GiteeAdapter().authParams(anonymous).isEmpty());
    }

    // ---------------------------------------------------------------- 路径

    @Test
    public void apiPathsDifferPerPlatform() {
        assertEquals("https://api.github.com/repos/octocat/hello/contents",
                new GithubAdapter().contentsUrl(config("github", "https://api.github.com")));
        assertEquals("https://gitee.com/api/v5/repos/octocat/hello/contents",
                new GiteeAdapter().contentsUrl(config("gitee", "https://gitee.com")));
        assertEquals("https://git.example.com/api/v1/repos/octocat/hello/contents",
                new GiteaAdapter().contentsUrl(config("gitea", "https://git.example.com")));
        assertEquals("https://gogs.example.com/api/v1/repos/octocat/hello/commits",
                new GogsAdapter().commitsUrl(config("gogs", "https://gogs.example.com")));
    }

    @Test
    public void pathSegmentsArePercentEncoded() {
        GithubAdapter adapter = new GithubAdapter();
        assertEquals("/docs/%E6%8C%87%E5%8D%97/a%20b.txt", adapter.encodePath("docs/指南/a b.txt"));
        assertEquals("/a/b", adapter.encodePath("a/b"));
        assertEquals("", adapter.encodePath(""));
    }

    @Test
    public void pageSizeParamDiffers() {
        assertEquals("per_page", new GithubAdapter().pageSizeParam());
        assertEquals("per_page", new GiteeAdapter().pageSizeParam());
        assertEquals("limit", new GiteaAdapter().pageSizeParam());
        assertEquals("limit", new GogsAdapter().pageSizeParam());
    }

    // ---------------------------------------------------------------- 条目解析

    @Test
    public void directoryListingParsed() {
        String body = "[{\"name\":\"docs\",\"path\":\"docs\",\"type\":\"dir\",\"size\":0,\"sha\":\"t1\"},"
                + "{\"name\":\"a.txt\",\"path\":\"a.txt\",\"type\":\"file\",\"size\":12,\"sha\":\"b1\","
                + "\"download_url\":\"https://raw/a.txt\"}]";
        List<GitEntry> entries = new GithubAdapter().parseEntries(body, "", false);
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).isDirectory());
        assertEquals("docs", entries.get(0).name());
        assertEquals(12L, entries.get(1).size());
        assertEquals("b1", entries.get(1).sha());
        assertEquals("https://raw/a.txt", entries.get(1).downloadUrl());
    }

    @Test
    public void singleFileParsed() {
        String body = "{\"name\":\"a.txt\",\"path\":\"a.txt\",\"type\":\"file\",\"size\":5,\"sha\":\"b1\","
                + "\"encoding\":\"base64\",\"content\":\"aGVsbG8=\"}";
        List<GitEntry> entries = new GithubAdapter().parseEntries(body, "a.txt", true);
        assertEquals(1, entries.size());
        assertNull(entries.get(0).downloadUrl());
    }

    @Test
    public void base64ContentDecoded() {
        String body = "{\"encoding\":\"base64\",\"content\":\"aGVsbG8=\"}";
        assertEquals("hello", new String(new GithubAdapter().parseContent(body, "a.txt")));
    }

    @Test
    public void base64WithNewlinesDecoded() {
        // 平台把 base64 折行返回，MIMEDecoder 之外必须先剥空白
        String body = "{\"encoding\":\"base64\",\"content\":\"aGVs\\nbG8=\"}";
        assertEquals("hello", new String(new GithubAdapter().parseContent(body, "a.txt")));
    }

    @Test
    public void missingContentMeansNoInlineBytes() {
        assertNull(new GithubAdapter().parseContent("{\"name\":\"big.bin\"}", "big.bin"));
    }

    @Test
    public void giteeEmptyArrayTreatedAsNoData() {
        // Gitee 在文件不存在时返回 HTTP 200 + []（不是 404）。
        // 真机同步时发现：不认这个形态会把「文件不存在」误判成「存在但没 sha」，
        // 随后 PUT 不带 sha 去更新，平台报「文件已存在」。
        for (GitHostAdapter adapter : java.util.Arrays.<GitHostAdapter>asList(new GiteeAdapter(),
                new GithubAdapter(), new GiteaAdapter(), new GogsAdapter())) {
            assertTrue("[] 必须按「无数据」处理：" + adapter.getClass().getSimpleName(),
                    adapter.parseContentObject(Json.object("[]", "a.txt"), "a.txt") == null);
        }
        assertTrue(Json.object("[]", "a.txt").isEmpty());
    }

    @Test
    public void nonEmptyArrayStillRejectedAsObject() {
        // 只有空数组算「无数据」；非空数组仍是形态错误，必须报出来
        assertThrows(com.alianga.jkit.storage.StorageException.class,
                () -> Json.object("[{\"a\":1}]", "a.txt"));
    }

    // ---------------------------------------------------------------- 提交解析

    @Test
    public void githubCommitAuthorFromNestedCommit() {
        String body = "[{\"sha\":\"abcdef1234567890\",\"commit\":{\"author\":{\"name\":\"alice\","
                + "\"date\":\"2024-05-01T10:00:00Z\"},\"message\":\"init\\n\\nbody\"}}]";
        List<GitCommit> commits = new GithubAdapter().parseCommits(body, "");
        assertEquals(1, commits.size());
        assertEquals("abcdef1", commits.get(0).shortSha());
        assertEquals("alice", commits.get(0).author());
        assertEquals("init", commits.get(0).message());
        assertEquals(java.time.OffsetDateTime.parse("2024-05-01T10:00:00Z")
                .toInstant().toEpochMilli(), commits.get(0).time());
    }

    @Test
    public void cdnPathPerPlatform() {
        // GitHub 走 jsDelivr 的 /gh/ 形态（分支用 @ 连接）；自建两家沿用 raw 形态；
        // Gitee 不支持加速，返回 null
        GitSourceConfig gh = config("github", "https://api.github.com");
        // config() 助手的分支是 main
        assertEquals("/gh/octocat/hello@main/docs/a.txt", new GithubAdapter().cdnPath(gh, "docs/a.txt"));
        GitSourceConfig gitea = config("gitea", "https://git.example.com");
        assertEquals("/octocat/hello/raw/main/a.txt", new GiteaAdapter().cdnPath(gitea, "a.txt"));
        GitSourceConfig gogs = config("gogs", "https://gogs.example.com");
        assertEquals("/octocat/hello/raw/main/a.txt", new GogsAdapter().cdnPath(gogs, "a.txt"));
        assertNull("Gitee 不应给出 CDN 路径",
                new GiteeAdapter().cdnPath(config("gitee", "https://gitee.com"), "a.txt"));
    }

    @Test
    public void cdnPathEncodesChineseAndSpaces() {
        GitSourceConfig gh = config("github", "https://api.github.com");
        String path = new GithubAdapter().cdnPath(gh, "20211012/下降 a.png");
        assertTrue(path.contains("%"));
        assertFalse("路径里不应残留未编码的中文或空格", path.contains("下降") || path.contains(" "));
    }

    @Test
    public void createMethodDistinguishesGitee() {
        // Gitee：新建 POST / 更新 PUT；其余三家一律 PUT。
        // 真机同步时发现：Gitee 用 PUT 新建会报 400 "sha is missing"。
        assertEquals("POST", new GiteeAdapter().createMethod(false));
        assertEquals("PUT", new GiteeAdapter().createMethod(true));
        assertEquals("PUT", new GithubAdapter().createMethod(false));
        assertEquals("PUT", new GithubAdapter().createMethod(true));
        assertEquals("PUT", new GiteaAdapter().createMethod(false));
        assertEquals("PUT", new GogsAdapter().createMethod(false));
    }

    @Test
    public void giteaCommitAuthorFromUsername() {
        String body = "[{\"sha\":\"1234567890abcdef\",\"created\":\"2024-05-02T08:30:00+08:00\","
                + "\"author\":{\"username\":\"bob\",\"full_name\":\"Bob Lee\"},"
                + "\"commit\":{\"author\":{\"name\":\"ignored-by-gitea-adapter\","
                + "\"date\":\"2024-05-02T08:30:00Z\"}},\"message\":\"docs: update\"}]";
        List<GitCommit> commits = new GiteaAdapter().parseCommits(body, "");
        assertEquals("bob", commits.get(0).author());
        assertEquals("docs: update", commits.get(0).message());
        assertEquals("1234567", commits.get(0).shortSha());
    }

    @Test
    public void gogsCommitAuthorFromUsername() {
        String body = "[{\"sha\":\"9999999999abcdef\",\"author\":{\"username\":\"carol\"},"
                + "\"commit\":{\"author\":{\"name\":\"Carol\",\"date\":\"2024-06-01T12:00:00Z\"}},"
                + "\"message\":\"fix typo\"}]";
        assertEquals("carol", new GogsAdapter().parseCommits(body, "").get(0).author());
    }

    @Test
    public void missingAuthorYieldsNullForUnknownDisplay() {
        String body = "[{\"sha\":\"abc1234567\",\"commit\":{\"message\":\"no author\"}}]";
        GitCommit commit = new GithubAdapter().parseCommits(body, "").get(0);
        assertNull(commit.author());
        assertNull("界面据此显示「未知」", commit.authorOrUnknown());
    }

    @Test
    public void multiLineMessageKeepsFirstLineOnly() {
        String body = "[{\"sha\":\"abc1234567\",\"commit\":{\"message\":\"subject\\n\\nlong body\"}}]";
        assertEquals("subject", new GithubAdapter().parseCommits(body, "").get(0).message());
    }

    @Test
    public void unknownTimeFormatBecomesUnknownMarker() {
        String body = "[{\"sha\":\"abc1234567\",\"commit\":{\"author\":{\"date\":\"not-a-date\"}}}]";
        assertEquals(GitCommit.TIME_UNKNOWN,
                new GithubAdapter().parseCommits(body, "").get(0).time());
    }

    @Test
    public void shortShaKeepsFullValueWhenShorter() {
        assertEquals("abc", new GitCommit("abc", "a", 0L, "m").shortSha());
        assertEquals("", new GitCommit("", "a", 0L, "m").shortSha());
    }

    // ---------------------------------------------------------------- raw 地址

    @Test
    public void rawUrlsPerPlatform() {
        GithubAdapter github = new GithubAdapter();
        assertEquals("https://raw.githubusercontent.com/octocat/hello/main/docs/a.txt",
                github.rawUrl(config("github", "https://api.github.com"), "docs/a.txt"));
        // GitHub Enterprise：没有独立 raw 域名，回落到实例地址
        assertTrue(github.rawUrl(config("github", "https://git.corp.com/api/v3"), "a.txt")
                .startsWith("https://git.corp.com/api/v3/raw/"));
        assertEquals("https://gitee.com/octocat/hello/raw/main/a.txt",
                new GiteeAdapter().rawUrl(config("gitee", "https://gitee.com"), "a.txt"));
        assertTrue(new GiteaAdapter().rawUrl(config("gitea", "https://git.example.com"), "a.txt")
                .contains("/api/v1/repos/octocat/hello/raw/a.txt?ref=main"));
        // Gogs 走 API 路由：web raw 端点不认 Authorization: token 头（session
        // cookie 鉴权），私有仓库会得到登录页重定向——见 GogsAdapter.rawUrl 注释
        assertEquals("https://gogs.example.com/api/v1/repos/octocat/hello/raw/a.txt?ref=main",
                new GogsAdapter().rawUrl(config("gogs", "https://gogs.example.com"), "a.txt"));
    }

    /** rawUrl 的各段必须百分号编码：空格 / # / % / 中文裸拼会被截断或拒绝（URI.create 抛 IAE）。 */
    @Test
    public void rawUrlsEncodeSpecialCharacters() {
        // 空格 → %20（不是 +），# → %23（否则后面被当成 fragment 截断）
        assertEquals("https://raw.githubusercontent.com/octocat/hello/main/docs/a%20b%23c.txt",
                new GithubAdapter().rawUrl(config("github", "https://api.github.com"), "docs/a b#c.txt"));
        assertEquals("https://gitee.com/octocat/hello/raw/main/%E6%96%87%E4%BB%B6.txt",
                new GiteeAdapter().rawUrl(config("gitee", "https://gitee.com"), "文件.txt"));
        assertEquals("https://gogs.example.com/api/v1/repos/octocat/hello/raw/100%25.txt?ref=main",
                new GogsAdapter().rawUrl(config("gogs", "https://gogs.example.com"), "100%.txt"));
        // Gitea：路径编码 + ref 查询参数值里的分支斜杠也要编码
        assertTrue(new GiteaAdapter().rawUrl(config("gitea", "https://git.example.com"), "a b.txt")
                .contains("/raw/a%20b.txt?ref=main"));
    }

    /** 带斜杠的分支名：路径段里保留 /（Gogs/GitHub/Gitee 的 raw 形态），ref 查询值里编成 %2F。 */
    @Test
    public void rawUrlsHandleSlashedBranch() {
        GitSourceConfig slashed = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "gitea",
                GitSourceConfig.BASE_URL, "https://git.example.com",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r",
                GitSourceConfig.BRANCH, "feature/x",
                GitSourceConfig.TOKEN, "t")));
        assertTrue("Gogs 与 Gitea 同构：分支走 ref 查询参数",
                new GogsAdapter().rawUrl(slashed, "a.txt").contains("?ref=feature%2Fx"));
        assertTrue("查询参数形态的分支斜杠编码为 %2F",
                new GiteaAdapter().rawUrl(slashed, "a.txt").endsWith("?ref=feature%2Fx"));
    }
}
