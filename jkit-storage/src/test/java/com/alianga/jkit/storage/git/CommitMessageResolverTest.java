package com.alianga.jkit.storage.git;

import org.junit.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link CommitMessageResolver} 与 {@link GitErrors} 的纯逻辑测试：
 * 模板占位符渲染、手动模式取消语义、HTTP 状态码到错误码的映射。
 *
 * @author 郑明亮
 */
public class CommitMessageResolverTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 5, 1, 10, 30, 45);

    @Test
    public void defaultTemplateRendersFilenameAndTime() {
        String message = CommitMessageResolver.render(GitSourceConfig.DEFAULT_TEMPLATE,
                new CommitMessageResolver.Request("上传", "docs/a.txt", "main"), NOW);
        assertEquals("上传: a.txt @ 2024-05-01 10:30:45", message);
    }

    @Test
    public void defaultTemplateReflectsActualOperationNotUpload() {
        // 真实仓库上发现的缺陷：模板曾写死 "upload"，导致删除 / 建目录的提交也显示成
        // 「upload: xxx」，在仓库历史里误导人。默认模板必须带 {op}。
        assertTrue("默认模板必须含 {op}", GitSourceConfig.DEFAULT_TEMPLATE.contains("{op}"));
        assertEquals("删除: a.txt @ 2024-05-01 10:30:45",
                CommitMessageResolver.render(GitSourceConfig.DEFAULT_TEMPLATE,
                        new CommitMessageResolver.Request("删除", "a.txt", "main"), NOW));
        assertEquals("新建目录: .gitkeep @ 2024-05-01 10:30:45",
                CommitMessageResolver.render(GitSourceConfig.DEFAULT_TEMPLATE,
                        new CommitMessageResolver.Request("新建目录", ".gitkeep", "main"), NOW));
    }

    @Test
    public void allPlaceholdersRender() {
        String message = CommitMessageResolver.render("{op} {path} {filename} {time} {branch}",
                new CommitMessageResolver.Request("删除", "docs/sub/a.txt", "develop"), NOW);
        assertEquals("删除 docs/sub/a.txt a.txt 2024-05-01 10:30:45 develop", message);
    }

    @Test
    public void unknownPlaceholderKeptVerbatim() {
        String message = CommitMessageResolver.render("fix: {filename} {bogus}",
                new CommitMessageResolver.Request("上传", "a.txt", "main"), NOW);
        assertEquals("fix: a.txt {bogus}", message);
    }

    @Test
    public void blankTemplateFallsBackToDefault() {
        String message = CommitMessageResolver.render("   ",
                new CommitMessageResolver.Request("上传", "a.txt", "main"), NOW);
        assertEquals("上传: a.txt @ 2024-05-01 10:30:45", message);
    }

    @Test
    public void emptyRenderFallsBackToOperationName() {
        // 模板只含空占位符时不能渲染成空串：Git 不接受空提交信息
        String message = CommitMessageResolver.render("{path}",
                new CommitMessageResolver.Request("上传", "", "main"), NOW);
        assertEquals("上传", message);
    }

    @Test
    public void filenameOfRootPathIsEmpty() {
        String message = CommitMessageResolver.render("{filename}",
                new CommitMessageResolver.Request("上传", "", "main"), NOW);
        assertEquals("上传", message);
    }

    // ---------------------------------------------------------------- 错误映射

    @Test
    public void authFailuresMapped() {
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.AUTH_FAILED,
                GitErrors.http(401, "{\"message\":\"Bad credentials\"}", "a.txt").code());
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.AUTH_FAILED,
                GitErrors.http(403, "", "a.txt").code());
        assertTrue(GitErrors.http(403, "", "a.txt").getMessage().contains("权限不足"));
    }

    @Test
    public void rateLimitedMappedForBothStatuses() {
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.RATE_LIMITED,
                GitErrors.http(429, "", "a.txt").code());
        // GitHub 把次级限流也报成 403，靠响应体里的 rate limit 区分
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.RATE_LIMITED,
                GitErrors.http(403, "{\"message\":\"API rate limit exceeded\"}", "a.txt").code());
    }

    @Test
    public void notFoundAndConflictAndTooLargeMapped() {
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.NOT_FOUND,
                GitErrors.http(404, "", "a.txt").code());
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.CONFLICT,
                GitErrors.http(409, "", "a.txt").code());
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.CONFLICT,
                GitErrors.http(413, "", "big.zip").code());
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.CONFLICT,
                GitErrors.http(422, "", "a.txt").code());
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.SERVER_ERROR,
                GitErrors.http(500, "", "a.txt").code());
    }

    /**
     * GitHub 目录超 1000 项的 409 不是真的内容冲突：Contents API 单目录上限
     * 1000 项，刷新多少次都没用——文案必须单独指路，不能套「请刷新后重试」。
     */
    @Test
    public void listingTooLarge409GetsDistinctMessage() {
        String text = GitErrors.http(409,
                "{\"message\":\"This API returns blobs up to 1 MB in size. "
                        + "The API returns listings up to 1000 items\"}", "docs").getMessage();
        assertTrue("目录过大要有专门文案：" + text, text.contains("1000 项"));
        assertFalse("不能再误导用户去刷新：" + text, text.contains("请刷新后重试"));
        // 普通 409（不带关键词）仍是冲突语义
        assertTrue(GitErrors.http(409, "", "a.txt").getMessage().contains("请刷新后重试"));
    }

    @Test
    public void platformMessageIsExtractedButTruncated() {
        String body = "{\"message\":\"" + "x".repeat(400) + "\"}";
        String text = GitErrors.http(404, body, "a.txt").getMessage();
        // 404 走 notFound（不显示平台消息），这里验 401 分支的截断逻辑
        String auth = GitErrors.http(401, body, "a.txt").getMessage();
        assertTrue(auth.length() < 300);
        assertTrue(auth.endsWith("…"));
    }

    @Test
    public void htmlErrorPageNotPastIntoTip() {
        String text = GitErrors.http(502, "<html><body>Bad Gateway</body></html>", "a.txt")
                .getMessage();
        assertFalse(text.contains("<html>"));
        assertTrue(text.contains("502"));
    }

    // ---------------------------------------------------------------- 平台注册

    @Test
    public void fourPlatformsShareOneEngine() {
        GitStorageSupport.register();
        for (String id : GitStorageSupport.platformIds()) {
            com.alianga.jkit.storage.StoragePlatform platform =
                    com.alianga.jkit.storage.FileStorages.platform(id);
            assertTrue(id + " 应注册", platform != null);
            assertEquals(GitStorageSupport.ENGINE_ID, platform.engine());
        }
        assertTrue(GitStorageSupport.isGit(GitStorageSupport.GITHUB));
        assertFalse(GitStorageSupport.isGit("svn"));
    }

    @Test
    public void manualCommitDetectedFromRawConfig() {
        assertTrue(GitStorageSupport.manualCommit(TestMaps.of("commitMode", "manual")));
        assertTrue(GitStorageSupport.manualCommit(TestMaps.of("commitMode", "MANUAL")));
        assertFalse(GitStorageSupport.manualCommit(TestMaps.of("commitMode", "auto")));
        assertFalse(GitStorageSupport.manualCommit(TestMaps.of()));
        assertFalse(GitStorageSupport.manualCommit(null));
    }

    @Test
    public void tokenFieldDeclaredSecret() {
        GitStorageSupport.register();
        com.alianga.jkit.storage.StoragePlatform github =
                com.alianga.jkit.storage.FileStorages.platform(GitStorageSupport.GITHUB);
        boolean secret = github.fields().stream()
                .anyMatch(f -> "token".equals(f.key()) && f.isSecret());
        assertTrue("令牌必须按密钥字段声明（否则明文落盘）", secret);
    }

    @Test
    public void selfHostedPlatformsRequireNothingAtDeclaredLevel() {
        // 自建平台没有默认实例地址，靠引擎校验拦下；这里只验默认值确实为空
        GitStorageSupport.register();
        assertEquals("", com.alianga.jkit.storage.FileStorages.platform(GitStorageSupport.GITEA)
                .defaults().get(GitSourceConfig.BASE_URL));
        assertEquals("https://api.github.com",
                com.alianga.jkit.storage.FileStorages.platform(GitStorageSupport.GITHUB)
                        .defaults().get(GitSourceConfig.BASE_URL));
    }

    @Test
    public void missingBaseUrlRejectedAtEngineLevel() {
        assertThrows(com.alianga.jkit.storage.StorageException.class, () ->
                new GitStorageProvider(com.alianga.jkit.storage.StorageConfig.of(TestMaps.of(
                        GitSourceConfig.HOST, "gitea",
                        GitSourceConfig.OWNER, "o",
                        GitSourceConfig.REPO, "r"))));
    }
}
