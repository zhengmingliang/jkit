package com.alianga.jkit.http.curl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变的 curl 解析结果。执行器和 {@code jkit-curl-codegen} 都只依赖这一份模型。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ParsedCurlRequest {
    private final String method;
    private final String url;
    private final List<Header> headers;
    private final List<QueryParam> query;
    private final Body body;
    private final Auth auth;
    private final ProxySpec proxy;
    private final boolean insecure;
    private final boolean followRedirects;
    private final boolean compressed;
    private final Integer timeoutSec;
    private final Integer connectTimeoutSec;
    private final List<String> warnings;
    private final List<String> unknownOptions;

    private ParsedCurlRequest(Builder b) {
        this.method = b.method == null ? "GET" : b.method;
        this.url = b.url;
        this.headers = freeze(b.headers);
        this.query = freeze(b.query);
        this.body = b.body == null ? Body.none() : b.body;
        this.auth = b.auth;
        this.proxy = b.proxy;
        this.insecure = b.insecure;
        this.followRedirects = b.followRedirects;
        this.compressed = b.compressed;
        this.timeoutSec = b.timeoutSec;
        this.connectTimeoutSec = b.connectTimeoutSec;
        this.warnings = freeze(b.warnings);
        this.unknownOptions = freeze(b.unknownOptions);
    }

    /**
     * @return HTTP 方法
     */
    public String method() {
        return method;
    }

    /**
     * @return URL，可能为 {@code null}
     */
    public String url() {
        return url;
    }

    /**
     * @return 请求头（有序，可重复）
     */
    public List<Header> headers() {
        return headers;
    }

    /**
     * @return 额外记录的 query
     */
    public List<QueryParam> query() {
        return query;
    }

    /**
     * @return 正文
     */
    public Body body() {
        return body;
    }

    /**
     * @return 认证信息，可能为 {@code null}
     */
    public Auth auth() {
        return auth;
    }

    /**
     * @return 代理，可能为 {@code null}
     */
    public ProxySpec proxy() {
        return proxy;
    }

    /**
     * @return 是否忽略证书（{@code -k}）
     */
    public boolean insecure() {
        return insecure;
    }

    /**
     * @return 是否跟随重定向
     */
    public boolean followRedirects() {
        return followRedirects;
    }

    /**
     * @return 是否声明 {@code --compressed}
     */
    public boolean compressed() {
        return compressed;
    }

    /**
     * @return 总超时秒数，未设置时为 {@code null}
     */
    public Integer timeoutSec() {
        return timeoutSec;
    }

    /**
     * @return 连接超时秒数，未设置时为 {@code null}
     */
    public Integer connectTimeoutSec() {
        return connectTimeoutSec;
    }

    /**
     * @return 解析警告
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * @return 未识别的选项
     */
    public List<String> unknownOptions() {
        return unknownOptions;
    }

    /**
     * 按名称查找最后一个请求头（忽略大小写）。
     *
     * @param name 头名称
     * @return 值，不存在时为 {@code null}
     */
    public String header(String name) {
        if (name == null) {
            return null;
        }
        for (int i = headers.size() - 1; i >= 0; i--) {
            Header h = headers.get(i);
            if (h.name().equalsIgnoreCase(name)) {
                return h.value();
            }
        }
        return null;
    }

    /**
     * @param name 头名称
     * @return 是否存在该头
     */
    public boolean hasHeader(String name) {
        return header(name) != null;
    }

    /**
     * @return 新的建造器
     */
    public static Builder builder() {
        return new Builder();
    }

    private static <T> List<T> freeze(List<T> src) {
        return Collections.unmodifiableList(new ArrayList<T>(src));
    }

    /**
     * HTTP 请求头。
     */
    public static final class Header {
        private final String name;
        private final String value;

        /**
         * @param name 名称
         * @param value 值
         */
        public Header(String name, String value) {
            if (name == null) {
                throw new IllegalArgumentException("name");
            }
            this.name = name;
            this.value = value == null ? "" : value;
        }

        /**
         * @return 名称
         */
        public String name() {
            return name;
        }

        /**
         * @return 值
         */
        public String value() {
            return value;
        }
    }

    /**
     * Query 参数。
     */
    public static final class QueryParam {
        private final String name;
        private final String value;

        /**
         * @param name 名称
         * @param value 值
         */
        public QueryParam(String name, String value) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
        }

        /**
         * @return 名称
         */
        public String name() {
            return name;
        }

        /**
         * @return 值
         */
        public String value() {
            return value;
        }
    }

    /**
     * 认证。
     */
    public static final class Auth {
        private final String type;
        private final String user;
        private final String password;
        private final String token;

        /**
         * @param user 用户名
         * @param password 密码
         * @return basic 认证
         */
        public static Auth basic(String user, String password) {
            return new Auth("basic", user, password == null ? "" : password, null);
        }

        /**
         * @param token bearer token
         * @return bearer 认证
         */
        public static Auth bearer(String token) {
            return new Auth("bearer", null, null, token);
        }

        private Auth(String type, String user, String password, String token) {
            this.type = type;
            this.user = user;
            this.password = password;
            this.token = token;
        }

        /**
         * @return {@code basic} 或 {@code bearer}
         */
        public String type() {
            return type;
        }

        /**
         * @return 用户名
         */
        public String user() {
            return user;
        }

        /**
         * @return 密码
         */
        public String password() {
            return password;
        }

        /**
         * @return bearer token
         */
        public String token() {
            return token;
        }
    }

    /**
     * 代理。
     */
    public static final class ProxySpec {
        private final String host;
        private final int port;
        private final String user;
        private final String password;
        private final String scheme;

        /**
         * @param host 主机
         * @param port 端口
         * @param user 用户名，可为 {@code null}
         * @param password 密码，可为 {@code null}
         * @param scheme 协议
         */
        public ProxySpec(String host, int port, String user, String password, String scheme) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.scheme = scheme == null ? "http" : scheme;
        }

        /**
         * @return 主机
         */
        public String host() {
            return host;
        }

        /**
         * @return 端口
         */
        public int port() {
            return port;
        }

        /**
         * @return 用户名
         */
        public String user() {
            return user;
        }

        /**
         * @return 密码
         */
        public String password() {
            return password;
        }

        /**
         * @return 协议
         */
        public String scheme() {
            return scheme;
        }

        /**
         * @return {@code host:port}
         */
        public String hostPort() {
            return host + ":" + port;
        }
    }

    /**
     * multipart 字段。
     */
    public static final class FormPart {
        private final String name;
        private final boolean file;
        private final String value;
        private final String filePath;
        private final String filename;
        private final String contentType;

        /**
         * @param name 字段名
         * @param file 是否文件
         * @param value 文本值
         * @param filePath 文件路径
         * @param filename 文件名
         * @param contentType 类型
         */
        public FormPart(String name, boolean file, String value, String filePath,
                        String filename, String contentType) {
            this.name = name;
            this.file = file;
            this.value = value;
            this.filePath = filePath;
            this.filename = filename;
            this.contentType = contentType;
        }

        /**
         * @param name 字段名
         * @param value 文本
         * @return 文本字段
         */
        public static FormPart field(String name, String value) {
            return new FormPart(name, false, value, null, null, null);
        }

        /**
         * @param name 字段名
         * @param path 路径
         * @param filename 文件名
         * @param contentType 类型
         * @return 文件字段
         */
        public static FormPart file(String name, String path, String filename, String contentType) {
            return new FormPart(name, true, null, path, filename, contentType);
        }

        /**
         * @return 字段名
         */
        public String name() {
            return name;
        }

        /**
         * @return 是否文件
         */
        public boolean file() {
            return file;
        }

        /**
         * @return 文本值
         */
        public String value() {
            return value;
        }

        /**
         * @return 文件路径
         */
        public String filePath() {
            return filePath;
        }

        /**
         * @return 文件名
         */
        public String filename() {
            return filename;
        }

        /**
         * @return Content-Type
         */
        public String contentType() {
            return contentType;
        }
    }

    /**
     * 请求正文。
     */
    public static final class Body {
        /**
         * 正文类型。
         */
        public enum Kind {
            /** 无正文 */
            NONE,
            /** 原始文本 */
            RAW,
            /** JSON */
            JSON,
            /** application/x-www-form-urlencoded */
            URLENCODED,
            /** multipart/form-data */
            MULTIPART,
            /** 本地文件 */
            FILE
        }

        private final Kind kind;
        private final String text;
        private final String filePath;
        private final boolean binary;
        private final List<FormPart> parts;

        private Body(Kind kind, String text, String filePath, boolean binary, List<FormPart> parts) {
            this.kind = kind;
            this.text = text;
            this.filePath = filePath;
            this.binary = binary;
            this.parts = parts == null
                    ? Collections.<FormPart>emptyList()
                    : Collections.unmodifiableList(new ArrayList<FormPart>(parts));
        }

        /**
         * @return 空正文
         */
        public static Body none() {
            return new Body(Kind.NONE, null, null, false, null);
        }

        /**
         * @param text JSON 文本
         * @return JSON 正文
         */
        public static Body json(String text) {
            return new Body(Kind.JSON, text, null, false, null);
        }

        /**
         * @param text 文本
         * @param binary 是否按 binary 处理
         * @return 原始正文
         */
        public static Body raw(String text, boolean binary) {
            return new Body(Kind.RAW, text, null, binary, null);
        }

        /**
         * @param text 编码后的表单
         * @return urlencoded 正文
         */
        public static Body urlencoded(String text) {
            return new Body(Kind.URLENCODED, text, null, false, null);
        }

        /**
         * @param path 文件路径
         * @param binary 是否 binary
         * @return 文件正文
         */
        public static Body file(String path, boolean binary) {
            return new Body(Kind.FILE, null, path, binary, null);
        }

        /**
         * @param parts 字段
         * @return multipart 正文
         */
        public static Body multipart(List<FormPart> parts) {
            return new Body(Kind.MULTIPART, null, null, false, parts);
        }

        /**
         * @return 类型
         */
        public Kind kind() {
            return kind;
        }

        /**
         * @return 文本
         */
        public String text() {
            return text;
        }

        /**
         * @return 文件路径
         */
        public String filePath() {
            return filePath;
        }

        /**
         * @return 是否 binary
         */
        public boolean binary() {
            return binary;
        }

        /**
         * @return multipart 字段
         */
        public List<FormPart> parts() {
            return parts;
        }

        /**
         * @return 是否有可发送的正文
         */
        public boolean isPresent() {
            if (kind == Kind.NONE) {
                return false;
            }
            if (kind == Kind.MULTIPART) {
                return !parts.isEmpty();
            }
            if (kind == Kind.FILE) {
                return filePath != null && !filePath.isEmpty();
            }
            return text != null && !text.isEmpty();
        }
    }

    /**
     * 建造器。
     */
    public static final class Builder {
        private String method = "GET";
        private String url;
        private final List<Header> headers = new ArrayList<Header>();
        private final List<QueryParam> query = new ArrayList<QueryParam>();
        private Body body;
        private Auth auth;
        private ProxySpec proxy;
        private boolean insecure;
        private boolean followRedirects = true;
        private boolean compressed;
        private Integer timeoutSec;
        private Integer connectTimeoutSec;
        private final List<String> warnings = new ArrayList<String>();
        private final List<String> unknownOptions = new ArrayList<String>();

        /**
         * @param m 方法
         * @return this
         */
        public Builder method(String m) {
            this.method = m;
            return this;
        }

        /**
         * @param u URL
         * @return this
         */
        public Builder url(String u) {
            this.url = u;
            return this;
        }

        /**
         * 追加请求头（允许重复）。
         *
         * @param n 名称
         * @param v 值
         * @return this
         */
        public Builder header(String n, String v) {
            headers.add(new Header(n, v));
            return this;
        }

        /**
         * 按忽略大小写覆盖已有同名头，否则追加。
         *
         * @param n 名称
         * @param v 值
         * @return this
         */
        public Builder upsertHeader(String n, String v) {
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).name().equalsIgnoreCase(n)) {
                    headers.set(i, new Header(n, v));
                    return this;
                }
            }
            headers.add(new Header(n, v));
            return this;
        }

        /**
         * @param n 名称
         * @param v 值
         * @return this
         */
        public Builder query(String n, String v) {
            query.add(new QueryParam(n, v));
            return this;
        }

        /**
         * @param b 正文
         * @return this
         */
        public Builder body(Body b) {
            this.body = b;
            return this;
        }

        /**
         * @param a 认证
         * @return this
         */
        public Builder auth(Auth a) {
            this.auth = a;
            return this;
        }

        /**
         * @param p 代理
         * @return this
         */
        public Builder proxy(ProxySpec p) {
            this.proxy = p;
            return this;
        }

        /**
         * @param v 是否忽略证书
         * @return this
         */
        public Builder insecure(boolean v) {
            this.insecure = v;
            return this;
        }

        /**
         * @param v 是否跟随重定向
         * @return this
         */
        public Builder followRedirects(boolean v) {
            this.followRedirects = v;
            return this;
        }

        /**
         * @param v 是否 compressed
         * @return this
         */
        public Builder compressed(boolean v) {
            this.compressed = v;
            return this;
        }

        /**
         * @param v 总超时秒
         * @return this
         */
        public Builder timeoutSec(Integer v) {
            this.timeoutSec = v;
            return this;
        }

        /**
         * @param v 连接超时秒
         * @return this
         */
        public Builder connectTimeoutSec(Integer v) {
            this.connectTimeoutSec = v;
            return this;
        }

        /**
         * @param w 警告
         * @return this
         */
        public Builder warn(String w) {
            this.warnings.add(w);
            return this;
        }

        /**
         * @param opt 未知选项
         * @return this
         */
        public Builder unknown(String opt) {
            this.unknownOptions.add(opt);
            return this;
        }

        /**
         * @return 当前 URL
         */
        public String url() {
            return url;
        }

        /**
         * @return 当前方法
         */
        public String method() {
            return method;
        }

        /**
         * @return 当前正文
         */
        public Body body() {
            return body;
        }

        /**
         * @return 认证
         */
        public Auth auth() {
            return auth;
        }

        /**
         * @return 代理
         */
        public ProxySpec proxy() {
            return proxy;
        }

        /**
         * @return 是否忽略证书
         */
        public boolean insecure() {
            return insecure;
        }

        /**
         * @return 是否跟随重定向
         */
        public boolean followRedirects() {
            return followRedirects;
        }

        /**
         * @return 总超时
         */
        public Integer timeoutSec() {
            return timeoutSec;
        }

        /**
         * @return 连接超时
         */
        public Integer connectTimeoutSec() {
            return connectTimeoutSec;
        }

        /**
         * @return 是否 compressed
         */
        public boolean compressed() {
            return compressed;
        }

        /**
         * @param name 头名称
         * @return 值
         */
        public String header(String name) {
            for (int i = headers.size() - 1; i >= 0; i--) {
                if (headers.get(i).name().equalsIgnoreCase(name)) {
                    return headers.get(i).value();
                }
            }
            return null;
        }

        /**
         * @param name 头名称
         * @return 是否存在
         */
        public boolean hasHeader(String name) {
            return header(name) != null;
        }

        /**
         * @return 不可变模型；允许 URL 为空，执行时再校验
         */
        public ParsedCurlRequest build() {
            return new ParsedCurlRequest(this);
        }
    }
}
