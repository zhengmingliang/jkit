package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.engine.gist.GistClient.FileChange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GitHub Gist 作为文件存储：一条连接 = 一个 Gist，Gist 的 {@code files} 映射当文件盘。
 *
 * <p>Gist 本身是扁平的文本片段（文件名不能含 {@code /}，没有目录），本引擎用
 * {@link GistPaths} 的可逆编码把逻辑路径压成扁平文件名，并按 {@code ~} 还原出虚拟目录树，
 * 因此 {@code FileStorage} 的列目录 / 建目录 / 递归删除 / 同步等语义都能复用。</p>
 *
 * <p><b>为什么这么设计</b>：Gist 的 ID 由服务端分配、数量多而每个都小，与「bucket / 仓库」
 * 不同。让一条连接对应一个 Gist，写操作只需要一次 {@code PATCH /gists/{id}}，不会在列表时
 * 把账号下几十个 Gist 全拉一遍；跨 Gist 浏览请配置多条连接或用 {@code GistClient#listGists()}
 * 查到 ID 再填进来。</p>
 *
 * <p><b>限制（如实声明）</b>：只适合文本 / 小文件（单文件 ≤ {@link GistConfig#MAX_UPLOAD_BYTES}）；
 * 目录为编码模拟，非原生；每次写操作都会产生一个 Gist 修订版本（可在网页端回溯）。</p>
 *
 * @author 郑明亮
 */
public final class GistFileStorage implements FileStorage {
    private final String gistId;
    private final GistClient client;

    /**
     * 引擎工厂入口（由 {@link GistStorageSupport#register()} 注册到 {@code FileStorages}）。
     *
     * @param config 连接配置
     * @throws StorageException 配置非法时抛 INVALID_CONFIG
     */
    public GistFileStorage(StorageConfig config) {
        this(config, new GistClient(config));
    }

    /**
     * 测试 / 高级用法：注入自定义客户端（如本地桩服务）。
     *
     * @param config 连接配置
     * @param client Gist REST 客户端
     */
    GistFileStorage(StorageConfig config, GistClient client) {
        GistConfig.validate(config);
        this.gistId = GistConfig.normalizeGistId(config.get(GistConfig.GIST_ID));
        this.client = client;
    }

    /** @return 本实例对应的 Gist ID */
    public String gistId() {
        return gistId;
    }

    /** @return 底层 REST 客户端（列举 Gist / 新建 Gist 用） */
    public GistClient client() {
        return client;
    }

    @Override
    public String platform() {
        return GistConfig.PLATFORM_ID;
    }

    // ------------------------------------------------------------------ 读操作

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        return snapshot().containsPath(normalized);
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("");
        }
        Snapshot snapshot = snapshot();
        byte[] content = snapshot.files.get(normalized);
        if (content != null) {
            return StorageEntry.file(normalized, content.length,
                    snapshot.mtimeOf(normalized), null);
        }
        if (snapshot.dirs.contains(normalized)) {
            return StorageEntry.dir(normalized);
        }
        throw StorageException.notFound(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        Snapshot snapshot = snapshot();
        if (!normalized.isEmpty() && !snapshot.dirs.contains(normalized)
                && !snapshot.files.containsKey(normalized)) {
            throw StorageException.notFound(normalized);
        }
        Set<String> childDirs = new HashSet<String>();
        List<StorageEntry> result = new ArrayList<StorageEntry>();
        for (Map.Entry<String, byte[]> entry : snapshot.files.entrySet()) {
            String path = entry.getKey();
            if (!StoragePaths.isUnder(normalized, path)) {
                continue;
            }
            String relative = StoragePaths.relativize(normalized, path);
            if (relative == null || relative.isEmpty()) {
                continue;
            }
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                // 占位文件本身不出现在列表里，但它所在的这一层目录必须可见
                childDirs.add(StoragePaths.join(normalized, relative.substring(0, slash)));
            } else if (!GistPaths.isPlaceholder(path)) {
                result.add(StorageEntry.file(path, entry.getValue().length,
                        snapshot.mtimeOf(path), null));
            }
        }
        for (String childDir : childDirs) {
            result.add(StorageEntry.dir(childDir));
        }
        // 目录在前、文件在后，各自按名称排序：与界面其他引擎的浏览顺序一致
        Collections.sort(result, new java.util.Comparator<StorageEntry>() {
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

    // ------------------------------------------------------------------ 写操作

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        Snapshot snapshot = snapshot();
        if (snapshot.containsPath(normalized)) {
            return;
        }
        // 空目录：写一个隐藏占位文件，保证目录在列表里可见、可递归删除
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        changes.put(GistPaths.placeholderFor(normalized),
                FileChange.content(GistPaths.PLACEHOLDER_CONTENT.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));
        client.patch(gistId, changes);
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        upload(path, in, size, null);
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("上传路径不能是存储根目录");
        }
        if (GistPaths.isPlaceholder(normalized)) {
            throw StorageException.invalidConfig("该名称是引擎保留的目录占位文件名，请换一个");
        }
        byte[] bytes = readAll(in, size);
        if (bytes.length > GistConfig.MAX_UPLOAD_BYTES) {
            throw StorageException.conflict("GitHub Gist 单文件上限 "
                    + GistConfig.MAX_UPLOAD_BYTES + " 字节，当前 " + bytes.length
                    + " 字节：请改用仓库（Git 托管平台）或拆分文件", normalized);
        }
        String flat = GistPaths.encode(normalized);
        if (flat.isEmpty()) {
            throw StorageException.invalidConfig("上传路径无效: " + path);
        }
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        changes.put(flat, FileChange.content(bytes));
        // 父目录若由占位文件撑着，写入真实文件后删掉占位，避免两层表示同一个目录
        String parent = StoragePaths.parent(normalized);
        if (!parent.isEmpty()) {
            String placeholder = GistPaths.placeholderFor(parent);
            if (snapshot().files.containsKey(StoragePaths.join(parent,
                    GistPaths.PLACEHOLDER_BASENAME))) {
                changes.put(placeholder, null);
            }
        }
        client.patch(gistId, changes);
    }

    @Override
    public void download(String path, OutputStream out) {
        byte[] bytes = contentOf(path);
        try {
            out.write(bytes);
        } catch (IOException e) {
            throw StorageException.io("写出文件内容失败: " + e.getMessage(), path, e);
        }
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        Snapshot snapshot = snapshot();
        if (snapshot.dirs.contains(normalized)) {
            List<StorageEntry> children = list(normalized);
            if (!children.isEmpty()) {
                throw StorageException.conflict("目录非空: " + normalized
                        + "，请用 deleteDir 递归删除", normalized);
            }
        }
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        boolean found = false;
        for (String logical : snapshot.files.keySet()) {
            if (logical.equals(normalized) || StoragePaths.isUnder(normalized, logical)) {
                changes.put(GistPaths.encode(logical), null);
                found = true;
            }
        }
        if (!found) {
            return; // 删除不存在的对象视为成功（幂等）
        }
        client.patch(gistId, changes);
    }

    @Override
    public void deleteDir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        Snapshot snapshot = snapshot();
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        for (String logical : snapshot.files.keySet()) {
            if (logical.equals(normalized) || StoragePaths.isUnder(normalized, logical)) {
                changes.put(GistPaths.encode(logical), null);
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        client.patch(gistId, changes);
    }

    @Override
    public void copy(String src, String dst) {
        String source = StoragePaths.normalize(src);
        String target = StoragePaths.normalize(dst);
        if (target.isEmpty() || source.isEmpty()) {
            throw StorageException.invalidConfig("复制 / 移动的源与目标路径都不能是根目录");
        }
        Snapshot snapshot = snapshot();
        if (snapshot.dirs.contains(source)) {
            copyDir(snapshot, source, target);
            return;
        }
        byte[] bytes = snapshot.files.get(source);
        if (bytes == null) {
            throw StorageException.notFound(source);
        }
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        changes.put(GistPaths.encode(target), FileChange.content(bytes));
        client.patch(gistId, changes);
    }

    @Override
    public void rename(String src, String dst) {
        String source = StoragePaths.normalize(src);
        String target = StoragePaths.normalize(dst);
        if (target.isEmpty() || source.isEmpty()) {
            throw StorageException.invalidConfig("重命名 / 移动的源与目标路径都不能是根目录");
        }
        Snapshot snapshot = snapshot();
        if (snapshot.dirs.contains(source)) {
            // 目录改名：逐文件改扁平名（PATCH 支持 filename 字段），一次请求完成
            renameDir(snapshot, source, target);
            return;
        }
        byte[] bytes = snapshot.files.get(source);
        if (bytes == null) {
            throw StorageException.notFound(source);
        }
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        changes.put(GistPaths.encode(source),
                FileChange.renameWithContent(GistPaths.encode(target), bytes));
        client.patch(gistId, changes);
    }

    @Override
    public String presign(String path, java.time.Duration ttl) {
        // Gist 没有带时效的预签名链接；返回网页地址（公开 Gist 任何人可看，
        // 私有 Gist 需登录 GitHub 且有权限），语义上等价于「分享链接」。
        Snapshot snapshot = snapshot();
        if (!snapshot.files.containsKey(StoragePaths.normalize(path))) {
            throw StorageException.notFound(path);
        }
        return "https://gist.github.com/" + gistId;
    }

    @Override
    public String absolutePathOf(String path) {
        return "gist://" + gistId + "/" + StoragePaths.normalize(path);
    }

    // ------------------------------------------------------------------ 内部

    private byte[] contentOf(String path) {
        String normalized = StoragePaths.normalize(path);
        byte[] bytes = snapshot().files.get(normalized);
        if (bytes == null) {
            throw StorageException.notFound(normalized);
        }
        return bytes;
    }

    private void copyDir(Snapshot snapshot, String source, String target) {
        if (StoragePaths.isUnder(source, target)) {
            throw StorageException.invalidConfig("不能把目录复制到自身或其子目录内");
        }
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        for (Map.Entry<String, byte[]> entry : snapshot.files.entrySet()) {
            if (entry.getKey().equals(source) || StoragePaths.isUnder(source, entry.getKey())) {
                String relative = StoragePaths.relativize(source, entry.getKey());
                String dest = StoragePaths.join(target, relative == null ? "" : relative);
                changes.put(GistPaths.encode(dest), FileChange.content(entry.getValue()));
            }
        }
        if (changes.isEmpty()) {
            changes.put(GistPaths.placeholderFor(target),
                    FileChange.content(GistPaths.PLACEHOLDER_CONTENT.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)));
        }
        client.patch(gistId, changes);
    }

    private void renameDir(Snapshot snapshot, String source, String target) {
        if (StoragePaths.isUnder(source, target)) {
            throw StorageException.invalidConfig("不能把目录移动到自身或其子目录内");
        }
        Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();
        for (Map.Entry<String, byte[]> entry : snapshot.files.entrySet()) {
            if (entry.getKey().equals(source) || StoragePaths.isUnder(source, entry.getKey())) {
                String relative = StoragePaths.relativize(source, entry.getKey());
                String dest = StoragePaths.join(target, relative == null ? "" : relative);
                changes.put(GistPaths.encode(entry.getKey()),
                        FileChange.renameWithContent(GistPaths.encode(dest), entry.getValue()));
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        client.patch(gistId, changes);
    }

    private static byte[] readAll(InputStream in, long size) {
        if (in == null) {
            return new byte[0];
        }
        int hint = size > 0L && size < Integer.MAX_VALUE ? (int) size : 8192;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(hint, 32));
        byte[] buffer = new byte[8192];
        try {
            int read;
            long total = 0L;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > GistConfig.MAX_UPLOAD_BYTES) {
                    throw StorageException.conflict("GitHub Gist 单文件上限 "
                            + GistConfig.MAX_UPLOAD_BYTES + " 字节，已中止读取", "-");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw StorageException.io("读取上传流失败: " + e.getMessage(), "-", e);
        }
        return out.toByteArray();
    }

    /**
     * 拉取一次 Gist 并展开成逻辑目录树快照。每个操作独立拉取（HTTP 引擎实例本来就轻，
     * {@code Sessions.call} 用完即关），不做跨操作缓存，避免多窗口并发下读到陈旧内容。
     */
    private Snapshot snapshot() {
        Map<String, byte[]> flat = client.getGist(gistId);
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        Set<String> dirs = new HashSet<String>();
        dirs.add("");
        for (Map.Entry<String, byte[]> entry : flat.entrySet()) {
            String logical = GistPaths.decode(entry.getKey());
            if (logical.isEmpty()) {
                continue;
            }
            files.put(logical, entry.getValue());
            String parent = StoragePaths.parent(logical);
            while (!parent.isEmpty()) {
                dirs.add(parent);
                parent = StoragePaths.parent(parent);
            }
        }
        return new Snapshot(files, dirs);
    }

    /** 一次 Gist 读取的逻辑视图。 */
    private static final class Snapshot {
        private final Map<String, byte[]> files;
        private final Set<String> dirs;

        Snapshot(Map<String, byte[]> files, Set<String> dirs) {
            this.files = files;
            this.dirs = dirs;
        }

        boolean containsPath(String path) {
            return files.containsKey(path) || dirs.contains(path);
        }

        long mtimeOf(String path) {
            // Gist REST 的文件对象不带单文件更新时间；统一返回未知，
            // 避免拿 gist.updated_at 冒充每个文件的 mtime（同步引擎会误判）
            return StorageEntry.MTIME_UNKNOWN;
        }
    }
}
