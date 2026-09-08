# jkit-curl-codegen

把 curl 命令转成其它语言 / HTTP 库源码。

解析与执行在 [jkit](../README.md)（`CurlParser` / `HttpUtils.curl`），本库只读 `ParsedCurlRequest` 再生成源码。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-curl-codegen</artifactId>
    <version>2.0.1</version>
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
