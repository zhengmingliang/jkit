package com.alianga.jkit.storage.webdav.versioned;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * WebDavRemoteStore：条件写 / replace / unsupported。
 *
 * @author 郑明亮
 */
public class WebDavRemoteStoreTest {

    private FakeWebDavServer fake;
    private WebDavRemoteStore store;

    @Before
    public void start() throws Exception {
        fake = FakeWebDavServer.startDefault();
        fake.setMode(FakeWebDavServer.Mode.STRICT_412);
        store = new WebDavRemoteStore(fake.config("data"));
    }

    @After
    public void stop() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    public void putIfAbsentSucceedsOnStrictServer() throws Exception {
        Path tmp = Files.createTempFile("p-", ".bin");
        Files.write(tmp, "hello".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putIfAbsent("a.bin", tmp);
        assertTrue(r.success());
        assertFalse(r.renamed());
        Optional<RemoteObject> st = store.stat("a.bin");
        assertTrue(st.isPresent());
        assertEquals(5L, st.get().size());
    }

    @Test
    public void putIfAbsentConflictOnExisting() throws Exception {
        Path tmp = Files.createTempFile("p-", ".bin");
        Files.write(tmp, "v1".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.putIfAbsent("b.bin", tmp).success());
        Files.write(tmp, "v2".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putIfAbsent("b.bin", tmp);
        assertTrue(r.preconditionFailed());
    }

    @Test
    public void putReplaceHonorsReplaceHeaderOnRenameMode() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("data"));
        Path tmp = Files.createTempFile("p-", ".bin");
        Files.write(tmp, "v1".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.putReplace("c.bin", tmp).success());
        Files.write(tmp, "v2".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putReplace("c.bin", tmp);
        assertTrue(r.success());
        assertFalse(r.renamed());
        Path got = Files.createTempFile("g-", ".bin");
        store.get("c.bin", got);
        assertEquals("v2", new String(Files.readAllBytes(got), StandardCharsets.UTF_8));
    }

    @Test
    public void probeDetectsStrict412() throws Exception {
        StoreCapabilities caps = store.probeCapabilities();
        assertTrue(caps.writable());
        assertTrue(caps.conditionalPut());
        assertEquals(StoreCapabilities.OverwritePolicy.REJECTS_OR_412, caps.overwritePolicy());
    }

    @Test
    public void probeDetectsRenameAndReplace() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("data"));
        StoreCapabilities caps = store.probeCapabilities();
        assertTrue(caps.writable());
        assertFalse(caps.conditionalPut());
        assertEquals(StoreCapabilities.OverwritePolicy.RENAMES_ON_EXISTS, caps.overwritePolicy());
        assertTrue(caps.replaceHeader());
        Path tmp = Files.createTempFile("p-", ".bin");
        Files.write(tmp, "x".getBytes(StandardCharsets.UTF_8));
        // RENAMES：恢复 If-None-Match 原子 PUT，新名应成功且不改名
        PutResult fresh = store.putIfAbsent("n.bin", tmp);
        assertTrue(fresh.success());
        assertFalse(fresh.renamed());
        assertFalse(fresh.unsupported());
    }

    @Test
    public void putIfAbsentOnRenameModeDetectsConflictViaLocation() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("data"));
        store.probeCapabilities();
        Path tmp = Files.createTempFile("p-", ".bin");
        Files.write(tmp, "v1".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.putReplace("race.bin", tmp).success());
        Files.write(tmp, "v2".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putIfAbsent("race.bin", tmp);
        assertTrue(r.renamed());
        assertFalse(r.unsupported());
        assertTrue(r.effectiveName() != null && !r.effectiveName().equals("race.bin"));
    }

    @Test
    public void putIfAbsentUnsupportedOnSilentOverwrite() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.IGNORE_HEADERS_OVERWRITE);
        store = new WebDavRemoteStore(fake.config("data"));
        store.probeCapabilities();
        Path tmp = Files.createTempFile("p-", ".bin");
        Files.write(tmp, "x".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putIfAbsent("fresh-silent.bin", tmp);
        assertTrue(r.unsupported());
    }

    @Test
    public void renamePathMatchesCopypartyFormat() {
        String out = FakeWebDavServer.renamePath("/data/x.zip");
        assertTrue(out.matches("^/data/x\\.zip-\\d+-[0-9a-f]{6}\\.zip$"));
        String ver = FakeWebDavServer.renamePath(
                "/backups/reqdesk-v000002-hosta-abcdef01.zip");
        assertTrue(ver.matches(
                "^/backups/reqdesk-v000002-hosta-abcdef01\\.zip-\\d+-[0-9a-f]{6}\\.zip$"));
        assertFalse(VersionId.parse(ver.substring(ver.lastIndexOf('/') + 1)).isPresent());
    }

    @Test
    public void putIfAbsentRenameLocationUsesCopypartyShape() throws Exception {
        fake.setMode(FakeWebDavServer.Mode.RENAME_ON_EXISTS);
        store = new WebDavRemoteStore(fake.config("data"));
        store.probeCapabilities();
        Path tmp = Files.createTempFile("p-", ".zip");
        Files.write(tmp, "v1".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.putReplace("probe.zip", tmp).success());
        Files.write(tmp, "v2".getBytes(StandardCharsets.UTF_8));
        PutResult r = store.putIfAbsent("probe.zip", tmp);
        assertTrue(r.renamed());
        String eff = r.effectiveName();
        assertTrue(eff, eff.matches("(?i)probe\\.zip-\\d+-[0-9a-f]{6}\\.zip"));
    }

}
