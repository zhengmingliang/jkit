# 版本更新说明

本文记录 jkit 各版本的用户可见变更。新版本一律追加到「版本记录」最上方，不要改写已发布小节。

## 如何记录后续版本

发布（或准备发布）新版本时按下面做：

1. 把根 `pom.xml`（`jkit-parent`）的 `<version>` 改成新版本号，子模块继承该版本；同步 `README.md` 页头与依赖示例、`Main.java` 打印的版本。
2. 在本文「版本记录」**最上方**插入新版本小节，日期用当天（`YYYY-MM-DD`）。
3. 按「新增 / 变更 / 修复 / 构建」分类写清调用方能感知的变化，不要只贴 git 标题。
4. 本次新增的公开 API 在 javadoc 里补 `@since x.y.z`。
5. 历史版本（如 2.0.0 的 ZmlTools 迁移说明）留在原处，不要把旧条目改写成新版本。

模板：

```markdown
## x.y.z - YYYY-MM-DD

### 新增
- ...

### 变更
- ...

### 修复
- ...

### 构建
- ...
```

没有某一类变化时省略对应小标题即可。

## 版本记录

## 2.0.1 - 2026-09-01

日期格式化与 `ConvertUtils.toDate` 性能版本。常用日期路径不再每次 `new SimpleDateFormat`。

### 新增

- `DateUtils.parse(String)`：自动识别常见日期字符串（时间戳、紧凑数字、`-` `/` `.`、中文/韩文、ISO-8601 含 `T`/`Z`/`+0800`/`+08:00`）。
- `DateUtils.fromEpochNumber(long)`：10 位秒或 13 位毫秒时间戳转 `Date`。
- `DateUtils.fromTemporal(TemporalAccessor)`：`java.time` 时间对象转 `Date`。
- `ConvertUtils.toDate` 额外支持 `Instant`、`OffsetDateTime`、`ZonedDateTime`。
- `Print.enableLog`：为 `false` 时只打控制台，不再写入 JUL。
- `com.alianga.jkit.log.LocaleFormatter`：固定 Locale 的 JUL 格式化器，时间戳用手写 `yyyy-MM-dd HH:mm:ss.SSS`。

### 变更

- `DateUtils` 去掉 `SimpleDateFormat`。`yyyy-MM-dd HH:mm:ss` 走 `char[]` + 秒级缓存，`yyyy-MM-dd` / `yyyyMMdd` / `yyyy-MM-dd HH:mm:ss.SSS` 走手写拼接，其余 pattern 复用 `DateTimeFormatter`。
- `ConvertUtils.toDate(Object)` 改为按数字字段抽取，不再推断 SimpleDateFormat pattern。常见字符串解析约快一个数量级，结果与 ZmlTools 对齐。
- JUL 默认格式改为英文级别名（`WARNING`/`SEVERE`），不再随 JVM 默认语言变化。
- `RandomUtils.randomBirth` 改为 `LocalDate` + 手写 `yyyyMMdd`，去掉每次分配的 `SimpleDateFormat`/`Calendar`；`minAge == maxAge` 不再抛异常。
- `getIdCardCheckNum` 复用 `IdCardUtils.calcTrailingNumber`，避免 17 次 `Integer.parseInt`。
- `getUUID` 去 `-` 不再走正则；`decoding` 改为整数幂避免 `Math.pow` 精度问题；`randomOne` 可取到数组最后一个元素。
- `IdCardUtils` 随机生日按当月实际天数生成，避免 Calendar 宽松模式下的日期滚动。
- curl 解析：展开 `-kLs`/`-XPOST`，`--json` / `--data-urlencode` 按 curl 语义处理，`@file` 不再当字面正文，`-F` 保持 multipart；`toCurl` 代理输出 `host:port`；`-k` 只作用于单次请求。公开 API 为 `parseCurl` / `curlToRequest` / `curl` / `curlString` / `requestToCurl`，以及不可变模型 `ParsedCurlRequest`（`CurlParser.parseModel`）。
- 多语言 curl 代码生成从 jkit 拆到独立 artifact `com.alianga:jkit-curl-codegen`。jkit 只解析和执行，不再带 `CurlParser.generate` 与 `http.codegen` 包。

### 构建

- `git-commit-id-plugin`、`buildnumber-maven-plugin`、`maven-source-plugin` 从 `publish` profile 挪到默认构建，`package` 即可产出带构建信息的 sources jar。
- 仓库改为多模块：父 POM `com.alianga:jkit-parent`（packaging pom），运行时库在模块 `jkit-core`（发布坐标仍是 `com.alianga:jkit`，沿用原根 POM 的编译 / Checkstyle / MRJAR / 发布配置），代码生成在 `jkit-curl-codegen`。根目录 `mvn test` 同时构建两个模块。

### jkit-curl-codegen

- 新增 curl 代码生成：OkHttp / Apache 5 / JDK 11+ / jkit / Kotlin / fetch / axios / requests / httpx / Go / C# / PHP。
- 扩充到 28 种：新增 Java `HttpURLConnection` / Unirest、JavaScript request / unirest / http（follow-redirects）/ jQuery / XMLHttpRequest、PHP pecl_http、R httr2、Rust reqwest、Swift URLSession、Ruby Net::HTTP、PowerShell Invoke-RestMethod、curl（Windows cmd）、curl（Windows PowerShell）、wget。入口为 `CurlCodegen.generate(id, curl)`。
- 修复 R（httr2）与 PowerShell 生成器的运行时错误：R 改用 `req_perform()`（默认跟随重定向；关闭时 `req_options(followlocation = 0)`），multipart 字段名加反引号、正文不重复设置 Content-Type；PowerShell 把受限头 User-Agent / Cookie 分别转成 `-UserAgent` 参数与 `WebRequestSession`（Windows PowerShell 5.1 的 `-Headers` 不接受这两个头），字符串正文的 Content-Type 自动补 `charset=utf-8`。
- 修复 PowerShell 生成器两处 5.1 运行时错误：`-Headers` 里的 Connection / Content-Length / Host / Range 等受限头直接丢弃（`Connection: close` 转成 `-DisableKeepAlive`，keep-alive 为默认行为）；Cookie 值含逗号 / 分号时按 .NET 要求整体加双引号，避免 `CookieContainer.Add` 抛 `CookieException`。
- 修复 Windows curl 生成器在 PowerShell 中不可用：拆成两个生成器——`shell-curl-windows` 面向 cmd.exe，恢复 `^` 续行的多行写法（cmd.exe 不认识 `--%`，会当成 curl 的未知选项报错）；新增 `shell-curl-powershell` 面向 PowerShell，输出单行 `curl.exe --% ...`（`curl.exe` 绕过 Invoke-WebRequest 别名，`--%` 停止解析让 sec-ch-ua 等含双引号的头原样透传；`^` 续行是 cmd.exe 专用语法，粘贴到 PowerShell 会被逐行执行）；顺带移除非法选项 `--no-location`（curl 默认即不跟随重定向）。
- 修复 PowerShell 版 curl 响应中文乱码：命令前追加 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;`（Windows PowerShell 5.1 默认按系统 ANSI 代码页 / 中文系统 GBK 解码原生命令输出，UTF-8 响应会乱码）；cmd.exe 版本在说明里提示先执行 `chcp 65001`。
- 修复 PowerShell（Invoke-RestMethod）生成器响应中文乱码：PS 5.1 在响应 Content-Type 不带 charset 时按 ISO-8859-1 解码响应体且无法覆盖，改为 `Invoke-WebRequest -UseBasicParsing` + `[System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())` 从原始字节强制 UTF-8 解码，再 `ConvertFrom-Json`（非 JSON 响应回退为原始文本）；生成器 id `powershell-restmethod` 保持不变。

## 2.0.0

jkit 首个对外版本，由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来：零第三方依赖、包名改为 `com.alianga.jkit`，坐标 `com.alianga:jkit:2.0.0`。

能力概要见 [README.md](README.md)：CSV / HTTP / JSON / YAML / 配置 / 表达式等均用纯 JDK 重写。从 ZmlTools 迁过来的项目可继续用 `relocated/zmltools` 把 `top.wuyongshi:ZmlTools:2.0.0` 重定向到本坐标（包名仍需手工替换）。
