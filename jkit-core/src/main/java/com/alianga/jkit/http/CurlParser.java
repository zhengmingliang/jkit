package com.alianga.jkit.http;

import com.alianga.jkit.http.curl.CurlTokenizer;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Auth;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.ProxySpec;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析 curl 命令。{@link #parse(String)} 返回可执行的 {@link CurlRequest}；
 * {@link #parseModel(String)} 返回不可变模型，供执行器或外部代码生成使用。
 *
 * @author 郑明亮
 */
public final class CurlParser {
    private static final Set<String> SKIP_FLAGS = new HashSet<String>(Arrays.asList(
            "-s", "--silent", "-S", "--show-error", "-v", "--verbose", "-i", "--include",
            "-f", "--fail", "-#", "--progress-bar", "-N", "--no-buffer", "--no-progress-meter",
            "--http1.0", "--http1.1", "--http2", "--http2-prior-knowledge", "--http3",
            "--raw", "--globoff", "-g", "--path-as-is", "--location-trusted",
            "--compressed-ssh", "--fail-with-body", "--no-keep-alive"));

    private static final Set<String> SKIP_VALUE = new HashSet<String>(Arrays.asList(
            "-o", "--output", "-O", "--remote-name", "-D", "--dump-header", "-w", "--write-out",
            "--retry", "--retry-delay", "--retry-max-time", "--max-redirs", "--limit-rate",
            "--cert", "--cacert", "--capath", "--key", "--pass", "--unix-socket",
            "--connect-to", "--resolve", "--interface", "--dns-servers", "--output-dir",
            "--range", "-r", "--max-filesize", "--keepalive-time", "--speed-limit", "--speed-time"));

    private static final Pattern HOST_NAME = Pattern.compile(
            "^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?(/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCALHOST = Pattern.compile(
            "^localhost(:\\d+)?(/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile(
            "^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/|$)");
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);

    private CurlParser() {
    }

    /**
     * 兼容入口：解析为可执行的 {@link CurlRequest}。
     *
     * @param curl 完整命令
     * @return 请求模型
     */
    public static CurlRequest parse(String curl) {
        return CurlRequest.from(parseModel(curl));
    }

    /**
     * 解析为不可变模型。执行路径用 {@link CurlRequest#from(ParsedCurlRequest)}；
     * 多语言源码生成请用独立模块 {@code jkit-curl-codegen}。
     *
     * @param curl 完整命令
     * @return 不可变模型
     * @since 2.0.1
     */
    public static ParsedCurlRequest parseModel(String curl) {
        return parseBuilder(curl).build();
    }

    /**
     * 将 shell 风格命令拆分为参数。该方法包级可见，供同包测试验证引号和转义规则。
     *
     * @param input 命令参数部分
     * @return 参数列表
     */
    static List<String> tokenize(String input) {
        return CurlTokenizer.tokenize(input);
    }

    static ParsedCurlRequest.Builder parseBuilder(String curl) {
        if (curl == null) {
            throw new IllegalArgumentException("curl is required");
        }
        String normalized = stripPrefix(CurlTokenizer.normalize(curl).trim());
        ParsedCurlRequest.Builder builder = ParsedCurlRequest.builder();
        if (normalized.isEmpty()) {
            return builder;
        }
        List<String> args = CurlTokenizer.expandArgs(CurlTokenizer.tokenize(normalized));
        return parseArgs(args, builder);
    }

    private static ParsedCurlRequest.Builder parseArgs(List<String> args, ParsedCurlRequest.Builder b) {
        boolean dataAsQuery = false;
        boolean jsonFlag = false;
        String methodExplicit = null;
        boolean hasData = false;
        boolean dataBinary = false;
        List<String> dataParts = new ArrayList<String>();
        List<FormPart> formParts = new ArrayList<FormPart>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) {
                continue;
            }
            if (!arg.startsWith("-") || "-".equals(arg)) {
                if (looksLikeUrl(arg) || (b.url() == null && looksLikeHost(arg))) {
                    b.url(ensureScheme(arg));
                } else if (!arg.isEmpty() && !"curl".equals(arg)) {
                    b.warn("未识别的位置参数：" + truncate(arg));
                }
                continue;
            }

            String opt = arg;
            String inline = null;
            if (opt.startsWith("--")) {
                int eq = opt.indexOf('=');
                if (eq > 2) {
                    inline = opt.substring(eq + 1);
                    opt = opt.substring(0, eq);
                }
            }

            Take take = new Take(inline, args, i);

            if ("-X".equals(opt) || "--request".equals(opt)) {
                methodExplicit = take.value();
            } else if ("-H".equals(opt) || "--header".equals(opt)) {
                addHeader(b, take.value());
            } else if ("-d".equals(opt) || "--data".equals(opt) || "--data-raw".equals(opt)
                    || "--data-ascii".equals(opt)) {
                dataParts.add(readDataPiece(take.value(), false, b));
                hasData = true;
            } else if ("--data-binary".equals(opt)) {
                dataParts.add(readDataPiece(take.value(), true, b));
                hasData = true;
                dataBinary = true;
            } else if ("--data-urlencode".equals(opt)) {
                dataParts.add(encodeDataUrl(take.value(), b));
                hasData = true;
            } else if ("--json".equals(opt)) {
                dataParts.add(readDataPiece(take.value(), false, b));
                hasData = true;
                jsonFlag = true;
            } else if ("--url-query".equals(opt)) {
                parseQueryPiece(take.value(), b);
            } else if ("-G".equals(opt) || "--get".equals(opt)) {
                dataAsQuery = true;
            } else if ("-I".equals(opt) || "--head".equals(opt)) {
                if (methodExplicit == null) {
                    methodExplicit = "HEAD";
                }
            } else if ("-u".equals(opt) || "--user".equals(opt)) {
                parseUser(b, take.value());
            } else if ("--oauth2-bearer".equals(opt)) {
                b.auth(Auth.bearer(take.value()));
            } else if ("-A".equals(opt) || "--user-agent".equals(opt)) {
                b.upsertHeader("User-Agent", take.value());
            } else if ("-e".equals(opt) || "--referer".equals(opt)) {
                b.upsertHeader("Referer", take.value());
            } else if ("-x".equals(opt) || "--proxy".equals(opt)) {
                b.proxy(parseProxy(take.value(), b));
            } else if ("-b".equals(opt) || "--cookie".equals(opt)) {
                String cookie = take.value();
                if (cookie.indexOf('=') >= 0) {
                    b.upsertHeader("Cookie", cookie);
                } else {
                    b.warn("-b 指向 cookie 文件 " + cookie + "，未读入内容");
                }
            } else if ("--url".equals(opt)) {
                b.url(ensureScheme(take.value()));
            } else if ("-F".equals(opt) || "--form".equals(opt) || "--form-string".equals(opt)) {
                FormPart part = parseForm(take.value(), "--form-string".equals(opt), b);
                if (part != null) {
                    formParts.add(part);
                }
            } else if ("-T".equals(opt) || "--upload-file".equals(opt)) {
                b.body(Body.file(take.value(), true));
                if (methodExplicit == null) {
                    methodExplicit = "PUT";
                }
            } else if ("-k".equals(opt) || "--insecure".equals(opt)) {
                b.insecure(true);
            } else if ("-L".equals(opt) || "--location".equals(opt)) {
                b.followRedirects(true);
            } else if ("--compressed".equals(opt)) {
                b.compressed(true);
                if (!b.hasHeader("Accept-Encoding")) {
                    b.upsertHeader("Accept-Encoding", "gzip, deflate, br");
                }
            } else if ("-m".equals(opt) || "--max-time".equals(opt)) {
                b.timeoutSec(parseInt(take.value()));
            } else if ("--connect-timeout".equals(opt)) {
                b.connectTimeoutSec(parseInt(take.value()));
            } else if ("--create-dirs".equals(opt)) {
                // no-op
            } else if (SKIP_FLAGS.contains(opt)) {
                // ignored
            } else if (SKIP_VALUE.contains(opt)) {
                if (inline == null) {
                    take.skipValueIfPresent();
                }
                b.warn("已忽略不参与请求构造的选项 " + opt);
            } else {
                b.unknown(opt);
                b.warn("未支持的选项 " + opt + "，已跳过");
                if (inline == null) {
                    take.skipValueIfPresent();
                }
            }
            i = take.index();
        }

        if (!formParts.isEmpty()) {
            b.body(Body.multipart(formParts));
            if (hasData) {
                b.warn("-F 与 -d 同时出现时按 multipart 处理，-d 正文被忽略");
            }
        } else if (hasData) {
            String joined = join(dataParts, "&");
            if (dataAsQuery) {
                b.url(appendQuery(b.url(), joined));
            } else if (dataParts.size() == 1 && dataParts.get(0).startsWith("@")
                    && dataParts.get(0).length() > 1) {
                b.body(Body.file(dataParts.get(0).substring(1), dataBinary));
            } else {
                Body.Kind kind;
                if (jsonFlag) {
                    kind = Body.Kind.JSON;
                } else if (dataParts.size() > 1) {
                    kind = Body.Kind.URLENCODED;
                } else {
                    kind = guessBodyKind(joined, dataBinary);
                }
                if (kind == Body.Kind.JSON) {
                    b.body(Body.json(joined));
                } else if (kind == Body.Kind.URLENCODED) {
                    b.body(Body.urlencoded(joined));
                } else {
                    b.body(Body.raw(joined, dataBinary));
                }
                if (jsonFlag) {
                    b.upsertHeader("Content-Type", "application/json");
                    if (!b.hasHeader("Accept")) {
                        b.upsertHeader("Accept", "application/json");
                    }
                } else if (!b.hasHeader("Content-Type") && kind == Body.Kind.URLENCODED) {
                    b.upsertHeader("Content-Type", "application/x-www-form-urlencoded");
                }
            }
        }

        String inferred;
        if (methodExplicit != null) {
            inferred = methodExplicit.toUpperCase(Locale.ROOT);
        } else if ((!formParts.isEmpty() || hasData) && !dataAsQuery) {
            inferred = "POST";
        } else {
            inferred = "GET";
        }
        b.method(inferred);
        return b;
    }

    private static String stripPrefix(String input) {
        String s = input.trim();
        while (s.toLowerCase(Locale.ROOT).startsWith("sudo ")) {
            s = s.substring(5).trim();
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("curl.exe")) {
            s = "curl" + s.substring(8);
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("curl")) {
            s = s.substring(4).trim();
        }
        return s;
    }

    private static void addHeader(ParsedCurlRequest.Builder b, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            if (raw.endsWith(";") && raw.length() > 1) {
                b.header(raw.substring(0, raw.length() - 1).trim(), "");
            }
            return;
        }
        b.header(raw.substring(0, colon).trim(), raw.substring(colon + 1).trim());
    }

    private static void parseUser(ParsedCurlRequest.Builder b, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            b.auth(Auth.basic(raw, ""));
        } else {
            b.auth(Auth.basic(raw.substring(0, colon), raw.substring(colon + 1)));
        }
    }

    private static ProxySpec parseProxy(String raw, ParsedCurlRequest.Builder b) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String scheme = "http";
        String value = raw;
        int schemeAt = value.indexOf("://");
        if (schemeAt > 0) {
            String s = value.substring(0, schemeAt).toLowerCase(Locale.ROOT);
            if ("https".equals(s) || "socks5".equals(s) || "http".equals(s)) {
                scheme = s;
            }
            value = value.substring(schemeAt + 3);
        }
        String user = null;
        String password = null;
        int at = value.lastIndexOf('@');
        if (at > 0) {
            String cred = value.substring(0, at);
            value = value.substring(at + 1);
            int c = cred.indexOf(':');
            if (c >= 0) {
                user = cred.substring(0, c);
                password = cred.substring(c + 1);
            } else {
                user = cred;
            }
        }
        int colon = value.lastIndexOf(':');
        if (colon < 0) {
            return new ProxySpec(value, "https".equals(scheme) ? 443 : 80, user, password, scheme);
        }
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            return new ProxySpec(value.substring(0, colon), port, user, password, scheme);
        } catch (NumberFormatException e) {
            b.warn("代理端口无法解析：" + raw + "，回退 80");
            return new ProxySpec(value, 80, user, password, scheme);
        }
    }

    private static FormPart parseForm(String raw, boolean asString, ParsedCurlRequest.Builder b) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int eq = raw.indexOf('=');
        if (eq < 0) {
            b.warn("无法解析 -F 字段：" + raw);
            return null;
        }
        String name = raw.substring(0, eq);
        String rest = raw.substring(eq + 1);
        String contentType = null;
        String filename = null;
        String[] extras = rest.split(";");
        rest = extras[0];
        for (int i = 1; i < extras.length; i++) {
            String piece = extras[i].trim();
            int ke = piece.indexOf('=');
            if (ke <= 0) {
                continue;
            }
            String key = piece.substring(0, ke).trim().toLowerCase(Locale.ROOT);
            String val = piece.substring(ke + 1);
            if ("type".equals(key)) {
                contentType = val;
            } else if ("filename".equals(key)) {
                filename = val;
            }
        }
        if (!asString && rest.startsWith("@")) {
            String path = rest.substring(1);
            return FormPart.file(name, path, filename != null ? filename : fileNameOf(path), contentType);
        }
        if (!asString && rest.startsWith("<")) {
            b.warn("字段 " + name + " 使用 <file 读取文本文件，按路径处理");
            return FormPart.file(name, rest.substring(1), filename, contentType);
        }
        return FormPart.field(name, rest);
    }

    private static String readDataPiece(String value, boolean binary, ParsedCurlRequest.Builder b) {
        if (value != null && value.startsWith("@") && value.length() > 1 && !value.startsWith("@-")) {
            b.warn((binary ? "--data-binary" : "--data") + " 引用文件 " + value.substring(1));
            return value;
        }
        if ("@-".equals(value)) {
            b.warn("从 stdin 读取正文（@-）");
        }
        return value == null ? "" : value;
    }

    private static String encodeDataUrl(String raw, ParsedCurlRequest.Builder b) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("@")) {
            b.warn("--data-urlencode 引用文件 " + raw);
            return raw;
        }
        int eq = raw.indexOf('=');
        if (eq < 0) {
            return urlEncode(raw);
        }
        String name = raw.substring(0, eq);
        String value = raw.substring(eq + 1);
        if (name.isEmpty()) {
            return urlEncode(value);
        }
        return name + "=" + urlEncode(value);
    }

    private static void parseQueryPiece(String raw, ParsedCurlRequest.Builder b) {
        int eq = raw.indexOf('=');
        if (eq < 0) {
            b.query(raw, "");
            b.url(appendQuery(b.url(), urlEncode(raw)));
        } else {
            String n = raw.substring(0, eq);
            String v = raw.substring(eq + 1);
            b.query(n, v);
            b.url(appendQuery(b.url(), urlEncode(n) + "=" + urlEncode(v)));
        }
    }

    private static String appendQuery(String url, String qs) {
        if (qs == null || qs.isEmpty() || url == null) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + qs;
    }

    private static Body.Kind guessBodyKind(String text, boolean binary) {
        if (binary) {
            return Body.Kind.RAW;
        }
        if (looksLikeJson(text)) {
            return Body.Kind.JSON;
        }
        String t = text == null ? "" : text.trim();
        if (t.indexOf('=') >= 0 && !t.startsWith("{") && !t.startsWith("[")) {
            return Body.Kind.URLENCODED;
        }
        return Body.Kind.RAW;
    }

    private static boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.indexOf("}&") >= 0 || t.indexOf("]&") >= 0) {
            return false;
        }
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    static boolean looksLikeUrl(String arg) {
        if (arg == null) {
            return false;
        }
        String lower = arg.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("ws://") || lower.startsWith("wss://");
    }

    private static boolean looksLikeHost(String arg) {
        if (arg == null || arg.isEmpty() || arg.startsWith("-")) {
            return false;
        }
        if (arg.contains("://")) {
            return looksLikeUrl(arg);
        }
        return LOCALHOST.matcher(arg).find() || IPV4.matcher(arg).find() || HOST_NAME.matcher(arg).find();
    }

    private static String ensureScheme(String url) {
        if (url != null && SCHEME.matcher(url).find()) {
            return url;
        }
        return "http://" + url;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String fileNameOf(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    private static String truncate(String s) {
        return s.length() > 48 ? s.substring(0, 45) + "..." : s;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /**
     * 消费选项参数；支持 {@code --header=value} 内联和 {@code -H value} 下一个 token。
     */
    private static final class Take {
        private final String inline;
        private final List<String> args;
        private int i;

        Take(String inline, List<String> args, int i) {
            this.inline = inline;
            this.args = args;
            this.i = i;
        }

        String value() {
            if (inline != null) {
                return inline;
            }
            if (i + 1 >= args.size()) {
                return "";
            }
            String v = args.get(i + 1);
            if (v.startsWith("-") && v.length() > 1 && !v.matches("-?\\d+(\\.\\d+)?")) {
                return "";
            }
            i++;
            return v;
        }

        void skipValueIfPresent() {
            if (i + 1 < args.size()) {
                String peek = args.get(i + 1);
                if (peek != null && !peek.startsWith("-") && !looksLikeUrl(peek) && !looksLikeHost(peek)) {
                    i++;
                }
            }
        }

        int index() {
            return i;
        }
    }
}
