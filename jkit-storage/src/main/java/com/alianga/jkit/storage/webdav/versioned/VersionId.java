package com.alianga.jkit.storage.webdav.versioned;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不可变版本包文件名：{@code reqdesk-v&lt;seq6&gt;-&lt;host&gt;-&lt;hash8&gt;.zip}。
 *
 * <p>全锚点模式 {@code ^reqdesk-v(\d+)-([a-z0-9_-]+)-([a-f0-9]{8})\.zip$}：
 * copyparty 改名残留 {@code name.zip-&lt;ts&gt;-&lt;rand&gt;.zip} 因中间含 {@code .zip.} 无法匹配。
 * host 段在生成时已将点号替换为 {@code -}（见 {@link #normalizeHost}）。</p>
 *
 * <p>seq 为零填充十进制；host 已规范化（仅 [a-z0-9_-]）；hash8 为内容 SHA-256 前 8 位小写十六进制。</p>
 *
 * @author 郑明亮
 */
public final class VersionId {
    /** 版本包文件名全锚点模式（单一事实源；勿放宽）。 */
    private static final Pattern PATTERN = Pattern.compile(
            "^reqdesk-v(\\d+)-([a-z0-9_-]+)-([a-f0-9]{8})\\.zip$",
            Pattern.CASE_INSENSITIVE);

    public static final Comparator<VersionId> BY_SEQ =
            new Comparator<VersionId>() {
                @Override
                public int compare(VersionId a, VersionId b) {
                    return Long.compare(a.seq, b.seq);
                }
            };

    private final long seq;
    private final String host;
    private final String hash8;
    private final String fileName;

    public VersionId(long seq, String host, String hash8, String fileName) {
        this.seq = seq;
        this.host = host;
        this.hash8 = hash8;
        this.fileName = fileName;
    }

    public long seq() {
        return seq;
    }

    public String host() {
        return host;
    }

    public String hash8() {
        return hash8;
    }

    public String fileName() {
        return fileName;
    }

    /**
     * @param seq   版本号
     * @param host  原始主机名
     * @param hash8 哈希前缀（可长于 8，截断）
     * @return 版本标识
     */
    public static VersionId of(long seq, String host, String hash8) {
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1");
        }
        String h = normalizeHost(host);
        String hs = normalizeHash8(hash8);
        String name = String.format(Locale.ROOT, "reqdesk-v%06d-%s-%s.zip", seq, h, hs);
        return new VersionId(seq, h, hs, name);
    }

    /**
     * @param fileName 远端文件名
     * @return 解析结果；不匹配则 empty
     */
    public static Optional<VersionId> parse(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(fileName.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        long seq = Long.parseLong(m.group(1));
        if (seq < 1) {
            return Optional.empty();
        }
        String host = normalizeHost(m.group(2));
        String hash = normalizeHash8(m.group(3));
        String canonical = String.format(Locale.ROOT, "reqdesk-v%06d-%s-%s.zip", seq, host, hash);
        return Optional.of(new VersionId(seq, host, hash, canonical));
    }

    /**
     * 是否为合法版本包名（委托 {@link #parse}）。
     *
     * @param fileName 文件名
     * @return 是否匹配
     */
    public static boolean isVersionFileName(String fileName) {
        return parse(fileName).isPresent();
    }

    /**
     * @param raw 原始主机名
     * @return 仅保留 [a-z0-9_-]，空则 {@code host}
     */
    public static String normalizeHost(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "host";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.trim().isEmpty()) {
            return "host";
        }
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        return s;
    }

    private static String normalizeHash8(String hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash8 required");
        }
        String h = hash.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
        if (h.length() < 8) {
            throw new IllegalArgumentException("hash8 too short: " + hash);
        }
        return h.substring(0, 8);
    }

    @Override
    public String toString() {
        return fileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VersionId)) {
            return false;
        }
        VersionId that = (VersionId) o;
        return seq == that.seq && fileName.equals(that.fileName);
    }

    @Override
    public int hashCode() {
        return fileName.hashCode();
    }
}
