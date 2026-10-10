package com.alianga.jkit.storage.sync;

/**
 * 对比模式：同步 / 迁移前判断两侧文件是否一致的策略。
 *
 * <p>跨平台场景下修改时间与 ETag 不总可靠（FTP 可能拿不到 mtime、S3 分片上传的
 * ETag 不是 MD5），所以默认只比大小；需要更严格时再启用其它维度。</p>
 *
 * @author 郑明亮
 */
public enum CompareMode {
    /** 只比文件大小（默认，速度最快、跨平台最稳）。 */
    SIZE,
    /** 大小一致再比修改时间；任一侧时间未知时退化为只比大小。 */
    SIZE_AND_MTIME,
    /** 大小一致再比 ETag；任一侧 ETag 未知时退化为只比大小。 */
    SIZE_AND_ETAG,
    /** 无条件视为不同：全量迁移用。 */
    ALWAYS
}
