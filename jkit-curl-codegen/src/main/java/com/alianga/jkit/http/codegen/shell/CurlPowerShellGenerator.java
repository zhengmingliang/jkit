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
 * PowerShell 下可直接粘贴运行的 curl.exe 命令（单行）。
 *
 * <p>PowerShell 中 {@code curl} 是 Invoke-WebRequest 的别名，且其参数解析器会把
 * {@code sec-ch-ua} 等含双引号的参数拆坏，因此 curl 部分固定以 {@code curl.exe --%}
 * 开头：{@code curl.exe} 绕过别名，{@code --%} 是 PowerShell 的停止解析符号，让整行参数
 * 按 cmd.exe 语法原样透传给 curl.exe。命令最前面先执行
 * {@code [Console]::OutputEncoding = [System.Text.Encoding]::UTF8}，
 * 否则 PowerShell 5.1 会按系统 ANSI 代码页（中文系统为 GBK）解码 curl 输出，
 * UTF-8 响应中的中文全部乱码。</p>
 *
 * <p>命令必须是单行：PowerShell 的续行符是反引号、cmd.exe 是 {@code ^}，
 * 二者互不兼容；且 {@code --%} 之后的反引号续行会被原样透传给 curl，
 * 多行写法在 PowerShell 中同样无法运行。要在 cmd.exe 中运行请使用
 * {@code shell-curl-windows} 生成器的多行 {@code ^} 版本。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CurlPowerShellGenerator extends AbstractCodeGenerator {
    @Override
    public String id() {
        return "shell-curl-powershell";
    }

    @Override
    public String language() {
        return "shell";
    }

    @Override
    public String library() {
        return "curl.exe (Windows PowerShell)";
    }

    @Override
    public GeneratedCode generate(ParsedCurlRequest req) {
        List<String> notes = new ArrayList<String>();
        StringBuilder args = new StringBuilder();
        if (req.followRedirects()) {
            args.append(" --location");
        }
        args.append(" --request ").append(req.method().toUpperCase(Locale.ROOT));
        for (Header h : CurlGenSupport.visibleHeaders(req)) {
            args.append(" --header ").append(CodeQuote.cmd(h.name() + ": " + h.value()));
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String fn = p.filename() == null ? "" : ";filename=" + p.filename();
                    args.append(" --form ").append(CodeQuote.cmd(p.name() + "=@" + p.filePath() + fn));
                } else {
                    args.append(" --form ").append(CodeQuote.cmd(p.name() + "="
                            + (p.value() == null ? "" : p.value())));
                }
            }
            notes.add("multipart 边界由 curl 自动生成，不要手动写 Content-Type。");
        } else if (body.kind() == Body.Kind.FILE) {
            args.append(" --data-binary ").append(CodeQuote.cmd("@" + body.filePath()));
            notes.add("正文来自本地文件，请确认路径在运行环境中可访问。");
        } else if (body.isPresent()) {
            args.append(" --data-raw ").append(CodeQuote.cmd(body.text()));
        }
        if (req.insecure()) {
            args.append(" --insecure");
            notes.add("已关闭证书校验，仅用于开发环境。");
        }
        if (req.compressed()) {
            args.append(" --compressed");
        }
        if (req.connectTimeoutSec() != null) {
            args.append(" --connect-timeout ").append(req.connectTimeoutSec());
        }
        if (req.timeoutSec() != null) {
            args.append(" --max-time ").append(req.timeoutSec());
        }
        if (req.proxy() != null) {
            args.append(" --proxy ")
                    .append(CodeQuote.cmd(req.proxy().scheme() + "://" + req.proxy().hostPort()));
            if (req.proxy().user() != null) {
                args.append(" --proxy-user ").append(CodeQuote.cmd(req.proxy().user() + ":"
                        + (req.proxy().password() == null ? "" : req.proxy().password())));
            }
        }
        args.append(" ").append(CodeQuote.cmd(req.url()));

        // PowerShell 5.1 默认按系统 ANSI 代码页（中文系统为 GBK/936）解码原生命令输出，
        // UTF-8 响应会中文乱码；先设 [Console]::OutputEncoding 再执行 curl。
        // 用 ; 连接保持整条命令仍是单行，--% 只作用于其后的 curl.exe 部分。
        String src = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                + "curl.exe --%" + args + "\n";
        notes.add("此命令面向 PowerShell：整行直接粘贴运行即可（curl.exe 绕过 "
                + "Invoke-WebRequest 别名，--% 让 PowerShell 原样传参，"
                + "含双引号的请求头不会被拆坏）。");
        notes.add("开头的 [Console]::OutputEncoding = UTF8 让 PowerShell 按 UTF-8 解码 "
                + "curl 的响应输出，避免中文乱码（5.1 默认按 GBK 解码）。");
        notes.add("在 cmd.exe 中请使用 shell-curl-windows 生成器："
                + "cmd.exe 不认识 --%，会把它当成 curl 的未知选项。");
        return new GeneratedCode("curl_powershell.ps1", "shell", src,
                Collections.<String>emptyList(), notes);
    }
}
