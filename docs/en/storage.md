# File Storage (jkit-storage)

A zero-third-party-dependency, unified multi-storage file management engine: one `FileStorage` interface for local filesystem, FTP, SFTP, WebDAV, Amazon S3 and 30+ S3-compatible clouds, with built-in cross-storage compare, incremental sync and full migration.

## Quick start

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-storage</artifactId>
    <version>2.0.5</version>
</dependency>
```

```java
// create in one line
FileStorage oss = FileStorages.of("aliyun-oss", StorageConfig.empty()
        .set("endpoint", "https://oss-cn-hangzhou.aliyuncs.com")
        .set("bucket", "mybucket").set("accessKey", "AK").set("secretKey", "SK"));

// operate in one line (identical behavior on every platform)
oss.upload("demo/hello.txt", "hello".getBytes(StandardCharsets.UTF_8));
List<StorageEntry> files = oss.list("demo");
String text = oss.readString("demo/hello.txt");
oss.writeString("demo/hello.txt", "updated");
oss.download("demo/hello.txt", out);
oss.rename("demo/hello.txt", "demo/new.txt");
oss.delete("demo/hello.txt");
```

Config-driven creation (for GUIs / config files):

```java
Map<String, String> props = Map.of("platform", "local", "basePath", "/data");
FileStorage local = FileStorages.of(props);
```

## Unified interface

| Method | Unified behavior |
| --- | --- |
| `exists / stat / list` | missing objects throw `NOT_FOUND`; `list` is single-level, order unspecified |
| `mkdir` | idempotent; object stores write a folder marker object |
| `upload(path, in, size)` | caller closes the stream; pass `-1` for unknown size |
| `download(path, out)` | does not close the output stream; missing throws `NOT_FOUND` |
| `delete` | idempotent; non-empty folder throws `CONFLICT` (use `deleteDir`) |
| `copy / rename` | overwrite the target when it exists |
| `readString / writeString` | UTF-8 text read/write (inline editing) |
| `walk` | recursive enumeration (used by the sync engine) |

Path convention: `/` separated, no leading `/`, empty string for root; `..` segments are collapsed at the entry point so paths cannot escape the storage root.

## Platform support

- **Native engines**: local filesystem, Aliyun OSS, Azure Blob Storage, go-fastdfs
- **S3-compatible engine** (SigV4/SigV2, virtual-host and path addressing, ListObjects V1/V2): Amazon S3, MinIO, Huawei OBS, Tencent COS, Qiniu Kodo, Baidu BOS, Upyun USS, Google Cloud Storage, Volcano TOS, Cloudflare R2, Kingsoft KS3, JD OSS, Tianyi OOS, China Mobile EOS, Woyun OSS, NetEase NOS, Ucloud US3, Pingan OBS, Shouyun OSS, IBM COS, Meituan MSS, plus `generic-s3` for any compatible service
- **Protocol engines**: FTP (pure-JDK client), SFTP (optional jsch dependency), WebDAV
- **Planned**: FastDFS, MongoDB GridFS, QingStor (platform registered; instance creation throws `UNSUPPORTED`)

`FileStorages.platforms()` returns all platform descriptors with field definitions (labels, required, secret, defaults) — enough to drive dynamic GUI forms; `register(...)` / `registerEngine(...)` accept custom platforms and engines.

## Unified errors and stability

Every failure throws `StorageException` with a `StorageErrorCode`: `INVALID_CONFIG / AUTH_FAILED / CONNECT_FAILED / TIMEOUT / NOT_FOUND / ALREADY_EXISTS / CONFLICT / IO_ERROR / RATE_LIMITED / SERVER_ERROR / UNSUPPORTED / CANCELLED`. The HTTP engine retries idempotent requests once on connection-level failures (dead keep-alive connections, early server disconnects, half-closed sockets) and returns or closes connections appropriately for long-running stability.

## Cross-storage compare and sync

```java
List<StorageDiff> diffs = SyncEngine.diff(local, "data", oss, "data", CompareMode.SIZE);
SyncResult result = SyncEngine.sync(local, "data", oss, "data",
        SyncOptions.mirror().concurrency(8), progress);
```

- Three modes: incremental (skip identical files), mirror (delete extras on target), full migration (`ALWAYS` policy)
- Compare policies: `SIZE` (default) / `SIZE_AND_MTIME` / `SIZE_AND_ETAG` / `ALWAYS`
- Dry run, concurrency, path filters, error cap; target folders are created before copying; large files stream through temp files

See the module [README](https://github.com/zhengmingliang/jkit/blob/main/jkit-storage/README.md) for the full API reference.
