package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * GitHub Gist 引擎全流程测试：本地 HTTP 桩服务覆盖平台注册、REST 请求形态、鉴权、
 * 列目录（虚拟目录树）、stat / exists、上传下载（文本 / 二进制 / 中文）、建目录、
 * 删除（含递归）、改名 / 复制（文件与目录）、截断回源、分页列举、错误码映射与能力声明。
 *
 * @author 郑明亮
 */
public class GistFileStorageTest {
    private static final String GIST_ID = "6cad326836d38bd3a7ae";

    private FakeGistServer server;
    private String apiBase;

    @Before
    public void startServer() throws Exception {
        server = new FakeGistServer();
        apiBase = server.start();
        server.seed(GIST_ID, "jkit test gist",
                "README.md", "# hello",
                "docs~guide.md", "guide content",
                "docs~api~v1.txt", "api v1",
                "src~Main.java", "class Main {}");
    }

    @After
    public void stopServer() {
        server.stop();
    }

    private FileStorage storage() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("token", "fake-token");
        values.put("gistId", GIST_ID);
        values.put("apiBase", apiBase);
        return FileStorages.of("github-gist", values);
    }

    // ---------------------------------------------------------------- 预设与配置

    @Test
    public void presetRegisteredWithHonestCapabilities() {
        StoragePlatform platform = FileStorages.platform("github-gist");
        assertNotNull("github-gist 平台应已内置注册", platform);
        assertEquals("github-gist", platform.engine());
        assertEquals("GitHub Gist", platform.displayName());
        assertEquals("公有云", platform.category());

        assertTrue("令牌应必填", findField(platform, "token").isRequired());
        assertTrue("令牌应为密钥字段", findField(platform, "token").isSecret());
        assertTrue("Gist ID 应必填", findField(platform, "gistId").isRequired());
        assertEquals("https://api.github.com",
                platform.defaults().get("apiBase"));

        assertTrue("目录为编码模拟，须如实声明",
                FileStorages.capabilitiesOf("github-gist").isFolderOpsEmulated());
        assertEquals("单文件上限应如实声明为 1MB", GistConfig.MAX_UPLOAD_BYTES,
                FileStorages.capabilitiesOf("github-gist").maxUploadBytes());
        assertFalse("Gist 不是只读引擎",
                FileStorages.capabilitiesOf("github-gist").isReadOnly());
        assertFalse("Gist 没有原生回收站",
                FileStorages.capabilitiesOf("github-gist").hasNativeRecycleBin());
    }

    @Test
    public void registrationIsIdempotent() {
        GistStorageSupport.register();
        GistStorageSupport.register();
        int cards = 0;
        for (StoragePlatform platform : FileStorages.platforms()) {
            if ("github-gist".equals(platform.id())) {
                cards++;
            }
        }
        assertEquals("重复注册不得产生重复平台卡片", 1, cards);
    }

    @Test
    public void gistIdAcceptsFullUrl() {
        assertEquals("abc123", GistConfig.normalizeGistId(
                "https://gist.github.com/octocat/abc123/"));
        assertEquals("abc123", GistConfig.normalizeGistId(
                "https://gist.github.com/octocat/abc123?file=x#y"));
        assertEquals("abc123", GistConfig.normalizeGistId("  abc123  "));
        try {
            GistConfig.normalizeGistId("bad/id/with/slash!");
            fail("含非法字符的 ID 应被拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void missingCredentialsAreRejected() {
        try {
            FileStorages.of("github-gist", StorageConfig.empty().set("gistId", GIST_ID));
            fail("缺令牌应报配置错误");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
        try {
            FileStorages.of("github-gist", StorageConfig.empty().set("token", "t"));
            fail("缺 Gist ID 应报配置错误");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    // ---------------------------------------------------------------- 请求形态

    @Test
    public void readSendsGithubRequiredHeaders() {
        storage().list("");
        assertEquals("GET", server.lastMethod);
        assertEquals("Bearer fake-token", server.lastAuthorization);
        assertEquals("application/vnd.github+json", server.lastAccept);
        assertEquals("jkit-storage", server.lastUserAgent);
        assertEquals("2022-11-28", server.lastApiVersion);
    }

    @Test
    public void writeUsesPatchWithJsonBody() {
        storage().upload("notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("PATCH", server.lastMethod);
        assertEquals("application/json", server.lastContentType);
        assertTrue("PATCH body 应含 files 映射",
                new String(server.lastBody, StandardCharsets.UTF_8).contains("\"files\""));
    }

    // ---------------------------------------------------------------- 读操作

    @Test
    public void listRootSynthesizesVirtualDirectoriesAndFiles() {
        List<StorageEntry> entries = storage().list("");
        // docs / src 为虚拟目录，README.md 为文件；占位文件不出现在列表里
        assertEquals(3, entries.size());
        assertEquals("docs", entries.get(0).path());
        assertTrue(entries.get(0).isDirectory());
        assertEquals("src", entries.get(1).path());
        assertTrue(entries.get(1).isDirectory());
        assertEquals("README.md", entries.get(2).path());
        assertFalse(entries.get(2).isDirectory());
        assertEquals("# hello".getBytes(StandardCharsets.UTF_8).length, entries.get(2).size());
    }

    @Test
    public void listNestedDirectory() {
        List<StorageEntry> entries = storage().list("docs/api");
        assertEquals(1, entries.size());
        assertEquals("docs/api/v1.txt", entries.get(0).path());
    }

    @Test
    public void statAndExists() {
        FileStorage fs = storage();
        assertTrue(fs.exists(""));
        assertTrue(fs.exists("docs"));
        assertTrue(fs.exists("docs/guide.md"));
        assertFalse(fs.exists("nope/missing.md"));
        assertEquals(13L, fs.stat("docs/guide.md").size());
        assertTrue(fs.stat("docs").isDirectory());
        assertEquals(StorageEntry.MTIME_UNKNOWN, fs.stat("docs/guide.md").lastModified());
        try {
            fs.stat("missing.txt");
            fail("不存在对象 stat 应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void downloadTextAndReadBytes() {
        FileStorage fs = storage();
        assertEquals("# hello", fs.readString("README.md"));
        assertArrayEquals("guide content".getBytes(StandardCharsets.UTF_8),
                fs.readBytes("docs/guide.md"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fs.download("README.md", out);
        assertEquals("# hello", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 写操作

    @Test
    public void uploadTextThenDownloadRoundTrip() {
        FileStorage fs = storage();
        fs.writeString("memo/今天.txt", "中文内容\nline2");
        assertEquals("中文内容\nline2", fs.readString("memo/今天.txt"));
        assertTrue("扁平文件名应保留可读中文并编码目录",
                server.files(GIST_ID).containsKey("memo~今天.txt"));
    }

    @Test
    public void binaryRoundTripIsByteExact() {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i - 128); // 含非法 UTF-8 字节
        }
        FileStorage fs = storage();
        fs.upload("bin/data.bin", binary);
        assertArrayEquals("二进制内容必须字节级往返", binary, fs.readBytes("bin/data.bin"));
        assertTrue("非 UTF-8 内容应被包装为二进制标记",
                server.files(GIST_ID).get("bin~data.bin").startsWith(GistPaths.BINARY_MARKER));
    }

    @Test
    public void emptyAndLargeUploadLimits() {
        FileStorage fs = storage();
        fs.upload("empty.txt", new byte[0]);
        assertArrayEquals(new byte[0], fs.readBytes("empty.txt"));
        byte[] tooLarge = new byte[(int) GistConfig.MAX_UPLOAD_BYTES + 1];
        try {
            fs.upload("big.bin", tooLarge);
            fail("超过 1MB 应被拒绝，不应静默截断");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
    }

    @Test
    public void mkdirCreatesHiddenPlaceholder() {
        FileStorage fs = storage();
        fs.mkdir("archive/2026");
        assertTrue(fs.exists("archive/2026"));
        assertTrue("空目录应有占位文件撑着",
                server.files(GIST_ID).containsKey("archive~2026~" + GistPaths.PLACEHOLDER_BASENAME));
        // 占位文件不出现在列表里，但目录可见
        List<StorageEntry> root = fs.list("");
        boolean archiveVisible = root.stream().anyMatch(e -> "archive".equals(e.path()) && e.isDirectory());
        assertTrue("新建目录应在根目录可见", archiveVisible);
        assertTrue("占位文件不应混进文件列表", fs.list("archive/2026").isEmpty());
    }

    @Test
    public void uploadIntoPlaceholderDirRemovesPlaceholder() {
        FileStorage fs = storage();
        fs.mkdir("newdir");
        fs.writeString("newdir/a.txt", "A");
        assertFalse("写入真实文件后占位应被清掉",
                server.files(GIST_ID).containsKey("newdir~" + GistPaths.PLACEHOLDER_BASENAME));
        assertEquals("A", fs.readString("newdir/a.txt"));
    }

    @Test
    public void deleteFileAndRecursiveDeleteDir() {
        FileStorage fs = storage();
        fs.delete("README.md");
        assertFalse(fs.exists("README.md"));
        // 删除不存在对象幂等
        fs.delete("README.md");

        try {
            fs.delete("docs");
            fail("非空目录应拒绝普通 delete，提示用 deleteDir");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        fs.deleteDir("docs");
        assertFalse(fs.exists("docs/guide.md"));
        assertFalse(fs.exists("docs/api/v1.txt"));
    }

    @Test
    public void renameAndCopyFile() {
        FileStorage fs = storage();
        fs.rename("README.md", "docs/INTRO.md");
        assertFalse(fs.exists("README.md"));
        assertEquals("# hello", fs.readString("docs/INTRO.md"));
        fs.copy("docs/INTRO.md", "copies/intro-copy.md");
        assertEquals("# hello", fs.readString("copies/intro-copy.md"));
    }

    @Test
    public void renameAndCopyVirtualDirectory() {
        FileStorage fs = storage();
        fs.rename("docs", "manual");
        assertEquals("guide content", fs.readString("manual/guide.md"));
        assertEquals("api v1", fs.readString("manual/api/v1.txt"));
        assertFalse(fs.exists("docs/guide.md"));
        fs.copy("manual", "manual-backup");
        assertEquals("guide content", fs.readString("manual-backup/guide.md"));
    }

    @Test
    public void presignReturnsWebUrlAndAbsolutePath() {
        FileStorage fs = storage();
        assertEquals("https://gist.github.com/" + GIST_ID, fs.presign("README.md", null));
        assertEquals("gist://" + GIST_ID + "/docs/guide.md", fs.absolutePathOf("docs/guide.md"));
    }

    // ---------------------------------------------------------------- 高级 REST 能力

    @Test
    public void truncatedFileIsFetchedFromRawUrl() {
        server.truncatedFile = "README.md";
        assertArrayEquals("FULL-CONTENT-FROM-RAW".getBytes(StandardCharsets.UTF_8),
                storage().readBytes("README.md"));
        assertTrue("应先 GET gist 再 GET raw_url",
                server.received.stream().anyMatch(s -> s.contains("/raw/" + GIST_ID + "/README.md")));
    }

    @Test
    public void topLevelTruncatedIsRejectedLoudly() {
        server.topLevelTruncated = true;
        try {
            storage().list("");
            fail("顶层 truncated 必须明确报错，不能静默漏文件");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
    }

    @Test
    public void listGistsPaginatesAndReturnsInfo() {
        server.paginate = true;
        GistClient client = new GistClient(StorageConfig.empty()
                .set("token", "fake-token").set("apiBase", apiBase));
        List<GistClient.GistInfo> infos = client.listGists();
        // 第一页 100 个 filler + 第二页 1 个真实 Gist：翻页后应全部收齐
        assertEquals(101, infos.size());
        GistClient.GistInfo real = infos.stream()
                .filter(g -> GIST_ID.equals(g.id())).findFirst().orElse(null);
        assertNotNull("第二页的真实 Gist 应被收到", real);
        assertTrue(real.fileNames().contains("README.md"));
        assertEquals("jkit test gist", real.description());
        assertEquals("https://gist.github.com/" + GIST_ID, real.htmlUrl());
        assertTrue("应发生翻页", server.received.stream().anyMatch(s -> s.contains("page=2")));
    }

    @Test
    public void createAndDeleteGist() {
        GistClient client = new GistClient(StorageConfig.empty()
                .set("token", "fake-token").set("apiBase", apiBase));
        String id = client.createGist("new one", false,
                new java.util.AbstractMap.SimpleEntry<String, byte[]>("hello.txt",
                        "hi".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(id);
        assertEquals("hi", server.files(id).get("hello.txt"));
        client.deleteGist(id);
        assertFalse(server.gists.containsKey(id));
    }

    // ---------------------------------------------------------------- 错误码映射

    @Test
    public void authFailureMapsToAuthFailed() {
        server.requireAuth = true;
        try {
            FileStorages.of("github-gist", StorageConfig.empty()
                    .set("token", "wrong-token").set("gistId", GIST_ID)
                    .set("apiBase", apiBase)).list("");
            fail("401 应映射为 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void notFoundGistMapsToNotFound() {
        try {
            FileStorages.of("github-gist", StorageConfig.empty()
                    .set("token", "fake-token").set("gistId", "doesnotexist")
                    .set("apiBase", apiBase)).list("");
            fail("404 应映射为 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void rateLimitAndServerErrorAreMapped() {
        server.forcedStatus = 429;
        try {
            storage().list("");
            fail("429 应映射为 RATE_LIMITED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.RATE_LIMITED, e.code());
        }
        server.forcedStatus = 500;
        try {
            storage().list("");
            fail("5xx 应映射为 SERVER_ERROR");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.SERVER_ERROR, e.code());
        }
        server.forcedStatus = 422;
        try {
            storage().list("");
            fail("422 应映射为 INVALID_CONFIG（文件名非法）");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    // ---------------------------------------------------------------- 路径编码

    @Test
    public void pathEncodingIsReversible() {
        String[] paths = {"a/b/c.txt", "中文/文件 名.txt", "x~y/z%w.txt", "colon:name.txt"};
        for (String path : paths) {
            String flat = GistPaths.encode(path);
            assertFalse("扁平名不能含斜杠: " + flat, flat.contains("/"));
            assertEquals("编解码必须可逆: " + path, path, GistPaths.decode(flat));
        }
        assertEquals("", GistPaths.encode(""));
        assertEquals("", GistPaths.decode(""));
    }

    @Test
    public void contentEncodingIsReversible() {
        assertEquals("plain text", GistPaths.encodeContent("plain text".getBytes(StandardCharsets.UTF_8)));
        byte[] raw = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x41};
        assertArrayEquals(raw, GistPaths.decodeContent(GistPaths.encodeContent(raw)));
        assertArrayEquals(new byte[0], GistPaths.decodeContent(GistPaths.encodeContent(new byte[0])));
    }

    private static StorageField findField(StoragePlatform platform, String key) {
        for (StorageField field : platform.fields()) {
            if (field.key().equals(key)) {
                return field;
            }
        }
        return null;
    }
}
