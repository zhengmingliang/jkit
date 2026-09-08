package com.alianga.jkit.notify;

import com.alianga.jkit.config.ConfigLoadOptions;
import com.alianga.jkit.config.ConfigPropertyResolver;

import org.junit.Assume;

import java.io.File;

/**
 * 实发探测凭证加载与门控：从 {@code ${user.home}/jkit/application.yml} 读取密钥/Token，
 * 避免写进测试源码后被误提交。模板见同模块
 * {@code src/test/resources/jkit-application.yml.example}。
 *
 * <p>所有会打到真实第三方的测试须同时满足：
 * <ol>
 *   <li>{@link #assumeLiveEnabled()}：显式 {@code -Djkit.notify.live=true}（yml 有密钥不够）</li>
 *   <li>对应凭证齐全：缺配置时用 {@link Assume} 跳过，勿让 CI 因缺密钥失败</li>
 * </ol>
 * 默认 {@code mvn test} 始终离线，不会实发。
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

    /**
     * 实发开关系统属性（canonical）：{@code -Djkit.notify.live=true}。
     * 缺省为 false；仅显式打开时 {@link #assumeLiveEnabled()} 才放行。
     */
    public static final String LIVE_PROP = "jkit.notify.live";

    private NotifyTestConfig() {
    }

    /**
     * 实发门控：默认关闭。仅当系统属性 {@value #LIVE_PROP} 为 {@code true} 时放行。
     * yml 里有密钥不够——必须手动加 {@code -Djkit.notify.live=true}。
     */
    public static void assumeLiveEnabled() {
        Assume.assumeTrue(
                "set -Djkit.notify.live=true to run live sends (yml keys alone are not enough)",
                Boolean.parseBoolean(System.getProperty(LIVE_PROP, "false")));
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
