# IO and Resources

`com.alianga.jkit.io` provides classpath / file resource location, properties loading, file-type detection from file headers, and `WatchService`-based path monitoring. For Spring-style layered config reading, see [config.md](config.md).

## 1. ResourceUtils

Resolves `classpath:`, `file:`, and jar/war URLs into a `URL` or `File`:

```java
import com.alianga.jkit.io.ResourceUtils;

URL url = ResourceUtils.getURL("classpath:application.yml");
File file = ResourceUtils.getFile("file:./config/app.yml");
boolean jar = ResourceUtils.isJarURL(url);
```

`classpath:` goes through the default ClassLoader; plain paths are treated as filesystem paths. When the resource is not on the local filesystem (e.g. inside a jar), `getFile` throws `FileNotFoundException`.

## 2. PropertiesLoaderUtils

Loads `.properties` or `.xml` properties. Files are read as UTF-8 by default:

```java
import com.alianga.jkit.io.PropertiesLoaderUtils;

Properties one = PropertiesLoaderUtils.loadProperties(new File("app.properties"));
Properties all = PropertiesLoaderUtils.loadAllProperties("git.properties");
```

`loadAllProperties` merges all same-named resources on the classpath. For YAML use `ConfigPropertyResolver` / `YamlDocument`, not this class.

## 3. FileType

Guesses the extension from the file-header magic bytes (looks at the first 3 bytes by default):

```java
import com.alianga.jkit.io.FileType;

String ext = FileType.getFileType("photo.jpg");
```

This is not full MIME detection; it only covers common formats.

Bidirectional MIME mapping is provided by two classpath resources (migrated from ZmlTools):

| Resource | Direction | Entry point |
| --- | --- | --- |
| `filetype.properties` | mimeType → suffix | `FileType.getSuffixByMimeType` |
| `mimetype.properties` | suffix → mimeType | `FileType.getMimeTypeBySuffix` |

```java
FileType.getSuffixByMimeType("image/png");   // "png"
FileType.getMimeTypeBySuffix("jpg");         // "image/jpeg"
```

Two conventions:

- **Suffixes never carry a leading dot**; callers build file names with `"." + suffix`. `EncryptUtils.base64DecodeFile`
  relies on this when parsing data URIs.
- When no match is found, an **empty string** is returned instead of `null`.

Missing resources do not make the class unusable either; these two lookups simply degrade to returning empty strings
(previously, a missing resource made `Properties.load(null)` throw an NPE, leaving the whole `FileType` class
permanently unusable via `ExceptionInInitializerError`, which also broke base64 data-URI decoding).

`FileType.bytesToHexString` and `EncryptUtils.bytesToHexString` are now unified to delegate to `ByteUtils.toHexStringLower`.

## 4. FileMonitor

Registers `WatchService` events (create / modify / delete / overflow) for a directory or a single file. When monitoring a single file it actually watches the containing directory, so deleting and recreating the file still delivers events.

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

If config hot-reloading only needs mtime-based polling of files, `ConfigReloader` is more direct.

`FileMonitor` extends `MonitorServer` (`MonitorServer extends Thread implements Closeable`), so `start()`
spawns **a non-daemon thread** that blocks on `WatchService.take()`; without calling `close()` the process will not
exit. For background monitoring, call `monitor.setDaemon(true)` before `start()`, or `close()` in a shutdown hook.
`watch()` / `watch(Monitor)` are the synchronous versions; they occupy the current thread and loop until `close()`.

`create(...)` only builds the object; `start()` is what registers the path. The watch target is decided by the rules
in `init()`: an existing directory is watched as a directory; an existing regular file, or a path whose name
**contains `.` and does not end with `.d`** (`looksLikeFile`), is treated as a "single file to be created", and its
parent directories are created automatically via `createDirectories`; any other non-existent path is **created as a
directory** before being watched — passing a wrong path leaves an empty directory behind, so mind this side effect.

For recursive watching pass `maxDepth` (`1` or less watches only the current directory); the parameter is ignored for single-file watching:

```java
// Watch /tmp/conf and up to 3 levels of directories beneath it
FileMonitor recursive = FileMonitor.create(Paths.get("/tmp/conf"), 3, FileMonitor.EVENTS_ALL);
recursive.setMaxDepth(2);   // still changeable before start()
```

### Monitor / BaseMonitor: the observer

`Monitor` has four methods: `onCreate` / `onModify` / `onDelete` / `onOverflow`. When you only care about one or two
of them, extend `BaseMonitor`, which implements all four as no-ops:

```java
import com.alianga.jkit.io.monitor.BaseMonitor;
import com.alianga.jkit.io.monitor.FileMonitor;

FileMonitor monitor = FileMonitor.createAll("/tmp/conf", new BaseMonitor() {
    @Override
    public void onModify(WatchEvent<?> event, Path currentPath) {
        // currentPath is the directory where the event happened; the specific file must be resolved yourself
        Path changed = currentPath.resolve(event.context().toString());
        reload(changed);
    }
});
monitor.setDaemon(true);
monitor.start();
```

The `createAll` family (`Path` / `String` / `File` / `URI` / `URL`) is equivalent to `create(path, EVENTS_ALL).setWatcher(watcher)`.
The `URL` version throws `MonitorException` when the URL cannot be converted into a valid URI.

`onOverflow` corresponds to `StandardWatchEventKinds.OVERFLOW`, meaning **events were dropped** (queue backlog). At that
point incremental information is already lost; the correct response is to rescan the whole target directory, not to ignore it.

### WatchEventKinds: event constants

`WatchEventKinds` is an enum wrapper around `StandardWatchEventKinds`; `getValue()` returns the raw `WatchEvent.Kind`,
and `WatchEventKinds.ALL` is an array of all four event kinds (`FileMonitor.EVENTS_ALL` is exactly that):

```java
import com.alianga.jkit.io.monitor.WatchEventKinds;

FileMonitor.create(path, WatchEventKinds.CREATE.getValue(), WatchEventKinds.DELETE.getValue());
FileMonitor.create(path, WatchEventKinds.ALL);   // equivalent to FileMonitor.EVENTS_ALL
FileMonitor.create(path);                         // an empty events array also registers all events
```

`FileMonitor.ENTRY_CREATE` / `ENTRY_MODIFY` / `ENTRY_DELETE` / `OVERFLOW` are static aliases of the same constants,
so you can check the event type directly with `event.kind() == FileMonitor.ENTRY_MODIFY`.

### MonitorServer: one service watching multiple paths

`MonitorServer` is the underlying implementation: it holds one `WatchService` and a `WatchKey -> Path` map, and
`registerPath` can be called multiple times to attach several directories to the same service. `init()` must be called
first (internally `isClosed` starts as `true`; calling `registerPath` before initialization throws
`MonitorException("Watch Monitor is not initialized")`):

```java
import com.alianga.jkit.io.monitor.MonitorServer;

MonitorServer server = new MonitorServer();
server.init();                                    // throws IOException, creates the WatchService
server.registerPath(Paths.get("/tmp/conf"), 1);   // maxDepth=1 watches only the current directory
server.registerPath(Paths.get("/tmp/data"), 3);   // >1 recursively registers subdirectories

Thread worker = new Thread(() -> {
    while (true) {
        // each call takes a single WatchKey and dispatches its events; the loop is controlled by the caller
        server.watch((event, currentPath) -> {
            System.out.println(event.kind().name() + " @ " + currentPath.resolve(event.context().toString()));
        }, event -> event.kind() != FileMonitor.OVERFLOW);   // the second argument is an event filter, may be null
    }
});
worker.setDaemon(true);
worker.start();
// ...
server.close();
```

Key points:

- `MonitorServer` itself **does not override `run()`**; `new MonitorServer().start()` does nothing — you must loop
  over `watch(...)` yourself. It is `FileMonitor` that implements `run()` as `watch()`.
- There are two overloads, `watch(MonitorAction, Predicate)` and `watch(Monitor, Predicate)`: the former hands you the
  raw event to classify yourself; the latter dispatches by event type to the four `Monitor` callbacks.
- When `take()` is interrupted, the interrupt flag is restored and `close()` is called; if the `WatchService` is
  already closed (`ClosedWatchServiceException`), it also just calls `close()` and returns, so `close()` makes a
  blocked `watch` exit.
- The path passed to `registerPath` must be a directory and is normalized via `toAbsolutePath().normalize()`; with
  `maxDepth > 1` subdirectories are registered via `walkFileTree`, and inaccessible subtrees
  (`AccessDeniedException`) are silently skipped.
- `setModifiers(WatchEvent.Modifier[])` is passed through verbatim to `Path.register`; which modifiers are supported
  depends on the JDK and filesystem implementation (the JDK public API defines no standard modifiers).
- **Recursive watching is not free**: `registerPath` walks the directory tree only once at call time. `FileMonitor`
  adds compensation — for directory watching with `maxDepth > 1`, when an `ENTRY_CREATE` arrives and the new entry is
  a directory, the new directory is registered automatically at the remaining depth; with `MonitorServer` directly you
  must handle newly created directories yourself.

### MonitorAction: functional event handling

`MonitorAction` is a `@FunctionalInterface` with the signature `handle(WatchEvent<?> event, Path currentPath)`. Unlike
`Monitor`, it does not dispatch by type, so it suits "all events go through the same logic" scenarios or cases where you
need to read `event.kind()` yourself:

```java
import com.alianga.jkit.io.monitor.MonitorAction;

MonitorAction action = (event, currentPath) -> {
    if (event.kind() == FileMonitor.ENTRY_MODIFY) {
        markDirty(currentPath.resolve(event.context().toString()));
    }
};
server.watch(action, null);
```

`currentPath` is **the directory as it was registered** (the path the `WatchKey` corresponds to), and `event.context()`
is the file name relative to that directory; for `OVERFLOW` events `context()` is not a `Path`, so check the type before
building a path.

### impl.DelayMonitor: coalescing bursty modify events

A single file save often triggers **multiple `ENTRY_MODIFY` events** (one for writing content, one for size, one for
timestamps; editors that "write a temp file then replace" make it worse), and the javadoc of `Monitor.onModify` states
outright that "file modification may fire multiple times". Reloading config directly in `onModify` therefore reloads
several times per save. `DelayMonitor` solves this: it performs **trailing-edge debounce per file path** — `onModify`
is only actually invoked once after the file sees no further modify events within `delay` milliseconds; `create` /
`delete` / `overflow` events are forwarded immediately.

```java
import com.alianga.jkit.io.monitor.BaseMonitor;
import com.alianga.jkit.io.monitor.FileMonitor;
import com.alianga.jkit.io.monitor.impl.DelayMonitor;

// Style 1: createAll with a delay (internally just wraps a DelayMonitor)
FileMonitor monitor = FileMonitor.createAll("/tmp/conf", new BaseMonitor() {
    @Override
    public void onModify(WatchEvent<?> event, Path currentPath) {
        reload(currentPath.resolve(event.context().toString()));
    }
}, 300);
monitor.setDaemon(true);
monitor.start();

// Style 2: explicit wrapping, for setWatcher or MonitorServer.watch(Monitor, filter)
DelayMonitor delay = new DelayMonitor(realWatcher, 300);
FileMonitor.create(path, FileMonitor.EVENTS_ALL).setWatcher(delay).start();
```

Key points:

- With `delay < 1` debouncing is disabled entirely (no thread pool is created) and `onModify` is forwarded directly.
- Nesting is not allowed: passing another `DelayMonitor` as the constructor argument throws
  `IllegalArgumentException`, and passing `null` also throws `IllegalArgumentException` (via `Assert.notNull`
  internally).
- Delayed tasks run on an internal **single-threaded daemon scheduler pool**
  (`ExecutorServiceUtil.newSingleScheduledExecutorService`, thread name `alianga-N`). In other words, the coalesced
  `onModify` **does not run on the watch thread** — if the callback touches shared state you must ensure thread safety
  yourself, and the delayed callbacks for all files run serially, so slow work in a callback delays later events.
- Coalescing keeps the **last** event object; intermediate events are discarded.
- `DelayMonitor` implements `Closeable`: `close()` cancels pending tasks and calls `shutdownNow()`.
  `FileMonitor.close()` checks whether the watcher is `Closeable` and closes it for you, so with
  `createAll(path, watcher, delay)` there is nothing to manage; if you `new DelayMonitor` yourself without going
  through `FileMonitor`, remember to `close()` it manually, otherwise the scheduler pool stays around.

### Common pitfalls of watching

- **Each `FileMonitor` / `MonitorServer` instance owns one `WatchService`**, which corresponds to one inotify instance
  on Linux, limited by `fs.inotify.max_user_instances` (many machines default to 128). When the limit is exceeded,
  `init()` throws `MonitorException` whose cause is
  `IOException: User limit of inotify instances reached or too many open files`. To watch many paths, reuse one
  `MonitorServer` with multiple `registerPath` calls instead of creating a pile of `FileMonitor`s.
- A closed instance cannot be reused: `start()` throws `MonitorException("Watch Monitor is closed")`, and `Thread`
  itself forbids a second `start()` anyway; create a new instance if you need to watch again.
- Single-file watching is implemented by filtering on "event path == target file"; `OVERFLOW` is not part of the
  filtering (it is always passed through).
- Exceptions thrown by callbacks bubble up through `watch()` into the watch thread and break the watch loop outright;
  do your own try/catch inside callbacks.
- The event granularity, deduplication, and latency of `WatchService` are determined by each platform's
  implementation; this module does no cross-platform normalization. If you need a deterministic "did the content
  change" check, compare mtime / digests yourself in the callback, or switch to a polling approach like
  `ConfigReloader`.

## 5. ByteUtils

Read/write helpers for byte arrays and primitive types, intended for protocol parsing or binary files. It complements
`IOUtils` (streams / UTF-8 encoding): use `IOUtils` for bulk reads and writes, and `ByteUtils` for fixed-length fields.

It is also the library's single implementation of hex conversion — **lowercase and uppercase are two different methods; do not mix them up**:

```java
import com.alianga.jkit.io.ByteUtils;

byte[] data = {0x00, 0x0f, (byte) 0xab};

ByteUtils.toHexStringLower(data);   // "000fab", use this for digests and file-header identification
ByteUtils.toHexString(data);        // "000FAB", historical behavior, stays uppercase
ByteUtils.toHexString(data, '-');   // "00-0F-AB-"
ByteUtils.hexString2Bytes("000fab"); // restores the bytes; both cases accepted
```

`toHexStringLower(null)` returns `null`, and an empty array returns an empty string. `EncryptUtils.bytesToHexString`
and `FileType.bytesToHexString` return `null` for both `null` and empty arrays (preserving their respective historical
contracts); in all other cases they delegate to this class.
