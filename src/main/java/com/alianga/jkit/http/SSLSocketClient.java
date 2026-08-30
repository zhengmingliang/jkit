package com.alianga.jkit.http;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Created by 郑明亮 on 2019/9/6 14:28.
 */

/**
 * @author 郑明亮
 * @version 1.0
 * @description https 连接支持类
 * @time 2019/9/6 14:28
 */
public class SSLSocketClient {
    /**
     * 获取SSLSocketFactory
     *
     * @return 基于信任全部证书的 TrustManager 构建的 {@link SSLSocketFactory}
     */
    public static SSLSocketFactory getSSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, getTrustManager(), new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * TrustManager
     *
     * @return 只包含一个信任全部证书的 {@link X509TrustManager} 的数组
     */
    public static TrustManager[] getTrustManager() {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
        };
        return trustAllCerts;
    }

    /**
     * 获取信任全部证书的 X509TrustManager
     *
     * @return 不做任何证书校验、可接受签发者列表为空的 {@link X509TrustManager} 实例
     */
    public static X509TrustManager getX509TrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[]{};
            }
        };
    }

    /**
     * 获取HostnameVerifier
     *
     * @return 对任意主机名都校验通过的 {@link HostnameVerifier}
     */
    public static HostnameVerifier getHostnameVerifier() {
        HostnameVerifier hostnameVerifier = (s, sslSession) -> true;
        return hostnameVerifier;
    }
}
