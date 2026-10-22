package com.alianga.jkit.storage;

import com.alianga.jkit.storage.engine.AzureBlobFileStorage;
import com.alianga.jkit.storage.engine.CloudreveFileStorage;
import com.alianga.jkit.storage.engine.DogeFileStorage;
import com.alianga.jkit.storage.engine.FtpFileStorage;
import com.alianga.jkit.storage.engine.GoFastDfsFileStorage;
import com.alianga.jkit.storage.engine.LocalFileStorage;
import com.alianga.jkit.storage.engine.MailFileStorage;
import com.alianga.jkit.storage.engine.OssFileStorage;
import com.alianga.jkit.storage.engine.QqMailAttachFileStorage;
import com.alianga.jkit.storage.engine.S3FileStorage;
import com.alianga.jkit.storage.engine.SftpFileStorage;
import com.alianga.jkit.storage.engine.WebDavFileStorage;
import com.alianga.jkit.storage.engine.cmcloud.CmCloudFileStorage;
import com.alianga.jkit.storage.engine.cmcloud.CmCloudStorageSupport;
import com.alianga.jkit.storage.engine.cmcloud.Yun139FileStorage;
import com.alianga.jkit.storage.engine.cmcloud.Yun139StorageSupport;
import com.alianga.jkit.storage.preset.StoragePresets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储门面：一行代码创建任意平台的 {@link FileStorage} 实例。
 *
 * <pre>{@code
 * // 内置平台（30+），按平台 id + 配置创建
 * FileStorage fs = FileStorages.of("oss", StorageConfig.empty()
 *         .set("endpoint", "https://oss-cn-hangzhou.aliyuncs.com")
 *         .set("bucket", "mybucket").set("accessKey", "AK").set("secretKey", "SK"));
 *
 * // 配置驱动的创建（Map 即配置，上层界面无需感知平台差异）
 * Map<String, String> props = new LinkedHashMap<>();
 * props.put("platform", "local");
 * props.put("basePath", "/data");
 * FileStorage local = FileStorages.of(props);
 * }</pre>
 *
 * <p>内置平台清单见 {@link #platforms()}；业务可注册自有平台或整体替换引擎实现
 * （{@link #register}），注册在首次使用前完成即可。</p>
 *
 * @author 郑明亮
 */
public final class FileStorages {
    /** 引擎注册表：引擎 id → 由配置创建实例的工厂。 */
    private static final Map<String, java.util.function.Function<StorageConfig, FileStorage>> ENGINES =
            new ConcurrentHashMap<String, java.util.function.Function<StorageConfig, FileStorage>>();

    /** 平台注册表：平台 id → 平台描述。 */
    private static final Map<String, StoragePlatform> PLATFORMS = new LinkedHashMap<String, StoragePlatform>();

    static {
        ENGINES.put("local", LocalFileStorage::new);
        ENGINES.put("mail", MailFileStorage::new);
        ENGINES.put("qqmail-attach", QqMailAttachFileStorage::new);
        ENGINES.put("ftp", FtpFileStorage::new);
        ENGINES.put("sftp", SftpFileStorage::new);
        ENGINES.put("webdav", WebDavFileStorage::new);
        ENGINES.put("s3", S3FileStorage::new);
        ENGINES.put("oss", OssFileStorage::new);
        ENGINES.put("azure-blob", AzureBlobFileStorage::new);
        ENGINES.put("go-fastdfs", GoFastDfsFileStorage::new);
        ENGINES.put("doge", DogeFileStorage::new);
        ENGINES.put("cloudreve", CloudreveFileStorage::new);
        ENGINES.put("cmcloud", CmCloudFileStorage::new);
        ENGINES.put("yun139", Yun139FileStorage::new);
        for (StoragePlatform platform : StoragePresets.builtin()) {
            PLATFORMS.put(platform.id(), platform);
        }
        // 中国移动云盘两条路线：IDaaS 扫码授权（cmcloud，写操作受专属目录约束）与
        // 网页版同款接口（yun139，全目录可读可写），平台与字段由各自 Support 注册
        CmCloudStorageSupport.register();
        Yun139StorageSupport.register();
    }

    private FileStorages() {
    }

    /**
     * 按平台 id + 配置创建存储实例。平台默认值会合并进传入配置（用户已设置的键优先），
     * 并校验必填字段。
     *
     * @param platformId 平台 id（见 {@link #platforms()}）
     * @param config     连接配置
     * @return 存储实例
     * @throws StorageException 平台不存在或配置非法时抛 {@link StorageErrorCode#INVALID_CONFIG}
     */
    public static FileStorage of(String platformId, StorageConfig config) {
        StoragePlatform platform = PLATFORMS.get(platformId);
        if (platform == null) {
            throw StorageException.invalidConfig("未知的存储平台: " + platformId
                    + "，可用平台见 FileStorages.platforms()");
        }
        StorageConfig merged = config == null ? platform.defaults() : config.withDefaults(platform.defaults());
        java.util.function.Function<StorageConfig, FileStorage> factory = ENGINES.get(platform.engine());
        if (factory == null) {
            throw StorageException.unsupported("平台 " + platformId + " 的引擎 " + platform.engine()
                    + " 未注册（该平台可能仍在规划中）");
        }
        validateRequired(platform, merged);
        return factory.apply(merged);
    }

    /**
     * 按平台 id + 键值对创建存储实例（等同 {@link #of(String, StorageConfig)}）。
     *
     * @param platformId 平台 id
     * @param values     配置键值对，可为 null
     * @return 存储实例
     */
    public static FileStorage of(String platformId, Map<String, String> values) {
        return of(platformId, values == null ? StorageConfig.empty() : StorageConfig.of(values));
    }

    /**
     * 配置驱动创建：配置里必须包含 {@code platform} 键指定平台 id。
     * 图形界面 / 配置文件场景用这个入口，无需为每个平台写死创建逻辑。
     *
     * @param values 配置键值对（含 platform）
     * @return 存储实例
     * @throws StorageException 缺少 platform 键或平台未知时抛 {@link StorageErrorCode#INVALID_CONFIG}
     */
    public static FileStorage of(Map<String, String> values) {
        StorageConfig config = StorageConfig.of(values);
        String platformId = config.get("platform");
        if (platformId == null || platformId.trim().isEmpty()) {
            throw StorageException.invalidConfig("配置缺少 platform 键（存储平台 id）");
        }
        return of(platformId.trim(), config);
    }

    /**
     * 全部内置（及已注册）平台描述，按注册顺序返回。界面上用它动态生成
     * 平台选择列表与配置表单。
     *
     * @return 平台列表（只读）
     */
    public static List<StoragePlatform> platforms() {
        synchronized (PLATFORMS) {
            return Collections.unmodifiableList(new ArrayList<StoragePlatform>(PLATFORMS.values()));
        }
    }

    /**
     * 按平台 id 查询平台描述。
     *
     * @param platformId 平台 id
     * @return 平台描述，未知返回 null
     */
    public static StoragePlatform platform(String platformId) {
        synchronized (PLATFORMS) {
            return PLATFORMS.get(platformId);
        }
    }

    /**
     * 注册（或覆盖）一个平台描述。可与 {@link #registerEngine} 配合接入自定义引擎，
     * 例如某个内网 S3 兼容存储的固定端点模板。
     *
     * @param platform 平台描述
     */
    public static void register(StoragePlatform platform) {
        synchronized (PLATFORMS) {
            PLATFORMS.put(platform.id(), platform);
        }
    }

    /**
     * 注册（或覆盖）一个引擎工厂：引擎 id → 由配置创建 {@link FileStorage}。
     *
     * @param engineId 引擎 id
     * @param factory  工厂
     */
    public static void registerEngine(String engineId,
                                      java.util.function.Function<StorageConfig, FileStorage> factory) {
        ENGINES.put(engineId, factory);
    }

    private static void validateRequired(StoragePlatform platform, StorageConfig config) {
        for (StorageField field : platform.fields()) {
            if (field.isRequired()) {
                String value = config.get(field.key());
                if (value == null || value.trim().isEmpty()) {
                    throw StorageException.invalidConfig(
                            "平台 " + platform.id() + " 缺少必填配置: " + field.key()
                                    + "（" + field.label() + "）");
                }
            }
        }
    }
}
