package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * WebDAV引擎（占位）：功能实现中，所有操作先返回 UNSUPPORTED。
 *
 * @author 郑明亮
 */
public class WebDavFileStorage implements FileStorage {
    private final StorageConfig config;

    /** 由配置创建。
     *
     * @param config 连接配置
     */
    public WebDavFileStorage(StorageConfig config) {
        this.config = config;
    }

    @Override
    public String platform() {
        return "webdav";
    }

    @Override
    public boolean exists(String path) {
        throw unsupported();
    }

    @Override
    public StorageEntry stat(String path) {
        throw unsupported();
    }

    @Override
    public List<StorageEntry> list(String dir) {
        throw unsupported();
    }

    @Override
    public void mkdir(String dir) {
        throw unsupported();
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        throw unsupported();
    }

    @Override
    public void download(String path, OutputStream out) {
        throw unsupported();
    }

    @Override
    public void delete(String path) {
        throw unsupported();
    }

    @Override
    public void copy(String src, String dst) {
        throw unsupported();
    }

    @Override
    public void rename(String src, String dst) {
        throw unsupported();
    }

    private StorageException unsupported() {
        return StorageException.unsupported("WebDavFileStorage 引擎实现中");
    }
}
