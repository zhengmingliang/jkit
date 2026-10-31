package com.alianga.jkit.storage.git.adapter;

import com.alianga.jkit.storage.git.GitCdn;
import com.alianga.jkit.storage.git.GitSourceConfig;

import java.util.Collections;
import java.util.Map;

/**
 * Gogs（自建）适配：{@code {baseUrl}/api/v1/repos/{owner}/{repo}}，
 * 鉴权头 {@code Authorization: token <token>}，提交作者在 {@code author.username}
 * （Gogs 也提供 {@code author.full_name}），分页参数用 {@code limit}。
 *
 * <p>Gogs 与 Gitea 的 API 高度同源（Gitea 是 Gogs 的分支），差异只在：
 * 提交时间字段（Gogs 有 {@code commit.author.date}，与 GitHub 一致）、
 * raw 地址形态（Gogs 用 {@code /raw/} 且分支放路径里）。</p>
 *
 * @author 郑明亮
 */
public final class GogsAdapter extends AbstractGitAdapter {
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
        String user = authorFrom(commit, "username", "login", "full_name", "name");
        if (user != null) {
            return user;
        }
        // 作者账号已注销时没有用户对象，退到 git 提交里的署名
        return nestedCommitAuthor(commit);
    }

    @Override
    public String pageSizeParam() {
        return "limit";
    }

    @Override
    public String rawUrl(GitSourceConfig config, String repoPath) {
        // 走 API 路由而非 web 路由：默认 openRaw 会把 Authorization: token 头
        // 发出去，Gogs 的 web raw 端点（/{owner}/{repo}/raw/...）走 session
        // cookie 鉴权、不认这个头——私有仓库会得到登录页重定向或 404，且
        // 跟随重定向会把 HTML 当文件内容写进下载流。/api/v1/.../raw/ 认令牌
        // （与 Gitea 同构）。路径逐段编码：含空格 / # / % / 中文的文件名裸拼
        // 会被截断或拒绝
        return config.baseUrl() + "/api/v1/repos/" + encodeRawPath(config.owner()) + "/"
                + encodeRawPath(config.repo()) + "/raw/" + encodeRawPath(repoPath)
                + "?ref=" + pctEncode(config.branch());
    }

    /**
     * 自建实例前面挂 CDN / 反代时，路径形状与 raw 一致（{@code /{owner}/{repo}/raw/{branch}/{path}}）。
     */
    @Override
    public String cdnPath(GitSourceConfig config, String repoPath) {
        return "/" + config.owner() + "/" + config.repo() + "/raw/" + config.branch()
                + "/" + GitCdn.encodePath(repoPath);
    }
}
