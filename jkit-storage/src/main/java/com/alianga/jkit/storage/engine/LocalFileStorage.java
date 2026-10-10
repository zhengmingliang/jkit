package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文件系统引擎：基于 {@code java.nio.file}，无任何额外依赖。
 * basePath 之下的读写与其它平台保持同一套接口语义；路径上跳（{@code ..}）
 * 在 {@link StoragePaths#normalize} 已被折叠，不会逃逸出根目录。
 *
 * @author 郑明亮
 */
public class LocalFileStorage implements FileStorage {
    private final Path base;

    /**
     * 由配置创建（配置键：basePath）。
     *
     * @param config 连接配置
     */
    public LocalFileStorage(StorageConfig config) {
        String root = config.require("basePath");
        this.base = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.exists(base)) {
            try {
                Files.createDirectories(base);
            } catch (IOException e) {
                throw StorageException.io("根目录创建失败: " + base + "（" + e.getMessage() + "）", "-", e);
            }
        } else if (!Files.isDirectory(base)) {
            throw StorageException.invalidConfig("basePath 不是目录: " + base);
        }
    }

    @Override
    public String platform() {
        return "local";
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        Path target = resolve(normalized);
        if (!Files.exists(target)) {
            throw StorageException.notFound(normalized);
        }
        try {
            if (Files.isDirectory(target)) {
                return StorageEntry.dir(normalized, Files.getLastModifiedTime(target).toMillis());
            }
            return StorageEntry.file(normalized, Files.size(target),
                    Files.getLastModifiedTime(target).toMillis(), null);
        } catch (IOException e) {
            throw StorageException.io("读取元数据失败: " + normalized, normalized, e);
        }
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        Path directory = resolve(normalized);
        if (!Files.isDirectory(directory)) {
            throw StorageException.notFound(normalized);
        }
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                String childPath = normalized.isEmpty()
                        ? child.getFileName().toString() : normalized + "/" + child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    entries.add(StorageEntry.dir(childPath, Files.getLastModifiedTime(child).toMillis()));
                } else {
                    entries.add(StorageEntry.file(childPath, Files.size(child),
                            Files.getLastModifiedTime(child).toMillis(), null));
                }
            }
        } catch (IOException e) {
            throw StorageException.io("列出目录失败: " + normalized, normalized, e);
        }
        return entries;
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        Path directory = resolve(normalized);
        if (Files.isDirectory(directory)) {
            return;
        }
        if (Files.exists(directory)) {
            throw StorageException.conflict("同名文件已存在，无法创建目录: " + normalized, normalized);
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw StorageException.io("创建目录失败: " + normalized, normalized, e);
        }
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        Path target = resolve(normalized);
        Path parent = target.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = target.resolveSibling("." + target.getFileName() + ".jkit-tmp");
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    out.write(chunk, 0, read);
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw StorageException.io("上传失败: " + normalized + "（" + e.getMessage() + "）", normalized, e);
        }
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        String normalized = requireFile(path);
        upload(path, in, size);
        if (meta != null && meta.hasLastModified()) {
            try {
                Files.setLastModifiedTime(resolve(normalized),
                        java.nio.file.attribute.FileTime.fromMillis(meta.lastModified()));
            } catch (IOException ignore) {
                // mtime 保留失败不影响上传结果
            }
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        Path target = resolve(normalized);
        if (!Files.exists(target)) {
            throw StorageException.notFound(normalized);
        }
        if (Files.isDirectory(target)) {
            throw StorageException.conflict("目标是目录，无法按文件下载: " + normalized, normalized);
        }
        try {
            Files.copy(target, out);
        } catch (IOException e) {
            throw StorageException.io("下载失败: " + normalized + "（" + e.getMessage() + "）", normalized, e);
        }
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        Path target = resolve(normalized);
        if (!Files.exists(target)) {
            return;
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            if (e instanceof java.nio.file.DirectoryNotEmptyException) {
                throw StorageException.conflict("目录非空: " + normalized + "，请用 deleteDir", normalized);
            }
            throw StorageException.io("删除失败: " + normalized + "（" + e.getMessage() + "）", normalized, e);
        }
    }

    @Override
    public void copy(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        Path from = resolve(source);
        if (Files.isDirectory(from)) {
            copyDir(source, target);
            return;
        }
        try {
            Path to = resolve(target);
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw StorageException.io("复制失败: " + source + " -> " + target, target, e);
        }
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        Path from = resolve(source);
        Path to = resolve(target);
        try {
            if (Files.isDirectory(from) && Files.exists(to)) {
                throw StorageException.conflict("目标目录已存在: " + target, target);
            }
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw StorageException.io("重命名失败: " + source + " -> " + target, target, e);
        }
    }

    private void copyDir(String source, String target) {
        mkdir(target);
        for (StorageEntry entry : list(source)) {
            if (entry.isDirectory()) {
                copyDir(entry.path(), StoragePaths.join(target, entry.name()));
            } else {
                copy(entry.path(), StoragePaths.join(target, entry.name()));
            }
        }
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private Path resolve(String path) {
        String normalized = StoragePaths.normalize(path);
        Path resolved = normalized.isEmpty()
                ? base : base.resolve(normalized.replace('/', File.separatorChar));
        return resolved.normalize();
    }
}
