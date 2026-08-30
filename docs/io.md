# IO 与资源

`com.alianga.jkit.io` 提供 classpath / 文件资源定位、properties 加载、文件头类型判断，以及基于 `WatchService` 的路径监听。配置的 Spring 式分层读取见 [config.md](config.md)。

## 1. ResourceUtils

把 `classpath:`、`file:`、jar/war URL 解析成 `URL` 或 `File`：

```java
import com.alianga.jkit.io.ResourceUtils;

URL url = ResourceUtils.getURL("classpath:application.yml");
File file = ResourceUtils.getFile("file:./config/app.yml");
boolean jar = ResourceUtils.isJarURL(url);
```

`classpath:` 走默认 ClassLoader；普通路径按文件系统处理。资源不在本地文件系统时（例如 jar 内），`getFile` 会抛 `FileNotFoundException`。

## 2. PropertiesLoaderUtils

加载 `.properties` 或 `.xml` properties。文件默认按 UTF-8 读：

```java
import com.alianga.jkit.io.PropertiesLoaderUtils;

Properties one = PropertiesLoaderUtils.loadProperties(new File("app.properties"));
Properties all = PropertiesLoaderUtils.loadAllProperties("git.properties");
```

`loadAllProperties` 会合并 classpath 上同名的全部资源。YAML 请用 `ConfigPropertyResolver` / `YamlDocument`，不要走这个类。

## 3. FileType

按文件头魔术数字猜测扩展名（默认看前 3 字节）：

```java
import com.alianga.jkit.io.FileType;

String ext = FileType.getFileType("photo.jpg");
```

这不是完整 MIME 探测，只覆盖常见格式。

MIME 双向映射由两个 classpath 资源提供（迁移自 ZmlTools）：

| 资源 | 方向 | 入口 |
| --- | --- | --- |
| `filetype.properties` | mimeType → 后缀 | `FileType.getSuffixByMimeType` |
| `mimetype.properties` | 后缀 → mimeType | `FileType.getMimeTypeBySuffix` |

```java
FileType.getSuffixByMimeType("image/png");   // "png"
FileType.getMimeTypeBySuffix("jpg");         // "image/jpeg"
```

两点约定：

- **后缀一律不带前导点**，调用方按 `"." + suffix` 拼文件名。`EncryptUtils.base64DecodeFile` 解析
  data URI 时就依赖这一点。
- 查不到时返回**空串**而非 `null`。

资源缺失也不会让类不可用，只是这两个查询全部退化为空串（此前缺失会让 `Properties.load(null)` 抛 NPE，
整个 `FileType` 因 `ExceptionInInitializerError` 永久不可用，连带 base64 data-URI 解码一起失败）。

`FileType.bytesToHexString` 与 `EncryptUtils.bytesToHexString` 现已统一转调 `ByteUtils.toHexStringLower`。

## 4. FileMonitor

对目录或单文件注册 `WatchService` 事件（创建 / 修改 / 删除 / overflow）。监听单文件时实际盯的是所在目录，文件删掉再创建仍能收到事件。

```java
import com.alianga.jkit.io.monitor.FileMonitor;
import com.alianga.jkit.io.monitor.Monitor;

FileMonitor monitor = FileMonitor.create(new File("/tmp/conf"), FileMonitor.EVENTS_ALL);
monitor.setWatcher(new Monitor() {
    public void onCreate(WatchEvent<?> event, Path path) { }
    public void onModify(WatchEvent<?> event, Path path) { }
    public void onDelete(WatchEvent<?> event, Path path) { }
    public void onOverflow(WatchEvent<?> event, Path path) { }
});
monitor.start();
monitor.close();
```

配置热加载若只是按 mtime 轮询文件，用 `ConfigReloader` 更直接。

`FileMonitor` 继承 `MonitorServer`（`MonitorServer extends Thread implements Closeable`），所以 `start()`
起的是**一个非守护线程**，它阻塞在 `WatchService.take()` 上；不调用 `close()` 进程不会退出。后台监听请在
`start()` 之前 `monitor.setDaemon(true)`，或在退出钩子里 `close()`。`watch()` / `watch(Monitor)` 是同步版本，
会占用当前线程一直循环直到 `close()`。

`create(...)` 只是造对象，`start()` 才注册路径。监听目标按 `init()` 的规则判定：已存在的目录按目录监听；
已存在的普通文件、或路径名**含 `.` 且不以 `.d` 结尾**（`looksLikeFile`）按"待创建的单文件"处理，并自动
`createDirectories` 建出父目录；其余不存在的路径会被**创建成目录**再监听——传错路径会留下空目录，注意这个副作用。

递归监听传 `maxDepth`（`1` 或更小只监听当前目录），单文件监听时该参数被忽略：

```java
// 监听 /tmp/conf 及其下最多 3 层目录
FileMonitor recursive = FileMonitor.create(Paths.get("/tmp/conf"), 3, FileMonitor.EVENTS_ALL);
recursive.setMaxDepth(2);   // start() 之前还可以改
```

### Monitor / BaseMonitor：观察者

`Monitor` 有 `onCreate` / `onModify` / `onDelete` / `onOverflow` 四个方法。只关心其中一两个时继承
`BaseMonitor`，它把四个方法都实现为空：

```java
import com.alianga.jkit.io.monitor.BaseMonitor;
import com.alianga.jkit.io.monitor.FileMonitor;

FileMonitor monitor = FileMonitor.createAll("/tmp/conf", new BaseMonitor() {
    @Override
    public void onModify(WatchEvent<?> event, Path currentPath) {
        // currentPath 是事件发生的目录，具体文件要自己拼
        Path changed = currentPath.resolve(event.context().toString());
        reload(changed);
    }
});
monitor.setDaemon(true);
monitor.start();
```

`createAll` 系列（`Path` / `String` / `File` / `URI` / `URL`）等价于 `create(path, EVENTS_ALL).setWatcher(watcher)`。
`URL` 版本在 URL 无法转成合法 URI 时抛 `MonitorException`。

`onOverflow` 对应 `StandardWatchEventKinds.OVERFLOW`，意思是**事件被丢弃了**（队列积压），此时增量信息已经丢失，
正确处理是重新全量扫描目标目录，而不是忽略。

### WatchEventKinds：事件常量

`WatchEventKinds` 是对 `StandardWatchEventKinds` 的枚举封装，`getValue()` 拿到原始 `WatchEvent.Kind`，
`WatchEventKinds.ALL` 是全部四种事件的数组（`FileMonitor.EVENTS_ALL` 就是它）：

```java
import com.alianga.jkit.io.monitor.WatchEventKinds;

FileMonitor.create(path, WatchEventKinds.CREATE.getValue(), WatchEventKinds.DELETE.getValue());
FileMonitor.create(path, WatchEventKinds.ALL);   // 等价于 FileMonitor.EVENTS_ALL
FileMonitor.create(path);                         // events 为空时也按全部事件注册
```

`FileMonitor.ENTRY_CREATE` / `ENTRY_MODIFY` / `ENTRY_DELETE` / `OVERFLOW` 是同一批常量的静态别名，
判断事件类型时可以直接 `event.kind() == FileMonitor.ENTRY_MODIFY`。

### MonitorServer：一个服务监听多个路径

`MonitorServer` 是底层实现：持有一个 `WatchService` 和 `WatchKey -> Path` 映射，可以 `registerPath` 多次，
把多个目录挂到同一个服务上。`init()` 必须先调用（内部 `isClosed` 初值为 `true`，未初始化就 `registerPath`
会抛 `MonitorException("Watch Monitor is not initialized")`）：

```java
import com.alianga.jkit.io.monitor.MonitorServer;

MonitorServer server = new MonitorServer();
server.init();                                    // throws IOException，创建 WatchService
server.registerPath(Paths.get("/tmp/conf"), 1);   // maxDepth=1 只监听当前目录
server.registerPath(Paths.get("/tmp/data"), 3);   // >1 时递归注册子目录

Thread worker = new Thread(() -> {
    while (true) {
        // 每次调用只 take 一个 WatchKey 并分发它的事件，循环由调用方控制
        server.watch((event, currentPath) -> {
            System.out.println(event.kind().name() + " @ " + currentPath.resolve(event.context().toString()));
        }, event -> event.kind() != FileMonitor.OVERFLOW);   // 第二个参数是事件过滤器，可传 null
    }
});
worker.setDaemon(true);
worker.start();
// ...
server.close();
```

要点：

- `MonitorServer` 自己**没有覆写 `run()`**，直接 `new MonitorServer().start()` 什么都不会做，必须自己循环调用
  `watch(...)`；`FileMonitor` 才把 `run()` 实现为 `watch()`。
- `watch(MonitorAction, Predicate)` 与 `watch(Monitor, Predicate)` 两个重载：前者拿到原始事件自己判断类型，
  后者按事件类型分发到 `Monitor` 的四个回调。
- `take()` 被中断时会恢复中断标志并 `close()`；`WatchService` 已关闭（`ClosedWatchServiceException`）也直接
  `close()` 返回，所以 `close()` 能让阻塞中的 `watch` 退出。
- `registerPath` 的路径必须是目录，会被 `toAbsolutePath().normalize()`；`maxDepth > 1` 时用 `walkFileTree` 注册
  子目录，无权限的子树（`AccessDeniedException`）静默跳过。
- `setModifiers(WatchEvent.Modifier[])` 原样透传给 `Path.register`，具体支持哪些 modifier 由 JDK 与文件系统
  实现决定（JDK 公共 API 没有定义标准 modifier）。
- **递归监听不是免费的**：`registerPath` 只在调用时遍历一次目录树。`FileMonitor` 额外做了补偿——`maxDepth > 1`
  的目录监听在收到 `ENTRY_CREATE` 且新建的是目录时，会自动把新目录按剩余深度注册进来；直接用
  `MonitorServer` 则需要自己处理新建目录。

### MonitorAction：函数式事件处理

`MonitorAction` 是 `@FunctionalInterface`，签名 `handle(WatchEvent<?> event, Path currentPath)`。它和 `Monitor`
的区别是不按类型分发，适合"所有事件走同一段逻辑"或需要自己读 `event.kind()` 的场景：

```java
import com.alianga.jkit.io.monitor.MonitorAction;

MonitorAction action = (event, currentPath) -> {
    if (event.kind() == FileMonitor.ENTRY_MODIFY) {
        markDirty(currentPath.resolve(event.context().toString()));
    }
};
server.watch(action, null);
```

`currentPath` 是**注册时的那个目录**（`WatchKey` 对应的路径），`event.context()` 是相对该目录的文件名；
`OVERFLOW` 事件的 `context()` 不是 `Path`，拼路径前要判断类型。

### impl.DelayMonitor：合并抖动的修改事件

保存一次文件常常触发**多次 `ENTRY_MODIFY`**（写内容、改大小、改时间戳各一次，编辑器"先写临时文件再替换"
会更夸张），`Monitor.onModify` 的注释也直接写了"文件修改可能触发多次"。直接在 `onModify` 里重载配置，
一次保存会重载好几遍。`DelayMonitor` 就是解决这个问题的：**按文件路径做尾部延迟合并**，只有该文件在
`delay` 毫秒内不再有修改事件，才真正回调一次 `onModify`；`create` / `delete` / `overflow` 事件立即转发。

```java
import com.alianga.jkit.io.monitor.BaseMonitor;
import com.alianga.jkit.io.monitor.FileMonitor;
import com.alianga.jkit.io.monitor.impl.DelayMonitor;

// 写法一：createAll 直接带延迟（内部就是包一层 DelayMonitor）
FileMonitor monitor = FileMonitor.createAll("/tmp/conf", new BaseMonitor() {
    @Override
    public void onModify(WatchEvent<?> event, Path currentPath) {
        reload(currentPath.resolve(event.context().toString()));
    }
}, 300);
monitor.setDaemon(true);
monitor.start();

// 写法二：显式包装，用于 setWatcher 或 MonitorServer.watch(Monitor, filter)
DelayMonitor delay = new DelayMonitor(realWatcher, 300);
FileMonitor.create(path, FileMonitor.EVENTS_ALL).setWatcher(delay).start();
```

要点：

- `delay < 1` 时完全不启用延迟（不创建线程池），`onModify` 直接转发。
- 不能嵌套：构造参数传另一个 `DelayMonitor` 抛 `IllegalArgumentException`，传 `null` 也抛
  `IllegalArgumentException`（内部走 `Assert.notNull`）。
- 延迟任务跑在内部的**单线程守护调度池**（`ExecutorServiceUtil.newSingleScheduledExecutorService`，线程名
  `alianga-N`）。也就是说合并后的 `onModify` **不在监听线程上执行**，回调里访问共享状态要自己保证线程安全，
  且所有文件的延迟回调串行执行——回调里做耗时操作会拖后面的事件。
- 合并时保留的是**最后一次**事件对象，中间事件被丢弃。
- `DelayMonitor` 实现了 `Closeable`：`close()` 取消未触发的任务并 `shutdownNow()`。`FileMonitor.close()` 会检查
  watcher 是否 `Closeable` 并顺手关掉它，所以走 `createAll(path, watcher, delay)` 时不用自己管；
  自己 `new DelayMonitor` 又不走 `FileMonitor` 的场景，记得手动 `close()`，否则调度池一直留着。

### 监听的通用坑

- **每个 `FileMonitor` / `MonitorServer` 实例各占一个 `WatchService`**，在 Linux 上对应一个 inotify 实例，受
  `fs.inotify.max_user_instances` 限制（很多机器默认 128）。超限时 `init()` 抛 `MonitorException`，
  cause 是 `IOException: User limit of inotify instances reached or too many open files`。要监听很多路径，
  应该复用一个 `MonitorServer` 多次 `registerPath`，而不是 new 一堆 `FileMonitor`。
- 已 `close()` 的实例不能复用：`start()` 会抛 `MonitorException("Watch Monitor is closed")`，而且 `Thread`
  本身也不允许二次 `start()`；需要重新监听请新建实例。
- 单文件监听靠"事件路径 == 目标文件"过滤实现，`OVERFLOW` 不参与过滤（总是透传）。
- 回调抛出的异常会顺着 `watch()` 冒到监听线程，直接把监听循环打断；回调里请自己 try/catch。
- `WatchService` 的事件粒度、去重和延迟由各平台实现决定，本模块不做跨平台归一化；需要确定性的"内容是否变了"
  判断，请在回调里自行比对 mtime / 摘要，或改用 `ConfigReloader` 那种轮询方案。

## 5. ByteUtils

字节数组与基本类型的读写辅助，给协议解析或二进制文件用。和 `IOUtils`（流 / UTF-8 编解码）互补：大块读写走 `IOUtils`，定长字段走 `ByteUtils`。

它同时是全库 16 进制转换的唯一实现，**大小写是两个不同方法，不要混用**：

```java
import com.alianga.jkit.io.ByteUtils;

byte[] data = {0x00, 0x0f, (byte) 0xab};

ByteUtils.toHexStringLower(data);   // "000fab"，摘要、文件头识别用这个
ByteUtils.toHexString(data);        // "000FAB"，历史行为，保持大写
ByteUtils.toHexString(data, '-');   // "00-0F-AB-"
ByteUtils.hexString2Bytes("000fab"); // 还原，大小写都接受
```

`toHexStringLower(null)` 返回 `null`，空数组返回空串。`EncryptUtils.bytesToHexString` 和
`FileType.bytesToHexString` 对 `null` 和空数组都返回 `null`（保持各自的历史契约），其余情况转调本类。
