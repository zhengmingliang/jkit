package com.alianga.jkit.storage.engine;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.InputStream;

/**
 * 附件/预览字节流（重定向 URL 不外泄，宿主可直接 inline 输出）。用完须 close。
 *
 * @author 郑明亮
 */
public final class QqMailAttachStream extends FilterInputStream {
    private final String contentType;
    private final String filename;
    private final long contentLength;
    private final Closeable owner;

    QqMailAttachStream(InputStream in, String contentType, String filename, long contentLength, Closeable owner) {
        super(in);
        this.contentType = contentType;
        this.filename = filename;
        this.contentLength = contentLength;
        this.owner = owner;
    }

    /** @return Content-Type（可能 null） */
    public String contentType() {
        return contentType;
    }

    /** @return Content-Disposition 文件名（filename* 优先；可能 null） */
    public String filename() {
        return filename;
    }

    /** @return Content-Length，未知 -1 */
    public long contentLength() {
        return contentLength;
    }

    @Override
    public void close() throws java.io.IOException {
        try {
            super.close();
        } finally {
            if (owner != null) {
                owner.close();
            }
        }
    }
}
