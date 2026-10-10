# jkit-storage — 统一多存储平台文件管理

零第三方依赖的统一文件存储引擎：一套 `FileStorage` 接口读写 **本地文件系统、FTP、SFTP、WebDAV、Amazon S3 与三十余个 S3 兼容云**（阿里云 OSS、华为云 OBS、腾讯云 COS、七牛云 Kodo、百度云 BOS、又拍云 USS、MinIO、Google Cloud Storage、Azure Blob Storage、go-fastdfs、火山引擎 TOS、Cloudflare R2、金山云 KS3、京东云 OSS、天翼云 OOS、IBM COS 等），内置**跨存储内容对比、增量同步与全量迁移**引擎。除 SFTP 的可选 jsch 外，全部传输逻辑基于 JDK 标准库实现，引入本模块不会向宿主应用传递任何强制的外部依赖。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-storage</artifactId>
    <version>2.0.5</version>
</dependency>
```

JDK 8 源码与目标，零第三方依赖。SFTP 需要在 classpath 额外提供 `com.github.mwiede:jsch`（或老的 `com.jcraft:jsch`），未提供时 SFTP 平台会抛出带提示的 `UNSUPPORTED`，其它平台不受影响。

## 30 秒上手

```java
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.sync.SyncEngine;
import com.alianga.jkit.storage.sync.SyncOptions;

// 一行创建任意平台的存储实例
FileStorage oss = FileStorages.of("aliyun-oss", StorageConfig.empty()
        .set("endpoint", "https://oss-cn-hangzhou.aliyuncs.com")
        .set("bucket", "mybucket")
        .set("accessKey", "AK")
        .set("secretKey", "SK"));

// 一行完成基础文件操作——所有平台行为一致
oss.upload("demo/hello.txt", "你好".getBytes(StandardCharsets.UTF_8));
List<StorageEntry> files = oss.list("demo");
String text = oss.readString("demo/hello.txt");       // 在线编辑：读
oss.writeString("demo/hello.txt", "新内容");           // 在线编辑：写
oss.download("demo/hello.txt", Files.newOutputStream(localPath));
oss.rename("demo/hello.txt", "demo/hello2.txt");
oss.delete("demo/hello.txt");

// 跨存储同步：本地 → OSS，增量（跳过一致文件），带进度回调
FileStorage local = FileStorages.of("local", StorageConfig.empty().set("basePath", "/data"));
SyncEngine.sync(local, "backup", oss, "backup", SyncOptions.incremental(), progress);
```

## 接口一览（`FileStorage`）

| 方法 | 说明 | 所有平台的统一行为 |
| --- | --- | --- |
| `exists / stat / list` | 存在性、元数据、单层列表 | 路径不存在统一抛 `NOT_FOUND`；`list` 只返回直接子项 |
| `mkdir` | 建目录 | 幂等；对象存储写 `dir/` 占位对象 |
| `upload(path, in, size)` | 上传流 | 流由调用方关闭；size 未知传 `-1`（引擎自行缓冲） |
| `download(path, out)` | 下载到输出流 | 不关闭输出流；缺失抛 `NOT_FOUND` |
| `delete` | 删文件或空目录 | 幂等；非空目录抛 `CONFLICT` |
| `deleteDir` | 递归删除 | 拒绝删除存储根目录 |
| `copy / rename` | 复制 / 移动 | 目标存在则覆盖；平台有原生实现用原生，否则 copy+delete |
| `readString / writeString` | 文本读写（UTF-8） | 在线编辑场景 |
| `walk` | 递归枚举全部后代 | 同步 / 迁移引擎使用 |

路径约定：`/` 分隔、无前导 `/`、根目录为空串；`..` 与 `.` 在入口即被规范化，路径无法逃逸出存储根（`StoragePaths.normalize`）。

## 平台支持矩阵

| 档位 | 平台 | 说明 |
| --- | --- | --- |
| 原生引擎 | 本地文件系统、阿里云 OSS、Azure Blob Storage、go-fastdfs | 按平台官方协议实现 |
| S3 兼容引擎（SigV4/SigV2 双签名、虚拟主机与路径寻址、ListObjects V1/V2） | Amazon S3、Amazon S3 V2、MinIO、华为云 OBS、腾讯云 COS、七牛云 Kodo、百度云 BOS、又拍云 USS、Google Cloud Storage、火山引擎 TOS、Cloudflare R2、金山云 KS3、京东云 OSS、天翼云 OOS、移动云 EOS、联通沃云 OSS、网易数帆 NOS、Ucloud US3、平安云 OBS、首云 OSS、IBM COS、美团云 MSS、其它任意 S3 兼容存储（`generic-s3`） | 兼容平台只需填 endpoint / bucket / AK / SK；`platforms()` 提供全部字段定义 |
| 协议引擎 | FTP（内置纯 JDK 客户端，被动模式）、SFTP（可选 jsch）、WebDAV（PROPFIND/MKCOL/MOVE 全支持） | |
| 规划中 | FastDFS、MongoDB GridFS、青云 QingStor | 平台已注册、引擎未实现，创建实例时抛 `UNSUPPORTED` 并提示（FastDFS / GridFS 的协议无统一列表能力，已在设计中单独规划） |

云端 presets 的默认端点随版本内置（如 `qiniu-kodo → s3.cn-north-1.qiniucs.com`），自建 / 内网部署直接覆盖 `endpoint`。腾讯云 COS 走 S3 兼容 API 时 SecretKey 需 MD5 变换（`md5Secret=true` 已内置默认）。

## 自定义平台与引擎

```java
// 注册一个内网 S3 兼容存储的平台模板，界面 / 配置驱动场景即可直接使用
FileStorages.register(new StoragePlatform("intranet-s3", "内网对象存储", "s3", "S3 兼容",
        StorageField.required("endpoint", "Endpoint"),
        StorageField.required("bucket", "Bucket"),
        StorageField.required("accessKey", "AccessKey"),
        StorageField.secret("secretKey", "SecretKey", true)));
FileStorages.registerEngine("my-engine", config -> new MyFileStorage(config));
```

## 统一错误模型

所有失败统一抛 `StorageException`（RuntimeException），携带 `StorageErrorCode`、出错路径与平台原始信息摘要：

`INVALID_CONFIG` / `AUTH_FAILED` / `CONNECT_FAILED` / `TIMEOUT` / `NOT_FOUND` / `ALREADY_EXISTS` / `CONFLICT` / `IO_ERROR` / `RATE_LIMITED` / `SERVER_ERROR` / `UNSUPPORTED` / `CANCELLED`。

HTTP 引擎内置稳定性加固：幂等请求遇到 keep-alive 死连接、服务端提前断连、半关闭等连接级失败自动换新连接重试一次；响应读尽归还连接池，未读尽强制断开，避免污染连接池。

## 同步与迁移（`sync` 包）

```java
// 结构对比：按相对路径对齐两侧（可以是不同平台），返回全部差异
List<StorageDiff> diffs = SyncEngine.diff(local, "data", oss, "data", CompareMode.SIZE);

// 增量同步 / 全量迁移 / 镜像同步（删除目标端多余文件），可试运行
SyncResult result = SyncEngine.sync(local, "data", oss, "data",
        SyncOptions.mirror().concurrency(8).dryRun(false), new SyncProgress() { ... });
```

对比策略：`SIZE`（默认，跨平台最稳）/ `SIZE_AND_MTIME` / `SIZE_AND_ETAG` / `ALWAYS`（全量迁移）。执行时自动在目标端补齐父目录；大文件经临时文件流式中转；并发可配，失败达到上限自动中止；`SyncProgress` 回调计划、进度与逐文件错误（并发 &gt; 1 时回调来自多个线程）。

## 图形界面（jkit-toolbox）

工具箱的「统一文件存储」插件基于本引擎实现：连接管理（表单按平台字段定义动态生成、连接测试、本地持久化）、文件浏览（上传 / 下载 / 在线编辑 / 重命名 / 递归删除）、跨存储对比（结构差异 + 逐行文本 diff）、同步（三种模式 + 试运行 + 进度日志）。见 jkit-toolbox 仓库 `storage-ui` 模块。
