package com.alianga.jkit.storage.engine;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SFTP 通道池借取：池满作废可恢复；无 I/O 才超时；有 I/O 时等待者不误报。
 *
 * @author 郑明亮
 */
public class SftpChannelPoolTest {

    @Test(timeout = 10_000)
    public void waiterRecoversWhenFullPoolChannelsAreInvalidated() throws Exception {
        LinkedBlockingQueue<String> idle = new LinkedBlockingQueue<>();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        int poolSize = 2;
        String a = SftpFileStorage.borrowFrom(idle, created, poolSize, () -> false, () -> "ch" + seq.incrementAndGet());
        String b = SftpFileStorage.borrowFrom(idle, created, poolSize, () -> false, () -> "ch" + seq.incrementAndGet());
        assertEquals(2, created.get());
        CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() ->
                SftpFileStorage.borrowFrom(idle, created, poolSize, () -> false, () -> "ch" + seq.incrementAndGet()));
        Thread.sleep(300);
        // 两条通道都作废：release(broken) 只做 created--，不往 idle 放
        created.decrementAndGet();
        created.decrementAndGet();
        assertEquals("ch3", waiter.get(5, TimeUnit.SECONDS));
        assertEquals(1, created.get());
    }

    @Test(timeout = 10_000)
    public void borrowTimesOutWhenPoolStaysFull() {
        LinkedBlockingQueue<String> idle = new LinkedBlockingQueue<>();
        AtomicInteger created = new AtomicInteger(1);
        long start = System.nanoTime();
        try {
            SftpFileStorage.borrowFrom(idle, created, 1, () -> false, () -> "new", 500L);
            org.junit.Assert.fail("池一直满应超时");
        } catch (com.alianga.jkit.storage.StorageException e) {
            assertEquals(com.alianga.jkit.storage.StorageErrorCode.TIMEOUT, e.code());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("应在超时附近返回，实际 " + elapsedMs, elapsedMs >= 400 && elapsedMs < 3000);
        assertEquals(1, created.get());
    }

    @Test(timeout = 10_000)
    public void waiterSucceedsWhileHoldersKeepDoingIoBeyondTimeout() throws Exception {
        LinkedBlockingQueue<String> idle = new LinkedBlockingQueue<>();
        AtomicInteger created = new AtomicInteger();
        AtomicLong lastIo = new AtomicLong(System.nanoTime());
        int poolSize = 1;
        String held = SftpFileStorage.borrowFrom(idle, created, poolSize, () -> false,
                () -> "held", 500L, lastIo);
        assertEquals("held", held);
        assertEquals(1, created.get());

        CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() ->
                SftpFileStorage.borrowFrom(idle, created, poolSize, () -> false,
                        () -> "new", 400L, lastIo));

        // 持有者持续做 I/O 超过超时窗口，然后归还
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(900);
        while (System.nanoTime() < end) {
            lastIo.set(System.nanoTime());
            Thread.sleep(50);
        }
        idle.offer(held);
        lastIo.set(System.nanoTime());

        assertEquals("held", waiter.get(3, TimeUnit.SECONDS));
        assertEquals(1, created.get());
    }

    @Test(timeout = 10_000)
    public void borrowTimesOutWhenPoolStuckWithNoIo() {
        LinkedBlockingQueue<String> idle = new LinkedBlockingQueue<>();
        AtomicInteger created = new AtomicInteger(1);
        AtomicLong lastIo = new AtomicLong(System.nanoTime());
        long start = System.nanoTime();
        try {
            SftpFileStorage.borrowFrom(idle, created, 1, () -> false, () -> "new", 500L, lastIo);
            org.junit.Assert.fail("无 I/O 的满池应超时");
        } catch (com.alianga.jkit.storage.StorageException e) {
            assertEquals(com.alianga.jkit.storage.StorageErrorCode.TIMEOUT, e.code());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("应在超时附近返回，实际 " + elapsedMs, elapsedMs >= 400 && elapsedMs < 3000);
        assertEquals(1, created.get());
    }
}
