package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SVN 平台声明与注册：把 {@link SvnFileStorage} 以 {@code svn} 平台接入
 * {@code FileStorages}，连接管理界面的平台卡片、表单字段、测试连接随即自动可用，
 * 无需界面层特判。
 *
 * <p>协议字段在 {@code svn / http / https} 三种连接方式间切换：默认 svn://（3690），
 * http / https 走 WebDAV 协议，https 自签名证书可勾选信任。注册幂等，可在
 * 界面初始化与测试启动时重复调用。</p>
 *
 * @author 郑明亮
 */
public final class SvnStorageSupport {
    /** 平台 id（同时作为引擎 id 与连接配置里的 platform 值）。 */
    public static final String PLATFORM_ID = SvnConfig.PLATFORM_ID;

    /** 条目属性：最后修改人（SVN last author；通用键，其他引擎也可用同名键提供属主）。 */
    public static final String ATTR_OWNER = "owner";
    /** 条目属性：条目最后变更的 SVN 版本号。 */
    public static final String ATTR_SVN_REVISION = "svn.revision";
    /** 条目属性：最后变更版本的提交说明（服务端提供时才有）。 */
    public static final String ATTR_SVN_COMMIT_MESSAGE = "svn.commitMessage";
    /** 条目属性：条目在版本库中的完整 URL。 */
    public static final String ATTR_SVN_URL = "svn.url";

    private static volatile boolean registered;

    private SvnStorageSupport() {
    }

    /** 注册 SVN 平台与引擎（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        List<StorageField> fields = java.util.Arrays.asList(
                StorageField.optional("protocol", "协议", "svn",
                        "可选 svn / http / https；svn 走 svn:// 协议（默认 3690 端口），http/https 走 WebDAV"),
                StorageField.required("host", "服务器地址"),
                StorageField.optional("port", "端口", "",
                        "留空按协议默认：svn=3690，http=80，https=443"),
                StorageField.optional("user", "用户名", null, "匿名访问留空"),
                StorageField.secret("password", "密码", false),
                StorageField.optional("basePath", "起始目录", "/",
                        "仓库内目录，如 /trunk/project；留空或 / 为仓库根"),
                StorageField.optional("trustServerCert", "信任 HTTPS 证书", "false",
                        "默认校验证书（安全）；仅当 https 使用自签名 / 私有 CA 证书时才置 true，"
                                + "会接受任何证书、丧失中间人防护，svn 协议下忽略"));

        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("protocol", "svn");
        defaults.put("basePath", "/");
        defaults.put("trustServerCert", "false");

        StoragePlatform platform = new StoragePlatform(
                PLATFORM_ID, "SVN 版本库", PLATFORM_ID, "协议",
                fields, StorageConfig.of(defaults));
        FileStorages.register(platform);
        FileStorages.registerEngine(PLATFORM_ID, new java.util.function.Function<StorageConfig, FileStorage>() {
            @Override
            public FileStorage apply(StorageConfig config) {
                return new SvnFileStorage(config);
            }
        });
        registered = true;
    }
}
