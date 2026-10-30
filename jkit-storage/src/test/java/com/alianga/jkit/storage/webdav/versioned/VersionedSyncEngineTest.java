package com.alianga.jkit.storage.webdav.versioned;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 版本化同步：事实源为 zip 名；改名冲突清理；索引失败不影响正确性。
 *
 * @author 郑明亮
 */
public class VersionedSyncEngineTest {

    private FakeWebDavServer fake;
    private WebDavRemoteStore store;

    @Before
    public void start() throws Exception {
        fake = FakeWebDavServer.startDefault();
        fake.setMode(FakeWebDavServer.Mode.STRICT_412);
        store = new WebDavRemoteStore(fake.config("backups"));
    }

    @After
    public void stop() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    public void versionIdRoundTrip() {
        VersionId v = VersionId.of(7, "My Host!", "abcdef012345");
        assertEquals("reqdesk-v000007-my-host-abcdef01.zip", v.fileName());
        Optional<VersionId> p = VersionId.parse(v.fileName());
        assertTrue(p.isPresent());
        assertEquals(7L, p.get().seq());
        assertEquals("my-host", p.get().host());
        assertEquals("abcdef01", p.get().hash8());
    }

    @Test
    public void uploadIncrementsSeqAndSetsHead() throws Exception {
        VersionedSyncEngine eng = new VersionedSyncEngine(store, "hostA", 20);
        Path zip1 = Files.createTempFile("v1-", ".zip");
        Files.write(zip1, "content-one-aaaa".getBytes(StandardCharsets.UTF_8));
        VersionedSyncEngine.UploadOutcome o1 = eng.uploadNewVersion(zip1, LocalBase.EMPTY, null);
        assertEquals(1L, o1.version().seq());
        assertTrue(o1.version().fileName().startsWith("reqdesk-v000001-hosta-"));

        Path zip2 = Files.createTempFile("v2-", ".zip");
        Files.write(zip2, "content-two-bbbb".getBytes(StandardCharsets.UTF_8));
        VersionedSyncEngine.UploadOutcome o2 = eng.uploadNewVersion(
                zip2, LocalBase.of(o1.version(), o1.contentHash()), null);
        assertEquals(2L, o2.version().seq());

        List<VersionId> all = eng.listVersions();
        assertEquals(2, all.size());
        assertEquals(2L, eng.resolveHead().get().seq());
    }

    @Test
    public void conflictWhenRemoteAhead() throws Exception {
        VersionedSyncEngine eng = new VersionedSyncEngine(store, "hostA", 20);
        Path zip1 = Files.createTempFile("v1-", ".zip");
        Files.write(zip1, "aaa".getBytes(StandardCharsets.UTF_8));
        VersionedSyncEngine.UploadOutcome o1 = eng.uploadNewVersion(zip1, LocalBase.EMPTY, null);

        VersionedSyncEngine engB = new VersionedSyncEngine(store, "hostB", 20);
        Path zip2 = Files.createTempFile("v2-", ".zip");
        Files.write(zip2, "bbb".getBytes(StandardCharsets.UTF_8));
        engB.uploadNewVersion(zip2, LocalBase.of(o1.version(), o1.contentHash()), null);

        Path zip3 = Files.createTempFile("v3-", ".zip");
        Files.write(zip3, "ccc".getBytes(StandardCharsets.UTF_8));
        try {
            eng.uploadNewVersion(zip3, LocalBase.of(o1.version(), o1.contentHash()),
                    new VersionedSyncEngine.ConflictHandler() {
                        @Override
                        public ConflictDecision onConflict(VersionId head, LocalBase base) {
                            return ConflictDecision.CANCEL;
                        }
                    });
            fail("should conflict");
        } catch (VersionedSyncEngine.SyncConflictException e) {
            assertTrue(e.getMessage().contains("取消") || e.kind() != null);
        }
    }

    @Test
    public void renameOnExistsTreatsAsConflictAndDeletesStray() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("backups"));
        // 先探测能力，使 putIfAbsent 在无条件写时返回 unsupported
        store.probeCapabilities();
        VersionedSyncEngine eng = new VersionedSyncEngine(store, "hostA", 20);

        Path zip = Files.createTempFile("race-", ".zip");
        Files.write(zip, "payload-zzzzzzzz".getBytes(StandardCharsets.UTF_8));
        String hash8 = VersionedSyncEngine.sha256Hex(Files.readAllBytes(zip)).substring(0, 8);
        VersionId next = VersionId.of(1, "hosta", hash8);
        // 预置同名：后续上传应递增
        store.putReplace(next.fileName(), zip);

        VersionedSyncEngine.UploadOutcome o = eng.uploadNewVersion(zip, LocalBase.EMPTY,
                new VersionedSyncEngine.ConflictHandler() {
                    @Override
                    public ConflictDecision onConflict(VersionId h, LocalBase b) {
                        return ConflictDecision.UPLOAD_AS_NEW_VERSION;
                    }
                });
        assertTrue(o.version().seq() >= 1);
        long stray = 0;
        for (RemoteObject r : store.list()) {
            if (r.name().endsWith(".zip") && !VersionId.parse(r.name()).isPresent()) {
                stray++;
            }
        }
        Optional<VersionId> head = eng.resolveHead();
        assertTrue(head.isPresent());
        assertTrue(VersionId.parse(head.get().fileName()).isPresent());
        assertEquals(0, stray);
    }

    @Test
    public void indexFailureDoesNotBreakUpload() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("backups"));
        store.probeCapabilities();
        VersionedSyncEngine eng = new VersionedSyncEngine(store, "box", 5);
        Path zip = Files.createTempFile("idx-", ".zip");
        Files.write(zip, "index-independent".getBytes(StandardCharsets.UTF_8));
        VersionedSyncEngine.UploadOutcome o = eng.uploadNewVersion(zip, LocalBase.EMPTY, null);
        assertEquals(1L, o.version().seq());
        assertTrue(eng.resolveHead().isPresent());
    }

    @Test
    public void putIfAbsentReturnsUnsupportedWithoutConditionalWrite() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("backups"));
        store.probeCapabilities();
        Path tmp = Files.createTempFile("u-", ".bin");
        Files.write(tmp, "x".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putIfAbsent("fresh.bin", tmp);
        assertTrue(r.unsupported());
    }
}
