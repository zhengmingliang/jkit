package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 139 云盘（移动云盘）「新个人云」Web API 文件存储：浏览、下载、上传、建目录、删除、
 * 移动、重命名、复制与预签名分享链接——<b>全目录可读可写</b>，不受智能体专属目录约束。
 *
 * <p>这是网页版同款接口（AList / OpenList / cloud139 长期验证的路线）：网页版
 * Authorization 认证 + Mcloud-Sign 签名。写操作对齐网页版能力：删除走回收站
 * （{@code /recyclebin/batchTrash}，可恢复）、移动走 {@code /file/batchMove}、
 * 重命名走 {@code /file/update}（文件 / 目录同接口）、复制走 {@code /file/batchCopy}、
 * 建目录走 {@code /file/create}（type=folder）。</p>
 *
 * <p><b>ID 映射</b>：与 {@link CmCloudFileStorage} 同一套「逻辑路径 → fileId」缓存模式
 * （根目录对应平台约定的 {@code "/"}，逐层列目录解析；实例级缓存随操作生灭）。</p>
 *
 * <p><b>上传</b>：SHA-256 整体摘要 + 分片（默认 20MiB，与网页版一致），
 * {@code file/create}（contentHash 供秒传判定）→ 逐片 PUT 预签名地址（octet-stream +
 * 显式 Content-Length + Origin/Referer）→ {@code file/complete}；秒传命中跳过传输；
 * 超过 100 片的部分经 {@code file/getUploadUrl} 分批补取地址；同名冲突平台默认
 * {@code auto_rename}（服务端自动改名，以云端实际名为准）。</p>
 *
 * @author 郑明亮
 */
public class Yun139FileStorage implements FileStorage {
    /** 条目属性：平台文件 ID（删除 / 移动 / 复制等操作的真实寻址键）。 */
    public static final String ATTR_Y139_FILE_ID = "yun139.fileId";
    /** 条目属性：云端绝对路径（含起始目录前缀，展示用）。 */
    public static final String ATTR_Y139_CLOUD_PATH = "yun139.cloudPath";

    /** 列目录单页大小（与网页版一致）。 */
    private static final int PAGE_SIZE = 100;
    /** 单目录翻页上限：防止平台游标异常时死循环。 */
    private static final int MAX_PAGES = 1000;
    /** create 单次携带的分片数上限，其余分片地址走 getUploadUrl 补取。 */
    private static final int MAX_PARTS_PER_CREATE = Yun139Profile.MAX_PARTS_PER_CREATE;

    private final Yun139Config config;
    private final Yun139Transport transport;

    /** 逻辑路径 → 平台 fileId 缓存（根路径 "" 固定映射 "/"；实例级，随操作生灭）。 */
    private final Map<String, String> idCache = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /** 引擎工厂入口（由 {@link Yun139StorageSupport#register()} 注册到 {@code FileStorages}）。 */
    public Yun139FileStorage(StorageConfig storageConfig) {
        this(storageConfig, Yun139Profile.ROUTE_POLICY_URL);
    }

    /** 测试可注入路由策略地址（默认官方地址）。 */
    Yun139FileStorage(StorageConfig storageConfig, String routePolicyUrl) {
        this.config = Yun139Config.from(storageConfig);
        this.transport = new Yun139Transport(config, routePolicyUrl);
    }

    @Override
    public String platform() {
        return Yun139Profile.PLATFORM_ID;
    }

    /** @return 本实例的连接配置（诊断用） */
    public Yun139Config sourceConfig() {
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
        String logical = StoragePaths.normalize(path);
        if (logical.isEmpty()) {
            return StorageEntry.dir("");
        }
        String parent = StoragePaths.parent(logical);
        String name = StoragePaths.name(logical);
        for (StorageEntry entry : list(parent)) {
            if (name.equals(entry.name())) {
                return entry;
            }
        }
        throw StorageException.notFound(path);
    }

    @Override
    public List<StorageEntry> list(String path) {
        String logical = StoragePaths.normalize(path);
        String folderId = folderIdOf(logical);
        List<StorageEntry> result = new ArrayList<>();
        for (Map<String, Object> item : listChildren(folderId, logical)) {
            result.add(toEntry(item, logical));
        }
        // 目录在前、文件在后，各自按名称排序：与界面其他引擎的浏览顺序一致
        result.sort(Comparator
                .comparing((StorageEntry e) -> !e.isDirectory())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    /** 列一个目录的全部直接子项（游标翻页取尽）。 */
    private List<Map<String, Object>> listChildren(String folderId, String logical) {
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            pageInfo.put("pageCursor", cursor);
            pageInfo.put("pageSize", PAGE_SIZE);
            Map<String, Object> body = CmCloudTransport.body();
            body.put("imageThumbnailStyleList", Arrays.asList("Small", "Large"));
            body.put("orderBy", "updated_at");
            body.put("orderDirection", "DESC");
            body.put("pageInfo", pageInfo);
            body.put("parentFileId", folderId);
            Map<String, Object> data = transport.post("/file/list", body, logical);
            Object rawItems = data.get("items");
            List<?> batch = rawItems instanceof List ? (List<?>) rawItems : Collections.emptyList();
            for (Object entry : batch) {
                if (entry instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) entry;
                    items.add(item);
                }
            }
            String next = CmCloudTransport.str(data, "nextPageCursor");
            if (next == null || Strs.isBlank(next) || next.equals(cursor)) {
                // 本页未满或无游标即取尽；游标重复说明平台异常，按取尽处理避免死循环
                return items;
            }
            cursor = next;
        }
        throw new StorageException(StorageErrorCode.SERVER_ERROR,
                "目录 " + logical + " 翻页超过 " + MAX_PAGES + " 页仍未取尽，已中止以防死循环", logical);
    }

    /** 原始子项 → 显示条目（属性里带 fileId，供删除 / 移动 / 复制与详情面板消费）。 */
    private StorageEntry toEntry(Map<String, Object> item, String parentLogical) {
        String name = CmCloudTransport.str(item, "name");
        if (name == null || Strs.isBlank(name)) {
            name = CmCloudTransport.str(item, "fileName");
        }
        String fileId = CmCloudTransport.str(item, "fileId");
        boolean isDir = "folder".equalsIgnoreCase(CmCloudTransport.str(item, "type"));
        long mtime = parseTime(item.get("updatedAt"));
        if (mtime == StorageEntry.MTIME_UNKNOWN) {
            mtime = parseTime(item.get("createdAt"));
        }
        String childPath = parentLogical.isEmpty() ? name : parentLogical + "/" + name;
        StorageEntry result = isDir
                ? StorageEntry.dir(childPath, mtime)
                : StorageEntry.file(childPath,
                        CmCloudTransport.num(item, "size", StorageEntry.SIZE_UNKNOWN),
                        mtime, fileId);
        if (fileId != null && !Strs.isBlank(fileId)) {
            result = result.withAttribute(ATTR_Y139_FILE_ID, fileId);
        }
        result = result.withAttribute(ATTR_Y139_CLOUD_PATH, config.cloudPath(childPath));
        return result;
    }

    /** 平台时间字段（ISO8601 带时区，或毫秒数）→ epoch 毫秒；拿不到给 MTIME_UNKNOWN。 */
    private static long parseTime(Object value) {
        if (value instanceof Number) {
            long raw = ((Number) value).longValue();
            return raw <= 0 ? StorageEntry.MTIME_UNKNOWN : raw;
        }
        if (value instanceof String && !Strs.isBlank((String) value)) {
            String trimmed = ((String) value).trim();
            if (trimmed.chars().allMatch(Character::isDigit)) {
                try {
                    long raw = Long.parseLong(trimmed);
                    return raw <= 0 ? StorageEntry.MTIME_UNKNOWN : raw;
                } catch (NumberFormatException ignored) {
                    return StorageEntry.MTIME_UNKNOWN;
                }
            }
            try {
                return OffsetDateTime.parse(trimmed,
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                try {
                    return Instant.parse(trimmed).toEpochMilli();
                } catch (RuntimeException ignoredToo) {
                    // 非法时间格式：按未知处理，不让整列目录失败
                }
            }
        }
        return StorageEntry.MTIME_UNKNOWN;
    }

    // ------------------------------------------------------------------ 写操作

    @Override
    public void mkdir(String dir) {
        String logical = StoragePaths.normalize(dir);
        if (logical.isEmpty()) {
            return; // 根目录恒存在，视为幂等成功
        }
        String parent = StoragePaths.parent(logical);
        String parentFileId = folderIdOf(parent);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("parentFileId", parentFileId);
        body.put("name", StoragePaths.name(logical));
        body.put("description", "");
        body.put("type", "folder");
        body.put("fileRenameMode", "force_rename");
        Map<String, Object> data = transport.post("/file/create", body, dir);
        String createdId = CmCloudTransport.str(data, "fileId");
        if (createdId != null && !Strs.isBlank(createdId)) {
            idCache.put(logical, createdId);
        }
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String logical = StoragePaths.normalize(path);
        if (logical.isEmpty()) {
            throw StorageException.invalidConfig("上传目标不能是存储根目录：" + path);
        }
        String parent = StoragePaths.parent(logical);
        String parentFileId = folderIdOf(parent);
        String name = StoragePaths.name(logical);

        // 平台契约需要确切的 size 与整体 SHA-256：先落盘缓冲再上传，
        // 摘要与大小都算准了才发起 create，避免「传到一半才发现对不上」
        Path buffer = null;
        try {
            buffer = Files.createTempFile("jkit-yun139-", ".upload");
            long actual;
            String contentHash;
            try (InputStream source = in) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                actual = copyWithDigest(source, buffer, digest);
                contentHash = Strs.hex(digest.digest());
            }
            if (actual != size && size >= 0) {
                throw new StorageException(StorageErrorCode.CONFLICT,
                        "上传流实际大小 " + actual + " 字节与声明大小 " + size + " 不一致，已中止", path);
            }
            doUpload(logical, name, parentFileId, buffer, actual, contentHash);
        } catch (NoSuchAlgorithmException e) {
            throw StorageException.invalidConfig("JVM 不支持 SHA-256 摘要算法");
        } catch (IOException e) {
            throw StorageException.io("上传缓冲写入失败：" + e.getMessage(), path, e);
        } finally {
            if (buffer != null) {
                try {
                    Files.deleteIfExists(buffer);
                } catch (IOException ignored) {
                    // 临时文件删除失败不影响上传结果
                }
            }
        }
    }

    /** 创建上传任务 → 逐片 PUT 预签名地址 → complete 校验（秒传 / exist 命中时跳过传输）。 */
    private void doUpload(String logical, String name, String parentFileId, Path buffer,
                          long size, String contentHash) {
        if (size <= 0) {
            // 分片契约要求 partSize 为正整数、请求体非空：0 字节文件无法按契约表达
            throw new StorageException(StorageErrorCode.CONFLICT,
                    "139 云盘不支持 0 字节文件上传：" + logical, logical);
        }
        List<long[]> parts = planParts(size);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("contentHash", contentHash);
        body.put("contentHashAlgorithm", Yun139Profile.HASH_ALGORITHM);
        body.put("contentType", Yun139Profile.CONTENT_TYPE_OCTET);
        body.put("parallelUpload", false);
        body.put("partInfos", partInfosBody(parts, MAX_PARTS_PER_CREATE));
        body.put("size", size);
        body.put("parentFileId", parentFileId);
        body.put("name", name);
        body.put("type", "file");
        body.put("fileRenameMode", "auto_rename");
        Map<String, Object> data = transport.post("/file/create", body, logical);

        String fileId = CmCloudTransport.str(data, "fileId");
        // 秒传 / 云端已存在相同内容：任务即告完成，不再传输、不再 complete
        if (Boolean.parseBoolean(CmCloudTransport.str(data, "rapidUpload"))
                || Boolean.parseBoolean(CmCloudTransport.str(data, "exist"))) {
            return;
        }
        String uploadId = CmCloudTransport.str(data, "uploadId");
        // create 已返回前 100 片地址；缺失的（大文件超出部分）按 100 片一批补取
        Map<Integer, String> urlByNumber = uploadUrlsOf(data);
        try (InputStream in = Files.newInputStream(buffer)) {
            for (long[] part : parts) {
                int number = (int) part[0];
                long partSize = part[1];
                byte[] blob = readExactly(in, partSize);
                if (blob.length != partSize) {
                    throw new StorageException(StorageErrorCode.IO_ERROR,
                            "本地文件在上传中途被修改，分片 " + number + " 读取不完整，已中止", logical);
                }
                String url = urlByNumber.get(number);
                if (url == null) {
                    url = fetchPartUrl(fileId, uploadId, number, partSize, logical);
                }
                transport.putPart(url, blob, logical);
            }
        } catch (IOException e) {
            throw StorageException.io("读取上传缓冲文件失败：" + e.getMessage(), logical, e);
        }
        Map<String, Object> completeBody = CmCloudTransport.body();
        completeBody.put("contentHash", contentHash);
        completeBody.put("contentHashAlgorithm", Yun139Profile.HASH_ALGORITHM);
        completeBody.put("fileId", fileId);
        completeBody.put("uploadId", uploadId);
        transport.post("/file/complete", completeBody, logical);
    }

    /**
     * 分片计划：每片 {@code config.partSize()}（默认 20MiB、不超过 EOS 单对象上限），
     * 返回 {@code [{partNumber, partSize}, ...]}。
     */
    private List<long[]> planParts(long size) {
        long partSize = config.partSize();
        List<long[]> parts = new ArrayList<>();
        long offset = 0;
        int number = 1;
        while (offset < size) {
            long chunk = Math.min(partSize, size - offset);
            parts.add(new long[]{number, chunk});
            offset += chunk;
            number++;
        }
        return parts;
    }

    /** create 请求体的 partInfos（携带 parallelHashCtx.partOffset，网页版同款）。 */
    private static List<Map<String, Object>> partInfosBody(List<long[]> parts, int limit) {
        List<Map<String, Object>> infos = new ArrayList<>();
        int count = Math.min(parts.size(), limit);
        long offset = 0;
        for (int i = 0; i < count; i++) {
            long[] part = parts.get(i);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("partNumber", (int) part[0]);
            info.put("partSize", part[1]);
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("partOffset", offset);
            info.put("parallelHashCtx", ctx);
            infos.add(info);
            offset += part[1];
        }
        return infos;
    }

    /** 从 file/create 响应收集「分片序号 → 预签名上传地址」。 */
    private static Map<Integer, String> uploadUrlsOf(Map<String, Object> data) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (data.get("partInfos") instanceof List) {
            List<?> parts = (List<?>) data.get("partInfos");
            for (Object entry : parts) {
                if (!(entry instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) entry;
                Object partNumber = map.get("partNumber");
                Object uploadUrl = map.get("uploadUrl");
                if (partNumber instanceof Number && uploadUrl instanceof String
                        && !Strs.isBlank((String) uploadUrl)) {
                    result.put(((Number) partNumber).intValue(), (String) uploadUrl);
                }
            }
        }
        return result;
    }

    /** 补取缺失分片的预签名地址（file/getUploadUrl，100 片一批，带公共账号信息）。 */
    private String fetchPartUrl(String fileId, String uploadId, int partNumber, long partSize,
                                String logical) {
        if (config.account().isEmpty()) {
            throw StorageException.io("平台未返回分片 " + partNumber + " 的上传地址，且未配置"
                    + "登录账号（补取地址需要），请补填「登录账号」后重试", logical, null);
        }
        Map<String, Object> commonAccountInfo = new LinkedHashMap<>();
        commonAccountInfo.put("account", config.account());
        commonAccountInfo.put("accountType", 1);
        Map<String, Object> partInfo = new LinkedHashMap<>();
        partInfo.put("partNumber", partNumber);
        partInfo.put("partSize", partSize);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileId", fileId);
        body.put("uploadId", uploadId);
        body.put("partInfos", Arrays.asList(partInfo));
        body.put("commonAccountInfo", commonAccountInfo);
        Map<String, Object> data = transport.post("/file/getUploadUrl", body, logical);
        String url = uploadUrlsOf(data).get(partNumber);
        if (url == null) {
            throw StorageException.io("平台未返回分片 " + partNumber + " 的上传地址", logical, null);
        }
        return url;
    }

    /** 从流里读满 length 字节（不足即文件被改动，返回短读由调用方报错）。 */
    private static byte[] readExactly(InputStream in, long length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(chunk, 0, (int) Math.min(chunk.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(chunk, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    /** 流式落盘并顺带计算摘要，返回实际字节数。 */
    private static long copyWithDigest(InputStream in, Path target, MessageDigest digest)
            throws IOException {
        long total = 0;
        byte[] chunk = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                digest.update(chunk, 0, read);
                out.write(chunk, 0, read);
                total += read;
            }
        }
        return total;
    }

    @Override
    public void download(String path, OutputStream out) {
        try (InputStream in = openDownload(path, 0)) {
            Io.copy(in, out);
        } catch (IOException e) {
            throw StorageException.io("下载失败：" + e.getMessage(), path, e);
        }
    }

    @Override
    public boolean supportsRange() {
        // 预签名下载地址（EOS）支持 Range 请求（断点续传）
        return true;
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        try (InputStream in = openDownload(path, offset)) {
            Io.copy(in, out);
        } catch (IOException e) {
            throw StorageException.io("断点续传下载失败：" + e.getMessage(), path, e);
        }
    }

    /** 取预签名下载地址并打开输入流。 */
    private InputStream openDownload(String path, long offset) {
        String logical = StoragePaths.normalize(path);
        if (logical.isEmpty()) {
            throw StorageException.invalidConfig("下载目标不能是存储根目录：" + path);
        }
        String url = downloadUrlOf(logical, path);
        return transport.openDownload(url, offset, path);
    }

    @Override
    public String presign(String path, java.time.Duration ttl) {
        // 新个人云的 getDownloadUrl 不接受自定义有效期（平台固定过期时间），ttl 仅作兼容参数
        String logical = StoragePaths.normalize(path);
        if (logical.isEmpty()) {
            throw StorageException.invalidConfig("分享目标不能是存储根目录：" + path);
        }
        return downloadUrlOf(logical, path);
    }

    /** {@code file/getDownloadUrl} → 优先 cdnUrl，回退 url。 */
    private String downloadUrlOf(String logical, String display) {
        String fileId = fileIdOf(logical, display);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileId", fileId);
        Map<String, Object> data = transport.post("/file/getDownloadUrl", body, display);
        String url = CmCloudTransport.str(data, "cdnUrl");
        if (url == null || Strs.isBlank(url)) {
            url = CmCloudTransport.str(data, "url");
        }
        if (url == null || Strs.isBlank(url)) {
            throw StorageException.io("平台未返回下载地址", display, null);
        }
        return url;
    }

    @Override
    public void delete(String path) {
        String logical = StoragePaths.normalize(path);
        if (logical.isEmpty()) {
            throw StorageException.invalidConfig("delete 拒绝删除存储根目录，请指定具体路径");
        }
        String fileId = fileIdOf(logical, path);
        // 回收站删除（batchTrash）：整树可恢复，比永久删除安全；恢复走网页端回收站
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileIds", Arrays.asList(fileId));
        transport.post("/recyclebin/batchTrash", body, logical);
        idCache.remove(logical);
    }

    @Override
    public void deleteDir(String path) {
        if (StoragePaths.normalize(path).isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        // batchTrash 对目录是整树删除（进回收站），不必逐文件 walk
        delete(path);
    }

    @Override
    public void copy(String source, String target) {
        String srcLogical = StoragePaths.normalize(source);
        String dstLogical = StoragePaths.normalize(target);
        if (srcLogical.isEmpty() || dstLogical.isEmpty()) {
            throw StorageException.invalidConfig("复制源与目标都不能是存储根目录");
        }
        String srcName = StoragePaths.name(srcLogical);
        String dstName = StoragePaths.name(dstLogical);
        if (!srcName.equals(dstName)) {
            // batchCopy 只接受 toParentFileId，不支持改目标名
            throw StorageException.unsupported("139 云盘的复制不支持改名：目标名须与源名相同（"
                    + srcName + "）");
        }
        String srcFileId = fileIdOf(srcLogical, source);
        String dstParentId = folderIdOf(StoragePaths.parent(dstLogical));
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileIds", Arrays.asList(srcFileId));
        body.put("toParentFileId", dstParentId);
        transport.post("/file/batchCopy", body, target);
        // 冲突处理仅平台默认（可能自动改名）：复制结果以目标目录刷新后的实际内容为准
        idCache.remove(dstLogical);
    }

    @Override
    public void rename(String source, String target) {
        String srcLogical = StoragePaths.normalize(source);
        String dstLogical = StoragePaths.normalize(target);
        if (srcLogical.isEmpty()) {
            throw StorageException.invalidConfig("rename 拒绝操作存储根目录：" + source);
        }
        String fileId = fileIdOf(srcLogical, source);
        String srcParent = StoragePaths.parent(srcLogical);
        String dstParent = StoragePaths.parent(dstLogical);
        String srcName = StoragePaths.name(srcLogical);
        String dstName = StoragePaths.name(dstLogical);
        // 跨目录：先移动（batchMove 保持原名），同目录或改名需求走 update
        if (!srcParent.equals(dstParent)) {
            Map<String, Object> moveBody = CmCloudTransport.body();
            moveBody.put("fileIds", Arrays.asList(fileId));
            moveBody.put("toParentFileId", folderIdOf(dstParent));
            transport.post("/file/batchMove", moveBody, target);
            idCache.remove(srcLogical);
            idCache.put(dstLogical, fileId);
        }
        if (!srcName.equals(dstName)) {
            Map<String, Object> updateBody = CmCloudTransport.body();
            updateBody.put("fileId", fileId);
            updateBody.put("name", dstName);
            updateBody.put("description", "");
            transport.post("/file/update", updateBody, target);
        }
    }

    @Override
    public String absolutePathOf(String path) {
        return config.cloudPath(path);
    }

    // ------------------------------------------------------------------ ID 解析

    /**
     * 逻辑路径 → 平台文件 ID（带缓存；目录解析用 {@link #folderIdOf}，文件走 stat 补查）。
     *
     * @throws StorageException NOT_FOUND 路径不存在
     */
    private String fileIdOf(String logical, String display) {
        String cached = idCache.get(logical);
        if (cached != null) {
            return cached;
        }
        StorageEntry entry = stat(display);
        // 文件 / 目录一律优先取列表时缓存的 fileId 属性：目录条目没有 etag（只有文件带）
        String fileId = entry.attribute(ATTR_Y139_FILE_ID);
        if (fileId == null || Strs.isBlank(fileId)) {
            fileId = entry.etag();
        }
        if (fileId == null || Strs.isBlank(fileId)) {
            throw StorageException.io("平台未返回 " + logical + " 的文件 ID", display, null);
        }
        idCache.put(logical, fileId);
        return fileId;
    }

    /**
     * 逻辑目录路径 → 平台目录 ID：根映射为平台约定的 {@code "/"}，其余按段自根向下逐层
     * 列目录解析（先解析起始目录 rootSegments，再逐层解析逻辑路径；顺带缓存每一层前缀）。
     *
     * @throws StorageException NOT_FOUND 某一层目录不存在 / 路径中有文件占位
     */
    private String folderIdOf(String logical) {
        String normalized = StoragePaths.normalize(logical);
        if (normalized.isEmpty()) {
            return Yun139Profile.PERSONAL_ROOT_ID;
        }
        String cached = idCache.get(normalized);
        if (cached != null) {
            return cached;
        }
        List<String> segments = Arrays.asList(normalized.split("/", -1));
        String currentId = Yun139Profile.PERSONAL_ROOT_ID;
        String resolved = "";
        for (String segment : config.rootSegments()) {
            currentId = childFolderId(currentId, segment);
            resolved = resolved.isEmpty() ? segment : resolved + "/" + segment;
            idCache.put(resolved, currentId);
        }
        for (String segment : segments) {
            currentId = childFolderId(currentId, segment);
            resolved = resolved.isEmpty() ? segment : resolved + "/" + segment;
            idCache.put(resolved, currentId);
        }
        return currentId;
    }

    /** 在目录 {@code parentId} 下按名字找子目录 ID（不存在或不是目录 → NOT_FOUND）。 */
    private String childFolderId(String parentId, String name) {
        for (Map<String, Object> item : listChildren(parentId, name)) {
            if (name.equals(CmCloudTransport.str(item, "name"))) {
                if (!"folder".equalsIgnoreCase(CmCloudTransport.str(item, "type"))) {
                    throw new StorageException(StorageErrorCode.NOT_FOUND,
                            "路径中存在同名文件，无法作为目录：" + name, name);
                }
                String id = CmCloudTransport.str(item, "fileId");
                if (id == null || Strs.isBlank(id)) {
                    throw StorageException.io("平台未返回目录 " + name + " 的文件 ID", name, null);
                }
                return id;
            }
        }
        throw StorageException.notFound(name);
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
        return "Yun139FileStorage(" + config + ")";
    }
}
