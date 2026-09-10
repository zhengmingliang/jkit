# Miscellaneous Utility Modules

The packages below don't have dedicated long-form docs; their entry points and typical usage are given here. See the corresponding documents for HTTP / JSON / YAML / configuration.

## Text Encoding Detection

`EncodingDetect` determines the charset of bytes/files: BOM, charset declarations in XML/HTML, ISO-2022, then validates UTF-8; for invalid UTF-8 it distinguishes GBK, Big5, Shift_JIS, EUC-JP, and EUC-KR by the ratio of decoded Han characters/kana/Korean.

```java
Charset cs = EncodingDetect.detect(file);
String name = EncodingDetect.getJavaEncode("/tmp/a.txt"); // legacy API compatibility
String text = EncodingDetect.decode(bytes);
```

`FileUtils.readTxtFile(file, null)` invokes this detection.

## convert

`ConvertUtils`: object to String / int / long / double / boolean / BigDecimal / Date. Use the overloads with default values when the input is `null` or conversion fails.

```java
int n = ConvertUtils.toInt("12.9", 0);       // 12
boolean ok = ConvertUtils.toBoolean("YES");  // true
```

`ObjectWrapper`: wraps any object and reads it as a typed value. Its fallback rules differ from `ConvertUtils`—`getXxx()` returns the zero value (`""` / `0` / `0L` / `0.0` / `false`) **only when the wrapped object is `null`**; a non-null object whose content isn't numeric throws `NumberFormatException` directly. Use `getXxxOrDefault` for fault tolerance, noting that it likewise only kicks in for `null`.

```java
ObjectWrapper w = new ObjectWrapper(map.get("age"));
int age = w.getIntegerOrDefault(18);              // null → 18; "abc" → NumberFormatException
String s = new ObjectWrapper(null).getString();   // ""
```

`GenericConverter<I, O>` extends `Function<I, O>`: implement only `apply` and you get `convert(I)` for free (when the argument is `null` it doesn't call `apply` and returns `null` directly) plus `convert(List<I>)` (converts element by element into a new list of equal length; a `null` argument returns `null`; `null` elements inside the list **are still** passed into `apply`).

```java
GenericConverter<String, Integer> conv = Integer::valueOf;
Integer one = conv.convert("1");
List<Integer> list = conv.convert(Arrays.asList("1", "2"));
```

## collection / math

- `Collections`, `Maps`, `ArrayUtils`, `Booleans`: null-safe collection and Map operations.
- `Numbers`, `NumberUtils`: numeric strings, scientific notation, etc.

```java
Map<String, Integer> map = Maps.newHashMap();
Collections.isEmpty(list);
```

`NumberUtils` has two numeric checks with **different semantics that are not interchangeable**: `isNumber(String)` checks character by character and only accepts `0-9`, so `-1`, `1.5`, and `1e10` all return `false`; `isParsableNumber(String)` answers "can this be parsed as a Java numeric literal", recognizing signs, decimal points, scientific notation, `0x` hexadecimal, and the `l/L/f/F/d/D` suffixes. Both return `false` for `null` and empty strings.

- `CountMap<K>`: a `HashMap<K, Integer>` subclass; `increment(key)` / `increment(key, step)` accumulate (a negative `step` subtracts). `get` is overridden to "return `0` when the key is absent" instead of `null`, so retrieved values can be used in arithmetic directly.
- `MultiKeyHashMap<K, V>`: `get` tries an exact match first, then falls back to a lookup by the lowercase form of the key; `get(k1, k2)` and `get(k1, k2, keys...)` return the first non-null value in turn, while `put(k1, k2, value)` and `putValue(value, key, keys...)` attach the same value to multiple keys (all registered in the lowercase index). One pitfall: a hit is judged by "value is non-null", so a key stored with a `null` value counts as a miss and the fallback lookup continues.
- `ArrayStack` / `Buffer`: `Buffer` is a collection interface for "retrieving elements in a defined order" (`get()` reads without removing, `remove()` reads and removes); `ArrayStack` is its LIFO implementation, extends `ArrayList`, and is **neither generic nor thread-safe**. The two method sets throw different exceptions on an empty stack: `peek()` / `pop()` throw `EmptyStackException`, while the interface's `get()` / `remove()` throw `java.nio.BufferUnderflowException`. `search(obj)` returns the 1-based distance from the top of the stack, or `-1` if not found.

```java
CountMap<String> counter = new CountMap<>();
counter.increment("a");
int a = counter.get("a");   // 1
int b = counter.get("b");   // 0, not null

ArrayStack stack = new ArrayStack();
stack.push("x");
Object top = stack.pop();
```

`math.Number` is a subclass of `BigDecimal` that adds `add(String)`, `add(String...)` (accumulates multiple values in turn, returning the sum), `toScaleString(scale[, roundingMode])` (prints with the given decimal places, without scientific notation), `valueOf(BigDecimal)`, and the scale-bearing constructor `new Number(val, scale)`. Note that `BigDecimal` itself is immutable, so these methods **return new values without modifying the current object**; `setScale` throws `ArithmeticException` when rounding is required but no `RoundingMode` is given.

## log

`com.alianga.jkit.log.Log` is based on `java.util.logging`, supports `{}` placeholders, and prints the stack trace if the last argument is a `Throwable`.

```java
private static final Log log = Log.get(Foo.class);
log.info("user={} id={}", name, id);
log.error("read/write failed", ex);
```

## valid

`Preconditions`, `Verify`, `Assert`: argument and state checks that throw the corresponding exception on failure. Not the same kind of thing as JUnit's `Assert`.

```java
Preconditions.checkNotNull(arg, "arg");
Verify.verify(n > 0, "n must be positive");
```

## crypto

`AESCrypt` / `DESCrypt` / `EncryptUtils`: symmetric encryption/decryption, kept for compatibility with legacy projects. Keys, IVs, and modes are the caller's responsibility. `Hash64` is FNV-1a—suitable only for cache keys / sharding, not as a password hash.

```java
long digest = Hash64.hash("cache-key");
byte[] out = AESCrypt.encrypt(plain, key16);
```

The digest methods (`md5`, `sha1`, `sha256`, `sha512`, `sha256_HMAC`) output **lowercase** hex; underneath they all go through `ByteUtils.toHexStringLower`.

`Crypt` is the abstract base class for encryption/decryption: two sets of `encrypt` / `decrypt` for `byte[]` and streams, each optionally taking a `CryptListener` to observe progress (returning `false` from `onRunning` aborts; the buffer `BUFFER_SIZE` is 4KB). `CipherCrpyt` implements streaming with the JDK `Cipher`, and `AESCrypt` and `DESCrypt` are its subclasses. Note that **constructed instances use CBC** (AES requires a 16/24/32-byte key + 16-byte IV), while the static `encrypt(data, key)` / `decrypt(data, key)` **use ECB**—the two paths are not interchangeable. Streaming methods throw `RuntimeException(ex.getMessage())` on error, swallowing the original exception.

```java
Crypt crypt = new AESCrypt(key16, iv16);   // CBC
byte[] enc = crypt.encrypt(plain);
crypt.decrypt(in, out);                    // streams for large files; both in and out are closed
```

The streaming overloads **close both streams passed in** (closing the internal `CipherOutputStream` also closes `outputData`, and `inputData` is explicitly `close()`d), so don't reuse them outside try-with-resources.

`AwaruaTiger`: Tiger digest (192-bit / 24 bytes). `computeHash(bytes)` processes the entire array in one shot and automatically resets the instance, so the same instance can be called repeatedly; however, instances carry internal state and are **not thread-safe**. Kept only for compatibility scenarios like TTH (tiger tree hash); use SHA-256 in new code.

### ContextObfuscator: Context-Derived Obfuscation Wrapping

`ContextObfuscator.obfuscate(plain, contexts...)` / `deobfuscate(cipherBase64, contexts...)` add another layer of wrapping around an existing key or short sensitive data; a typical use is avoiding sending the transport AES key in plaintext in a login response.

**Be clear about what it is**: this is **not an encryption algorithm**; the method is named `obfuscate` rather than `encrypt` precisely to remind you of that at the call site. Its goal is to raise the bar for reverse engineering, not to provide cryptographic strength—the algorithm has not been peer-reviewed and its rules can eventually be reconstructed; the checksum has no key, so it can only detect accidental corruption and context mismatch—it is **not a MAC** and cannot stop deliberate forgery. Transport security should still rely on HTTPS, and at-rest security on an exclusive key that is never distributed. Don't use it as a substitute for `AESCrypt`.

What it does give you: capturing only the ciphertext doesn't reveal the plaintext (the algorithm must also be reconstructed and all contexts known); each call mixes in a random salt, so identical plaintext yields different ciphertext and can't be replay-compared; any change to the context or the ciphertext fails verification with an error.

```java
byte[] transportKey = ...;
String wrapped = ContextObfuscator.obfuscate(transportKey, username, String.valueOf(timestamp));
byte[] restored = ContextObfuscator.deobfuscate(wrapped, username, String.valueOf(timestamp));
```

Contexts are **order-sensitive and count-sensitive** and must match item by item when unwrapping; `null` elements are treated as empty strings. Mismatch, tampered ciphertext, or insufficient length all throw `IllegalArgumentException`; empty plaintext returns an empty string, and empty ciphertext returns an empty array.

The ciphertext format is standard Base64 of `salt(8) + XOR ciphertext (same length as plaintext) + checksum(4)`; the keystream starts from `contexts joined by "::" + "::" + salt + built-in pepper` as its initial state, runs 64 rounds of SHA-256 per cycle with digests byte-reversed and concatenated, and chains on the previous round's output. **Interoperable implementations (such as frontend JS) depend on this format—changing it breaks compatibility**, and the "golden vector" cases in `ContextObfuscatorTest` lock it down. Note that `PEPPER_PART_*` in the class are hardcoded constants, a different thing from the randomly generated per-call salt; interoperable implementations must use exactly the same bytes.

```java
byte[] tiger = new AwaruaTiger().computeHash(data);
```

## base64

`Base64Utils` coexists with the JDK's `java.util.Base64` **deliberately—it is not a duplicate implementation**:

| Scenario | Use | Reason |
| --- | --- | --- |
| Writing into the caller's existing buffer / decoding from a buffer range | `Base64Utils.encode(src, buf, off)`, `Base64Utils.decode(buf, from, len)` | Zero-copy; the JDK has no equivalent API; the JSON module uses only these methods |
| Whole `byte[]` ↔ `String` | `Base64Utils.encodeToString` / `decode(String)` | **The entry points automatically pick the faster implementation based on the JDK version**, so callers don't need to care |
| Lenient decoding / MIME decoding | `Base64Utils.decodeLenient`, `decodeMime` | The JDK has no corresponding lenient mode |

Which implementation is faster for whole-buffer conversion depends on the JDK version (the JDK has progressively added JIT intrinsics for `java.util.Base64`—encoding earlier, decoding later), so the `Base64Utils` entry points choose automatically. Measured on this machine (Corretto 8/9/11/17 + GraalVM 21, fixed iteration counts, thorough warmup, median of 15 rounds), the table below shows the throughput ratio of `JDK implementation / handwritten implementation`; >1 means the JDK is faster:

| JDK | Whole-buffer encode | Whole-buffer decode |
| --- | --- | --- |
| 8 | 0.83~0.85 (handwritten ~20% faster) | 0.59~0.61 (handwritten 1.6~1.7x faster) |
| 9 | 1.07~1.14 (JDK faster) | 0.61~0.68 (handwritten 1.5~1.6x faster) |
| 11 | 0.95~0.99 (even) | 1.42~1.50 (JDK faster) |
| 17 | 1.10~1.13 (JDK faster) | 1.29~1.43 (JDK faster) |
| 21 | 1.94~4.06 (JDK faster) | 4.07~4.85 (JDK faster) |

So the thresholds are set to: **use the handwritten implementation for encoding only on JDK 8, and for decoding below JDK 11**. The result is that the entry points never fall behind calling the JDK directly on any version—about 26% faster encoding and 67% faster decoding on JDK 8, about 63% faster decoding on JDK 9, and on par with the JDK on JDK 11+ (because it is the JDK).

The switch is only valid if the two implementations are fully equivalent, which `Base64UtilsEquivalenceTest` guards: it covers all lengths 0~200, all single-byte values, 256KB large payloads, and 15 kinds of illegal input (requiring the public entry points and the handwritten implementation to **both throw or both not throw** the same kind of exception), all run on JDK 8/9/11/17/21.

```java
String s = EncryptUtils.base64Encode("中文");   // takes bytes as UTF-8; internally goes through Base64Utils
byte[] raw = EncryptUtils.base64Decode(s);
String dataUri = Base64Utils.encodeFileWithPrefix(file);
```

### Strict Decoding vs Lenient Decoding

The `decode` family **requires explicit padding** (the length must be a multiple of 4); unpadded input throws `IllegalArgumentException`.
Use `decodeLenient` when you need to accept unpadded data, and `decodeMime` when you need to ignore line wrapping:

```java
Base64Utils.decode("aGVsbG8");        // throws IllegalArgumentException
Base64Utils.decodeLenient("aGVsbG8"); // decodes "hello" normally
Base64Utils.decodeMime("aGVsbG8g\r\nd29ybGQ=");
```

This division of labor is deliberate; **making `decode` lenient is not recommended**, for two reasons:

1. The algorithm of `decode(byte[], int, int)` derives the output length with `n = len >> 2`; the entire implementation is built on the premise that "the length is a multiple of 4".
   Merely removing the length check doesn't make it lenient—it would **silently drop the last 2~3 characters**; for example, `"abc"` should decode to 2 bytes
   but would actually return an empty array. Truly supporting unpadded input requires adding remainder handling, which is exactly what `decodeLenient` already does.
2. These methods are hot paths in JSON deserialization (`JSONTypeDeserializer` decodes directly from a range of the parse buffer).
   Strict mode makes corrupted base64 fields fail immediately; switching to lenient would degrade to silently producing shorter byte arrays,
   turning a "parse failure" into "data quietly truncated". jkit's own serialization side always outputs padded base64,
   so loosening only matters when reading external non-canonical data—at the cost of losing this layer of validation.

### Determining Whether Something Is Base64

```java
Base64Utils.isBase64Format(text);  // format check: can it be decoded by decode
Base64Utils.isBase64(text);        // heuristic: is it canonical base64 of "printable ASCII text"
```

The two serve different purposes—**don't mix them up**:

| | `isBase64Format` | `isBase64` |
| --- | --- | --- |
| Criterion | Whether `decode` can decode it | Decodes entirely to printable ASCII (32~126), and re-encoding matches the original string |
| base64 of binary data | `true` | **`false`** |
| Text containing Chinese / line breaks | `true` | `false` |
| Non-canonical forms (unpadded, MIME-wrapped) | `false` | `false` |

`isBase64` was migrated from common-model's `com.dtsz.cm.utils.Base64Utils` and matches the original implementation case by case. It is a **heuristic**;
be aware of two known limitations: base64 of binary content like images returns `false`, and ordinary text that happens to look like base64 can't be distinguished—
`"MTIzNDU2"` decodes to `"123456"` and re-encodes identically, so it is judged `true`.
If what you want is "is this string valid base64", use `isBase64Format`.

Two more behaviors to note:

- `Base64Utils.encodeString` / `decodeString` handle strings as **UTF-8** (previously the platform default charset was used, giving inconsistent results across platforms).
- `Base64Utils.encodeFileWithPrefix` builds the data URI prefix from the file's **actual MIME type**; previously it hardcoded `data:image/png;base64,` for any file, giving non-PNG files a wrong prefix.

## image

`SpecCaptcha`, `GifCaptcha`: captcha images, depend on `java.awt` (headless environments need `-Djava.awt.headless=true`).

Their abstract base class is `Captcha` (extends `Randoms`); you can call `setLen` (character count, default 5), `setWidth` (150), `setHeight` (40), `setFont`, and subclasses implement `out(OutputStream)`. **The random characters are generated inside `out()`, so `text()` must be called after `out()`**—calling `text()` first only yields `null`. Also, the two subclasses handle streams inconsistently: `SpecCaptcha.out` only `flush`es without closing the stream, while `GifCaptcha.out` **closes** the passed-in stream in a `finally` block.

```java
SpecCaptcha captcha = new SpecCaptcha(150, 40, 4);
captcha.out(response.getOutputStream());
String answer = captcha.text();   // must be after out()
```

- `Randoms`: the random source for captchas; the `ALPHA` character table excludes easily confused characters (`0`, `1`, `O`, `I`, `l`); comes with `alpha()`, `num(bound)`, `num(min, max)`; backed by `SecureRandom`. `num(min, max)` is **left-closed, right-open** `[min, max)`, and `max <= min` throws an exception.
- `GifEncoder`: the GIF encoder used by `GifCaptcha`; it can also assemble animations on its own—`start(os)` → multiple `addFrame(image)` → `finish()`, together with `setDelay(ms)` or `setFrameRate(fps)`, `setRepeat(0)` (0 = loop forever, default plays once, and must be set before the first frame), `setQuality`, `setTransparent`, `setSize`. `start(OutputStream)` doesn't close the stream; only `start(String file)` closes it at `finish()`; `getFrameByteArray()` works only when the output stream is a `ByteArrayOutputStream`, otherwise it throws `ClassCastException`.

```java
GifEncoder encoder = new GifEncoder();
encoder.start(out);
encoder.setDelay(100);
encoder.setRepeat(0);
encoder.addFrame(frame1);
encoder.finish();
```

## thread

`ExecutorServiceUtil`: creates, awaits, and shuts down thread pools, avoiding the task loss of calling `shutdownNow` directly. Threads it produces are daemons, named with the `alianga-` prefix.

`execute(Runnable)` and `submit(Runnable)` / `submit(Callable)` all go to the same process-wide shared default pool (lazily initialized via class initialization, so there's no risk of concurrently creating multiple pools); use `submit` when you need the result or want to cancel the task. `sleep(millis)` returns `false` when interrupted and **restores the interrupt flag**, so callers can still check `Thread.currentThread().isInterrupted()`. The default thread factory is `getDefaultThreadFactory()`.

## reflect / jdk

`ReflectionUtils`, `ClassStrucWrap`, `FieldAccessor`: getter/setter metadata, also used by JSON/YAML binding. `jdk.UnsafeUtils` and `JdkApiAgent` are performance paths—business code should not depend on them directly.

There are two entry points for reading a single property/field, with **different rules—don't mix them up**:

```java
// exact match by field name, walking up superclasses level by level, reading the field's actual value
Object v1 = ReflectionUtils.getDeclaredFieldValue(bean, "name");
// looks up cached metadata by bean property name; can read computed properties that only have a getter
Object v2 = ObjectUtils.getPropertyValue(bean, "name");
```

`getPropertyValue` reads the field directly when a field with the same name exists—it **does not execute the getter body**; only derived properties without a corresponding field actually invoke the getter. Both were formerly named `getObjectFieldValue` (same name, same signature—extremely easy to misuse); they are now kept as `@Deprecated` delegates, so use the new names above.

`GenericParameterizedType` is a generic-structure descriptor object—exactly what JSON's generic binding entry points consume (`JSON.parse(json, genericType, readOptions...)`, `JSON.read(file, genericType, ...)`). Common factories: `actualType(Class)` (cached and reused per type), `collectionType(collectionClass, valueType)` (`valueType` can wrap another layer for nested generics), `mapType(...)`, `arrayType(componentType)`, `entityType(entityClass, genericClass)` (entities **support only a single, non-nested type parameter**), and `of(Type)` straight from a reflection `Type`. `of` **returns `null` instead of throwing** when it hits an unsupported type or a parse exception, so callers must null-check.

```java
GenericParameterizedType<ArrayList> type =
        GenericParameterizedType.collectionType(ArrayList.class, User.class);
List<User> users = JSON.parse(json, type);
```

- `ReflectConsts`: the type classification table. `getClassCategory(cls)` assigns a type to one of the `ClassCategory` categories (`CharSequence`, `NumberCategory`, `MapCategory`, `CollectionCategory`, `EnumCategory`, `ObjectCategory`, `NonInstance`...), and serialization/deserialization dispatch on it; the nested `PrimitiveType` enum provides wrapper classes, array lengths, and index-based element read/write for primitive types. Classification results are cached with a cap of 4096 types; beyond that no new cache entries are added (classification results are unaffected).
- `GetterInfo` / `SetterInfo` / `FieldInfo`: property metadata produced by `ClassStrucWrap`. `GetterInfo.invoke(target)` reads a value, `SetterInfo.invoke(target, value)` writes one; also `getName()`, `getUnderlineName()` (underscore naming), `getAnnotation(...)`, `getGenericParameterizedType()`, `isMethod()` (method or field); `FieldInfo` exposes a property's getter/setter as a pair. Business code usually only needs the `ClassStrucWrap.get(clazz).getGetterInfos()` / `getFieldInfos()` layer.
- `reflect.UnsafeHelper`: like `jdk.UnsafeUtils` and `JdkApiAgent` above, a performance path—directly reading `String`'s `value`/`coder`, computing field offsets, instantiation without constructors, bypassing `setAccessible`, etc.; behavior varies across JDK versions, so business code should not depend on it directly.
- `jdk.JDKVersion.VERSION`: the runtime JDK specification version as a `float` (JDK 8 is `1.8`, JDK 17 is `17.0`), taken from `java.specification.version`, falling back to `1.8` if reading or parsing fails. Use it for version-capability switches instead of parsing system properties yourself.

```java
if (JDKVersion.VERSION >= 9) { /* use the VarHandle implementation */ }
```

## CSV / ID Card

- `com.alianga.jkit.csv.CSVUtils`: reads and writes string rows in UTF-8; headers, commas/newlines inside quotes; includes streaming `readStream` / `writer`. The `CSV`/`CSVTable` classes in the same package provide a table model and POJO mapping; both share the same parser. See [csv.md](https://github.com/zhengmingliang/jkit/blob/develop/docs/csv.md).
- `IdCardUtils` / `IdCardGenerator`: 18-digit validation, parsing, and generation; region data is in `idcard-areas.txt`.

## Miscellaneous Utilities

A few scattered but commonly used classes under the root package `com.alianga.jkit`.

### String Emptiness Checks and Search

`StringUtils` has two sets of emptiness checks with **non-overlapping semantics**:

- `isEmpty(Object)` / `isNotEmpty(CharSequence)`: only check whether the length is 0, **without trimming leading/trailing whitespace**, so `isEmpty("   ")` is `false`. Being length-based, they are equally correct for non-`String` character sequences like `StringBuilder` and `StringBuffer`. Objects that aren't character sequences are always treated as non-empty.
- `isBlank(CharSequence)` / `isNotBlank(CharSequence)`: `null`, empty strings, and whitespace-only strings all count as blank. **Use this set when "whitespace-only should count as empty".**

Each set has multi-argument overloads: `isAnyEmpty` / `isNoneEmpty` / `isAllEmpty` and `isAnyBlank` / `isNoneBlank` / `isAllBlank`, with consistent conventions (when the array itself is `null` or empty, `isAny*` returns `false` and `isAll*` returns `true`). Correspondingly, there are two default-value methods: `defaultIfEmpty` replaces only `null` and empty strings, while `defaultIfBlank` also replaces whitespace-only strings (the older `defaultString` replaces only `null`). `trimToNull` trims leading/trailing whitespace and returns `null` if the result is empty.

Distinguish the search methods carefully: `contains(CharSequence, CharSequence)` is a **substring** check, while `containsElementIgnoreCase(String[], String)` is an **array membership** check ignoring case—the two are not overloads of each other. `containsAny` has char and CharSequence versions; the char version handles surrogate pairs correctly (matching only half of a supplementary character doesn't count as a hit). `indexOfIgnoreCase` provides case-insensitive search; a negative start index is treated as 0, and it returns `-1` when not found. `replaceOnce` replaces only the first occurrence. `equalsIgnoreCase(null, null)` returns `true`.

### Random Numbers and Random Strings

`RandomUtils`: `getNum(start, end)` is a **closed interval** `[start, end]`, while `nextInt(bound)` is `[0, bound)` (note the different semantics); `getRandomCode(n)` yields digits + upper/lowercase letters, `getRandomNumCode(n)` yields digits only, `getUUID()` yields 32 chars (with `-` removed); `encoding(long)` / `decoding(String)` convert to and from base-62 short codes, and `encoding`'s argument must be `> 0`, otherwise it throws `RuntimeException`. There are also fake-data methods like `getRandomIdCard()`, `getChineseName()`, `getRandomTel()`, `getRandomIp()`, `getRandomEmail(minLen, maxLen)`, `getRandomUserAgent()`—**suitable only for testing and demos**. The class's `Random` instance is a `SecureRandom`, but `getNum` / `getDoubleNum` use `Math.random()`.

`RandomStringUtils` (commons-lang style): **`random(count)` picks characters from the entire Unicode range** (including surrogate pairs and unprintable characters); for readable results use `randomAlphanumeric` / `randomAlphabetic` / `randomNumeric` / `randomAscii`, or `random(count, chars)` with your own character table. Backed by a plain `Random`—don't use it to generate tokens or passwords.

```java
String code = RandomUtils.getRandomNumCode(6);          // 6 digits
String key = RandomStringUtils.randomAlphanumeric(16);  // 16 alphanumeric characters
int dice = RandomUtils.getNum(1, 6);                    // includes 1 and 6
```

### Printing and Object Utilities

`Print`: colored console printing. `normal` / `warning` / `error` print to `System.out` in green/yellow/red respectively and **also write a log entry at the same level** (via `log.Log`); if you only want a colored string, use `wrapNormal` / `wrapRed` / `wrapGreen` / `wrapYellow` / `wrapBlue`, etc. ANSI escape sequences turn into garbage on terminals that don't support them or when redirected to a file, so don't use them in output meant for log files.

`Objects`: null-safe object utilities. **Same name as `java.util.Objects`**—when both are used together, fully qualified names are required. Besides the JDK set (`equals`, `deepEquals`, `hash`, `requireNonNull`, `requireNonNullElse`...), there are `isEmpty(Object)` (`null`, empty strings, empty collections, empty Maps, empty arrays, and empty `Optional`s all count as empty), `unwrapOptional`, and two families of getters: `getInteger` / `getLong` / `getBoolean` / `getMap`, etc. return `null` when the value can't be obtained, while `getIntValue` / `getLongValue` / `getBooleanValue`, etc. return zero values—both families have overloads with default values. Two pitfalls: `getNumber` parses strings with `NumberFormat.getInstance()`, so **results vary with the system locale**; `getFirst(collection)` returns the `Optional` from `stream().findFirst()`, not the element itself (arrays and plain objects do return the value itself).

For multi-value null checks use `allNotNull(Object...)` / `anyNotNull(Object...)` (both return `false` when the array itself is `null`; for an empty array they return `true` / `false` respectively). There are two default-value methods, differing in **whether the default itself may be null**: `requireNonNullElse(obj, default)` validates the default and throws `NullPointerException` when both are `null`; `defaultIfNull(obj, default)` doesn't validate and returns `null` when both are `null`.

```java
int n = Objects.getIntValue(map.get("count"));        // 0 when unavailable
String s = Objects.getString(obj, "-");
boolean empty = Objects.isEmpty(java.util.Optional.empty());  // true
```

### Classes, Ports, and System Environment

- `ClassUtils.getDefaultClassLoader()`: obtains a ClassLoader in the order "thread context → this class → system", for classpath resource loading; returns `null` when all three fail (callers must null-check). `ClassUtils.isClassExist(className)` is for optional-dependency probing: it only loads and links, **without triggering the target class's static initialization**, so it has no side effects; a missing class, a broken dependency chain (`NoClassDefFoundError`), or an illegal class name all uniformly return `false`.
- `PortsUtils`: `isOpen(host, port)` attempts a TCP connection with a 200ms timeout; `isOpen(host, port, timeout)` lets you customize the timeout; `scanPorts(host, "1-1024")` or `scanPorts(host, "22,80,3306")` scan in bulk—the rule syntax supports mixing commas and ranges, automatic dedup, and correcting reversed ranges (`82-80` is equivalent to `80-82`), with results returned in **ascending** port order. Scanning uses a model of "NIO non-blocking connects + sharding across at most 8 worker threads": each thread carries hundreds of in-flight connections on one `Selector`, hostnames are resolved only once, and scanning all 65535 ports takes about 290ms measured (the previous 100-thread blocking implementation took about 460ms and lost results from concurrent `ArrayList` writes). `scanPorts(host, rule, timeout, maxInFlight)` tunes the per-port timeout and in-flight connection cap; raising `maxInFlight` shortens runtime near-linearly, and concurrency is automatically reduced rather than erroring when file descriptors run short. An empty port rule, an out-of-range port (valid range 1~65535), or an unresolvable host all throw `IllegalArgumentException`. The thread-pool overload `scanPorts(host, rule, threadPool)` and `getDefaultThreadPool()` are deprecated: the new implementation no longer needs an external thread pool, and a passed-in pool is ignored and not closed.
- `Systems`: environment info. Constants `USER_DIR`, `USER_HOME`, `USER_NAME`, `TMP_DIR`, `FILE_SEPARATOR`, `HOST_NAME`, `OS_ARCH`, `TIMEZONE`, `LANGUAGE`; methods `osName()` (lowercase), `line()` (line separator), `isWindows()`, `isLinux()`, `getPid()` (returns `PID_NOT_FOUND`, i.e. `0`, when unavailable), `isDebuggerAttached()`, `threadDump()`, `tmpDirName()` (guaranteed to end with a separator). There's also a set of unit-aware system property readers: `getSizeAsInt` / `getSizeAsLong` support `k`/`m`/`g` suffixes, `getDurationInNanos` supports `s`/`ms`/`us`/`ns` suffixes; malformed formats or overflow throw `NumberFormatException`.

```java
long buf = Systems.getSizeAsLong("app.buffer", 64 * 1024);  // accepts "64k"
long timeout = Systems.getDurationInNanos("app.timeout", 0); // accepts "500ms"
if (Systems.isLinux()) { /* ... */ }
```

### Exceptions and Types

- `ExceptionUtils`: `getStackTrace(Throwable)` / `getStackTrace(Exception)` convert a stack trace to a string; `getThrowableList(t)` returns the whole exception chain (handles cyclic causes; a `null` argument returns an empty list); `getRootCause(t)` takes the end of the chain—**when there's no cause it returns itself, not `null`**.
- `TypeUtils`: `isBoxed(clz)` checks whether a type is a wrapper type, `getBoxedType(clz)` converts a primitive to its wrapper, `getUnBoxedType(clz)` converts a wrapper to its primitive (`Integer.class` → `int.class`); both return the input unchanged when it doesn't match.
- `TypeDict.checkType(hex)`: guesses an extension from the file header hex string (uppercase); covers only jpg/png/gif/bmp, returning the string `"0000"` when unrecognized. For real file type detection use `io.FileType`.
- `Constants`, `Callback`: `Constants` holds legacy constants; `Callback` is the batch-reading callback for `FileUtils.readLargeFile*` (`getFirstLine(firstLine)` receives the first line, `apply(data)` receives each subsequent batch).

### encode.AsciiEncoding

Low-level fast conversions between numbers and ASCII, underlying `Systems.parseSize` / `parseDuration`: `digitCount(int)` / `digitCount(long)` compute the decimal digit count via table lookup (**meaningful only for non-negative values**), `parseIntAscii(cs, index, length)` / `parseLongAscii(...)` parse a number from a range of a `CharSequence` without creating intermediate objects (illegal input or overflow throws `NumberFormatException`); plus the batch-of-4/8-digit processors `isFourDigitsAsciiEncodedNumber` / `parseFourDigitsLittleEndian` / `isEightDigitAsciiEncodedNumber` / `parseEightDigitsLittleEndian`.

```java
int digits = AsciiEncoding.digitCount(12345);            // 5
long v = AsciiEncoding.parseLongAscii("id=1234", 3, 4);  // 1234
```
