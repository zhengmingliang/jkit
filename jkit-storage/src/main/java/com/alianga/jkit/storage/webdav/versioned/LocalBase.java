package com.alianga.jkit.storage.webdav.versioned;

/**
 * 本机已确认的远端基线（上次成功同步后的 head）。
 *
 * @author 郑明亮
 */
public final class LocalBase {
    public static final LocalBase EMPTY = new LocalBase(0, "", "");

    private final long seq;
    private final String versionFile;
    private final String contentHash;

    public LocalBase(long seq, String versionFile, String contentHash) {
        this.versionFile = versionFile == null ? "" : versionFile;
        this.contentHash = contentHash == null ? "" : contentHash;
        this.seq = seq < 0 ? 0 : seq;
    }

    public long seq() {
        return seq;
    }

    public String versionFile() {
        return versionFile;
    }

    public String contentHash() {
        return contentHash;
    }

    public boolean isEmpty() {
        return seq <= 0 || versionFile.trim().isEmpty();
    }

    public static LocalBase of(VersionId id, String fullHash) {
        return new LocalBase(id.seq(), id.fileName(), fullHash == null ? "" : fullHash);
    }
}
