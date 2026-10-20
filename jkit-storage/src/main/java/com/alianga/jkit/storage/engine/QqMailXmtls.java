package com.alianga.jkit.storage.engine;

/**
 * QQ 邮箱网页 xmtls 加解密（/login/login web-pwd 续期用）。
 *
 * <p>同一次请求必须用 {@link #encrypt} 返回的会话句柄去 {@link #decrypt} 响应——
 * 响应只能由发请求的那个客户端（临时 ECDH 私钥只在内存）解开。明文含 pwd_key，实现不得记日志。</p>
 *
 * @author 郑明亮
 */
interface QqMailXmtls extends AutoCloseable {
    /** 加密结果：会话句柄 + 要 POST 的密文体。 */
    final class Sealed {
        final long handle;
        final String body;

        Sealed(long handle, String body) {
            this.handle = handle;
            this.body = body;
        }

        @Override
        public String toString() {
            return "Sealed{handle=" + handle + ", body=redacted(" + (body == null ? 0 : body.length()) + ")}";
        }
    }

    /**
     * @param pwdKey    ticket 里的 pwd_key（为空走内置 psk，即 web-commkey）
     * @param plainJson 明文 JSON
     * @return 句柄与密文
     */
    Sealed encrypt(String pwdKey, String plainJson);

    /**
     * 解密并释放会话。
     *
     * @return 明文；解不开返回 null
     */
    String decrypt(long handle, String cipherText);

    /** 释放会话（不解密）。 */
    void release(long handle);

    @Override
    void close();
}
