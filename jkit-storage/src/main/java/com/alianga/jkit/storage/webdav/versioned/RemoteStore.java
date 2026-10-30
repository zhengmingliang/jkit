package com.alianga.jkit.storage.webdav.versioned;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 远端对象存储传输接口（与具体 WebDAV 客户端解耦）。
 *
 * <p>不含 UI 类型。版本号与业务索引由上层 {@link VersionedSyncEngine} 维护。</p>
 *
 * <p>{@link #putIfAbsent} / {@link #putIfMatch} 在实现不支持条件写时必须返回
 * {@link PutResult#unsupported(String)}，禁止静默降级为普通 PUT。</p>
 *
 * @author 郑明亮
 */
public interface RemoteStore extends AutoCloseable {
    /**
     * 列出目录下文件（实现方可过滤；调用方再按业务名过滤）。
     *
     * @return 对象列表
     * @throws IOException 传输失败
     */
    List<RemoteObject> list() throws IOException;

    /**
     * @param name 文件名
     * @return 元数据；不存在则 empty
     * @throws IOException 传输失败
     */
    Optional<RemoteObject> stat(String name) throws IOException;

    /**
     * @param name      远端文件名
     * @param localFile 本地目标
     * @throws IOException 失败
     */
    void get(String name, Path localFile) throws IOException;

    /**
     * 仅当远端不存在该名时写入（优先 If-None-Match:*）。
     * 不支持条件写时返回 {@link PutResult#unsupported(String)}，禁止静默普通 PUT。
     *
     * @param name      远端文件名
     * @param localFile 本地源
     * @return PUT 结果
     * @throws IOException 失败
     */
    PutResult putIfAbsent(String name, Path localFile) throws IOException;

    /**
     * 仅当远端 ETag 匹配时覆盖。
     * 不支持条件写时返回 {@link PutResult#unsupported(String)}。
     *
     * @param name         远端文件名
     * @param localFile    本地源
     * @param expectedEtag 期望强 ETag
     * @return PUT 结果；412 时 {@link PutResult#preconditionFailed()}
     * @throws IOException 失败
     */
    PutResult putIfMatch(String name, Path localFile, String expectedEtag) throws IOException;

    /**
     * 强制覆盖（若支持 {@code replace: 1} 等）。
     *
     * @param name      远端文件名
     * @param localFile 本地源
     * @return PUT 结果
     * @throws IOException 失败
     */
    PutResult putReplace(String name, Path localFile) throws IOException;

    /**
     * @param name 远端文件名
     * @throws IOException 失败（404 可视为成功）
     */
    void delete(String name) throws IOException;

    /**
     * 探测可写性与条件写 / 覆盖 / ETag / replace 能力。
     *
     * @return 能力
     * @throws IOException 认证/网络/目录等硬失败
     */
    StoreCapabilities probeCapabilities() throws IOException;

    @Override
    default void close() {
        // 默认无资源
    }
}
