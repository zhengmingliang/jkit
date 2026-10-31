package com.alianga.jkit.storage.git;

/**
 * 一次提交记录：短 SHA、作者、时间、提交说明。
 *
 * <p>作者缺失（服务端未返回或字段为空）时 {@link #author()} 返回 {@code null}，
 * 界面侧统一显示「未知」，不把 null 直接画到表格里。</p>
 *
 * @author 郑明亮
 */
public final class GitCommit {
    /** 未知时间的占位值（与 {@code StorageEntry.MTIME_UNKNOWN} 一致）。 */
    public static final long TIME_UNKNOWN = -1L;
    /** 界面上展示的短 SHA 长度。 */
    public static final int SHORT_SHA = 7;

    private final String sha;
    private final String author;
    private final long time;
    private final String message;

    /**
     * @param sha     完整 SHA（界面展示取前 7 位）
     * @param author  作者名；未知为 null
     * @param time    提交时间（epoch 毫秒）；未知为 -1
     * @param message 提交说明（首行）；未知为空串
     */
    public GitCommit(String sha, String author, long time, String message) {
        this.sha = sha;
        this.author = author;
        this.time = time;
        this.message = message;
    }

    /** @return 完整 SHA */
    public String sha() {
        return sha;
    }

    /** @return 作者名；未知为 null */
    public String author() {
        return author;
    }

    /** @return 提交时间（epoch 毫秒）；未知为 -1 */
    public long time() {
        return time;
    }

    /** @return 提交说明（首行） */
    public String message() {
        return message;
    }

    /** @return 短 SHA（不足 7 位时原样返回） */
    public String shortSha() {
        if (sha == null || sha.trim().isEmpty()) {
            return "";
        }
        return sha.length() <= SHORT_SHA ? sha : sha.substring(0, SHORT_SHA);
    }

    /** @return 作者；缺失返回 {@code null}（界面显示「未知」） */
    public String authorOrUnknown() {
        return author == null || author.trim().isEmpty() ? null : author;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GitCommit)) {
            return false;
        }
        GitCommit that = (GitCommit) other;
        return time == that.time
                && java.util.Objects.equals(sha, that.sha)
                && java.util.Objects.equals(author, that.author)
                && java.util.Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(sha, author, time, message);
    }

    @Override
    public String toString() {
        return "GitCommit[" + shortSha() + " " + authorOrUnknown() + " " + message + "]";
    }
}
