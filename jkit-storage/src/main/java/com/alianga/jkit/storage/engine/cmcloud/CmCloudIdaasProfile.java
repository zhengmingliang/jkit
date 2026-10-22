package com.alianga.jkit.storage.engine.cmcloud;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * IDaaS 授权登记常量与本地凭据路径（纯 Java 授权栈，不依赖技能包 / Python）。
 *
 * <p>devId / clientId / skillId / strategyId 等是移动云盘授权体系分配给本工具的
 * 固定登记值（与 cm-cloud-manage 技能包的 auth_profiles.json 同源），直接内置。
 * 连接配置里同名字段留空时一律回落到这里，用户不需要手填。</p>
 *
 * <p>本地凭据与 SDK 同一套目录约定（file 后端、信封加密）：
 * {@code ~/.cmcloud/auth/<agent>/<profile>/<skillId>/} 下放
 * {@code auth_info_ydrz.json}（uuid / accessToken）与 {@code clawUid.json}
 * （设备身份）；fallback 设备文件与 CLI 共享
 * （{@code ~/.cmcloud/auth/default/<skillId>/fallback_clawUid.json}）。</p>
 *
 * @author 郑明亮
 */
public final class CmCloudIdaasProfile {
    /** IDaaS token 服务入口。 */
    public static final String TOKEN_SERVICE_BASE_URL = "https://idaas.cmpassport.com";
    /** 配置下发接口。 */
    public static final String CONFIG_DELIVERY_PATH = "/keyManage/custody/configDelivery";
    /** preAuth（登录取票）接口。 */
    public static final String PRE_AUTH_PATH = "/keyManage/custody/preAuth";
    /** 接口版本 / SDK 版本（签名串里用到，必须与登记值一致）。 */
    public static final String INTERFACE_VERSION = "3.1";
    public static final String SDK_VERSION = "IDaaS_AUT_KMS_v3.1.0";
    /** preAuth 内嵌业务公钥（发布时由 configDelivery 抓取后固化，不从远端拉取）。 */
    public static final String EMBEDDED_PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n"
            + "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCJp+9qWjK4Z9luY/0musMaRV4x\n"
            + "Jyfsn3EQ1OQqgI+2ZaNsqbl5PZWlrp57WfirfZ4Y/9+xVmC2H4rE08Jo4hXjubCI\n"
            + "h3iJdZuVNtrlgYVZ7tyA8yELsbcaFg31fNRfWlp7tZRE5YKnO7Oe3ag2Tt3lHI9z\n"
            + "BC98i9bSXBD1u0TQrwIDAQAB\n"
            + "-----END PUBLIC KEY-----";

    /** 结果码：成功。 */
    public static final String CODE_SUCCESS = "103000";
    /** 结果码：需要用户在浏览器完成授权（响应带 authPageUrl + uuid）。 */
    public static final String CODE_AUTH_REQUIRED = "103001";
    /** 结果码：待授权会话尚未完成（重放已保存 uuid 轮询即可）。 */
    public static final String CODE_PENDING = "130088";

    // ---- 登记常量（auth_profiles.json default profile，勿改动） ----
    public static final String DEV_ID = "1403";
    public static final String CLIENT_ID = "8d9bae50e6b8439aae62ed8148e1967e";
    public static final String SKILL_ID = "5b00f72c4709f27dc9abd117763e0cb6";
    public static final String SKILL_NAME = "中国移动云盘skill";
    public static final String STRATEGY_ID = "d872deb58067415daf2c4e1883aff777";
    public static final String TEMPLATE_ID = "4jqgxa3ofp6acetcuues9a8psdmexuk1";
    public static final String SCOPE_IDS = "10008,10009";

    /** 本工具在 ~/.cmcloud/auth/ 下的智能体目录名（与 CLI 的 agent 命名空间隔离）。 */
    private static final String AGENT_DIR = "jkit";
    private static final String PROFILE_DIR = "default";

    private CmCloudIdaasProfile() {
    }

    /** uuid / accessToken 凭据文件（信封加密，与 SDK file 后端同格式）。 */
    public static Path authInfoFile() {
        return Paths.get(System.getProperty("user.home"), ".cmcloud", "auth",
                AGENT_DIR, PROFILE_DIR, SKILL_ID, "auth_info_ydrz.json");
    }

    /** 持久化设备身份 clawUid（preAuth 成功后写，服务端验证过的值）。 */
    public static Path clawUidFile() {
        return Paths.get(System.getProperty("user.home"), ".cmcloud", "auth",
                AGENT_DIR, PROFILE_DIR, SKILL_ID, "clawUid.json");
    }

    /**
     * 兜底设备文件：与 cmcloud CLI 共享（profile default 的登记路径）。
     * CLI 已生成过 deviceId 时直接沿用，避免同一台机器出现两套设备身份。
     */
    public static Path fallbackClawUidFile() {
        return Paths.get(System.getProperty("user.home"), ".cmcloud", "auth",
                PROFILE_DIR, SKILL_ID, "fallback_clawUid.json");
    }

    /** 本工具自己的业务票据缓存（creditToken 过期后重新取票用）。 */
    public static Path ticketCacheFile() {
        return Paths.get(System.getProperty("user.home"), ".cmcloud", "auth",
                AGENT_DIR, PROFILE_DIR, SKILL_ID, "credit_token.json");
    }
}
