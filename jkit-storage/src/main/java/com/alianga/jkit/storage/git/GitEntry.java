package com.alianga.jkit.storage.git;

/**
 * 仓库里的一项内容：文件或目录（Git 的 Contents API 一次返回一层）。
 *
 * <p>{@code sha} 有两个含义：对文件是 blob SHA（更新内容时必须回传，作为「我改的是这个版本」的
 * 依据），对目录是 tree SHA。删除文件时同样要回传 blob SHA。</p>
 *
 * @author 郑明亮
 */
public final class GitEntry {
    /** 目录类型值。 */
    public static final String TYPE_DIR = "dir";
    /** 文件类型值。 */
    public static final String TYPE_FILE = "file";

    private final String name;
    private final String path;
    private final String type;
    private final long size;
    private final String sha;
    private final String downloadUrl;

    /**
     * @param name        名称（不含路径）
     * @param path        仓库内完整路径
     * @param type        {@code file} / {@code dir}
     * @param size        字节数；目录或未知为 -1
     * @param sha         对象 SHA；未知为 null
     * @param downloadUrl 原始内容地址；目录为 null
     */
    public GitEntry(String name, String path, String type, long size, String sha,
                    String downloadUrl) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.size = size;
        this.sha = sha;
        this.downloadUrl = downloadUrl;
    }

    /** @return 名称（不含路径） */
    public String name() {
        return name;
    }

    /** @return 仓库内完整路径 */
    public String path() {
        return path;
    }

    /** @return {@code file} / {@code dir} */
    public String type() {
        return type;
    }

    /** @return 字节数；目录或未知为 -1 */
    public long size() {
        return size;
    }

    /** @return 对象 SHA；未知为 null */
    public String sha() {
        return sha;
    }

    /** @return 原始内容地址；目录为 null */
    public String downloadUrl() {
        return downloadUrl;
    }

    /** @return true 表示目录 */
    public boolean isDirectory() {
        return TYPE_DIR.equals(type);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GitEntry)) {
            return false;
        }
        GitEntry that = (GitEntry) other;
        return size == that.size
                && java.util.Objects.equals(name, that.name)
                && java.util.Objects.equals(path, that.path)
                && java.util.Objects.equals(type, that.type)
                && java.util.Objects.equals(sha, that.sha)
                && java.util.Objects.equals(downloadUrl, that.downloadUrl);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, path, type, size, sha, downloadUrl);
    }

    @Override
    public String toString() {
        return "GitEntry[" + type + " " + path + (size >= 0 ? " " + size + "B" : "")
                + (sha == null ? "" : " " + sha) + "]";
    }
}
