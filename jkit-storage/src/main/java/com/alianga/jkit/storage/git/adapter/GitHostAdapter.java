package com.alianga.jkit.storage.git.adapter;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.git.GitCommit;
import com.alianga.jkit.storage.git.GitEntry;
import com.alianga.jkit.storage.git.GitSourceConfig;
import com.alianga.jkit.storage.git.GitTransport;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * Git 托管平台差异的收敛点：四家只在这里做 baseUrl、鉴权头、API 路径、JSON 字段的适配。
 *
 * <p>统一模型（由本接口表达，具体实现见各 Adapter）：
 * <ul>
 *   <li>列目录：{@code GET contents/{path}?ref=branch} → 数组；</li>
 *   <li>读文件：{@code GET contents/{path}?ref=branch} → 单对象（含 base64 content / download_url）；</li>
 *   <li>写文件：{@code PUT contents/{path}}，body 带 message / content / sha / branch；</li>
 *   <li>删文件：{@code DELETE contents/{path}}，body 带 message / sha / branch；</li>
 *   <li>提交记录：{@code GET commits?path=...&sha=branch} → 数组。</li>
 * </ul>
 * 平台差异（鉴权头形态、路径前缀、作者字段嵌套、分页参数）全部由实现吸收，
 * 上层的 {@code GitStorageProvider} 只认这套语义。</p>
 *
 * @author 郑明亮
 */
public interface GitHostAdapter {
    /**
     * 鉴权头：GitHub 用 {@code Authorization: Bearer}，Gitea / Gogs 用 {@code token}，
     * Gitee 走查询参数（因此返回空 map）。
     *
     * @return 请求头；无令牌时为空 map
     */
    Map<String, String> authHeaders(GitSourceConfig config);

    /**
     * 需要额外拼到查询串里的鉴权参数（Gitee 用 {@code access_token}）。
     *
     * @param config 连接配置
     * @return 查询参数；无则空 map
     */
    default Map<String, String> authParams(GitSourceConfig config) {
        return Collections.emptyMap();
    }

    /**
     * 仓库内容 API 的前缀（不含首尾斜杠的处理由实现决定）。
     *
     * @param config 连接配置
     * @return 形如 {@code https://api.github.com/repos/o/r/contents}
     */
    String contentsUrl(GitSourceConfig config);

    /**
     * 提交记录 API 的地址。
     *
     * @param config 连接配置
     * @return 形如 {@code https://api.github.com/repos/o/r/commits}
     */
    String commitsUrl(GitSourceConfig config);

    /**
     * 仓库内路径拼到 URL 上的形态：GitHub / Gitee 直接拼，Gitea / Gogs 要求每段 URL 编码。
     *
     * @param repoPath 仓库内路径（可能含中文 / 空格）
     * @return URL 路径片段（不含查询串）
     */
    String encodePath(String repoPath);

    /**
     * 把「列目录 / 读文件」的响应解析成条目。
     *
     * @param body   响应体
     * @param path   逻辑路径（进异常文案）
     * @param expectFile true 表示调用方要的是单个文件；false 表示要目录清单
     * @return 条目列表（expectFile 时最多一个元素）
     */
    java.util.List<GitEntry> parseEntries(String body, String path, boolean expectFile);

    /**
     * 解析「读文件」响应里的字节内容（base64 解码；不含内容时返回 null）。
     *
     * @param body 响应体
     * @param path 逻辑路径
     * @return 字节数组；响应不含内容返回 null
     */
    byte[] parseContent(String body, String path);

    /**
     * 从已解析好的元数据对象里取字节内容（避免把 map 重新序列化一遍）。
     *
     * @param meta 已解析的「读文件」响应对象
     * @param path 逻辑路径
     * @return 字节数组；不含内容返回 null
     */
    default byte[] parseContentObject(Map<String, Object> meta, String path) {
        return parseContent(com.alianga.jkit.json.JSON.toJsonString(meta), path);
    }

    /**
     * 从「读文件 / 列目录」响应里取原始下载地址（用于大文件不经 base64 的流式下载）。
     *
     * @param body 响应体
     * @return 下载地址；无则 null
     */
    String parseDownloadUrl(String body);

    /**
     * 解析提交记录数组。作者字段嵌套各家不同，取不到返回 {@code null}（界面显示「未知」）。
     *
     * @param body 响应体
     * @param path 逻辑路径
     * @return 提交列表（按时间倒序，最新在前）
     */
    java.util.List<GitCommit> parseCommits(String body, String path);

    /**
     * 提交记录的每页条数参数名与上限（GitHub / Gitee 用 per_page，Gitea / Gogs 用 limit）。
     *
     * @return 参数名
     */
    String pageSizeParam();

    /**
     * 提交记录的分页参数名（GitHub / Gitee 用 page，Gitea / Gogs 也用 page）。
     *
     * @return 参数名
     */
    default String pageParam() {
        return "page";
    }

    /**
     * 读文件的原始内容地址（{@code download_url} 拿不到时的兜底）。
     *
     * @param config    连接配置
     * @param repoPath  仓库内路径
     * @return 原始地址
     */
    String rawUrl(GitSourceConfig config, String repoPath);

    /**
     * CDN 加速路径（不含域名，以 {@code /} 开头）：CDN 站在 Git 服务前面，
     * 路径形状沿用各家 raw 路径；GitHub 走 jsDelivr 的 {@code /gh/} 特例。
     *
     * <p>默认实现给不出加速路径（返回 null 表示本平台不支持 CDN），
     * 支持的平台各自覆盖。</p>
     *
     * @param config    连接配置
     * @param repoPath  仓库内路径
     * @return CDN 路径；不支持返回 null
     */
    default String cdnPath(GitSourceConfig config, String repoPath) {
        return null;
    }

    /**
     * 请求体的 branch 字段名（GitHub / Gogs 用 branch，Gitee / Gitea 用 branch 也接受，
     * 但 Gogs 老版本只认 branch）。预留给将来出现差异的平台。
     *
     * @return 字段名
     */
    default String branchField() {
        return "branch";
    }

    /**
     * 写内容的 HTTP 方法：GitHub / Gitea / Gogs 一律 {@code PUT}（新建不带 sha、
     * 更新带 sha）；<b>Gitee 例外</b>——新建必须 {@code POST}，PUT 新建会报
     * {@code 400 sha is missing}。
     *
     * @param updating true 表示更新已有文件（带 sha），false 表示新建
     * @return HTTP 方法名
     */
    default String createMethod(boolean updating) {
        return "PUT";
    }

    /**
     * 上传 / 删除前的预检：部分平台对空令牌或未授权仓库的报错不清晰，
     * 这里给出平台特定的明确提示。
     *
     * @param config     连接配置
     * @param forWrite   true 表示写操作
     * @throws StorageException 配置不支持该操作
     */
    default void precheck(GitSourceConfig config, boolean forWrite) {
        if (forWrite && !config.authenticated()) {
            throw StorageException.authFailed(
                    "写操作需要访问令牌：请在连接配置里填写 "
                            + config.host().displayName() + " 的令牌（Token）");
        }
    }

    /**
     * 打开原始内容流：大文件不经 base64，直接走 {@code download_url} 或 raw 接口。
     *
     * @param transport 传输层
     * @param config    连接配置
     * @param repoPath  仓库内路径（raw 兜底地址用）
     * @param url       下载地址（{@code download_url}；为 null 时用 {@link #rawUrl}）
     * @param path      逻辑路径（进异常文案）
     * @return 输入流（调用方负责关闭）
     */
    default InputStream openRaw(GitTransport transport, GitSourceConfig config,
                                String repoPath, String url, String path) {
        String target = url == null || url.trim().isEmpty() ? rawUrl(config, repoPath) : url;
        return transport.openStream(target, authHeaders(config), path);
    }
}
