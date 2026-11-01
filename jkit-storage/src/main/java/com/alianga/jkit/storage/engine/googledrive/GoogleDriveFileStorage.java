package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.cap.StorageCapabilities;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Drive 存储引擎实现：通过 Google Drive REST API v3 读写云端硬盘或团队共享盘。
 *
 * <p>支持特性：
 * <ul>
 *   <li>OAuth2 令牌自动续期与 401 失败自愈重试；</li>
 *   <li>支持个人云端硬盘主目录及团队共享盘 (Shared Drives / Team Drives)；</li>
 *   <li>路径到 ID 级联解析与并发缓存，极大减少多级目录遍历 HTTP 请求；</li>
 *   <li>多部分流式分块上传，支持覆盖写与未知大小中转；</li>
 *   <li>Range 标头断点续传下载（{@link #supportsRange()} 为 true）；</li>
 *   <li>删除即移入原生回收站（网页端可恢复，能力声明 hasNativeRecycleBin）。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public class GoogleDriveFileStorage implements FileStorage {
    private final StorageConfig config;
    private final String basePath;
    private final GoogleDriveClient client;

    public GoogleDriveFileStorage(StorageConfig config) {
        GoogleDriveConfig.validate(config);
        this.config = config;
        this.basePath = StoragePaths.normalize(config.getOr(GoogleDriveConfig.BASE_PATH, ""));

        String clientId = config.getOr(GoogleDriveConfig.CLIENT_ID, "");
        String clientSecret = config.getOr(GoogleDriveConfig.CLIENT_SECRET, "");
        String redirectUri = config.getOr(GoogleDriveConfig.REDIRECT_URI, GoogleDriveConfig.DEFAULT_REDIRECT_URI);
        String accessToken = config.getOr(GoogleDriveConfig.ACCESS_TOKEN, "");
        String refreshToken = config.getOr(GoogleDriveConfig.REFRESH_TOKEN, "");
        long expiresAt = config.longOr(GoogleDriveConfig.EXPIRES_AT, 0L);
        String driveId = config.getOr(GoogleDriveConfig.DRIVE_ID, "");

        this.client = new GoogleDriveClient(
                clientId, clientSecret, redirectUri,
                accessToken, refreshToken, expiresAt,
                driveId,
                config.getOr("apiBase", GoogleDriveConfig.DEFAULT_API_BASE),
                config.getOr("uploadBase", GoogleDriveConfig.DEFAULT_UPLOAD_BASE),
                config.getOr("tokenUrl", GoogleDriveConfig.DEFAULT_TOKEN_URL),
                null);
    }

    public GoogleDriveClient client() {
        return client;
    }

    @Override
    public String platform() {
        return GoogleDriveConfig.PLATFORM_ID;
    }

    @Override
    public boolean exists(String path) {
        try {
            stat(path);
            return true;
        } catch (StorageException e) {
            return false;
        }
    }

    @Override
    public StorageEntry stat(String path) {
        StorageEntry entry = client.stat(toFullPath(path));
        return wrapEntry(entry);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        List<StorageEntry> rawList = client.list(toFullPath(dir));
        List<StorageEntry> logicalList = new ArrayList<StorageEntry>(rawList.size());
        for (StorageEntry entry : rawList) {
            logicalList.add(wrapEntry(entry));
        }
        return logicalList;
    }

    private StorageEntry wrapEntry(StorageEntry entry) {
        String logPath = toLogicalPath(entry.path());
        if (entry.isDirectory()) {
            return StorageEntry.dir(logPath, entry.lastModified());
        }
        return StorageEntry.file(logPath, entry.size(), entry.lastModified(), entry.etag());
    }

    @Override
    public void mkdir(String dir) {
        client.mkdirs(toFullPath(dir));
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        upload(path, in, size, null);
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        client.upload(toFullPath(path), in, size, meta);
    }

    @Override
    public void download(String path, OutputStream out) {
        download(path, 0L, out);
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        client.download(toFullPath(path), offset, out);
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public StorageCapabilities capabilities() {
        return com.alianga.jkit.storage.FileStorages.capabilitiesOf(platform())
                .withSupportsRange(true);
    }

    @Override
    public void delete(String path) {
        client.delete(toFullPath(path));
    }

    @Override
    public void deleteDir(String dir) {
        if (StoragePaths.normalize(dir).isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        // 平台把文件夹整体移入回收站（整树可恢复），不必逐文件 walk
        client.delete(toFullPath(dir));
    }

    @Override
    public void copy(String src, String dst) {
        client.copy(toFullPath(src), toFullPath(dst));
    }

    @Override
    public void rename(String src, String dst) {
        client.rename(toFullPath(src), toFullPath(dst));
    }

    @Override
    public void close() {
        // 无状态 HTTP 客户端，无需特殊释放
    }

    /** 逻辑路径 → 真实云端绝对/叠加基路径后的完整路径。 */
    private String toFullPath(String logicalPath) {
        String norm = StoragePaths.normalize(logicalPath);
        if (basePath.isEmpty()) {
            return norm;
        }
        return norm.isEmpty() ? basePath : StoragePaths.join(basePath, norm);
    }

    /** 完整路径 → 对外呈现的逻辑路径。 */
    private String toLogicalPath(String fullPath) {
        String norm = StoragePaths.normalize(fullPath);
        if (basePath.isEmpty()) {
            return norm;
        }
        if (norm.equals(basePath)) {
            return "";
        }
        if (norm.startsWith(basePath + "/")) {
            return StoragePaths.normalize(norm.substring(basePath.length() + 1));
        }
        return norm;
    }
}
