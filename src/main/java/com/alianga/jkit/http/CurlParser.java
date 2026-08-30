package com.alianga.jkit.http;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析 curl 命令行为 {@link CurlRequest}，可再交给 {@link com.alianga.jkit.HttpUtils} 执行。
 *
 * @author 郑明亮
 */
public final class CurlParser {
    private CurlParser() {
    }

    /**
     * 解析 curl 文本（支持换行续写、单双引号）。
     *
     * @param curl 完整命令
     * @return 请求模型
     */
    public static CurlRequest parse(String curl) {
        if (curl == null) {
            throw new IllegalArgumentException("curl is required");
        }
        String normalized = curl.trim();
        if (normalized.startsWith("curl")) {
            normalized = normalized.substring(4).trim();
        }
        normalized = normalized.replace("\\\r\n", " ").replace("\\\n", " ").replace("^\r\n", " ")
                .replace("^\n", " ");
        List<String> args = tokenize(normalized);
        return parseArgs(args);
    }

    /**
     * 将 shell 风格命令拆分为参数。该方法包级可见，供同包测试验证引号和转义规则。
     *
     * @param input 命令参数部分
     * @return 参数列表
     */
    static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                i++;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char q = input.charAt(i);
                    if (q == '\'') {
                        i++;
                        break;
                    }
                    current.append(q);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n) {
                    char q = input.charAt(i);
                    if (q == '"') {
                        i++;
                        break;
                    }
                    if (q == '\\' && i + 1 < n) {
                        i++;
                        current.append(input.charAt(i));
                        i++;
                        continue;
                    }
                    current.append(q);
                    i++;
                }
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                current.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            current.append(c);
            i++;
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 解析已分词的 curl 参数。
     *
     * @param args 参数列表
     * @return 请求模型
     */
    private static CurlRequest parseArgs(List<String> args) {
        CurlRequest request = new CurlRequest();
        boolean dataAsQuery = false;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String method = null;
        int i = 0;
        while (i < args.size()) {
            String arg = args.get(i);
            if (!arg.startsWith("-")) {
                if (looksLikeUrl(arg)) {
                    request.setUrl(arg);
                }
                i++;
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
            if ("-X".equals(opt) || "--request".equals(opt)) {
                method = inline != null ? inline : next(args, ++i);
            } else if ("-H".equals(opt) || "--header".equals(opt)) {
                addHeader(request, inline != null ? inline : next(args, ++i));
            } else if ("-d".equals(opt) || "--data".equals(opt) || "--data-raw".equals(opt)
                    || "--data-binary".equals(opt) || "--data-ascii".equals(opt)) {
                String piece = stripFilePrefix(inline != null ? inline : next(args, ++i));
                if (hasData) {
                    data.append('&');
                }
                data.append(piece);
                hasData = true;
            } else if ("--data-urlencode".equals(opt)) {
                String piece = inline != null ? inline : next(args, ++i);
                if (hasData) {
                    data.append('&');
                }
                data.append(piece);
                hasData = true;
            } else if ("-G".equals(opt) || "--get".equals(opt)) {
                dataAsQuery = true;
            } else if ("-I".equals(opt) || "--head".equals(opt)) {
                method = "HEAD";
            } else if ("-u".equals(opt) || "--user".equals(opt)) {
                parseUser(request, inline != null ? inline : next(args, ++i));
            } else if ("-A".equals(opt) || "--user-agent".equals(opt)) {
                request.getHeaders().put("User-Agent", inline != null ? inline : next(args, ++i));
            } else if ("-e".equals(opt) || "--referer".equals(opt)) {
                request.getHeaders().put("Referer", inline != null ? inline : next(args, ++i));
            } else if ("-x".equals(opt) || "--proxy".equals(opt)) {
                parseProxy(request, inline != null ? inline : next(args, ++i));
            } else if ("-b".equals(opt) || "--cookie".equals(opt)) {
                request.getHeaders().put("Cookie", inline != null ? inline : next(args, ++i));
            } else if ("--url".equals(opt)) {
                request.setUrl(inline != null ? inline : next(args, ++i));
            } else if ("-F".equals(opt) || "--form".equals(opt)) {
                parseForm(request, inline != null ? inline : next(args, ++i));
            } else if ("-T".equals(opt) || "--upload-file".equals(opt)) {
                request.setUpload(new File(inline != null ? inline : next(args, ++i)), "file");
                if (method == null) {
                    method = "PUT";
                }
            } else if ("-k".equals(opt) || "--insecure".equals(opt)) {
                request.setInsecure(true);
            } else if ("-L".equals(opt) || "--location".equals(opt)) {
                request.setFollowRedirects(true);
            } else if ("--compressed".equals(opt) || "-s".equals(opt) || "--silent".equals(opt)
                    || "-v".equals(opt) || "--verbose".equals(opt) || "-i".equals(opt)
                    || "--include".equals(opt)) {
                // ignore
            } else if ("-o".equals(opt) || "--output".equals(opt) || "-m".equals(opt)
                    || "--max-time".equals(opt) || "--connect-timeout".equals(opt)) {
                if (inline == null) {
                    i++;
                }
            }
            i++;
        }
        if (hasData) {
            String body = data.toString();
            if (dataAsQuery) {
                String url = request.getUrl();
                if (url != null) {
                    request.setUrl(url + (url.indexOf('?') >= 0 ? '&' : '?') + body);
                }
            } else {
                request.setBody(body);
                if (request.getContentType() == null && request.getHeaders().get("Content-Type") == null) {
                    request.setContentType("application/x-www-form-urlencoded");
                }
                if (method == null) {
                    method = "POST";
                }
            }
        }
        if (method != null) {
            request.setMethod(method.toUpperCase(Locale.ROOT));
        }
        inferContentType(request);
        return request;
    }

    private static void inferContentType(CurlRequest request) {
        String ct = request.getHeaders().get("Content-Type");
        if (ct == null) {
            for (String key : request.getHeaders().keySet()) {
                if ("Content-Type".equalsIgnoreCase(key)) {
                    ct = request.getHeaders().get(key);
                    break;
                }
            }
        }
        if (ct != null) {
            request.setContentType(ct);
        }
    }

    private static void addHeader(CurlRequest request, String raw) {
        if (raw == null) {
            return;
        }
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String name = raw.substring(0, colon).trim();
        String value = raw.substring(colon + 1).trim();
        request.getHeaders().put(name, value);
    }

    private static void parseUser(CurlRequest request, String raw) {
        if (raw == null) {
            return;
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            request.setBasicUser(raw, "");
        } else {
            request.setBasicUser(raw.substring(0, colon), raw.substring(colon + 1));
        }
    }

    private static void parseProxy(CurlRequest request, String raw) {
        if (raw == null) {
            return;
        }
        String value = raw;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            int slash = value.indexOf("://");
            value = value.substring(slash + 3);
        }
        int colon = value.lastIndexOf(':');
        if (colon < 0) {
            request.setProxy(value, 80);
        } else {
            try {
                request.setProxy(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
            } catch (NumberFormatException e) {
                request.setProxy(value, 80);
            }
        }
    }

    private static void parseForm(CurlRequest request, String raw) {
        if (raw == null) {
            return;
        }
        int eq = raw.indexOf('=');
        if (eq < 0) {
            return;
        }
        String name = raw.substring(0, eq);
        String value = raw.substring(eq + 1);
        if (value.startsWith("@")) {
            request.setUpload(new File(value.substring(1)), name);
        } else {
            String body = request.getBody();
            String piece = name + "=" + value;
            request.setBody(body == null || body.isEmpty() ? piece : body + "&" + piece);
            if (request.getContentType() == null) {
                request.setContentType("application/x-www-form-urlencoded");
            }
        }
    }

    private static String stripFilePrefix(String value) {
        if (value != null && value.startsWith("@") && value.length() > 1 && !value.startsWith("@-")) {
            return value;
        }
        return value == null ? "" : value;
    }

    private static String next(List<String> args, int index) {
        if (index < 0 || index >= args.size()) {
            return "";
        }
        return args.get(index);
    }

    private static boolean looksLikeUrl(String arg) {
        String lower = arg.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ws://")
                || lower.startsWith("wss://");
    }
}
