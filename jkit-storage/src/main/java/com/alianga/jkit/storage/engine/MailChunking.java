package com.alianga.jkit.storage.engine;

/**
 * 邮件分卷尺寸计算：由附件上限推导单封 MIME 上限与单片原始字节数，
 * 保证 Base64（每 76 字符 CRLF）膨胀后整封 MIME 不超过会话侧校验。
 *
 * @author 郑明亮
 */
final class MailChunking {
    /** 会话侧整封 MIME 允许超出 maxAttachmentBytes 的余量。 */
    static final long MIME_SLACK_BYTES = 256L * 1024L;
    /** 头部 / multipart 边界 / 正文等非附件开销预留。 */
    static final long HEADER_RESERVE_BYTES = 16L * 1024L;
    /** Base64 每行 76 字符对应原始 57 字节。 */
    private static final long RAW_PER_LINE = 57L;
    /** 编码后每行 76 字符 + CRLF。 */
    private static final long ENCODED_PER_LINE = 78L;

    private MailChunking() {
    }

    /**
     * 会话接受的整封 MIME 上限。
     *
     * @param maxAttachmentBytes 附件上限
     * @return MIME 上限
     */
    static long maxMimeBytes(long maxAttachmentBytes) {
        return maxAttachmentBytes + MIME_SLACK_BYTES;
    }

    /**
     * 单片（或单封）原始字节上限：
     * {@code min(maxAttachment, floor((maxMime - headerReserve) / 78) * 57)}，
     * 即按含 CRLF 的 Base64 行数折算，且不超过附件上限本身。
     *
     * @param maxAttachmentBytes 附件上限
     * @return 单片原始字节数（至少 1）
     */
    static long partRawBytes(long maxAttachmentBytes) {
        long avail = maxMimeBytes(maxAttachmentBytes) - HEADER_RESERVE_BYTES;
        long byEncoding = (avail / ENCODED_PER_LINE) * RAW_PER_LINE;
        return Math.max(1L, Math.min(maxAttachmentBytes, byEncoding));
    }
}
