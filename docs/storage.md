# 文件存储（jkit-storage）

零第三方依赖的统一多存储平台文件管理引擎：一套 `FileStorage` 接口操作本地文件系统、FTP、SFTP、WebDAV、Amazon S3 与三十余个 S3 兼容云，并内置跨存储对比、增量同步与全量迁移。

## 快速开始

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-storage</artifactId>
    <version>2.0.5</version>
</dependency>
```

```java
// 一行创建
FileStorage oss = FileStorages.of("aliyun-oss", StorageConfig.empty()
        .set("endpoint", "https://oss-cn-hangzhou.aliyuncs.com")
        .set("bucket", "mybucket").set("accessKey", "AK").set("secretKey", "SK"));

// 一行操作（所有平台行为一致）
oss.upload("demo/hello.txt", "你好".getBytes(StandardCharsets.UTF_8));
List<StorageEntry> files = oss.list("demo");
String text = oss.readString("demo/hello.txt");
oss.writeString("demo/hello.txt", "新内容");
oss.download("demo/hello.txt", out);
oss.rename("demo/hello.txt", "demo/new.txt");
oss.delete("demo/hello.txt");
```

配置驱动创建（图形界面 / 配置文件场景）：

```java
Map<String, String> props = Map.of("platform", "local", "basePath", "/data");
FileStorage local = FileStorages.of(props);
```

## 统一接口

| 方法 | 统一行为 |
| --- | --- |
| `exists / stat / list` | 不存在统一抛 `NOT_FOUND`；`list` 单层、无排序保证 |
| `mkdir` | 幂等；对象存储写目录占位对象 |
| `upload(path, in, size)` | 流由调用方关闭；未知 size 传 `-1` |
| `download(path, out)` | 不关闭输出流；缺失抛 `NOT_FOUND` |
| `delete` | 幂等；非空目录抛 `CONFLICT`（用 `deleteDir` 递归） |
| `copy / rename` | 目标存在则覆盖 |
| `readString / writeString` | UTF-8 文本读写（在线编辑） |
| `walk` | 递归枚举（同步引擎用） |

路径约定：`/` 分隔、无前导 `/`、根目录为空串；`..` 在入口被折叠，无法逃逸存储根。

## 平台支持

- **原生引擎**：本地文件系统、阿里云 OSS、Azure Blob Storage、go-fastdfs
- **S3 兼容引擎**（SigV4/SigV2、虚拟主机与路径寻址、ListObjects V1/V2）：Amazon S3、MinIO、华为云 OBS、腾讯云 COS、七牛 Kodo、百度 BOS、又拍 USS、Google Cloud Storage、火山 TOS、Cloudflare R2、金山 KS3、京东 OSS、天翼 OOS、移动 EOS、沃云 OSS、网易 NOS、Ucloud US3、平安 OBS、首云 OSS、IBM COS、美团 MSS、以及 `generic-s3` 接入任意兼容存储
- **协议引擎**：FTP（纯 JDK 客户端）、SFTP（可选 jsch 依赖）、WebDAV
- **规划中**：FastDFS、MongoDB GridFS、青云 QingStor（已注册平台，创建实例抛 `UNSUPPORTED`）

`FileStorages.platforms()` 返回全部平台描述与配置字段定义（含显示名、必填、密钥、默认值），可直接驱动图形界面动态表单；`FileStorages.register(...)` / `registerEngine(...)` 支持自定义平台与引擎。

## 统一错误与稳定性

所有失败统一抛 `StorageException`，错误码：`INVALID_CONFIG / AUTH_FAILED / CONNECT_FAILED / TIMEOUT / NOT_FOUND / ALREADY_EXISTS / CONFLICT / IO_ERROR / RATE_LIMITED / SERVER_ERROR / UNSUPPORTED / CANCELLED`。HTTP 引擎对幂等请求内置连接级失败自动重试（keep-alive 死连接、服务端提前断连、半关闭），连接池归还策略经过特殊处理，长跑稳定。

## 跨存储对比与同步

```java
List<StorageDiff> diffs = SyncEngine.diff(local, "data", oss, "data", CompareMode.SIZE);
SyncResult result = SyncEngine.sync(local, "data", oss, "data",
        SyncOptions.mirror().concurrency(8), progress);
```

- 三种模式：增量同步（跳过一致文件）、镜像同步（删除目标端多余）、全量迁移（`ALWAYS` 策略全部重传）
- 对比策略：`SIZE`（默认）/ `SIZE_AND_MTIME` / `SIZE_AND_ETAG` / `ALWAYS`
- 支持试运行（dryRun）、并发线程数、路径过滤、失败上限；执行前自动补齐目标端目录；大文件临时文件流式中转

完整接口说明见模块 [README](https://github.com/zhengmingliang/jkit/blob/main/jkit-storage/README.md)。
