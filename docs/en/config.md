# Configuration Reading Guide

`com.alianga.jkit.config` reads YAML / properties following Spring Boot's externalized configuration precedence. YAML parsing uses this library's own `YamlDocument`, with zero third-party dependencies. It is well suited for reading configuration in non-Spring processes, early in startup, or in utility code.

Loading, caching, and writing back a single named file still use `PropertiesUtil`; use `ConfigPropertyResolver` when you need merging across search paths, profiles, and environment variables. Both share the same YAML flattening logic.

## 1. Default Loading (Spring Boot Rules)

```java
import com.alianga.jkit.config.ConfigPropertyResolver;

ConfigPropertyResolver resolver = ConfigPropertyResolver.load();
String port = resolver.getString("server.port");
Integer timeout = resolver.getInt("app.login_timeout", 2880);
boolean enabled = resolver.getBoolean("demo.datasource.init-demo", false);
```

You can cache it once per process:

```java
ConfigPropertyResolver cached = ConfigPropertyResolver.instance();
ConfigPropertyResolver.reset(); // clear the cache after tests or hot reload
```

Default search order (later entries override earlier ones):

1. `bootstrap` and `application` under `classpath:/` and `classpath:/config/`
2. Within the same directory, by extension: `.properties` &lt; `.yml` &lt; `.yaml`
3. `{name}-{profile}`; for example, `spring.profiles.active: prod` in `application.yml` also loads `application-prod.yml`
4. `file:./`, `file:./config/`
5. Operating system environment variables (`SERVER_PORT` → `server.port`)
6. JVM system properties (higher precedence than environment variables)

Profile sources, in order: `-Dspring.profiles.active`, `SPRING_PROFILES_ACTIVE`, and `spring.profiles.active` / `spring.profiles.include` in configuration files.

Reading values:

```java
resolver.get("app.origin-list");
resolver.getString("spring.application.name", "sample");
resolver.getInt("server.port");
resolver.getLong("app.login_timeout");
resolver.getDouble("ratio", 1.0);
resolver.getBoolean("quartz.enabled");
resolver.getList("whitelist.paths", String.class);   // comma-separated or key[0], key[1]
resolver.getExp("${whitelist.path.driver:/opt/drivers}");
resolver.contains("server.port");
resolver.keys();
resolver.asMap();
resolver.toProperties();

// bind keys with the same prefix to an object (via YamlDocument.toEntity)
RedisProperties redis = resolver.getObject("spring.data.redis", RedisProperties.class);
Map<String, Object> demoDs = resolver.getMap("demo.datasource");
```

`getObject("spring.data.redis", RedisProperties.class)` collects keys such as `spring.data.redis.host` and `spring.data.redis.port`, reconstructs the nested structure, and hands it to the YAML module for setter binding. Names like `login_timeout` and `max-size` match `setLoginTimeout` / `setMaxSize`. Lists of objects (e.g. `custom.nested[0].name`) are supported as well.

`ServerPortConfig.getServerPort()` reads `server.port` and returns `8080` when it is not configured.

## 2. Custom Configuration Names (Non-Spring Applications)

The configuration base name corresponds to Spring Boot's `spring.config.name`, and still uses the search paths and profiles described above:

```java
ConfigPropertyResolver app = ConfigPropertyResolver.load("my-app");
// looks for my-app.yml / my-app.yaml / my-app.properties
// as well as my-app-{profile}.*, classpath:/config/, file:./config/, etc.
```

If you only want specific files, without directory layering or profiles:

```java
ConfigPropertyResolver files = ConfigPropertyResolver.loadFile("jdbc.yml", "redis.properties");
String url = files.getString("spring.datasource.url");
```

For each argument, `loadFile` first tries to find it as a classpath resource, then falls back to a filesystem path. Files listed later override keys with the same name. `${...}` placeholders are resolved by default, and environment variables / system properties are not layered in.

For finer control, use `ConfigLoadOptions`:

```java
import com.alianga.jkit.config.ConfigLoadOptions;

ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
        ConfigLoadOptions.of("my-app")
                .locations("classpath:/", "file:./config/")
                .activeProfiles("prod")
                .enableEnvironment(false)
                .enableSystemProperties(false));
```

You can append directories to the default search paths (later entries override earlier ones) without rewriting the whole list. `~` / `~/...` expands to `user.home`; entries without a `classpath:` / `file:` prefix are treated as filesystem directories:

```java
ConfigPropertyResolver local = ConfigPropertyResolver.load(
        ConfigLoadOptions.defaults()
                .addLocation("~/jkit")                 // -> file:${user.home}/jkit
                .addLocation(new File("/etc/myapp"))); // if an existing file is passed, its parent directory is used
```

## 3. Placeholders

Both `${key}` and `${key:default}` are supported and can be nested; circular references throw an `IllegalStateException`:

```java
// app.host=localhost, app.port=9999
// app.url=http://${app.host}:${app.port}
resolver.getString("app.url");           // http://localhost:9999
resolver.getExp("${app.unknown:none}");  // none
```

You can also call `PlaceholderResolver.resolveAll(map)` / `resolveValue(...)` directly.

## 4. Comparison with PropertiesUtil

`PropertiesUtil` loads and caches files by name and supports writing back (`SafeProperties` preserves comments). It now supports `.properties`, `.yml`, and `.yaml`; YAML is flattened into the `a.b[0].c` form:

```java
import com.alianga.jkit.PropertiesUtil;

PropertiesUtil.loadFromClassPath("application.yml");
String mode = PropertiesUtil.getString("spring.mode"); // reads only this file, no profiles are loaded
```

| | `ConfigPropertyResolver` | `PropertiesUtil` |
| --- | --- | --- |
| Search paths / profiles / env | Yes | No, reads only the file you specify |
| YAML | Yes | Yes (shared flattening) |
| Placeholders | Resolved by default | Not resolved |
| Write back to file | No | Yes |

## 5. Key Forms for YAML Lists

Nested Maps are joined with `.`, and lists use Spring's `key[0]` form, not `key.[0]`:

```yaml
custom:
  items:
    - one
    - two
  nested:
    - name: a
```

This corresponds to `custom.items[0]` and `custom.nested[0].name`. Comma-separated scalars (such as `whitelist.paths: /a, /b`) can be split by comma using `getList`.
