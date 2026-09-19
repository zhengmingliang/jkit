# 版本更新说明

本文记录 jkit 各版本的用户可见变更。每个版本号只出现一次，按新到旧排列。

未发版的改动追加到**当前版本**小节（现在是 2.0.2）；发版后冻结该小节，在上方新建下一版本。不要改写已冻结的历史版本，也不要为同一版本再开 `unreleased` 标题。

## 2.0.2 - 2026-09-14

### 新增

**jkit-notify**

- `MarkdownTheme`：Markdown → HTML 的 13 套渲染主题（经典 / 蓝 / 橙心 / 彩虹 / 兰青 / 嫩黄 / 碧蓝 / Vue 绿 / 绿意 / 麦色 / 墨黑 / 姹紫 / **博客**），前 12 套命名与观感对齐 doocs/md，`blog` 复刻 alianga.com（Halo · LIlGG_Sakura）正文：珊瑚红链接、暖黄行内码、深色 One Dark 代码块、¶/# 标题装饰、列表虚线圆角框。`MarkdownTheme.of("lark")` 按 id 解析，忽略大小写与 `-` / `_`，未知值回退经典主题。
- `MarkdownRenderOptions` 与 `Markdown.toHtml(md, options)` / `toDocument(md, options)`：一次指定主题、代码高亮、内联样式与响应式。`inlineStyle(true)` 把样式写进每个标签的 `style` 属性，兼容会剥离 `<head><style>` 的 Outlook / 部分企业邮箱与微信粘贴。`Markdown` 升为公开入口；`NotifyUtils.markdownToHtml(md)` / `markdownToDocument(md, boolean)` / `wrapHtmlDocument(fragment, boolean)` 保留并标过期。
- 代码块语法高亮（默认开启，`.highlight(false)` 关闭）：自研零依赖词法扫描，覆盖 java / js / ts / go / python / sql / shell / yaml / properties / json / xml / html，输出内联 `<span style>`，两种模式下都可见；不认识的语言退化为纯转义。
- `SmtpChannel.markdownTheme(theme)` / `inlineMarkdownStyle(boolean)`：给 SMTP 渠道固定 Markdown 渲染主题（默认经典 + `<style>` 模式）。
- 本地图片内嵌：`MarkdownRenderOptions.imageBaseDir(dir)`（对应 `SmtpChannel.markdownImageBaseDir(dir)`）设置基准目录后，Markdown 里引用本地相对路径的图片会转成 `data:image/...;base64,...` 写进 `src`，正文可脱离原文件独立展示。只处理本地路径，`http(s)://` / `//` / `data:` / `cid:` 原样保留；支持 `./`、`../`、绝对路径与 `file:` 前缀。文件不存在、非图片（按后缀与文件头识别 MIME）或超过 `maxInlineImageBytes`（默认 2MB）时保留原 `src`，不抛异常。
- `MarkdownRenderOptions` 新增 `.inlineImage(boolean)` 与 `.maxInlineImageBytes(long)`，控制图片内嵌开关与单张体积上限。

**jkit-sql**

- `JdbcUrlUtils`：解析 JDBC URL（主机 / 集群节点 / 库名 / schema / 参数），`fromUrl` 推断 `SqlDialect`，`getDbType` 返回类型短名，`driverForUrl` / `getDriverClassName` 猜测驱动类。覆盖 MySQL 复制与负载、PostgreSQL HA、Oracle SID/Service/RAC、SQL Server、H2、Gauss/openGauss、达梦等。PostgreSQL 系从 `currentSchema` 取 schema（缺省 `public`）。
- `SQL.inject` / `SqlInjectConfig` / `SqlInject` / `SqlRewrites.inject`：行级条件注入（列名自定，不限租户）。下钻 UNION / 子查询 / CTE；JOIN 按别名限定；INSERT 补列；MERGE 补 ON。启动时 `SQL.injectConfig` 配表白名单和列；`SqlInject.setCurrent` 覆盖本线程；`SqlInjectValue` 每次 inject 再取值。可一次注入多列。字符串值按 SQL 单引号转义。
- `SQL.replaceSelectItem` / `SQL.replaceSelectItems` / `SQL.expandStar`：列级脱敏。整树替换 SELECT 投影（保留输出列名）；`replaceSelectItems` 一次 clone、一次遍历替换多列；`expandStar` 按表列清单把 `*` / `t.*` 展开后再裁列或改写成掩码表达式。解析不到的星号保持原样。
- `SqlWallConfig`：`denyTables` / `allowTables` / `requireWhereColumns` / `maxTables`。违规码 `deny-table`、`allow-table`、`missing-where-column`、`too-many-tables`。恒真再拦 `LIKE '%'` 与 `XOR 1=1`。
- `DATE_FORMAT` 跨方言转换会改写常见格式符：`%Y-%m-%d %H:%i:%s` → PG/Oracle `TO_CHAR(..., 'YYYY-MM-DD HH24:MI:SS')`，SQLite `strftime` 会交换参数并把 `%i` 改成 `%M`。对不上的格式符保留并 `SEMANTIC_RISK`。
- 复杂业务 SQL 1200 条回归（`sqls/complex-sql/`，四方言各 300）：L1 parse 1200/1200；L2 parse→format→parse 结构保真 1200/1200；L3 主矩阵 1500 次转换后再 parse 1500/1500。`DATE(col)` 回写不再误成类型字面量。转换补 `GETDATE`/`DATEADD`/`LEAST`/`GREATEST`、`CURRENT_DATE`→SQL Server `CAST(GETDATE() AS DATE)`、Oracle 日期间隔用数字加减、递归 CTE 去 `RECURSIVE` 并补列清单。真库代表题 001/022/211 原文与 MySQL→Oracle12/SQL Server 转换后均可执行（`tools-test` `ComplexSqlExecutionIT`）。
- 复杂 SQL 方言切片 L2/L4 harness（Oracle）：`ComplexSqlOracleSliceL2*`（jkit-sql）与 `ComplexSqlOracleNativeExecute*`（tools-test）；001–300 在 oracle19c 本库原生执行 300/300（parseFail=0 / execFail=0），报告 `target/complex-sql-reports/oracle-l2|l4-*`。
- 复杂 SQL 方言切片 L2/L4 harness（MySQL）：`ComplexSqlMysqlSliceL2*`（jkit-sql）与 `ComplexSqlMysqlNativeExecute*`（tools-test）；001–300 在 MySQL `3308/test_db` 本库原生执行 300/300（parseFail=0 / execFail=0），报告 `target/complex-sql-reports/mysql-l2|l4-*`。
- 复杂 SQL 方言切片 L2/L4 harness（PostgreSQL）：`ComplexSqlPostgresSliceL2*`（jkit-sql）与 `ComplexSqlPostgresNativeExecute*`（tools-test）；001–300 在 `jkit_complex`（5532）本库原生执行 294/300（parseFail=0；6 条语料 `round(float8,int)` 需显式 cast），报告 `target/complex-sql-reports/postgres-l2|l4-*`。
- 复杂 SQL 方言切片 L2/L4 harness（SQL Server）：`ComplexSqlSqlServerSliceL2*`（jkit-sql）与 `ComplexSqlSqlServerNativeExecute*`（tools-test）；001–300 在 `jkit_ss_test`（1433）本库原生执行 300/300（parseFail=0 / execFail=0），报告 `target/complex-sql-reports/sqlserver-l2|l4-*`。
- `com.alianga.jkit.sql.entity.Comment`：本模块自有的表 / 字段注释注解（`TYPE`+`FIELD`，`value()`）。与 Hibernate `@Comment`、`@SqlTable(comment)` / `@SqlColumn(comment)` 并列；扫描仍按简单名 `Comment` 识别，供 `jkit-sql-model` 等模块解析字段注释时选用。
- `SQL.bind` / `SQL.bindNamed`：把 `?` / `:name` 换成字面量或公式，并识别模板占位 IDENT（`@name@` / `#{table}` / `${*}` / `{{*}}` / `<*>` 等）。表名位置写成标识符（必要时加方言引号，防注入）；表达式位置与 `:name` 相同。字符串只加倍单引号；公式传 `SqlExpr`。`IN ?` 可填集合。布尔：Oracle / 达梦 / SQL Server / SQLite / DB2 / MySQL / Hive 写 `1`/`0`；PG / H2 / ANSI / Presto / ClickHouse 写 `TRUE`/`FALSE`。未传命名值时不扫描标识符。
- `SqlPlaceholders.mybatis()` / `hashBrace()` / `dollarBrace()`：内置 MyBatis `#{property}`、`${property}`，解析支持 `#{id,jdbcType=VARCHAR}` / `#{item.name}`；bind 按逗号前的属性名取值。
- `SqlGuardedStatement`：T-SQL 控制流守卫 `IF <expr> <stmt> [ELSE <stmt>]`（如 SQL Server init 幂等删表前置 `IF OBJECT_ID('t','U') IS NOT NULL DROP TABLE t;`）。`SqlParser.parseIfGuard` 解析 condition 原文与内层 body 并包成 `SqlGuardedStatement`；其 `type()` 委托给 body，故 `IF…DROP` 对外仍是 DROP，下游格式化 / 跨方言转换可正确识别。解析器在语句起始处的 `IF` 一律按控制流守卫处理（MySQL 里 `IF` 是函数，但语句起始位置不冲突）。`SqlFormatter` / `SqlAstCloner` / `SqlSchemaConverter` 均已支持——`sqlserver_init.sql` 整文件 `parseAll` 不再因 `IF` 抛 unsupported，其 51 处守卫识别为 DROP，跨方言转换 0 失败。

**jkit-core**

- `AESCrypt` GCM 认证加密：`encryptGcm(data, key)` / `decryptGcm(data, key)` 走 `AES/GCM/NoPadding`，每次随机 12 字节 IV 并拼在密文前（`IV || ciphertext`），解密自动拆分，无需调用方保管 IV；四参版本 `encryptGcm(data, key, iv, aad)` / `decryptGcm(data, key, iv, aad)` 支持自定义 IV 与 AAD（附加认证数据参与完整性校验但不加密，适合绑定用户 ID / 业务单号）。另有 `generateIv()`、`GCM_CIPHER_ALGORITHM` / `GCM_IV_LENGTH` / `GCM_TAG_BITS` 常量。相比 ECB / CBC，密文被篡改或密钥不对时解密直接抛异常，而不是解出乱码继续往下传。
- `EncryptUtils.RSA` OAEP 填充：`encryptOaep(byte[], PublicKey)` / `decryptOaep(byte[], PrivateKey)` 及 base64 字符串便捷方法，填充为 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`；新增 `buildKeyPair(int keySize)` 可显式指定密钥长度，常量 `DEFAULT_KEY_SIZE`。
- `ULID`：`common.idgenerate` 下的 26 字符 Crockford Base32 有序 ID。`next()` / `nextMonotonic()`（同毫秒内 `+1`，单 JVM 严格递增）、`parse()` 取时间戳与随机部分、`toBytes` / `fromBytes` 16 字节紧凑存储、`isUlid()` 校验。解析按 Crockford 容错（`I`/`L`→`1`、`O`→`0`、大小写不敏感），含 `U` 等非法字符拒绝。按字符串排序即按时间排序，比 UUIDv4 更适合做索引主键。
- `UUIDv7`：RFC 9562 的时间有序 UUID，毫秒时间戳在高 48 位。`next()` / `nextMonotonic()`（12 位计数器，一毫秒用满则推进逻辑时钟 1ms，保证不回退）、`nextString()`、`timestamp(UUID)` 取回毫秒时间戳、`isV7(UUID)` 判定。形态仍是标准 UUID，可直接存 `uuid` 列。
- `IdGenerator.ulid()` / `uuidV7()` / `uuidV7String()`：与雪花并列的门面入口。
- `DesensitizeUtils`：常用数据脱敏。`phone` / `idCard` / `bankCard` / `name` / `email` / `address` / `carNo` / `ip` / `password` 语义方法，通用 `mask(value, keepHead, keepTail[, maskChar])` 与 `maskAll`，配置驱动用 `desensitize(value, Type)`（`Type` 枚举十类）。`null` 与空串原样返回不抛异常；`keepHead + keepTail` 覆盖全文时只保留首字符而非原样返回；`password` 固定输出 6 个掩码，不泄漏密码长度。
- `HttpClient`：可实例化 HTTP 客户端（next-plan 第 4 节）。`HttpUtils` 是进程级全局门面，所有 `setXxx` 改全局默认，多线程下互相覆盖污染；`HttpClient` 每个实例持有独立配置（超时 / 代理 / SSL / CookieJar / 引擎 / 拦截器 / fakeIp / 默认 Content-Type），多实例与多线程互不污染。`builder()` 从当前全局默认起算、链式覆盖；`shared()` 取全局默认单例，与 `HttpUtils` 静态方法等价。隔离通过 ThreadLocal 把实例配置下发到发送链路实现，无需全局加锁。`HttpConfig` 新增 `copy()` 供实例取独立配置副本。
- `JwtUtils`：JSON Web Token 签发与校验（next-plan 第 4 节「JWT + 加密默认值」项中 JWT 一半）。仅依赖 JDK（`java.util.Base64` 的 url 变体 + `javax.crypto` 的 HMAC / RSA-SHA256），零第三方依赖。`createHs256(secret, payload)` / `createRs256(keyPair, payload)` 签发，`parseHs256(secret, token)` / `parseRs256(publicKey, token)` 校验并回吐 payload（自动采用 `Map` 上下文）；`withExp(minutes)` / `withNbf(minutes)` / `withIssuer` / `withAudience` 等声明快捷构造，`exp` / `nbf` / `iat` 走毫秒或秒两种单位（`expAt(Instant)` / `expSeconds(long)`）。校验严格：签名错误、密钥不符、过期、未生效、载荷被篡改一律拒绝；显式禁止 `alg=none` 与算法混淆（HS256 令牌不会被 RS256 公钥解）；base64url 无填充、载荷支持任意 JSON 对象（含嵌套与标准注册声明）。`JwtUtilsTest` 16 例覆盖 HS256 / RS256 往返、错误密钥、篡改、过期、未生效、声明保留、`alg=none` 拒绝、base64url 边界等。
- `JSONPatch`：JSON Patch（RFC 6902）应用工具（next-plan 第 4 节「JSON Patch / Schema `required` 别名」项中 Patch 一半；`required` 别名已在 2.0.2 早些时候落地）。`apply(String doc, String patch)` 自行解析与序列化，`apply(Object, List)` 对已解析的 `Map`/`List` 原地修改并返回；支持 `add` / `remove` / `replace` / `move` / `copy` / `test` 六种操作，路径采用 JSON Pointer（RFC 6901，`~1`→`/`、`~0`→`~` 解码），`test` 按值比较数字（`1` 与 `1.0` 相等）。非法 `path`、下标越界、删除/替换不存在的成员或 `test` 不成立时抛出 `JSONPatchException`（`JSONException` 子类）。`JSONPatchTest` 32 例覆盖各操作、数组插入与末尾 `-`、根替换、指针转义、数字相等与各类非法输入。
- `Retryer` / `CircuitBreaker`：通用（与 HTTP 解耦）的韧性抽包（next-plan 第 4 节「韧性抽包」项）。`Retryer.retry(Callable, RetryConfig)` 按指数退避 + 抖动重试有限次，默认重试所有非 `Error` 异常，`retryOn(Predicate)` / `retryOn(Class)` 收窄范围；`CircuitBreaker` 状态机 `CLOSED`→`OPEN`→`HALF_OPEN`，连续失败达阈值熔断、冷却后半开探测、成功恢复，内部状态用原子变量、可多线程共用。两者均纯 JDK、零依赖。`RetryerTest`（8 例）+ `CircuitBreakerTest`（7 例）覆盖首次成功、重试后成功、耗尽抛最后异常、谓词过滤、不重试 `Error`、退避上限、熔断阈值/OPEN 直接拒绝/冷却后半开/半开恢复与重开/成功重置等。
- `com.alianga.jkit.html` 轻量 HTML 解析与 CSS 选择器（next-plan 第 4 节「轻量 HTML」项，替代已移除的 Jsoup），零依赖：`Html.parse` 解析为 `Document`；DOM 有 `Element` / `TextNode` / `Comment` / `Elements`，元素支持 `select` / `selectFirst` / `text` / `attr` / `innerHtml` / `outerHtml` / `children` 等；选择器支持标签、`#id`、`.class`（含 `.a.b`）、属性 `[attr]` 与 `=`/`^=`/`$=`/`*=`/`~=`/`|=`、后代与子代组合符、逗号分组、`:first-child` / `:last-child` / `:only-child` / `:root` / `:empty` / `:not()` / `:nth-child(an+b)`；另有 `Html.escape` / `Html.unescape` 实体处理。解析器对不规范 HTML 容错（隐式关闭未闭合标签、void 元素不进栈、`<div/` 等残缺标签安全终止、script/style 按原文处理）。`HtmlTest` 14 例覆盖解析结构、各选择器、伪类、void 元素序列化、文本抽取、容错与实体转义。

- `com.alianga.jkit.html` 保真度增强（以 Jsoup 1.18.1 为对照做差分验证，93 组「HTML + 选择器」用例 86 组结果一致）：**解析**端新增隐式 `html`/`head`/`body` 骨架、省略结束标签按 HTML5 作用域自动闭合（`p` / `li` / `dt`+`dd` / `td`+`th`+`tr` / `option` / `rt`+`rp` / `thead`+`tbody`+`tfoot`，含被行内元素埋住的 `p`）、`table` 内隐式补 `tbody`、无引号属性值支持 `/` 与 `&`（`href=/x?a=1&b=2` 不再被截断）、新增 `DataNode` 使 `script`/`style` 原文序列化不转义；**选择器**端新增相邻兄弟 `+` 与通用兄弟 `~` 组合符、`:nth-last-child` / `:nth-of-type` / `:nth-last-of-type` / `:first-of-type` / `:last-of-type` / `:only-of-type` / `:contains` / `:containsOwn` / `:matches` / `:matchesOwn`、属性 `[attr!=v]`，伪类名大小写不敏感，且非法选择器（如 `h1 @ p`、`p ~ + p`、`p:not()`）改为立即抛 `SelectorException`——此前遇到未知字符会死循环；**文本**端 `text()` 与 Jsoup 对齐（块级元素间补空格、空白折叠、裁剪首尾含 `&nbsp;`），新增 `ownText()` / `nextElementSibling()` / `previousElementSibling()` / `elementSiblingIndex()`，`nodeText()` 改为返回未归一化原文；**实体**端新增 `Entities` 收录约 200 个高频命名实体（`&copy;` `&mdash;` `&hellip;` 等）。`HtmlTest` 扩充至 32 例，并新增非法选择器必须抛异常而非挂死的回归用例。

- `com.alianga.jkit.html` 性能优化（同机与 Jsoup 1.18.1 对比：预热 3 轮、计时 7 轮取中位数，`-Xms1g -Xmx2g`，Jsoup 仅用于基准、未进入项目依赖。**内存**侧 `Element` 属性表由 `LinkedHashMap` 改为并行 `String[]`（键 / 值 / 计数三件套），子节点表与属性表均懒创建，新增 `Names` 驻留表复用标签名与属性名字符串——200 份 300 KB 页面 DOM 常驻堆 506.9 MB → 268.0 MB（Jsoup 241.5 MB），由高出 110% 收窄到高出 11%。**解析**侧开放元素栈的 head / body 成员判断由 O(栈深) 线性查找改为下标缓存，起始标签的属性写入复用缓冲区（不再每个标签分配 Map），`Character.isLetter` / `isWhitespace` / `isLetterOrDigit` 换成本地 ASCII 判断，`trim().isEmpty()` 改为手写扫描——300 KB 页面解析 2.161 ms → 1.775 ms，由慢 12% 变为快 3%–28%。**选择器**侧遍历由「先 `allElements` 再 `LinkedHashSet` 去重」改为直接递归（不建中间列表、不分配迭代器），`Compound.matches` 按下标遍历，属性选择器由「`hasAttr` + `attr` 两次扫描」合并为一次，`~=` 去掉正则 `split`，标签名在解析期归一后由 `equalsIgnoreCase` 退化为 `equals`，并新增上限 256 条、按访问序淘汰的选择器解析缓存（与 Jsoup 一样缓存解析结果）——中等页面（30 KB）选择器由慢 5 倍变为快 1.6–4.1 倍，大页面（300 KB）选择器基本持平（0.91–1.15 倍）。优化后 `HtmlTest` 仍 32 例全过，与 Jsoup 的差分验证仍是 112 组一致 / 7 组已知差异，与优化前逐条相同，无行为回退。

- `com.alianga.jkit.html` 选择器索引：`Document` 上新增惰性 id / class / 标签索引（键前缀 `t:` / `#:` / `.:`，首次查询时建，之后复用）。`doc.select(...)` / `doc.selectFirst(...)` 不再每次全树深度优先遍历，而是「取候选 + 逐个校验」；键的优先级是 id > class > 标签名，`div.foo` 取 `.foo`、`a[href^=/p/]` 取 `a`（标签名也算索引键，所以带属性的查询一样受益），只有纯属性 / 伪类查询（如 `[data-x]`、`:empty`）才退回全树遍历。查询根是子元素时（如 `article.selectFirst("a.post-title")`）仍走遍历——候选集以整份文档为范围，逐个判断是否落在子树内反而更慢，所以抽取类代码里能提到文档级的查询尽量提到文档级。索引敢这么用，是因为本模块 DOM 解析完即不可变（没有 setter、没有增删子节点的入口），建一次就再也不会失效，不需要任何失效逻辑。实测（合成页面，成对交替计时）：选择器由 1.3x–2.2x 提升到中页面 4.6x–80x、大页面 8.6x–749x，端到端 1.89x；真实站点 alianga.com 首页解析 1.74x、解析 + 抽 10 篇全部字段 1.73x、4 个抽取选择器 4.5x–37.1x。常驻堆只解析仍为 202.6 MB（不查询就不建索引，与建索引前一模一样），解析 + 一次查询 209.1 MB vs Jsoup 181.0 MB，索引约占一份 DOM 的 3.5%。`HtmlTest` 由 34 例扩充到 39 例，新增 `indexAndTraversalReturnTheSameElements`（同一批查询分别在文档级走索引、在元素级走遍历，断言元素序列逐个 `assertSame`）、`indexKeepsDocumentOrder`、`repeatedIdAndMultiClassAreIndexedCompletely`、`missKeyYieldsEmptyInsteadOfCrash`、`indexIsBuiltOnceAndReused`；与 Jsoup 的差分验证仍是 112 组一致 / 7 组已知差异，真实站点 10 篇文章 5 个字段逐条一致。

### 变更

- `jkit-core`：`EncryptUtils.RSA.buildKeyPair()` 默认密钥长度由 1024 位上调到 **2048 位**。1024 位 RSA 已不满足当前安全基线（NIST / PCI-DSS 均要求 ≥2048）；确需沿用旧长度改为显式调用 `buildKeyPair(1024)`，小于 512 位抛 `IllegalArgumentException`。
- `jkit-core`：`DESCrypt` 与 `EncryptUtils.DES` 标 `@Deprecated`（56 位有效密钥可被暴力破解，ECB / CBC 不防篡改）。方法仍可用，仅用于解密历史数据；新代码用 `AESCrypt.encryptGcm`。
- `jkit-notify`：Markdown 渲染入口迁到 `Markdown`（`toHtml` / `toDocument` / `wrapDocument`）。`NotifyUtils` 上 2.0.1 的 `markdownToHtml(md)` / `markdownToDocument(md, boolean)` / `wrapHtmlDocument(fragment, boolean)` 保留并 `@Deprecated`。`toDocument` / `wrapDocument` 按主题生成样式表（此前是写死的一段 CSS），默认观感略调（正文 16px、行高 1.75、标题与表格样式更完整），并默认开启代码高亮。
- `jkit-notify`：修复 macOS 窗口风格代码块（碧蓝 / Vue 绿 / 墨黑）顶部三个圆点与首行代码重叠——样式表与内联样式都改为顶部留 36px（此前只写 `padding-top:34px`，内联模式与移动端媒体查询的简写 `padding` 都会把它覆盖掉）。
- `jkit-notify`：移动端媒体查询改为带 `!important`（内联模式下行内 `style` 优先级更高，不带则整段适配不生效），并补上标题字号收敛、表格单元格内边距、图片宽度与表格惯性滚动；移动端不再用简写 `padding` 覆盖代码块上内边距。
- `jkit-notify`：带引号装饰的引用（姹紫 / 兰青 / 橙心）改为把引号放进左侧 2.6em 留白区，不再压在正文上；内联模式下的引用内边距与样式表保持一致（此前竖条与填充两种风格产出完全相同）。
- `jkit-notify`：**正文排版改挂到自带的 `<div class="jkit-md">` 容器上**（此前写在 `body` 上）。Gmail、QQ 邮箱等客户端会剥掉 `<html>/<head>/<body>`，`body` 上的 `max-width` / `padding` 会一同失效，表现为正文铺满读信区。现在宽度、内边距、字体、颜色、行高全部内联在这层 div 上。
- `jkit-notify`：修复完整 HTML 文档在浏览器中正文栏贴左边——`body` 不再带 `max-width`（文档壳给 body 写了内联 `margin:0` 以铺满底色，限宽会留下、居中外边距被盖掉）。栏宽与 `margin:0 auto` 只挂在 `div.jkit-md` 上，移动端媒体查询也只收这层容器的内边距。
- `jkit-notify`：正文栏宽度默认 720 → **820px**，桌面端左右内边距 32 → 16px、上下 24px，移动端改为 `12px 8px`（左右基本不留白）。容器宽度取主题自身的 `maxWidth` 令牌，不再在媒体查询里硬编码。
- `jkit-notify`：图片改为居中并带主题圆角（`display:block;margin:1.4em auto`），与段落、代码块、表格同宽——此前单独收窄图片会让图片与文字对不齐；桌面端代码块左右内边距 18px、行高 1.7。
- `jkit-notify`：原文里的 HTML `<img>`（公众号稿常用 `<img src="..." width="100%" />`）消毒后透传，不再转义成 `&lt;img&gt;`。只保留 src/alt/title/width/height/class/loading；`onerror` 等事件丢掉，`javascript:` 的 src 降为 `#`。其他 HTML 标签仍转义。
- `jkit-sql`：行级注入实现类由 `SqlTenantRewriter` 更名为 `SqlInjectRewriter`（2.0.2 未发版，不保留旧名）。租户只是一种场景，类名/方法名不再带 tenant。
- `jkit-sql`：复杂 SQL 回归门禁收紧——L1 parse / L3 转换后 parse 由「≥95% / ≥90%」收到 100%；L3 由 5 个方向对扩到四方言 4×3 全矩阵（3600 次转换，补上 PostgreSQL 作为源）；L2 新增「有效括号不减少」硬断言。8 个方言切片 L2 测试收敛到 `AbstractComplexSqlSliceL2Test`，子类只声明方言与编号区间（净减约 1100 行重复代码）。
- `jkit-sql`：`SqlIdentifier` 支持逐段引号标记（新增 `markQuotedPart` / `isPartQuoted` / `quotedParts`）。此前只有一个整体 `quoted`，`c."LEVEL"` 会被回写成 `"c"."LEVEL"`——引号扩散到表别名，Oracle 里 `"c"` 与别名 `C` 不匹配而报 `ORA-00904`（1200 条语料真库全量对照中 15 条中招）。`SqlParser` 按段打标记、`SqlAstCloner` 复制位图、`SqlFormatter` 按段输出；无逐段信息时（改写器构造的标识符）回退到整体 `quoted`，行为不变。
- `jkit-sql`：L2 结构对比由语句级 6 项扩到深结构——`ComplexSqlReports.deepStructureDrift` 递归 select 树比对列数 / WHERE / GROUP BY / HAVING / ORDER BY / FROM / DISTINCT / UNION / LIMIT，L2 与 8 个方言切片共用。此前列被吃掉、WHERE 整段丢失都检测不到。
- `jkit-sql`：`SQL.bind` / `bindNamed` 字符串入口不再对 parse 结果二次 clone；访问者热路径分发提前；绑定值少分配（位置参数走数组下标、无引号字符串快路径、小整数原文缓存）。AST 入口仍 clone-then-mutate。
- `jkit-sql-auto`：`SqlAutoDialects.fromUrl` / `driverForUrl` 委托 `JdbcUrlUtils`（覆盖 Gauss / Kingbase / Hive / ClickHouse / Trino 等更多 URL）。
- `jkit-sql-auto`：已有表对照实体注释。`DatabaseMetaData.REMARKS` 读入活表/列；实体注释非空且与库不一致时发出 `COMMENT ON` / `ALTER TABLE … COMMENT` / MySQL `MODIFY … COMMENT`。实体未写注释时不覆盖库里已有注释。

- `com.alianga.jkit.html` 真实站点验证（Halo 1.4.5 博客，首页 70 KB，含 24 个 `script`）：抽最近 10 篇文章的链接、标题、懒加载图 `data-src`、占位图 `src`、发布日期，**5 个字段 10 篇逐条与 Jsoup 一致**；3 篇详情页的 `pre code` 命中数与内容同样一致。效率上首页解析 0.179 ms vs Jsoup 0.295 ms（1.65x），端到端（解析 + 抽 10 篇全部字段）0.310 ms vs 0.381 ms（1.23x）。

### 文档

- `docs/sql.md` / `docs/en/sql.md`：实体表 / 列注释补充本模块 `com.alianga.jkit.sql.entity.Comment`。
- `docs/sql.md` / `docs/en/sql.md`：经典 Oracle ROWNUM 包装补充 UNION `ORDER BY` 先外包再分页，以及 `toSqlString` 必须带目标方言。
- `docs/sql.md` / `docs/en/sql.md`：跨方言转换去掉「进行中」口径；补齐 `inject` / `expandStar` / `replaceSelectItem` / `bind` / Wall 表策略 / `DATE_FORMAT` 格式符。英文转换章节与中文对齐。`sql-auto` 补充已有表注释同步。
- `docs/sql.md` / `docs/en/sql.md`「业务场景」补 `bind` / `inject` / `expandStar`+`replaceSelectItems` / `addComment`+方言引号 / MyBatis `#{}/ ${}` 可复制示例；模板占位符节增加 parse+bind 常用写法。样例与 `SqlBusinessScenarioTest` 对齐。
- `docs/sql.md` / `docs/en/sql.md` 业务场景扩到 18 类（2.0.2）：多数据源方言识别（`JdbcUrlUtils`）、报表 `DATE_FORMAT` 跨方言、动态表名安全绑定、低代码查询沙箱（Wall 表白名单 / WHERE 必含列 / 表数上限）。场景 4 补恒真 `LIKE '%'` / `XOR`。
- `docs/toolkit.md`：crypto 章节补 AES-GCM（IV 布局 / AAD / 参数校验 / 与 ECB 的差异）、RSA（OAEP 与 PKCS#1 v1.5 选型表、密钥长度、单块上限）、DES 废弃说明；新增「ID 生成」章节对比雪花 / ULID / UUIDv7 的适用场景与单调模式语义，新增「脱敏」章节说明各类型保留位数与 `mask` 的兜底策略。
- `docs/json.md` / `docs/en/json.md`：Schema 关键字表补 `required`（标准数组写法）与 `must`（自有布尔写法）的区别，新增「必填：required 与 must 选哪个」小节。
- `docs/csv.md` / `docs/en/csv.md`：补完此前 0 字节的空文档，覆盖 `CSVUtils` 轻量读写、`CSV`/`CSVTable` 带表头与 POJO 映射、`CSVObjectWriter` 流式写对象、流式 `readStream`、字符集与解析细节、异常；从 `srcExclude` 移除并加入中英文侧边栏。
- `docs/expression.md` / `docs/en/expression.md`：补完此前 0 字节的空文档，覆盖算术/逻辑、`Map` 与 JavaBean 上下文、位置参数 `p0/p1`、`@` 内置函数、自定义函数与静态方法注册、`renderTemplate` 模板渲染、`CacheableExpression` 缓存说明与异常；同包加入侧边栏，`toolkit.md` 新增「表达式引擎」指针小节。
- `docs/html.md` / `docs/en/html.md`：§9 性能数据全部重测刷新，计时改为**成对交替**（A→B→B→A 各测两轮、各取较小值）。此前固定「先 jkit 后 jsoup」会让先跑的一方吃亏——JIT 编译、分支预测、缓存预热都压在前几轮——大页面 `.post` 因此报出 0.60x 这种并不存在的「jkit 更慢」，`.post` 交替后稳定在 1.3x。刷新后：解析 1.92x / 2.06x / 2.02x（小 / 中 / 大页面），解析 + `text()` 1.67x，选择器中页面 1.55x–2.04x、大页面 1.28x–1.77x，端到端 2.01x，150 份大页面 DOM 常驻堆 202.1 MB vs Jsoup 179.7 MB（0.89x）；真实站点 alianga.com 首页解析 0.203 ms vs 0.381 ms（1.88x）、解析 + 抽 10 篇全部字段 0.247 ms vs 0.428 ms（1.74x）。压测代码迁到 `tools-test` 项目（`com.alianga.test.html.HtmlParseBenchTest` / `HtmlRealSiteBenchTest`），报告归档在 `reports/`，§9 新增「复现方式」小节写明命令与断言口径。
- `docs/html.md` / `docs/en/html.md`：§9 再次刷新为建索引后的数据，新增「索引的使用范围」小节（只对文档级查询生效、键的取法、候选仍是超集故结果与遍历逐一相同），并把优化手段清单由四处扩到五处——前四处加起来选择器只有 1.3x–2.2x，加上惰性索引才是 4.4x–35x。
- `docs/en/sql.md`：跨方言类型转换章节补全到与中文全等——Normal Form 框架与设计文档入口、Phase 0–1 完整 import 代码、`SqlDataTypeRegistry` 校验、`RegistryValidationTest`、Phase 2–3 整句 `SQL.convert` 入口与 `SqlSchemaConvertOptions`/`generateOracleSequence`、完整函数改写清单（含 `CONVERT USING charset` 不误映射、`DATEADD`/`GETDATE`/`FROM_UNIXTIME`、`UCASE`/`LPAD`/`CEIL`/`YEAR` 等）、`SQL.convertBatch` 与 `ALTER` 处理、真库回归命令块、`SqlDialectSpec` 扩展、`ConversionResult.sqlWithExtras()` 与 `SqlSchemaConverterProvider.registerFunctions`、类型别名/函数扩展指引。

### 修复

- `jkit-core`：`com.alianga.jkit.html` 的 `script` / `style` / `textarea` 结束标签查找是 O(标签数 × 文档长度)——每次查找都对整篇文档做一次 `toLowerCase()` 再 `indexOf`。合成页面只有 1 个 `script` 时看不出来，真实页面（含 24 个 `script` 的 70 KB 首页）解析耗时 3.929 ms，比 Jsoup 慢 14 倍。改为逐字符扫描 + `regionMatches` 忽略大小写比较，零分配；同一页面 3.929 ms → 0.179 ms（快 21 倍），合成大页面解析也由 1.775 ms 降到 1.060 ms。结束标签的大小写、`</script >` 这类带空白写法、以及内容里的 `<` 均由 `rawTextCloseTagIsCaseInsensitive` 回归覆盖。
- `jkit-core`：`com.alianga.jkit.html` 的 `pre` 上下文没有向上传递保留空白。`pre > code` 里的换行会被折叠成空格（`<pre><code>int a = 1;\n    int b = 2;</code></pre>` 的 `text()` 得到 `int a = 1; int b = 2;`），代码块抽取结果失真。现在与 Jsoup 一致：从父元素逐级向上，命中 `pre` / `textarea` 等即保留原始空白，中途遇到非行内元素则不再保留——所以 `pre > code > span` 保留而 `pre > div > code` 不保留。由 `preContextKeepsNewlines` 回归覆盖。
- `jkit-core`：JSON Schema 的 `required` 关键字此前只被解析、从未参与校验——按标准写法声明的必填字段缺失时 `validateSuccess` 仍返回 `true`，是静默失效。`JSONNode#validateSchemaObject` 现在校验 `required` 列出的字段是否存在（标准语义：只要求字段存在，值为 `null` 也算存在；类型判定仍由 `type` 负责）；`required` 无需配合 `properties` 也能生效，嵌套对象同样覆盖。本库自有的 `must` 语义不变（要求字段存在且值不为 `null`），两者混用时都要满足。
- `jkit-sql`：回写不再丢表达式括号。`(a - b) / c` 此前回写成 `a - b / c`、`-(a + b)` 回写成 `-a + b`、`ROUND((SELECT …), 2)` 回写成 `ROUND(SELECT …, 2)`——求值顺序被改，函数参数里的子查询还会写成非法 SQL。parser 现在保留源文括号标记，函数参数中的标量子查询照常带括号。复杂 SQL 语料 1200 条里 172 条受影响；L2 文本保真率由 45.75% 升到 91.83%，并新增「有效括号不减少」门禁（折叠 `(col)` 这类冗余原子括号不算丢失）。
- `jkit-sql`：经典 Oracle 对带 `ORDER BY` 的 UNION / INTERSECT / EXCEPT / MINUS 做 ROWNUM 分页时，先包成 `SELECT * FROM (set-op) ORDER BY …` 再套 ROWNUM，避免子查询里对集合运算列别名排序报 `ORA-00904`。双层包装外层只投影原查询列，不再把中间层的 `RN` 输出给调用方（原查询为 `SELECT *` 时仍会带出 `RN`）。
- `jkit-sql`：`SqlNode.toString()` 默认按 MySQL 回写标识符引号（反引号），与 `SQL.toSqlString` 一致；不再误用 ANSI 双引号。`addComment("正文")` / 紧凑模式下的 `--` 行注释会包成合法块注释，避免把后续 SQL 拼成普通文本或整句注释掉。`addHint` 对未包装的正文补 slash-star-plus。跨方言仍用 `SQL.toSqlString(stmt, dialect)`。
- `jkit-sql-auto`：`SqlAutoInspector` 判断表是否存在时补上 schema。未配置时从 `Connection.getSchema()` 取；无连接（dry-run）或驱动不支持时从 JDBC URL 解析。PostgreSQL / Gauss 缺省 `public`，SQL Server 缺省 `dbo`，Oracle / 达梦回落用户名。避免把其它 schema 下的同名表误判为已存在，或对本库缺失表发出 ALTER。
- `jkit-curl-codegen`：curl 解析告警（如 `未支持的选项 --digest，已跳过`）此前在生成路径全部丢失，现合入 `GeneratedCode.notes()` 最前面，生成结果不再静默吞掉提示。
- `jkit-curl-codegen`：生成器不再静默丢内容。`py-requests` multipart 真实生成 `files=` 并传入请求（此前文件上传整体消失）；`py-httpx` 补 multipart / 文件正文；`js-fetch` / `js-axios` 的 `-T` 文件正文给出带路径的 fs 读取示例而非裸 `undefined`；`go-nethttp` / `csharp-httpclient` 真实生成 multipart（`mime/multipart` / `MultipartFormDataContent`）与文件正文（`os.Open` / `File.ReadAllBytes`）；`php-curl` multipart 生成 `CURLFile`。代理处理对齐：`py-requests` 不再硬编码 `http://`（带 scheme 与认证）；`java-okhttp` / `kotlin-okhttp` 补 `proxyAuthenticator`；`go-nethttp` 走 `http.Transport`；`csharp-httpclient` 走 `WebProxy`；`java-apache` 补 `setProxy`；`js-axios` 补 `proxy` 配置；`php-curl` 补 `CURLOPT_PROXY`；无法按请求配代理的 `js-fetch` / `js-native` 会在 notes 里说明。
- `jkit-curl-codegen`：`har` 生成器 `decode` 不再把 URL/表单里的字面 `+` 错变成空格（保留 `+`，`%20`/`%2B` 仍正常解码）；creator 版本号不再硬编码 `2.0.1`，jar 内读 `Implementation-Version`，开发环境回落当前版本。`CodeQuote` 的 JS/Python/Go/C#/Ruby/R 字符串转义补上 U+2028/U+2029 与其余控制字符（此前可生成非法 JS）；Rust/Swift 用 `\u{XXXX}`，Lua 用三位十进制 `\ddd`。
- `jkit-curl-codegen`：修复三处会把生成代码「打破」或注入的转义缺口。新增 `CodeQuote.kotlin()`（额外转义 `${`，Kotlin 模板插值此前会被求值，含 `${}` 的值直接让生成结果编译失败）、`CodeQuote.swiftEscape()`（Swift 字符串内容；此前 multipart 的字段名 / 文件名 / Content-Type 裸拼进字面量，含双引号即断串）、`CodeQuote.rName()`（R 反引号名；此前头名与 multipart 字段名裸拼，含反引号即解析失败）。
- `jkit-curl-codegen`：`kotlin-okhttp` 的正文构造改用 okhttp 4.x 扩展函数 `toMediaType()` / `toRequestBody()` / `asRequestBody()`。此前用的 `MediaType.parse` 在 Kotlin 下是 ERROR 级废弃、`RequestBody.create(MediaType, x)` 也已废弃，生成的代码根本编译不过；multipart 文件分片同时改用分片自带的 Content-Type，不再固定 `application/octet-stream`。
- `jkit-curl-codegen`：`-u` 的 basic 认证不再写两遍。`visibleHeaders()` 已注入 `Authorization`，而 `lua` / `httpie` / `php-guzzle` / `ruby-httparty` 又各自渲染一遍原生认证；新增 `CurlGenSupport.headersWithoutAuth()` 供这类生成器取头。`lua` 原先还会额外写进一个 luasocket 并不认识的 `authentication` 选项，已去掉。
- `jkit-curl-codegen`：`-k` 真正生成忽略证书校验的代码。`java-okhttp` / `kotlin-okhttp` 此前只加一行注释（Java 版注释还写着「已生成」但并没生成），生成的代码仍严格校验证书；现按各自语言生成 trust-all `X509TrustManager` 并放行主机名校验，同时保留「仅用于开发环境」提示。
- `jkit-curl-codegen`：`swift-urlsession` 补 `#if canImport(FoundationNetworking)`，Linux 上不再因缺少该 import 而编译失败。
- `jkit-curl-codegen`：`httpie` 命令补 `--ignore-stdin`。非交互执行（脚本 / 管道）时 HTTPie 会把 stdin 当请求正文，与 `--raw` 同时出现就报 `Request body (from stdin, --raw or a file) and request data (key=value) cannot be mixed`，生成结果直接不可用。urlencoded 正文不再同时给语义冲突的 `--form` 与 `--raw`，只保留 `--raw`。
- `jkit-curl-codegen`：`csharp-httpclient` 补 `using System;`。项目关闭 `ImplicitUsings` 时生成的 `Console` / `Exception` 会编译不过。
- `jkit-core`：curl 解析多个 `-b`/`--cookie` 按 curl 语义用 `; ` 拼接（此前互相覆盖只留最后一个）；`-E` 与 `--cert` 一致按「忽略不参与请求构造」处理（此前报「未支持」）。
- `jkit-core`：`HttpUtils` 调试日志与「total timeout exceeded」异常里的 URL 保留完整结构但敏感字段打码——`access_token`/`token`/`sign`/`signature`/`secret`/`key` 等查询参数值，以及 Telegram `/bot<token>/`、Server酱 `/<SendKey>.send` 路径令牌；新增公开方法 `HttpIo.maskUrl`。钉钉/Telegram/Server酱/阿里云短信等凭证不再随日志或异常落盘。
- `jkit-notify`：SMTP 修复四处——收件人/发件人/Reply-To 不再允许 CR/LF（配置层剥除 + 渠道层拒绝），杜绝向 MIME 头与 SMTP 命令注入；DATA 阶段真正执行 RFC 5321 dot-stuffing（此前 `dotStuff()` 存在但未接入发送路径）；EHLO 声明时优先 `AUTH PLAIN`（此前只会 `AUTH LOGIN`）；单个 RCPT 被拒不再中断整封邮件，全部拒绝才算失败，部分被拒在结果的 response 里列出。
- `jkit-notify`：新增 `NotificationChannel.validate(ChannelConfig)`（默认空实现）。`NotificationManager` 在任何网络发送前调用，`sendAll` / `sendFailover` 恢复「编程错误不会部分发送」的契约——此前缺 webhook/token 会在前一渠道真实发出后才抛。短信与 ntfy 渠道的 URL 构造依赖消息，改用探针消息预检。
- `jkit-notify`：`Message` 新增 `copy()`；短信渠道逐号码发送基于副本注入当前收件人，不再把 `smsReceiver` 写回污染调用方消息，同一消息并发发多个短信渠道也不会互相覆盖收件人。
- `jkit-notify`：`SendResult` 聚合时本地抑制（SUPPRESSED）按最轻级别处理，不再盖过限流/配置错误成为聚合结果的最严重类别。
- `jkit-notify`：`Attachment.contentBytes()` 文件附件按块读取，不再按声明长度一次性分配（大文件直接 OOM）；超过 2GB 快速失败并提示改用 `openStream()`。
- `jkit-notify`：渠道注册表 `register/unregister/get/list` 加同步，并发读写不再可能丢渠道或抛 `ConcurrentModificationException`；`NotifyPolicy` 去重占位与限流计数前移到 `beforeSend` 原子完成（`afterAttempt` 保留兼容、不再重复记账），并发下同一条消息不会同时通过去重检查、也不会放行超额消息；默认异步线程池改为 8 线程 + 1000 有界队列 + CallerRunsPolicy 背压，不再无限积压。
- `jkit-notify`：SPI 加载逐 provider 容错，单个扩展渠道损坏（缺依赖 / 构造抛异常）只告警跳过，不再让整个模块以 `ExceptionInInitializerError` 崩掉。
- `jkit-notify-extra`：阿里云短信 `RegionId` 跟随 `CFG_REGION`（可配地域），不再硬编码 `cn-hangzhou`。
- `jkit-core`：表达式求值器 `com.alianga.jkit.expression` 修复两处正确性问题。`&&` / `||` 此前**不做短路求值**——进入运算符分派前就急切算出了右操作数，导致 `true || (1/0)`、`false && (1/0)` 这类本应短路的表达式反而抛除零异常，右操作数的副作用也无法被跳过；现在 `ExprEvaluator` 在左操作数已能决定结果时直接返回、不再计算右操作数，与 Java 语义一致。`>` / `<` / `>=` / `<=` 此前硬性按 `Number` 转型，字符串比较直接抛异常；改为数字按数值、其余 `Comparable` 按自然序（如字符串字典序）比较，类型不可比时给出清晰错误。新增 `ExpressionRegressionTest`（23 例）固化短路、字符串关系比较、运算符优先级、类型强制、内置函数与三元等语义——该包此前零测试。
- `jkit-core`：`com.alianga.jkit.beans` 修复两处正确性问题。`BeanUtils.copyProperties(srcMap, tgtMap, excludeFields)` 带忽略字段时，循环把源值写回了**源 Map** 而非目标 Map，导致目标 Map 完全没被更新（源反而被原地重写）；改为写入目标 Map，与不带 `excludeFields` 的 `putAll` 分支行为一致。`ObjectUtils.set(collection, "[n]", value)` 此前即便下标赋值成功也会在设完元素后**无条件抛 `TypeNotMatchExecption`**，使集合下标赋值不可用；改为仅当 key 不是 `[n]` 形态时才抛错。新增 `BeansTest`（17 例）覆盖四向拷贝、merge、集合下标赋值、多级路径、非空字段与 `isEmpty` 等语义——该包此前零测试。

## 2.0.1 - 2026-09-13

仓库拆成多模块。本版本随父 POM 一并交付 `jkit-sql`、`jkit-sql-auto`（及 Spring Boot 2 / 3 starter）、`jkit-notify`、`jkit-notify-extra`、`jkit-curl-codegen`。坐标 `com.alianga:jkit` 仍指向 `jkit-core`。

### 新增

**jkit-sql**

- 新模块 `com.alianga:jkit-sql`：零依赖手写 SQL 解析器（词法 `char[]` + 关键字开地址哈希，递归下降 AST）。入口 `SQL.parse` / `parseAll` / `parseExpr` / `format` / `toSqlString` / `tables` / `stat` / `parameters` / `parameterize` / `exportParameterValues` / `wall` / `clone` / `eval` / `rewrite`。非法 SQL 抛 `SqlParseException`。用法见 [`docs/sql.md`](/sql)。
- **方言**：一等枚举 `MYSQL`（默认）、`POSTGRES`、`ORACLE`（12c 以下 ROWNUM 分页）、`ORACLE12`（`OFFSET/FETCH`）、`SQLSERVER`、`ANSI`、`H2`、`DB2`、`SQLITE`、`HIVE`（别名 `maxcompute`/`odps`/`argo`）、`CLICKHOUSE`、`PRESTO`（别名 `trino`）、`DAMENG`（`dm`/`dameng`/`jdbc:dm:`）。`fromName` 覆盖 GoldenDB / SelectDB / AnalyticDB / MatrixOne / StoneDB / HighGo / UXDB / MogDB / Vastbase / AntDB / IvorySQL / GBase 8a / GBase 8s / 行云 / Oscar 等别名，与 icell `common-model` 的 SQL 型数据源对齐。能力由 `SqlDialectSpec` 驱动，可用 `SqlDialectWrapper` 按需覆写单条能力（如 MySQL `ANSI_QUOTES`）。
- **分页**：`getLimit` / `getOffset` / `setLimit` / `setOffset` / `setPage` / `addLimit` / `SQL.adaptPagination`。`format` / `toSqlString` 按目标方言适配（MySQL `LIMIT` ↔ 经典 ORACLE ROWNUM ↔ ORACLE12 `OFFSET FETCH` ↔ SQL Server `TOP` | `OFFSET FETCH`）。`SqlBuilder.limit` / `offset` 按有效方言生成，不再一律输出 `LIMIT`。
- **改写链**：`SqlRewriteHook` + `SqlRewrites`（`addLimit` / `setPage` / `andWhere` / `replaceTable` / `replaceColumn` / `adaptPagination` / `addSelectItem` / `removeSelectItem` 可与自定义规则混排）+ `SQL.rewrite`（先深拷贝再改）。跨方言函数改写覆盖 JOIN ON / MERGE / OVER / 列 `DEFAULT`：`DATE_ADD` / `DATEDIFF` / `FROM_UNIXTIME` / `UNIX_TIMESTAMP` / `SUBSTRING` / `LEFT` / `RIGHT` / `DECODE` / `NVL2` / `UCASE`/`LCASE` / `CONCAT_WS` / `LPAD`/`RPAD` / `YEAR`/`MONTH`/`DAY`/`HOUR`/`MINUTE`/`SECOND` / `SYSDATE` / `LAST_DAY` / `CHAR`/`CHR` 等；FULLTEXT 升为 `MANUAL_ACTION_REQUIRED`。函数改写走 `SqlSchemaConverterProvider.registerFunctions`（SPI 后覆盖）。
- **实体 DDL**：`SqlEntities` 扫描 `@SqlTable` / JPA / MyBatis-Plus / MyBatis `@Alias`（无第三方编译依赖）。公开 `columnSql` / `columnTypeSql` / `createIndex` / `orderByForeignKeys` / `extraSql` / `sequenceSql` / `indexName` / `createTable(..., includeIndexes)`。认任意简单名为 `Comment` 的注解与 Hibernate `@ColumnDefault`；`@SqlTable(comment)` / `@SqlColumn(comment)` 按方言生成注释；Oracle ≤11g 无 IDENTITY 时附录 SEQUENCE + TRIGGER。
- **格式化与解析选项**：`SqlFormatOptions.keywordCase`（`AS_IS` / `UPPER` / `LOWER`）、`quoteIdentifiers`；`SqlParseOptions.pipesAsConcat` / `keepComments` / `placeholders()`（模板占位符）/ `statementParsers()`（按前导关键字挂自定义语句解析器）。
- **安全与扩展**：`SQL.wall` → `SqlWallResult`；`SqlWallRule` 规则链 + `SqlWallConfig.rules(...)`（内置 selectOnly / denyDdl / 无 WHERE 写等，行为与违规码不变）。`SqlAstVisitor` 类型分发（不破坏 `SqlVisitorAdapter`）。
- **语句 AST**：`EXPLAIN` / `SET` / `COMMENT ON` / `SHOW` / `ANALYZE|VACUUM|OPTIMIZE` / 事务控制 / `COPY` / `FLUSH` / `START TRANSACTION` / `LOAD DATA` / `LOCK TABLES` / `PREPARE` / `BEGIN…END` / 过程·触发器·事件（`SqlRoutineParam` / `SqlControlStatement` / `SqlDeclareStatement` / `SqlHandlerStatement`）升为独立类型；`MODEL` / `MATCH_RECOGNIZE` 结构化；`RENAME TABLE` 独立语句。
- **SqlBuilder**：流式 SELECT / INSERT / UPDATE / DELETE，`join` / `leftJoin` / `rightJoin` / `fullJoin` / `crossJoin` / `union` / `with` / `distinct` / `groupBy` / `having`。
- **解析覆盖**：相对 Druid BVT / JSqlParser 内联 / 文件语料约 **96.3% / 92.3% / 90.0%**。窗口、CTE、MERGE、PIVOT、闪回、过程块、Hive / ClickHouse / Informix / DB2 等边角语法见 [`docs/sql.md`](/sql)，不在此按迭代轮次罗列。

**jkit-sql-auto**

- 新模块 `com.alianga:jkit-sql-auto`：启动时按实体自动建表 / 更新表结构。扫描 `@SqlTable` / JPA / MyBatis-Plus，对照 `DatabaseMetaData` 执行 `CREATE TABLE` / `ALTER TABLE ADD` / `CREATE INDEX`。模式：`none` / `validate` / `update`（默认，只追加）/ `create` / `create-drop`。配置前缀 `jkit.sql.auto.*`，数据源可回落 `spring.datasource.*`。运行时零第三方依赖。
- `SqlAuto.drop` 按外键逆序删托管表；Spring Boot 2 / 3 自动配置模块 `jkit-sql-auto-spring-boot-2`、`jkit-sql-auto-spring-boot-3`。
- 表 / 列注释按方言执行（`COMMENT` / `COMMENT ON` / `sp_addextendedproperty`）；Oracle ≤11g 自增主键用 SEQUENCE + TRIGGER，达梦列上写 IDENTITY。
- `postgresIdentityStyle` / `foreignKeys` / `autoIncrement`：给 OpenGauss / GBase 8a / DuckDB 等能力较窄的产品关掉对应 DDL。
- `table-prefix`（`jkit.sql.auto.table-prefix` / 链式 `tablePrefix(String)`）：所有自动建表名统一加前缀（如 `t_`），作用于建表 / 改表 / 删表 / 索引 / 序列 / 外键目标表。
- `index-prefix-enabled`（`jkit.sql.auto.index-prefix-enabled`，默认 `true`）：自动派生的索引名是否也带表前缀；实体里显式 `@Index(name=…)` 始终原样保留。

**jkit-notify**

- 新模块 `com.alianga:jkit-notify`：钉钉（含加签）/ 企微 / 飞书（含签名）/ Server酱 / Bark / 通用 Webhook / SMTP（纯 Socket：AUTH LOGIN + STARTTLS/SSL + MIME）。`NotificationChannel` SPI + `NotificationManager`；`MessageType`（TEXT / MARKDOWN / HTML）。零第三方依赖。用法见 [`docs/notify.md`](/notify)。
- 失败分类：`SendResult.failureType()` / `isRetryable()`，`FailureType` 为 RETRYABLE / THROTTLED / CONFIG_ERROR / PERMANENT / SUPPRESSED；未收录错误码回退 HTTP 状态。自定义渠道覆写 `AbstractHttpChannel.classify`。
- 消息按 UTF-8 **字节**截断（不劈开汉字与 emoji）；钉钉 @人自动把缺失的 `@手机号` 追加进正文；`Message.var` / `vars` 替换 `${key}` / `${a.b}`；附件流式读取、按 `10MB`/`512KB` 拆包、自动识别 MIME。
- SMTP：`sslProtocols` 钉扎 TLS 版本、`trustAllCerts` 支持自签网关；MARKDOWN 经 `NotifyUtils.markdownToHtml` 按 `text/html` 发送。飞书加签写入 JSON 请求体。`NotifyPolicy`：静默时段、5 分钟去重、本地限流；`sendFailover` 多账号顺序切换。
- 可选模块 `com.alianga:jkit-notify-extra`：Slack / Telegram / ntfy / 阿里云 / 腾讯云 / 云片 / 华为云短信。渠道 extras 在各自实现类上，不放进核心 `Message`。

**jkit-core**

- `DateUtils.parse(String)`：自动识别时间戳、紧凑数字、`-` `/` `.`、中文/韩文、ISO-8601（含 `T`/`Z`/`+0800`/`+08:00`）。
- `DateUtils.fromEpochNumber(long)`：10 位秒或 13 位毫秒时间戳转 `Date`。
- `DateUtils.fromTemporal(TemporalAccessor)`：`java.time` 时间对象转 `Date`。
- `ConvertUtils.toDate` 额外支持 `Instant`、`OffsetDateTime`、`ZonedDateTime`。
- `HttpUtils.debug` / `HttpUtils.printCurl`：发送前把请求摘要或等价 curl 打到标准输出（不要在生产打开）。
- `ConfigLoadOptions.addLocation`：在默认搜索路径上追加目录（`classpath:` / `file:` / 裸路径 / `File`，`~` 展开为 `user.home`）。
- `Print.enableLog`：为 `false` 时只打控制台，不再写入 JUL。
- `com.alianga.jkit.log.LocaleFormatter`：固定 Locale 的 JUL 格式化器。

**jkit-curl-codegen**

- 从 jkit 拆出独立 artifact `com.alianga:jkit-curl-codegen`，入口 `CurlCodegen.generate(id, curl)`。覆盖 34 种目标：Java（jkit / JDK 11+ / OkHttp / Apache 5 / HttpURLConnection / Unirest）、Kotlin、JavaScript（fetch / axios / request / jQuery / XHR 等）、Python（requests / httpx）、Go、C#、PHP、R、Rust、Swift、Ruby、Lua、PowerShell、curl（含 Windows cmd / PowerShell）、wget，以及 `http` / `har` / `httpie` 互操作格式。

### 变更

- `jkit-sql`：**破坏性** — `SQL.andWhere` / `replaceTable` / `replaceColumn` 改为与 `addLimit` / `setPage` 一致的 clone-then-mutate（返回新 AST，不污染原树）；调用方须使用返回值。
- `jkit-sql`：`SQL.clone` 改为真正的 AST 树拷贝（`SqlAstCloner` / `SqlNode.copy`），热路径不再 format→parse；跨方言 `format` / `adaptPagination` / `setPage` 显著加速。经典 ORACLE offset=0 单层 ROWNUM 包装免 clone。
- `jkit-sql-auto`：`dryRun(true)` 的 `SqlAuto.run(options)` / `plan(options)` 不再打开 JDBC / DataSource。URL 仅用于推断方言，按空库规划全量 `CREATE TABLE`。已传入 `Connection` 的重载仍对照活表，只是不执行。
- `jkit-core`：`DateUtils` 去掉 `SimpleDateFormat`。`yyyy-MM-dd HH:mm:ss` 走 `char[]` + 秒级缓存，其余常见 pattern 走手写拼接或 `DateTimeFormatter`。`ConvertUtils.toDate(Object)` 改为按数字字段抽取。常见字符串解析约快一个数量级，结果与 ZmlTools 对齐。
- `jkit-core`：JUL 默认格式改为英文级别名（`WARNING` / `SEVERE`），不再随 JVM 默认语言变化。
- `jkit-core`：`RandomUtils.randomBirth` 改为 `LocalDate`（`minAge == maxAge` 不再抛异常）；`getUUID` 去 `-` 不再走正则；`IdCardUtils` 随机生日按当月实际天数生成。
- `jkit-core`：curl 解析对齐 curl 语义（展开 `-kLs`/`-XPOST`，`--json` / `--data-urlencode`，`@file` 不再当字面正文）。公开 API：`parseCurl` / `curlToRequest` / `curl` / `curlString` / `requestToCurl`，模型 `ParsedCurlRequest`。`CurlParser.generate` 与 `http.codegen` 包从 jkit 移除，改依赖 `jkit-curl-codegen`。
- `jkit-notify`：核心 `ChannelConfig` 移除 SMS 专用 `template` / `appId` / `region`，改用 `ChannelConfig.extra` + `AbstractSmsChannel.CFG_*`。核心渠道仅由 `defaults()` 注册，SPI 只加载 extra 模块，避免双重注册。
- `jkit-notify-extra`：`SlackChannel.EXTRA_CHANNEL` 替代用 `Message.EXTRA_GROUP` 表示 Slack 频道（短暂回落兼容 `EXTRA_GROUP`）。

### 修复

- `jkit-core`：响应声明 `Content-Encoding: gzip` 但正文为空时，JDK 8 不再抛 `EOFException`，返回空流（关闭时仍回收底层连接）。
- `jkit-sql`：子类与 `MappedSuperclass` / 父类重复声明同名列时只保留子类字段，避免 PostgreSQL `column specified more than once`。
- `jkit-sql`：`@GeneratedValue(generator="system-uuid")` / `GenerationType.UUID` / 非整数 `@SqlGenerated` 不再生成 `AUTO_INCREMENT`/`IDENTITY`（PostgreSQL 对 `VARCHAR` 主键写 IDENTITY 会语法错误）。
- `jkit-sql`：自动生成的索引名 / 序列名 / 触发器名按方言标识符长度上限截断（经典 Oracle 30 字符，超长时保留前缀 + 4 位散列）。未命名索引改为 `{table}_{col}_idx`，同表多个不再撞名。公开 `SqlDialectSpec.maxIdentifierLength` / `fitIdentifier`。
- `jkit-sql`：`SQL.format` pretty 模式对 `CREATE TABLE` 按列换行缩进（`toSqlString` / compact 仍单行）。
- `jkit-sql`：`GROUP_CONCAT` 无显式 `SEPARATOR` 时转 `STRING_AGG`/`LISTAGG` 不再丢分隔符；MySQL `MODIFY`/`CHANGE` 转 PG/ANSI/H2/PRESTO 时不再把列名写重。
- `jkit-sql`：`SUBSTRING` / `LEFT` / `RIGHT` 按方言回写（PG/MySQL/H2 用 `FROM n FOR m`，SQL Server/SQLite/Hive/ClickHouse 用逗号形态；负起点按 `LENGTH/LEN` 改写）；达梦裸 SELECT 补 `FROM dual`。
- `jkit-sql`：`SqlDialectWrapper` 不再委托派生方法（`identQuoteClose` / `quoteIdent` / `pipesAreConcat` / `preferredLimitStyle`），子类只覆写原语时派生能力跟着变。
- `jkit-sql`：回写保真 — `NATURAL JOIN` 不再丢修饰符；`RENAME TABLE` 不再写成非法 `ALTER TABLE`；`ADD UNIQUE KEY` 不再丢 `UNIQUE`；`INSERT/UPDATE/DELETE` 的 `IGNORE` / `LOW_PRIORITY` / `HIGH_PRIORITY` 入 AST 并回写；非裸标识符别名强制加引号。
- `jkit-sql`：`tables()` 补漏 `CREATE TRIGGER` / `CREATE TABLE … LIKE` / `RENAME TO` / 多表 `OPTIMIZE|ANALYZE`；库名、例程名、CTE 名不再误计。仅注释 / 空白输入归为 `OTHER`，不再抛 `empty SQL`。
- `jkit-sql-auto`：经典 Oracle 下自动生成的 `CREATE INDEX` 名超过 30 字符时截断，避免 ORA-00972。
- `jkit-curl-codegen`：R（httr2）改用 `req_perform()`；PowerShell 5.1 受限头 / Cookie 逗号 / 中文乱码；Windows curl 拆成 cmd（`shell-curl-windows`）与 PowerShell（`shell-curl-powershell`）两个生成器。

### 构建

- 仓库改为多模块：父 POM `com.alianga:jkit-parent`，运行时库在 `jkit-core`（发布坐标仍是 `com.alianga:jkit`），其余能力各一模块。根目录 `mvn test` 同时构建全部模块。
- `git-commit-id-plugin`、`buildnumber-maven-plugin`、`maven-source-plugin` 从 `publish` profile 挪到默认构建，`package` 即可产出带构建信息的 sources jar。
- 整条 reactor 可用 JDK 8 启动 Maven：其余模块按 JDK 8 编译；`jkit-core` 的 `META-INF/versions/9`、`/11` 经 toolchain 走 JDK 9 / 11；仅 `jkit-sql-auto-spring-boot-3` 经 toolchain 走 JDK 17（Spring Boot 3 要求）。依赖本机 `~/.m2/toolchains.xml` 已声明 jdk 8/9/11/17/21。启动示例：`JAVA_HOME=$(jdk8) mvn -o clean install`。
- `jkit-notify` 实发用例须 `-Djkit.notify.live=true` 且 yml 凭证齐全；默认 `mvn test` 即使有密钥也不实发。

### 文档

- `docs/sql.md` / `docs/en/sql.md` 按代码对齐跨方言分页、`SQL.clone`、`SqlRewrites`、`SqlEntities` 公开方法与方言表；两侧 API 覆盖已对齐。README 与模块 README 去掉内部开发计划入口。
- `docs/sql-auto.md`（及英文）按「选包 → 写实体 → Spring Boot / 非 Spring → 配置项」重排；模块 README、快速开始页同步。

## 2.0.0

jkit 首个对外版本，由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来：零第三方依赖、包名改为 `com.alianga.jkit`，坐标 `com.alianga:jkit:2.0.0`。

能力概要见 [README.md](https://github.com/zhengmingliang/jkit/blob/develop/README.md)：CSV / HTTP / JSON / YAML / 配置 / 表达式等均用纯 JDK 重写。从 ZmlTools 迁过来的项目可继续用 `relocated/zmltools` 把 `top.wuyongshi:ZmlTools:2.0.0` 重定向到本坐标（包名仍需手工替换）。

---

## 如何记录后续版本

1. 把根 `pom.xml`（`jkit-parent`）的 `<version>` 改成新版本号，子模块继承该版本；同步 `README.md` 页头与依赖示例、`Main.java` 打印的版本。
2. 在简介段落后、当前最新版本小节**上方**插入新版本，日期用当天（`YYYY-MM-DD`）。**同一版本号只有一个二级标题**；未发版前把新条目追加进该小节，不要再开 `## x.y.z / unreleased`。
3. 按「新增 / 变更 / 修复 / 构建」分类写清调用方能感知的变化（多模块时用加粗模块名分组）。不要只贴 git 标题，不要按内部里程碑或竞品覆盖率轮次记账（那些写 `docs/sql.md` / `docs/next-plan.md`）。
4. 本次新增的公开 API 在 javadoc 里补 `@since x.y.z`。
5. 历史版本留在原处，不要把旧条目改写成新版本。

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
