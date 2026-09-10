# HTTP Module Guide

`com.alianga.jkit.HttpUtils` is a pure-JDK HTTP client with zero third-party dependencies, migrated from the OkHttp version in ZmlTools. Its capabilities match the original `HttpUtils`: GET/POST, JSON body, multipart upload, file download, cookies, proxies, fake IP, and ignoring HTTPS certificates.

The underlying implementation is chosen automatically based on the runtime JDK:

| Runtime | Implementation |
| --- | --- |
| JDK 8 | `java.net.HttpURLConnection` (`UrlConnectionHttpEngine`) |
| JDK 9+ with `java.net.http.HttpClient` present | `java.net.http.HttpClient` (`JdkHttpClientEngine`, located in `META-INF/versions/11` of the multi-release JAR) |
| If the above class fails to load | Falls back to `HttpURLConnection` |

`java.net.http.HttpClient` is the standard API since JDK 11. JDK 9/10 does not have that package and will automatically use `HttpURLConnection`. You can force the choice with the system property `jkit.http.engine=url` or `jdk`.

The detailed types live in `com.alianga.jkit.http`: `HttpResponse`, `HttpResponseBody`, `HttpRequest`, `HttpCookieJar`, `UploadInfo`, `HttpCallBack`, etc. The original OkHttp `Response` / `ResponseBody` / `CookieJar` / `Call` have been replaced by the JDK types above.

## 1. GET

```java
import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.HttpResponse;

// Simple GET, returns the body
String html = HttpUtils.get("https://example.com");

// Query parameters (null values are skipped; arrays / Lists expand to repeated same-name values)
Map<String, Object> params = new LinkedHashMap<String, Object>();
params.put("name", "Tom");
params.put("ids", Arrays.asList(1, 2));
String json = HttpUtils.get("https://api.example.com/search", params);

// Custom headers (when non-empty, they replace the default User-Agent)
Map<String, String> headers = new HashMap<String, String>();
headers.put("Authorization", "Bearer token");
String body = HttpUtils.get(url, params, headers);

// Headers only, no query
HttpResponse onlyHeader = HttpUtils.getResponse(url, headers);
String same = HttpUtils.get(url, null, headers);
int code = response.code();
boolean ok = response.isSuccessful();       // 200–299
String contentType = response.header("Content-Type");
String text = response.body().string();
byte[] bytes = response.body().bytes();
response.close();
```

`getRequestParamString(params)` only encodes a Map into `a=1&b=2`; it does not send a request. Chinese characters and spaces are encoded as `application/x-www-form-urlencoded` using UTF-8.

## 2. POST Form

```java
Map<String, Object> form = new HashMap<String, Object>();
form.put("username", "tom");
form.put("password", "secret");

String text = HttpUtils.postForm(url, form);          // recommended
String text2 = HttpUtils.getStringFromPost(url, form); // legacy name
String withHeader = HttpUtils.postForm(url, form, headers);
byte[] bytes = HttpUtils.getBytesFromPost(url, form);
InputStream in = HttpUtils.getInputStreamFromPost(url, form);

HttpUtils.post(url);                                  // empty body
HttpResponse onlyHeader = HttpUtils.getResponseFromPost(url, headers);

// With custom headers
HttpResponse response = HttpUtils.getResponseFromPost(url, form, headers);
```

The form Content-Type is `application/x-www-form-urlencoded`, and fields are URL-encoded. `null` values are written as the string `"null"` (consistent with `value + ""` in the original OkHttp version).

## 3. POST JSON / Arbitrary Body

```java
// Objects are serialized via JSON.toJsonString first
Map<String, Object> payload = new LinkedHashMap<String, Object>();
payload.put("name", "Tom");
payload.put("age", 18);
String resp = HttpUtils.postJson(url, payload);           // recommended
String resp0 = HttpUtils.sendRequestBody(url, payload);   // legacy name

// Strings are sent as-is (default application/json; charset=utf-8)
String resp2 = HttpUtils.sendRequestBody(url, "{\"ok\":true}");

// Specify Content-Type (XML, plain, etc.)
String xml = HttpUtils.sendRequestBody(url, "<id>1</id>", "application/xml");

// Pass headers at the same time
String resp3 = HttpUtils.sendRequestBody(url, json, headers, "application/json");
```

The global default media type can be changed:

```java
HttpUtils.defaultMediaType = "application/json; charset=utf-8";
```

`sendRequestBody(url, Object, ...)` always serializes the object to JSON; `sendRequestBody(url, String, ...)` treats the string as the body without encoding it again.

## 4. File Upload

```java
import com.alianga.jkit.http.UploadInfo;
import com.alianga.jkit.http.HttpResponse;

UploadInfo info = new UploadInfo("file", "/tmp/report.pdf", "report.pdf");
info.setMediaType("application/pdf");     // optional; when omitted it is detected from the file

Map<String, String> extra = new HashMap<String, String>();
extra.put("bizId", "1001");

HttpResponse response = HttpUtils.upload(url, info, extra, headers);
String result = response.body().string();
```

The implementation is `multipart/form-data`. The `key` of `UploadInfo` defaults to `file`; when `fileName` is empty, the local file name is used. The file must exist and be a regular file; CR/LF in field names and file names is filtered to prevent multipart header injection. Currently `upload` still buffers the multipart request body in memory; for large files, prefer `putFile`. A streaming multipart API may be added later.

## 5. File Download (download)

The legacy names `getFileFromHttpDataBySyn/Asyn` still work but are marked `@Deprecated`; use `download` / `downloadAsync` instead.

```java
import com.alianga.jkit.http.HttpCallBack;
import java.io.File;
import java.util.concurrent.Future;

// Synchronous: blocks until writing finishes, returns the absolute path
String path = HttpUtils.download(fileUrl, "data.zip", "/tmp/downloads");
String path2 = HttpUtils.download(fileUrl, new File("/tmp/data.zip"));

// Resumable download: sends Range when local content exists; appends when the server returns 206
HttpUtils.download(fileUrl, new File("/tmp/data.zip"), true);

// Progress: onProcess(bytes written, total size or -1 if unknown)
HttpUtils.download(fileUrl, dest, false, new HttpCallBack<String>() {
    public void onProcess(long saved, long total) { }
    public void onFailure(HttpCall call, IOException e) { }
    public void onResponse(HttpCall call, HttpResponse response, String result) { }
});

// Async, returns a Future (uses the global read timeout, no longer 1 second)
Future<String> future = HttpUtils.downloadAsync(fileUrl, dest);
String saved = future.get();
```

For compatibility with the old behavior, synchronous downloads retry at most 3 times on `SocketTimeoutException`. Normal requests are not retried by default; configure `RetryPolicy` if you need uniform retries. Large files are always streamed to disk and never materialized as a whole `byte[]` response.

Do not use `get()` for very large GET responses (it buffers); use instead:

```java
HttpResponse stream = HttpUtils.openStream(url);
try (InputStream in = stream.body().byteStream()) {
    // Process while reading
} finally {
    stream.close();
}
```

Resolving file names:

```java
String name = HttpUtils.getFileName(fileUrl);          // sends HEAD
String name2 = HttpUtils.getFileName(response);        // from response headers / URL

// When you only have a Content-Disposition header value, use the parsing entry point directly
String name3 = HttpUtils.parseFileName(contentDisposition, "fallback.bin");
```

Supports `Content-Disposition: filename=` and RFC 5987 `filename*=UTF-8''...`.

`HttpUtils.parseFileName(contentDisposition, defaultName)` is the library's single implementation for parsing this header;
`getFileName(HttpResponse)` and `FileUtils.getFileNameFromHttp(Map)` both delegate to it.
When no file name can be parsed, it returns `defaultName` (passing `null` is normalized to an empty string).

`FileUtils.getFileNameFromHttp(String url)` now delegates to `HttpUtils.getFileName(url)` (changed to a HEAD request),
falling back to extracting from the URL when the response headers have none. `FileUtils.getFileNameFromHttp(Map)` looks up response header names **case-insensitively**,
adapting to any casing the server returns.

### Atomic Download + SHA-256 Verification

Writes to a temporary file first, then atomically replaces the target on success: the target is either complete or keeps its old content; the `.part` temp file is cleaned up automatically on failure.

```java
// Download and verify SHA-256 (hex, case-insensitive); on mismatch throws IOException without replacing the old file
String path = HttpUtils.downloadAtomic(fileUrl, new File("/tmp/data.zip"), expectedSha256);

// With progress callback
HttpUtils.downloadAtomic(fileUrl, dest, expectedSha256, new HttpCallBack<String>() {
    public void onProcess(long saved, long total) { }
    public void onFailure(HttpCall call, IOException e) { }
    public void onResponse(HttpCall call, HttpResponse response, String result) { }
});

// No hash check; only atomic replacement is guaranteed
HttpUtils.downloadAtomic(fileUrl, dest);
```

Suitable for scenarios where "no half-written file is acceptable", such as periodically pulling configs, model files, or upgrade packages. To compute the SHA-256 of a local file:

```java
EncryptUtils.sha256(new File("/tmp/data.zip"))   // returns lowercase hex
```

## 6. Cookies

An in-memory `CookieJarImpl` is used by default. Subsequent requests in the same JVM automatically carry the saved cookies.

```java
HttpUtils.setCookieJar(new CookieJarImpl());   // swap in a clean store
String cookieHeader = HttpUtils.getCookieValue(response);  // sid=abc;
```

### Per-Request Cookies

Bypasses the global CookieJar and is sent directly with the request (takes precedence over the global store):

```java
HttpResponse response = HttpUtils.execute(HttpRequest.get(url)
        .cookie("token", "abc")
        .cookies(mapOf("trace", "1")));
```

### CookieJar Persistence and Import/Export

`CookieJarImpl` is a thread-safe in-memory store that filters by Domain / Path / Secure per RFC 6265 semantics,
and automatically purges cookies expired via Max-Age / Expires:

```java
CookieJarImpl jar = new CookieJarImpl();
HttpUtils.setCookieJar(jar);

// JSON persistence (preserves HttpOnly / Secure / expiry, restoring login state after restart)
jar.saveTo(new File("cookies.json"));
new CookieJarImpl().loadFrom(new File("cookies.json"));

// Netscape cookies.txt format (usable by browsers / curl; does not include the HttpOnly field)
jar.exportNetscape(new File("cookies.txt"));
new CookieJarImpl().importNetscape(new File("cookies.txt"));

// Management
jar.remove("sid");                     // remove by name
jar.remove("sid", "example.com");     // remove by name + domain (domain comparison ignores leading dots and case)
jar.clear();
jar.size();
```

Implement `HttpCookieJar` for a custom store to plug in external storage such as Redis.

## 7. PUT / DELETE / PATCH / Authentication

```java
HttpUtils.putJson(url, payload);
HttpUtils.putJson(url, payload, headers);
HttpUtils.put(url, "<xml/>", "application/xml");
HttpUtils.put(url, body, headers, "application/xml");
HttpUtils.put(url, headers);                          // empty body
HttpUtils.putFile(url, new File("/tmp/big.bin"), "application/octet-stream");
HttpUtils.putFile(url, file, "application/octet-stream", headers);
HttpUtils.patchJson(url, payload, headers);
HttpUtils.patch(url, body, headers, "application/xml");
HttpUtils.delete(url);
HttpUtils.delete(url, headers);
HttpUtils.delete(url, "{\"id\":1}");                  // with body
HttpUtils.deleteJson(url, payload, headers);

headers.put("Authorization", HttpUtils.basicAuth("user", "pass"));
headers.put("Authorization", HttpUtils.bearer("token"));
```

For custom methods, use `HttpUtils.execute(HttpRequest.put(url).body(...))`.

## 8. Proxy, Fake IP, HTTPS, HTTP/2

### HTTPS Security Notes

For compatibility with the legacy API, `supportHttps()` and `setIgnoreSsl(true)` can still enable "trust all certificates" mode, but this disables certificate-chain and hostname verification and is only suitable for local testing, packet capture, and temporary development environments.

In production, it is recommended to call explicitly:

```java
HttpUtils.useSecureSsl();                 // Use the JDK default certificate chain and hostname verification
// Or configure a dedicated SSLContext for self-signed certificates:
HttpUtils.setSsl(customSslContext, null);
```

Even better, avoid global state and configure at the request level:

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

`requireSuccessful()` converts non-2xx responses into `HttpStatusException`, which carries the status code, request URL, and the original response object. The original behavior remains compatible: plain `getResponse` does not throw automatically on 4xx/5xx.

```java
// HTTP / SOCKS proxy
HttpUtils.setHttpProxy("127.0.0.1", 7890);
HttpUtils.setHttpProxy("127.0.0.1", 7890, "user", "pass"); // Proxy-Authorization
HttpUtils.setSocksProxy("127.0.0.1", 1080);
HttpUtils.setProxy(null);

HttpUtils.fakeIp = true;

// Local troubleshooting: prints a request summary / copyable curl before sending. It leaks sensitive headers such as Authorization; do not enable it in production.
// debug prints method, URL, headers, and a body preview (binary prints only the byte count; long bodies are truncated); printCurl prints the equivalent curl.
HttpUtils.debug = true;
HttpUtils.printCurl = true;

// Legacy compatibility: ignore certificates (testing only)
HttpUtils.supportHttps();
// Recommended for production: use the default certificate chain and hostname verification
HttpUtils.useSecureSsl();
// Legacy names still work
HttpUtils.verifySsl();
HttpUtils.ignoreSNI();

// HTTP/2: negotiated preferentially by default on JDK 11+ (plain http:// remains 1.1)
HttpUtils.setHttp2(true);
```

Connection reuse: the JDK 11+ `HttpClient` pools connections itself; `HttpURLConnection` relies on JVM keep-alive (`http.keepAlive` / `http.maxConnections`).

## 9. Retries, Timeouts, and Memory

No automatic retries by default. When retries are needed, configure them explicitly, and note that only idempotent requests are allowed to retry on IO errors by default:

```java
HttpUtils.config().setRetryPolicy(RetryPolicy.builder()
        .maxAttempts(3)
        .initialBackoffMs(200)
        .maxBackoffMs(3000)
        .retryOnIOException()
        .retryStatus(408, 429, 500, 502, 503, 504)
        .build());
```

POST/PATCH are not retried automatically on exceptions by default; if your business can guarantee idempotency, call `retryNonIdempotent(true)` explicitly. The retry policy is global configuration; per-instance client isolation will be provided in a future API.



```java
HttpUtils.setConnectTimeout(10_000);
HttpUtils.setReadTimeout(30_000);
HttpUtils.setMaxBufferBytes(16L * 1024 * 1024);  // buffer cap for get/post; exceeding it throws
HttpUtils.config().setDownloadBufferSize(64 * 1024);
```

`get()` / `postJson()` put the body in memory, so the default limit is 64MB. Downloads, `openStream`, and SSE stream the data and are not subject to this limit.

## 10. SSE

The read timeout is set to unlimited for SSE. The spec fields `data` / `event` / `id` / `retry` and comment lines are all supported.
Many LLM APIs (such as SenseNova Chat Completions) emit one complete `data: {json}` per line without blank lines in between; the parser treats such complete JSON / `[DONE]` lines as an event immediately, and the buffer is also flushed when the stream ends.

```java
HttpCall call = HttpUtils.sse(url, new SseListener() {
    public void onEvent(SseEvent event) {
        System.out.println(event.getEvent() + " " + event.getData());
    }
    public void onError(IOException e) { e.printStackTrace(); }
});
HttpUtils.sse(url, headers, listener);                // GET + headers
HttpUtils.sse(url, params, headers, listener);        // GET + query
HttpUtils.sseJson(url, payload, headers, listener);   // POST JSON body
HttpUtils.sse(url, headers, rawBody, "application/json", listener);

// Custom HttpRequest (timeout, proxy, body, etc. follow the request object)
HttpRequest request = HttpRequest.post(url)
        .header("Authorization", "Bearer tok")
        .contentType("application/json")
        .body("{\"stream\":true}");
HttpUtils.sse(request, listener);
HttpUtils.sseMerge(request, SseMergeFormat.OPENAI, mergeListener);
HttpUtils.sseReconnect(request, options, listener);

// A curl copied from the browser can be turned into SSE / auto-merge directly
CurlRequest parsed = HttpUtils.parseCurl(curl);
HttpUtils.sse(parsed, listener);                      // or parsed.toHttpRequest()
HttpUtils.sseMerge(parsed, SseMergeFormat.OPENAI, mergeListener);
HttpUtils.sseMerge(HttpUtils.curlToRequest(curl), SseMergeFormat.AUTO, mergeListener);
```

### Auto-Merging

`sseMerge` / `sseMergeJson` assemble deltas into content, reasoning chains, and tool-call arguments according to each vendor's protocol. Each `onDelta` provides the accumulated result and the current delta:

```java
HttpUtils.sseMergeJson(url, payload, headers, SseMergeFormat.OPENAI, new SseMergeListener() {
    public void onDelta(SseMergeResult snap) {
        System.out.print(snap.getThinkingDelta());  // reasoning delta (may arrive before content)
        System.out.print(snap.getContentDelta());   // reply delta
        // snap.getContent() / getThinking() / getToolCall() are the fully merged text so far
    }
    public void onComplete(SseMergeResult result) {
        System.out.println("\nDONE: " + result.getContent());
    }
});
```

| Format | Applicable to | Delta path | End marker |
| --- | --- | --- | --- |
| `AUTO` | Preferred when the vendor is unknown | Detected by event name / JSON structure | Follows the detection result |
| `OPENAI` | Chat Completions and compatible layers (DeepSeek / GLM / Qwen / Kimi / OpenRouter / **SenseNova**) | `choices[0].delta.content`; reasoning in `reasoning` / `reasoning_content` | `[DONE]`, or a non-empty `finish_reason` (an empty string does not end the stream) |
| `OPENAI_RESPONSES` | OpenAI `/v1/responses` | `response.output_text.delta` | `response.completed` |
| `CLAUDE` | Anthropic Messages | `text_delta` / `thinking_delta` | `message_stop` (not `content_block_stop`) |
| `GEMINI` | `streamGenerateContent?alt=sse` | `parts[].text`; `thought:true` goes into thinking | non-empty `finishReason` |
| `DASHSCOPE` | Tongyi native (`X-DashScope-SSE: enable`) | `output.choices[0].message.content` | non-empty `finish_reason` |
| `ERNIE` | ERNIE Bot / Qianfan native | top-level `result` | `is_end: true` |
| `OLLAMA_GENERATE` / `OLLAMA_CHAT` | Ollama | `response` / `message.content` | `done: true` |
| `RAW` | Unknown protocols | concatenates raw `data` | — |
| `jsonPath("$.a.b")` | Custom | the specified JSON path | — |

In OpenAI-compatible streams, reasoning models often push a long stretch of `delta.reasoning` first, during which `getContent()` may still be empty and the reasoning chain is in `getThinking()`. There may also be a final `choices: []` carrying only usage, followed by `data: [DONE]`.

`call.cancel()` stops reading.

### Auto-Reconnect (Last-Event-ID Resumption)

`HttpUtils.sse` ends as soon as the connection breaks; use `sseReconnect` when you need reconnection. It automatically carries the
`Last-Event-ID` header on reconnect so the server can resume from the breakpoint:

```java
SseReconnectOptions options = SseReconnectOptions.builder()
        .maxRetries(-1)              // -1 means unlimited (default)
        .initialBackoffMs(1000)      // initial backoff
        .maxBackoffMs(30_000)        // backoff cap
        .multiplier(2.0)             // exponential multiplier
        .reconnectOnStreamEnd(true)  // reconnect even when the server closes the stream normally (can be disabled for one-shot LLM output)
        .honorServerRetry(true)      // honor the server's retry: field
        .build();

HttpCall call = HttpUtils.sseReconnect(url, options, new SseListener() {
    public void onEvent(SseEvent event) { }
    public void onReconnect(int attempt, long delayMs, Throwable cause) {
        // the attempt-th reconnection, waiting delayMs milliseconds
    }
    public void onError(IOException e) { }
    public void onClosed() { }   // called once after cancellation or when retries are exhausted
});
call.cancel();   // stop reconnecting
```

## 11. curl Parsing and Execution

Convert a copied curl command into an `HttpRequest` and send it directly:

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

Common options such as `-X/-H/-d/--data-raw/--data-urlencode/--json/-u/-G/-k/-x/-b/-A/-e/-F/-T/--url` are supported,
and short option clusters (`-kLs`, `-XPOST`) are expanded. `-k` applies only to that single request and does not change the global `HttpUtils.setIgnoreSsl`.

After parsing you can also modify the headers/body and then call `parsed.execute()`,
or hand `parsed.toHttpRequest()` / `parsed` to `HttpUtils.sse` / `sseMerge` / `sseReconnect`.

When you need to convert curl into source code for other languages such as OkHttp / fetch / requests, use the standalone module
`com.alianga:jkit-curl-codegen` (see [jkit-curl-codegen/README.md](https://github.com/zhengmingliang/jkit/blob/develop/jkit-curl-codegen/README.md)).
jkit itself only parses and executes: the `ParsedCurlRequest` produced by `parseModel` is the generator's input.

Besides language SDKs, this module also provides interop formats aligned with curlconverter: `http` (raw HTTP message),
`har` (HAR 1.2 JSON), `httpie` (HTTPie CLI), plus `ruby-httparty` / `php-guzzle` /
`lua` (luasocket `socket.http`).

## 12. WebSocket (JDK 11+)

```java
WebSocketSession ws = HttpUtils.webSocket("wss://example.com/chat", new WebSocketListener() {
    public void onOpen() { }
    public void onText(String text, boolean last) { }
    public void onClose(int code, String reason) { }
});
ws.sendText("hello");
ws.close();
```

On JDK 8 or when `java.net.http` is not loaded, it throws an `IOException` indicating that JDK 11+ is required.

## 13. Engine Selection and Extension

```java
String engine = HttpUtils.getEngineName();
// "jdk-http-client" or "http-url-connection"

// Force HttpURLConnection (for testing or troubleshooting)
HttpUtils.setEngine(HttpEngines.urlConnection());

// Restore automatic selection
HttpUtils.setEngine(null);
```

JVM arguments:

```text
-Djkit.http.engine=url
-Djkit.http.engine=jdk
```

Implement `HttpEngine#execute(HttpRequest)` yourself and inject it via `HttpUtils.setEngine` to swap in a custom transport with connection pooling, logging interception, etc.

## 14. Differences from the Original OkHttp Version

- Return types changed from `okhttp3.Response` / `ResponseBody` to `HttpResponse` / `HttpResponseBody`, with common methods (`code()`, `body().string()`, `bytes()`, `byteStream()`, `header()`) aligned.
- `getOkHttpClient()` changed to `getHttpEngine()`; OkHttp types are no longer exposed.
- `setFakeIpHeader` now writes into a `Map<String, String>`.
- `defaultMediaType` changed from the OkHttp `MediaType` to a string.
- `Cookies` (Servlet-dependent) was not migrated; use `HttpCookieJar` for client-side cookies.
- 4xx/5xx **do not throw**; the response body is still readable (consistent with OkHttp). The 404 `FileNotFoundException` from `HttpURLConnection` is absorbed inside the engine.
- The transport declares `gzip, deflate, br` by default and automatically decompresses these three response encodings. The `br` decoder is ported from Google Brotli (MIT). Unsupported algorithms (such as `zstd`) never appear in the outgoing `Accept-Encoding`; if a response still carries an unknown `Content-Encoding`, `decodeContentEncoding` returns the byte stream as-is for the caller to decode. You can extend this with `ContentEncodings.register(...)`.
- For downloads use `download` / `downloadAsync` / `downloadAtomic`; the old method names remain as deprecated aliases.
- HTTP/2 is preferred by default on JDK 11+; disable it with `HttpUtils.setHttp2(false)`.

## 15. Request Model and Response Body Enhancements

### HttpRequest Enhancements

```java
HttpRequest request = HttpRequest.get(url)
        .query("name", "Tom")            // structured query parameter, URL-encoded automatically when sent
        .query("ids", Arrays.asList(1, 2)) // collection values expand to multiple same-name parameters
        .cookie("token", "abc")            // per-request cookie
        .userAgent("jkit")
        .accept("application/json")
        .acceptLanguage("zh-CN")
        .referer("https://example.com")
        .bearerToken("tok")                 // Authorization: Bearer tok
        .ifNoneMatch("W/\"v1\"")           // ETag conditional request; returns 304 if unchanged
        .ifModifiedSince("Wed, 21 Oct 2026 07:28:00 GMT")
        .range(1024, 2048)                  // Range: bytes=1024-2048 (also rangeFrom / rangeSuffix)
        .tag("order-sync")                  // request tag for logging and tracing
        .expectContinue(true);              // Expect: 100-continue (engines without support ignore it)

request.post(url).body("text content");           // UTF-8
request.post(url).body("text content", Charset.forName("GBK")); // specify the charset
Object tag = request.getTag();

HttpResponse response = HttpUtils.execute(request); // send
```

Note: neither the JDK's `HttpURLConnection` nor `HttpClient` supports a separate write timeout;
request-body upload speed is bounded jointly by `readTimeoutMs` and the peer. Use `HttpCall.cancel()` uniformly for request cancellation.

### HttpResponseBody Enhancements

```java
HttpResponse response = HttpUtils.getResponse(url);
HttpResponseBody body = response.body();

User user = body.json(User.class);              // bind JSON directly to an entity class
JSONNode node = body.jsonNode();                // JSON node tree, extractable via xpath
Map<String, Object> map = body.jsonMap();       // JSON object as a Map

Charset cs = body.charset();                    // charset parsed from Content-Type, defaults to UTF-8
body.isEmpty();                                 // whether the body is empty
body.transferTo(outputStream);                  // stream forwarding (no extra memory)
body.saveTo(new File("out.json"));              // save to a file (parent directories created automatically)
body.string();                                  // decode with the Content-Type charset
body.string(Charset.forName("GBK"));            // decode with the specified charset

// A streaming response body can be consumed only once; reading it again throws IllegalStateException;
// a buffered response body (the default) can be read repeatedly. Call body.bytes() first if you need it multiple times.
response.close();
```

## 16. Interceptors, Total Timeout, and Error Handling

### 16.1 Interceptors

Interceptors wrap the **entire** send process (including retries, failover, and redirect following), so `chain.proceed()` is called only once,
even though multiple network requests may actually be made inside. Timing, tracing, signing, and auth refresh each need to be written only once.

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

Registration order is execution order; the first registered is outermost. **Do not** read the response body inside an interceptor, or the caller gets nothing.
You may skip `proceed()` and return a self-made response directly to implement local caching / circuit-breaking short-circuits.

### 16.2 Total Duration Cap

Setting only connect/read timeouts is not enough: a call with "3 retries + 5 redirect hops" can take more than ten times a single timeout in the worst case.

```java
HttpUtils.config().setTotalTimeoutMs(3000);          // global
HttpRequest.get(url).totalTimeoutMs(800).execute();  // per-request, takes precedence over the global setting
```

The total duration covers all time spent on retries, redirects, and failover, and shrinks each attempt's connect/read timeouts to fit the remaining budget.

### 16.3 Response Metadata

```java
HttpResponse response = HttpRequest.get(url).execute();
response.elapsedMs();        // end-to-end elapsed time (including retries and redirects)
response.attempts();         // number of network requests actually made
response.endpointBaseUrl();  // the endpoint hit by load balancing, null when not enabled
response.requestUrl();       // the final URL after redirects
```

### 16.4 Retry Policy

```java
HttpUtils.config().setRetryPolicy(RetryPolicy.defaults());
// Equivalent to: 3 attempts, retrying IO exceptions and 408/429/500/502/503/504,
//        exponential backoff starting at 200ms (capped at 10s), ±20% jitter, honoring Retry-After
```

Idempotency rules: GET/HEAD/PUT/DELETE/OPTIONS are retryable; POST/PATCH are **not** retried by default unless
① `retryNonIdempotent(true)` is set explicitly, ② the request carries an `Idempotency-Key` header, or ③ the failure happened during connection establishment
(`ConnectException` / `UnknownHostException`, where the request definitely never arrived).

Backoff includes jitter to prevent all clients from hammering the server at the same moment it recovers. When the server returns `Retry-After` (seconds or an HTTP-date), it is honored first.

### 16.5 Throwing on Non-2xx

The historical behavior remains the default: the body of a 4xx/5xx response is returned as the value. For strict mode:

```java
HttpUtils.config().setThrowOnHttpError(true);
try {
    String body = HttpUtils.get(url);
} catch (HttpStatusException e) {
    e.getStatusCode();
    e.getBodySnippet();  // the error body has been read out and the response closed; no need to close it yourself
}
```

### 16.6 Redirects

Redirects are followed hop by hop by this module (not delegated to the engine), so `Set-Cookie` from intermediate hops enters the CookieJar normally
— flows like "302 after login" no longer lose the session cookie, and `response.requestUrl()` is the final landing URL.

```java
HttpUtils.config().setMaxRedirects(5);                    // 0 means do not follow
HttpRequest.get(url).followRedirects(false).execute();    // disable for a single request
```

301/302/303 rewrite non-GET/HEAD to GET and drop the body per browser convention, while 307/308 keep the method and body;
`Authorization` is stripped automatically when crossing origins.

### 16.7 Async and Thread Pools

```java
Future<HttpResponse> future = HttpRequest.get(url).executeAsync();
HttpUtils.config().setExecutor(myPool);   // the built-in pool has only 20 threads and is shared with SSE
```

Each SSE subscription holds a thread for a long time; be sure to switch to your own pool when you have many subscriptions.

## 17. Load Balancing and Service Discovery

Scheduling, failover, circuit breaking, and service discovery across multiple upstream endpoints take effect automatically for both engines through the shared send pipeline.

### 17.1 Quick Start

```java
import com.alianga.jkit.http.lb.*;

EndpointPool pool = EndpointPool.builder()
        .serviceName("orders")                          // applies only to http://orders/...
        .add("http://10.0.0.7:8080", 3)                 // weight 3
        .add("http://10.0.0.8:8080", 1)
        .strategy(LoadBalanceStrategies.p2cLeastLoaded())
        .build();

HttpUtils.config().setEndpointPool(pool);

// No changes needed in calling code: just use the service name as the URL host
String body = HttpUtils.get("http://orders/api/v1/detail?id=1");
```

**Service-name gateway**: when a pool has a `serviceName`, only requests whose host equals it are rewritten;
requests with a real host or port connect directly as-is — an explicit escape hatch that also keeps multiple upstreams in the same process from interfering with each other.
A pool without `serviceName` applies to all requests (recommended only for single-upstream scenarios).

Per-request override: `HttpRequest.get(url).endpointPool(pool).execute()`, which is not subject to the `serviceName` restriction.

### 17.2 Scheduling Strategies

| Strategy | Description | When to use |
| --- | --- | --- |
| `p2cLeastLoaded()` (default) | Picks two random candidates and chooses the better by "in-flight / weight", O(1) | General first choice; best when instance performance varies |
| `p2cPeakEwma()` | Adds EWMA latency into the cost | Automatically avoids instances that are "reachable but slow" |
| `smoothWeightedRoundRobin()` | nginx-style smooth weighted round robin | Precise weight-based distribution with smooth traffic |
| `weightedRandom()` | Weighted random, stateless | Many endpoints, very high QPS |
| `consistentHash()` | Consistent hash ring + virtual nodes | Upstreams with local caches / session state |

Smooth weighted round robin with 3:1 weights outputs `a b a a`, whereas modulo-based round robin outputs `a a a b` — the latter is bursty.

Consistent hashing requires an affinity key:

```java
HttpRequest.get("http://orders/api").loadBalanceKey(userId).execute();
```

Implement `LoadBalanceStrategy` for a custom strategy; health statistics and circuit breaking remain the pool's responsibility.

### 17.3 Circuit Breaking and Half-Open Probing

```java
EndpointPool.builder()
        .breaker(BreakerOptions.builder()
                .consecutiveFailureThreshold(5)   // 5 consecutive failures
                .failureRateThreshold(0.5)        // or window failure rate ≥ 50%
                .minimumRequests(10)              // and at least 10 samples in the window
                .cooldownMs(10_000)               // 10s cooldown, doubling on consecutive trips
                .maxCooldownMs(120_000)
                .build())
```

The two trigger conditions are OR-ed: consecutive failures are reliable under low traffic, while the sliding-window failure rate is sensitive under high traffic (successful requests keep resetting the consecutive counter).

**Failure classification** is key: only connection failures, TLS handshake failures, 5xx, and 429 count as node failures;
business 4xx (400/401/403/404) are problems with the request itself and are excluded from breaking, otherwise a batch of 404s would take down the whole pool.
Whether read timeouts count is configurable:

```java
HttpUtils.config().setCountReadTimeoutAsEndpointFailure(false);  // one-way push / long-polling APIs
```

**Half-open probing is rate-limited**: after the cooldown expires, each endpoint admits only **one** probe request; all other requests fail fast,
so traffic is not flooded toward a known-bad node. The breaker opens only when the probe succeeds; a failed probe re-enters a longer cooldown.

Manual traffic draining for operations:

```java
pool.byOrigin("http://10.0.0.7:8080").setDisabled(true);
```

### 17.4 Service Discovery

`ServiceDiscovery` returns **weighted** endpoints, so weights from the registry participate directly in scheduling.

```java
// Nacos
ServiceDiscovery discovery = new NacosDiscovery.Builder("10.0.0.1:8848,10.0.0.2:8848")
        .namespaceId("prod")
        .groupName("ORDER_GROUP")      // must be specified when not DEFAULT_GROUP, otherwise nothing is found
        .clusters("BJ")                // only instances in the local data center
        .auth("nacos", "nacos")        // logs in automatically for an accessToken and refreshes it
        .endpointScheme("http")
        .build();

EndpointPool pool = EndpointPool.builder()
        .serviceName("order-service")
        .discovery(discovery)
        .refreshIntervalMs(10_000)     // background refresh interval; 0 means fetch only once at construction
        .build();

// DNS (suitable for K8s headless services)
new ServiceDiscovery.Dns("http", 8080);

// Static fallback
new ServiceDiscovery.Fallback(discovery, new ServiceDiscovery.Static("http://10.0.0.9:8080"));
```

Refresh uses **incremental merging**: endpoints with the same base URL inherit their previous health state (breaker, in-flight, statistics),
so scaling never resets breaker information, and a bad node just removed does not immediately resume receiving traffic.
When discovery fails or returns an empty list, the **previous snapshot is kept** (fail-static); the endpoint pool is never emptied.

Registry-based discovery (Nacos/DNS) must be configured with a `serviceName`, otherwise `build()` fails immediately instead of throwing
a confusing exception at the first refresh; `ServiceDiscovery.Static` carries its own endpoints and does not need one.

Remember to call `pool.close()` when done to stop the background refresh thread.

`NacosDiscovery` makes its HTTP calls through this module's `HttpUtils`, so it automatically gets engine adaptation
(JDK 8 → `HttpURLConnection`, JDK 11+ → `java.net.http.HttpClient`), connection reuse, content decompression,
and unified timeout semantics — no need to write `HttpURLConnection` code again yourself.

### 17.5 Bypassing Load Balancing

Calls made by "the infrastructure itself" must not be rewritten by load balancing — registry fetches, health probes, admin APIs, etc.:

```java
HttpRequest.get("http://10.0.0.1:8848/nacos/v1/ns/instance/list").bypassLoadBalance(true).execute();
```

This matters especially for global pools without a `serviceName`: such pools apply to **all** requests,
and if service discovery's own HTTP requests were not bypassed, they would be rewritten to business endpoints. `NacosDiscovery` sets this flag internally already.

### 17.6 Manually Configuring Multiple Domains / IPs

Without a registry, just list the endpoints; domains, IP+port, and even different protocols can be mixed:

```java
// Multiple domains (e.g., primary and backup access points)
EndpointPool pool = EndpointPool.builder()
        .serviceName("pay")
        .add("https://pay-a.example.com", 3)
        .add("https://pay-b.example.com", 1)
        .build();
HttpUtils.config().setEndpointPool(pool);
HttpUtils.postJson("http://pay/v1/order", payload);   // use the service name as the host

// Multiple IP+port endpoints
EndpointPool.builder().serviceName("orders")
        .add("http://10.0.0.7:8080").add("http://10.0.0.8:8080").build();
```

If you don't want the "service name" indirection, here are two more direct approaches:

```java
// Option 1: no serviceName; the pool applies to all requests, and any host in the URL gets rewritten
EndpointPool any = EndpointPool.builder()
        .add("https://pay-a.example.com").add("https://pay-b.example.com").build();
HttpUtils.config().setEndpointPool(any);
HttpUtils.get("https://pay-a.example.com/v1/ping");   // rotates between a/b

// Option 2: attach the pool to specific requests only, leaving the global state untouched
HttpRequest.get("https://pay-a.example.com/v1/ping").endpointPool(any).execute();
```

The endpoint list is immutable after construction. To add or remove endpoints at runtime, use a `ServiceDiscovery`
that returns the current list together with `pool.refreshNow()` (see 17.4); for temporary draining, use `pool.byOrigin(...).setDisabled(true)`.

### 17.7 Host Header and HTTPS

How the `Host` header is set after origin rewriting depends on the endpoint form, which the pool determines automatically:

| Endpoint form | `Host` header | Reason |
| --- | --- | --- |
| IP, e.g. `http://10.0.0.7:8080` | the original host name (e.g. `orders`) | needed when the upstream routes by domain-based virtual host / gateway |
| Domain, e.g. `https://pay-a.example.com` | the endpoint's own domain | hard-writing the logical service name back would route the upstream to the wrong backend |

**IP endpoints also require JVM arguments**, otherwise the JDK discards the application-set `Host`:

- JDK 8: `-Dsun.net.http.allowRestrictedHeaders=true`
- JDK 11+: `-Djdk.httpclient.allowRestrictedHeaders=host`

If you don't need this behavior, turn it off with `preserveHostHeader(false)`.

**HTTPS endpoints**: with domain endpoints, certificate verification works naturally (the URL contains that domain, so SNI matches the certificate),
which is why domains rather than IPs are recommended for multi-access-point scenarios. If you must use IP + HTTPS, certificates usually don't contain IPs,
so you need explicit `ignoreSsl(true)` (this module's default is to skip verification) or provide your own `SSLContext`.

**HTTPS + IP endpoints**: certificates contain domains, not IPs, so the handshake fails under strict verification. Three options:
① use domain endpoints instead of IPs; ② explicitly `ignoreSsl(true)` for the request (this module's default already skips verification);
③ provide your own `SSLContext`. Choose deliberately per environment — don't assume a Nacos IP list will just work with HTTPS.

### 17.8 Observability

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

Metrics can also be read directly from each endpoint: `getInFlight()`, `getEwmaLatencyMs()`, `getWindowFailures()`,
`getWindowTotal()`, `getConsecutiveFailures()`, `getBreakerTrips()`, `getCooldownUntilMs()`.

### 17.9 Relationship with Retries

Failover and retries share one attempt budget: the attempt count is `max(retry policy attempts, min(endpoint count, 5))`,
and the whole call is bounded by the total timeout, so many endpoints cannot stretch a single call indefinitely.

## 18. Notes

- Ignoring SSL is only suitable for development or packet-capture debugging; do not trust all certificates by default for public production traffic.
  Once `HttpRequest.ignoreSsl(...)` is called explicitly, it is not overridden by the global switch.
- Large file downloads stream to disk; `get` / `post` buffer the body in memory and are unsuitable for very large responses.
- Downloads force `Accept-Encoding: identity`: Range offsets and Content-Length are both measured in unencoded bytes,
  otherwise resuming would append decoded bytes at an encoded offset, producing a corrupted file.
- Downloads verify the status code and length, and by default write to a temp file first and then atomically replace the target, so failures never leave a truncated target file.
- Files larger than 1MB are assembled into a temp file and streamed, avoiding a memory peak of 2x the file size.
- The CookieJar rejects `Domain`s unrelated to the request host as well as public suffixes like `.co.uk`, and has a capacity limit (default 3000).
- The published JAR sets `Multi-Release: true`; on JDK 11+ the HttpClient implementation in `META-INF/versions/11` is loaded.
