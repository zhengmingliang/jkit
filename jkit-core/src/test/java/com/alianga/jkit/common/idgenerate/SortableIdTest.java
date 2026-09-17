package com.alianga.jkit.common.idgenerate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ULID 与 UUIDv7 的回归：格式、可排序性、解析往返、并发唯一、门面入口。
 */
public class SortableIdTest {

    @Test
    public void ulidFormat() {
        String id = ULID.next();
        assertEquals(ULID.LENGTH, id.length());
        assertTrue("ULID 只应含 Crockford Base32 字符: " + id, id.matches("[0-9A-HJKMNP-TV-Z]{26}"));
    }

    @Test
    public void ulidTimestampIsCurrentTime() {
        long before = System.currentTimeMillis();
        String id = ULID.next();
        long after = System.currentTimeMillis();
        long ts = ULID.parse(id).timestamp();
        assertTrue("时间戳应在生成区间内: " + ts, ts >= before && ts <= after);
    }

    @Test
    public void ulidLexicographicOrderMatchesTime() throws Exception {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 500; i++) {
            ids.add(ULID.next());
            if (i % 50 == 0) {
                Thread.sleep(2);
            }
        }
        List<String> sorted = new ArrayList<String>(ids);
        Collections.sort(sorted);
        // 同一毫秒内的顺序由随机部分决定，只要求解析出的时间戳非递减
        long prev = -1;
        for (String id : sorted) {
            long ts = ULID.parse(id).timestamp();
            assertTrue("按字典序排序后时间戳不应回退", ts >= prev);
            prev = ts;
        }
    }

    @Test
    public void ulidMonotonicIsStrictlyIncreasing() {
        String prev = ULID.nextMonotonic();
        for (int i = 0; i < 2000; i++) {
            String cur = ULID.nextMonotonic();
            assertTrue("单调 ULID 应严格递增: " + prev + " -> " + cur, cur.compareTo(prev) > 0);
            prev = cur;
        }
    }

    @Test
    public void ulidParseRoundTrip() {
        String id = ULID.next();
        ULID.Value value = ULID.parse(id);
        assertEquals(id, value.toString());
        assertEquals(10, value.randomBytes().length);
    }

    @Test
    public void ulidBytesRoundTrip() {
        String id = ULID.next();
        byte[] bytes = ULID.toBytes(id);
        assertEquals(16, bytes.length);
        assertEquals(id, ULID.fromBytes(bytes));

        try {
            ULID.fromBytes(new byte[15]);
            fail("非 16 字节应拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("16 bytes"));
        }
    }

    @Test
    public void ulidIsUlidValidation() {
        assertTrue(ULID.isUlid(ULID.next()));
        assertTrue("大小写不敏感", ULID.isUlid(ULID.next().toLowerCase()));
        // Crockford 容错：I/L 视作 1，O 视作 0
        assertTrue(ULID.isUlid("01HZYJKMNPQRSTVWXYZ0123456".replace('1', 'I')));
        assertTrue(ULID.isUlid("01HZYJKMNPQRSTVWXYZ0123456".replace('1', 'L')));
        assertTrue(ULID.isUlid("01HZYJKMNPQRSTVWXYZ0123456".replace('0', 'O')));
        assertFalse(ULID.isUlid(null));
        assertFalse("长度不对", ULID.isUlid("01HZY"));
        assertFalse("含 U", ULID.isUlid("01HZYJKMNPQRSTVWXYU1234567"));
        assertFalse("含连字符", ULID.isUlid("01HZY-JKMNPQRSTVWXYZ012345"));
    }

    @Test
    public void ulidRejectsIllegalInput() {
        try {
            ULID.parse("too-short");
            fail("长度不合法应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("26"));
        }
        try {
            ULID.parse("01HZYJKMNPQRSTVWXYU1234567");
            fail("含 U 应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Illegal"));
        }
    }

    @Test
    public void ulidUniqueUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Set<String>>> tasks = new ArrayList<Callable<Set<String>>>();
            for (int t = 0; t < threads; t++) {
                tasks.add(new Callable<Set<String>>() {
                    @Override
                    public Set<String> call() {
                        Set<String> local = new HashSet<String>();
                        for (int i = 0; i < perThread; i++) {
                            local.add(ULID.nextMonotonic());
                        }
                        return local;
                    }
                });
            }
            Set<String> all = new HashSet<String>();
            for (Future<Set<String>> f : pool.invokeAll(tasks)) {
                all.addAll(f.get());
            }
            assertEquals("并发 16000 个 ULID 不应重复", threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void uuidV7Layout() {
        UUID uuid = UUIDv7.next();
        assertEquals("version 必须是 7", 7, uuid.version());
        assertEquals("variant 必须是 RFC 4122（2）", 2, uuid.variant());
        assertTrue(UUIDv7.isV7(uuid));
        assertEquals(36, uuid.toString().length());
    }

    @Test
    public void uuidV7TimestampIsCurrentTime() {
        long before = System.currentTimeMillis();
        UUID uuid = UUIDv7.next();
        long after = System.currentTimeMillis();
        long ts = UUIDv7.timestamp(uuid);
        assertTrue("时间戳应在生成区间内: " + ts, ts >= before && ts <= after);
    }

    @Test
    public void uuidV7BeatsV4OnSortability() throws Exception {
        // v7 的时间戳在高位：连续生成的 ID 按字符串排序后时间戳应非递减
        List<UUID> ids = new ArrayList<UUID>();
        for (int i = 0; i < 500; i++) {
            ids.add(UUIDv7.next());
            if (i % 50 == 0) {
                Thread.sleep(2);
            }
        }
        List<UUID> sorted = new ArrayList<UUID>(ids);
        Collections.sort(sorted);
        long prev = -1;
        for (UUID uuid : sorted) {
            long ts = UUIDv7.timestamp(uuid);
            assertTrue(ts >= prev);
            prev = ts;
        }
    }

    @Test
    public void uuidV7MonotonicIsStrictlyIncreasing() {
        UUID prev = UUIDv7.nextMonotonic();
        for (int i = 0; i < 2000; i++) {
            UUID cur = UUIDv7.nextMonotonic();
            assertTrue("单调 UUIDv7 应严格递增", cur.compareTo(prev) > 0);
            assertTrue(UUIDv7.isV7(cur));
            prev = cur;
        }
    }

    @Test
    public void uuidV7IsV7RejectsOthers() {
        assertFalse(UUIDv7.isV7(null));
        assertFalse("UUIDv4 不是 v7", UUIDv7.isV7(UUID.randomUUID()));
    }

    @Test
    public void uuidV7TimestampRejectsNull() {
        try {
            UUIDv7.timestamp(null);
            fail("null 应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("null"));
        }
    }

    @Test
    public void uuidV7UniqueUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Set<UUID>>> tasks = new ArrayList<Callable<Set<UUID>>>();
            for (int t = 0; t < threads; t++) {
                tasks.add(new Callable<Set<UUID>>() {
                    @Override
                    public Set<UUID> call() {
                        Set<UUID> local = new HashSet<UUID>();
                        for (int i = 0; i < perThread; i++) {
                            local.add(UUIDv7.nextMonotonic());
                        }
                        return local;
                    }
                });
            }
            Set<UUID> all = new HashSet<UUID>();
            for (Future<Set<UUID>> f : pool.invokeAll(tasks)) {
                all.addAll(f.get());
            }
            assertEquals("并发 16000 个 UUIDv7 不应重复", threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void idGeneratorFacade() {
        assertEquals(26, IdGenerator.ulid().length());
        assertTrue(UUIDv7.isV7(IdGenerator.uuidV7()));
        assertEquals(36, IdGenerator.uuidV7String().length());
        // 既有雪花入口不受影响
        assertTrue(IdGenerator.id() > 0);
        assertEquals(16, IdGenerator.hex().length());
    }
}
