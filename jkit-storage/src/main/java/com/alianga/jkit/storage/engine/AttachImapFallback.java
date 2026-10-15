package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageException;

import java.io.OutputStream;
import java.util.List;

/**
 * 网页下载失败时的 IMAP 回退策略：按 Message-ID 定位原邮件，再按「文件名 + 精确大小（+ md5）」
 * 匹配附件取字节。超大附件（ftn）不在邮件正文里，不可回退。
 *
 * <p>TODO：Message-ID 需经 {@code /read/readmail}（messageid 字段）由 mailid 解析，接口未抓包，
 * 生产实现待接；匹配逻辑见 {@link #match(QqMailAttachItem, List)}。</p>
 *
 * @author 郑明亮
 */
public interface AttachImapFallback {
    /** 候选附件（来自 IMAP 邮件的 MIME 部件）。 */
    final class Candidate {
        final String name;
        final long size;
        final String md5;
        final Object handle;

        /**
         * @param name   附件名
         * @param size   解码后精确字节
         * @param md5    md5（无则 null）
         * @param handle 实现私有句柄（如 IMAP uid + part 号）
         */
        public Candidate(String name, long size, String md5, Object handle) {
            this.name = name;
            this.size = size;
            this.md5 = md5;
            this.handle = handle;
        }

        /** @return 实现私有句柄 */
        public Object handle() {
            return handle;
        }
    }

    /**
     * 回退下载。
     *
     * @param item 网页附件条目
     * @param out  输出流
     */
    void download(QqMailAttachItem item, OutputStream out);

    /**
     * 匹配：名字相同（忽略大小写）且大小精确相等；双方都有 md5 时还须 md5 相等（忽略大小写）。
     * 超大附件直接拒绝；0 个或多个命中都视为失败。
     *
     * @param item       网页条目
     * @param candidates 邮件内附件
     * @return 唯一命中
     */
    static Candidate match(QqMailAttachItem item, List<Candidate> candidates) {
        if (item.isBig()) {
            throw StorageException.unsupported("超大附件不在邮件正文中，无法经 IMAP 回退: " + item.name());
        }
        Candidate hit = null;
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c == null || c.name == null || !c.name.equalsIgnoreCase(item.name()) || c.size != item.size()) {
                    continue;
                }
                if (item.md5() != null && !item.md5().isEmpty() && c.md5 != null && !c.md5.isEmpty()
                        && !c.md5.equalsIgnoreCase(item.md5())) {
                    continue;
                }
                if (hit != null) {
                    throw StorageException.conflict("IMAP 回退命中多个同名同大小附件: " + item.name(), null);
                }
                hit = c;
            }
        }
        if (hit == null) {
            throw StorageException.notFound("IMAP 邮件中未找到匹配附件: " + item.name());
        }
        return hit;
    }
}
