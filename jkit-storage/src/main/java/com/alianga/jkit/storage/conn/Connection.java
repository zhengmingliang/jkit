package com.alianga.jkit.storage.conn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一个存储连接：稳定 id + 显示名 + 平台 id + 键值配置。
 *
 * <p>id 与显示名分开是为了让「改名」不打断引用：同步档案按 id 记连接，
 * 用户把连接从「公司 OSS」改成「生产 OSS」，档案依然认得它。只按名字引用的话，
 * 改名后档案会静默失效，定时同步也就跟着不跑了。</p>
 *
 * @author 郑明亮
 */
public final class Connection {
    private final String id;
    private final String name;
    private final String platform;
    private final Map<String, String> config;

    /**
     * @param id       稳定标识（落盘后不再变；空串表示尚未分配）
     * @param name     显示名（唯一，作为配置文件里的主键）
     * @param platform 平台 id（见 {@code FileStorages.platforms()}）
     * @param config   连接配置（拷贝保存；null 视为空）
     */
    public Connection(String id, String name, String platform, Map<String, String> config) {
        this.id = id;
        this.name = name;
        this.platform = platform;
        // 与原 record 行为一致：防御性拷贝且可变（部分调用方会在副本上继续改字段）
        this.config = config == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(config);
    }

    /**
     * 新建连接：分配一个稳定 id。
     *
     * @param name     显示名
     * @param platform 平台 id
     * @param config   配置
     */
    public Connection(String name, String platform, Map<String, String> config) {
        this(UUID.randomUUID().toString(), name, platform, config);
    }

    /** @return 稳定标识 */
    public String id() {
        return id;
    }

    /** @return 显示名 */
    public String name() {
        return name;
    }

    /** @return 平台 id */
    public String platform() {
        return platform;
    }

    /** @return 连接配置（只读副本） */
    public Map<String, String> config() {
        return config;
    }

    /** 换一个 id（同名连接被覆盖时沿用旧 id，保证档案不断链）。 */
    public Connection withId(String newId) {
        return new Connection(newId, name, platform, config);
    }

    /**
     * 带一个修改后字段的副本（连接编辑用）：保留 id，改名不换身份。
     *
     * @param newName    新名称
     * @param newPlatform 新平台
     * @param newConfig  新配置
     * @return 新连接
     */
    public Connection with(String newName, String newPlatform, Map<String, String> newConfig) {
        return new Connection(id, newName, newPlatform, newConfig);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Connection)) {
            return false;
        }
        Connection that = (Connection) other;
        return java.util.Objects.equals(id, that.id)
                && java.util.Objects.equals(name, that.name)
                && java.util.Objects.equals(platform, that.platform)
                && java.util.Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, name, platform, config);
    }

    @Override
    public String toString() {
        return name;
    }
}
