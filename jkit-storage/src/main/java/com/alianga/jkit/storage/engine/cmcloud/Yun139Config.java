package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 139 云盘（全量）连接配置：网页版 Authorization + 登录账号 + 可选 API 地址 / 分片大小 /
 * 起始目录。
 *
 * <p>Authorization 是移动云盘网页版的登录态（{@code Basic} 后的 Base64 串，内含账号与
 * 短期 authToken），获取方式：登录 {@code yun.139.com} → 开发者工具 → 网络面板 → 找
 * {@code hcy/file/list} 请求 → 复制请求头 {@code Authorization} 中 {@code Basic} 后面的
 * 内容（整段粘贴也可以，解析时自动去掉 {@code Basic} 前缀）。它授权访问<b>整个个人云</b>，
 * 不受智能体专属目录约束；过期后平台按鉴权失败返回，重新抓取更新连接配置即可。</p>
 *
 * @author 郑明亮
 */
final class Yun139Config {
    /** 原始引擎配置（把代理 / 超时等通用网络设置透传给 {@code HttpCore}）。 */
    private final StorageConfig raw;
    private final String authorization;
    private final String account;
    private final String baseUrl;
    private final List<String> rootSegments;
    private final long partSize;

    private Yun139Config(StorageConfig raw, String authorization, String account, String baseUrl,
                         List<String> rootSegments, long partSize) {
        this.raw = raw;
        this.authorization = authorization;
        this.account = account;
        this.baseUrl = baseUrl;
        this.rootSegments = rootSegments;
        this.partSize = partSize;
    }

    /**
     * 解析并校验连接配置。
     *
     * @throws StorageException INVALID_CONFIG：缺 Authorization / 账号与 API 地址都不给 /
     *                          API 地址非 https / 分片大小越界
     */
    static Yun139Config from(StorageConfig config) {
        String authorization = trim(config.get(Yun139Profile.AUTHORIZATION));
        // 容错：用户常把 "Basic " 前缀一起粘进来
        if (authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            authorization = trim(authorization.substring(6));
        }
        if (authorization.isEmpty()) {
            throw StorageException.invalidConfig(
                    "139 云盘连接缺少网页版 Authorization：登录 yun.139.com 后在开发者工具"
                            + "网络面板找 hcy/file/list 请求，复制请求头 Authorization 中"
                            + " Basic 后面的内容");
        }
        String account = trim(config.get(Yun139Profile.ACCOUNT));
        String baseUrl = trim(config.get(Yun139Profile.BASE_URL));
        if (baseUrl.isEmpty() && account.isEmpty()) {
            throw StorageException.invalidConfig(
                    "139 云盘连接缺少登录账号：路由策略解析个人云 API 域名需要账号"
                            + "（也可以在「个人云 API 地址」直接填写，两种给一种即可）");
        }
        if (!baseUrl.isEmpty()) {
            if (!baseUrl.startsWith("https://")) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIG,
                        "个人云 API 地址必须是 https:// 开头，当前为：" + baseUrl);
            }
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
        long partSize = Yun139Profile.DEFAULT_PART_SIZE;
        String partText = trim(config.get(Yun139Profile.PART_SIZE_MB));
        if (!partText.isEmpty()) {
            long mb;
            try {
                mb = Long.parseLong(partText);
            } catch (NumberFormatException e) {
                throw StorageException.invalidConfig("上传分片大小必须是数字（MB），当前为：" + partText);
            }
            if (mb < Yun139Profile.MIN_PART_SIZE / (1024 * 1024)
                    || Yun139Profile.MAX_PART_SIZE / (1024 * 1024) < mb) {
                throw StorageException.invalidConfig("上传分片大小须在 1~5120 MB 之间（EOS 单对象"
                        + "上限 5GB），当前为：" + mb);
            }
            partSize = mb * 1024 * 1024;
        }
        List<String> root = new ArrayList<>();
        for (String segment : trim(config.get(Yun139Profile.BASE_PATH)).split("/", -1)) {
            if (!Strs.isBlank(segment)) {
                root.add(segment.trim());
            }
        }
        return new Yun139Config(config, authorization, account, baseUrl,
                Collections.unmodifiableList(root), partSize);
    }

    /** @return 原始引擎配置（代理 / 超时等通用网络设置） */
    StorageConfig raw() {
        return raw;
    }

    /** 不含 {@code Basic} 前缀的网页版 Authorization。 */
    String authorization() {
        return authorization;
    }

    /** 登录账号（手机号 / 139 邮箱；路由策略解析域名与 getUploadUrl 公共账号信息用）。 */
    String account() {
        return account;
    }

    /** 手工指定的个人云 API 地址（空表示按账号经路由策略解析）。 */
    String baseUrl() {
        return baseUrl;
    }

    long partSize() {
        return partSize;
    }

    /** 起始目录的路径段（逻辑根的云端落点；空列表表示云盘根）。 */
    List<String> rootSegments() {
        return rootSegments;
    }

    /** 逻辑路径转云端展示路径（仅文案与「复制路径」用，不参与请求）。 */
    String cloudPath(String logicalPath) {
        String relative = StoragePaths.normalize(logicalPath);
        StringBuilder out = new StringBuilder();
        for (String segment : rootSegments) {
            out.append('/').append(segment);
        }
        if (!relative.isEmpty()) {
            for (String segment : relative.split("/", -1)) {
                out.append('/').append(segment);
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    /** 不含 Authorization 的诊断文本（凭证只露出前 6 位）。 */
    @Override
    public String toString() {
        String auth = authorization.length() <= 6 ? "***" : authorization.substring(0, 6) + "***";
        return "yun139[" + (baseUrl.isEmpty() ? "route:" + account : baseUrl) + " auth=" + auth
                + " partMB=" + (partSize / (1024 * 1024)) + "]";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
