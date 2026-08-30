# 配置读取使用指南

`com.alianga.jkit.config` 按 Spring Boot 外部化配置的优先级读取 YAML / properties，YAML 解析走本库的 `YamlDocument`，零第三方依赖。适合在非 Spring 进程、启动早期或工具代码里拿配置。

指定单个文件名的加载、缓存和回写仍用 `PropertiesUtil`；需要按搜索路径、profile、环境变量合并时用 `ConfigPropertyResolver`。两者共用同一套 YAML 扁平化逻辑。

## 1. 默认加载（Spring Boot 规则）

```java
import com.alianga.jkit.config.ConfigPropertyResolver;

ConfigPropertyResolver resolver = ConfigPropertyResolver.load();
String port = resolver.getString("server.port");
Integer timeout = resolver.getInt("app.login_timeout", 2880);
boolean enabled = resolver.getBoolean("demo.datasource.init-demo", false);
```

进程内可缓存一次：

```java
ConfigPropertyResolver cached = ConfigPropertyResolver.instance();
ConfigPropertyResolver.reset(); // 测试或热加载后清缓存
```

默认搜索顺序（后者覆盖前者）：

1. `classpath:/`、`classpath:/config/` 下的 `bootstrap`、`application`
2. 同一目录内扩展名：`.properties` &lt; `.yml` &lt; `.yaml`
3. `{name}-{profile}`，例如 `application.yml` 中 `spring.profiles.active: prod` 会继续加载 `application-prod.yml`
4. `file:./`、`file:./config/`
5. 操作系统环境变量（`SERVER_PORT` → `server.port`）
6. JVM 系统属性（高于环境变量）

profile 来源依次为：`-Dspring.profiles.active`、`SPRING_PROFILES_ACTIVE`、配置文件里的 `spring.profiles.active` / `spring.profiles.include`。

取值：

```java
resolver.get("app.origin-list");
resolver.getString("spring.application.name", "sample");
resolver.getInt("server.port");
resolver.getLong("app.login_timeout");
resolver.getDouble("ratio", 1.0);
resolver.getBoolean("quartz.enabled");
resolver.getList("whitelist.paths", String.class);   // 逗号分隔或 key[0]、key[1]
resolver.getExp("${whitelist.path.driver:/opt/drivers}");
resolver.contains("server.port");
resolver.keys();
resolver.asMap();
resolver.toProperties();

// 相同前缀绑定到对象（走 YamlDocument.toEntity）
RedisProperties redis = resolver.getObject("spring.data.redis", RedisProperties.class);
Map<String, Object> demoDs = resolver.getMap("demo.datasource");
```

`getObject("spring.data.redis", RedisProperties.class)` 会收集 `spring.data.redis.host`、`spring.data.redis.port` 等 key，还原成嵌套结构再交给 YAML 模块做 setter 绑定。`login_timeout`、`max-size` 这类名字会匹配 `setLoginTimeout` / `setMaxSize`。对象列表（如 `custom.nested[0].name`）同样支持。

`ServerPortConfig.getServerPort()` 读取 `server.port`，没有配置时返回 `8080`。

## 2. 自定义配置名（非 Spring 应用）

配置基名对应 Spring Boot 的 `spring.config.name`，仍走上面的搜索路径和 profile：

```java
ConfigPropertyResolver app = ConfigPropertyResolver.load("my-app");
// 查找 my-app.yml / my-app.yaml / my-app.properties
// 以及 my-app-{profile}.*、classpath:/config/、file:./config/ 等
```

只要若干文件、不要目录分层和 profile：

```java
ConfigPropertyResolver files = ConfigPropertyResolver.loadFile("jdbc.yml", "redis.properties");
String url = files.getString("spring.datasource.url");
```

`loadFile` 每个参数先当 classpath 资源找，找不到再当文件系统路径。后出现的文件覆盖同名 key，默认解析 `${...}` 占位符，不叠加 env / 系统属性。

更细的控制用 `ConfigLoadOptions`：

```java
import com.alianga.jkit.config.ConfigLoadOptions;

ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
        ConfigLoadOptions.of("my-app")
                .locations("classpath:/", "file:./config/")
                .activeProfiles("prod")
                .enableEnvironment(false)
                .enableSystemProperties(false));
```

## 3. 占位符

支持 `${key}` 与 `${key:default}`，可嵌套，循环引用会抛 `IllegalStateException`：

```java
// app.host=localhost, app.port=9999
// app.url=http://${app.host}:${app.port}
resolver.getString("app.url");           // http://localhost:9999
resolver.getExp("${app.unknown:none}");  // none
```

也可直接调用 `PlaceholderResolver.resolveAll(map)` / `resolveValue(...)`。

## 4. 与 PropertiesUtil

`PropertiesUtil` 按文件名加载并缓存，支持回写（`SafeProperties` 保留注释）。现已支持 `.properties`、`.yml`、`.yaml`，YAML 会扁平化为 `a.b[0].c`：

```java
import com.alianga.jkit.PropertiesUtil;

PropertiesUtil.loadFromClassPath("application.yml");
String mode = PropertiesUtil.getString("spring.mode"); // 只读这一个文件，不加载 profile
```

| | `ConfigPropertyResolver` | `PropertiesUtil` |
| --- | --- | --- |
| 搜索路径 / profile / env | 是 | 否，只读你指定的文件 |
| YAML | 是 | 是（共用扁平化） |
| 占位符 | 默认解析 | 不解析 |
| 回写文件 | 否 | 是 |

## 5. YAML 列表的 key 形式

嵌套 Map 用 `.` 连接，列表用 Spring 的 `key[0]`，不是 `key.[0]`：

```yaml
custom:
  items:
    - one
    - two
  nested:
    - name: a
```

对应 `custom.items[0]`、`custom.nested[0].name`。逗号分隔的标量（如 `whitelist.paths: /a, /b`）用 `getList` 按逗号拆分。
