# HTTP 模块使用指南

`com.alianga.jkit.HttpUtils` 是纯 JDK、零第三方依赖的 HTTP 客户端，从 ZmlTools 的 OkHttp 版本迁移而来，对外能力对齐原 `HttpUtils`：GET/POST、JSON Body、multipart 上传、文件下载、Cookie、代理、伪造 IP、忽略 HTTPS 证书。

底层实现按运行时 JDK 自动选择：

| 运行环境 | 实现 |
| --- | --- |
| JDK 8 | `java.net.HttpURLConnection`（`UrlConnectionHttpEngine`） |
| JDK 9+ 且存在 `java.net.http.HttpClient` | `java.net.http.HttpClient`（`JdkHttpClientEngine`，位于多版本 JAR 的 `META-INF/versions/11`） |
| 上述类加载失败时 | 回退到 `HttpURLConnection` |

`java.net.http.HttpClient` 是 JDK 11 起的标准 API。JDK 9/10 没有该包，会自动走 `HttpURLConnection`。可用系统属性 `jkit.http.engine=url` 或 `jdk` 强制选择。

详细类型位于 `com.alianga.jkit.http`：`HttpResponse`、`HttpResponseBody`、`HttpRequest`、`HttpCookieJar`、`UploadInfo`、`HttpCallBack` 等。原 OkHttp 的 `Response` / `ResponseBody` / `CookieJar` / `Call` 已替换为上述 JDK 类型。

## 1. GET

```java
import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.HttpResponse;

// 简单 GET，返回正文
String html = HttpUtils.get("https://example.com");

// 查询参数（null 值跳过；数组 / List 展开为同名多值）
Map<String, Object> params = new LinkedHashMap<String, Object>();
params.put("name", "张三");
params.put("ids", Arrays.asList(1, 2));
String json = HttpUtils.get("https://api.example.com/search", params);

// 自定义请求头（非空时会替换默认 User-Agent）
Map<String, String> headers = new HashMap<String, String>();
headers.put("Authorization", "Bearer token");
String body = HttpUtils.get(url, params, headers);

// 只传请求头、无 query
HttpResponse onlyHeader = HttpUtils.getResponse(url, headers);
String same = HttpUtils.get(url, null, headers);
int code = response.code();
boolean ok = response.isSuccessful();       // 200–299
String contentType = response.header("Content-Type");
String text = response.body().string();
byte[] bytes = response.body().bytes();
response.close();
```

`getRequestParamString(params)` 只负责把 Map 编成 `a=1&b=2`，不会发请求。中文与空格按 UTF-8 做 `application/x-www-form-urlencoded` 编码。

## 2. POST 表单

```java
Map<String, Object> form = new HashMap<String, Object>();
form.put("username", "tom");
form.put("password", "secret");

String text = HttpUtils.postForm(url, form);          // 推荐
String text2 = HttpUtils.getStringFromPost(url, form); // 旧名
String withHeader = HttpUtils.postForm(url, form, headers);
byte[] bytes = HttpUtils.getBytesFromPost(url, form);
InputStream in = HttpUtils.getInputStreamFromPost(url, form);

HttpUtils.post(url);                                  // 空 body
HttpResponse onlyHeader = HttpUtils.getResponseFromPost(url, headers);

// 带自定义头
HttpResponse response = HttpUtils.getResponseFromPost(url, form, headers);
```

表单的 Content-Type 为 `application/x-www-form-urlencoded`，字段会做 URL 编码。`null` 值会写成字符串 `"null"`（与原 OkHttp 版本 `value + ""` 一致）。

## 3. POST JSON / 任意 Body

```java
// 对象会先走 JSON.toJsonString
Map<String, Object> payload = new LinkedHashMap<String, Object>();
payload.put("name", "Tom");
payload.put("age", 18);
String resp = HttpUtils.postJson(url, payload);           // 推荐
String resp0 = HttpUtils.sendRequestBody(url, payload);   // 旧名

// 已经是字符串则原样发送（默认 application/json; charset=utf-8）
String resp2 = HttpUtils.sendRequestBody(url, "{\"ok\":true}");

// 指定 Content-Type（XML、plain 等）
String xml = HttpUtils.sendRequestBody(url, "<id>1</id>", "application/xml");

// 同时传请求头
String resp3 = HttpUtils.sendRequestBody(url, json, headers, "application/json");
```

全局默认类型可改：

```java
HttpUtils.defaultMediaType = "application/json; charset=utf-8";
```

`sendRequestBody(url, Object, ...)` 始终把对象序列化成 JSON；`sendRequestBody(url, String, ...)` 把字符串当正文，不再二次编码。

## 4. 文件上传

```java
import com.alianga.jkit.http.UploadInfo;
import com.alianga.jkit.http.HttpResponse;

UploadInfo info = new UploadInfo("file", "/tmp/report.pdf", "report.pdf");
info.setMediaType("application/pdf");     // 可省略，省略时按文件探测

Map<String, String> extra = new HashMap<String, String>();
extra.put("bizId", "1001");

HttpResponse response = HttpUtils.upload(url, info, extra, headers);
String result = response.body().string();
```

实现为 `multipart/form-data`。`UploadInfo` 的 `key` 默认是 `file`，`fileName` 为空时使用本地文件名。文件必须存在且为普通文件；字段名和文件名中的 CR/LF 会被过滤，避免 multipart Header 注入。当前 `upload` 仍会将 multipart 请求体缓冲到内存，大文件建议使用 `putFile`；后续可增加流式 multipart API。

## 5. 文件下载（download）

旧名 `getFileFromHttpDataBySyn/Asyn` 仍可用，但已标记 `@Deprecated`，请改用 `download` / `downloadAsync`。

```java
import com.alianga.jkit.http.HttpCallBack;
import java.io.File;
import java.util.concurrent.Future;

// 同步：阻塞到写完，返回绝对路径
String path = HttpUtils.download(fileUrl, "data.zip", "/tmp/downloads");
String path2 = HttpUtils.download(fileUrl, new File("/tmp/data.zip"));

// 断点续传：本地已有内容时发 Range，服务端 206 则追加
HttpUtils.download(fileUrl, new File("/tmp/data.zip"), true);

// 进度：onProcess(已写入字节, 总大小未知为 -1)
HttpUtils.download(fileUrl, dest, false, new HttpCallBack<String>() {
    public void onProcess(long saved, long total) { }
    public void onFailure(HttpCall call, IOException e) { }
    public void onResponse(HttpCall call, HttpResponse response, String result) { }
});

// 异步，返回 Future（使用全局读超时，不再是 1 秒）
Future<String> future = HttpUtils.downloadAsync(fileUrl, dest);
String saved = future.get();
```

同步下载在兼容旧行为下遇到 `SocketTimeoutException` 时最多重试 3 次。普通请求默认不重试；如需统一重试请配置 `RetryPolicy`。大文件始终流式写盘，不会把整个响应当成 `byte[]`。

超大 GET 响应不要用 `get()`（会缓冲），改用：

```java
HttpResponse stream = HttpUtils.openStream(url);
try (InputStream in = stream.body().byteStream()) {
    // 边读边处理
} finally {
    stream.close();
}
```

解析文件名：

```java
String name = HttpUtils.getFileName(fileUrl);          // 发 HEAD
String name2 = HttpUtils.getFileName(response);        // 从响应头 / URL

// 只有一个 Content-Disposition 头值时，直接用解析入口
String name3 = HttpUtils.parseFileName(contentDisposition, "fallback.bin");
```

支持 `Content-Disposition: filename=` 与 RFC 5987 的 `filename*=UTF-8''...`。

`HttpUtils.parseFileName(contentDisposition, defaultName)` 是全库该头解析的唯一实现，
`getFileName(HttpResponse)` 和 `FileUtils.getFileNameFromHttp(Map)` 都转调它。
解析不到文件名时返回 `defaultName`（传 `null` 归一为空串）。

`FileUtils.getFileNameFromHttp(String url)` 现在转调 `HttpUtils.getFileName(url)`（改为 HEAD 请求），
响应头里取不到时退回从 URL 截取；`FileUtils.getFileNameFromHttp(Map)` 查找响应头名时**大小写不敏感**，
适配服务端返回的任意大小写。

### 原子下载 + SHA-256 校验

先写临时文件，成功后原子替换目标文件：目标文件要么完整要么保持旧内容，失败自动清理 `.part` 临时文件。

```java
// 下载并校验 SHA-256（十六进制，大小写不敏感），校验失败抛 IOException 且不替换旧文件
String path = HttpUtils.downloadAtomic(fileUrl, new File("/tmp/data.zip"), expectedSha256);

// 带进度回调
HttpUtils.downloadAtomic(fileUrl, dest, expectedSha256, new HttpCallBack<String>() {
    public void onProcess(long saved, long total) { }
    public void onFailure(HttpCall call, IOException e) { }
    public void onResponse(HttpCall call, HttpResponse response, String result) { }
});

// 不校验哈希，仅保证原子替换
HttpUtils.downloadAtomic(fileUrl, dest);
```

适合定时拉取配置、模型文件、升级包等"不能出现半截文件"的场景。计算本地文件 SHA-256 可用：

```java
EncryptUtils.sha256(new File("/tmp/data.zip"))   // 返回小写十六进制
```

## 6. Cookie

默认使用内存 `CookieJarImpl`。同一 JVM 内后续请求会自动带上已保存的 Cookie。

```java
HttpUtils.setCookieJar(new CookieJarImpl());   // 换一个干净仓库
String cookieHeader = HttpUtils.getCookieValue(response);  // sid=abc;
```

### 单请求 Cookie

不经过全局 CookieJar，直接随请求发送（优先于全局仓库）：

```java
HttpResponse response = HttpUtils.execute(HttpRequest.get(url)
        .cookie("token", "abc")
        .cookies(mapOf("trace", "1")));
```

### CookieJar 持久化与导入导出

`CookieJarImpl` 为线程安全内存仓库，按 RFC 6265 语义做 Domain / Path / Secure 过滤，
Max-Age / Expires 过期自动清理：

```java
CookieJarImpl jar = new CookieJarImpl();
HttpUtils.setCookieJar(jar);

// JSON 持久化（保留 HttpOnly / Secure / 过期时间，重启后恢复登录态）
jar.saveTo(new File("cookies.json"));
new CookieJarImpl().loadFrom(new File("cookies.json"));

// Netscape cookies.txt 格式（可被浏览器 / curl 使用；不含 HttpOnly 字段）
jar.exportNetscape(new File("cookies.txt"));
new CookieJarImpl().importNetscape(new File("cookies.txt"));

// 管理
jar.remove("sid");                     // 按名称删除
jar.remove("sid", "example.com");     // 按名称 + 域删除（域比较忽略前导点与大小写）
jar.clear();
jar.size();
```

自定义仓库实现 `HttpCookieJar` 即可接入 Redis 等外部存储。

## 7. PUT / DELETE / PATCH / 认证

```java
HttpUtils.putJson(url, payload);
HttpUtils.putJson(url, payload, headers);
HttpUtils.put(url, "<xml/>", "application/xml");
HttpUtils.put(url, body, headers, "application/xml");
HttpUtils.put(url, headers);                          // 空 body
HttpUtils.putFile(url, new File("/tmp/big.bin"), "application/octet-stream");
HttpUtils.putFile(url, file, "application/octet-stream", headers);
HttpUtils.patchJson(url, payload, headers);
HttpUtils.patch(url, body, headers, "application/xml");
HttpUtils.delete(url);
HttpUtils.delete(url, headers);
HttpUtils.delete(url, "{\"id\":1}");                  // 带 body
HttpUtils.deleteJson(url, payload, headers);

headers.put("Authorization", HttpUtils.basicAuth("user", "pass"));
headers.put("Authorization", HttpUtils.bearer("token"));
```

自定义方法走 `HttpUtils.execute(HttpRequest.put(url).body(...))`。

## 8. 代理、伪造 IP、HTTPS、HTTP/2

### HTTPS 安全说明

为兼容旧版 API，`supportHttps()` 和 `setIgnoreSsl(true)` 仍可开启“信任所有证书”模式，但这会关闭证书链与主机名校验，只适合本地测试、抓包和临时开发环境。

生产环境推荐显式调用：

```java
HttpUtils.useSecureSsl();                 // 使用 JDK 默认证书链和主机名校验
// 或者为自签名证书配置专用 SSLContext：
HttpUtils.setSsl(customSslContext, null);
```

更推荐避免全局状态，直接在请求级别配置：

```java
HttpRequest request = HttpRequest.get(url)
        .ignoreSsl(false)
        .sslContext(customSslContext);
HttpResponse response = HttpUtils.execute(request);
try {
    response.requireSuccessful();
    String text = response.body().string();
} finally {
    response.close();
}
```

`requireSuccessful()` 会将非 2xx 响应转换为 `HttpStatusException`，异常中包含状态码、请求 URL 和原始响应对象。原有行为仍保持兼容：普通 `getResponse` 不会因为 4xx/5xx 自动抛异常。

```java
// HTTP / SOCKS 代理
HttpUtils.setHttpProxy("127.0.0.1", 7890);
HttpUtils.setHttpProxy("127.0.0.1", 7890, "user", "pass"); // Proxy-Authorization
HttpUtils.setSocksProxy("127.0.0.1", 1080);
HttpUtils.setProxy(null);

HttpUtils.fakeIp = true;

// 本地排查：发送前打印请求摘要 / 可复制的 curl。会带出 Authorization 等敏感头，不要在生产打开。
// debug 打方法、URL、头和正文预览（二进制只打字节数，长正文截断）；printCurl 打等价 curl。
HttpUtils.debug = true;
HttpUtils.printCurl = true;

// 兼容旧版：忽略证书（仅测试使用）
HttpUtils.supportHttps();
// 生产推荐：使用默认证书链和主机名校验
HttpUtils.useSecureSsl();
// 旧名称仍可用
HttpUtils.verifySsl();
HttpUtils.ignoreSNI();

// HTTP/2：JDK 11+ 默认优先协商（明文 http:// 仍是 1.1）
HttpUtils.setHttp2(true);
```

连接复用：JDK 11+ 的 `HttpClient` 本身做连接池；`HttpURLConnection` 依赖 JVM keep-alive（`http.keepAlive` / `http.maxConnections`）。

## 9. 重试与超时、内存

默认不自动重试。需要重试时，应显式配置，并注意只有幂等请求默认允许因 IO 错误重试：

```java
HttpUtils.config().setRetryPolicy(RetryPolicy.builder()
        .maxAttempts(3)
        .initialBackoffMs(200)
        .maxBackoffMs(3000)
        .retryOnIOException()
        .retryStatus(408, 429, 500, 502, 503, 504)
        .build());
```

POST/PATCH 默认不会因异常自动重试；如业务能够保证幂等，可显式调用 `retryNonIdempotent(true)`。重试策略为全局配置，实例化客户端隔离将在后续 API 中提供。



```java
HttpUtils.setConnectTimeout(10_000);
HttpUtils.setReadTimeout(30_000);
HttpUtils.setMaxBufferBytes(16L * 1024 * 1024);  // get/post 缓冲上限，超出抛错
HttpUtils.config().setDownloadBufferSize(64 * 1024);
```

`get()` / `postJson()` 会把正文放进内存，所以上限默认 64MB。下载、`openStream`、SSE 走流，不受该上限限制。

## 10. SSE

读超时对 SSE 设为不限制。规范字段 `data` / `event` / `id` / `retry` 与注释行均支持。
不少大模型接口（如 SenseNova Chat Completions）每行一条完整 `data: {json}`，中间没有空行；解析时会把这类完整 JSON / `[DONE]` 立即当成一条事件，流结束也会冲掉缓冲区。

```java
HttpCall call = HttpUtils.sse(url, new SseListener() {
    public void onEvent(SseEvent event) {
        System.out.println(event.getEvent() + " " + event.getData());
    }
    public void onError(IOException e) { e.printStackTrace(); }
});
HttpUtils.sse(url, headers, listener);                // GET + 头
HttpUtils.sse(url, params, headers, listener);        // GET + query
HttpUtils.sseJson(url, payload, headers, listener);   // POST JSON body
HttpUtils.sse(url, headers, rawBody, "application/json", listener);

// 自定义 HttpRequest（超时、代理、正文等按请求对象走）
HttpRequest request = HttpRequest.post(url)
        .header("Authorization", "Bearer tok")
        .contentType("application/json")
        .body("{\"stream\":true}");
HttpUtils.sse(request, listener);
HttpUtils.sseMerge(request, SseMergeFormat.OPENAI, mergeListener);
HttpUtils.sseReconnect(request, options, listener);

// 浏览器里复制的 curl 可直接转 SSE / 自动合并
CurlRequest parsed = HttpUtils.parseCurl(curl);
HttpUtils.sse(parsed, listener);                      // 或 parsed.toHttpRequest()
HttpUtils.sseMerge(parsed, SseMergeFormat.OPENAI, mergeListener);
HttpUtils.sseMerge(HttpUtils.curlToRequest(curl), SseMergeFormat.AUTO, mergeListener);
```

### 自动合并

`sseMerge` / `sseMergeJson` 按厂商协议把增量拼成正文、思维链和工具调用参数。`onDelta` 每次给出累计结果和本片增量：

```java
HttpUtils.sseMergeJson(url, payload, headers, SseMergeFormat.OPENAI, new SseMergeListener() {
    public void onDelta(SseMergeResult snap) {
        System.out.print(snap.getThinkingDelta());  // 思维链增量（可能先于正文）
        System.out.print(snap.getContentDelta());   // 回复增量
        // snap.getContent() / getThinking() / getToolCall() 为目前已合并全文
    }
    public void onComplete(SseMergeResult result) {
        System.out.println("\nDONE: " + result.getContent());
    }
});
```

| 格式 | 适用 | 增量路径 | 结束标记 |
| --- | --- | --- | --- |
| `AUTO` | 不确定厂商时优先 | 按事件名 / JSON 结构识别 | 随识别结果 |
| `OPENAI` | Chat Completions 及兼容层（DeepSeek / GLM / Qwen / Kimi / OpenRouter / **SenseNova**） | `choices[0].delta.content`；思维链 `reasoning` / `reasoning_content` | `[DONE]`，或非空 `finish_reason`（空字符串不算结束） |
| `OPENAI_RESPONSES` | OpenAI `/v1/responses` | `response.output_text.delta` | `response.completed` |
| `CLAUDE` | Anthropic Messages | `text_delta` / `thinking_delta` | `message_stop`（不是 `content_block_stop`） |
| `GEMINI` | `streamGenerateContent?alt=sse` | `parts[].text`；`thought:true` 记入思考 | 非空 `finishReason` |
| `DASHSCOPE` | 通义原生（`X-DashScope-SSE: enable`） | `output.choices[0].message.content` | 非空 `finish_reason` |
| `ERNIE` | 文心一言 / 千帆原生 | 顶层 `result` | `is_end: true` |
| `OLLAMA_GENERATE` / `OLLAMA_CHAT` | Ollama | `response` / `message.content` | `done: true` |
| `RAW` | 未知协议 | 拼接 `data` 原文 | — |
| `jsonPath("$.a.b")` | 自定义 | 指定 JSON 路径 | — |

OpenAI 兼容流里，推理模型往往会先推很长一段 `delta.reasoning`，此时 `getContent()` 仍可能为空，思维链在 `getThinking()`。最后可能还有一条 `choices: []` 只带 usage，随后 `data: [DONE]`。

`call.cancel()` 可停止读取。

### 自动重连（Last-Event-ID 续传）

`HttpUtils.sse` 连接中断即结束；需要断线重连请用 `sseReconnect`，重连时自动携带
`Last-Event-ID` 头，服务端可从断点续传：

```java
SseReconnectOptions options = SseReconnectOptions.builder()
        .maxRetries(-1)              // -1 不限次数（默认）
        .initialBackoffMs(1000)      // 首次退避
        .maxBackoffMs(30_000)        // 退避上限
        .multiplier(2.0)             // 指数倍增
        .reconnectOnStreamEnd(true)  // 服务端正常关流也重连（LLM 一次性输出场景可关）
        .honorServerRetry(true)      // 尊重服务端 retry: 字段
        .build();

HttpCall call = HttpUtils.sseReconnect(url, options, new SseListener() {
    public void onEvent(SseEvent event) { }
    public void onReconnect(int attempt, long delayMs, Throwable cause) {
        // 第 attempt 次重连，等待 delayMs 毫秒
    }
    public void onError(IOException e) { }
    public void onClosed() { }   // 取消或重试耗尽后回调一次
});
call.cancel();   // 停止重连
```

## 11. curl 解析与执行

把复制来的 curl 转成 `HttpRequest` 并直接发出：

```java
String curl = "curl -X POST 'https://api.example.com/v1/chat' \\\n"
        + "  -H 'Authorization: Bearer tok' \\\n"
        + "  -H 'Content-Type: application/json' \\\n"
        + "  --data-raw '{\"model\":\"gpt-4\"}'";

CurlRequest parsed = HttpUtils.parseCurl(curl);
HttpRequest request = HttpUtils.curlToRequest(curl);
HttpResponse response = HttpUtils.curl(curl);
String body = HttpUtils.curlString(curl);
```

支持 `-X/-H/-d/--data-raw/--data-urlencode/--json/-u/-G/-k/-x/-b/-A/-e/-F/-T/--url` 等常见选项，
短选项簇（`-kLs`、`-XPOST`）会展开。`-k` 只作用于这一次请求，不会改全局 `HttpUtils.setIgnoreSsl`。

解析后也可改 header/body 再 `parsed.execute()`，
或把 `parsed.toHttpRequest()` / `parsed` 交给 `HttpUtils.sse` / `sseMerge` / `sseReconnect`。

需要把 curl 转成 OkHttp / fetch / requests 等其它语言源码时，请用独立模块
`com.alianga:jkit-curl-codegen`（见 [jkit-curl-codegen/README.md](https://github.com/zhengmingliang/jkit/blob/develop/jkit-curl-codegen/README.md)）。
jkit 本身只做解析和执行：`parseModel` 给出的 `ParsedCurlRequest` 就是生成器的输入。

除语言 SDK 外，本模块也提供与 curlconverter 对齐的互操作格式：`http`（原始 HTTP 报文）、
`har`（HAR 1.2 JSON）、`httpie`（HTTPie CLI），以及 `ruby-httparty` / `php-guzzle` /
`lua`（luasocket `socket.http`）。

## 12. WebSocket（JDK 11+）

```java
WebSocketSession ws = HttpUtils.webSocket("wss://example.com/chat", new WebSocketListener() {
    public void onOpen() { }
    public void onText(String text, boolean last) { }
    public void onClose(int code, String reason) { }
});
ws.sendText("hello");
ws.close();
```

JDK 8 或未加载 `java.net.http` 时抛出 `IOException`，提示需要 JDK 11+。

## 13. 引擎选择与扩展

```java
String engine = HttpUtils.getEngineName();
// "jdk-http-client" 或 "http-url-connection"

// 强制使用 HttpURLConnection（测试或排查问题时）
HttpUtils.setEngine(HttpEngines.urlConnection());

// 恢复自动选择
HttpUtils.setEngine(null);
```

启动参数：

```text
-Djkit.http.engine=url
-Djkit.http.engine=jdk
```

自行实现 `HttpEngine#execute(HttpRequest)` 后通过 `HttpUtils.setEngine` 注入，即可换成连接池、日志拦截等定制传输。

## 14. 与原 OkHttp 版本的差异

- 返回类型由 `okhttp3.Response` / `ResponseBody` 改为 `HttpResponse` / `HttpResponseBody`，常用方法（`code()`、`body().string()`、`bytes()`、`byteStream()`、`header()`）对齐。
- `getOkHttpClient()` 改为 `getHttpEngine()`，不再暴露 OkHttp 类型。
- `setFakeIpHeader` 改为写入 `Map<String, String>`。
- `defaultMediaType` 从 OkHttp `MediaType` 改为字符串。
- `Cookies`（依赖 Servlet）未迁移；客户端 Cookie 请用 `HttpCookieJar`。
- 4xx/5xx **不抛异常**，响应体仍可读取（与 OkHttp 一致）。`HttpURLConnection` 的 404 `FileNotFoundException` 已在引擎内消化。
- 传输层默认声明 `gzip, deflate, br`，并自动解压这三种响应。`br` 解码器移植自 Google Brotli（MIT）。未支持的算法（如 `zstd`）不会出现在发出的 `Accept-Encoding` 里；若响应仍带未知 `Content-Encoding`，`decodeContentEncoding` 会原样返回字节流，由调用方自行解码。可用 `ContentEncodings.register(...)` 扩展。
- 下载请用 `download` / `downloadAsync` / `downloadAtomic`；旧方法名保留为废弃别名。
- JDK 11+ 默认优先 HTTP/2；可用 `HttpUtils.setHttp2(false)` 关闭。

## 15. 请求模型与响应体增强

### HttpRequest 增强能力

```java
HttpRequest request = HttpRequest.get(url)
        .query("name", "张三")            // 结构化 query 参数，发送时自动 URL 编码
        .query("ids", Arrays.asList(1, 2)) // 集合值展开为多个同名参数
        .cookie("token", "abc")            // 单请求 Cookie
        .userAgent("jkit")
        .accept("application/json")
        .acceptLanguage("zh-CN")
        .referer("https://example.com")
        .bearerToken("tok")                 // Authorization: Bearer tok
        .ifNoneMatch("W/\"v1\"")           // ETag 条件请求，未变化返回 304
        .ifModifiedSince("Wed, 21 Oct 2026 07:28:00 GMT")
        .range(1024, 2048)                  // Range: bytes=1024-2048（另有 rangeFrom / rangeSuffix）
        .tag("order-sync")                  // 请求标签，用于日志与链路追踪
        .expectContinue(true);              // Expect: 100-continue（不支持的引擎会忽略）

request.post(url).body("文本内容");           // UTF-8
request.post(url).body("文本内容", Charset.forName("GBK")); // 指定字符集
Object tag = request.getTag();

HttpResponse response = HttpUtils.execute(request); // 发送
```

说明：JDK 的 `HttpURLConnection` / `HttpClient` 均不支持独立的写超时，
请求体上传速度由 `readTimeoutMs` 与对端共同约束；请求取消统一用 `HttpCall.cancel()`。

### HttpResponseBody 增强能力

```java
HttpResponse response = HttpUtils.getResponse(url);
HttpResponseBody body = response.body();

User user = body.json(User.class);              // JSON 直接绑定实体类
JSONNode node = body.jsonNode();                // JSON 节点树，可用 xpath 提取
Map<String, Object> map = body.jsonMap();       // JSON 对象 Map

Charset cs = body.charset();                    // 按 Content-Type 解析字符集，缺省 UTF-8
body.isEmpty();                                 // 正文是否为空
body.transferTo(outputStream);                  // 流转发（不额外占内存）
body.saveTo(new File("out.json"));              // 保存到文件（自动建父目录）
body.string();                                  // 按 Content-Type 字符集解码
body.string(Charset.forName("GBK"));            // 指定字符集解码

// 流式响应体只能消费一次，重复读取抛 IllegalStateException；
// 缓冲响应体（默认）可重复读取。需要多次使用请先 body.bytes()。
response.close();
```

## 16. 拦截器、总时长与错误处理

### 16.1 拦截器

拦截器包裹**整个**发送过程（含重试、故障转移、重定向跟随），所以 `chain.proceed()` 只会被调用一次，
而内部可能真的发出多次网络请求。计时、链路追踪、签名、鉴权刷新都只需写一遍。

```java
HttpUtils.config().addInterceptor(chain -> {
    HttpRequest request = chain.request().header("X-Trace-Id", traceId());
    long start = System.nanoTime();
    HttpResponse response = chain.proceed(request);
    metrics.record(request.getMethod(), response.code(),
            (System.nanoTime() - start) / 1_000_000);
    return response;
});
```

注册顺序即执行顺序，先注册的在最外层。拦截器里**不要**读取响应体，否则调用方拿不到内容。
可以不调 `proceed()` 直接返回自造响应，实现本地缓存 / 熔断短路。

### 16.2 总时长上限

只设 connect/read 超时是不够的：一次「3 次重试 + 5 跳重定向」的调用，最坏耗时是单次超时的十几倍。

```java
HttpUtils.config().setTotalTimeoutMs(3000);          // 全局
HttpRequest.get(url).totalTimeoutMs(800).execute();  // 单次请求，优先于全局
```

总时长覆盖重试、重定向与故障转移的全部耗时，并会把每次尝试的 connect/read 超时收敛到剩余预算以内。

### 16.3 响应元信息

```java
HttpResponse response = HttpRequest.get(url).execute();
response.elapsedMs();        // 端到端耗时（含重试与重定向）
response.attempts();         // 实际发出的网络请求次数
response.endpointBaseUrl();  // 负载均衡命中的端点，未启用时为 null
response.requestUrl();       // 重定向后的最终地址
```

### 16.4 重试策略

```java
HttpUtils.config().setRetryPolicy(RetryPolicy.defaults());
// 等价于：3 次尝试、重试 IO 异常与 408/429/500/502/503/504、
//        200ms 起指数退避（上限 10s）、±20% 抖动、遵循 Retry-After
```

幂等性规则：GET/HEAD/PUT/DELETE/OPTIONS 可重试；POST/PATCH 默认**不**重试，除非
①显式 `retryNonIdempotent(true)`，②请求带 `Idempotency-Key` 头，或③失败发生在连接建立阶段
（`ConnectException` / `UnknownHostException`，请求确定没送达）。

退避带抖动，避免服务端恢复瞬间被所有客户端同时打爆。服务端返回 `Retry-After`（秒数或 HTTP-date）时优先遵循。

### 16.5 非 2xx 抛异常

默认沿用历史行为：4xx/5xx 的正文作为返回值。需要严格模式时：

```java
HttpUtils.config().setThrowOnHttpError(true);
try {
    String body = HttpUtils.get(url);
} catch (HttpStatusException e) {
    e.getStatusCode();
    e.getBodySnippet();  // 错误正文已读出，响应已关闭，无需自行关闭
}
```

### 16.6 重定向

重定向由本模块逐跳跟随（不交给引擎），因此中间跳的 `Set-Cookie` 会正常进入 CookieJar
——「登录后 302」这类流程不会再丢会话 cookie，`response.requestUrl()` 也是最终落地地址。

```java
HttpUtils.config().setMaxRedirects(5);                    // 0 表示不跟随
HttpRequest.get(url).followRedirects(false).execute();    // 单次请求关闭
```

301/302/303 会按浏览器惯例把非 GET/HEAD 改成 GET 并丢弃正文，307/308 保持方法与正文；
跨 origin 时自动剥离 `Authorization`。

### 16.7 异步与线程池

```java
Future<HttpResponse> future = HttpRequest.get(url).executeAsync();
HttpUtils.config().setExecutor(myPool);   // 内置池只有 20 线程且与 SSE 共享
```

SSE 每条订阅会长期占用一个线程，订阅数多时务必换成自己的池。

## 17. 负载均衡与服务发现

多上游端点的调度、故障转移、熔断与服务发现，对两个引擎都通过共享的发送链路自动生效。

### 17.1 快速开始

```java
import com.alianga.jkit.http.lb.*;

EndpointPool pool = EndpointPool.builder()
        .serviceName("orders")                          // 只作用于 http://orders/...
        .add("http://10.0.0.7:8080", 3)                 // 权重 3
        .add("http://10.0.0.8:8080", 1)
        .strategy(LoadBalanceStrategies.p2cLeastLoaded())
        .build();

HttpUtils.config().setEndpointPool(pool);

// 调用代码不用改：URL 的主机名写服务名即可
String body = HttpUtils.get("http://orders/api/v1/detail?id=1");
```

**服务名网关**：池带 `serviceName` 时，只有主机名等于它的请求会被改写；
写了真实主机或端口的请求原样直连——这是明确的逃生门，也让同进程访问多个上游不会互相干扰。
不带 `serviceName` 的池作用于所有请求（仅建议单上游场景）。

单次请求覆盖：`HttpRequest.get(url).endpointPool(pool).execute()`，此时不受 `serviceName` 限制。

### 17.2 调度策略

| 策略 | 说明 | 适用场景 |
| --- | --- | --- |
| `p2cLeastLoaded()`（默认） | 随机取两个候选，比「在途数 / 权重」取优者，O(1) | 通用首选，实例性能不均时最好 |
| `p2cPeakEwma()` | 代价里额外计入 EWMA 延迟 | 需要自动避开「连得上但很慢」的实例 |
| `smoothWeightedRoundRobin()` | nginx 平滑加权轮询 | 需要精确按权重分摊、流量平顺 |
| `weightedRandom()` | 加权随机，无状态 | 端点很多、QPS 很高 |
| `consistentHash()` | 一致性哈希环 + 虚拟节点 | 上游有本地缓存 / 会话状态 |

平滑加权轮询在 3:1 权重下输出 `a b a a`，而取模式轮询输出 `a a a b`——后者是突发式的。

一致性哈希需要亲和键：

```java
HttpRequest.get("http://orders/api").loadBalanceKey(userId).execute();
```

自定义策略实现 `LoadBalanceStrategy` 即可，健康统计与熔断仍由池负责。

### 17.3 熔断与半开探测

```java
EndpointPool.builder()
        .breaker(BreakerOptions.builder()
                .consecutiveFailureThreshold(5)   // 连续失败 5 次
                .failureRateThreshold(0.5)        // 或窗口失败率 ≥ 50%
                .minimumRequests(10)              // 且窗口内至少 10 个样本
                .cooldownMs(10_000)               // 冷却 10s，连续熔断按 2 倍递增
                .maxCooldownMs(120_000)
                .build())
```

两个触发条件取「或」：连续失败在低流量下可靠，滑动窗口失败率在高流量下灵敏（成功请求会不断把连续计数清零）。

**失败分类**是关键：只有连接失败、TLS 握手失败、5xx、429 才算节点故障；
业务 4xx（400/401/403/404）是请求本身的问题，不计入熔断，否则一批 404 会把整个池打穿。
读超时是否计入可配：

```java
HttpUtils.config().setCountReadTimeoutAsEndpointFailure(false);  // 单向推送/长轮询接口
```

**半开探测有限流**：冷却到期后，每个端点只放行**一个**探测请求，其余请求快速失败，
不会把流量灌向已知故障的节点。探测成功才解除熔断；探测失败重新进入更长的冷却。

运维手动摘流：

```java
pool.byOrigin("http://10.0.0.7:8080").setDisabled(true);
```

### 17.4 服务发现

`ServiceDiscovery` 返回**带权重**的端点，注册中心的权重能直接参与调度。

```java
// Nacos
ServiceDiscovery discovery = new NacosDiscovery.Builder("10.0.0.1:8848,10.0.0.2:8848")
        .namespaceId("prod")
        .groupName("ORDER_GROUP")      // 非 DEFAULT_GROUP 必须指定，否则查不到
        .clusters("BJ")                // 只取本机房实例
        .auth("nacos", "nacos")        // 自动登录换 accessToken 并刷新
        .endpointScheme("http")
        .build();

EndpointPool pool = EndpointPool.builder()
        .serviceName("order-service")
        .discovery(discovery)
        .refreshIntervalMs(10_000)     // 后台刷新周期，0 表示只在构造时拉一次
        .build();

// DNS（适合 K8s headless service）
new ServiceDiscovery.Dns("http", 8080);

// 静态兜底
new ServiceDiscovery.Fallback(discovery, new ServiceDiscovery.Static("http://10.0.0.9:8080"));
```

刷新采用**增量合并**：同基址端点继承原有健康状态（熔断、在途、统计），
所以扩缩容不会把熔断信息清零，刚摘掉的坏节点也不会立刻恢复接流。
发现失败或返回空列表时**保留上一次快照**（fail-static），绝不清空端点池。

注册中心型发现（Nacos/DNS）必须配 `serviceName`，否则 `build()` 会立即报错而不是等到第一次刷新
才抛出让人费解的异常；`ServiceDiscovery.Static` 自带端点，不需要。

用完记得 `pool.close()` 停掉后台刷新线程。

`NacosDiscovery` 的 HTTP 调用直接走本模块的 `HttpUtils`，因此自动获得引擎自适应
（JDK 8 → `HttpURLConnection`，JDK 11+ → `java.net.http.HttpClient`）、连接复用、内容解压
与统一超时语义，不需要自己再写一遍 `HttpURLConnection`。

### 17.5 绕过负载均衡

「基础设施自身」的调用不该被负载均衡改写——注册中心拉取、健康探测、管理接口等：

```java
HttpRequest.get("http://10.0.0.1:8848/nacos/v1/ns/instance/list").bypassLoadBalance(true).execute();
```

这一点对未设 `serviceName` 的全局池尤其关键：那种池作用于**所有**请求，
服务发现自己的 HTTP 请求若不绕过，就会被改写到业务端点上去。`NacosDiscovery` 内部已经加了这个标记。

### 17.6 手动配置多个域名 / IP

不接注册中心时直接列端点即可，域名、IP+端口、甚至不同协议可以混用：

```java
// 多域名（如主备两个接入点）
EndpointPool pool = EndpointPool.builder()
        .serviceName("pay")
        .add("https://pay-a.example.com", 3)
        .add("https://pay-b.example.com", 1)
        .build();
HttpUtils.config().setEndpointPool(pool);
HttpUtils.postJson("http://pay/v1/order", payload);   // 主机名写服务名

// 多 IP+端口
EndpointPool.builder().serviceName("orders")
        .add("http://10.0.0.7:8080").add("http://10.0.0.8:8080").build();
```

不想引入「服务名」这层间接的话，两种更直接的方式：

```java
// 方式一：不设 serviceName，池对所有请求生效，URL 里的主机名写哪个都会被改写
EndpointPool any = EndpointPool.builder()
        .add("https://pay-a.example.com").add("https://pay-b.example.com").build();
HttpUtils.config().setEndpointPool(any);
HttpUtils.get("https://pay-a.example.com/v1/ping");   // 会在 a/b 之间轮换

// 方式二：只给指定请求挂池，不影响全局
HttpRequest.get("https://pay-a.example.com/v1/ping").endpointPool(any).execute();
```

端点列表构造后不可变。要在运行期增删，用一个返回当前列表的 `ServiceDiscovery`
配合 `pool.refreshNow()`（见 17.4），临时摘流用 `pool.byOrigin(...).setDisabled(true)`。

### 17.7 Host 头与 HTTPS

改写 origin 后 `Host` 头怎么给，取决于端点形态，池会自动判断：

| 端点形态 | `Host` 头 | 原因 |
| --- | --- | --- |
| IP，如 `http://10.0.0.7:8080` | 原始主机名（如 `orders`） | 上游按域名做虚拟主机 / 网关路由时需要它 |
| 域名，如 `https://pay-a.example.com` | 端点自己的域名 | 硬写回逻辑服务名会让上游路由到错误后端 |

**IP 端点还需要加启动参数**，否则 JDK 会丢弃应用设置的 `Host`：

- JDK 8：`-Dsun.net.http.allowRestrictedHeaders=true`
- JDK 11+：`-Djdk.httpclient.allowRestrictedHeaders=host`

不需要这个行为时 `preserveHostHeader(false)` 关掉。

**HTTPS 端点**：用域名端点时证书校验天然成立（URL 里就是该域名，SNI 与证书一致），
这也是多接入点场景推荐用域名而不是 IP 的原因。若必须用 IP + HTTPS，证书里通常没有 IP，
需要显式 `ignoreSsl(true)`（本模块默认值即忽略校验）或自备 `SSLContext`。

**HTTPS + IP 端点**：证书里是域名而不是 IP，严格校验下握手会失败。三个选择：
①端点用域名而不是 IP；②为该请求显式 `ignoreSsl(true)`（本模块默认值就是忽略校验）；
③自备 `SSLContext`。请按环境明确取舍，不要以为拿 Nacos 的 IP 列表配 HTTPS 能直接跑通。

### 17.8 可观测性

```java
EndpointPool.builder().listener(new EndpointPoolListener() {
    @Override public void onBreakerOpen(EndpointPool p, Endpoint e, long cooldownMs) {
        alarm.fire("endpoint down: " + e.getBaseUrl());
    }
    @Override public void onFailover(EndpointPool p, Endpoint failed, Endpoint next, String cause) { }
    @Override public void onEndpointsChanged(EndpointPool p, int added, int removed, int total) { }
    @Override public void onDiscoveryFailure(EndpointPool p, Exception error) { }
    @Override public void onAllEndpointsDown(EndpointPool p, Endpoint probe) { }
})
```

端点自身也可直接读指标：`getInFlight()`、`getEwmaLatencyMs()`、`getWindowFailures()`、
`getWindowTotal()`、`getConsecutiveFailures()`、`getBreakerTrips()`、`getCooldownUntilMs()`。

### 17.9 与重试的关系

故障转移与重试共用一套尝试预算：尝试次数取 `max(重试策略次数, min(端点数, 5))`，
并且整体受总时长上限约束，不会因为端点多而把一次调用无限拖长。

## 18. 注意事项

- 忽略 SSL 只适合开发或抓包调试，不要在对公网生产流量中默认信任全部证书。
  `HttpRequest.ignoreSsl(...)` 一旦显式调用就不会被全局开关覆盖。
- 大文件下载走流式写盘；`get` / `post` 会把正文缓冲到内存，不适合超大响应。
- 下载强制 `Accept-Encoding: identity`：Range 偏移与 Content-Length 都以未编码字节计，
  否则续传会把解码后的字节追加到编码偏移上，得到损坏文件。
- 下载会校验状态码与长度，并默认先写临时文件再原子替换，失败不会留下截断的目标文件。
- 上传超过 1MB 的文件会拼装到临时文件后流式发送，避免内存峰值达到文件的 2 倍。
- CookieJar 会拒绝与请求主机无关的 `Domain`，以及 `.co.uk` 这类公共后缀，并有条数上限（默认 3000）。
- 发布 JAR 已设置 `Multi-Release: true`，JDK 11+ 会加载 `META-INF/versions/11` 中的 HttpClient 实现。
