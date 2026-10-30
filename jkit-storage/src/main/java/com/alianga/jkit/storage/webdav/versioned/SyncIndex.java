package com.alianga.jkit.storage.webdav.versioned;

import com.alianga.jkit.json.JSON;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 远端索引文件 {@code reqdesk-sync.json} 的缓存/提示结构（非事实源）。
 *
 * <p>事实源是版本包文件名本身；索引仅加速 UI 与恢复入口。</p>
 *
 * @author 郑明亮
 */
public final class SyncIndex {
    public static final String FILE_NAME = "reqdesk-sync.json";
    public static final int SCHEMA = 1;

    private final int schemaVersion;
    private final long headSeq;
    private final String headFile;
    private final String contentHash;
    private final String host;
    private final long updatedAtMs;

    public SyncIndex(int schemaVersion, long headSeq, String headFile,
                     String contentHash, String host, long updatedAtMs) {
        this.schemaVersion = schemaVersion;
        this.headSeq = headSeq;
        this.headFile = headFile == null ? "" : headFile;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.host = host == null ? "" : host;
        this.updatedAtMs = updatedAtMs;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public long headSeq() {
        return headSeq;
    }

    public String headFile() {
        return headFile;
    }

    public String contentHash() {
        return contentHash;
    }

    public String host() {
        return host;
    }

    public long updatedAtMs() {
        return updatedAtMs;
    }

    public static SyncIndex of(VersionId head, String fullContentHash, String host, long updatedAtMs) {
        Objects.requireNonNull(head, "head");
        return new SyncIndex(SCHEMA, head.seq(), head.fileName(),
                fullContentHash == null ? "" : fullContentHash,
                VersionId.normalizeHost(host),
                updatedAtMs);
    }

    public byte[] toJsonBytes() {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("schemaVersion", schemaVersion);
        o.put("headSeq", headSeq);
        o.put("headFile", headFile);
        o.put("contentHash", contentHash);
        o.put("host", host);
        o.put("updatedAt", updatedAtMs);
        return JSON.toJsonString(o).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static SyncIndex parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty index");
        }
        Map<String, Object> o = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), Map.class);
        if (o == null) {
            throw new IllegalArgumentException("invalid index json");
        }
        int schema = asInt(o.get("schemaVersion"), 1);
        long seq = asLong(o.get("headSeq"), 0L);
        String file = asString(o.get("headFile"));
        String hash = asString(o.get("contentHash"));
        String host = asString(o.get("host"));
        long at = asLong(o.get("updatedAt"), 0L);
        return new SyncIndex(schema, seq, file, hash, host, at);
    }

    private static String asString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int asInt(Object v, int dft) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v == null) {
            return dft;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static long asLong(Object v, long dft) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v == null) {
            return dft;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
