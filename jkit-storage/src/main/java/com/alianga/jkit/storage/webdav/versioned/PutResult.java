package com.alianga.jkit.storage.webdav.versioned;

/**
 * PUT 结果。httpStatus == 501 表示实现显式声明不支持该条件写语义（禁止静默降级为普通 PUT）。
 *
 * @author 郑明亮
 */
public final class PutResult {
    private final int httpStatus;
    private final String etag;
    private final String location;
    private final String requestedName;
    private final String effectiveName;
    private final boolean renamed;

    public PutResult(int httpStatus, String etag, String location,
                     String requestedName, String effectiveName, boolean renamed) {
        this.httpStatus = httpStatus;
        this.etag = etag == null ? "" : etag;
        this.location = location == null ? "" : location;
        this.requestedName = requestedName == null ? "" : requestedName;
        this.effectiveName = effectiveName == null ? this.requestedName : effectiveName;
        this.renamed = renamed;
    }

    /** 条件写不被服务器/实现支持时的显式结果（非静默普通 PUT）。 */
    public static PutResult unsupported(String requestedName) {
        String name = requestedName == null ? "" : requestedName;
        return new PutResult(501, "", "", name, name, false);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String etag() {
        return etag;
    }

    public String location() {
        return location;
    }

    public String requestedName() {
        return requestedName;
    }

    public String effectiveName() {
        return effectiveName;
    }

    public boolean renamed() {
        return renamed;
    }

    public boolean success() {
        return httpStatus == 200 || httpStatus == 201 || httpStatus == 204;
    }

    public boolean preconditionFailed() {
        return httpStatus == 412;
    }

    public boolean unsupported() {
        return httpStatus == 501;
    }
}
