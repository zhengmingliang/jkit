package com.alianga.jkit.storage;

import com.alianga.jkit.storage.internal.MimeTypes;

/**
 * 文件类型辅助：按扩展名推断 Content-Type、判断是否像文本文件。
 * 上传与「在线编辑」入口的依据。
 *
 * @author 郑明亮
 */
public final class StorageFiles {
    private StorageFiles() {
    }

    /**
     * 按路径扩展名推断 Content-Type。
     *
     * @param path 对象路径
     * @return Content-Type，未知类型为 {@code application/octet-stream}
     */
    public static String contentType(String path) {
        return MimeTypes.of(path);
    }

    /**
     * 判断扩展名是否属于常见文本类型（界面据此决定能否直接进编辑器）。
     *
     * @param path 对象路径
     * @return true 表示像文本文件
     */
    public static boolean looksLikeText(String path) {
        return MimeTypes.looksLikeText(path);
    }
}
