package com.alianga.jkit.storage.webdav.versioned;

import java.time.Instant;
import java.util.Objects;

/**
 * 远端对象元数据（传输层，不含业务语义）。
 *
 * @author 郑明亮
 */
public final class RemoteObject {
    private final String name;
    private final String href;
    private final long size;
    private final Instant lastModified;
    private final String etag;

    public RemoteObject(String name, String href, long size, Instant lastModified, String etag) {
        this.name = name == null ? "" : name;
        this.href = href == null ? "" : href;
        this.size = size;
        this.lastModified = lastModified == null ? Instant.EPOCH : lastModified;
        this.etag = etag == null ? "" : etag;
    }

    public String name() {
        return name;
    }

    public String href() {
        return href;
    }

    public long size() {
        return size;
    }

    public Instant lastModified() {
        return lastModified;
    }

    public String etag() {
        return etag;
    }

    public boolean hasStrongEtag() {
        return etag != null && !etag.trim().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RemoteObject)) {
            return false;
        }
        RemoteObject that = (RemoteObject) o;
        return size == that.size
                && Objects.equals(name, that.name)
                && Objects.equals(href, that.href)
                && Objects.equals(lastModified, that.lastModified)
                && Objects.equals(etag, that.etag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, href, size, lastModified, etag);
    }
}
