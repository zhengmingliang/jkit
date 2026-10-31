package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SVN 连接配置：协议（svn / http / https）+ 主机 + 端口 + 认证 + 仓库内起始目录。
 *
 * <p>逻辑路径与其他存储引擎一致：{@code ""}（或 {@code /}）为根（即配置的起始目录），
 * 段间以 {@code /} 分隔。SVNKit 的 RA 调用使用「相对会话 URL」的路径，
 * URL 拼装时逐段百分号编码，中文 / 空格 / 特殊字符文件名都能正确提交。</p>
 *
 * @author 郑明亮
 */
final class SvnConfig {
    static final String PLATFORM_ID = "svn";

    static final String PROTOCOL = "protocol";
    static final String HOST = "host";
    static final String PORT = "port";
    static final String USER = "user";
    static final String PASSWORD = "password";
    static final String BASE_PATH = "basePath";
    static final String TRUST_SERVER_CERT = "trustServerCert";

    static final String PROTO_SVN = "svn";
    static final String PROTO_HTTP = "http";
    static final String PROTO_HTTPS = "https";

    /** 各协议默认端口：留空时采用。 */
    private static int defaultPort(String protocol) {
        if (PROTO_HTTP.equals(protocol)) {
            return 80;
        }
        if (PROTO_HTTPS.equals(protocol)) {
            return 443;
        }
        return 3690;
    }

    /** 起始目录在仓库内的路径段（不含前导斜杠；空表示仓库根）。 */
    private final List<String> baseSegments;
    private final String protocol;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean trustServerCert;

    private SvnConfig(String protocol, String host, int port, String user, String password,
                      List<String> baseSegments, boolean trustServerCert) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.baseSegments = baseSegments;
        this.trustServerCert = trustServerCert;
    }

    /**
     * 解析并校验连接配置。
     *
     * @param config 引擎配置（已由 {@code FileStorages.of} 叠加平台默认值）
     * @return 合法配置
     * @throws StorageException INVALID_CONFIG：协议未知 / 主机缺失 / 端口非数字 / 地址非法
     */
    static SvnConfig from(StorageConfig config) {
        // 平台默认值会注入 protocol=svn；直连引擎且未配协议时同样按 svn 处理
        String rawProtocol = trimToEmpty(config.getOr(PROTOCOL, ""));
        String protocol = normalizeProtocol(rawProtocol.isEmpty() ? PROTO_SVN : rawProtocol);
        if (protocol == null) {
            throw StorageException.invalidConfig("SVN 协议仅支持 svn / http / https，当前为："
                    + rawProtocol);
        }
        String host = trimToEmpty(config.get(HOST));
        List<String> extraBase = Collections.emptyList();
        int uriPort = -1;
        String userFromUrl = null;
        String passwordFromUrl = null;
        if (host.contains("://")) {
            // 容忍用户把完整 URL 粘进「服务器地址」：从中拆出协议 / 主机 / 端口，路径并入起始目录
            URI uri = parseFullUrl(host);
            String uriProtocol = normalizeProtocol(uri.getScheme());
            if (uriProtocol == null) {
                throw StorageException.invalidConfig("SVN 地址协议仅支持 svn / http / https："
                        + sanitized(host));
            }
            protocol = uriProtocol;
            host = uri.getHost() == null ? "" : uri.getHost();
            uriPort = uri.getPort();
            extraBase = split(uri.getPath());
            // URL 里带的 user:pass@ 拆成凭据（字段留空时回填），host 存储前剥离——
            // host 不是 secret 字段，原样保存等于密码明文落盘 connections.json
            if (uri.getUserInfo() != null) {
                int colon = uri.getUserInfo().indexOf(':');
                String urlUser = colon >= 0 ? uri.getUserInfo().substring(0, colon) : uri.getUserInfo();
                String urlPassword = colon >= 0 ? uri.getUserInfo().substring(colon + 1) : "";
                if ((config.get(USER) == null || config.get(USER).trim().isEmpty())
                        && !urlUser.trim().isEmpty()) {
                    userFromUrl = urlUser;
                }
                if ((config.get(PASSWORD) == null || config.get(PASSWORD).trim().isEmpty())
                        && !urlPassword.isEmpty()) {
                    passwordFromUrl = urlPassword;
                }
            }
        }
        if (host.isEmpty()) {
            throw StorageException.invalidConfig("SVN 连接缺少服务器地址（host）");
        }
        int port;
        String portText = trimToEmpty(config.get(PORT));
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw StorageException.invalidConfig("SVN 端口必须是数字，当前为：" + portText);
            }
            if (port < 1 || port > 65535) {
                throw StorageException.invalidConfig("SVN 端口超出 1-65535 范围：" + port);
            }
        } else if (uriPort > 0) {
            // 完整 URL 里带的端口
            port = uriPort;
        } else {
            port = defaultPort(protocol);
        }
        String user = trimToNull(config.get(USER));
        if (user == null) {
            user = userFromUrl;
        }
        String password = config.get(PASSWORD);
        if (password == null || password.isEmpty()) {
            if (passwordFromUrl != null) {
                password = passwordFromUrl;
            } else if (password == null) {
                password = "";
            }
        }
        // 起始目录 = URL 里的仓库路径前缀 + 配置的仓库内起始目录（host=http://s/repo，basePath=trunk → /repo/trunk）
        List<String> base = new ArrayList<>(extraBase);
        base.addAll(split(config.getOr(BASE_PATH, "")));

        // 默认不信任服务端证书：HTTPS + Basic 认证下，无条件接受任何证书等于
        // 允许中间人窃取凭据、篡改仓库内容。自签名 / 私有 CA 须由用户显式勾选
        // 「信任 HTTPS 证书」（opt-in），与字段提示的语义一致。
        boolean trust = config.boolOr(TRUST_SERVER_CERT, false);
        return new SvnConfig(protocol, host, port, user, password,
                Collections.unmodifiableList(base), trust);
    }

    private static String normalizeProtocol(String raw) {
        String value = trimToEmpty(raw).toLowerCase(Locale.ROOT);
        if (value.endsWith("://")) {
            value = value.substring(0, value.length() - 3);
        }
        if (PROTO_SVN.equals(value) || PROTO_HTTP.equals(value) || PROTO_HTTPS.equals(value)) {
            return value;
        }
        return null;
    }

    private static URI parseFullUrl(String raw) {
        try {
            URI uri = new URI(raw.trim());
            if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                throw StorageException.invalidConfig("SVN 服务地址缺少主机名：" + sanitized(raw));
            }
            return uri;
        } catch (URISyntaxException e) {
            // 用户可能把带 user:pass@ 的 URL 粘进来：错误消息只回显脱敏骨架，
            // 完整原文进异常消息等于密码直达界面
            throw StorageException.invalidConfig("SVN 服务地址无法解析：" + sanitized(raw));
        }
    }

    /** URL 脱敏：剥掉 user:pass@ 段，其余原样（协议 / 主机 / 端口 / 路径）。 */
    private static String sanitized(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int at = url.indexOf('@', schemeEnd + 3);
        if (at < 0) {
            return url;
        }
        return url.substring(0, schemeEnd + 3) + "…@" + url.substring(at + 1);
    }

    /** 拆路径段：去空白段、做百分号解码（来自完整 URL 的路径是已编码的）。 */
    private static List<String> split(String path) {
        List<String> segments = new ArrayList<>();
        if (path == null || path.trim().isEmpty()) {
            return segments;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            segments.add(decode(segment));
        }
        return segments;
    }

    private static String decode(String segment) {
        try {
            // URLDecoder 是 query/form 语义（+ 解成空格），而 URL 路径段里的 + 是
            // 字面字符：repo 路径 c++trunk 会被解成「c  trunk」指向不存在的目录。
            // 先把 + 保护成 %2B 再交给 URLDecoder，只处理百分号转义
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return segment;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    String protocol() {
        return protocol;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    boolean anonymous() {
        return user == null;
    }

    boolean trustServerCert() {
        return trustServerCert;
    }

    /**
     * 逻辑路径转 SVNKit 相对路径（无起首斜杠，{@code null} / 空 / "/" 为 {@code ""}）。
     *
     * @param logicalPath 逻辑路径
     * @return 相对会话 URL 的路径
     */
    String relativePath(String logicalPath) {
        return StoragePaths.normalize(logicalPath);
    }

    /**
     * 起始目录 + 逻辑路径拼成完整仓库 URL，每段按 UTF-8 百分号编码。
     *
     * @param logicalPath 逻辑路径
     * @return 完整 URL
     * @throws StorageException 路径含无法编码字符（理论上不会发生）
     */
    SVNURL url(String logicalPath) {
        try {
            StringBuilder encodedPath = new StringBuilder();
            for (String segment : baseSegments) {
                encodedPath.append('/').append(encode(segment));
            }
            String relative = relativePath(logicalPath);
            if (!relative.isEmpty()) {
                for (String segment : relative.split("/", -1)) {
                    encodedPath.append('/').append(encode(segment));
                }
            }
            if (encodedPath.length() == 0) {
                encodedPath.append('/');
            }
            // 末参 true：路径已是百分号编码形态
            return SVNURL.create(protocol, null, host, port, encodedPath.toString(), true);
        } catch (SVNException e) {
            throw StorageException.invalidConfig("SVN URL 无法构建（" + protocol + "://" + host + ":"
                    + port + "）：" + e.getMessage());
        }
    }

    /** 起始目录 URL（SVNRepository 会话位置）。 */
    SVNURL rootUrl() {
        return url("");
    }

    /** 仅用于诊断 / 测试：编码后的根 URL 字符串。 */
    String rootUrlText() {
        return rootUrl().toString();
    }

    /** 不含密钥的诊断文本。 */
    @Override
    public String toString() {
        String base = baseSegments.isEmpty() ? "" : "/" + String.join("/", baseSegments);
        return "svn[" + protocol + "://" + host + ":" + port + base + "]";
    }

    /**
     * 路径段百分号编码：与 URLEncoder 的差异是空格为 {@code %20} 而非 {@code +}，
     * 且 SVN 路径常字符（{@code -_.~()} 等）保持原样。
     */
    static String encode(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(segment.length() + 8);
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~'
                    || b == '!' || b == '$' || b == '&' || b == '\'' || b == '(' || b == ')'
                    || b == '*' || b == '+' || b == ',' || b == ';' || b == '=' || b == ':'
                    || b == '@' || b == '/') {
                out.append((char) b);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
