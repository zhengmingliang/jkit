package com.alianga.jkit.storage.conn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 常驻会话复用：同一连接连续列目录只建一次连接；释放后才重建；
 * 用通过的实例断线后自动重连重试一次；首次就连不上不重试。
 *
 * @author 郑明亮
 */
public class SessionsReuseTest {

    private Function<Connection, FileStorage> original;
    private java.util.concurrent.Executor originalCloser;
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final List<FileStorage> created = new ArrayList<>();

    @Before
    public void installStub() {
        originalCloser = Sessions.closer;
        Sessions.closer = Runnable::run;
        Sessions.closePool();
        original = Sessions.opener;
        Sessions.opener = connection -> {
            opens.incrementAndGet();
            FileStorage stub = (FileStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{FileStorage.class}, (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            closes.incrementAndGet();
                            return null;
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        return null;
                    });
            created.add(stub);
            return stub;
        };
    }

    @After
    public void restore() {
        Sessions.closePool();
        Sessions.opener = original;
        Sessions.closer = originalCloser;
    }

    private static Connection sftp() {
        return new Connection("s", "sftp", ConnMaps.map("host", "h", "user", "u"));
    }

    @Test
    public void fiveListingsOpenOnce() throws Exception {
        Connection connection = sftp();
        for (int i = 0; i < 5; i++) {
            Sessions.callPooled(connection, storage -> storage.list("dir" + 0));
        }
        assertEquals("SFTP 连续 5 次列目录应只建一次连接", 1, opens.get());
        assertEquals(0, closes.get());
    }

    @Test
    public void ftpIsPooledToo() throws Exception {
        Connection connection = new Connection("f", "ftp", ConnMaps.map("host", "h"));
        for (int i = 0; i < 5; i++) {
            Sessions.callPooled(connection, storage -> null);
        }
        assertEquals(1, opens.get());
    }

    @Test
    public void releaseClosesAndNextCallReopens() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        Sessions.release(connection);
        assertEquals(1, closes.get());
        assertEquals(0, Sessions.pooledCount());
        Sessions.callPooled(connection, storage -> null);
        assertEquals(2, opens.get());
    }

    @Test
    public void releaseOnlyAffectsThatConnection() throws Exception {
        Connection a = sftp();
        Connection b = new Connection("b", "sftp", ConnMaps.map("host", "h2", "user", "u"));
        Sessions.callPooled(a, storage -> null);
        Sessions.callPooled(b, storage -> null);
        Sessions.release(a);
        assertEquals(1, Sessions.pooledCount());
    }

    @Test
    public void droppedSessionReconnectsOnce() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        FileStorage first = created.get(0);
        AtomicInteger attempts = new AtomicInteger();
        String result = Sessions.callPooled(connection, storage -> {
            if (attempts.incrementAndGet() == 1) {
                assertSame(first, storage);
                throw new StorageException(StorageErrorCode.CONNECT_FAILED, "session is down", "");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, attempts.get());
        assertEquals("断线后重建一次", 2, opens.get());
        assertEquals("旧实例被关闭", 1, closes.get());
    }

    @Test
    public void sftpPipeClosedReportedAsConflictStillReconnects() throws Exception {
        // 真机实测：服务端重启后 SFTP 引擎报「列出目录失败: Pipe closed（SftpException id=4）」，错误码是 CONFLICT
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        AtomicInteger attempts = new AtomicInteger();
        Sessions.callPooled(connection, storage -> {
            if (attempts.incrementAndGet() == 1) {
                throw new StorageException(StorageErrorCode.CONFLICT, "列出目录失败: Pipe closed", "d",
                        new java.io.IOException("Pipe closed"));
            }
            return null;
        });
        assertEquals(2, attempts.get());
        assertEquals(2, opens.get());
    }

    @Test
    public void firstConnectFailureIsNotRetried() throws Exception {
        Connection connection = sftp();
        AtomicInteger attempts = new AtomicInteger();
        try {
            Sessions.callPooled(connection, storage -> {
                attempts.incrementAndGet();
                throw new StorageException(StorageErrorCode.CONNECT_FAILED, "refused", "");
            });
            fail("应抛出");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.CONNECT_FAILED, expected.code());
        }
        assertEquals("从没连通过的实例不重试，免得错地址等两倍超时", 1, attempts.get());
        assertEquals(0, Sessions.pooledCount());
    }

    @Test
    public void businessErrorsKeepTheSession() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        try {
            Sessions.callPooled(connection, storage -> {
                throw new StorageException(StorageErrorCode.NOT_FOUND, "nope", "x");
            });
            fail("应抛出");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.NOT_FOUND, expected.code());
        }
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
    }

    @Test
    public void httpPlatformsAreNotPooled() {
        assertTrue(Sessions.isPooledPlatform("sftp"));
        assertTrue(Sessions.isPooledPlatform("ftp"));
        assertTrue(!Sessions.isPooledPlatform("aliyun-oss"));
    }

    private static StorageException dropped() {
        return new StorageException(StorageErrorCode.IO_ERROR, "connection reset", "");
    }

    @Test
    public void writeOpsAreNotRetriedAfterDrop() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        AtomicInteger attempts = new AtomicInteger();
        try {
            Sessions.callPooledWrite(connection, storage -> {
                attempts.incrementAndGet();
                throw dropped();
            });
            fail("应抛出");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.IO_ERROR, expected.code());
        }
        assertEquals("写操作断线不重试，避免重复执行", 1, attempts.get());
        assertEquals("断掉的实例仍作废", 1, closes.get());
        Sessions.callPooled(connection, storage -> null);
        assertEquals("下次操作重建", 2, opens.get());
    }

    @Test
    public void releaseDefersCloseUntilInFlightOpFinishes() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> {
            Sessions.release(connection);
            assertEquals("在途操作未结束，不得关闭", 0, closes.get());
            return null;
        });
        assertEquals("最后一个操作归还后关闭", 1, closes.get());
        assertEquals(0, Sessions.pooledCount());
    }

    @Test
    public void closePoolDefersCloseToo() throws Exception {
        Connection connection = sftp();
        Sessions.callPooledWrite(connection, storage -> {
            Sessions.closePool();
            assertEquals(0, closes.get());
            return null;
        });
        assertEquals(1, closes.get());
    }

    @Test
    public void releasedEntryNeverRetriesNorReentersPool() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        AtomicInteger attempts = new AtomicInteger();
        try {
            Sessions.callPooled(connection, storage -> {
                attempts.incrementAndGet();
                Sessions.release(connection);
                throw dropped();
            });
            fail("应抛出");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.IO_ERROR, expected.code());
        }
        assertEquals("已释放的实例出错不重试", 1, attempts.get());
        assertEquals("不重登孤儿实例", 1, opens.get());
        assertEquals(0, Sessions.pooledCount());
        assertEquals(1, closes.get());
    }

    @Test
    public void retainOnlyClosesChangedOrDeletedConnectionsOnly() throws Exception {
        Connection a = sftp();
        Connection b = new Connection("b", "sftp", ConnMaps.map("host", "h2", "user", "u"));
        Connection c = new Connection("c", "ftp", ConnMaps.map("host", "h3"));
        Sessions.callPooled(a, storage -> null);
        Sessions.callPooled(b, storage -> null);
        Sessions.callPooled(c, storage -> null);
        Connection bEdited = b.with(b.name(), b.platform(), ConnMaps.map("host", "h2-new", "user", "u"));
        Sessions.retainOnly(ConnMaps.list(a, bEdited));
        assertEquals("仅未变的 a 保留", 1, Sessions.pooledCount());
        assertEquals(2, closes.get());
        Sessions.callPooled(a, storage -> null);
        assertEquals("a 继续复用", 3, opens.get());
    }

    @Test
    public void releaseClosesOffTheCallingThread() throws Exception {
        Sessions.closer = originalCloser;
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        Sessions.opener = connection -> (FileStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileStorage.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        gate.await(); // 模拟 FTP close 等引擎锁
                        closed.countDown();
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
        Connection connection = new Connection("f", "ftp", ConnMaps.map("host", "h"));
        Sessions.callPooled(connection, storage -> null);
        long start = System.nanoTime();
        Sessions.release(connection);
        assertTrue("release 不得在调用线程（UI 线程）上等 close",
                (System.nanoTime() - start) < 500_000_000L);
        gate.countDown();
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    public void thumbLaneUsesSeparateInstance() throws Exception {
        Connection connection = new Connection("f", "ftp", ConnMaps.map("host", "h"));
        FileStorage[] seen = new FileStorage[2];
        Sessions.callPooled(connection, storage -> seen[0] = storage);
        Sessions.callPooledLane(connection, "thumb", storage -> seen[1] = storage);
        Sessions.callPooledLane(connection, "thumb", storage -> null);
        assertTrue("缩略图通道与浏览不共用实例", seen[0] != seen[1]);
        assertEquals(2, opens.get());
        Sessions.release(connection);
        assertEquals("释放连接时通道实例一并关闭", 2, closes.get());
    }

    @Test
    public void retryInstanceThatFailsAgainIsEvicted() throws Exception {
        Connection connection = sftp();
        Sessions.callPooled(connection, storage -> null);
        try {
            Sessions.callPooled(connection, storage -> {
                throw dropped();
            });
            fail("应抛出");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.IO_ERROR, expected.code());
        }
        assertEquals(2, opens.get());
        assertEquals("重试实例再失败立即摘出池", 0, Sessions.pooledCount());
        assertEquals(2, closes.get());
    }

    @Test
    public void staleConnectionAfterRetainOnlyDoesNotReopen() throws Exception {
        Connection old = sftp();
        Sessions.callPooled(old, storage -> null);
        Connection edited = old.with(old.name(), old.platform(), ConnMaps.map("host", "h-new", "user", "u"));
        Sessions.retainOnly(ConnMaps.list(edited));
        try {
            Sessions.callPooled(old, storage -> null);
            fail("旧 Connection 应被拒绝");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.CONFLICT, expected.code());
        }
        assertEquals("不在旧 key 下重建实例", 1, opens.get());
        Sessions.callPooled(edited, storage -> null);
        assertEquals(2, opens.get());
        Sessions.retainOnly(ConnMaps.list());
        try {
            Sessions.callPooled(edited, storage -> null);
            fail("已删除连接应被拒绝");
        } catch (StorageException expected) {
            assertEquals(StorageErrorCode.CONFLICT, expected.code());
        }
    }

    /**
     * 用户手工改过配置后，引擎回推的旧 Cookie 缓存必须作废。
     *
     * <p>历史 bug：LATEST_COOKIES 永不失效——用户粘贴新 cookie 保存后，
     * 下次 open 时 withLatestCookie 又把旧登录态静默覆盖回去，新值不生效
     * 且无提示。</p>
     */
    @Test
    public void retainOnlyDropsCookieCacheOfEditedConnection() {
        Connection original = new Connection("q", "qqmail-attach", ConnMaps.map("cookie", "C1"));
        Sessions.retainOnly(ConnMaps.list(original));
        // 引擎回推新 cookie C2（内存缓存记住它；sink 未注册，模拟落盘失败/未接）
        Sessions.onSessionUpdated(original, "C2", null);
        // 用户手工编辑保存：配置里现在是 C3
        Connection edited = original.with("q", "qqmail-attach", ConnMaps.map("cookie", "C3"));
        Sessions.retainOnly(ConnMaps.list(edited));
        Connection effective = Sessions.withLatestCookie(edited);
        assertEquals("用户手输的值不能被旧缓存覆盖", "C3", effective.config().get("cookie"));
        Sessions.retainOnly(ConnMaps.list());
    }

    /** 连接被删除：其 Cookie / sid 缓存同样作废，不留脏数据。 */
    @Test
    public void retainOnlyDropsCookieCacheOfDeletedConnection() {
        Connection doomed = new Connection("q", "qqmail-attach", ConnMaps.map("cookie", "C1"));
        Sessions.retainOnly(ConnMaps.list(doomed));
        Sessions.onSessionUpdated(doomed, "C2", "S2");
        Sessions.retainOnly(ConnMaps.list());
        // 同 id 的旧对象再来：不应再叠加已删除连接的缓存
        Connection effective = Sessions.withLatestCookie(doomed);
        assertEquals("已删除连接的缓存必须清空", "C1", effective.config().get("cookie"));
    }

    /** 配置未变的重载：缓存保留——sink 落盘失败的场景仍靠内存值兜底。 */
    @Test
    public void retainOnlyKeepsCookieCacheWhenUnchanged() {
        Connection stable = new Connection("q", "qqmail-attach", ConnMaps.map("cookie", "C1"));
        Sessions.retainOnly(ConnMaps.list(stable));
        Sessions.onSessionUpdated(stable, "C2", null);
        // 无变更重载（sink 落盘失败，配置仍是 C1）：缓存必须留着
        Sessions.retainOnly(ConnMaps.list(stable));
        Connection effective = Sessions.withLatestCookie(stable);
        assertEquals("配置没变时保留引擎回推的最新值（落盘失败的兜底）",
                "C2", effective.config().get("cookie"));
        Sessions.retainOnly(ConnMaps.list());
    }
}
