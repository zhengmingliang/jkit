package com.alianga.jkit.storage.engine.cmcloud;

/**
 * 139 云盘（移动云盘）「新个人云」Web API 常量：端点、公共请求头与契约参数。
 *
 * <p>与 IDaaS V3 接口（{@code openapi.yun.139.com} 网关 + 五项票据，写操作被限制在
 * 智能体专属目录）不同，这是移动云盘<b>网页版使用的个人云 API</b>——AList / OpenList /
 * cloud139 三个独立实现长期验证过的路线：{@code Authorization: Basic {token}} 认证 +
 * {@code Mcloud-Sign} 签名，<b>全目录</b>可读可写，支持上传 / 下载 / 删除 / 移动 /
 * 重命名 / 建目录 / 复制，不受专属目录授权范围约束。</p>
 *
 * <p>API 域名不固定：由路由策略接口按账号动态下发（取 {@code modName=personal} 项的
 * {@code httpsUrl}，形如 {@code https://personal-kd-njs.yun.139.com/hcy}），实例内缓存。</p>
 *
 * @author 郑明亮
 */
final class Yun139Profile {
    /** 平台 id（引擎 id 与连接配置里的 platform 值）。 */
    static final String PLATFORM_ID = "yun139";
    /** 平台显示名。 */
    static final String DISPLAY_NAME = "中国移动云盘（全量）";
    /** 平台分类（界面分组：公有云蓝）。 */
    static final String CATEGORY = "公有云";

    /** 路由策略：按账号解析个人云 API 域名。 */
    static final String ROUTE_POLICY_URL = "https://user-njs.yun.139.com/user/route/qryRoutePolicy";

    /** 个人云根的 parentFileId 约定值（与 IDaaS V3 相同：{@code "/"}）。 */
    static final String PERSONAL_ROOT_ID = "/";

    // ---- 公共请求头（对齐网页版 7.14.0，AList / cloud139 同款）----
    static final String MCLOUD_VERSION = "7.14.0";
    static final String MCLOUD_CLIENT = "10701";
    static final String MCLOUD_CHANNEL = "1000101";
    static final String MCLOUD_CHANNEL_SRC = "10000034";
    static final String DEVICE_INFO = "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||";
    static final String CLIENT_INFO =
            "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||dW5kZWZpbmVk||";
    static final String ORIGIN = "https://yun.139.com";
    static final String REFERER = "https://yun.139.com/w/";

    /** 新个人云摘要算法（大写，区别于 IDaaS V3 的小写 sha256）。 */
    static final String HASH_ALGORITHM = "SHA256";
    static final String CONTENT_TYPE_OCTET = "application/octet-stream";
    /** 上传分片默认大小（与网页版一致；EOS 单对象上限 5GB，分片不得超过）。 */
    static final long DEFAULT_PART_SIZE = 20L * 1024 * 1024;
    static final long MAX_PART_SIZE = 5L * 1024 * 1024 * 1024;
    static final long MIN_PART_SIZE = 1L * 1024 * 1024;
    /** file/create 单次最多携带的分片数，超出部分走 file/getUploadUrl 补取。 */
    static final int MAX_PARTS_PER_CREATE = 100;

    // ---- 连接配置键 ----
    static final String AUTHORIZATION = "authorization";
    static final String ACCOUNT = "account";
    static final String BASE_URL = "baseUrl";
    static final String PART_SIZE_MB = "partSizeMB";
    static final String BASE_PATH = "basePath";

    private Yun139Profile() {
    }
}
