package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.git.adapter.GitHostAdapter;
import com.alianga.jkit.storage.git.adapter.GiteaAdapter;
import com.alianga.jkit.storage.git.adapter.GiteeAdapter;
import com.alianga.jkit.storage.git.adapter.GithubAdapter;
import com.alianga.jkit.storage.git.adapter.GogsAdapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Git 托管仓库作为文件存储：浏览、读 / 下载、上传 / 覆盖、删除，写操作以 Git 提交落盘。
 *
 * <p>与 SVN 引擎的定位一致：一套实现通过 Adapter 覆盖四家平台，界面侧无需特判。
 * 每个实例持有独立的 {@link GitTransport}，由 {@code Sessions.call} 每次操作新建、用完即关。</p>
 *
 * <p><b>提交信息来源</b>：写操作需要提交信息，本类不自己弹窗（引擎层不该碰界面），
 * 而是调用 {@link #commitMessageSource}。自动模式下由 {@link CommitMessageResolver}
 * 按连接模板生成；手动模式下回调到界面层弹 JavaFX 对话框，返回 {@code null} 表示
 * 用户取消，此时上传 / 删除直接中止且不产生提交——不猜测、不写默认信息。</p>
 *
 * @author 郑明亮
 */
public class GitStorageProvider implements FileStorage {
    /** 条目属性：条目最后一次变更的提交 SHA（短）。 */
    public static final String ATTR_GIT_SHA = "git.sha";
    /** 条目属性：条目最后一次变更的提交作者。 */
    public static final String ATTR_GIT_AUTHOR = "git.author";
    /** 条目属性：条目最后一次变更的提交说明。 */
    public static final String ATTR_GIT_MESSAGE = "git.message";
    /** 条目属性：条目在仓库中的路径（含 rootPath）。 */
    public static final String ATTR_GIT_PATH = "git.path";

    /** 提交记录默认拉取条数（详情面板 / 右键历史共用）。 */
    public static final int DEFAULT_COMMIT_LIMIT = 30;

    /** base64 内联上传的体积上限：超过则明确报错，不假装成功。 */
    private static final long MAX_INLINE_BYTES = 20L * 1024L * 1024L;

    /** 目录占位文件内容：一个换行（Gitee 拒绝零字节内容，报 400 "content is empty"）。 */
    private static final byte[] GITKEEP_BYTES = "\n".getBytes(StandardCharsets.UTF_8);

    private final GitSourceConfig config;
    private final GitHostAdapter adapter;
    private final GitTransport transport;
    /** 写操作提交信息的提供者（手动模式由界面层弹窗；返回 null 表示用户取消）。 */
    private final CommitMessageSource commitMessageSource;
    /**
     * 实例级覆盖：批量写操作期间整批复用一条提交信息。
     *
     * <p>放在实例上（而不是改写全局 {@link #UI}）是因为界面可以有多个浏览面板：
     * 全局钩子会被另一栏的收尾动作改掉，顺序一错就把提交信息串到别的连接上。</p>
     */
    private volatile CommitMessageSource batchOverride;

    private volatile boolean closed;

    /**
     * 引擎工厂入口（由 {@link GitStorageSupport#register()} 注册到 {@code FileStorages}）。
     *
     * @param storageConfig 连接配置
     * @throws StorageException 配置非法（平台未知 / 缺实例地址 / 缺 owner 或 repo）
     */
    public GitStorageProvider(StorageConfig storageConfig) {
        this(storageConfig, null);
    }

    /**
     * @param storageConfig       连接配置
     * @param commitMessageSource 手动模式下的提交信息提供者（null 时走全局钩子 {@link #UI}）
     */
    GitStorageProvider(StorageConfig storageConfig, CommitMessageSource commitMessageSource) {
        this.config = GitSourceConfig.from(storageConfig);
        this.adapter = adapterFor(config.host());
        this.transport = new GitTransport();
        this.commitMessageSource = commitMessageSource;
    }

    /**
     * 提交信息提供者：手动模式下由界面层弹 JavaFX 对话框询问。
     *
     * <p>引擎层不依赖 JavaFX，界面层在启动时把实现装到 {@link GitStorageProvider#UI}，
     * 引擎在每次写操作前回调它。</p>
     */
    public interface CommitMessageSource {
        /**
         * 取本次写操作的提交信息。
         *
         * @param config    连接配置
         * @param operation 操作名（上传 / 删除 / 新建目录 / 重命名 / 复制）
         * @param repoPath  仓库内路径
         * @return 提交信息；null 表示用户取消，调用方必须中止操作
         */
        String message(GitSourceConfig config, String operation, String repoPath);
    }

    /** 界面层安装的提交信息提供者（手动模式弹窗用）。未安装时手动模式会明确报错而非静默兜底。 */
    static volatile CommitMessageSource UI;

    /**
     * 安装界面层的提交信息提供者（宿主 / 界面初始化时调用一次）。
     *
     * @param source 提供者；null 表示卸载
     */
    public static void installCommitMessageSource(CommitMessageSource source) {
        UI = source;
    }

    /**
     * 让本实例在后续写操作里整批复用一条提交信息（批量上传 / 删除用）。
     *
     * <p>引擎实例由 {@code Sessions.call} 每次操作新建，因此这个覆盖只作用于
     * 当前这次传输，不会泄漏到别的连接或别的面板。</p>
     *
     * @param message 整批共用的提交信息；null 表示清除覆盖
     */
    public void useBatchCommitMessage(String message) {
        final String batchMessage = message;
        this.batchOverride = batchMessage == null ? null : new CommitMessageSource() {
            @Override
            public String message(GitSourceConfig cfg, String operation, String repoPath) {
                return batchMessage;
            }
        };
    }

    /** 平台 → Adapter：四个平台各一个实现，差异只在鉴权头 / 路径 / 字段。 */
    static GitHostAdapter adapterFor(GitHost host) {
        switch (host) {
            case GITHUB:
                return new GithubAdapter();
            case GITEE:
                return new GiteeAdapter();
            case GITEA:
                return new GiteaAdapter();
            case GOGS:
                return new GogsAdapter();
            default:
                throw new IllegalArgumentException("未知的 Git 托管平台：" + host);
        }
    }

    @Override
    public String platform() {
        return GitStorageSupport.ENGINE_ID;
    }

    /** @return 本实例的连接配置（提交历史视图、右键菜单要用） */
    public GitSourceConfig sourceConfig() {
        return config;
    }

    // ------------------------------------------------------------------ 读操作

    @Override
    public boolean exists(String path) {
        try {
            stat(path);
            return true;
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public StorageEntry stat(String path) {
        String repoPath = config.repoPath(path);
        if (repoPath.isEmpty()) {
            // 仓库根：没有父目录可列，也不存在自身的变更记录
            return StorageEntry.dir("");
        }
        String parent = StoragePaths.parent(repoPath);
        String name = StoragePaths.name(repoPath);
        List<GitEntry> siblings = listRepo(parent.isEmpty() ? "" : stripRoot(parent));
        for (GitEntry entry : siblings) {
            if (name.equals(entry.name())) {
                return toEntry(entry, path);
            }
        }
        throw StorageException.notFound(path);
    }

    @Override
    public List<StorageEntry> list(String path) {
        List<GitEntry> entries = listRepo(path);
        List<StorageEntry> result = new ArrayList<StorageEntry>(entries.size());
        String display = StoragePaths.normalize(path);
        for (GitEntry entry : entries) {
            String childPath = display.isEmpty() ? entry.name() : display + "/" + entry.name();
            result.add(toEntry(entry, childPath));
        }
        // 目录在前、文件在后，各自按名称排序：与界面其他引擎的浏览顺序一致
        Collections.sort(result, new Comparator<StorageEntry>() {
            @Override
            public int compare(StorageEntry left, StorageEntry right) {
                if (left.isDirectory() != right.isDirectory()) {
                    return left.isDirectory() ? -1 : 1;
                }
                return left.name().toLowerCase(Locale.ROOT)
                        .compareTo(right.name().toLowerCase(Locale.ROOT));
            }
        });
        return result;
    }

    /** 列仓库内一层（逻辑路径 → 仓库路径）。 */
    private List<GitEntry> listRepo(String logicalPath) {
        String repoPath = config.repoPath(logicalPath);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("ref", config.branch());
        params.putAll(adapter.authParams(config));
        String url = adapter.contentsUrl(config)
                + (repoPath.isEmpty() ? "" : adapter.encodePath(repoPath))
                + GitTransport.query(params);
        GitTransport.Response response = transport.call("GET", url,
                adapter.authHeaders(config), null, logicalPath);
        if (response.status() == 404) {
            throw StorageException.notFound(logicalPath);
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw GitErrors.http(response.status(), response.body(), logicalPath);
        }
        return adapter.parseEntries(response.body(), logicalPath, false);
    }

    /** 逻辑路径 → 显示条目（属性里带 Git 元数据，供详情面板消费）。 */
    private StorageEntry toEntry(GitEntry entry, String displayPath) {
        StorageEntry result = entry.isDirectory()
                ? StorageEntry.dir(displayPath)
                : StorageEntry.file(displayPath, entry.size(), StorageEntry.MTIME_UNKNOWN, entry.sha());
        if (!entry.isDirectory() && entry.sha() != null && !entry.sha().trim().isEmpty()) {
            result = result.withAttribute(ATTR_GIT_SHA, entry.sha());
        }
        result = result.withAttribute(ATTR_GIT_PATH, entry.path());
        return result;
    }

    /**
     * 去掉逻辑路径里的 rootPath 前缀（stat 的父目录计算用）。
     *
     * @param repoParent 仓库内父目录
     * @return 对应的逻辑路径
     */
    private String stripRoot(String repoParent) {
        List<String> root = config.rootSegments();
        if (root.isEmpty()) {
            return repoParent;
        }
        String prefix = String.join("/", root);
        String normalized = StoragePaths.normalize(repoParent);
        if (normalized.equals(prefix)) {
            return "";
        }
        if (normalized.startsWith(prefix + "/")) {
            return normalized.substring(prefix.length() + 1);
        }
        return normalized;
    }

    @Override
    public void download(String path, OutputStream out) {
        String repoPath = config.repoPath(path);
        // 先取条目元数据：拿到 download_url 就不用走 base64（大文件也扛得住）
        Map<String, Object> meta = fileMetadata(path, repoPath);
        String downloadUrl = Json.str(meta, "download_url");
        byte[] inline = adapter.parseContentObject(meta, path);
        if (inline == null && (downloadUrl == null || downloadUrl.trim().isEmpty())) {
            downloadUrl = adapter.rawUrl(config, repoPath);
        }
        if (inline != null) {
            try {
                out.write(inline);
            } catch (IOException e) {
                throw StorageException.io("写入下载数据失败", path, e);
            }
            return;
        }
        InputStream in = adapter.openRaw(transport, config, repoPath, downloadUrl, path);
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw StorageException.io("下载失败：" + e.getMessage(), path, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    /** 读单个文件的元数据对象（同时用于取 sha 与 download_url）。 */
    private Map<String, Object> fileMetadata(String path, String repoPath) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("ref", config.branch());
        params.putAll(adapter.authParams(config));
        String url = adapter.contentsUrl(config) + adapter.encodePath(repoPath)
                + GitTransport.query(params);
        GitTransport.Response response = transport.call("GET", url,
                adapter.authHeaders(config), null, path);
        if (response.status() == 404) {
            throw StorageException.notFound(path);
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw GitErrors.http(response.status(), response.body(), path);
        }
        return Json.object(response.body(), path);
    }

    // ------------------------------------------------------------------ 写操作

    @Override
    public void mkdir(String dir) {
        String repoPath = config.repoPath(dir);
        if (repoPath.isEmpty()) {
            throw StorageException.invalidConfig("Git 存储不支持在仓库根之上创建目录");
        }
        // Git 没有空目录：写 .gitkeep 占位，与对象存储的目录占位对象同一思路。
        // 内容写一个换行而不是零字节：Gitee 的 contents API 拒绝空内容
        // （400 "content is empty"），GitHub / Gitea / Gogs 则接受零字节。
        String keepPath = repoPath + "/.gitkeep";
        adapter.precheck(config, true);
        // 幂等：占位文件已存在就直接返回（否则每同步一次都多一条无意义提交）
        if (existingSha(keepPath, keepPath) != null) {
            return;
        }
        String message = resolveMessage("新建目录", keepPath);
        if (message == null) {
            return;
        }
        writeContent(keepPath, GITKEEP_BYTES, message, null, keepPath);
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String repoPath = config.repoPath(path);
        adapter.precheck(config, true);
        byte[] payload = readPayload(in, size, path);
        String message = resolveMessage("上传", repoPath);
        if (message == null) {
            // 手动模式下用户取消：不产生任何提交，直接返回
            return;
        }
        String sha = existingSha(path, repoPath);
        writeContent(repoPath, payload, message, sha, path);
    }

    @Override
    public void delete(String path) {
        deleteOne(path, false);
    }

    @Override
    public void deleteDir(String path) {
        if (StoragePaths.normalize(path).isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        deleteOne(path, true);
    }

    /**
     * 删除：目录走递归（Git 没有目录对象，只能逐个文件删），
     * 且整树共用一条提交信息——一次弹窗问一次，不每个文件弹一次。
     *
     * @param path      逻辑路径
     * @param recursive true 表示递归删除目录内全部文件
     */
    private void deleteOne(String path, boolean recursive) {
        String repoPath = config.repoPath(path);
        if (repoPath.isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        adapter.precheck(config, true);
        List<String> targets = new ArrayList<String>();
        if (recursive) {
            try {
                collectFiles(path, targets);
            } catch (StorageException e) {
                if (e.code() == StorageErrorCode.NOT_FOUND) {
                    // 目录已不存在：与单文件 delete 的幂等语义一致（同步 / 批删场景
                    // 另一端已删时不应误报失败）
                    return;
                }
                throw e;
            }
        } else {
            targets.add(path);
        }
        if (targets.isEmpty()) {
            // 空目录（只有占位文件已被删）：删除不存在的对象视为成功
            return;
        }
        String message = resolveMessage("删除", repoPath);
        if (message == null) {
            return;
        }
        for (String target : targets) {
            checkCancelled();
            String targetRepoPath = config.repoPath(target);
            String sha = existingSha(target, targetRepoPath);
            if (sha == null) {
                // 已不存在：幂等跳过（并发删除 / 占位文件缺失）
                continue;
            }
            deleteContent(targetRepoPath, sha, message, target);
        }
    }

    /**
     * 长遍历（递归删除）的取消点：每个文件删除前检查线程中断。
     *
     * <p>删除 N 个文件要发 2N 个请求（existingSha 的 GET + DELETE），万级目录
     * 一旦开始此前只能等全部跑完——传输层对中断的响应是正确的，但两次调用
     * 之间的循环会继续。检查到中断即抛 CANCELLED（与传输层同一语义）。</p>
     */
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageException(StorageErrorCode.CANCELLED, "Git 删除被中断");
        }
    }

    /** 递归收集目录下的文件路径（深度优先，目录展开走 list）。 */
    private void collectFiles(String dir, List<String> sink) {
        checkCancelled();
        for (StorageEntry entry : list(dir)) {
            if (entry.isDirectory()) {
                collectFiles(entry.path(), sink);
            } else {
                sink.add(entry.path());
            }
        }
    }

    @Override
    public void rename(String source, String target) {
        adapter.precheck(config, true);
        String message = resolveMessage("重命名", config.repoPath(target));
        if (message == null) {
            return;
        }
        // Git 没有原子 rename：读源内容 → 写目标 → 删源（两次提交）。
        // 目标已存在时必须带它的 blob sha（覆盖语义）：不带的 PUT/POST 会被平台
        // 以 422「sha 不匹配」/ 400「文件已存在」拒绝，报错文案对用户是天书
        byte[] payload = readSourceBounded(source);
        writeContent(config.repoPath(target), payload, message,
                existingSha(target, config.repoPath(target)), target);
        String sourceSha = existingSha(source, config.repoPath(source));
        if (sourceSha != null) {
            try {
                deleteContent(config.repoPath(source), sourceSha, message, source);
            } catch (StorageException deleteFailed) {
                // 目标已写入、源删除失败：仓库里留下双份，消息必须说清楚现状
                throw new StorageException(deleteFailed.code(),
                        "已写入 " + target + "，但删除源文件失败（仓库中暂存双份）："
                                + deleteFailed.getMessage(), source, deleteFailed);
            }
        }
    }

    @Override
    public void copy(String source, String target) {
        adapter.precheck(config, true);
        String message = resolveMessage("复制", config.repoPath(target));
        if (message == null) {
            return;
        }
        byte[] payload = readSourceBounded(source);
        writeContent(config.repoPath(target), payload, message,
                existingSha(target, config.repoPath(target)), target);
    }

    /**
     * 读取 rename / copy 的源内容（有界）：超过 {@link #MAX_INLINE_BYTES} 立即中止。
     *
     * <p>upload 路径有 readPayload 的上限防线，rename / copy 此前没有任何检查——
     * 平台对大文件走流式下载，几百 MB 的文件 rename 会把它整个缓冲进堆，
     * 桌面工具低堆环境下是实际 OOM。</p>
     */
    private byte[] readSourceBounded(String source) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] bytes, int off, int len) {
                checkInlineBound((long) count + len, source);
                super.write(bytes, off, len);
            }

            @Override
            public synchronized void write(int b) {
                checkInlineBound((long) count + 1, source);
                super.write(b);
            }
        };
        download(source, buffer);
        return buffer.toByteArray();
    }

    /**
     * 单文件 REST 提交体积防线：超限抛可读的 CONFLICT（包内可见供单测直接驱动）。
     *
     * @param totalBytes 累计字节数
     * @param path       出错时的逻辑路径
     */
    static void checkInlineBound(long totalBytes, String path) {
        if (totalBytes > MAX_INLINE_BYTES) {
            throw new StorageException(StorageErrorCode.CONFLICT,
                    "文件超过 " + (MAX_INLINE_BYTES / 1024 / 1024)
                            + "MB：Git 托管平台通过 REST 提交单文件有上限，"
                            + "重命名 / 复制请下载后用 Git 客户端操作", path);
        }
    }

    /** 读上传流到内存：Contents API 只接受 base64 JSON，必须先拿到完整字节。 */
    private byte[] readPayload(InputStream in, long size, String path) {
        if (size > MAX_INLINE_BYTES) {
            throw new StorageException(StorageErrorCode.CONFLICT,
                    "文件过大（" + size + " 字节）：Git 托管平台通过 REST 提交单文件上限约 "
                            + (MAX_INLINE_BYTES / 1024 / 1024) + "MB，请改用 Git 客户端推送", path);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                checkInlineBound((long) out.size() + read, path);
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw StorageException.io("读取上传数据失败", path, e);
        }
    }

    /**
     * 已存在文件的 blob SHA（更新 / 删除时必须回传）；不存在返回 null。
     *
     * <p>「不存在」有两种形态，都要认：GitHub 返回 HTTP 404；
     * <b>Gitee 返回 HTTP 200 + 空数组 {@code []}</b>——只按状态码判会把它当成
     * 「文件存在但没 sha」，随后 PUT 不带 sha 去更新，平台报「文件已存在」。</p>
     */
    private String existingSha(String path, String repoPath) {
        try {
            Map<String, Object> meta = fileMetadata(path, repoPath);
            if (meta == null || meta.isEmpty()) {
                return null;
            }
            return Json.str(meta, "sha");
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /** 写内容（PUT contents）：新建不带 sha，更新带 sha。 */
    private void writeContent(String repoPath, byte[] payload, String message, String sha,
                              String path) {
        adapter.precheck(config, true);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(payload));
        body.put(adapter.branchField(), config.branch());
        if (sha != null && !sha.trim().isEmpty()) {
            body.put("sha", sha);
        }
        String json = com.alianga.jkit.json.JSON.toJsonString(body);
        Map<String, String> params = new LinkedHashMap<String, String>(adapter.authParams(config));
        String url = adapter.contentsUrl(config) + adapter.encodePath(repoPath)
                + GitTransport.query(params);
        // 新建 / 更新的 HTTP 方法各家不同：GitHub 一律 PUT，Gitee 新建用 POST、更新用 PUT
        String method = adapter.createMethod(sha != null && !sha.trim().isEmpty());
        GitTransport.Response response = transport.call(method, url,
                adapter.authHeaders(config), json, path);
        if (response.status() < 200 || response.status() >= 300) {
            throw GitErrors.http(response.status(), response.body(), path);
        }
    }

    /** 删内容（DELETE contents）。 */
    private void deleteContent(String repoPath, String sha, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        body.put("sha", sha);
        body.put(adapter.branchField(), config.branch());
        String json = com.alianga.jkit.json.JSON.toJsonString(body);
        Map<String, String> params = new LinkedHashMap<String, String>(adapter.authParams(config));
        String url = adapter.contentsUrl(config) + adapter.encodePath(repoPath)
                + GitTransport.query(params);
        GitTransport.Response response = transport.call("DELETE", url,
                adapter.authHeaders(config), json, path);
        if (response.status() == 404) {
            // 删除不存在的对象视为成功（与 FileStorage 契约一致）
            return;
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw GitErrors.http(response.status(), response.body(), path);
        }
    }

    /**
     * 解析本次写操作的提交信息：自动模式按模板，手动模式问界面层。
     *
     * @param operation 操作名
     * @param repoPath  仓库内路径
     * @return 提交信息；null 表示用户取消（调用方必须中止操作）
     */
    private String resolveMessage(String operation, String repoPath) {
        if (config.manualCommit()) {
            CommitMessageSource source = commitMessageSource != null ? commitMessageSource
                    : (batchOverride != null ? batchOverride : UI);
            if (source == null) {
                // 手动模式却没有界面层弹窗入口：宁可明确失败，也不用默认信息偷偷提交
                throw StorageException.invalidConfig(
                        "连接配置为「手动填写提交信息」，但当前环境没有提供输入入口，已中止写操作");
            }
            String provided = source.message(config, operation, repoPath);
            // 空白与取消等价：Git 不接受空提交信息
            if (provided == null || provided.trim().isEmpty()) {
                return null;
            }
            return provided.trim();
        }
        return CommitMessageResolver.render(config.commitTemplate(),
                new CommitMessageResolver.Request(operation, repoPath, config.branch()),
                LocalDateTime.now());
    }

    // ------------------------------------------------------------------ 提交记录

    /**
     * 取某个路径相关的提交记录（按时间倒序，最新在前）。
     *
     * @param path  逻辑路径（空串表示整个存储根，即 rootPath 前缀）
     * @param limit 最大条数
     * @return 提交列表；权限不足抛 AUTH_FAILED（界面转成可读提示）
     */
    public List<GitCommit> commits(String path, int limit) {
        String repoPath = config.repoPath(path);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("sha", config.branch());
        params.put(adapter.pageSizeParam(), String.valueOf(limit <= 0
                ? DEFAULT_COMMIT_LIMIT : limit));
        if (!repoPath.isEmpty()) {
            // 目录过滤：平台对 path 传目录时返回其下全部提交（GitHub / Gitea / Gogs 均支持）
            params.put("path", repoPath);
        }
        params.putAll(adapter.authParams(config));
        String url = adapter.commitsUrl(config) + GitTransport.query(params);
        GitTransport.Response response = transport.call("GET", url,
                adapter.authHeaders(config), null, path);
        if (response.status() == 404) {
            // 分支不存在或仓库不可见：按「拿不到历史」处理，不让详情面板崩
            return Collections.emptyList();
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw GitErrors.http(response.status(), response.body(), path);
        }
        return adapter.parseCommits(response.body(), path);
    }

    // ------------------------------------------------------------------ 其它

    @Override
    public boolean supportsRange() {
        return false;
    }

    @Override
    public String absolutePathOf(String path) {
        return config.repoPath(path);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        transport.close();
    }

    @Override
    public String toString() {
        return "GitStorageProvider(" + config + ")";
    }
}
