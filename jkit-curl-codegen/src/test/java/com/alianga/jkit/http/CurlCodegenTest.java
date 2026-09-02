package com.alianga.jkit.http;

import com.alianga.jkit.http.codegen.CodeGenerator;
import com.alianga.jkit.http.codegen.CurlCodegen;
import com.alianga.jkit.http.codegen.GeneratedCode;
import com.alianga.jkit.http.codegen.GeneratorRegistry;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 各语言 / SDK 代码生成器回归。
 */
public class CurlCodegenTest {

    private static final String[] IDS = {
            "java-okhttp", "java-apache", "java-httpurlconnection", "java-unirest", "java-jdk", "java-jkit",
            "kotlin-okhttp", "js-fetch", "js-axios", "js-request", "js-unirest", "js-native",
            "js-jquery", "js-xhr",
            "py-requests", "py-httpx", "go-nethttp",
            "csharp-httpclient", "php-curl", "php-pecl-http",
            "powershell-restmethod", "shell-curl-windows", "shell-curl-powershell", "shell-wget",
            "swift-urlsession", "ruby-nethttp", "rust-reqwest", "r-httr2"
    };

    private static final String SAMPLE = "curl -kLs -XPOST 'https://example.com/v1/chat' "
            + "-H 'Authorization: Bearer tok' "
            + "--json '{\"q\":\"hi\"}' "
            + "-x 127.0.0.1:7890";

    @Test
    public void registryContainsAllAdvertisedGenerators() {
        Set<String> ids = new HashSet<String>();
        for (com.alianga.jkit.http.codegen.CodeGenerator g : GeneratorRegistry.get().list()) {
            ids.add(g.id());
        }
        assertTrue(ids.containsAll(Arrays.asList(IDS)));
        assertEquals(28, IDS.length);
    }

    @Test
    public void everyGeneratorEmitsUrlMethodAndJsonBody() {
        ParsedCurlRequest model = CurlParser.parseModel(SAMPLE);
        for (String id : IDS) {
            GeneratedCode code = GeneratorRegistry.get().generate(id, model);
            assertNotNull(id, code.source());
            assertFalse(id, code.source().trim().isEmpty());
            assertTrue(id + " missing url\n" + code.source(),
                    code.source().contains("https://example.com/v1/chat"));
            assertTrue(id + " missing json\n" + code.source(),
                    code.source().contains("hi") || code.source().contains("\\\"q\\\""));
        }
    }

    @Test
    public void jkitGeneratorUsesPerRequestIgnoreSslNotGlobalSetter() {
        GeneratedCode code = CurlCodegen.generate("java-jkit", SAMPLE);
        assertTrue(code.source().contains("ignoreSsl(true)"));
        assertFalse(code.source().contains("setIgnoreSsl"));
        assertTrue(code.source().contains("HttpUtils.execute"));
    }

    @Test
    public void okHttpAndFetchIncludeHeaders() {
        GeneratedCode ok = CurlCodegen.generate("java-okhttp", SAMPLE);
        assertTrue(ok.source().contains("addHeader"));
        assertTrue(ok.source().contains("OkHttpClient"));
        GeneratedCode fetch = CurlCodegen.generate("js-fetch", SAMPLE);
        assertTrue(fetch.source().contains("fetch("));
        assertTrue(fetch.language().equals("javascript"));
    }

    @Test
    public void pythonGoCsharpPhpCompileShape() {
        assertTrue(CurlCodegen.generate("py-requests", SAMPLE).source().contains("requests.request"));
        assertTrue(CurlCodegen.generate("py-httpx", SAMPLE).source().contains("httpx.Client"));
        assertTrue(CurlCodegen.generate("go-nethttp", SAMPLE).source().contains("http.NewRequest"));
        assertTrue(CurlCodegen.generate("csharp-httpclient", SAMPLE).source().contains("HttpClient"));
        assertTrue(CurlCodegen.generate("php-curl", SAMPLE).source().contains("curl_init"));
        assertTrue(CurlCodegen.generate("kotlin-okhttp", SAMPLE).source().contains("fun main"));
        assertTrue(CurlCodegen.generate("java-apache", SAMPLE).source().contains("HttpUriRequestBase"));
        assertTrue(CurlCodegen.generate("java-jdk", SAMPLE).source().contains("java.net.http.HttpClient"));
    }

    @Test
    public void newGeneratorsKeepTheirOwnShape() {
        assertTrue(CurlCodegen.generate("java-httpurlconnection", SAMPLE).source()
                .contains("HttpURLConnection"));
        assertTrue(CurlCodegen.generate("java-unirest", SAMPLE).source().contains("Unirest.post"));
        assertTrue(CurlCodegen.generate("js-request", SAMPLE).source().contains("require('request')"));
        assertTrue(CurlCodegen.generate("js-unirest", SAMPLE).source().contains("unirest("));
        assertTrue(CurlCodegen.generate("js-native", SAMPLE).source().contains("follow-redirects"));
        assertTrue(CurlCodegen.generate("js-jquery", SAMPLE).source().contains("$.ajax"));
        assertTrue(CurlCodegen.generate("js-xhr", SAMPLE).source().contains("XMLHttpRequest"));
        assertTrue(CurlCodegen.generate("php-pecl-http", SAMPLE).source().contains("http\\Client"));
        assertTrue(CurlCodegen.generate("powershell-restmethod", SAMPLE).source()
                .contains("Invoke-WebRequest"));
        assertTrue(CurlCodegen.generate("shell-curl-windows", SAMPLE).source().contains("curl"));
        assertTrue(CurlCodegen.generate("shell-curl-powershell", SAMPLE).source()
                .contains("curl.exe --%"));
        assertTrue(CurlCodegen.generate("shell-wget", SAMPLE).source().contains("wget"));
        assertTrue(CurlCodegen.generate("swift-urlsession", SAMPLE).source().contains("URLSession"));
        assertTrue(CurlCodegen.generate("ruby-nethttp", SAMPLE).source().contains("Net::HTTP"));
        assertTrue(CurlCodegen.generate("rust-reqwest", SAMPLE).source().contains("reqwest"));
        assertTrue(CurlCodegen.generate("r-httr2", SAMPLE).source().contains("httr2"));
    }

    @Test
    public void insecureAndProxyAreHonouredByCommandLineGenerators() {
        String windows = CurlCodegen.generate("shell-curl-windows", SAMPLE).source();
        assertTrue(windows.contains("--insecure"));
        assertTrue(windows.contains("--proxy"));
        // cmd.exe 版本：^ 续行的多行命令，不能带 --%（cmd.exe 不认识停止解析符号）
        assertTrue(windows.startsWith("curl"));
        assertFalse("cmd.exe 版本不能带 --%，会被当成 curl 的未知选项",
                windows.contains("--%"));
        String psCurl = CurlCodegen.generate("shell-curl-powershell", SAMPLE).source();
        assertTrue(psCurl.contains("--insecure"));
        assertTrue(psCurl.contains("--proxy"));
        // PowerShell 版本：单行 + UTF-8 前缀 + curl.exe + --%
        // （curl 是 Invoke-WebRequest 的别名；5.1 默认按 GBK 解码原生命令输出）
        assertTrue(psCurl.startsWith("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                + "curl.exe --%"));
        assertFalse(psCurl.substring(0, psCurl.length() - 1).contains("\n"));
        String wget = CurlCodegen.generate("shell-wget", SAMPLE).source();
        assertTrue(wget.contains("--no-check-certificate"));
        assertTrue(wget.contains("-e https_proxy="));
        assertTrue(wget.contains(" \\\n"));
    }

    @Test
    public void genericMethodFallsBackToGenericBuilder() {
        String curl = "curl -X TRACE https://example.com/trace";
        assertTrue(CurlCodegen.generate("java-unirest", curl).source().contains("Unirest.request(\"TRACE\""));
        assertTrue(CurlCodegen.generate("ruby-nethttp", curl).source().contains("Net::HTTPGenericRequest"));
        assertTrue(CurlCodegen.generate("rust-reqwest", curl).source().contains("Method::from_bytes"));
    }

    @Test
    public void multipartIsEmittedForNewGenerators() {
        String curl = "curl -F 'a=1' -F 'f=@/tmp/x.bin;filename=x.bin' https://example.com/up";
        assertTrue(GeneratorRegistry.get().generate("ruby-nethttp", CurlParser.parseModel(curl)).source()
                .contains("/tmp/x.bin"));
        assertTrue(GeneratorRegistry.get().generate("rust-reqwest", CurlParser.parseModel(curl)).source()
                .contains("multipart::Form"));
        assertTrue(GeneratorRegistry.get().generate("shell-curl-windows", CurlParser.parseModel(curl)).source()
                .contains("--form"));
        assertTrue(GeneratorRegistry.get().generate("js-jquery", CurlParser.parseModel(curl)).source()
                .contains("FormData"));
    }

    @Test
    public void multipartAppearsInOkHttpAndCurlRoundTrip() {
        String curl = "curl -F 'a=1' -F 'f=@/tmp/x.bin;filename=x.bin' https://example.com/up";
        ParsedCurlRequest model = CurlParser.parseModel(curl);
        String ok = GeneratorRegistry.get().generate("java-okhttp", model).source();
        assertTrue(ok.contains("MultipartBody"));
        assertTrue(ok.contains("/tmp/x.bin"));
        String round = CurlRequest.toCurl(model);
        assertTrue(round.contains("-F"));
        assertTrue(round.contains("@/tmp/x.bin"));
    }
    @Test
    public void curl2OkHttp() {
        String curl = "curl --url 'https://wx.mail.qq.com/list/maillist' \\\n" +
                "  -H 'accept: */*' \\\n" +
                "  -H 'accept-language: zh-CN,zh;q=0.9' \\\n" +
                "  -H 'content-type: application/x-www-form-urlencoded' \\\n" +
                "  -b 'pgv_pvid=7023543030; a_pk__04=98e5ac423f14c6d23fa66dae09000004d19b11; _qimei_fingerprint=ae194b7c8a5144b5ad23ad6c0dabe749; a_sk__05=01543531656d603663376c356165606036613530666735306237643035363163606d; pgv_info=ssid=s9197570480; a_sk__10=015465606564636732626260666463303263353530676337306766626d67616c633661656464; a_sk__07__0WEB071JJOW4VSP7=015465636c626166676c6261; qlogin_uid=d4c064b0d0de4eb76d1bb6ffa2d3d569; qq_domain_video_guid_verify=ac54e888c59b6180; _qimei_q36=; _qimei_h38=98e5ac423f14c6d23fa66dae09000004d19b11; qm_device_id=yyTVjkcDll2m12FIyDYKRgLr733ZiaahiRVctbdCso8TbZMZUVso65MaxizL312f; xm_uin=13102662097181836; qm_logintype=qq; _qimei_q32=; _qimei_i_2=23b94bdfe930; _qimei_i_1=7cc64b8b9d0f558a9293fc330d8577b3f1baa6f2440a0584e0de7d582f93206c616333c13980b0ddd7bdc0d5; a_sk__07__15d45fa36b498329=015465636c6c676665626360; xm_envid=456_Op6QLPleVk2xjXiCRR4JUqFVLwletzzLCR/oBfnAYqf8w3x8NghD4XU9IIhDCXhW58sbbr5EZ1AHw/hZnYmO/Rzlb++eHCYPqlT/iPsoMS0zL1e49WmPFNCccMtpFPtBQxQjME2b//wBDz2LVRfH0v8df9iAapsbM3jhsFk=; xm_pcache=13102662097181836&V2@X16xs6YhRF2oGMVm5d0IDgAA@0; xm_device_id=50233710; xm_sid=zYxMZowcOTAu6jJmAD9QZAAA; xm_muti_sid=13102662097181836&zYxMZowcOTAu6jJmAD9QZAAA; xm_skey=13102662097181836&5067ddbd3051b0ba2558dfbb82d77d1e; xm_ws=13102662097181836&f8bd33d629a91948d180adf9e9020724; xm_data_ticket=13102662097181836&CAESIGqYBzEALozNP-ocjF587YWo7RPmB7ybPcSWF9tQ2eRx' \\\n" +
                "  -H 'origin: https://wx.mail.qq.com' \\\n" +
                "  -H 'priority: u=1, i' \\\n" +
                "  -H 'referer: https://wx.mail.qq.com/' \\\n" +
                "  -H 'sec-ch-ua: \"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"' \\\n" +
                "  -H 'sec-ch-ua-mobile: ?0' \\\n" +
                "  -H 'sec-ch-ua-platform: \"Linux\"' \\\n" +
                "  -H 'sec-fetch-dest: empty' \\\n" +
                "  -H 'sec-fetch-mode: cors' \\\n" +
                "  -H 'sec-fetch-site: same-origin' \\\n" +
                "  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36' \\\n" +
                "  --data-raw 'dir=1&dirid=1&page_now=1&page_size=50&enable_topmail=true&func=1&sort_type=1&sort_direction=1&language=zh&r=27638380347761788348213280&sid=zYxMZowcOTAu6jJmAD9QZAAA'";
        ParsedCurlRequest model = CurlParser.parseModel(curl);
        List<CodeGenerator> list = GeneratorRegistry.get().list();
        System.out.println("支持的生成器数量 = " + list.size());
        for (CodeGenerator codeGenerator : list) {
            String library = codeGenerator.library();
            System.out.println("library = " + library);
            String id = codeGenerator.id();
            System.out.println("-------" + id + "--------");
            System.out.println(GeneratorRegistry.get().generate(id, model).source());
        }
    }

    @Test
    public void rHttr2UsesReqPerformNotIterative() {
        // req_perform_iterative() 的 next_req 是必填参数，且语义是分页不是重定向
        String src = CurlCodegen.generate("r-httr2", SAMPLE).source();
        assertTrue(src.contains("req_perform()"));
        assertFalse(src.contains("req_perform_iterative"));
        assertFalse("Content-Type 不应重复出现在 req_headers 与 req_body_raw",
                src.contains("`Content-Type`"));
        // followRedirects 默认 true：不输出 followlocation；关闭时输出 0
        assertTrue(src.contains("ssl_verifypeer = 0"));
        assertFalse(src.contains("followlocation"));
    }

    @Test
    public void powershellDecodesResponseAsUtf8FromRawBytes() {
        // PS 5.1 的 Invoke-RestMethod / $response.Content 在响应不带 charset 时
        // 按 ISO-8859-1 解码，UTF-8 中文全部乱码，必须从原始字节强制 UTF-8
        String src = CurlCodegen.generate("powershell-restmethod", SAMPLE).source();
        assertTrue(src.contains("Invoke-WebRequest"));
        assertTrue(src.contains("$response.RawContentStream.ToArray()"));
        assertTrue(src.contains("[System.Text.Encoding]::UTF8.GetString"));
        // 仍然自动解析 JSON，行为对齐原来的 Invoke-RestMethod
        assertTrue(src.contains("ConvertFrom-Json"));
        assertTrue(src.contains("ConvertTo-Json -Depth 10"));
    }

    @Test
    public void powershellMovesRestrictedHeadersOut() {
        String curl = "curl 'https://example.com/api' "
                + "-H 'User-Agent: Mozilla/5.0' -b 'sid=abc; uid=42' "
                + "-H 'Content-Type: application/json' --data-raw '{\"name\":\"张三\"}'";
        String src = CurlCodegen.generate("powershell-restmethod", curl).source();
        // 受限头不允许出现在 -Headers 哈希表里
        assertFalse(src.contains("'User-Agent'"));
        assertFalse(src.contains("'Cookie'"));
        // User-Agent 走 -UserAgent，Cookie 走 WebRequestSession
        assertTrue(src.contains("-UserAgent 'Mozilla/5.0'"));
        assertTrue(src.contains("WebRequestSession"));
        assertTrue(src.contains("System.Net.Cookie('sid', 'abc', '/', 'example.com')"));
        assertTrue(src.contains("-WebSession $session"));
        // 字符串正文补 charset=utf-8，避免 5.1 按 ISO-8859-1 发送中文
        assertTrue(src.contains("-ContentType 'application/json; charset=utf-8'"));
    }

    @Test
    public void powershellDropsConnectionAndQuotesCommaCookies() {
        // Connection 映射到 HttpWebRequest.Connection 属性后 keep-alive 直接抛异常
        String curl = "curl 'https://alianga.com/api/admin/posts/latest?top=5' "
                + "-H 'Accept: application/json, text/plain, */*' "
                + "-H 'Connection: keep-alive' "
                + "-H 'Referer: https://alianga.com/admin/index.html' "
                + "-b 'cna=asHbIUYCUnwBASQJjQDvo1dM; "
                + "Hm_lvt_f05e7750437761d7611ae4840f232014=1786346912,1788319581; "
                + "HMACCOUNT=A1C95D9022240F49' "
                + "-H 'User-Agent: Mozilla/5.0 (X11; Linux x86_64) Chrome/151.0.0.0'";
        String src = CurlCodegen.generate("powershell-restmethod", curl).source();
        // Connection 等受限头不允许出现在 -Headers 哈希表里
        assertFalse(src.contains("'Connection'"));
        assertTrue("普通头应保留在 -Headers 里", src.contains("'Referer'"));
        // 含逗号的 Cookie 值必须整体加双引号，否则 CookieContainer.Add 抛 CookieException
        assertTrue(src.contains("System.Net.Cookie('Hm_lvt_f05e7750437761d7611ae4840f232014', "
                + "'\"1786346912,1788319581\"', '/', 'alianga.com')"));
        // 无逗号的 Cookie 保持原样
        assertTrue(src.contains("System.Net.Cookie('cna', 'asHbIUYCUnwBASQJjQDvo1dM', "
                + "'/', 'alianga.com')"));
    }

    @Test
    public void powershellTranslatesConnectionCloseToDisableKeepAlive() {
        String curl = "curl 'https://example.com/api' -H 'Connection: close'";
        GeneratedCode code = CurlCodegen.generate("powershell-restmethod", curl);
        assertTrue(code.source().contains("-DisableKeepAlive"));
        assertFalse(code.source().contains("'Connection'"));
    }

    @Test
    public void powershellCurlIsPasteableInPowerShell() {
        // 浏览器复制的 curl：含双引号的 sec-ch-ua、含逗号的 Cookie、Connection 头
        String curl = "curl 'https://alianga.com/api/admin/posts/latest?top=5' "
                + "-H 'Accept: application/json, text/plain, */*' "
                + "-H 'Connection: keep-alive' "
                + "-H 'sec-ch-ua: \"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", "
                + "\"Chromium\";v=\"151\"' "
                + "-b 'cna=asHbIUYCUnwBASQJjQDvo1dM; "
                + "Hm_lvt_f05e7750437761d7611ae4840f232014=1786346912,1788319581' "
                + "-H 'User-Agent: Mozilla/5.0 (X11; Linux x86_64) Chrome/151.0.0.0'";
        String src = CurlCodegen.generate("shell-curl-powershell", curl).source();
        // PowerShell 里 curl 是 Invoke-WebRequest 的别名，必须 curl.exe；
        // --% 停止解析后，含双引号 / 逗号的头才会原样透传
        assertTrue(src.startsWith("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                + "curl.exe --% "));
        // 单行命令：^ 续行只在 cmd.exe 有效，多行粘贴到 PowerShell 会逐行执行
        assertFalse(src.substring(0, src.length() - 1).contains("\n"));
        // 含双引号的头按 MSVCRT 规则转义为 \"
        assertTrue(src.contains("--header \"sec-ch-ua: \\\"Not=A?Brand\\\";v=\\\"99\\\", "
                + "\\\"Google Chrome\\\";v=\\\"151\\\", \\\"Chromium\\\";v=\\\"151\\\"\""));
        // 含逗号的 Cookie 原样保留在 --header 里
        assertTrue(src.contains("--header \"Cookie: cna=asHbIUYCUnwBASQJjQDvo1dM; "
                + "Hm_lvt_f05e7750437761d7611ae4840f232014=1786346912,1788319581\""));
        // curl 没有 --no-location 选项：默认就是不跟随，开启时才输出 --location
        assertFalse(src.contains("--no-location"));
    }

    @Test
    public void windowsCmdCurlUsesCaretContinuation() {
        // 同一条 curl，cmd.exe 版本输出 ^ 续行的多行命令，可直接粘贴进 cmd.exe
        String curl = "curl 'https://alianga.com/api/admin/posts/latest?top=5' "
                + "-H 'Accept: application/json, text/plain, */*' "
                + "-H 'sec-ch-ua: \"Not=A?Brand\";v=\"99\"' "
                + "-H 'User-Agent: Mozilla/5.0 (X11; Linux x86_64) Chrome/151.0.0.0'";
        String src = CurlCodegen.generate("shell-curl-windows", curl).source();
        // cmd.exe 没有 curl 别名问题，直接以 curl 开头，且不能带 --%
        assertTrue(src.startsWith("curl "));
        assertFalse("cmd.exe 不认识 --%，会当成 curl 的未知选项报错", src.contains("--%"));
        // 除最后一行（URL）外，每行以 ^ 结尾续行
        java.util.List<String> nonEmpty = new java.util.ArrayList<String>();
        for (String l : src.split("\n", -1)) {
            if (!l.trim().isEmpty()) {
                nonEmpty.add(l);
            }
        }
        for (int i = 0; i < nonEmpty.size(); i++) {
            String line = nonEmpty.get(i).trim();
            if (i < nonEmpty.size() - 1) {
                assertTrue("中间行必须以 ^ 续行: " + line, line.endsWith("^"));
            } else {
                assertFalse("最后一行（URL）不能再有 ^", line.endsWith("^"));
            }
        }
        // 最后一行是 URL 本身
        assertTrue(src.trim().endsWith("\"https://alianga.com/api/admin/posts/latest?top=5\""));
        // 含双引号的头按 MSVCRT 规则转义为 \"
        assertTrue(src.contains("--header \"sec-ch-ua: \\\"Not=A?Brand\\\";v=\\\"99\\\"\" ^"));
    }
}
