package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.git.adapter.GitHostAdapter;

import java.nio.charset.StandardCharsets;

/**
 * CDN 加速链接构造：把仓库内文件地址换成 CDN 域名下的地址。
 *
 * <p>纯字符串计算，<b>不发任何网络请求</b>——所需信息（owner / repo / branch / rootPath）
 * 全在连接配置里。因此界面上「复制 CDN 加速链接」不需要走后台线程，
 * 也不像「复制直链 / 临时链接」那样受多吉云专属的能力探测门槛限制。</p>
 *
 * <p>各家的路径形状由 {@link GitHostAdapter#cdnPath} 给出（沿用各自 raw 路径），
 * 本类只负责域名规范化与百分号编码。</p>
 *
 * @author 郑明亮
 */
public final class GitCdn {
    private GitCdn() {
    }

    /**
     * 构造 CDN 加速链接。
     *
     * @param config 连接配置（需已配 {@code cdnBase}）
     * @param repoPath 仓库内路径（含 rootPath 前缀）
     * @return CDN 链接；未启用 CDN 或路径为空时返回 null
     */
    public static String linkOf(GitSourceConfig config, String repoPath) {
        if (config == null || !config.cdnEnabled() || repoPath == null || repoPath.isEmpty()) {
            return null;
        }
        if (!GitStorageSupport.supportsCdn(config.host())) {
            return null;
        }
        GitHostAdapter adapter = GitStorageProvider.adapterFor(config.host());
        String path = adapter.cdnPath(config, repoPath);
        if (path == null || path.isEmpty()) {
            return null;
        }
        return config.cdnBase() + path;
    }

    /**
     * 路径段百分号编码：空格为 {@code %20}，中文按 UTF-8 编码；
     * 路径分隔符 {@code /} 保留（它是路径结构的一部分，不是文件名字符）。
     *
     * @param repoPath 仓库内路径
     * @return 编码后的路径
     */
    public static String encodePath(String repoPath) {
        if (repoPath == null || repoPath.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String segment : repoPath.split("/", -1)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(encodeSegment(segment));
        }
        return out.toString();
    }

    private static String encodeSegment(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            boolean safe = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~';
            if (safe) {
                out.append((char) b);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
