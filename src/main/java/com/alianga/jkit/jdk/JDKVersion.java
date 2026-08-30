package com.alianga.jkit.jdk;

/**
 * 当前运行时 JDK 版本号常量。
 * <p>
 * 版本号取自系统属性 {@code java.specification.version}，用于代码中按 JDK 版本做能力适配。
 */
public final class JDKVersion {
    /**
     * 当前运行时的 JDK 规范版本号，例如 JDK 8 为 {@code 1.8}、JDK 17 为 {@code 17.0}。
     * 无法获取或解析失败时回退为 {@code 1.8}。
     */
    public static final float VERSION;

    static {
        float jdkVersion = 1.8f;
        try {
            // 规范版本号
            String version = System.getProperty("java.specification.version");
            if (version != null) {
                jdkVersion = Float.parseFloat(version);
            }
        } catch (Throwable throwable) {
        }
        VERSION = jdkVersion;
    }
}
