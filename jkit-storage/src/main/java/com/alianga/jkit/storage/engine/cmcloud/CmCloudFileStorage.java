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
import java.time.Duration;
import java.time.Instant;
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
 * 中国移动云盘（个人云 / personalSaas V3 接口）文件存储：浏览、下载、上传、建目录、
 * 删除、复制与预签名分享链接。
 *
 * <p>与 SVN / Git 引擎同一套接入方式：实现 {@link FileStorage}，由
 * {@link CmCloudStorageSupport#register()} 注册到 {@code FileStorages}，
 * 界面侧平台卡片、连接表单、测试连接自动可用，无需特判。</p>
 *
 * <p><b>ID 映射</b>：移动云盘的文件操作全部以平台文件 ID（{@code fileId}）寻址，
 * 而本工具的浏览 / 同步 / 迁移都以逻辑路径寻址。引擎在实例内维护「逻辑路径 → fileId」
 * 缓存：根目录对应平台约定的 {@code "/"}（个人云根），其余按段逐层列目录解析；
 * 实例由 {@code Sessions.call} 每次操作新建，缓存随实例生灭，不跨操作残留，
 * 平台侧改名 / 删除导致的缓存失配只影响当前这一次操作。</p>
 *
 * <p><b>能力边界（按平台接口现状，不假装支持）</b>：</p>
 * <ul>
 *   <li>上传走「创建任务 → 逐片 PUT 预签名地址 → complete 校验」三步：分片 5MiB 起
 *   （非最后片不得小于 5MiB，最多 100 片），PUT 显式携带 octet-stream / GMT Date /
 *   Content-Length（平台二阶段上传契约，缺任一项都会被对象存储拒绝）；
 *   平台按 {@code force_rename} 处理同名（服务端自动改名保留双方），文件名以
 *   complete 后实际状态为准；0 字节文件平台契约不支持，明确报错。</li>
 *   <li>删除走异步任务接口（{@code batchDeleteAsync}），对目录是整树删除；
 *   任务轮询超时按「结果未知」报冲突，不谎报成功。</li>
 *   <li>重命名 / 移动接口未对本工具开放：明确抛 UNSUPPORTED。</li>
 *   <li>复制走同步批量复制接口，不支持改名（目标名必须与源相同）。</li>
 *   <li>写操作（上传 / 建目录 / 复制）落点受平台授权范围约束：只能落在智能体专属目录
 *   及其子目录内，越界时平台返回 16110013，引擎翻译为可读的 AUTH_FAILED。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public class CmCloudFileStorage implements FileStorage {
    /** 条目属性：平台文件 ID（删除 / 复制等操作的真实寻址键）。 */
    public static final String ATTR_CM_FILE_ID = "cmcloud.fileId";
    /** 条目属性：云端绝对路径（含起始目录前缀，展示用）。 */
    public static final String ATTR_CM_CLOUD_PATH = "cmcloud.cloudPath";

    /** 列目录单页大小（平台上限 100，取满减少翻页次数）。 */
    private static final int PAGE_SIZE = 100;
    /** 单目录翻页上限：防止平台游标异常时死循环。 */
    private static final int MAX_PAGES = 1000;
    /** 异步删除任务轮询总时长与间隔。 */
    private static final long DELETE_TIMEOUT_MS = 60_000L;
    private static final long DELETE_POLL_INTERVAL_MS = 1_000L;
    /** 上传分片大小（平台契约：非最后分片不得小于 5MiB；file/create 单次最多 100 片）。 */
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PARTS_PER_CREATE = 100;
    private static final int MAX_PART_NUMBER = 10_000;

    private final CmCloudConfig config;
    private final CmCloudTransport transport;

    /** 逻辑路径 → 平台 fileId 缓存（根路径 "" 固定映射 "/"；实例级，随操作生灭）。 */
    private final Map<String, String> idCache = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /**
     * 引擎工厂入口（由 {@link CmCloudStorageSupport#register()} 注册到 {@code FileStorages}）。
     *
     * @param storageConfig 连接配置
     * @throws StorageException 配置非法（缺票据 / 地址非法等）
     */
    public CmCloudFileStorage(StorageConfig storageConfig) {
        this.config = CmCloudConfig.from(storageConfig);
        this.transport = new CmCloudTransport();
    }

    @Override
    public String platform() {
        return CmCloudConfig.PLATFORM_ID;
    }

    /** @return 本实例的连接配置（诊断用） */
    public CmCloudConfig sourceConfig() {
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

    /**
     * 列一个目录的全部直接子项（翻页取尽）。
     *
     * @param folderId       平台目录 ID
     * @param logical        逻辑路径（异常文案用）
     * @return 子项原始对象列表
     */
    private List<Map<String, Object>> listChildren(String folderId, String logical) {
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = CmCloudTransport.body();
            body.put("parentFileId", folderId);
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            pageInfo.put("pageSize", PAGE_SIZE);
            if (cursor != null) {
                pageInfo.put("pageCursor", cursor);
            }
            body.put("pageInfo", pageInfo);
            Map<String, Object> data = transport.postJson(config, "/file/list", body, logical);
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

    /** 原始子项 → 显示条目（属性里带 fileId，供删除 / 复制与详情面板消费）。 */
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
                : StorageEntry.file(childPath, CmCloudTransport.num(item, "size", StorageEntry.SIZE_UNKNOWN),
                        mtime, fileId);
        if (fileId != null && !Strs.isBlank(fileId)) {
            result = result.withAttribute(ATTR_CM_FILE_ID, fileId);
        }
        result = result.withAttribute(ATTR_CM_CLOUD_PATH, config.cloudPath(childPath));
        return result;
    }

    /** 平台时间字段 → epoch 毫秒；拿不到给 {@link StorageEntry#MTIME_UNKNOWN}。 */
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
                return Instant.parse(trimmed).toEpochMilli();
            } catch (RuntimeException ignored) {
                // 非法时间格式：按未知处理，不让整列目录失败
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
        String name = StoragePaths.name(logical);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("name", name);
        body.put("parentFileId", parentFileId);
        Map<String, Object> data = transport.postJson(config, "/file/createFolder", body, dir);
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

        // 平台契约需要确切的 size 与整体 SHA-256（或 SHA-1）摘要：先落盘缓冲再上传，
        // 摘要与大小都算准了才发起 create，避免「传到一半才发现对不上」
        Path buffer = null;
        try {
            buffer = Files.createTempFile("jkit-cmcloud-", ".upload");
            long actual;
            String contentHash;
            try (InputStream source = in) {
                MessageDigest digest = MessageDigest.getInstance(config.hashAlgorithm());
                actual = copyWithDigest(source, buffer, digest);
                contentHash = Strs.hex(digest.digest());
            }
            if (actual != size && size >= 0) {
                throw new StorageException(StorageErrorCode.CONFLICT,
                        "上传流实际大小 " + actual + " 字节与声明大小 " + size + " 不一致，已中止", path);
            }
            doUpload(logical, name, parentFileId, buffer, actual, contentHash);
        } catch (NoSuchAlgorithmException e) {
            throw StorageException.invalidConfig("JVM 不支持摘要算法 " + config.hashAlgorithm());
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

    /** 创建上传任务 → 逐片 PUT 预签名地址 → complete 校验（秒传时跳过传输）。 */
    private void doUpload(String logical, String name, String parentFileId, Path buffer,
                          long size, String contentHash) {
        if (size <= 0) {
            // 平台分片契约要求 partSize 为正整数、请求体非空：0 字节文件无法按契约表达
            throw new StorageException(StorageErrorCode.CONFLICT,
                    "中国移动云盘不支持 0 字节文件上传：" + logical, logical);
        }
        // 分片计划与平台 CLI 同口径：每片 5MiB 起、file/create 单次最多 100 片
        List<long[]> parts = planParts(size);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("name", name);
        body.put("type", "file");
        body.put("size", size);
        body.put("parentFileId", parentFileId);
        List<Map<String, Object>> partInfos = new ArrayList<>(parts.size());
        for (long[] part : parts) {
            Map<String, Object> partInfo = new LinkedHashMap<>();
            partInfo.put("partNumber", (int) part[0]);
            partInfo.put("partSize", part[1]);
            partInfos.add(partInfo);
        }
        body.put("partInfos", partInfos);
        // contentHash 在 create 就提交：平台用它判断秒传（rapidUpload），同名/秒传命中时
        // 不再建立传输任务（与平台 CLI 同口径；complete 再带一次作最终校验）
        body.put("contentHash", contentHash);
        body.put("contentHashAlgorithm", config.hashAlgorithm());
        Map<String, Object> data = transport.postJson(config, "/file/create", body, logical);

        String fileId = CmCloudTransport.str(data, "fileId");
        // 秒传：内容哈希命中平台已有文件，任务即告完成，不再传输、不再 complete
        if (Boolean.parseBoolean(CmCloudTransport.str(data, "rapidUpload"))) {
            return;
        }
        String uploadId = CmCloudTransport.str(data, "uploadId");
        // create 响应已给出全部分片地址（分片数恒 <= 100）；个别分片缺地址时用 getUploadUrl 补取
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
                transport.putUpload(url, blob, logical);
            }
        } catch (IOException e) {
            throw StorageException.io("读取上传缓冲文件失败：" + e.getMessage(), logical, e);
        }
        Map<String, Object> completeBody = CmCloudTransport.body();
        completeBody.put("fileId", fileId);
        completeBody.put("uploadId", uploadId);
        completeBody.put("contentHash", contentHash);
        completeBody.put("contentHashAlgorithm", config.hashAlgorithm());
        transport.postJson(config, "/file/complete", completeBody, logical);
    }

    /**
     * 分片计划：每片 5MiB 起（非最后片不得小于 5MiB）、最多 100 片 / 10000 号；
     * 返回 {@code [{partNumber, partSize}, ...]}。
     */
    private static List<long[]> planParts(long size) {
        long count = (size + MIN_PART_SIZE - 1) / MIN_PART_SIZE;
        count = Math.max(1, Math.min(count, Math.min(MAX_PARTS_PER_CREATE, MAX_PART_NUMBER)));
        long base = Math.max(MIN_PART_SIZE, (size + count - 1) / count);
        List<long[]> parts = new ArrayList<>();
        long offset = 0;
        int number = 1;
        while (offset < size && parts.size() < MAX_PART_NUMBER) {
            long partSize = Math.min(base, size - offset);
            parts.add(new long[]{number, partSize});
            offset += partSize;
            number++;
        }
        return parts;
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

    /** 补取缺失分片的预签名地址（file/getUploadUrl，分片数超出 create 单次上限时用）。 */
    private String fetchPartUrl(String fileId, String uploadId, int partNumber, long partSize,
                                String logical) {
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileId", fileId);
        body.put("uploadId", uploadId);
        Map<String, Object> partInfo = new LinkedHashMap<>();
        partInfo.put("partNumber", partNumber);
        partInfo.put("partSize", partSize);
        body.put("partInfos", Arrays.asList(partInfo));
        Map<String, Object> data = transport.postJson(config, "/file/getUploadUrl", body, logical);
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
        // 预签名下载地址支持 Range 请求（断点续传）
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
        String fileId = fileIdOf(logical, path);
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileId", fileId);
        body.put("expireSec", config.downloadExpireSec());
        Map<String, Object> data = transport.postJson(config, "/file/getDownloadUrl", body, path);
        String url = CmCloudTransport.str(data, "url");
        if (url == null || Strs.isBlank(url)) {
            throw StorageException.io("平台未返回下载地址", path, null);
        }
        return transport.openDownload(url, offset, path);
    }

    @Override
    public String presign(String path, Duration ttl) {
        String logical = StoragePaths.normalize(path);
        if (logical.isEmpty()) {
            throw StorageException.invalidConfig("分享目标不能是存储根目录：" + path);
        }
        String fileId = fileIdOf(logical, path);
        long expireSec = ttl == null || ttl.isNegative() || ttl.isZero()
                ? config.downloadExpireSec() : ttl.getSeconds();
        expireSec = Math.max(1, Math.min(86400, expireSec));
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileId", fileId);
        body.put("expireSec", expireSec);
        Map<String, Object> data = transport.postJson(config, "/file/getDownloadUrl", body, path);
        String url = CmCloudTransport.str(data, "url");
        if (url == null || Strs.isBlank(url)) {
            throw StorageException.io("平台未返回分享链接", path, null);
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
        deleteById(fileId, logical);
        idCache.remove(logical);
    }

    @Override
    public void deleteDir(String path) {
        if (StoragePaths.normalize(path).isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        // 平台的异步删除对目录是整树删除，不必逐文件 walk
        delete(path);
    }

    /**
     * 提交异步删除任务并轮询到终态。
     *
     * <p>删除接口是异步的：返回 taskId 后按 {@code task/get} 轮询。终态四种：
     * {@code Succeed} 成功；{@code Failed} / {@code PartialSucceed} 按失败上报并带平台
     * 消息；轮询超时按「结果未知」报 CONFLICT——不谎报成功，也不盲目重发删除请求。</p>
     *
     * @param fileId 平台文件 ID
     * @param path   逻辑路径（异常文案用）
     */
    private void deleteById(String fileId, String path) {
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileIds", Arrays.asList(fileId));
        Map<String, Object> data = transport.postJson(config, "/file/batchDeleteAsync", body, path);
        String taskId = CmCloudTransport.str(data, "taskId");
        if (taskId == null || Strs.isBlank(taskId)) {
            // 平台未返回任务 ID：要么同步完成（无契约承诺），要么请求未受理，只能按未知处理
            throw new StorageException(StorageErrorCode.CONFLICT,
                    "删除请求已提交但平台未返回任务号，结果未知，请刷新目录核对：" + path, path);
        }
        long deadline = System.currentTimeMillis() + DELETE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> pollBody = CmCloudTransport.body();
            pollBody.put("taskId", taskId);
            Map<String, Object> taskData = transport.postJson(config, "/task/get", pollBody, path);
            Map<String, Object> taskInfo = taskData.get("taskInfo") instanceof Map
                    ? castMap(taskData.get("taskInfo")) : Collections.emptyMap();
            String status = CmCloudTransport.str(taskInfo, "status");
            if ("Succeed".equalsIgnoreCase(status)) {
                return;
            }
            if ("Failed".equalsIgnoreCase(status) || "PartialSucceed".equalsIgnoreCase(status)) {
                String message = CmCloudTransport.str(taskInfo, "message");
                throw CmCloudErrors.business(CmCloudTransport.str(taskInfo, "code"),
                        "删除任务" + ("PartialSucceed".equalsIgnoreCase(status) ? "仅部分成功" : "失败")
                                + (message == null ? "" : "：" + message), path);
            }
            // Running：继续等
            try {
                Thread.sleep(DELETE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageException(StorageErrorCode.CANCELLED, "删除等待被中断：" + path, path, e);
            }
        }
        throw new StorageException(StorageErrorCode.CONFLICT,
                "删除任务在 " + (DELETE_TIMEOUT_MS / 1000) + " 秒内未完成，结果未知（taskId=" + taskId
                        + "），请稍后刷新目录核对，不要盲目重复删除", path);
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
            // 平台批量复制接口只接受 toParentFileId，不支持改目标名
            throw StorageException.unsupported("中国移动云盘的复制不支持改名：目标名须与源名相同（"
                    + srcName + "）");
        }
        String srcFileId = fileIdOf(srcLogical, source);
        String dstParentId = folderIdOf(StoragePaths.parent(dstLogical));
        Map<String, Object> body = CmCloudTransport.body();
        body.put("fileIds", Arrays.asList(srcFileId));
        body.put("toParentFileId", dstParentId);
        transport.postJson(config, "/file/batchCopySync", body, target);
        // 冲突处理仅平台默认（可能自动改名）：复制结果以目标目录刷新后的实际内容为准
        idCache.remove(dstLogical);
    }

    @Override
    public void rename(String source, String target) {
        // 平台未对本工具开放移动 / 改名接口（batchMoveAsync 未开放）：明确不支持，不绕路模拟
        throw StorageException.unsupported("中国移动云盘暂不支持重命名 / 移动（平台接口未开放），"
                + "可改用「复制到目标目录后删除源」的方式");
    }

    @Override
    public String absolutePathOf(String path) {
        return config.cloudPath(path);
    }

    // ------------------------------------------------------------------ ID 解析

    /**
     * 逻辑路径 → 平台文件 ID（带缓存；目录解析用 {@link #folderIdOf}，文件走 stat 补查）。
     *
     * @param logical 逻辑路径
     * @return 平台文件 ID
     * @throws StorageException NOT_FOUND 路径不存在
     */
    private String fileIdOf(String logical, String display) {
        String cached = idCache.get(logical);
        if (cached != null) {
            return cached;
        }
        StorageEntry entry = stat(display);
        // 文件 / 目录一律优先取列表时缓存的 fileId 属性：目录条目没有 etag（只有文件带），
        // 只读 etag 会让「删除目录」一律报「平台未返回文件 ID」
        String fileId = entry.attribute(ATTR_CM_FILE_ID);
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
     * 逻辑目录路径 → 平台目录 ID：根映射为平台约定的 {@code "/"}，
     * 其余按段自根向下逐层列目录解析（解析过程顺带缓存每一层前缀）。
     *
     * @param logical 逻辑目录路径（根为空串）
     * @param display 出错文案用原始路径
     * @return 平台目录 ID
     * @throws StorageException NOT_FOUND 某一层目录不存在 / 路径中有文件占位
     */
    private String folderIdOf(String logical) {
        String normalized = StoragePaths.normalize(logical);
        if (normalized.isEmpty()) {
            return CmCloudConfig.PERSONAL_ROOT_ID;
        }
        String cached = idCache.get(normalized);
        if (cached != null) {
            return cached;
        }
        List<String> segments = Arrays.asList(normalized.split("/", -1));
        // 先解析起始目录（配置的 rootSegments）：它是逻辑根的云端落点
        String currentId = CmCloudConfig.PERSONAL_ROOT_ID;
        String resolved = "";
        for (String segment : config.rootSegments()) {
            currentId = childFolderId(currentId, segment);
            resolved = resolved.isEmpty() ? segment : resolved + "/" + segment;
            idCache.put(resolved, currentId);
        }
        // 再逐层解析逻辑路径
        for (String segment : segments) {
            currentId = childFolderId(currentId, segment);
            resolved = resolved.isEmpty() ? segment : resolved + "/" + segment;
            idCache.put(resolved, currentId);
        }
        return currentId;
    }

    /**
     * 在目录 {@code parentId} 下按名字找子目录 ID。
     *
     * @param parentId 父目录 ID
     * @param name     子目录名
     * @return 子目录 ID
     * @throws StorageException NOT_FOUND 名字不存在或不是目录
     */
    private String childFolderId(String parentId, String name) {
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = CmCloudTransport.body();
            body.put("parentFileId", parentId);
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            pageInfo.put("pageSize", PAGE_SIZE);
            if (cursor != null) {
                pageInfo.put("pageCursor", cursor);
            }
            body.put("pageInfo", pageInfo);
            Map<String, Object> data = transport.postJson(config, "/file/list", body, name);
            if (data.get("items") instanceof List) {
                List<?> batch = (List<?>) data.get("items");
                for (Object entry : batch) {
                    if (!(entry instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> item = castMap(entry);
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
            }
            String next = CmCloudTransport.str(data, "nextPageCursor");
            if (next == null || Strs.isBlank(next) || next.equals(cursor)) {
                throw StorageException.notFound(name);
            }
            cursor = next;
        }
        throw new StorageException(StorageErrorCode.SERVER_ERROR,
                "目录解析翻页超过 " + MAX_PAGES + " 页仍未取尽，已中止以防死循环", name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    // ------------------------------------------------------------------ 其它

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
        return "CmCloudFileStorage(" + config + ")";
    }
}
