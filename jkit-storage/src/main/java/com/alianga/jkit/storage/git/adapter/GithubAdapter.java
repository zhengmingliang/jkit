package com.alianga.jkit.storage.git.adapter;

import com.alianga.jkit.storage.git.GitCdn;
import com.alianga.jkit.storage.git.GitSourceConfig;

import java.util.Collections;
import java.util.Map;

/**
 * GitHub（含 GitHub Enterprise）适配：{@code https://api.github.com/repos/{owner}/{repo}}，
 * 鉴权头 {@code Authorization: Bearer <token>}，提交作者在 {@code commit.author.name}。
 *
 * @author 郑明亮
 */
public final class GithubAdapter extends AbstractGitAdapter {
    @Override
    protected String apiPrefix(GitSourceConfig config) {
        return "/repos/" + config.owner() + "/" + config.repo();
    }

    @Override
    public Map<String, String> authHeaders(GitSourceConfig config) {
        // GitHub 也接受 "token"，但对 fine-grained PAT 与 App token 一律用 Bearer
        return config.authenticated()
                ? Collections.singletonMap("Authorization", "Bearer " + config.token())
                : Collections.<String, String>emptyMap();
    }

    @Override
    protected String commitAuthor(Map<String, Object> commit) {
        return nestedCommitAuthor(commit);
    }

    @Override
    public String rawUrl(GitSourceConfig config, String repoPath) {
        // raw.githubusercontent.com 对公开仓库免鉴权；私有仓库走 API 的 download_url。
        // 分支与路径逐段编码：含空格 / # / % / 中文的文件名裸拼会被截断或拒绝
        String host = config.baseUrl();
        String rawHost = "https://api.github.com".equals(host)
                ? "https://raw.githubusercontent.com" : host + "/raw";
        return rawHost + "/" + encodeRawPath(config.owner()) + "/" + encodeRawPath(config.repo())
                + "/" + encodeRawPath(config.branch()) + "/" + encodeRawPath(repoPath);
    }

    /**
     * jsDelivr 的 GitHub 加速路径：{@code /gh/{owner}/{repo}@{branch}/{path}}。
     *
     * <p>注意分支用 {@code @} 连接而不是路径段——这是 jsDelivr 的格式
     * （实测 {@code /gh/zhengmingliang/img@master/2025/copilot-prompt.txt} 返回 200）。</p>
     */
    @Override
    public String cdnPath(GitSourceConfig config, String repoPath) {
        return "/gh/" + config.owner() + "/" + config.repo() + "@" + config.branch()
                + "/" + GitCdn.encodePath(repoPath);
    }
}
