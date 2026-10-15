package com.alianga.jkit.storage.engine;

import com.alianga.jkit.notify.Attachment;
import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.MailMime;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件（IMAP）存储引擎：把邮箱文件夹当成文件盘（二期 Plan A）。
 *
 * <p><b>路径模型</b>：{@code basePath} 为 IMAP 根邮箱；逻辑目录映射为真 IMAP 子文件夹；
 * 文件 Subject/附件名为路径最后一段（如 {@code docs/a.txt} → 文件夹 {@code basePath/docs}、
 * Subject {@code a.txt}）。{@link MailGateway#mkdir(String)} 执行 CREATE。</p>
 *
 * <p><b>约定</b>：一个逻辑文件 ↔ 一封 MIME（单 {@link Attachment}），或分卷时多封
 * {@code name.part1}..{@code name.partN}（N 从 1 起、连续无缺口）；二期不做「一封邮件多文件」。
 * 逻辑名本身<strong>不得</strong>以 {@code .part}+数字结尾——该后缀为分卷保留；
 * 上传此类路径会 {@link StorageErrorCode#INVALID_CONFIG}。若底层已误存为 {@code foo.partN}，
 * 则按逻辑名 {@code foo} 的分卷片处理，不再当作独立文件名。</p>
 *
 * <p><b>分卷</b>：超过 {@code maxAttachmentBytes} 时用 {@link Attachment#slice} 拆分；
 * {@code list} 隐藏 part、对外逻辑名与合计大小；{@code download} 按序合成前先校验
 * part 从 1 起连续无缺口，缺片/有缺口时 {@link StorageErrorCode#CONFLICT}，不向输出流写入；
 * {@code delete} 清掉所有已发现的 part（含残缺分卷）。</p>
 *
 * <p><b>连接</b>：{@code channelPool}（默认 1，协议层上限 4）；实例级串行，
 * 网关内按池大小借还 IMAP 会话。</p>
 *
 * <p>上传：同名先删再 {@code APPEND}（含全部分卷）；每封 APPEND 后按 APPENDUID / Message-ID
 * 回查 {@code X-Jkit-Size} 与附件 Base64 段长，不符即删并抛 IO_ERROR；分卷中途失败回滚已写片。
 * list/stat 大小取 {@code X-Jkit-Size}（遗留邮件按 Base64 段估算），不用 RFC822.SIZE
 * （QQ 实测对大邮件也只报约 900B）。</p>
 *
 * <p><b>已知限制</b>：宿主若使用 jkit-notify 2.0.4，纯 ASCII Subject 仍会被 RFC2047 编码
 * （2.0.5 起不编码）；读取侧已解码 encoded-word，按明文路径查找不受影响，但在邮箱客户端里
 * 看到的原始 Subject 为编码形式。</p>
 *
 * @author 郑明亮
 */
public class MailFileStorage implements FileStorage {
    /** 默认单附件上限 39MiB：QQ 2026-10-15 实测整封约 50MB，取 -20% 余量。 */
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 39L * 1024L * 1024L;
    /** 未配置 basePath 时的默认 IMAP 文件夹。 */
    public static final String DEFAULT_FOLDER = "jkit-mail-disk";
    /** 历史空目录占位名；mkdir 已改真 CREATE，不再写入，list 仍隐藏遗留 {@code .keep}。 */
    public static final String KEEP_NAME = ".keep";
    /** {@code name.partN}（N 从 1 起）。 */
    private static final Pattern PART_PATTERN = Pattern.compile("^(.*)\\.part(\\d+)$");

    private final MailGateway gateway;
    private final String folder;
    private final long maxAttachmentBytes;
    private final int channelPool;
    /** 实例级串行；网关侧可按 channelPool 持有多会话。 */
    private final Object lock = new Object();

    /**
     * 由配置创建并连接（生产路径，使用 IMAP 真实实现）。
     *
     * @param config 连接配置
     */
    public MailFileStorage(StorageConfig config) {
        this(config, newDefaultGateway());
    }

    /**
     * 可注入网关的包内构造（单测用 Fake）。
     *
     * @param config  连接配置
     * @param gateway 邮件网关
     */
    MailFileStorage(StorageConfig config, MailGateway gateway) {
        if (config == null) {
            throw StorageException.invalidConfig("MailFileStorage 配置不能为 null");
        }
        if (gateway == null) {
            throw StorageException.invalidConfig("MailGateway 不能为 null");
        }
        this.gateway = gateway;
        this.folder = config.getOr("basePath", DEFAULT_FOLDER);
        this.maxAttachmentBytes = config.longOr("maxAttachmentBytes", DEFAULT_MAX_ATTACHMENT_BYTES);
        this.channelPool = (int) Math.max(1L, config.longOr("channelPool", 1L));
        this.gateway.connect(config.asMap());
    }

    private static MailGateway newDefaultGateway() {
        return new DefaultMailGateway();
    }

    @Override
    public String platform() {
        return "mail";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        synchronized (lock) {
            Resolved resolved = resolveLogical(normalized);
            if (resolved == null) {
                return false;
            }
            ensureCompleteParts(resolved, normalized);
            return true;
        }
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        synchronized (lock) {
            Resolved resolved = resolveLogical(normalized);
            if (resolved == null) {
                throw StorageException.notFound(normalized);
            }
            ensureCompleteParts(resolved, normalized);
            return resolved.toStorageEntry(normalized);
        }
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        synchronized (lock) {
            if (!normalized.isEmpty()) {
                Resolved dirHit = resolveLogical(normalized);
                if (dirHit == null || !dirHit.directory) {
                    throw StorageException.notFound(normalized);
                }
            }
            return listChildren(normalized);
        }
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Resolved existing = resolveLogical(normalized);
            if (existing != null) {
                if (existing.directory) {
                    return;
                }
                throw StorageException.conflict("同名文件已存在，无法创建目录: " + normalized, normalized);
            }
            // 真 IMAP CREATE（fada3e0 协议层）；空目录不再写 .keep 占位
            dirCache.clear();
            gateway.mkdir(normalized);
        }
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String name = requireFilePath(path);
        byte[] payload = readPayload(in, size, name);
        synchronized (lock) {
            Resolved existing = resolveLogical(name);
            if (existing != null && existing.directory) {
                throw StorageException.conflict("目标是目录，无法上传文件: " + name, name);
            }
            if (existing != null && existing.foreign) {
                throw foreignReadOnly(name);
            }
            deleteExact(name);
            long partRaw = MailChunking.partRawBytes(maxAttachmentBytes);
            if (payload.length <= partRaw) {
                appendMime(name, payload, -1L);
                return;
            }
            Attachment whole = Attachment.of(name, payload);
            List<String> written = new ArrayList<String>();
            long offset = 0L;
            int partNo = 1;
            try {
                while (offset < payload.length) {
                    long chunk = Math.min(partRaw, payload.length - offset);
                    String partName = name + ".part" + partNo;
                    Attachment part = whole.slice(offset, chunk, partName);
                    appendMime(partName, part.content(), payload.length);
                    written.add(partName);
                    offset += chunk;
                    partNo++;
                }
            } catch (RuntimeException e) {
                // 中途失败：回滚已写分卷，避免残缺文件
                for (String w : written) {
                    try {
                        deleteQuiet(w);
                    } catch (RuntimeException ignored) {
                        // 尽力回滚
                    }
                }
                throw e;
            }
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        String name = requireFilePath(path);
        if (out == null) {
            throw StorageException.invalidConfig("download 输出流不能为 null");
        }
        synchronized (lock) {
            Resolved resolved = resolveLogical(name);
            if (resolved == null) {
                throw StorageException.notFound(name);
            }
            if (resolved.directory) {
                throw StorageException.conflict("目标是目录，无法按文件下载: " + name, name);
            }
            ensureCompleteParts(resolved, name);
            try {
                if (resolved.parts.isEmpty()) {
                    gateway.download(name, out);
                } else {
                    for (String partName : resolved.parts) {
                        gateway.download(partName, out);
                    }
                }
            } catch (StorageException e) {
                throw e;
            } catch (RuntimeException e) {
                throw StorageException.io("下载失败: " + name, name, e);
            }
        }
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        synchronized (lock) {
            Resolved resolved = resolveLogical(normalized);
            if (resolved == null) {
                return;
            }
            if (resolved.directory) {
                deleteUnder(normalized);
                return;
            }
            if (resolved.foreign) {
                throw foreignReadOnly(normalized);
            }
            deleteExact(normalized);
        }
    }

    @Override
    public void copy(String src, String dst) {
        throw StorageException.unsupported("邮件存储暂不支持 copy");
    }

    @Override
    public void rename(String src, String dst) {
        throw StorageException.unsupported("邮件存储暂不支持 rename");
    }

    @Override
    public void close() {
        synchronized (lock) {
            gateway.close();
        }
    }

    /** 测试/诊断：登记的通道池上限（二期仍串行）。 */
    int channelPool() {
        return channelPool;
    }

    /** 一页文件（IMAP 服务器端按 UID 降序分页）。 */
    public static final class Page {
        /** 本页条目（第 0 页含子目录）。 */
        public final List<StorageEntry> entries;
        /** 文件夹邮件总封数（一封外来邮件可能对应多个附件；未知 -1）。 */
        public final long totalMails;
        /** 页号，从 0 起。 */
        public final int page;
        /** 每页封数。 */
        public final int size;

        Page(List<StorageEntry> entries, long totalMails, int page, int size) {
            this.entries = entries;
            this.totalMails = totalMails;
            this.page = page;
            this.size = size;
        }

        /** @return 是否还有下一页 */
        public boolean hasMore() {
            return totalMails >= 0L && size > 0 && (long) (page + 1) * size < totalMails;
        }
    }

    /**
     * 分页列目录：只取一页邮件（默认每页 100 封），用于「加载更多」。
     *
     * @param dir  目录
     * @param page 页号，从 0 起
     * @param size 每页封数
     * @return 一页
     */
    public Page listPage(String dir, int page, int size) {
        String normalized = StoragePaths.normalize(dir);
        synchronized (lock) {
            MailGateway.MailPage mp = gateway.listDirPage(normalized, page, size);
            // 已加载的页并入短缓存，后续 stat / 下载不再重扫
            CachedDir prev = dirCache.get(normalized);
            List<MailEntry> merged = new ArrayList<MailEntry>();
            if (page > 0 && prev != null) {
                merged.addAll(prev.entries);
            }
            merged.addAll(mp.entries);
            dirCache.put(normalized, new CachedDir(merged, System.currentTimeMillis(),
                    mp.total >= 0L && size > 0 && (long) (page + 1) * size < mp.total));
            return new Page(listChildren(normalized, indexOf(mp.entries)), mp.total, mp.page, mp.size);
        }
    }

    private List<StorageEntry> listChildren(String dir) {
        return listChildren(dir, buildIndex(dir));
    }

    private List<StorageEntry> listChildren(String dir, Index index) {
        Map<String, StorageEntry> files = new LinkedHashMap<String, StorageEntry>();
        Set<String> dirs = new LinkedHashSet<String>();

        for (Map.Entry<String, Resolved> e : index.byLogical.entrySet()) {
            String logical = e.getKey();
            Resolved resolved = e.getValue();
            if (!StoragePaths.isUnder(dir, logical) || logical.equals(dir)) {
                continue;
            }
            String rel = StoragePaths.relativize(dir, logical);
            if (rel == null) {
                continue;
            }
            int slash = rel.indexOf('/');
            if (slash >= 0) {
                dirs.add(StoragePaths.join(dir, rel.substring(0, slash)));
                continue;
            }
            if (KEEP_NAME.equals(rel)) {
                continue;
            }
            if (resolved.directory) {
                dirs.add(logical);
                continue;
            }
            if (resolved.incompleteParts) {
                // 残缺分卷不出现在 list，避免被当成完整文件；exists/stat/download 抛 CONFLICT
                continue;
            }
            files.put(logical, resolved.toStorageEntry(logical));
        }

        // 仅有深层后代、尚无进 dirs 的中间段
        for (String raw : index.rawNames) {
            String logical = stripPart(raw);
            if (KEEP_NAME.equals(StoragePaths.name(logical))) {
                // 遗留 .keep 也贡献目录段
            }
            if (!StoragePaths.isUnder(dir, logical) || logical.equals(dir)) {
                continue;
            }
            String rel = StoragePaths.relativize(dir, logical);
            if (rel == null) {
                continue;
            }
            int slash = rel.indexOf('/');
            if (slash >= 0) {
                dirs.add(StoragePaths.join(dir, rel.substring(0, slash)));
            }
        }

        List<StorageEntry> result = new ArrayList<StorageEntry>();
        for (String d : dirs) {
            result.add(StorageEntry.dir(d));
        }
        result.addAll(files.values());
        return result;
    }

    private Resolved resolveLogical(String normalized) {
        // 只看父文件夹一层：避免为一次 stat/list 递归扫描整个 basePath
        Index index;
        try {
            index = buildIndex(StoragePaths.parent(normalized));
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
        Resolved hit = index.byLogical.get(normalized);
        if (hit != null) {
            return hit;
        }
        if (parentTruncated(normalized)) {
            // 大文件夹只加载了前几页：未命中时按名字问服务器（外来附件走 UID 索引 / 头扫描）
            try {
                MailEntry e = gateway.stat(normalized);
                if (e != null) {
                    if (e.directory()) {
                        return Resolved.dir(e.mtimeEpochMs());
                    }
                    Resolved r = e.foreign() ? Resolved.foreignFile(e)
                            : Resolved.file(e.size(), e.mtimeEpochMs(), e.uid(), Collections.<String>emptyList())
                                    .withAttributes(e.attributes());
                    return r;
                }
            } catch (StorageException ex) {
                if (ex.code() != StorageErrorCode.NOT_FOUND) {
                    throw ex;
                }
            }
        }
        // 合成目录：任意后代（含遗留 .keep / part）
        String prefix = normalized + "/";
        for (String raw : index.rawNames) {
            String logical = stripPart(raw);
            if (logical.equals(normalized) || logical.startsWith(prefix)) {
                return Resolved.dir();
            }
            // raw 本身以 prefix 开头（未 strip 时）
            if (raw.startsWith(prefix) || raw.equals(normalized)) {
                return Resolved.dir();
            }
        }
        return null;
    }

    private Index buildIndex() {
        return indexOf(gateway.list(""));
    }

    /** 单层索引：只列 {@code dir} 本身（子文件夹 + 本层邮件）。 */
    private Index buildIndex(String dir) {
        String key = dir == null ? "" : dir;
        long now = System.currentTimeMillis();
        CachedDir hit = dirCache.get(key);
        if (hit != null && now - hit.at < DIR_CACHE_TTL_MS) {
            return indexOf(hit.entries);
        }
        MailGateway.MailPage mp = gateway.listDirPage(key, 0, -1);
        CachedDir cached = new CachedDir(new ArrayList<MailEntry>(mp.entries), now, truncated(mp));
        dirCache.put(key, cached);
        return indexOf(cached.entries);
    }

    /** 本层是否只列出了部分邮件（大文件夹分页）。 */
    private static boolean truncated(MailGateway.MailPage mp) {
        if (mp.total < 0L) {
            return false;
        }
        Set<String> uids = new java.util.HashSet<String>();
        for (MailEntry e : mp.entries) {
            if (!e.directory() && e.uid() != null) {
                int hash = e.uid().indexOf('#');
                uids.add(hash < 0 ? e.uid() : e.uid().substring(0, hash));
            }
        }
        return mp.total > uids.size();
    }

    private boolean parentTruncated(String normalized) {
        CachedDir c = dirCache.get(StoragePaths.parent(normalized));
        return c != null && c.truncated;
    }

    /** 单层列表短缓存：list 后紧跟的 stat / download 不再重扫整个文件夹；任何写操作清空。 */
    static final long DIR_CACHE_TTL_MS = 30_000L;
    private final Map<String, CachedDir> dirCache = new java.util.HashMap<String, CachedDir>();

    private static final class CachedDir {
        final List<MailEntry> entries;
        final long at;
        final boolean truncated;

        CachedDir(List<MailEntry> entries, long at, boolean truncated) {
            this.entries = entries;
            this.at = at;
            this.truncated = truncated;
        }
    }

    private Index indexOf(List<MailEntry> mails) {
        Index index = new Index();
        if (mails == null) {
            return index;
        }
        Map<String, List<PartPiece>> partBags = new LinkedHashMap<String, List<PartPiece>>();
        for (MailEntry entry : mails) {
            if (entry == null || entry.name() == null || entry.name().isEmpty()) {
                continue;
            }
            String name = entry.name();
            if (entry.foreign()) {
                // 外来邮件附件：只读条目，不参与分卷归并 / 目录段推导
                for (MailEntry f : foreignMailEntries(entry)) {
                    index.byLogical.put(f.name(), Resolved.foreignFile(f));
                }
                continue;
            }
            index.rawNames.add(name);
            Matcher m = PART_PATTERN.matcher(name);
            if (m.matches()) {
                String base = m.group(1);
                int no = Integer.parseInt(m.group(2));
                List<PartPiece> bag = partBags.get(base);
                if (bag == null) {
                    bag = new ArrayList<PartPiece>();
                    partBags.put(base, bag);
                }
                bag.add(new PartPiece(no, name, entry));
                continue;
            }
            if (entry.directory()) {
                index.byLogical.put(name, Resolved.dir(entry.mtimeEpochMs()));
                continue;
            }
            index.byLogical.put(name, Resolved.file(entry.size(), entry.mtimeEpochMs(), entry.uid(),
                    Collections.<String>emptyList()).withAttributes(entry.attributes()));
        }
        for (Map.Entry<String, List<PartPiece>> e : partBags.entrySet()) {
            List<PartPiece> bag = e.getValue();
            Collections.sort(bag);
            List<String> partNames = new ArrayList<String>(bag.size());
            long total = 0L;
            long mtime = -1L;
            StringBuilder etag = new StringBuilder();
            for (PartPiece p : bag) {
                partNames.add(p.name);
                if (p.entry.size() >= 0L) {
                    total += p.entry.size();
                }
                if (p.entry.mtimeEpochMs() > mtime) {
                    mtime = p.entry.mtimeEpochMs();
                }
                if (etag.length() > 0) {
                    etag.append('+');
                }
                etag.append(p.entry.uid() == null ? p.name : p.entry.uid());
            }
            boolean incomplete = !isContiguousPartsFromOne(bag);
            // 若同时存在整文件与分卷，分卷覆盖（upload 会先删）
            index.byLogical.put(e.getKey(),
                    Resolved.file(total, mtime, etag.toString(), partNames, incomplete));
        }
        return index;
    }

    /**
     * 外来（非 jkit 写入）邮件条目的唯一出口。会话层已按附件逐个展开（无附件邮件不出现），
     * 这里默认原样收录；若改为隐藏外来邮件，返回空列表即可。
     *
     * @param entry 会话层给出的外来附件条目（{@link MailEntry#foreign()} 为 true）
     * @return 要收入索引的条目
     */
    List<MailEntry> foreignMailEntries(MailEntry entry) {
        return Collections.singletonList(entry);
    }

    private static StorageException foreignReadOnly(String path) {
        return StorageException.unsupported("非 jkit 写入的邮件附件只读: " + path);
    }

    private void deleteExact(String logicalName) {
        Resolved resolved = resolveLogical(logicalName);
        if (resolved == null) {
            return;
        }
        if (resolved.parts.isEmpty()) {
            deleteQuiet(logicalName);
            // 兼容：误存为 part 前缀时再扫一遍
            deletePartsByPrefix(logicalName);
        } else {
            for (String part : resolved.parts) {
                deleteQuiet(part);
            }
        }
    }

    private void deleteUnder(String dir) {
        Index index = buildIndex();
        String prefix = dir + "/";
        for (Map.Entry<String, Resolved> e : index.byLogical.entrySet()) {
            if (e.getValue().foreign && e.getKey().startsWith(prefix)) {
                // 删目录会连带删掉 IMAP 文件夹里的普通邮件，拒绝
                throw StorageException.unsupported("目录内含非 jkit 写入的邮件，拒绝删除: " + dir);
            }
        }
        Set<String> toDelete = new LinkedHashSet<String>();
        for (String raw : index.rawNames) {
            if (raw.equals(dir) || raw.startsWith(prefix)) {
                toDelete.add(raw);
            }
        }
        for (String logical : new ArrayList<String>(index.byLogical.keySet())) {
            if (logical.equals(dir) || logical.startsWith(prefix)) {
                Resolved r = index.byLogical.get(logical);
                if (r != null && !r.parts.isEmpty()) {
                    toDelete.addAll(r.parts);
                } else {
                    toDelete.add(logical);
                }
            }
        }
        for (String name : toDelete) {
            deleteQuiet(name);
        }
        try {
            dirCache.clear();
            gateway.rmdir(dir);
        } catch (StorageException e) {
            if (e.code() != StorageErrorCode.NOT_FOUND) {
                // 邮箱非空或不支持删除时不阻断文件侧清理
                if (e.code() != StorageErrorCode.SERVER_ERROR) {
                    throw e;
                }
            }
        }
    }

    private void deletePartsByPrefix(String logicalName) {
        List<MailEntry> mails = gateway.list("");
        if (mails == null) {
            return;
        }
        String prefix = logicalName + ".part";
        for (MailEntry entry : mails) {
            if (entry != null && !entry.foreign() && entry.name() != null && entry.name().startsWith(prefix)) {
                Matcher m = PART_PATTERN.matcher(entry.name());
                if (m.matches() && logicalName.equals(m.group(1))) {
                    deleteQuiet(entry.name());
                }
            }
        }
    }

    private void deleteQuiet(String name) {
        try {
            dirCache.clear();
            gateway.delete(name);
        } catch (StorageException e) {
            if (e.code() != StorageErrorCode.NOT_FOUND) {
                throw e;
            }
        }
    }

    private void appendMime(String logicalName, byte[] payload, long totalRaw) {
        String parent = StoragePaths.parent(logicalName);
        String baseName = StoragePaths.name(logicalName);
        if (!parent.isEmpty()) {
            dirCache.clear();
            gateway.mkdir(parent);
        }
        byte[] mime = buildStoredMime(baseName, payload, totalRaw);
        // folder = basePath 或 basePath/parent（网关/会话会解析）
        String imapFolder = parent.isEmpty() ? folder : StoragePaths.join(folder, parent);
        dirCache.clear();
        gateway.append(imapFolder, new ByteArrayInputStream(mime), mime.length);
    }

    private static String requireFilePath(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("文件路径不能为空");
        }
        if (PART_PATTERN.matcher(normalized).matches()) {
            throw StorageException.invalidConfig(
                    "逻辑路径不能使用保留后缀 .partN（分卷专用）: " + normalized);
        }
        if (KEEP_NAME.equals(StoragePaths.name(normalized)) && normalized.indexOf('/') < 0) {
            // 兼容遗留根下 .keep；mkdir 不再主动创建
        }
        return normalized;
    }

    /** part 号须从 1 起连续无缺口、无重复。 */
    private static boolean isContiguousPartsFromOne(List<PartPiece> bag) {
        if (bag == null || bag.isEmpty()) {
            return false;
        }
        for (int i = 0; i < bag.size(); i++) {
            if (bag.get(i).no != i + 1) {
                return false;
            }
        }
        return true;
    }

    private static void ensureCompleteParts(Resolved resolved, String path) {
        if (resolved != null && resolved.incompleteParts) {
            throw StorageException.conflict(
                    "分卷不完整或存在缺口，拒绝读写: " + path, path);
        }
    }

    private static String stripPart(String name) {
        Matcher m = PART_PATTERN.matcher(name);
        return m.matches() ? m.group(1) : name;
    }

    private static byte[] readPayload(InputStream in, long size, String name) {
        if (in == null) {
            return new byte[0];
        }
        try {
            if (size >= 0L && size <= Integer.MAX_VALUE) {
                byte[] buf = new byte[(int) size];
                int off = 0;
                while (off < buf.length) {
                    int read = in.read(buf, off, buf.length - off);
                    if (read < 0) {
                        break;
                    }
                    off += read;
                }
                if (off == buf.length) {
                    return buf;
                }
                byte[] exact = new byte[off];
                System.arraycopy(buf, 0, exact, 0, off);
                return exact;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw StorageException.io("读取上传内容失败: " + name, name, e);
        }
    }

    /**
     * 经 jkit-notify {@link Attachment} + {@link MailMime} 组装存储用 RFC822。
     * Subject/附件名为路径最后一段（不含目录前缀）。
     *
     * @param fileName 逻辑文件名
     * @param payload  附件字节
     * @return 完整 RFC822 字节
     */
    static byte[] buildStoredMime(String fileName, byte[] payload) {
        return buildStoredMime(fileName, payload, -1L);
    }

    /**
     * 组装存储 MIME，并在头部写 {@code X-Jkit-Size}（本封附件原始字节）、
     * 分卷时 {@code X-Jkit-Total-Size}（整文件原始字节），缺 Message-ID 时补唯一值。
     */
    static byte[] buildStoredMime(String fileName, byte[] payload, long totalRaw) {
        byte[] data = payload == null ? new byte[0] : payload;
        Attachment attachment = Attachment.of(fileName, data);
        Message message = Message.text(fileName, "Stored by jkit-storage MailFileStorage.")
                .attachment(attachment);
        ChannelConfig mimeCfg = ChannelConfig.smtp("127.0.0.1", 25)
                .from("jkit-storage@localhost")
                .to("jkit-storage@localhost");
        byte[] rendered = MailMime.render(message, "jkit-storage@localhost", mimeCfg);
        StringBuilder extra = new StringBuilder();
        extra.append("X-Jkit-Size: ").append(data.length).append("\r\n");
        if (totalRaw >= 0L) {
            extra.append("X-Jkit-Total-Size: ").append(totalRaw).append("\r\n");
        }
        // 唯一 Message-ID：busy 重试 / APPEND 校验按它 SEARCH
        if (ImapMailSession.extractMessageId(rendered) == null) {
            extra.append("Message-ID: <").append(java.util.UUID.randomUUID())
                    .append("@jkit-storage>\r\n");
        }
        byte[] header = extra.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + rendered.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(rendered, 0, out, header.length, rendered.length);
        return out;
    }

    private static final class Index {
        final Map<String, Resolved> byLogical = new LinkedHashMap<String, Resolved>();
        final List<String> rawNames = new ArrayList<String>();
    }

    private static final class PartPiece implements Comparable<PartPiece> {
        final int no;
        final String name;
        final MailEntry entry;

        PartPiece(int no, String name, MailEntry entry) {
            this.no = no;
            this.name = name;
            this.entry = entry;
        }

        @Override
        public int compareTo(PartPiece o) {
            return Integer.compare(this.no, o.no);
        }
    }

    private static final class Resolved {
        final boolean directory;
        final long size;
        final long mtime;
        final String etag;
        final List<String> parts;
        /** 分卷缺片/缺口/非从 1 连续时为 true；exists/stat/download 抛 CONFLICT。 */
        final boolean incompleteParts;
        /** 非 jkit 写入邮件的附件（只读）。 */
        boolean foreign;
        Map<String, String> attributes = Collections.<String, String>emptyMap();

        private Resolved(boolean directory, long size, long mtime, String etag, List<String> parts,
                         boolean incompleteParts) {
            this.directory = directory;
            this.size = size;
            this.mtime = mtime;
            this.etag = etag;
            this.parts = parts == null ? Collections.<String>emptyList() : parts;
            this.incompleteParts = incompleteParts;
        }

        static Resolved dir() {
            return dir(-1L);
        }

        static Resolved dir(long mtime) {
            return new Resolved(true, 0L, mtime, null, Collections.<String>emptyList(), false);
        }

        static Resolved file(long size, long mtime, String etag, List<String> parts) {
            return file(size, mtime, etag, parts, false);
        }

        static Resolved file(long size, long mtime, String etag, List<String> parts,
                             boolean incompleteParts) {
            return new Resolved(false, size, mtime, etag, parts, incompleteParts);
        }

        static Resolved foreignFile(MailEntry e) {
            Resolved r = file(e.size(), e.mtimeEpochMs(), e.uid(), Collections.<String>emptyList());
            r.foreign = true;
            return r.withAttributes(e.attributes());
        }

        Resolved withAttributes(Map<String, String> attrs) {
            this.attributes = attrs == null ? Collections.<String, String>emptyMap() : attrs;
            return this;
        }

        StorageEntry toStorageEntry(String path) {
            if (directory) {
                return StorageEntry.dir(path, mtime);
            }
            StorageEntry entry = StorageEntry.file(path, size, mtime, etag);
            for (Map.Entry<String, String> a : attributes.entrySet()) {
                entry = entry.withAttribute(a.getKey(), a.getValue());
            }
            return entry;
        }
    }
}
