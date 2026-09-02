package com.alianga.jkit.http.codegen.shell;

import com.alianga.jkit.http.codegen.AbstractCodeGenerator;
import com.alianga.jkit.http.codegen.CodeQuote;
import com.alianga.jkit.http.codegen.CurlGenSupport;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Windows cmd.exe 下的 curl 命令（多行，行尾以 {@code ^} 续行）。
 *
 * <p>cmd.exe 中没有 {@code curl} 别名问题，也不支持 PowerShell 的
 * {@code --%} 停止解析符号（会被当成未知选项传给 curl），因此这里输出
 * cmd.exe 原生的多行 {@code ^} 写法，可直接粘贴到 cmd.exe 窗口，
 * 或保存为 {@code .bat} / {@code .cmd} 脚本执行。</p>
 *
 * <p>要在 PowerShell 里运行请改用 {@code shell-curl-powershell}
 * 生成器：PowerShell 把 {@code curl} 解析成 Invoke-WebRequest 的别名，
 * 且 {@code ^} 续行符在 PowerShell 中无效。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CurlWindowsGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "shell-curl-windows";
    }

    @Override
    public String language() {
        return "shell";
    }

    @Override
    public String library() {
        return "curl.exe (Windows cmd)";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        List<String> lines = new ArrayList<String>();
        StringBuilder head = new StringBuilder("curl");
        if (req.followRedirects()) {
            head.append(" --location");
        }
        head.append(" --request ").append(req.method().toUpperCase(Locale.ROOT));
        lines.add(head + " ^");
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            lines.add("  --header " + CodeQuote.cmd(h.name() + ": " + h.value()) + " ^");
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String fn = p.filename() == null ? "" : ";filename=" + p.filename();
                    lines.add("  --form " + CodeQuote.cmd(p.name() + "=@" + p.filePath() + fn) + " ^");
                } else {
                    lines.add("  --form " + CodeQuote.cmd(p.name() + "="
                            + (p.value() == null ? "" : p.value())) + " ^");
                }
            }
            notes.add("multipart 边界由 curl 自动生成，不要手动写 Content-Type。");
        } else if (body.kind() == Body.Kind.FILE) {
            lines.add("  --data-binary " + CodeQuote.cmd("@" + body.filePath()) + " ^");
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            lines.add("  --data-raw " + CodeQuote.cmd(body.text()) + " ^");
        }
        if (req.insecure()) {
            lines.add("  --insecure ^");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.compressed()) {
            lines.add("  --compressed ^");
        }
        if (req.connectTimeoutSec() != null) {
            lines.add("  --connect-timeout " + req.connectTimeoutSec() + " ^");
        }
        if (req.timeoutSec() != null) {
            lines.add("  --max-time " + req.timeoutSec() + " ^");
        }
        if (req.proxy() != null) {
            lines.add("  --proxy "
                    + CodeQuote.cmd(req.proxy().scheme() + "://" + req.proxy().hostPort()) + " ^");
            if (req.proxy().user() != null) {
                lines.add("  --proxy-user " + CodeQuote.cmd(req.proxy().user() + ":"
                        + (req.proxy().password() == null ? "" : req.proxy().password())) + " ^");
            }
        }
        lines.add("  " + CodeQuote.cmd(req.url()));

        notes.add("此命令面向 cmd.exe：整段直接粘贴即可，行尾的 ^ 是 cmd.exe 续行符。");
        notes.add("响应中文乱码时，先执行 chcp 65001 把控制台切到 UTF-8 代码页再运行。");
        notes.add("在 PowerShell 中请使用 shell-curl-powershell 生成器："
                + "PowerShell 里 curl 是 Invoke-WebRequest 的别名，且 ^ 续行无效。");
        notes.add("保存为 .bat / .cmd 执行时，正文里的 % 要写成 %%，否则会被当成批处理变量。");
        return new GeneratedCode("curl_windows.bat", "shell",
                joinLines(lines) + "\n", Collections.<String>emptyList(), notes);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
