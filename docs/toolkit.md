# 其它工具模块

下面这些包没有单独长文，这里给出入口和典型用法。HTTP / JSON / YAML / 配置见对应文档。

## 文本编码检测

`EncodingDetect` 判断字节/文件的字符集：BOM、XML/HTML 的 charset 声明、ISO-2022，再验证 UTF-8；非法 UTF-8 时用解码后的汉字/假名/韩文比例区分 GBK、Big5、Shift_JIS、EUC-JP、EUC-KR。

```java
Charset cs = EncodingDetect.detect(file);
String name = EncodingDetect.getJavaEncode("/tmp/a.txt"); // 兼容旧 API
String text = EncodingDetect.decode(bytes);
```

`FileUtils.readTxtFile(file, null)` 会调用该检测。

## convert

`ConvertUtils`：对象到 String / int / long / double / boolean / BigDecimal / Date。`null` 或转换失败时用带默认值的重载。

```java
int n = ConvertUtils.toInt("12.9", 0);       // 12
boolean ok = ConvertUtils.toBoolean("YES");  // true
```

`ObjectWrapper`：把任意对象包一层，再按类型取值。兜底规则和 `ConvertUtils` 不一样——`getXxx()` **只在被包装对象是 `null` 时**返回零值（`""` / `0` / `0L` / `0.0` / `false`），对象非空但内容不是数字会直接抛 `NumberFormatException`。要容错就用 `getXxxOrDefault`，注意它同样只对 `null` 生效。

```java
ObjectWrapper w = new ObjectWrapper(map.get("age"));
int age = w.getIntegerOrDefault(18);              // null → 18；"abc" → NumberFormatException
String s = new ObjectWrapper(null).getString();   // ""
```

`GenericConverter<I, O>` 扩展 `Function<I, O>`：只实现 `apply`，就白得 `convert(I)`（入参为 `null` 时不调 `apply`，直接返回 `null`）和 `convert(List<I>)`（逐个转换成等长新列表，入参为 `null` 返回 `null`；列表内的 `null` 元素**仍会**传进 `apply`）。

```java
GenericConverter<String, Integer> conv = Integer::valueOf;
Integer one = conv.convert("1");
List<Integer> list = conv.convert(Arrays.asList("1", "2"));
```

## collection / math

- `Collections`、`Maps`、`ArrayUtils`、`Booleans`：空值安全的集合与 Map 操作。
- `Numbers`、`NumberUtils`：数字字符串、科学计数等。

```java
Map<String, Integer> map = Maps.newHashMap();
Collections.isEmpty(list);
```

`NumberUtils` 有两个**语义不同、不可互换**的数字判定：`isNumber(String)` 是逐字符判断，只认 `0-9`，所以 `-1`、`1.5`、`1e10` 都返回 `false`；`isParsableNumber(String)` 才是「能否当作 Java 数字字面量解析」，识别正负号、小数点、科学计数法、`0x` 十六进制与 `l/L/f/F/d/D` 后缀。两者对 `null` 和空串都返回 `false`。

- `CountMap<K>`：`HashMap<K, Integer>` 子类，`increment(key)` / `increment(key, step)` 累加（`step` 传负数就是减）。`get` 被覆写为「key 不存在返回 `0`」而不是 `null`，所以取出来能直接参与算术。
- `MultiKeyHashMap<K, V>`：`get` 先精确匹配，未命中再按 key 的小写形式回退查找；另有 `get(k1, k2)`、`get(k1, k2, keys...)` 依次返回第一个非空值，`put(k1, k2, value)` 和 `putValue(value, key, keys...)` 把同一个值挂到多个 key 上（都会登记小写索引）。一个坑：命中判断依据是「值非 null」，所以存了 `null` 值的 key 会被当作没命中，继续走回退查找。
- `ArrayStack` / `Buffer`：`Buffer` 是「按确定顺序取元素」的集合接口（`get()` 取不删、`remove()` 取并删）；`ArrayStack` 是它的 LIFO 实现，继承 `ArrayList`，**非泛型也非线程安全**。空栈时两套方法抛的异常不同：`peek()` / `pop()` 抛 `EmptyStackException`，接口的 `get()` / `remove()` 抛 `java.nio.BufferUnderflowException`。`search(obj)` 返回从栈顶算起的 1-based 距离，找不到返回 `-1`。

```java
CountMap<String> counter = new CountMap<>();
counter.increment("a");
int a = counter.get("a");   // 1
int b = counter.get("b");   // 0，不是 null

ArrayStack stack = new ArrayStack();
stack.push("x");
Object top = stack.pop();
```

`math.Number` 是 `BigDecimal` 的子类，补了 `add(String)`、`add(String...)`（把多个数依次累加，返回累加结果）、`toScaleString(scale[, roundingMode])`（按指定小数位输出，不带科学计数法）、`valueOf(BigDecimal)`，以及带 scale 的构造器 `new Number(val, scale)`。注意 `BigDecimal` 本身不可变，这些方法都是**返回新值而不改当前对象**；`setScale` 在需要舍入却没给 `RoundingMode` 时会抛 `ArithmeticException`。

## log

`com.alianga.jkit.log.Log` 基于 `java.util.logging`，`{}` 占位符，最后一个参数若是 `Throwable` 会打堆栈。

```java
private static final Log log = Log.get(Foo.class);
log.info("user={} id={}", name, id);
log.error("读写失败", ex);
```

## valid

`Preconditions`、`Verify`、`Assert`：参数和状态检查，失败抛对应异常。和 JUnit `Assert` 不是一类东西。

```java
Preconditions.checkNotNull(arg, "arg");
Verify.verify(n > 0, "n must be positive");
```

## crypto

`AESCrypt` / `DESCrypt` / `EncryptUtils`：对称加解密，兼容旧项目。密钥、IV、模式由调用方负责。`Hash64` 是 FNV-1a，只适合缓存 key / 分片，不能当密码哈希。

```java
long digest = Hash64.hash("cache-key");
byte[] out = AESCrypt.encrypt(plain, key16);
```

摘要类方法（`md5`、`sha1`、`sha256`、`sha512`、`sha256_HMAC`）输出**小写** 16 进制，底层统一走 `ByteUtils.toHexStringLower`。

`Crypt` 是加解密抽象基类：`byte[]` 和流两套 `encrypt` / `decrypt`，都可以额外传一个 `CryptListener` 观察进度（`onRunning` 返回 `false` 即中断，缓冲区 `BUFFER_SIZE` 为 4KB）。`CipherCrpyt` 用 JDK `Cipher` 做流式实现，`AESCrypt`、`DESCrypt` 是它的子类。要注意**构造实例走的是 CBC**（AES 需 16/24/32 字节 key + 16 字节 IV），而静态的 `encrypt(data, key)` / `decrypt(data, key)` **走的是 ECB**，两条路径不通用。流式方法出错时抛的是 `RuntimeException(ex.getMessage())`，原始异常被吞掉。

```java
Crypt crypt = new AESCrypt(key16, iv16);   // CBC
byte[] enc = crypt.encrypt(plain);
crypt.decrypt(in, out);                    // 大文件走流；in 和 out 都会被关闭
```

流式重载**会关掉传入的两条流**（内部关闭 `CipherOutputStream` 时连带关闭 `outputData`，并显式 `close()` 了 `inputData`），别在 try-with-resources 外面复用它们。

`AwaruaTiger`：Tiger 摘要（192 位 / 24 字节），`computeHash(bytes)` 一次算完整个数组并自动重置实例，因此同一实例可以重复调用；但实例带内部状态，**不是线程安全的**。只为 TTH（tiger tree hash）这类兼容场景保留，新代码用 SHA-256。

### ContextObfuscator：上下文派生的混淆包装

`ContextObfuscator.obfuscate(plain, contexts...)` / `deobfuscate(cipherBase64, contexts...)`，用于给已有密钥或短敏感数据再套一层包装，典型场景是避免在登录响应里明文下发传输用的 AES 密钥。

**先看清它是什么**：这**不是加密算法**，方法名之所以叫 `obfuscate` 而不是 `encrypt`，就是为了在调用点提醒这一点。它的目标是提高逆向门槛，不提供密码学强度——算法未经同行评审，规则终归可被还原；校验和不带密钥，只能发现意外损坏与上下文不匹配，**不是 MAC**，挡不住有意伪造。传输安全仍应依赖 HTTPS，静态存储安全应依赖不下发的独占密钥。别拿它替代 `AESCrypt`。

它能给的是：只抓到密文拿不到明文（还需还原算法并知道全部上下文）；每次调用掺入随机 salt，相同明文也产生不同密文，无法重放比对；上下文或密文任一被改动都会校验失配报错。

```java
byte[] transportKey = ...;
String wrapped = ContextObfuscator.obfuscate(transportKey, username, String.valueOf(timestamp));
byte[] restored = ContextObfuscator.deobfuscate(wrapped, username, String.valueOf(timestamp));
```

上下文**顺序敏感、个数敏感**，解包时必须逐项一致，其中的 `null` 元素按空字符串处理。不匹配、密文被篡改或长度不足都抛 `IllegalArgumentException`；空明文返回空字符串，空密文返回空数组。

密文格式为标准 Base64 的 `salt(8) + 异或密文(明文等长) + 校验和(4)`；密钥流以 `上下文以 "::" 连接 + "::" + salt + 内置 pepper` 为初始状态，每轮 64 次 SHA-256、摘要按字节翻转后拼接，并链式依赖上一轮输出。**这套格式有互操作实现（如前端 JS）依赖，改动即破坏兼容**，`ContextObfuscatorTest` 里的「黄金向量」用例专门锁定它。注意类中的 `PEPPER_PART_*` 是硬编码常量，与每次随机生成的 salt 是两回事，互操作实现必须使用完全相同的字节。

```java
byte[] tiger = new AwaruaTiger().computeHash(data);
```

## base64

`Base64Utils` 与 JDK 的 `java.util.Base64` **有意并存，不是重复实现**：

| 场景 | 用哪个 | 原因 |
| --- | --- | --- |
| 写入调用方已有缓冲区 / 从缓冲区范围解码 | `Base64Utils.encode(src, buf, off)`、`Base64Utils.decode(buf, from, len)` | 零拷贝，JDK 无等价 API；JSON 模块只用这几个方法 |
| 整块 `byte[]` ↔ `String` | `Base64Utils.encodeToString` / `decode(String)` | **入口内部按 JDK 版本自动选更快的实现**，调用方不用操心 |
| 宽松解码 / MIME 解码 | `Base64Utils.decodeLenient`、`decodeMime` | JDK 无对应宽松模式 |

整块转换哪个实现更快取决于 JDK 版本（JDK 陆续给 `java.util.Base64` 加了 JIT intrinsic，编码较早、解码较晚），所以 `Base64Utils` 的入口会自动选择。本机实测（Corretto 8/9/11/17 + GraalVM 21，固定循环数、充分预热、15 轮中位数），下表为 `JDK 实现 / 手写实现` 的吞吐比，>1 表示 JDK 更快：

| JDK | 整块编码 | 整块解码 |
| --- | --- | --- |
| 8 | 0.83~0.85（手写快 ~20%） | 0.59~0.61（手写快 1.6~1.7 倍） |
| 9 | 1.07~1.14（JDK 快） | 0.61~0.68（手写快 1.5~1.6 倍） |
| 11 | 0.95~0.99（持平） | 1.42~1.50（JDK 快） |
| 17 | 1.10~1.13（JDK 快） | 1.29~1.43（JDK 快） |
| 21 | 1.94~4.06（JDK 快） | 4.07~4.85（JDK 快） |

于是阈值定为：**编码只在 JDK 8 用手写，解码在 JDK 11 以下用手写**。效果是入口在每个版本上都不落后于直接调 JDK——JDK 8 上编码快约 26%、解码快约 67%，JDK 9 上解码快约 63%，JDK 11 及以上与 JDK 持平（因为就是它）。

切换成立的前提是两条实现完全等价，这一点由 `Base64UtilsEquivalenceTest` 守住：覆盖 0~200 全部长度、全部单字节取值、256KB 大负载、15 种非法输入（要求公开入口与手写实现**都抛或都不抛**同类异常），并在 JDK 8/9/11/17/21 上全部跑过。

```java
String s = EncryptUtils.base64Encode("中文");   // 按 UTF-8 取字节，内部走 Base64Utils
byte[] raw = EncryptUtils.base64Decode(s);
String dataUri = Base64Utils.encodeFileWithPrefix(file);
```

### 严格解码 vs 宽松解码

`decode` 系列**要求显式补位**（长度必须是 4 的整数倍），不补位的输入会抛 `IllegalArgumentException`。
需要接受不补位的数据时用 `decodeLenient`，需要忽略折行时用 `decodeMime`：

```java
Base64Utils.decode("aGVsbG8");        // 抛 IllegalArgumentException
Base64Utils.decodeLenient("aGVsbG8"); // 正常解出 "hello"
Base64Utils.decodeMime("aGVsbG8g\r\nd29ybGQ=");
```

这个分工是刻意保留的，**不建议把 `decode` 改成宽松**，原因有两条：

1. `decode(byte[], int, int)` 的算法用 `n = len >> 2` 推导输出长度，整段实现都建立在「长度是 4 的倍数」这个前提上。
   只去掉长度校验不会变宽松，而是会**静默丢掉末尾 2~3 个字符**——例如 `"abc"` 本应解出 2 字节，
   实际会返回空数组。要真正支持不补位必须补一套余数处理，而这正是 `decodeLenient` 已经做的事。
2. 这几个方法是 JSON 反序列化的热路径（`JSONTypeDeserializer` 直接从解析缓冲区的一段范围解码）。
   严格模式让损坏的 base64 字段立刻报错；改成宽松后会退化成静默产出更短的字节数组，
   等于把「解析失败」变成「数据悄悄少了一截」。jkit 自己的序列化端始终输出带补位的 base64，
   所以放宽只对读取外部非规范数据有意义，代价却是丢掉这层校验。

### 判定是否为 base64

```java
Base64Utils.isBase64Format(text);  // 格式校验：能否被 decode 解开
Base64Utils.isBase64(text);        // 启发式：是否是「可打印 ASCII 文本」的规范 base64
```

两者用途不同，**别用错**：

| | `isBase64Format` | `isBase64` |
| --- | --- | --- |
| 判断依据 | 能否被 `decode` 解开 | 解码后全为可打印 ASCII（32~126），且重新编码与原串相同 |
| 二进制数据的 base64 | `true` | **`false`** |
| 含中文 / 换行的文本 | `true` | `false` |
| 非规范形式（不补位、MIME 折行） | `false` | `false` |

`isBase64` 迁移自 common-model 的 `com.dtsz.cm.utils.Base64Utils`，行为与原实现逐例一致。它是**启发式**判断，
两个已知局限要清楚：图片等二进制内容的 base64 会返回 `false`；而本身形似 base64 的普通文本无法区分——
`"MTIzNDU2"` 解码为 `"123456"` 且能原样编码回去，会被判为 `true`。
想要的是「这串东西是不是合法 base64」就用 `isBase64Format`。

另外两点行为需要注意：

- `Base64Utils.encodeString` / `decodeString` 按 **UTF-8** 处理字符串（此前用平台默认编码，跨平台结果不一致）。
- `Base64Utils.encodeFileWithPrefix` 按文件**实际 MIME 类型**拼 data URI 前缀；此前对任意文件都硬编码 `data:image/png;base64,`，非 PNG 文件会得到错误前缀。

## image

`SpecCaptcha`、`GifCaptcha`：验证码图片，依赖 `java.awt`（无桌面环境需 `-Djava.awt.headless=true`）。

两者的抽象基类是 `Captcha`（继承 `Randoms`），可调 `setLen`（字符数，默认 5）、`setWidth`（150）、`setHeight`（40）、`setFont`，子类实现 `out(OutputStream)`。**随机字符是在 `out()` 里生成的，所以 `text()` 必须在 `out()` 之后调**，先取 `text()` 只会拿到 `null`。另外两个子类对流的处理不一致：`SpecCaptcha.out` 只 `flush` 不关流，`GifCaptcha.out` 在 `finally` 里会把传入的流**关掉**。

```java
SpecCaptcha captcha = new SpecCaptcha(150, 40, 4);
captcha.out(response.getOutputStream());
String answer = captcha.text();   // 必须在 out() 之后
```

- `Randoms`：验证码用的随机源，字符表 `ALPHA` 已剔除易混淆字符（`0`、`1`、`O`、`I`、`l`），配 `alpha()`、`num(bound)`、`num(min, max)`；底层是 `SecureRandom`。`num(min, max)` 是**左闭右开** `[min, max)`，且 `max <= min` 会抛异常。
- `GifEncoder`：`GifCaptcha` 用的 GIF 编码器，也能单独拼动图——`start(os)` → 多次 `addFrame(image)` → `finish()`，配合 `setDelay(ms)` 或 `setFrameRate(fps)`、`setRepeat(0)`（0 = 无限循环，默认 1 次，且必须在第一帧之前设置）、`setQuality`、`setTransparent`、`setSize`。`start(OutputStream)` 不会关流，只有 `start(String file)` 才会在 `finish()` 时关闭；`getFrameByteArray()` 仅当输出流是 `ByteArrayOutputStream` 时可用，否则抛 `ClassCastException`。

```java
GifEncoder encoder = new GifEncoder();
encoder.start(out);
encoder.setDelay(100);
encoder.setRepeat(0);
encoder.addFrame(frame1);
encoder.finish();
```

## thread

`ExecutorServiceUtil`：创建、等待、关闭线程池，避免直接 `shutdownNow` 漏任务。产出的线程是 daemon，命名前缀 `alianga-`。

`execute(Runnable)` 与 `submit(Runnable)` / `submit(Callable)` 走同一个进程内共享的默认池（借类初始化完成懒加载，不存在并发建多个池的问题）；需要拿执行结果或取消任务时用 `submit`。`sleep(millis)` 被中断时返回 `false` 并**会恢复中断位**，调用方可以继续用 `Thread.currentThread().isInterrupted()` 判断。默认线程工厂是 `getDefaultThreadFactory()`。

## reflect / jdk

`ReflectionUtils`、`ClassStrucWrap`、`FieldAccessor`：getter/setter 元数据，JSON/YAML 绑定也用这一套。`jdk.UnsafeUtils`、`JdkApiAgent` 是性能路径，业务代码不要直接依赖。

读取单个属性/字段有两个入口，**规则不同，别用错**：

```java
// 按字段名精确匹配，逐级遍历父类，读到字段真实值
Object v1 = ReflectionUtils.getDeclaredFieldValue(bean, "name");
// 按 bean 属性名查带缓存的元数据，能读到只有 getter 的计算属性
Object v2 = ObjectUtils.getPropertyValue(bean, "name");
```

`getPropertyValue` 在存在同名字段时会直读字段，**不执行 getter 方法体**；只有没有对应字段的派生属性才会真正调用 getter。两者旧名都叫 `getObjectFieldValue`（同名同签名，极易误用），现保留为 `@Deprecated` 转调，请改用上面的新名字。

`GenericParameterizedType` 是泛型结构描述对象，JSON 的泛型绑定入口吃的就是它（`JSON.parse(json, genericType, readOptions...)`、`JSON.read(file, genericType, ...)`）。常用工厂：`actualType(Class)`（按类型缓存复用）、`collectionType(collectionClass, valueType)`（`valueType` 可以再套一层，实现嵌套泛型）、`mapType(...)`、`arrayType(componentType)`、`entityType(entityClass, genericClass)`（实体**只支持单个泛型且不能嵌套**），以及从反射 `Type` 直接来的 `of(Type)`。`of` 遇到不支持的类型或解析出异常时**返回 `null` 而不是抛异常**，调用方要判空。

```java
GenericParameterizedType<ArrayList> type =
        GenericParameterizedType.collectionType(ArrayList.class, User.class);
List<User> users = JSON.parse(json, type);
```

- `ReflectConsts`：类型分类表。`getClassCategory(cls)` 把类型归到 `ClassCategory` 的某一类（`CharSequence`、`NumberCategory`、`MapCategory`、`CollectionCategory`、`EnumCategory`、`ObjectCategory`、`NonInstance`…），序列化/反序列化按这个分派；内嵌的 `PrimitiveType` 枚举提供基本类型的包装类、数组长度、按下标读写元素。分类结果有缓存，上限 4096 个类型，超出后不再新增缓存条目（不影响判定结果）。
- `GetterInfo` / `SetterInfo` / `FieldInfo`：`ClassStrucWrap` 产出的属性元数据。`GetterInfo.invoke(target)` 读值、`SetterInfo.invoke(target, value)` 写值，另有 `getName()`、`getUnderlineName()`（下划线命名）、`getAnnotation(...)`、`getGenericParameterizedType()`、`isMethod()`（是方法还是字段）；`FieldInfo` 把同一属性的 getter/setter 成对暴露。业务代码通常只用到 `ClassStrucWrap.get(clazz).getGetterInfos()` / `getFieldInfos()` 这一层。
- `reflect.UnsafeHelper`：和上面的 `jdk.UnsafeUtils`、`JdkApiAgent` 一样属于性能路径——直取 `String` 的 `value`/`coder`、算字段偏移、免构造实例化、绕过 `setAccessible` 等，跨 JDK 版本行为不一致，业务代码不要直接依赖。
- `jdk.JDKVersion.VERSION`：`float` 形式的运行时 JDK 规范版本（JDK 8 是 `1.8`，JDK 17 是 `17.0`），取自 `java.specification.version`，读取或解析失败时回落为 `1.8`。做版本能力开关时用它，不要自己再 parse 系统属性。

```java
if (JDKVersion.VERSION >= 9) { /* 走 VarHandle 实现 */ }
```

## CSV / 身份证

- `com.alianga.jkit.csv.CSVUtils`：UTF-8 读写字符串行，表头、引号内逗号/换行，含流式 `readStream` / `writer`。同包的 `CSV`/`CSVTable` 提供表格模型与 POJO 映射，两者共用同一个解析器。见 [csv.md](https://github.com/zhengmingliang/jkit/blob/develop/docs/csv.md)。
- `IdCardUtils` / `IdCardGenerator`：18 位校验、解析、生成；区划数据在 `idcard-areas.txt`。

## 杂项工具

根包 `com.alianga.jkit` 下几个零散但常用的类。

### 字符串判空与查找

`StringUtils` 的空值判定分两套，**语义不重叠**：

- `isEmpty(Object)` / `isNotEmpty(CharSequence)`：只看长度是否为 0，**不去除首尾空白**，所以 `isEmpty("   ")` 是 `false`。按长度判断因此对 `StringBuilder`、`StringBuffer` 等非 `String` 字符序列同样正确。非字符序列的对象一律视为非空。
- `isBlank(CharSequence)` / `isNotBlank(CharSequence)`：`null`、空串、纯空白都算空。**需要「空白也算空」时用这一套**。

两套各有多参重载：`isAnyEmpty` / `isNoneEmpty` / `isAllEmpty` 与 `isAnyBlank` / `isNoneBlank` / `isAllBlank`，约定一致（数组本身为 `null` 或空时，`isAny*` 返回 `false`、`isAll*` 返回 `true`）。取默认值相应地也有两个：`defaultIfEmpty` 只替换 `null` 和空串，`defaultIfBlank` 连纯空白一起替换（原有的 `defaultString` 只替换 `null`）。`trimToNull` 去掉首尾空白后若为空则返回 `null`。

查找类方法注意区分：`contains(CharSequence, CharSequence)` 是**子串**判断，`containsElementIgnoreCase(String[], String)` 是**数组成员**判断且忽略大小写，两者不是重载关系。`containsAny` 有字符与子序列两个版本，字符版正确处理代理对（只匹配到半个增补字符不算命中）。`indexOfIgnoreCase` 提供忽略大小写的查找，起始位置为负数时按 0 处理，未找到返回 `-1`。`replaceOnce` 只替换第一次出现。`equalsIgnoreCase(null, null)` 返回 `true`。

### 随机数与随机字符串

`RandomUtils`：`getNum(start, end)` 是**闭区间** `[start, end]`，`nextInt(bound)` 是 `[0, bound)`（注意两者语义不同）；`getRandomCode(n)` 出数字+大小写字母，`getRandomNumCode(n)` 出纯数字，`getUUID()` 出 32 位（去掉了 `-`）；`encoding(long)` / `decoding(String)` 是 62 进制短码互转，`encoding` 的入参必须 `> 0`，否则抛 `RuntimeException`。另有 `getRandomIdCard()`、`getChineseName()`、`getRandomTel()`、`getRandomIp()`、`getRandomEmail(minLen, maxLen)`、`getRandomUserAgent()` 等造假数据的方法，**只适合测试与演示**。类内的 `Random` 实例是 `SecureRandom`，但 `getNum` / `getDoubleNum` 用的是 `Math.random()`。

`RandomStringUtils`（commons-lang 风格）：**`random(count)` 会从整个 Unicode 范围取字符**（含代理对与不可打印字符），想要可读结果请用 `randomAlphanumeric` / `randomAlphabetic` / `randomNumeric` / `randomAscii`，或用 `random(count, chars)` 自带字符表。底层是普通 `Random`，不要拿它生成 token、密码。

```java
String code = RandomUtils.getRandomNumCode(6);          // 6 位数字
String key = RandomStringUtils.randomAlphanumeric(16);  // 16 位字母数字
int dice = RandomUtils.getNum(1, 6);                    // 含 1 和 6
```

### 打印与对象工具

`Print`：控制台彩色打印。`normal` / `warning` / `error` 分别按绿/黄/红打到 `System.out`，**同时写一条同级别日志**（走 `log.Log`）；只想要带色字符串就用 `wrapNormal` / `wrapRed` / `wrapGreen` / `wrapYellow` / `wrapBlue` 等。ANSI 转义序列在不支持的终端或重定向到文件时会变成乱码，不要写进日志文件用途的输出。

`Objects`：空值安全的对象工具。**与 `java.util.Objects` 同名**，两者同时使用时必须写全限定名。除了 JDK 那套（`equals`、`deepEquals`、`hash`、`requireNonNull`、`requireNonNullElse`…），还有 `isEmpty(Object)`（`null`、空串、空集合、空 Map、空数组、空 `Optional` 都算空）、`unwrapOptional`，以及两套取值方法：`getInteger` / `getLong` / `getBoolean` / `getMap` 等取不到时返回 `null`，`getIntValue` / `getLongValue` / `getBooleanValue` 等取不到时返回零值，都各有带默认值的重载。两个坑：`getNumber` 解析字符串用的是 `NumberFormat.getInstance()`，**结果随系统 locale 变化**；`getFirst(集合)` 返回的是 `stream().findFirst()` 的 `Optional`，不是元素本身（数组和普通对象才返回值本身）。

多值判空用 `allNotNull(Object...)` / `anyNotNull(Object...)`（数组本身为 `null` 时两者都返回 `false`，空数组则分别是 `true` / `false`）。取默认值有两个，区别在**默认值本身能否为 null**：`requireNonNullElse(obj, default)` 会校验默认值，两者都为 `null` 时抛 `NullPointerException`；`defaultIfNull(obj, default)` 不校验，两者都为 `null` 就返回 `null`。

```java
int n = Objects.getIntValue(map.get("count"));        // 取不到就是 0
String s = Objects.getString(obj, "-");
boolean empty = Objects.isEmpty(java.util.Optional.empty());  // true
```

### 类、端口、系统环境

- `ClassUtils.getDefaultClassLoader()`：按「线程上下文 → 本类 → 系统」顺序取 ClassLoader，用于 classpath 资源加载；三者都拿不到时返回 `null`（调用方需判空）。`ClassUtils.isClassExist(className)` 用于可选依赖探测：只做加载与链接、**不触发目标类的静态初始化**，因此没有副作用；类不存在、依赖链缺失（`NoClassDefFoundError`）、类名非法都统一返回 `false`。
- `PortsUtils`：`isOpen(host, port)` 用 200ms 超时试建 TCP 连接，`isOpen(host, port, timeout)` 自定义超时；`scanPorts(host, "1-1024")` 或 `scanPorts(host, "22,80,3306")` 批量扫描，规则支持逗号与区间混写、自动去重、并纠正写反的区间（`82-80` 等价于 `80-82`），返回结果按端口号**升序**。扫描采用「NIO 非阻塞连接 + 最多 8 个工作线程分片」的模型：每个线程用一个 `Selector` 承载数百个在途连接，域名只解析一次，实测扫完 65535 个端口约 290ms（此前的 100 线程阻塞实现约 460ms，且会因并发写 `ArrayList` 丢结果）。`scanPorts(host, rule, timeout, maxInFlight)` 可调单端口超时与在途连接上限，调大 `maxInFlight` 能近似线性缩短耗时；文件描述符不够时会自动降低并发而不是报错。端口规则为空、端口越界（合法范围 1~65535）或主机无法解析都会抛 `IllegalArgumentException`。带线程池的重载 `scanPorts(host, rule, threadPool)` 与 `getDefaultThreadPool()` 已废弃：新实现不再需要外部线程池，传入的池会被忽略且不会被关闭。
- `Systems`：环境信息。常量 `USER_DIR`、`USER_HOME`、`USER_NAME`、`TMP_DIR`、`FILE_SEPARATOR`、`HOST_NAME`、`OS_ARCH`、`TIMEZONE`、`LANGUAGE`；方法 `osName()`（小写）、`line()`（行分隔符）、`isWindows()`、`isLinux()`、`getPid()`（拿不到返回 `PID_NOT_FOUND`，即 `0`）、`isDebuggerAttached()`、`threadDump()`、`tmpDirName()`（结尾保证带分隔符）。还有一组带单位的系统属性读取：`getSizeAsInt` / `getSizeAsLong` 支持 `k`/`m`/`g` 后缀，`getDurationInNanos` 支持 `s`/`ms`/`us`/`ns` 后缀，格式非法或越界抛 `NumberFormatException`。

```java
long buf = Systems.getSizeAsLong("app.buffer", 64 * 1024);  // 支持 "64k"
long timeout = Systems.getDurationInNanos("app.timeout", 0); // 支持 "500ms"
if (Systems.isLinux()) { /* ... */ }
```

### 异常与类型

- `ExceptionUtils`：`getStackTrace(Throwable)` / `getStackTrace(Exception)` 把堆栈转字符串；`getThrowableList(t)` 返回整条异常链（能处理循环 cause，入参 `null` 返回空列表）；`getRootCause(t)` 取链末端——**没有 cause 时返回的是它自己，不是 `null`**。
- `TypeUtils`：`isBoxed(clz)` 判断是否为包装类型，`getBoxedType(clz)` 把基本类型换成包装类，`getUnBoxedType(clz)` 把包装类型换成基本类型（`Integer.class` → `int.class`）；两者对不匹配的入参都原样返回。
- `TypeDict.checkType(hex)`：按文件头十六进制串（大写）猜扩展名，只覆盖 jpg/png/gif/bmp 几种，认不出时返回字符串 `"0000"`。真要判文件类型请用 `io.FileType`。
- `Constants`、`Callback`：`Constants` 是历史遗留常量；`Callback` 是 `FileUtils.readLargeFile*` 的逐批读取回调（`getFirstLine(firstLine)` 接首行，`apply(data)` 接后续每批内容）。

### encode.AsciiEncoding

数字与 ASCII 之间的低层快速转换，是 `Systems.parseSize` / `parseDuration` 的底层：`digitCount(int)` / `digitCount(long)` 查表算十进制位数（**只对非负值有意义**），`parseIntAscii(cs, index, length)` / `parseLongAscii(...)` 从 `CharSequence` 的一段范围解析数字且不产生中间对象（非法输入或溢出抛 `NumberFormatException`），另有一次处理 4/8 个数字的 `isFourDigitsAsciiEncodedNumber` / `parseFourDigitsLittleEndian` / `isEightDigitAsciiEncodedNumber` / `parseEightDigitsLittleEndian`。

```java
int digits = AsciiEncoding.digitCount(12345);            // 5
long v = AsciiEncoding.parseLongAscii("id=1234", 3, 4);  // 1234
```
