package com.alianga.jkit.http.codegen.powershell;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Windows PowerShell（Invoke-WebRequest + UTF-8 解码）。
 *
 * <p>注意 Windows PowerShell 5.1 的限制：
 * {@code -Headers} 不允许 User-Agent / Cookie 等受限头，本生成器把它们分别转成
 * {@code -UserAgent} 参数与 {@code WebRequestSession}；字符串正文默认按 ISO-8859-1
 * 发送，因此 Content-Type 统一附加 {@code charset=utf-8}。</p>
 *
 * <p>另外，Connection / Content-Length 等逐跳或分帧头无法通过 {@code -Headers}
 * 传递（PS 5.1 把 Connection 映射到 {@code HttpWebRequest.Connection} 属性后，
 * Keep-Alive / Close 值直接抛异常），生成时直接忽略并附注说明。</p>
 *
 * <p>响应解码用 {@code Invoke-WebRequest} + {@code RawContentStream} 而不是
 * {@code Invoke-RestMethod}：PS 5.1 在响应 Content-Type 不带 charset 时默认按
 * ISO-8859-1 解码，UTF-8 中文全部乱码，且 {@code Invoke-RestMethod} 没有参数
 * 可以覆盖；只有从原始字节强制 UTF-8 解码才可靠。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class PowerShellGenerator extends AbstractCodeGenerator {
    /**
     * {@code -Headers} 无法承载的请求头：逐跳 / 分帧头，或被 PS 5.1 映射到
     * {@code HttpWebRequest} 属性后必然抛异常的头（Connection/Range/Host 等）。
     */
    private static final List<String> UNSUPPORTED_HEADERS = Collections.unmodifiableList(
            Arrays.asList("connection", "keep-alive", "proxy-connection", "te", "trailer",
                    "transfer-encoding", "upgrade", "via", "content-length", "host",
                    "expect", "range", "if-modified-since", "date"));

    @Override
    public String id() {
        return "powershell-restmethod";
    }

    @Override
    public String language() {
        return "powershell";
    }

    @Override
    public String library() {
        return "Invoke-WebRequest (UTF-8 decode)";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder src = new StringBuilder();

        // 5.1 的 -Headers 是受限头黑名单：User-Agent / Cookie 会直接抛异常，先拆出来；
        // Connection 等逐跳 / 分帧头也无法设置，直接忽略
        String userAgent = null;
        String cookieHeader = null;
        boolean hasContentType = false;
        boolean disableKeepAlive = false;
        boolean quotedCookie = false;
        List<String> dropped = new ArrayList<String>();
        List<Header> plain = new ArrayList<Header>();
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            String lower = h.name().toLowerCase(Locale.ROOT);
            if ("content-type".equals(lower)) {
                hasContentType = true;
            } else if ("user-agent".equals(lower)) {
                userAgent = h.value();
            } else if ("cookie".equals(lower)) {
                cookieHeader = h.value();
            } else if (UNSUPPORTED_HEADERS.contains(lower)) {
                dropped.add(h.name());
                if ("connection".equals(lower)
                        && h.value().toLowerCase(Locale.ROOT).contains("close")) {
                    disableKeepAlive = true;
                }
            } else {
                plain.add(h);
            }
        }
        if (!dropped.isEmpty()) {
            notes.add("-Headers 不支持 Connection / Content-Length 等受限头，已忽略："
                    + joinNames(dropped) + "（keep-alive 是 PowerShell 的默认行为）。");
        }
        if (disableKeepAlive) {
            notes.add("Connection: close 已转成 -DisableKeepAlive 参数。");
        }

        if (!plain.isEmpty()) {
            src.append("$headers = @{\n");
            for (Header h : plain) {
                src.append("    ").append(CodeQuote.ps(h.name())).append(" = ")
                        .append(CodeQuote.ps(h.value())).append("\n");
            }
            src.append("}\n\n");
        }
        if (userAgent != null) {
            notes.add("User-Agent 是受限头，已改用 -UserAgent 参数传递。");
        }
        String host = hostOf(req.url());
        if (cookieHeader != null && host != null) {
            src.append("$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession\n");
            for (String pair : cookieHeader.split(";")) {
                String p = pair.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int eq = p.indexOf('=');
                String name = eq < 0 ? p : p.substring(0, eq).trim();
                String value = eq < 0 ? "" : p.substring(eq + 1);
                value = quoteCookieValue(value);
                if (value.startsWith("\"")) {
                    quotedCookie = true;
                }
                src.append("$session.Cookies.Add((New-Object System.Net.Cookie(")
                        .append(CodeQuote.ps(name)).append(", ").append(CodeQuote.ps(value))
                        .append(", '/', ").append(CodeQuote.ps(host)).append(")))\n");
            }
            src.append("\n");
            notes.add("Cookie 是受限头，已转成 WebRequestSession（Windows PowerShell 5.1 兼容）。");
            if (quotedCookie) {
                notes.add("Cookie 值含逗号 / 分号时 System.Net.Cookie 校验要求整体加双引号"
                        + "（引号会随请求原样发送，若服务端严格校验请改用 PowerShell 7 的 -Headers Cookie）。");
            }
        } else if (cookieHeader != null) {
            notes.add("无法从 URL 解析域名，Cookie 请改用 -WebSession 手动设置。");
        }

        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            notes.add("multipart 使用 -Form，需要 PowerShell 6 及以上。");
            src.append("$form = @{\n");
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    src.append("    ").append(CodeQuote.ps(p.name())).append(" = Get-Item ")
                            .append(CodeQuote.ps(p.filePath())).append("\n");
                } else {
                    src.append("    ").append(CodeQuote.ps(p.name())).append(" = ")
                            .append(CodeQuote.ps(p.value() == null ? "" : p.value())).append("\n");
                }
            }
            src.append("}\n\n");
        } else if (body.isPresent() && body.kind() != Body.Kind.FILE) {
            src.append("$body = ").append(CodeQuote.ps(body.text())).append("\n\n");
        }
        if (req.insecure()) {
            // 注释保持 ASCII，避免 5.1 按 ANSI 读取无 BOM 的 UTF-8 脚本时乱码
            src.append("# -k / --insecure: skip certificate validation (dev only)\n");
            src.append("[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }\n\n");
            notes.add("已生成信任全部证书的代码，仅用于开发环境；PowerShell 7+ 可直接加 -SkipCertificateCheck 参数。");
        }
        src.append("$response = Invoke-WebRequest");
        src.append(" -Uri ").append(CodeQuote.ps(req.url()));
        src.append(" `\n    -Method ").append(CodeQuote.ps(req.method().toUpperCase(Locale.ROOT)));
        src.append(" `\n    -UseBasicParsing");
        if (!plain.isEmpty()) {
            src.append(" `\n    -Headers $headers");
        }
        if (cookieHeader != null && host != null) {
            src.append(" `\n    -WebSession $session");
        }
        if (userAgent != null) {
            src.append(" `\n    -UserAgent ").append(CodeQuote.ps(userAgent));
        }
        boolean stringBody = body.isPresent() && body.kind() != Body.Kind.FILE
                && body.kind() != Body.Kind.MULTIPART;
        if (hasContentType || stringBody) {
            String ct = CurlGenSupport.mediaType(req);
            if (stringBody && !ct.toLowerCase(Locale.ROOT).contains("charset")) {
                // 5.1 对字符串正文默认按 ISO-8859-1 发送，显式声明 utf-8 避免中文乱码
                ct = ct + "; charset=utf-8";
            }
            src.append(" `\n    -ContentType ").append(CodeQuote.ps(ct));
        }
        if (body.kind() == Body.Kind.MULTIPART) {
            src.append(" `\n    -Form $form");
        } else if (body.kind() == Body.Kind.FILE) {
            src.append(" `\n    -InFile ").append(CodeQuote.ps(body.filePath()));
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            src.append(" `\n    -Body $body");
        }
        src.append(" `\n    -MaximumRedirection ").append(req.followRedirects() ? 5 : 0);
        if (disableKeepAlive) {
            src.append(" `\n    -DisableKeepAlive");
        }
        if (req.timeoutSec() != null) {
            src.append(" `\n    -TimeoutSec ").append(req.timeoutSec());
        }
        if (req.proxy() != null) {
            src.append(" `\n    -Proxy ").append(CodeQuote.ps(req.proxy().scheme() + "://"
                    + req.proxy().hostPort()));
            if (req.proxy().user() != null) {
                notes.add("带账号的代理请额外传 -ProxyCredential (Get-Credential)。");
            }
        }
        src.append("\n\n");
        // PS 5.1 decodes a response without charset as ISO-8859-1, which garbles
        // UTF-8 Chinese; decode from raw bytes to force UTF-8.
        // (comment kept ASCII: PS 5.1 reads a BOM-less script as ANSI)
        src.append("# decode response as UTF-8 (PS 5.1 defaults to ISO-8859-1 when charset is absent)\n");
        src.append("$text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())\n\n");
        src.append("try {\n");
        src.append("    $response = $text | ConvertFrom-Json\n");
        src.append("} catch {\n");
        src.append("    $response = $text\n");
        src.append("}\n\n");
        src.append("$response | ConvertTo-Json -Depth 10\n");
        notes.add("响应从 RawContentStream 原始字节按 UTF-8 解码：PS 5.1 的 Invoke-RestMethod / "
                + "$response.Content 在响应不带 charset 时按 ISO-8859-1 解码，中文会乱码；"
                + "原始文本保存在 $text 变量里。");
        notes.add("服务端明确返回 GBK 等非 UTF-8 编码时，请把 UTF8 换成对应编码。");
        notes.add("Windows PowerShell 5.1 的 ConvertTo-Json 对仅大小写不同的重复键会报"
                + "「已添加了具有相同键的项」，这是 5.1 的已知缺陷；"
                + "此时请改用 Invoke-WebRequest -UseBasicParsing 查看原始内容，或升级 PowerShell 7。");
        return new GeneratedCode("curl_powershell.ps1", "powershell", src.toString(),
                Collections.<String>emptyList(), notes);
    }

    /**
     * System.Net.Cookie 校验不允许裸的逗号 / 分号，必须按 quoted-string 包裹。
     *
     * @param value 原始 Cookie 值
     * @return 可通过 CookieContainer.Add 校验的值
     */
    private static String quoteCookieValue(String value) {
        boolean alreadyQuoted = value.length() >= 2
                && value.startsWith("\"") && value.endsWith("\"");
        if (!alreadyQuoted && (value.indexOf(',') >= 0 || value.indexOf(';') >= 0)) {
            return "\"" + value + "\"";
        }
        return value;
    }

    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host == null || host.isEmpty() ? null : host;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
