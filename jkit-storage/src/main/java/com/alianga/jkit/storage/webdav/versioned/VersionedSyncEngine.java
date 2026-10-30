package com.alianga.jkit.storage.webdav.versioned;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 版本化快照同步引擎（纯逻辑，无 JavaFX / UI 依赖）。
 *
 * <p>事实源：远端 {@code reqdesk-v*.zip} 文件名中的最大 seq。索引文件仅为缓存。</p>
 *
 * @author 郑明亮
 */
public final class VersionedSyncEngine {
    private static final Logger LOG = Logger.getLogger(VersionedSyncEngine.class.getName());

    private final RemoteStore store;
    private final String hostId;
    private final int retentionN;

    /**
     * @param store      远端存储
     * @param hostId     本机标识（写入版本名）
     * @param retentionN 保留版本数（含 head）；&lt;1 则默认 20
     */
    public VersionedSyncEngine(RemoteStore store, String hostId, int retentionN) {
        this.store = Objects.requireNonNull(store, "store");
        this.hostId = VersionId.normalizeHost(hostId);
        this.retentionN = retentionN < 1 ? 20 : retentionN;
    }

    /**
     * 从 PROPFIND 列出全部版本包，按 seq 升序。
     *
     * @return 版本列表
     * @throws IOException 传输失败
     */
    public List<VersionId> listVersions() throws IOException {
        List<VersionId> out = new ArrayList<VersionId>();
        for (RemoteObject obj : store.list()) {
            Optional<VersionId> parsed = VersionId.parse(obj.name());
            if (parsed.isPresent()) {
                out.add(parsed.get());
            }
        }
        out.sort(VersionId.BY_SEQ);
        return out;
    }

    /**
     * @return 当前 head（最大 seq）；无版本则 empty
     * @throws IOException 传输失败
     */
    public Optional<VersionId> resolveHead() throws IOException {
        List<VersionId> all = listVersions();
        if (all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(all.get(all.size() - 1));
    }

    /**
     * 读取索引缓存（失败返回 empty，不抛）。
     *
     * @return 索引
     */
    public Optional<SyncIndex> readIndexHint() {
        try {
            Optional<RemoteObject> st = store.stat(SyncIndex.FILE_NAME);
            if (!st.isPresent()) {
                return Optional.empty();
            }
            Path tmp = Files.createTempFile("reqdesk-idx-", ".json");
            try {
                store.get(SyncIndex.FILE_NAME, tmp);
                return Optional.of(SyncIndex.parse(Files.readAllBytes(tmp)));
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "read index hint failed", e);
            return Optional.empty();
        }
    }

    /**
     * 上传本机 zip 为下一个版本。
     *
     * <p>冲突判定：远端 head.seq &gt; localBase.seq，或 head 文件名与 localBase 不一致。</p>
     *
     * @param zip        本机备份包
     * <p>对 {@link StoreCapabilities.OverwritePolicy#RENAMES_ON_EXISTS}，
     * {@code putIfAbsent} 发带 {@code If-None-Match:*} 的 PUT；若 {@code Location} 改名则删除残留并冲突。
     * 对静默覆盖服务器，无条件写时先 STAT 再 {@code putReplace}，存在 TOCTOU 窗口，
     * 由版本文件名中的 host+hash 缓解。</p>
     *
     * @param localBase  本机基线
     * @param onConflict 冲突回调；返回决策
     * @return 上传结果
     * @throws IOException           传输失败
     * @throws SyncConflictException 用户取消或无法解决
     */
    public UploadOutcome uploadNewVersion(Path zip, LocalBase localBase,
                                          ConflictHandler onConflict) throws IOException, SyncConflictException {
        Objects.requireNonNull(zip, "zip");
        Objects.requireNonNull(localBase, "localBase");
        byte[] bytes = Files.readAllBytes(zip);
        String fullHash = sha256Hex(bytes);
        String hash8 = fullHash.substring(0, 8);

        Optional<VersionId> headOpt = resolveHead();
        long remoteHeadSeq = headOpt.isPresent() ? headOpt.get().seq() : 0L;

        boolean diverged = !localBase.isEmpty()
                && headOpt.isPresent()
                && (remoteHeadSeq > localBase.seq()
                || !headOpt.get().fileName().equals(localBase.versionFile()));

        if (diverged) {
            ConflictDecision d = onConflict == null
                    ? ConflictDecision.CANCEL
                    : onConflict.onConflict(headOpt.orElse(null), localBase);
            if (d == ConflictDecision.CANCEL || d == null) {
                throw new SyncConflictException("用户取消冲突处理");
            }
            if (d == ConflictDecision.RESTORE_REMOTE_FIRST) {
                throw new SyncConflictException(SyncConflictException.Kind.NEED_RESTORE,
                        "需先恢复远端后再上传", headOpt.orElse(null));
            }
            // UPLOAD_AS_NEW_VERSION：基于远端 head 继续递增
        }

        long nextSeq = remoteHeadSeq + 1;
        if (nextSeq < 1) {
            nextSeq = 1;
        }
        VersionId next = VersionId.of(nextSeq, hostId, hash8);

        if (store.stat(next.fileName()).isPresent()) {
            headOpt = resolveHead();
            remoteHeadSeq = headOpt.isPresent() ? headOpt.get().seq() : 0L;
            nextSeq = remoteHeadSeq + 1;
            next = VersionId.of(nextSeq, hostId, hash8);
            if (store.stat(next.fileName()).isPresent()) {
                throw new SyncConflictException("版本号碰撞，请重试");
            }
        }

        PutResult put = store.putIfAbsent(next.fileName(), zip);
        if (put.unsupported()) {
            // 仅静默覆盖 / 未知策略会走到这里：名称已 STAT 为空后 putReplace。
            // 仍有 check-then-write 窗口（STAT→PUT 非原子）；版本名含 host+hash 降低碰撞，
            // 冲突由后续 renamed / preconditionFailed / 重试路径消化。
            put = store.putReplace(next.fileName(), zip);
        }
        if (put.renamed()) {
            try {
                if (put.effectiveName() != null && !put.effectiveName().trim().isEmpty()
                        && !put.effectiveName().equals(next.fileName())) {
                    store.delete(put.effectiveName());
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "删除改名残留失败: " + put.effectiveName(), e);
            }
            throw new SyncConflictException("远端改名写入（无条件写/冲突改名），请重试或检查权限");
        }
        if (put.preconditionFailed() || !put.success()) {
            headOpt = resolveHead();
            throw new SyncConflictException(SyncConflictException.Kind.UPLOAD_RACE,
                    "上传条件失败 status=" + put.httpStatus(), headOpt.orElse(null));
        }

        SyncIndex idx = SyncIndex.of(next, fullHash, hostId, System.currentTimeMillis());
        boolean indexOk = updateIndexBestEffort(idx);
        applyRetentionBestEffort();
        return new UploadOutcome(next, fullHash, indexOk);
    }

    /**
     * 下载指定版本到本地路径。
     *
     * @param version 版本
     * @param target  本地 zip 路径
     * @throws IOException 失败
     */
    public void downloadVersion(VersionId version, Path target) throws IOException {
        store.get(version.fileName(), target);
    }

    /**
     * 尽力更新索引：条件写 → replace:1 → 普通覆盖尝试；最后 GET 回读校验。
     *
     * @param idx 新索引
     * @return 是否校验通过
     */
    public boolean updateIndexBestEffort(SyncIndex idx) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("reqdesk-idx-up-", ".json");
            Files.write(tmp, idx.toJsonBytes());

            StoreCapabilities caps = null;
            try {
                caps = store.probeCapabilities();
            } catch (Exception ignored) {
                // 使用已有能力假设
            }

            Optional<RemoteObject> existing = store.stat(SyncIndex.FILE_NAME);
            PutResult put;
            if (!existing.isPresent()) {
                put = store.putIfAbsent(SyncIndex.FILE_NAME, tmp);
                if (put.unsupported()) {
                    put = store.putReplace(SyncIndex.FILE_NAME, tmp);
                }
            } else if (existing.get().hasStrongEtag() && caps != null && caps.conditionalPut()) {
                put = store.putIfMatch(SyncIndex.FILE_NAME, tmp, existing.get().etag());
                if (put.unsupported() || (put.preconditionFailed() && caps.replaceHeader())) {
                    put = store.putReplace(SyncIndex.FILE_NAME, tmp);
                }
            } else if (caps != null && caps.replaceHeader()) {
                put = store.putReplace(SyncIndex.FILE_NAME, tmp);
            } else {
                put = store.putReplace(SyncIndex.FILE_NAME, tmp);
            }

            if (put.renamed()) {
                try {
                    store.delete(put.effectiveName());
                } catch (Exception ignored) {
                    // ignore
                }
                LOG.warning("索引更新被改名，跳过（版本包已是事实源）");
                return false;
            }
            if (!put.success() && !put.preconditionFailed()) {
                LOG.warning("索引更新 HTTP " + put.httpStatus());
                return false;
            }

            Path verify = Files.createTempFile("reqdesk-idx-vf-", ".json");
            try {
                store.get(SyncIndex.FILE_NAME, verify);
                SyncIndex back = SyncIndex.parse(Files.readAllBytes(verify));
                boolean ok = back.headSeq() == idx.headSeq()
                        && Objects.equals(back.headFile(), idx.headFile())
                        && Objects.equals(back.contentHash(), idx.contentHash());
                if (!ok) {
                    LOG.warning("索引回读校验失败");
                }
                return ok;
            } finally {
                Files.deleteIfExists(verify);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "索引更新失败（可忽略）", e);
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    /** 保留最近 retentionN 个版本；失败仅记日志。 */
    public void applyRetentionBestEffort() {
        try {
            List<VersionId> all = listVersions();
            if (all.size() <= retentionN) {
                return;
            }
            List<VersionId> drop = all.subList(0, all.size() - retentionN);
            for (VersionId v : drop) {
                try {
                    store.delete(v.fileName());
                } catch (Exception e) {
                    LOG.log(Level.FINE, "retention delete failed: " + v.fileName(), e);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "retention failed", e);
        }
    }

    /**
     * @param data 字节
     * @return SHA-256 hex 小写
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 冲突处理回调。
     */
    public interface ConflictHandler {
        /**
         * @param remoteHead 远端 head（可能 null）
         * @param localBase  本机基线
         * @return 决策
         */
        ConflictDecision onConflict(VersionId remoteHead, LocalBase localBase);
    }

    /**
     * 上传成功结果。
     */
    public static final class UploadOutcome {
        private final VersionId version;
        private final String contentHash;
        private final boolean indexUpdated;

        public UploadOutcome(VersionId version, String contentHash, boolean indexUpdated) {
            this.version = version;
            this.contentHash = contentHash;
            this.indexUpdated = indexUpdated;
        }

        public VersionId version() {
            return version;
        }

        public String contentHash() {
            return contentHash;
        }

        public boolean indexUpdated() {
            return indexUpdated;
        }
    }

    /**
     * 同步冲突/需用户介入。
     */
    public static final class SyncConflictException extends Exception {
        public enum Kind { CONFLICT, NEED_RESTORE, UPLOAD_RACE }

        private final Kind kind;
        private final VersionId remoteHead;

        public SyncConflictException(String message) {
            this(Kind.CONFLICT, message, null);
        }

        public SyncConflictException(Kind kind, String message, VersionId remoteHead) {
            super(message);
            this.kind = kind == null ? Kind.CONFLICT : kind;
            this.remoteHead = remoteHead;
        }

        public Kind kind() {
            return kind;
        }

        public Optional<VersionId> remoteHead() {
            return Optional.ofNullable(remoteHead);
        }
    }
}
