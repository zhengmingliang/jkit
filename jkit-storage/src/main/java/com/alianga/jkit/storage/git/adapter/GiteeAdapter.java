package com.alianga.jkit.storage.git.adapter;

import com.alianga.jkit.storage.git.GitSourceConfig;
import com.alianga.jkit.storage.git.GitTransport;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * Gitee（码云）适配：{@code https://gitee.com/api/v5/repos/{owner}/{repo}}，
 * 鉴权走查询参数 {@code access_token}（Gitee 的私人令牌不支持 Bearer 头），
 * 提交作者在 {@code commit.author.name}，分页参数名与 GitHub 一致。
 *
 * @author 郑明亮
 */
public final class GiteeAdapter extends AbstractGitAdapter {
    @Override
    protected String apiPrefix(GitSourceConfig config) {
        return "/api/v5/repos/" + config.owner() + "/" + config.repo();
    }

    @Override
    public Map<String, String> authHeaders(GitSourceConfig config) {
        // Gitee 只认查询参数里的 access_token
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> authParams(GitSourceConfig config) {
        return config.authenticated()
                ? Collections.singletonMap("access_token", config.token())
                : Collections.<String, String>emptyMap();
    }

    @Override
    protected String commitAuthor(Map<String, Object> commit) {
        return nestedCommitAuthor(commit);
    }

    @Override
    public String createMethod(boolean updating) {
        // Gitee：新建用 POST，更新用 PUT；PUT 新建会得到 400 "sha is missing"
        return updating ? "PUT" : "POST";
    }

    @Override
    public String rawUrl(GitSourceConfig config, String repoPath) {
        // 分支与路径逐段编码：含空格 / # / % / 中文的文件名裸拼会被截断或拒绝
        return config.baseUrl() + "/" + encodeRawPath(config.owner()) + "/"
                + encodeRawPath(config.repo()) + "/raw/" + encodeRawPath(config.branch())
                + "/" + encodeRawPath(repoPath);
    }

    @Override
    public InputStream openRaw(GitTransport transport, GitSourceConfig config,
                               String repoPath, String url, String path) {
        // Gitee 的 raw 地址同样只认 access_token：拼上再发。
        // 必须走 appendQuery——query() 自带前导 ?，手工再补分隔符会叠出
        // ??access_token=，服务端解析出的参数名带 ?，令牌等于没带
        String target = url == null || url.trim().isEmpty() ? rawUrl(config, repoPath) : url;
        String authorized = config.authenticated()
                ? GitTransport.appendQuery(target,
                        Collections.singletonMap("access_token", config.token()))
                : target;
        return transport.openStream(authorized, Collections.<String, String>emptyMap(), path);
    }
}
