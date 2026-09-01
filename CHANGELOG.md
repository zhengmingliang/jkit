# 版本更新说明

本文记录 jkit 各版本的用户可见变更。新版本一律追加到「版本记录」最上方，不要改写已发布小节。

## 如何记录后续版本

发布（或准备发布）新版本时按下面做：

1. 把 `pom.xml` 的 `<version>` 改成新版本号，同步 `README.md` 页头与依赖示例、`Main.java` 打印的版本。
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

### 构建

- `git-commit-id-plugin`、`buildnumber-maven-plugin`、`maven-source-plugin` 从 `publish` profile 挪到默认构建，`package` 即可产出带构建信息的 sources jar。

## 2.0.0

jkit 首个对外版本，由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来：零第三方依赖、包名改为 `com.alianga.jkit`，坐标 `com.alianga:jkit:2.0.0`。

能力概要见 [README.md](README.md)：CSV / HTTP / JSON / YAML / 配置 / 表达式等均用纯 JDK 重写。从 ZmlTools 迁过来的项目可继续用 `relocated/zmltools` 把 `top.wuyongshi:ZmlTools:2.0.0` 重定向到本坐标（包名仍需手工替换）。
