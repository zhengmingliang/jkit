package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 对真实 {@code svnserve}（svn:// 协议，CRAM-MD5 认证）跑完整存储生命周期：
 * 建目录（含多级）、上传（流式提交）、列目录 / stat、下载、覆盖、重命名 / 复制、
 * 删除 / 递归删目录、中文文件名，以及鉴权失败、连不上、目标不存在 / 已存在等错误码。
 *
 * <p>本机缺少 svnserve / svnadmin 时整类跳过（与 RustfsTransferTest 同一约定）。</p>
 *
 * @author 郑明亮
 */
public class SvnServeIntegrationTest {

    private static Process server;
    private static int port;
    private static Path root;

    private static final String USER = "testuser";
    private static final String PASS = "testpass";
    private static final String BASE = "/repo";

    @BeforeClass
    public static void startServer() throws Exception {
        Assume.assumeTrue("缺少 svnadmin，跳过 svn:// 集成测试", commandExists("svnadmin"));
        Assume.assumeTrue("缺少 svnserve，跳过 svn:// 集成测试", commandExists("svnserve"));

        root = Files.createTempDirectory("jkit-svn-it");
        Path repos = root.resolve("repos");
        Files.createDirectories(repos);
        run("svnadmin", "create", repos.resolve("repo").toString());
        Files.write(repos.resolve("repo/conf/svnserve.conf"),
                (String.join("\n",
                "[general]",
                "anon-access = none",
                "auth-access = write",
                "password-db = passwd",
                "authz-db = authz",
                "realm = jkit-svn-test",
                "") + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(repos.resolve("repo/conf/passwd"),
                (String.join("\n",
                "[users]",
                USER + " = " + PASS,
                "reader = readerpass",
                "") + "\n").getBytes(StandardCharsets.UTF_8));
        // 授权规则：testuser 可写，reader 只读，其余拒绝
        Files.write(repos.resolve("repo/conf/authz"),
                (String.join("\n",
                "[groups]",
                "[repo:/]",
                "testuser = rw",
                "reader = r",
                "* =",
                "") + "\n").getBytes(StandardCharsets.UTF_8));

        port = freePort();
        ProcessBuilder builder = new ProcessBuilder("svnserve", "-d", "--foreground",
                "--listen-host=127.0.0.1", "--listen-port=" + port,
                "--root=" + repos);
        builder.redirectErrorStream(true);
        server = builder.start();
        Assume.assumeTrue("svnserve 启动后立刻退出", server.isAlive());
        if (!awaitListening(port, 10)) {
            server.destroyForcibly();
            Assume.assumeTrue("svnserve 10 秒内未监听端口，跳过", false);
        }
        SvnStorageSupport.register();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.destroy();
            try {
                if (!server.waitFor(5, TimeUnit.SECONDS)) {
                    server.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void fullLifecycleOverSvnProtocol() throws Exception {
        try (FileStorage storage = open(USER, PASS)) {
            // 起始根：空仓库
            assertTrue(storage.list("").isEmpty());

            // mkdir：单级与多级（makeParents）
            storage.mkdir("life/docs/sub");
            storage.mkdir("life/docs/deep/nested");
            assertTrue(storage.exists("life/docs/deep/nested"));
            assertTrue(storage.stat("life/docs/deep/nested").isDirectory());

            // 上传：文本、空文件、二进制、中文文件名
            String text = "hello 世界\n";
            upload(storage, "life/docs/sub/hello.txt", text.getBytes(StandardCharsets.UTF_8));
            upload(storage, "life/empty.txt", new byte[0]);
            byte[] binary = new byte[256];
            for (int i = 0; i < 256; i++) {
                binary[i] = (byte) i;
            }
            upload(storage, "life/中文 文件.bin", binary);

            // exists / stat
            assertTrue(storage.exists("life/docs/sub/hello.txt"));
            assertFalse(storage.exists("life/missing.txt"));
            StorageEntry fileEntry = storage.stat("life/docs/sub/hello.txt");
            assertFalse(fileEntry.isDirectory());
            assertEquals("life/docs/sub/hello.txt", fileEntry.path());
            assertEquals("hello.txt", fileEntry.name());
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, fileEntry.size());
            assertTrue(fileEntry.lastModified() > 0);
            StorageEntry rootEntry = storage.stat("/");
            assertTrue(rootEntry.isDirectory());
            assertEquals("", rootEntry.path());
            StorageEntry statDir = storage.stat("life/docs");
            assertTrue(statDir.isDirectory());
            assertEquals("stat 目录条目也应带最后修改人",
                    USER, statDir.attribute(SvnStorageSupport.ATTR_OWNER));
            assertTrue(statDir.lastModified() > 0);
            assertEquals("info/stat 应保留目录路径", "life/docs", statDir.path());
            assertTrue("目录应保留 SVN revision 属性",
                    Long.parseLong(statDir.attribute(SvnStorageSupport.ATTR_SVN_REVISION)) >= 0);

            StorageEntry nestedStatDir = storage.stat("life/docs/deep/nested");
            assertTrue("精确路径 stat 应识别多层目录", nestedStatDir.isDirectory());
            assertEquals(USER, nestedStatDir.attribute(SvnStorageSupport.ATTR_OWNER));
            assertTrue(nestedStatDir.lastModified() > 0);

            // 列目录：目录在前、文件在后，中文文件名可见
            List<StorageEntry> life = storage.list("life");
            assertEquals("docs", life.get(0).name());
            assertTrue(life.get(0).isDirectory());
            assertTrue(life.stream().anyMatch(e -> e.name().equals("中文 文件.bin")));
            assertTrue(life.stream().anyMatch(e -> e.name().equals("empty.txt")));

            // 用户信息与 SVN 元数据：文件和目录条目都带最后修改人；文件带版本与仓库 URL
            StorageEntry docs = life.get(0);
            assertEquals(USER, docs.attribute(SvnStorageSupport.ATTR_OWNER));
            assertTrue("目录也应有最后修改时间", docs.lastModified() > 0);
            StorageEntry bin = life.stream().filter(e -> e.name().equals("中文 文件.bin")).findFirst().orElseThrow();
            assertEquals(USER, bin.attribute(SvnStorageSupport.ATTR_OWNER));
            long revision = Long.parseLong(bin.attribute(SvnStorageSupport.ATTR_SVN_REVISION));
            assertTrue(revision >= 0);
            assertEquals("r" + revision, bin.etag());
            String url = bin.attribute(SvnStorageSupport.ATTR_SVN_URL);
            assertTrue("仓库 URL 应以条目名结尾：" + url, url.endsWith("%E4%B8%AD%E6%96%87%20%E6%96%87%E4%BB%B6.bin"));
            // stat 单条目元数据与 list 一致
            StorageEntry stat = storage.stat("life/docs/sub/hello.txt");
            assertEquals(USER, stat.attribute(SvnStorageSupport.ATTR_OWNER));
            assertEquals(stat.attribute(SvnStorageSupport.ATTR_SVN_REVISION),
                    stat.etag().substring(1));
            StorageEntry listedFile = storage.list("life/docs/sub").stream()
                    .filter(entry -> entry.name().equals("hello.txt")).findFirst().orElseThrow();
            assertEquals("精确 stat 应按文件最后变更 revision 返回提交说明",
                    "storage-ui upload hello.txt",
                    stat.attribute(SvnStorageSupport.ATTR_SVN_COMMIT_MESSAGE));
            assertEquals("精确 stat 应保留文件大小", listedFile.size(), stat.size());
            assertEquals("精确 stat 应保留文件修改时间", listedFile.lastModified(), stat.lastModified());
            StorageEntry listedDirectory = life.stream()
                    .filter(entry -> entry.name().equals("docs")).findFirst().orElseThrow();
            assertTrue("精确 stat 应按目录最后变更 revision 返回提交说明",
                    statDir.attribute(SvnStorageSupport.ATTR_SVN_COMMIT_MESSAGE) != null
                            && !statDir.attribute(SvnStorageSupport.ATTR_SVN_COMMIT_MESSAGE).trim().isEmpty());
            assertEquals("精确 stat 应保留目录最后修改时间",
                    listedDirectory.lastModified(), statDir.lastModified());

            // 下载：内容一致
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),
                    download(storage, "life/docs/sub/hello.txt"));
            assertArrayEquals(new byte[0], download(storage, "life/empty.txt"));
            assertArrayEquals(binary, download(storage, "life/中文 文件.bin"));

            // 默认便捷方法
            storage.writeString("life/note.txt", "笔记");
            assertEquals("笔记", storage.readString("life/note.txt"));

            // 覆盖上传（openFile 分支）
            upload(storage, "life/docs/sub/hello.txt", "changed".getBytes(StandardCharsets.UTF_8));
            assertEquals("changed", storage.readString("life/docs/sub/hello.txt"));

            // 重命名（URL 级 move，保留内容）+ 复制
            storage.rename("life/docs/sub/hello.txt", "life/docs/sub/hi.txt");
            assertFalse(storage.exists("life/docs/sub/hello.txt"));
            assertEquals("changed", storage.readString("life/docs/sub/hi.txt"));
            storage.copy("life/docs/sub/hi.txt", "life/docs/sub/hi-copy.txt");
            assertEquals("changed", storage.readString("life/docs/sub/hi-copy.txt"));

            // 删文件、递归删目录：一次提交整树
            storage.delete("life/docs/sub/hi-copy.txt");
            assertFalse(storage.exists("life/docs/sub/hi-copy.txt"));
            storage.deleteDir("life/docs");
            assertFalse(storage.exists("life/docs"));

            // 整棵 life 仍可列（docs 之外的文件还在），最后整树删除
            assertTrue(storage.exists("life/中文 文件.bin"));
            storage.deleteDir("life");
            assertFalse(storage.exists("life"));
            assertTrue(storage.list("").isEmpty());
        }
    }

    @Test
    public void mkdirExistingPathIsAlreadyExists() throws Exception {
        try (FileStorage storage = open(USER, PASS)) {
            storage.mkdir("dup-dir");
            expectCode(StorageErrorCode.ALREADY_EXISTS, () -> storage.mkdir("dup-dir"));
            // 上传到已存在的同名目录：ALREADY_EXISTS，不覆盖成文件
            expectCode(StorageErrorCode.ALREADY_EXISTS,
                    () -> storage.upload("dup-dir", new ByteArrayInputStream(new byte[0]), 0));
        }
    }

    @Test
    public void wrongPasswordIsAuthFailure() throws Exception {
        // 密码错误：认证阶段被拒（E170001）
        try (FileStorage storage = open(USER, "wrong-password")) {
            expectCode(StorageErrorCode.AUTH_FAILED, () -> storage.list(""));
        }
    }

    @Test
    public void readOnlyUserWriteIsPermissionDenied() throws Exception {
        // 合法用户但无写权限：授权失败同样归类 AUTH_FAILED，读操作可用
        try (FileStorage storage = open("reader", "readerpass")) {
            storage.list("");
            expectCode(StorageErrorCode.AUTH_FAILED, () -> storage.mkdir("reader-mkdir"));
        }
    }

    @Test
    public void unreachablePortIsConnectFailure() throws Exception {
        int closed = freePort();
        Map<String, String> config = baseConfig(USER, PASS);
        config.put("port", String.valueOf(closed));
        try (FileStorage storage = FileStorages.of("svn", config)) {
            expectCode(StorageErrorCode.CONNECT_FAILED, () -> storage.list(""));
        }
    }

    @Test
    public void missingTargetsAreNotFound() throws Exception {
        try (FileStorage storage = open(USER, PASS)) {
            expectCode(StorageErrorCode.NOT_FOUND, () -> storage.stat("no/such/file.txt"));
            expectCode(StorageErrorCode.NOT_FOUND, () -> storage.list("no/such/dir"));
            expectCode(StorageErrorCode.NOT_FOUND,
                    () -> storage.download("no/such/file.txt", new ByteArrayOutputStream()));
            // SVN 提交协议下删除不存在条目一律报事务过时（E160028）：CONFLICT
            expectCode(StorageErrorCode.CONFLICT, () -> storage.delete("no-such-file.txt"));
            expectCode(StorageErrorCode.CONFLICT, () -> storage.delete("no/such/file.txt"));
            expectCode(StorageErrorCode.NOT_FOUND,
                    () -> storage.rename("no/such/a.txt", "b.txt"));
        }
    }

    @Test
    public void deleteDirRefusesRoot() throws Exception {
        try (FileStorage storage = open(USER, PASS)) {
            expectCode(StorageErrorCode.INVALID_CONFIG, () -> storage.deleteDir("/"));
            expectCode(StorageErrorCode.INVALID_CONFIG, () -> storage.deleteDir(""));
        }
    }

    /**
     * 并发正确性守卫：SyncEngine（concurrency>1）会让多个线程共用同一个
     * SvnFileStorage 实例，而 SVNRepository 非线程安全（svn:// 是单条 socket
     * 上的多路请求序列），实现内所有 SVNKit 调用必须由同一把锁串行化。
     *
     * <p>注意：竞态是否显式炸掉取决于服务端时序，本测试不保证在无锁实现上
     * 必然失败——它保证的是并发读写混合下数据始终完整正确（内容不错位、
     * 写入不丢失），锁缺失导致的协议交错会先在这里以异常或内容错乱现形。</p>
     */
    @Test
    public void singleInstanceSurvivesConcurrentReadsAndWrites() throws Exception {
        try (FileStorage storage = open(USER, PASS)) {
            storage.mkdir("conc");
            int files = 8;
            for (int i = 0; i < files; i++) {
                upload(storage, "conc/f" + i + ".txt", ("payload-" + i).getBytes(StandardCharsets.UTF_8));
            }
            int threads = 8;
            int rounds = 12;
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            java.util.List<Throwable> errors =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            // 栅栏对齐起跑：所有线程同一刻冲进同一条 svn:// 连接，最大化交错概率
            java.util.concurrent.CyclicBarrier gate =
                    new java.util.concurrent.CyclicBarrier(threads);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    try {
                        gate.await(30L, TimeUnit.SECONDS);
                        for (int r = 0; r < rounds; r++) {
                            // 读写混合：与 SyncEngine 并发同步的访问形态一致
                            storage.list("conc");
                            storage.stat("conc/f" + (id % files) + ".txt");
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            storage.download("conc/f" + ((id + r) % files) + ".txt", out);
                            assertTrue("内容必须完整可读", out.size() > 0);
                            upload(storage, "conc/w" + id + "-" + r + ".txt",
                                    ("w" + id + r).getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Throwable failure) {
                        errors.add(failure);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60L, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            assertTrue("并发读写不应产生任何异常：" + errors, errors.isEmpty());
            // 全部写入都要落盘且内容正确
            for (int t = 0; t < threads; t++) {
                for (int r = 0; r < rounds; r++) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    storage.download("conc/w" + t + "-" + r + ".txt", out);
                    assertEquals("w" + t + r, out.toString(StandardCharsets.UTF_8));
                }
            }
            storage.deleteDir("conc");
        }
    }

    @Test
    public void platformRegisteredWithProtocolFields() {
        assertNotNull(FileStorages.platform("svn"));
        assertEquals("协议", FileStorages.platform("svn").category());
        assertEquals("svn", FileStorages.platform("svn").engine());
        assertTrue(FileStorages.platform("svn").fields().stream()
                .anyMatch(f -> f.key().equals("protocol")));
    }

    // ---- helpers ----

    private static FileStorage open(String user, String password) {
        return FileStorages.of("svn", baseConfig(user, password));
    }

    private static Map<String, String> baseConfig(String user, String password) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("protocol", "svn");
        config.put("host", "127.0.0.1");
        config.put("port", String.valueOf(port));
        config.put("user", user);
        config.put("password", password);
        config.put("basePath", BASE);
        return config;
    }

    private static void upload(FileStorage storage, String path, byte[] bytes) {
        storage.upload(path, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static byte[] download(FileStorage storage, String path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        storage.download(path, out);
        return out.toByteArray();
    }

    private static void expectCode(StorageErrorCode expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("期望抛出 " + expected);
        } catch (StorageException e) {
            assertEquals(expected, e.code());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("期望 StorageException，实际 " + e, e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static boolean awaitListening(int port, int seconds) throws Exception {
        for (int i = 0; i < seconds * 5; i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (Exception ignored) {
                Thread.sleep(200);
            }
        }
        return false;
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder("which", command)
                    .redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(command[0] + " 失败：" + output,
                process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0);
        return output;
    }
}
