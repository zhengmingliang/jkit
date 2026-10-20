package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 用常驻 Node 子进程跑网页原版 xmtls.js + xmtls.wasm（JSON 行协议，见 qqmail-xmtls-helper.cjs）。
 *
 * <ul>
 *   <li>node：配置 {@code nodePath} 优先，否则在 PATH 与常见目录里找；找不到给出明确错误。</li>
 *   <li>xmtls 资源：配置 {@code xmtlsDir} 优先（自备，不校验哈希）；否则缓存到
 *       {@code ~/.jkit/qqmail-xmtls/22042502/}，缺失时从 res.wx.qq.com 下载并按 SHA-256 钉死校验。</li>
 *   <li>子进程 stderr 丢弃、stdout 只认 {@code @@xmtls } 前缀行——wasm 会把明文（含 pwd_key）打到 stdout，
 *       助手已屏蔽，这里再兜一层。</li>
 *   <li>单次请求超时即杀进程；进程崩了下次调用自动重启。</li>
 * </ul>
 *
 * @author 郑明亮
 */
final class QqMailXmtlsNode implements QqMailXmtls {
    private static final Logger LOG = Logger.getLogger(QqMailXmtlsNode.class.getName());

    /** 与网页 home-web.js 引用的版本目录一致；腾讯换版本后旧 URL 一般仍可用，失效需更新常量与哈希。 */
    static final String XMTLS_VERSION = "22042502";
    static final String XMTLS_BASE = "https://res.wx.qq.com/t/webmail/mailcdn/" + XMTLS_VERSION + "/xmtls/";
    static final String JS_SHA256 = "006974e4ff0ee5d965503af6fa766a05d05c45aea2e284b4609b8bafc09700ac";
    static final String WASM_SHA256 = "1c317abcabaed80859954f44e7180edeab71fb86d4d46304c787d71fca2e39be";
    static final String HELPER_RESOURCE = "qqmail-xmtls-helper.cjs";
    static final String PREFIX = "@@xmtls ";

    private final String nodePathCfg;
    private final String xmtlsDirCfg;
    private final long timeoutMs;

    private final Object lock = new Object();
    private Process process;
    private Writer stdin;
    private volatile CountDownLatch ready;
    private volatile String fatal;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<Long, Pending>();
    private final AtomicLong ids = new AtomicLong();
    /** 句柄属于哪一代进程：进程重启后旧句柄作废。 */
    private long generation;

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Map<?, ?> result;
        volatile long gen = -1L;
    }

    /**
     * @param nodePath  node 可执行文件（空则自动找）
     * @param xmtlsDir  自备 xmtls 目录（空则用缓存并下载）
     * @param timeoutMs 单次请求超时
     */
    QqMailXmtlsNode(String nodePath, String xmtlsDir, long timeoutMs) {
        this.nodePathCfg = nodePath == null ? "" : nodePath.trim();
        this.xmtlsDirCfg = xmtlsDir == null ? "" : xmtlsDir.trim();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 15_000L;
    }

    @Override
    public Sealed encrypt(String pwdKey, String plainJson) {
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("op", "encrypt");
        if (pwdKey != null && !pwdKey.isEmpty()) {
            req.put("key", pwdKey);
        }
        req.put("data", plainJson);
        Map<?, ?> res = request(req);
        Object h = res.get("h");
        Object data = res.get("data");
        if (!Boolean.TRUE.equals(res.get("ok")) || h == null || data == null) {
            throw StorageException.io("xmtls 加密失败（" + err(res) + "）", null, null);
        }
        long gen;
        synchronized (lock) {
            gen = generation;
        }
        return new Sealed((gen << 32) | (Long.parseLong(String.valueOf(h)) & 0xffffffffL), String.valueOf(data));
    }

    @Override
    public String decrypt(long handle, String cipherText) {
        Long h = localHandle(handle);
        if (h == null) {
            return null;
        }
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("op", "decrypt");
        req.put("h", h);
        req.put("data", cipherText == null ? "" : cipherText);
        Map<?, ?> res = request(req);
        return Boolean.TRUE.equals(res.get("ok")) && res.get("data") != null ? String.valueOf(res.get("data")) : null;
    }

    @Override
    public void release(long handle) {
        Long h = localHandle(handle);
        if (h == null) {
            return;
        }
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("op", "release");
        req.put("h", h);
        try {
            request(req);
        } catch (StorageException ignored) {
            // 进程没了，会话自然也没了
        }
    }

    private Long localHandle(long handle) {
        synchronized (lock) {
            if ((handle >>> 32) != generation || process == null || !process.isAlive()) {
                return null;
            }
        }
        return handle & 0xffffffffL;
    }

    private Map<?, ?> request(Map<String, Object> req) {
        long id = ids.incrementAndGet();
        req.put("id", id);
        String line = JSON.toJsonString(req);
        Pending p = new Pending();
        pending.put(id, p);
        try {
            synchronized (lock) {
                ensureStarted();
                p.gen = generation;
                try {
                    stdin.write(line);
                    stdin.write('\n');
                    stdin.flush();
                } catch (IOException e) {
                    kill();
                    throw StorageException.io("xmtls 助手进程写入失败，已重启", null, null);
                }
            }
            boolean ok;
            try {
                ok = p.done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw StorageException.io("xmtls 请求被中断", null, null);
            }
            if (!ok) {
                synchronized (lock) {
                    kill();
                }
                throw StorageException.timeout("xmtls 助手 " + timeoutMs + "ms 未响应，已杀掉，下次自动重启");
            }
            if (p.result == null) {
                throw StorageException.io("xmtls 助手进程异常退出" + (fatal != null ? "（" + fatal + "）" : ""), null, null);
            }
            return p.result;
        } finally {
            pending.remove(id);
        }
    }

    /** 持锁调用。 */
    private void ensureStarted() {
        if (process != null && process.isAlive()) {
            return;
        }
        kill();
        String node = resolveNode();
        Path dir = prepareAssets();
        fatal = null;
        ready = new CountDownLatch(1);
        ProcessBuilder pb = new ProcessBuilder(node, dir.resolve(HELPER_RESOURCE).toString(), dir.toString());
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.to(nullFile()));
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIG,
                    "无法启动 node（" + node + "）：" + e.getClass().getSimpleName(), null);
        }
        process = proc;
        generation++;
        stdin = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
        final CountDownLatch myReady = ready;
        final long myGen = generation;
        Thread reader = new Thread(() -> readLoop(proc, myReady, myGen), "qqmail-xmtls-reader");
        reader.setDaemon(true);
        reader.start();
        boolean up;
        try {
            up = myReady.await(Math.max(timeoutMs, 20_000L), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            up = false;
        }
        if (!up || fatal != null || !proc.isAlive()) {
            String why = fatal != null ? fatal : (proc.isAlive() ? "启动超时" : "进程退出");
            kill();
            throw StorageException.io("xmtls 助手启动失败（" + why + "），需 Node 16+", null, null);
        }
    }

    private void readLoop(Process proc, CountDownLatch myReady, long myGen) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith(PREFIX)) {
                    continue; // 非协议行（可能含明文）一律丢弃，不记日志
                }
                Object o;
                try {
                    o = JSON.parse(line.substring(PREFIX.length()));
                } catch (RuntimeException e) {
                    continue;
                }
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) o;
                if (Boolean.TRUE.equals(m.get("ready"))) {
                    myReady.countDown();
                    continue;
                }
                if (m.get("fatal") != null) {
                    fatal = String.valueOf(m.get("fatal"));
                    myReady.countDown();
                    continue;
                }
                Object id = m.get("id");
                if (id == null) {
                    continue;
                }
                Pending p = pending.get(Long.parseLong(String.valueOf(id)));
                if (p != null) {
                    p.result = m;
                    p.done.countDown();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 进程退出
        } finally {
            myReady.countDown();
            for (Pending p : pending.values()) {
                if (p.gen != myGen) {
                    continue;
                }
                p.done.countDown(); // result 为 null → 调用方报“异常退出”
            }
        }
    }

    /** 持锁调用。 */
    private void kill() {
        Process p = process;
        process = null;
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // ignore
            }
            stdin = null;
        }
        if (p != null) {
            p.destroyForcibly();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            kill();
        }
    }

    // ------------------------------------------------------------------ node / 资源

    String resolveNode() {
        if (!nodePathCfg.isEmpty()) {
            File f = new File(nodePathCfg);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
            throw StorageException.invalidConfig("nodePath 不可执行：请填写 node 可执行文件的完整路径（Node 16+）");
        }
        String found = findOnPath(System.getenv("PATH"), isWindows());
        if (found != null) {
            return found;
        }
        throw StorageException.invalidConfig("未找到 node：QQ 邮箱自动续期需要本机安装 Node.js 16+，"
                + "或在连接配置「Node 路径」里填写 node 可执行文件完整路径");
    }

    static String findOnPath(String path, boolean windows) {
        String[] names = windows ? new String[]{"node.exe", "node.cmd"} : new String[]{"node"};
        StringBuilder dirs = new StringBuilder(path == null ? "" : path);
        String home = System.getProperty("user.home", "");
        // GUI 启动时 PATH 常不含版本管理器目录
        String[] extra = windows
                ? new String[]{System.getenv("ProgramFiles") + "\\nodejs", home + "\\AppData\\Roaming\\nvm\\current"}
                : new String[]{"/usr/local/bin", "/usr/bin", "/opt/homebrew/bin",
                        home + "/.local/share/mise/shims", home + "/.volta/bin", home + "/.nvm/current/bin"};
        for (String e : extra) {
            dirs.append(File.pathSeparatorChar).append(e);
        }
        for (String d : dirs.toString().split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (d.isEmpty()) {
                continue;
            }
            for (String n : names) {
                File f = new File(d, n);
                if (f.isFile() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private Path prepareAssets() {
        Path dir;
        boolean managed = xmtlsDirCfg.isEmpty();
        if (managed) {
            dir = Paths.get(System.getProperty("user.home"), ".jkit", "qqmail-xmtls", XMTLS_VERSION);
        } else {
            dir = Paths.get(xmtlsDirCfg);
        }
        try {
            Files.createDirectories(dir);
            if (managed) {
                ensureAsset(dir.resolve("xmtls.js"), XMTLS_BASE + "xmtls.js", JS_SHA256);
                ensureAsset(dir.resolve("xmtls.wasm"), XMTLS_BASE + "xmtls.wasm", WASM_SHA256);
            } else if (!Files.isRegularFile(dir.resolve("xmtls.js")) || !Files.isRegularFile(dir.resolve("xmtls.wasm"))) {
                throw StorageException.invalidConfig("xmtlsDir 里缺少 xmtls.js 或 xmtls.wasm");
            }
            writeHelper(dir.resolve(HELPER_RESOURCE));
        } catch (IOException e) {
            throw StorageException.io("准备 xmtls 资源失败：" + e.getClass().getSimpleName(), null, e);
        }
        return dir;
    }

    private static void ensureAsset(Path target, String url, String sha256) throws IOException {
        if (Files.isRegularFile(target) && sha256.equals(sha256(Files.readAllBytes(target)))) {
            return;
        }
        LOG.info("下载 xmtls 资源: " + url);
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            int code = c.getResponseCode();
            if (code != 200) {
                throw StorageException.io("下载 xmtls 资源失败 HTTP " + code + "（" + url + "）", null, null);
            }
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            String got = sha256(Files.readAllBytes(tmp));
            if (!sha256.equals(got)) {
                throw StorageException.io("xmtls 资源哈希不符（腾讯可能已更新版本），拒绝使用：" + url
                        + "；可自备文件并配置 xmtlsDir", null, null);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeHelper(Path target) throws IOException {
        byte[] want;
        try (InputStream in = QqMailXmtlsNode.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw StorageException.io("jar 内缺少 " + HELPER_RESOURCE, null, null);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            want = out.toByteArray();
        }
        if (Files.isRegularFile(target) && java.util.Arrays.equals(want, Files.readAllBytes(target))) {
            return;
        }
        try (OutputStream o = Files.newOutputStream(target)) {
            o.write(want);
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static File nullFile() {
        return new File(isWindows() ? "NUL" : "/dev/null");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String err(Map<?, ?> res) {
        Object e = res.get("err");
        return e == null ? "unknown" : String.valueOf(e);
    }

    @Override
    public String toString() {
        return "QqMailXmtlsNode{redacted}";
    }
}
