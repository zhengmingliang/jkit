# jkit-curl-codegen

[![Maven Central](https://img.shields.io/maven-central/v/com.alianga/jkit-curl-codegen?style=flat-square)](https://central.sonatype.com/artifact/com.alianga/jkit-curl-codegen)

把 curl 命令转成其它语言 / HTTP 库源码。

解析与执行在 [jkit](../README.md)（`CurlParser` / `HttpUtils.curl`），本库只读 `ParsedCurlRequest` 再生成源码。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-curl-codegen</artifactId>
    <version>2.0.2</version>
</dependency>
```

会传递依赖 `com.alianga:jkit`。

本模块挂在仓库根的 `jkit-parent` 下，依赖 `com.alianga:jkit`（源码在 `jkit-core`）。从仓库根构建：

```bash
mvn test
# 或只构建本模块
mvn -pl jkit-curl-codegen -am test
```

## 用法

```java
import com.alianga.jkit.http.codegen.CurlCodegen;
import com.alianga.jkit.http.codegen.GeneratedCode;

String curl = "curl -X POST 'https://api.example.com/v1/chat' \\\n"
        + "  -H 'Authorization: Bearer tok' \\\n"
        + "  --data-raw '{\"model\":\"gpt-4\"}'";

GeneratedCode okhttp = CurlCodegen.generate("java-okhttp", curl);
GeneratedCode fetch = CurlCodegen.generate("js-fetch", curl);
System.out.println(okhttp.source());
```

已有 `ParsedCurlRequest` 时：

```java
import com.alianga.jkit.http.CurlParser;
import com.alianga.jkit.http.curl.ParsedCurlRequest;

ParsedCurlRequest model = CurlParser.parseModel(curl);
GeneratedCode code = CurlCodegen.generate("py-requests", model);
```

`CurlCodegen.list()` / `list("java")` 可枚举生成器。

## 转义

URL、头名/头值、正文、multipart 的字段名与文件名都按目标语言的字符串字面量规则转义，
统一走 `com.alianga.jkit.http.codegen.CodeQuote`：

| 方法 | 用途 |
| --- | --- |
| `js` / `py` | JS / Python 单引号字面量 |
| `go` / `csharp` / `rust` / `swift` | 对应语言的双引号字面量 |
| `kotlin` | 双引号字面量，额外转义 `${`，避免被当成模板插值求值 |
| `swiftEscape` | Swift 字符串内容（不含外层引号），用于嵌进已写好的字面量中间 |
| `r` / `rName` | R 的字符串字面量 / 反引号名（反引号内同样要转义反引号与反斜杠） |
| `php` / `ruby` | PHP 单引号字面量 / Ruby 双引号字面量（额外转义 `#{`） |
| `ps` / `sh` / `cmd` / `lua` | PowerShell / POSIX shell / Windows cmd / Lua |
| `json` / `jsonEscape` | JSON 字符串 |

新增生成器时不要裸拼字符串：语言没有对应方法的，先在 `CodeQuote` 里补，再在生成器里用。

## 备注与解析告警

`GeneratedCode.notes()` 保存生成备注。curl 解析阶段的告警（如 `未支持的选项 --digest，已跳过`、
`-b 指向 cookie 文件，未读入内容`）会合入 notes 最前面，其后才是生成器自己的备注
（如「浏览器 FormData 文件请换成 File/Blob 对象」）。建议把 notes 展示给用户，避免选项被静默忽略：

```java
GeneratedCode code = CurlCodegen.generate("java-okhttp", curl);
for (String note : code.notes()) {
    System.out.println("note: " + note);
}
```

## 生成器 id

| id | 语言 | 库 |
| --- | --- | --- |
| `java-okhttp` | Java | OkHttp 4 |
| `java-apache` | Java | Apache HttpClient 5 |
| `java-httpurlconnection` | Java | HttpURLConnection |
| `java-unirest` | Java | Unirest |
| `java-jdk` | Java | JDK HttpClient |
| `java-jkit` | Java | jkit HttpUtils |
| `kotlin-okhttp` | Kotlin | OkHttp |
| `js-fetch` | JavaScript | fetch |
| `js-axios` | JavaScript | axios |
| `js-request` | JavaScript | request |
| `js-unirest` | JavaScript | unirest |
| `js-native` | JavaScript | http（follow-redirects） |
| `js-jquery` | JavaScript | jQuery |
| `js-xhr` | JavaScript | XMLHttpRequest |
| `py-requests` | Python | requests |
| `py-httpx` | Python | httpx |
| `go-nethttp` | Go | net/http |
| `csharp-httpclient` | C# | HttpClient |
| `php-curl` | PHP | curl |
| `php-pecl-http` | PHP | pecl_http |
| `r-httr2` | R | httr2 |
| `rust-reqwest` | Rust | reqwest |
| `swift-urlsession` | Swift | URLSession |
| `ruby-nethttp` | Ruby | Net::HTTP |
| `powershell-restmethod` | PowerShell | Invoke-WebRequest（UTF-8 解码响应） |
| `shell-curl-windows` | shell | curl.exe（Windows cmd，`^` 续行多行） |
| `shell-curl-powershell` | shell | curl.exe（Windows PowerShell，单行 `--%`） |
| `shell-wget` | shell | wget |
| `httpie` | shell | HTTPie |
| `http` | http | HTTP/1.1 raw message |
| `har` | har | HAR 1.2 JSON |
| `ruby-httparty` | Ruby | HTTParty |
| `php-guzzle` | PHP | Guzzle |
| `lua` | Lua | socket.http (luasocket) |

自定义生成器实现 `CodeGenerator`，再 `GeneratorRegistry.get().register(...)`，或通过 `META-INF/services/com.alianga.jkit.http.codegen.CodeGenerator` 注册。
