package com.alianga.jkit.storage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 存储平台描述：平台 id、显示名、所属引擎与配置字段定义。
 *
 * <p>内置平台清单见 {@code preset.StoragePresets}；业务也可通过
 * {@link FileStorages#register} 注册自有平台（比如某个内网 S3 兼容存储的固定端点模板）。</p>
 *
 * @author 郑明亮
 */
public final class StoragePlatform {
    /** 平台 id（kebab-case，如 {@code aliyun-oss}），同时是 {@link FileStorages#of} 的平台参数。 */
    private final String id;
    /** 平台显示名。 */
    private final String displayName;
    /** 渲染该平台实际能力的引擎 id（local / ftp / sftp / webdav / s3 / oss / azure-blob / go-fastdfs）。 */
    private final String engine;
    /** 分类（用于界面分组）：本地 / 协议 / 公有云 / S3 兼容。 */
    private final String category;
    /** 配置字段定义。 */
    private final List<StorageField> fields;
    /** 平台级默认配置值（合并进用户配置，用户已设置的键优先）。 */
    private final StorageConfig defaults;

    public StoragePlatform(String id, String displayName, String engine, String category,
                           List<StorageField> fields, StorageConfig defaults) {
        this.id = id;
        this.displayName = displayName;
        this.engine = engine;
        this.category = category;
        this.fields = Collections.unmodifiableList(fields);
        this.defaults = defaults == null ? StorageConfig.empty() : defaults;
    }

    /**
     * 便捷构造：无平台默认值。
     *
     * @param id          平台 id
     * @param displayName 显示名
     * @param engine      引擎 id
     * @param category    分类
     * @param fields      字段定义
     */
    public StoragePlatform(String id, String displayName, String engine, String category,
                           StorageField... fields) {
        this(id, displayName, engine, category, Arrays.asList(fields), null);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String engine() {
        return engine;
    }

    public String category() {
        return category;
    }

    public List<StorageField> fields() {
        return fields;
    }

    public StorageConfig defaults() {
        return defaults;
    }

    @Override
    public String toString() {
        return id + "(" + engine + ")";
    }
}
