package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageException;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link GitSourceConfig} 解析与校验：默认值、必填项、路径叠加、令牌不外泄。
 *
 * @author 郑明亮
 */
public class GitSourceConfigTest {
    private static StorageConfig github() {
        return StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.OWNER, "octocat",
                GitSourceConfig.REPO, "hello"));
    }

    @Test
    public void githubDefaultsToPublicApi() {
        GitSourceConfig config = GitSourceConfig.from(github());
        assertEquals(GitHost.GITHUB, config.host());
        assertEquals("https://api.github.com", config.baseUrl());
        assertEquals("main", config.branch());
        assertEquals(GitSourceConfig.MODE_AUTO, config.commitMode());
        assertFalse(config.manualCommit());
        assertEquals(GitSourceConfig.DEFAULT_TEMPLATE, config.commitTemplate());
    }

    @Test
    public void giteeDefaultsToPublicApi() {
        GitSourceConfig config = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "gitee",
                GitSourceConfig.OWNER, "oschina",
                GitSourceConfig.REPO, "demo")));
        assertEquals(GitHost.GITEE, config.host());
        assertEquals("https://gitee.com", config.baseUrl());
    }

    @Test
    public void selfHostedRequiresBaseUrl() {
        StorageException error = assertThrows(StorageException.class, () ->
                GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                        GitSourceConfig.HOST, "gitea",
                        GitSourceConfig.OWNER, "o",
                        GitSourceConfig.REPO, "r"))));
        assertEquals(com.alianga.jkit.storage.StorageErrorCode.INVALID_CONFIG, error.code());
        assertTrue(error.getMessage().contains("baseUrl"));
    }

    @Test
    public void trailingSlashIsTrimmed() {
        GitSourceConfig config = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "gitea",
                GitSourceConfig.BASE_URL, "https://git.example.com//",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r")));
        assertEquals("https://git.example.com", config.baseUrl());
    }

    @Test
    public void baseUrlMustBeHttp() {
        assertThrows(StorageException.class, () -> GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "gogs",
                GitSourceConfig.BASE_URL, "git.example.com",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r"))));
    }

    @Test
    public void ownerAndRepoAreRequired() {
        assertThrows(StorageException.class, () -> GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.REPO, "r"))));
        assertThrows(StorageException.class, () -> GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.OWNER, "o"))));
    }

    @Test
    public void unknownHostIsRejected() {
        assertThrows(StorageException.class, () -> GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "gitlab",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r"))));
    }

    @Test
    public void rootPathIsPrependedToLogicalPath() {
        GitSourceConfig config = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r",
                GitSourceConfig.ROOT_PATH, "/docs/guide/")));
        assertEquals("docs/guide", config.repoPath(""));
        assertEquals("docs/guide/a.txt", config.repoPath("a.txt"));
        assertEquals("docs/guide/sub/b.txt", config.repoPath("/sub/b.txt"));
    }

    @Test
    public void withoutRootPathRepoPathEqualsLogical() {
        GitSourceConfig config = GitSourceConfig.from(github());
        assertEquals("", config.repoPath(""));
        assertEquals("a/b.txt", config.repoPath("a/b.txt"));
        // 路径上跳被规范化拦住，不会逃出存储根
        assertEquals("a.txt", config.repoPath("../a.txt"));
    }

    @Test
    public void manualModeDetected() {
        GitSourceConfig config = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r",
                GitSourceConfig.COMMIT_MODE, "manual")));
        assertTrue(config.manualCommit());
    }

    @Test
    public void tokenIsReadButNeverPrinted() {
        GitSourceConfig config = GitSourceConfig.from(StorageConfig.of(TestMaps.of(
                GitSourceConfig.HOST, "github",
                GitSourceConfig.OWNER, "o",
                GitSourceConfig.REPO, "r",
                GitSourceConfig.TOKEN, "ghp_supersecret")));
        assertTrue(config.authenticated());
        assertEquals("ghp_supersecret", config.token());
        String text = config.toString();
        assertFalse("诊断文本不能包含令牌", text.contains("supersecret"));
        assertNotNull(text);
    }

    @Test
    public void missingTokenMeansAnonymous() {
        GitSourceConfig config = GitSourceConfig.from(github());
        assertFalse(config.authenticated());
        assertEquals("", config.token());
    }
}
