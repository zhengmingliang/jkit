package com.alianga.jkit.common.idgenerate;

import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SnowFlakeIdWorkerTest {
    @Test
    public void testNextIdPositiveAndUnique() {
        long a = SnowFlakeIdWorker.INSTANCE.nextId();
        long b = SnowFlakeIdWorker.INSTANCE.nextId();
        long c = SnowFlakeIdWorker.INSTANCE.nextId();
        assertTrue(a > 0);
        assertNotEquals(a, b);
        assertNotEquals(b, c);
    }

    @Test
    public void testNextStringId() {
        String id = SnowFlakeIdWorker.INSTANCE.nextStringId();
        assertTrue(id != null && id.length() > 0);
    }

    @Test
    public void testExpIdRoundTrip() {
        long id = SnowFlakeIdWorker.INSTANCE.nextId();
        IdInfo info = SnowFlakeIdWorker.INSTANCE.expId(id);
        assertTrue(info.getTimestamp() > 0);
        assertTrue(info.getBit() == 64);
        assertTrue(info.getSequence() >= 0);
    }

    @Test
    public void test53BitMode() {
        SnowFlakeIdWorker worker = new SnowFlakeIdWorker(SnowFlakeIdWorker.BIT_TYPE_53);
        long id = worker.nextId();
        assertTrue(id > 0);
        // 53位模式下ID不应超过 JS 安全整数范围
        assertTrue(id < (1L << 53));
        IdInfo info = worker.expId(id);
        assertTrue(info.getBit() == 53);
    }

    @Test
    public void testHex() {
        String hex = IdGenerator.hex();
        assertTrue(hex != null && hex.length() == 16);
    }
}
