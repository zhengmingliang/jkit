package com.alianga.jkit.storage.engine.cmcloud;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * 139 云盘系的 TLS 1.2 专属工厂（懒加载单例）。
 *
 * <p>实测（2026-10）：139 网关（user-njs.yun.139.com 等个人云域名）会对 JDK 默认的
 * ClientHello（Java 17 默认启用 TLS 1.3）直接断开握手——
 * {@code Remote host terminated the handshake}；把客户端限定到 TLSv1.2 后同一 JVM
 * 同一网络环境立即恢复。这里给引擎的全部 HTTPS 请求统一套 TLSv1.2 专属工厂，
 * 只作用于本引擎的连接（经 {@code HttpCore.Request#sslSocketFactory}），
 * 不改 JVM 全局默认。</p>
 *
 * @author 郑明亮
 */
final class Tls12 {
    /** TLSv1.2 专属上下文创建一次即可（线程安全由 volatile 双检保证）。 */
    private static volatile SSLSocketFactory factory;

    private Tls12() {
    }

    /** @return TLSv1.2 专属 SSL 工厂（默认信任库；创建失败回落 null 走 JVM 默认） */
    static SSLSocketFactory socketFactory() {
        SSLSocketFactory result = factory;
        if (result == null) {
            synchronized (Tls12.class) {
                result = factory;
                if (result == null) {
                    try {
                        // 未显式 init 的 SSLContext 使用 JVM 默认信任库，与全局默认一致
                        result = SSLContext.getInstance("TLSv1.2").getSocketFactory();
                    } catch (Exception e) {
                        // JVM 不支持 TLSv1.2（极老版本）：回落全局默认，由上层按网络错误排障
                        result = null;
                    }
                    factory = result;
                }
            }
        }
        return result;
    }
}
