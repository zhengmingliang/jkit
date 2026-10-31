package com.alianga.jkit.storage.git.adapter;

import com.alianga.jkit.storage.git.GitCdn;
import com.alianga.jkit.storage.git.GitSourceConfig;

import java.util.Collections;
import java.util.Map;

/**
 * Gitea（自建）适配：{@code {baseUrl}/api/v1/repos/{owner}/{repo}}，
 * 鉴权头 {@code Authorization: token <token>}，提交作者在 {@code author.username}
 * （老版本可能是 {@code author.full_name}），分页参数用 {@code limit}。
 *
 * @author 郑明亮
 */
public final class GiteaAdapter extends AbstractGitAdapter {
    @Override
    protected String apiPrefix(GitSourceConfig config) {
        return "/api/v1/repos/" + config.owner() + "/" + config.repo();
    }

    @Override
    public Map<String, String> authHeaders(GitSourceConfig config) {
        return config.authenticated()
                ? Collections.singletonMap("Authorization", "token " + config.token())
                : Collections.<String, String>emptyMap();
    }

    @Override
    protected String commitAuthor(Map<String, Object> commit) {
        // Gitea：author 是用户对象，用户名在 username（1.21+ 也有 login）
        String user = authorFrom(commit, "username", "login", "full_name", "name");
        if (user != null) {
            return user;
        }
        // 作者账号已注销 / 邮箱未关联账号时没有用户对象，退到 git 提交里的署名
        return nestedCommitAuthor(commit);
    }

    @Override
    public String pageSizeParam() {
        return "limit";
    }

    @Override
    public String rawUrl(GitSourceConfig config, String repoPath) {
        // 路径逐段编码：含空格 / # / % / 中文的文件名裸拼会被截断或拒绝；
        // ref 是查询参数值（非路径段），分支里的 / 也要编码，用 pctEncode
        return config.baseUrl() + "/api/v1/repos/" + encodeRawPath(config.owner()) + "/"
                + encodeRawPath(config.repo()) + "/raw/" + encodeRawPath(repoPath)
                + "?ref=" + pctEncode(config.branch());
    }

    /**
     * 自建实例前面挂 CDN / 反代时，路径形状与 Gogs 同构（{@code /{owner}/{repo}/raw/{branch}/{path}}）。
     */
    @Override
    public String cdnPath(GitSourceConfig config, String repoPath) {
        return "/" + config.owner() + "/" + config.repo() + "/raw/" + config.branch()
                + "/" + GitCdn.encodePath(repoPath);
    }
}
