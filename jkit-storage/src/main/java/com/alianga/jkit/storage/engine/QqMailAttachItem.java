package com.alianga.jkit.storage.engine;

import java.util.Map;

/**
 * 附件管理条目（attach_list / search 的单项）。{@link #toString()} 不含 key/code/大附件 fileid。
 *
 * @author 郑明亮
 */
public final class QqMailAttachItem {
    final String fileid;
    final String name;
    final long size;
    final String md5;
    final long mtimeMs;
    final String mailid;
    final String subject;
    final String sender;
    volatile boolean collect;
    final String downloadUrl;
    final String previewUrl;
    final String thumbnailUrl;
    final String bigAttachKey;
    final String bigAttachCode;
    final long etimeMs;
    final String shortId;

    QqMailAttachItem(Map<String, Object> m) {
        this.fileid = str(m.get("fileid"));
        this.name = str(m.get("name"));
        this.size = num(m.get("size"), -1L);
        this.md5 = str(m.get("md5"));
        this.mtimeMs = toMillis(num(m.get("mtime"), -1L));
        this.mailid = str(m.get("mailid"));
        this.subject = str(m.get("subject"));
        this.sender = str(m.get("sender"));
        this.collect = bool(m.get("is_collect"));
        this.downloadUrl = str(m.get("download_url"));
        this.previewUrl = str(m.get("preview_url"));
        this.thumbnailUrl = str(m.get("thumbnail_url"));
        this.bigAttachKey = str(m.get("big_attach_key"));
        this.bigAttachCode = str(m.get("big_attach_code"));
        this.etimeMs = toMillis(num(m.get("etime"), -1L));
        // 超大附件可能没有 fileid：用 big_attach_key 区分同一封邮件里的多个附件，避免短 id 撞车合并
        this.shortId = QqMailAttachFileStorage.shortId(mailid,
                fileid != null && !fileid.isEmpty() ? fileid : bigAttachKey);
    }

    /** @return 是否超大附件（ftn，有 key/code，约 30 天过期） */
    public boolean isBig() {
        return bigAttachKey != null && !bigAttachKey.isEmpty();
    }

    /** @return 文件名 */
    public String name() {
        return name;
    }

    /** @return 精确字节数 */
    public long size() {
        return size;
    }

    /** @return md5（可能为空） */
    public String md5() {
        return md5;
    }

    /** @return 所属邮件 id */
    public String mailid() {
        return mailid;
    }

    /** @return 是否已收藏 */
    public boolean collect() {
        return collect;
    }

    /** @return 短 id（sha256(mailid+"\n"+fileid) 前 8 位 hex） */
    public String shortId() {
        return shortId;
    }

    @Override
    public String toString() {
        return "QqMailAttachItem{name=" + name + ", size=" + size + ", shortId=" + shortId
                + ", big=" + isBig() + "}";
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static long num(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue() != 0;
        }
        return o != null && ("true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o)));
    }

    /** 秒级时间戳转毫秒（已是毫秒则原样）。 */
    static long toMillis(long t) {
        if (t <= 0L) {
            return -1L;
        }
        return t < 100_000_000_000L ? t * 1000L : t;
    }
}
