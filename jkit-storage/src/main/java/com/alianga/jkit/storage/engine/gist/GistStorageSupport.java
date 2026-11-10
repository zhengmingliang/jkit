package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;
import com.alianga.jkit.storage.cap.StorageCapabilities;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Gist 平台声明与注册：把 {@link GistFileStorage} 以 {@code github-gist}
 * 平台接入 {@link FileStorages}。注册幂等，界面初始化与测试启动时可重复调用。
 *
 * <p><b>能力如实声明</b>：Gist 是扁平文本片段，无原生目录（目录由文件名编码模拟）、
 * 单文件有 1 MB 上限、删除不进回收站。界面按能力渲染，不按平台 id 写 if。</p>
 *
 * @author 郑明亮
 */
public final class GistStorageSupport {
    private static volatile boolean registered;

    private GistStorageSupport() {
    }

    /**
     * @param platformId 平台 id
     * @return true 表示为 GitHub Gist 平台
     */
    public static boolean isGist(String platformId) {
        return GistConfig.PLATFORM_ID.equals(platformId);
    }

    /** 注册平台、引擎与能力（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        Map<String, String> defaults = new LinkedHashMap<String, String>();
        defaults.put(GistConfig.API_BASE, GistConfig.DEFAULT_API_BASE);
        // 旧连接没有这个键，withDefaults 会补成 off：默认明文，向后兼容
        defaults.put(GistConfig.ENCRYPTION, "off");

        StoragePlatform platform = new StoragePlatform(
                GistConfig.PLATFORM_ID, GistConfig.DISPLAY_NAME, GistConfig.PLATFORM_ID,
                GistConfig.CATEGORY, fields(), StorageConfig.of(defaults));
        FileStorages.register(platform);
        FileStorages.registerEngine(GistConfig.PLATFORM_ID, new java.util.function.Function<StorageConfig, FileStorage>() {
            @Override
            public FileStorage apply(StorageConfig config) {
                return new GistFileStorage(config);
            }
        });
        FileStorages.registerCapabilities(GistConfig.PLATFORM_ID,
                StorageCapabilities.standard()
                        .withMaxUploadBytes(GistConfig.MAX_UPLOAD_BYTES)
                        .withFolderOpsEmulated());
        registered = true;
    }

    private static List<StorageField> fields() {
        return Arrays.asList(
                StorageField.secret(GistConfig.TOKEN, "访问令牌", true,
                        "GitHub → Settings → Developer settings → Personal access tokens；"
                                + "经典 PAT 需勾选 gist 范围，细粒度令牌需给 Gists 读写权限"),
                StorageField.required(GistConfig.GIST_ID, "Gist ID",
                        "gist.github.com 地址最后一段，如 6cad326836d38bd3a7ae；"
                                + "也可直接粘贴完整地址，程序会自动取 ID"),
                StorageField.optional(GistConfig.API_BASE, "API 地址",
                        GistConfig.DEFAULT_API_BASE,
                        "公网默认 https://api.github.com；GitHub Enterprise 填 https://域名/api/v3"),
                StorageField.choice(GistConfig.ENCRYPTION, "客户端加密", "off",
                        "on = 写入前用下方口令加密文件内容（gist 里只存密文）；off = 明文存储。"
                                + "只加密内容，文件名与目录结构仍可见。口令丢失无法恢复，请妥善保管",
                        false,
                        new StorageField.Option("off", "关闭（明文存储）"),
                        new StorageField.Option("on", "开启（客户端加密）")),
                StorageField.secret(GistConfig.CONTENT_PASSWORD, "加密口令", false,
                        "仅在「客户端加密」选 on 时必填；恢复数据全靠它，丢失后无法找回。"
                                + "点右侧小眼睛可显示明文核对；改口令不会重新加密已有文件"),
                StorageField.secret(GistConfig.CONTENT_PASSWORD_CONFIRM, "确认加密口令", false,
                        "再输一遍上面的加密口令，防止手误；仅创建 / 修改时校验，不会保存到连接里"));
    }
}
