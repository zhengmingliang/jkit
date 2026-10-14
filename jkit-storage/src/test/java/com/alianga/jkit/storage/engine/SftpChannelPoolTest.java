package com.alianga.jkit.storage.engine;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * SFTP 通道池借取：池满且通道全部作废（只减计数不回队列）时，等待者不能永远挂住。
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
}
