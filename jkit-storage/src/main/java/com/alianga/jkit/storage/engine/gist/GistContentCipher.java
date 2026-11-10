package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.conn.PassphraseCipher;

/**
 * GitHub Gist 文件内容的可选客户端加密层：把 {@code GistPaths} 编码后的文本
 * 包装成「自描述信封」再写进 gist，读取时按信封前缀还原。
 *
 * <p><b>不新写任何加密原语</b>：AEAD 与口令派生全部委托给已有的
 * {@link PassphraseCipher}（PBKDF2WithHmacSHA256 210k 次 + 16B 随机 salt +
 * AES-256-GCM + 12B 随机 IV + 128bit tag + AAD）。信封里自带 KDF / 迭代次数 /
 * salt / iv，因此<b>恢复只需要口令</b>——不依赖本机密钥文件、不依赖当前连接配置，
 * 换一台机器、若干年后只要口令正确就能解开。这是它相比 {@code SecretCipher}
 * （机器本地 {@code .secret-key}，丢了就永久解不开）适合做备份的根本原因。</p>
 *
 * <p><b>存储格式（长期契约，勿改）</b>：{@code JKIT-GIST-ENC:v1:} + 信封 JSON。
 * 读取<b>按前缀判定</b>而非按开关：有前缀 → 必须解密；无前缀 → 按旧明文直通。
 * 因此旧数据、同一 gist 里明文与密文混合、以及「关了开关但留着口令」都能正确读取，
 * 对恢复最稳。加密只覆盖内容，文件名与虚拟目录仍以明文存在。</p>
 *
 * @author 郑明亮
 */
public final class GistContentCipher {
    /** 密文信封前缀：读取时据此判定「这是加密内容」，也是未来格式升级的版本位。 */
    public static final String PREFIX = "JKIT-GIST-ENC:v1:";

    private GistContentCipher() {
    }

    /**
     * @param stored gist {@code content} 字段文本
     * @return true 表示这是本引擎写出的密文信封
     */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * 加密：编码后的文本 → 带前缀的信封。
     *
     * @param password   加密口令（非空）
     * @param encoded    {@link GistPaths#encodeContent(byte[])} 的结果
     * @param objectPath 出错时进异常文案的对象路径
     * @return 可直接写入 {@code files[name].content} 的文本
     */
    public static String encrypt(String password, String encoded, String objectPath) {
        try {
            return PREFIX + PassphraseCipher.encrypt(password, encoded);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw StorageException.io("GitHub Gist 内容加密失败: " + e.getMessage(), objectPath, e);
        }
    }

    /**
     * 解密：带前缀的信封 → 编码后的文本（再交给 {@link GistPaths#decodeContent}）。
     *
     * <p>口令错误与数据篡改都会由 GCM tag 校验失败统一报「口令错误或数据已损坏」，
     * 不区分二者，避免泄露信息。缺口令、信封损坏都转成可行动的 {@link StorageException}。</p>
     *
     * @param password   加密口令；null / 空白表示未配置
     * @param stored     带前缀的信封文本
     * @param objectPath 出错时进异常文案的对象路径
     * @return 编码后的明文文本
     */
    public static String decrypt(String password, String stored, String objectPath) {
        if (password == null || password.trim().isEmpty()) {
            throw StorageException.invalidConfig("GitHub Gist 内容已加密，请在连接配置里填写加密口令"
                    + "后重试（口令丢失无法恢复）: " + objectPath);
        }
        String envelope = stored.substring(PREFIX.length());
        try {
            return PassphraseCipher.decrypt(password, envelope);
        } catch (IllegalArgumentException e) {
            throw StorageException.authFailed("GitHub Gist 加密口令错误或数据已损坏: "
                    + objectPath + "（" + e.getMessage() + "）");
        }
    }
}
