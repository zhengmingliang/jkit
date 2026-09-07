package com.alianga.jkit.notify;

import com.alianga.jkit.config.ConfigLoadOptions;
import com.alianga.jkit.config.ConfigPropertyResolver;

import java.io.File;

/**
 * 实发探测凭证加载：从 {@code ${user.home}/jkit/application.yml} 读取密钥/Token，
 * 避免写进测试源码后被误提交。模板见同模块
 * {@code src/test/resources/jkit-application.yml.example}。
 *
 * <p>用法与 {@link ServerChanBarkChannelTest#serverChanSend} 一致：缺配置时用
 * {@code @Ignore} 或 {@link org.junit.Assume} 跳过，勿让 CI 因缺密钥失败。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class NotifyTestConfig {

    /**
     * 本机实发配置目录（默认 {@code ~/jkit}，文件为其中的 {@code application.yml}）。
     */
    public static final String CONFIG_DIR =
            System.getProperty("user.home") + File.separator + "jkit";

    private NotifyTestConfig() {
    }

    /**
     * 加载本机 jkit 实发配置。
     *
     * @return 已解析的配置（文件不存在时返回空解析结果，取值多为 null）
     */
    public static ConfigPropertyResolver load() {
        return ConfigPropertyResolver.load(ConfigLoadOptions.defaults().addLocation(CONFIG_DIR));
    }

    /**
     * @return {@code ~/jkit/application.yml}（或 yaml/properties）是否存在
     */
    public static boolean configFileExists() {
        File dir = new File(CONFIG_DIR);
        return new File(dir, "application.yml").isFile()
                || new File(dir, "application.yaml").isFile()
                || new File(dir, "application.properties").isFile();
    }

    /**
     * 读取非空字符串配置；缺省或空白时返回 {@code null}。
     *
     * @param key 扁平化 key，如 {@code telegram.botToken}
     * @return 非空值，或 {@code null}
     */
    public static String requiredString(String key) {
        String value = load().getString(key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
