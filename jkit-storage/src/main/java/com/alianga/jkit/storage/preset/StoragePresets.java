package com.alianga.jkit.storage.preset;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 内置平台预设：每个存储平台 → 引擎 + 配置字段定义 + 默认值。
 *
 * <p>支持策略分三档（详见模块 README 的平台支持矩阵）：</p>
 * <ul>
 *   <li>原生引擎：{@code local / oss / azure-blob / go-fastdfs / doge}，按平台官方协议实现
 *       （doge 为管理 API + S3 兼容协议组合引擎）；</li>
 *   <li>S3 兼容引擎：{@code s3}（SigV4 / SigV2 双签名、虚拟主机与路径两种寻址、
 *       ListObjects V1/V2），覆盖 Amazon S3、MinIO、R2 及二十余家 S3 兼容云；</li>
 *   <li>规划中：{@code fastdfs / mongo-gridfs / qingstor}，注册了平台描述但引擎未实现，
 *       创建实例时抛 {@code UNSUPPORTED} 并提示。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public final class StoragePresets {
    private StoragePresets() {
    }

    /**
     * 构造全部内置平台描述。
     *
     * @return 平台列表
     */
    public static List<StoragePlatform> builtin() {
        List<StoragePlatform> platforms = new ArrayList<StoragePlatform>();
        platforms.add(new StoragePlatform("local", "本地文件系统", "local", "本地",
                StorageField.required("basePath", "根目录", "如 /data 或 D:\\data")));
        platforms.add(new StoragePlatform("ftp", "FTP", "ftp", "协议",
                StorageField.required("host", "服务器地址"),
                StorageField.optional("port", "端口", "21", null),
                StorageField.optional("user", "用户名", null, "匿名登录留空"),
                StorageField.secret("password", "密码", false),
                StorageField.optional("basePath", "起始目录", "/", "留空或 / 从服务器根目录开始；填 /home/xxx 从家目录开始"),
                StorageField.optional("passive", "被动模式", "true", "内网 / 防火墙环境建议开启"),
                StorageField.optional("encoding", "控制连接编码", "UTF-8", "部分老服务器需 GBK")));
        platforms.add(new StoragePlatform("sftp", "SFTP", "sftp", "协议",
                StorageField.required("host", "服务器地址"),
                StorageField.optional("port", "端口", "22", null),
                StorageField.required("user", "用户名"),
                StorageField.secret("password", "密码", false),
                StorageField.optional("privateKey", "私钥文件路径", null, "密码与私钥二选一"),
                StorageField.secret("privateKeyPassphrase", "私钥口令", false),
                StorageField.optional("basePath", "起始目录", "/", null),
                StorageField.optional("strictHostKeyChecking", "严格主机指纹校验", "false", null),
                StorageField.optional("channelPool", "并发通道数", "4", "SFTP 通道池上限，1 为串行")));
        platforms.add(new StoragePlatform("webdav", "WebDAV", "webdav", "协议",
                StorageField.required("endpoint", "服务地址", "如 https://dav.example.com/dav"),
                StorageField.optional("user", "用户名", null, null),
                StorageField.secret("password", "密码", false),
                StorageField.optional("basePath", "起始目录", "/", "留空或 / 从服务器根目录开始；填 /home/xxx 从家目录开始")));
        platforms.add(s3("amazon-s3", "Amazon S3", "https://s3.us-east-1.amazonaws.com",
                "公有云", "1"));
        platforms.add(s3("amazon-s3-v2", "Amazon S3（V2 列表）", "https://s3.us-east-1.amazonaws.com",
                "公有云", "2"));
        platforms.add(new StoragePlatform("aliyun-oss", "阿里云 OSS", "oss", "公有云",
                StorageField.required("endpoint", "Endpoint", "如 https://oss-cn-hangzhou.aliyuncs.com"),
                StorageField.required("bucket", "Bucket"),
                StorageField.required("accessKey", "AccessKeyId"),
                StorageField.secret("secretKey", "AccessKeySecret", true),
                StorageField.optional("basePath", "根前缀", "/", null)));
        // 多吉云：付费桶（标准型，官方 API）+ 基础桶（基础型，控制台会话 API 适配）
        // 付费桶：控制台密钥对换 STS 临时凭据 → S3 兼容协议；基础桶：控制台账号登录 → /v2/console/v1/oss/* 会话 API
        platforms.add(new StoragePlatform("doge", "多吉云", "doge", "公有云",
                StorageField.optional("bucketType", "桶类型", "paid",
                        "paid=标准型付费桶（官方 S3 兼容 API），free=基础型桶（控制台会话适配）"),
                StorageField.required("bucket", "Bucket", "业务桶名（如 fee-standard 付费 / meitu、free 基础）"),
                StorageField.optional("accessKey", "AccessKey", null, "付费桶必填：控制台 → 密钥管理获取"),
                StorageField.secret("secretKey", "SecretKey", false, "付费桶必填"),
                StorageField.optional("user", "控制台账号", null, "基础桶必填：多吉云登录手机号"),
                StorageField.secret("password", "控制台密码", false, "基础桶必填"),
                StorageField.optional("domain", "自定义域名", null,
                        "可选：绑定的加速域名（如 cdn.example.com）；留空自动探测桶绑定的域名"),
                StorageField.optional("region", "Region", null, "付费桶 SigV4 区域，留空自动推导"),
                StorageField.optional("basePath", "根前缀", "/", "对象 key 统一加该前缀"),
                StorageField.optional("listVersion", "列表 API 版本", "2",
                        "付费桶：1=ListObjects V1，2=ListObjects V2；基础桶忽略"),
                StorageField.optional("temporaryLinkTtl", "临时链接时效(秒)", "600",
                        "付费桶可配（60~604800，默认 600）；基础桶固定约 600 秒（官方不可配）")));
        platforms.add(s3("huawei-obs", "华为云 OBS", "https://obs.cn-north-4.myhuaweicloud.com",
                "公有云", null));
        platforms.add(s3("tencent-cos", "腾讯云 COS", "https://cos.ap-guangzhou.myqcloud.com",
                "公有云", null, StorageConfig.empty().set("pathStyle", "true").set("md5Secret", "true")));
        platforms.add(s3("qiniu-kodo", "七牛云 Kodo", "https://s3.cn-north-1.qiniucs.com",
                "公有云", null));
        platforms.add(s3("baidu-bos", "百度云 BOS", "https://s3.bj.bcebos.com", "公有云", null));
        platforms.add(s3("upyun-uss", "又拍云 USS", "https://s3.api.upyun.com", "公有云", null));
        platforms.add(s3("minio", "MinIO", "http://127.0.0.1:9000", "S3 兼容", null,
                StorageConfig.empty().set("pathStyle", "true")));
        platforms.add(s3("google-cloud-storage", "Google Cloud Storage", "https://storage.googleapis.com",
                "公有云", null, StorageConfig.empty().set("pathStyle", "true")));
        platforms.add(new StoragePlatform("azure-blob", "Azure Blob Storage", "azure-blob", "公有云",
                StorageField.required("endpoint", "Endpoint", "如 https://account.blob.core.windows.net"),
                StorageField.required("bucket", "Container"),
                StorageField.required("accessKey", "存储账户名"),
                StorageField.secret("secretKey", "存储账户密钥", true),
                StorageField.optional("basePath", "根前缀", "/", null)));
        // Cloudreve V4：账号密码直连（程序自动完成 OAuth2 授权链）+ /api/v4 文件 API；
        // OAuth2 应用凭据有内置默认值（内置桌面应用），自建应用在高级里覆盖
        platforms.add(new StoragePlatform("cloudreve", "Cloudreve", "cloudreve", "协议",
                StorageField.required("siteUrl", "站点地址", "如 https://pan.example.com"),
                StorageField.required("user", "用户名", "登录邮箱"),
                StorageField.secret("password", "密码", true),
                StorageField.optional("basePath", "起始目录", "/", "留空或 / 从网盘根目录开始"),
                StorageField.secret("oauthRefreshToken", "刷新令牌", false,
                        "可选，已有令牌时填它免重新授权；授权成功后自动获取"),
                StorageField.secret("oauthCode", "授权码", false,
                        "可选，浏览器授权后填：<站点>/session/authorize?client_id=<id>"
                                + "&response_type=code&redirect_uri=<回调地址>，"
                                + "把回调地址里的 code 填进来"),
                StorageField.optional("oauthClientId", "OAuth2 Client ID", null,
                        "可选，自建 OAuth2 应用时填（站点管理 → OAuth2 应用）；留空用内置凭据"),
                StorageField.secret("oauthClientSecret", "OAuth2 Client Secret", false,
                        "可选，自建 OAuth2 应用时填；留空用内置凭据"),
                StorageField.optional("oauthRedirectUri", "OAuth2 回调地址", null,
                        "可选，自建应用时填注册的回调地址（须与应用注册值一致）；"
                                + "留空用内置桌面应用的 <站点>/callback/desktop")));
        platforms.add(planned("fastdfs", "FastDFS"));
        platforms.add(planned("mongo-gridfs", "MongoDB GridFS"));
        platforms.add(new StoragePlatform("go-fastdfs", "go-fastdfs", "go-fastdfs", "其他",
                StorageField.required("endpoint", "服务地址", "如 http://127.0.0.1:8080"),
                StorageField.optional("group", "分组", "group1", "URL 中的分组段"),
                StorageField.optional("basePath", "根前缀", "/", null),
                StorageField.secret("token", "鉴权 token", false)));
        platforms.add(s3("volcano-tos", "火山引擎 TOS", "https://tos-s3-cn-beijing.volces.com",
                "公有云", null));
        platforms.add(s3("cloudflare-r2", "Cloudflare R2",
                "https://{accountId}.r2.cloudflarestorage.com", "公有云", null,
                StorageConfig.empty().set("pathStyle", "true")));
        platforms.add(s3("kingsoft-ks3", "金山云 KS3", "https://ks3-cn-beijing.ksyuncs.com",
                "公有云", null, StorageConfig.empty().set("authStyle", "ksyun")));
        platforms.add(s3("meituan-mss", "美团云 MSS", null, "S3 兼容", null));
        platforms.add(s3("jd-oss", "京东云 OSS", "https://s3.cn-north-1.jcloudcs.com", "公有云", null));
        platforms.add(s3("tianyi-oos", "天翼云 OOS", "https://oos-cn.ctyunapi.cn", "公有云", null));
        platforms.add(s3("mobile-eos", "移动云 EOS", null, "公有云", null));
        platforms.add(s3("woyun-oss", "联通沃云 OSS", null, "公有云", null));
        platforms.add(s3("netease-nos", "网易数帆 NOS", "https://nos-eastchina1.126.net", "公有云", null));
        platforms.add(s3("ucloud-us3", "Ucloud US3", null, "公有云", null));
        platforms.add(planned("qingstor", "青云 QingStor"));
        platforms.add(s3("pingan-obs", "平安云 OBS", null, "公有云", null));
        platforms.add(s3("shouyun-oss", "首云 OSS", null, "公有云", null));
        platforms.add(s3("ibm-cos", "IBM COS", "https://s3.us.cloud-object-storage.appdomain.cloud",
                "公有云", null, StorageConfig.empty().set("pathStyle", "true")));
        platforms.add(s3("generic-s3", "其它 S3 兼容存储", null, "S3 兼容", null,
                StorageConfig.empty().set("pathStyle", "true")));
        // 2026-10-15 实测 QQ IMAP：整封 MIME 51,850,514B 接受，53,421,722B 明确 NO "Mail length exceeded"
        // （约 50MiB 整封上限）；取实测上限 -20% ≈ 41.5MB 整封 → maxAttachmentBytes 39MiB
        // （+256K 余量后整封上限 41,156,608B，单片原始 30,063,966B）。
        long qqMax = 39L * 1024L * 1024L;
        platforms.add(new StoragePlatform("qq-mail", "QQ 邮箱", "mail", "邮件",
                Arrays.asList(
                        StorageField.optional("host", "IMAP 服务器", "imap.qq.com", null),
                        StorageField.optional("imapPort", "IMAP 端口", "993", null),
                        StorageField.required("user", "邮箱账号"),
                        StorageField.secret("password", "授权码", true),
                        StorageField.optional("ssl", "SSL", "true", null),
                        StorageField.optional("basePath", "邮箱文件夹", "jkit-mail-disk",
                                "IMAP 文件夹名，默认 jkit-mail-disk"),
                        StorageField.optional("maxAttachmentBytes", "单附件上限字节",
                                String.valueOf(qqMax), "QQ 整封实测约 50MB，默认 39MiB（-20% 余量）"),
                        StorageField.optional("channelPool", "并发通道数", "1",
                                "二期单会话串行；>1 预留，真多连接池待协议层")),
                StorageConfig.empty()
                        .set("host", "imap.qq.com")
                        .set("imapPort", "993")
                        .set("ssl", "true")
                        .set("basePath", "jkit-mail-disk")
                        .set("maxAttachmentBytes", String.valueOf(qqMax))
                        .set("channelPool", "1")));
        // QQ 邮箱网页版附件管理（只读）：sid + cookie 来自扫码登录，凭据不入日志
        platforms.add(new StoragePlatform("qq-mail-attach", "QQ 邮箱附件管理（只读）", "qqmail-attach", "邮件",
                Arrays.asList(
                        StorageField.secret("sid", "会话 sid", true),
                        StorageField.secret("cookie", "Cookie", true),
                        StorageField.secret("ticket", "续期 ticket", false,
                                "可选，用于登录态过期后自动续期：已登录 wx.mail.qq.com 的浏览器按 F12 → "
                                        + "Application（应用）→ Local Storage → https://wx.mail.qq.com，"
                                        + "复制键 ticket:<QQ号> 的整段值；需本机安装 Node.js 16+"),
                        StorageField.secret("ds", "续期 ds", false,
                                "可选，配合 ticket：F12 → Network 里任一 /login/login 请求 URL 的 ds 参数（32 位）"),
                        StorageField.optional("nodePath", "Node 路径", "",
                                "可选，node 可执行文件完整路径；留空自动在 PATH 中查找"),
                        StorageField.optional("sortType", "排序字段", "1", "1 时间 2 fileid 3 大小 4 ctime"),
                        StorageField.optional("sortDirection", "排序方向", "1", "1 降序 2 升序"),
                        StorageField.optional("pageSize", "分页大小", "100", null),
                        StorageField.optional("requestIntervalMs", "请求间隔毫秒", "300", "节流"),
                        StorageField.optional("authRetCodes", "鉴权失败 ret 码", "-3,-101,-102",
                                "待抓包确认"),
                        StorageField.optional("imapFallback", "IMAP 回退连接", "",
                                "引用 mail 引擎连接配置（TODO）")),
                StorageConfig.empty()
                        .set("sortType", "1")
                        .set("sortDirection", "1")
                        .set("pageSize", "100")
                        .set("requestIntervalMs", "300")));
        platforms.add(new StoragePlatform("mail", "IMAP 邮箱", "mail", "邮件",
                Arrays.asList(
                        StorageField.required("host", "IMAP 服务器"),
                        StorageField.optional("imapPort", "IMAP 端口", "993", null),
                        StorageField.required("user", "邮箱账号"),
                        StorageField.secret("password", "授权码", true),
                        StorageField.optional("ssl", "SSL", "true", null),
                        StorageField.optional("basePath", "邮箱文件夹", "jkit-mail-disk",
                                "IMAP 文件夹名，默认 jkit-mail-disk"),
                        StorageField.optional("maxAttachmentBytes", "单附件上限字节",
                                String.valueOf(qqMax), "单附件大小上限"),
                        StorageField.optional("channelPool", "并发通道数", "1",
                                "二期单会话串行；>1 预留，真多连接池待协议层")),
                StorageConfig.empty()
                        .set("imapPort", "993")
                        .set("ssl", "true")
                        .set("basePath", "jkit-mail-disk")
                        .set("maxAttachmentBytes", String.valueOf(qqMax))
                        .set("channelPool", "1")));
        return platforms;
    }

    /**
     * S3 兼容平台描述的公共字段与默认值。
     *
     * @param id              平台 id
     * @param displayName     显示名
     * @param endpointDefault 默认 endpoint，null 表示必须由用户填写
     * @param category        分类
     * @param listVersion     ListObjects 版本（"1"/"2"），null 用引擎默认
     * @param defaults        额外平台默认值
     * @return 平台描述
     */
    private static StoragePlatform s3(String id, String displayName, String endpointDefault,
                                      String category, String listVersion, StorageConfig... defaults) {
        List<StorageField> fields = new ArrayList<StorageField>();
        if (endpointDefault == null) {
            fields.add(StorageField.required("endpoint", "Endpoint", "如 https://s3.example.com"));
        } else {
            fields.add(StorageField.optional("endpoint", "Endpoint", endpointDefault, null));
        }
        fields.add(StorageField.required("bucket", "Bucket"));
        fields.add(StorageField.required("accessKey", "AccessKey"));
        fields.add(StorageField.secret("secretKey", "SecretKey", true));
        fields.add(StorageField.optional("region", "Region", "us-east-1", "SigV4 签名区域"));
        fields.add(StorageField.optional("pathStyle", "路径风格寻址", null, "true=endpoint/bucket/key"));
        fields.add(StorageField.optional("basePath", "根前缀", "/", "对象 key 统一加该前缀"));
        if (listVersion != null) {
            fields.add(StorageField.optional("listVersion", "列表 API 版本", listVersion,
                    "1=ListObjects V1，2=ListObjects V2"));
        }
        StorageConfig merged = StorageConfig.empty();
        if (listVersion != null) {
            merged.set("listVersion", listVersion);
        }
        for (StorageConfig extra : defaults) {
            for (java.util.Map.Entry<String, String> entry : extra.asMap().entrySet()) {
                merged.set(entry.getKey(), entry.getValue());
            }
        }
        return new StoragePlatform(id, displayName, "s3", category, fields, merged);
    }

    /**
     * 规划中的平台：注册了描述但引擎未实现，创建实例时给出明确提示。
     *
     * @param id          平台 id
     * @param displayName 显示名
     * @return 平台描述
     */
    private static StoragePlatform planned(String id, String displayName) {
        return new StoragePlatform(id, displayName, "planned", "规划中",
                Arrays.asList(
                        StorageField.required("endpoint", "服务地址"),
                        StorageField.required("bucket", "Bucket"),
                        StorageField.required("accessKey", "AccessKey"),
                        StorageField.secret("secretKey", "SecretKey", true)),
                null);
    }
}
