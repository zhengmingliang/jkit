package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePlatform;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 邮件存储单测：内存 FakeMailGateway，覆盖一期扁平 + 二期逻辑路径/mkdir/分卷。
 *
 * @author 郑明亮
 */
public class MailFileStorageTest {
    @Test
    public void asciiSubjectPlainInStoredMime() {
        byte[] mime = MailFileStorage.buildStoredMime("hello.txt",
                "payload".getBytes(StandardCharsets.UTF_8));
        String text = new String(mime, StandardCharsets.UTF_8);
        assertTrue(text, text.contains("Subject: hello.txt\r\n"));
        assertFalse(text, text.contains("Subject: =?UTF-8?B?"));
    }

    @Test
    public void uploadDownloadRoundtrip() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        byte[] data = "你好邮件盘".getBytes(StandardCharsets.UTF_8);
        fs.upload("hello.txt", data);
        assertTrue(fs.exists("hello.txt"));
        assertArrayEquals(data, fs.readBytes("hello.txt"));
        StorageEntry entry = fs.stat("hello.txt");
        assertEquals("hello.txt", entry.name());
        assertEquals(data.length, entry.size());
        assertNotNull(entry.etag());
        assertEquals(entry.etag(), fake.lastUidFor("hello.txt"));
    }

    @Test
    public void listNamesAndEtagAreUid() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fs.upload("a.bin", new byte[]{1, 2, 3});
        fs.upload("b.bin", new byte[]{4, 5});
        List<StorageEntry> listed = fs.list("");
        assertEquals(2, listed.size());
        Map<String, StorageEntry> byName = new LinkedHashMap<String, StorageEntry>();
        for (StorageEntry e : listed) {
            byName.put(e.name(), e);
            assertFalse("list name 不应含路径前缀", e.name().contains("/"));
            assertEquals(e.name(), e.path());
            assertEquals(fake.lastUidFor(e.name()), e.etag());
        }
        assertTrue(byName.containsKey("a.bin"));
        assertTrue(byName.containsKey("b.bin"));
    }

    @Test
    public void existsFalseStatAndDownloadNotFound() {
        FileStorage fs = open(new FakeMailGateway());
        assertFalse(fs.exists("missing.txt"));
        try {
            fs.stat("missing.txt");
            fail("stat 应 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        try {
            fs.download("missing.txt", new ByteArrayOutputStream());
            fail("download 应 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void deleteMissingIsSuccess() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fs.delete("ghost.txt");
        fs.delete("still-missing.txt");
    }

    @Test
    public void overwriteDeletesThenAppends() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fs.upload("same.txt", "first".getBytes(StandardCharsets.UTF_8));
        String uid1 = fake.lastUidFor("same.txt");
        fs.upload("same.txt", "second".getBytes(StandardCharsets.UTF_8));
        assertEquals("second", fs.readString("same.txt"));
        assertEquals(1, fake.deleteCount);
        assertEquals(2, fake.appendCount);
        assertFalse(uid1.equals(fake.lastUidFor("same.txt")));
    }

    @Test
    public void oversizeConflictWhenBelowChunkThresholdDisabled() {
        // maxAttachmentBytes 极小且仍走分卷：不再 CONFLICT，见 chunkedUploadDownloadDelete
        StorageConfig cfg = StorageConfig.empty()
                .set("host", "imap.example.com")
                .set("user", "u")
                .set("password", "p")
                .set("maxAttachmentBytes", "10");
        FileStorage fs = new MailFileStorage(cfg, new FakeMailGateway());
        fs.upload("big.bin", new byte[11]);
        assertTrue(fs.exists("big.bin"));
        assertEquals(11, fs.stat("big.bin").size());
    }

    @Test
    public void qqMailPlatformRegistered() {
        StoragePlatform qq = FileStorages.platform("qq-mail");
        assertNotNull(qq);
        assertEquals("mail", qq.engine());
        assertEquals("imap.qq.com", qq.defaults().get("host"));
        assertEquals("993", qq.defaults().get("imapPort"));
        assertEquals("true", qq.defaults().get("ssl"));
        assertEquals("jkit-mail-disk", qq.defaults().get("basePath"));
        assertEquals(String.valueOf(45L * 1024L * 1024L), qq.defaults().get("maxAttachmentBytes"));
        assertEquals("1", qq.defaults().get("channelPool"));
        assertNotNull(findField(qq, "channelPool"));
        StoragePlatform generic = FileStorages.platform("mail");
        assertNotNull(generic);
        assertEquals("mail", generic.engine());
        assertEquals("1", generic.defaults().get("channelPool"));
    }

    @Test
    public void copyRenameStillUnsupported() {
        FileStorage fs = open(new FakeMailGateway());
        assertUnsupported(() -> fs.copy("a", "b"));
        assertUnsupported(() -> fs.rename("a", "b"));
    }

    @Test
    public void mkdirListNestedUploadRoundtrip() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fs.mkdir("docs");
        assertTrue(fs.exists("docs"));
        assertTrue(fs.stat("docs").isDirectory());

        List<StorageEntry> root = fs.list("");
        assertEquals(1, root.size());
        assertEquals("docs", root.get(0).path());
        assertTrue(root.get(0).isDirectory());

        // 空目录 list 不暴露 .keep
        assertTrue(fs.list("docs").isEmpty());

        fs.upload("docs/a.txt", "nested".getBytes(StandardCharsets.UTF_8));
        assertTrue(fs.exists("docs/a.txt"));
        assertEquals("nested", fs.readString("docs/a.txt"));

        List<StorageEntry> kids = fs.list("docs");
        assertEquals(1, kids.size());
        assertEquals("a.txt", kids.get(0).name());
        assertEquals("docs/a.txt", kids.get(0).path());

        fs.mkdir("docs/sub");
        fs.upload("docs/sub/b.txt", "deep".getBytes(StandardCharsets.UTF_8));
        List<StorageEntry> docsKids = fs.list("docs");
        Map<String, StorageEntry> byPath = new LinkedHashMap<String, StorageEntry>();
        for (StorageEntry e : docsKids) {
            byPath.put(e.path(), e);
        }
        assertTrue(byPath.containsKey("docs/a.txt"));
        assertTrue(byPath.containsKey("docs/sub"));
        assertTrue(byPath.get("docs/sub").isDirectory());
        assertEquals("deep", fs.readString("docs/sub/b.txt"));
    }

    @Test
    public void mkdirIdempotentAndConflictWithFile() {
        FileStorage fs = open(new FakeMailGateway());
        fs.mkdir("d");
        fs.mkdir("d");
        fs.upload("file.txt", "x".getBytes(StandardCharsets.UTF_8));
        try {
            fs.mkdir("file.txt");
            fail("同名文件应 CONFLICT");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
    }

    @Test
    public void chunkedUploadDownloadDelete() {
        StorageConfig cfg = StorageConfig.empty()
                .set("host", "imap.example.com")
                .set("user", "u")
                .set("password", "p")
                .set("basePath", "jkit-mail-disk")
                .set("maxAttachmentBytes", "5");
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = new MailFileStorage(cfg, fake);
        byte[] data = "abcdefghij".getBytes(StandardCharsets.UTF_8); // 10 bytes → 2 parts
        fs.upload("big.bin", data);
        assertTrue(fs.exists("big.bin"));
        assertFalse("list 应隐藏 part", fs.exists("big.bin.part1") && visibleInList(fs, "big.bin.part1"));
        assertEquals(10, fs.stat("big.bin").size());
        assertArrayEquals(data, fs.readBytes("big.bin"));

        List<StorageEntry> listed = fs.list("");
        assertEquals(1, listed.size());
        assertEquals("big.bin", listed.get(0).name());
        assertEquals(10, listed.get(0).size());

        // 底层应有分卷
        assertTrue(fake.exists("big.bin.part1"));
        assertTrue(fake.exists("big.bin.part2"));
        assertFalse(fake.exists("big.bin"));

        fs.delete("big.bin");
        assertFalse(fs.exists("big.bin"));
        assertFalse(fake.exists("big.bin.part1"));
        assertFalse(fake.exists("big.bin.part2"));
    }

    @Test
    public void chunkedNestedPathAndOverwrite() {
        StorageConfig cfg = StorageConfig.empty()
                .set("host", "imap.example.com")
                .set("user", "u")
                .set("password", "p")
                .set("maxAttachmentBytes", "4");
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = new MailFileStorage(cfg, fake);
        fs.mkdir("docs");
        byte[] v1 = "12345678".getBytes(StandardCharsets.UTF_8);
        fs.upload("docs/big.bin", v1);
        assertArrayEquals(v1, fs.readBytes("docs/big.bin"));
        byte[] v2 = "ABCD".getBytes(StandardCharsets.UTF_8); // 恰好 1 片
        fs.upload("docs/big.bin", v2);
        assertArrayEquals(v2, fs.readBytes("docs/big.bin"));
        assertFalse(fake.exists("docs/big.bin.part1"));
        assertTrue(fake.exists("docs/big.bin"));
    }

    @Test
    public void incompletePartsGapRejectsExistsStatDownload() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fake.putEntry("big.bin.part1", "aaaa".getBytes(StandardCharsets.UTF_8));
        fake.putEntry("big.bin.part3", "cccc".getBytes(StandardCharsets.UTF_8));
        assertIncompleteVolumeConflict(fs, "big.bin");
        // 不得向输出流写入任何字节
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            fs.download("big.bin", out);
            fail("缺 part 下载应 CONFLICT");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        assertEquals(0, out.size());
    }

    @Test
    public void incompletePartsOnlyPart2Rejects() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fake.putEntry("solo.bin.part2", "bbbb".getBytes(StandardCharsets.UTF_8));
        assertIncompleteVolumeConflict(fs, "solo.bin");
    }

    @Test
    public void uploadReservedPartSuffixRejected() {
        FileStorage fs = open(new FakeMailGateway());
        try {
            fs.upload("foo.part1", "x".getBytes(StandardCharsets.UTF_8));
            fail("保留后缀 .partN 应 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
        try {
            fs.upload("docs/bar.part12", "y".getBytes(StandardCharsets.UTF_8));
            fail("嵌套保留后缀也应 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    private static void assertIncompleteVolumeConflict(FileStorage fs, String logical) {
        try {
            fs.exists(logical);
            fail("残缺分卷 exists 应 CONFLICT: " + logical);
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        try {
            fs.stat(logical);
            fail("残缺分卷 stat 应 CONFLICT: " + logical);
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        try {
            fs.download(logical, new ByteArrayOutputStream());
            fail("残缺分卷 download 应 CONFLICT: " + logical);
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
    }

    @Test
    public void listDepthForSyncWalk() {
        FileStorage fs = open(new FakeMailGateway());
        fs.mkdir("a");
        fs.mkdir("a/b");
        fs.upload("a/b/c.txt", "c".getBytes(StandardCharsets.UTF_8));
        fs.upload("root.txt", "r".getBytes(StandardCharsets.UTF_8));

        List<StorageEntry> root = fs.list("");
        Map<String, StorageEntry> rootMap = indexByPath(root);
        assertTrue(rootMap.containsKey("a"));
        assertTrue(rootMap.get("a").isDirectory());
        assertTrue(rootMap.containsKey("root.txt"));
        assertFalse(rootMap.containsKey("a/b"));
        assertFalse(rootMap.containsKey("a/b/c.txt"));

        List<StorageEntry> aKids = fs.list("a");
        Map<String, StorageEntry> aMap = indexByPath(aKids);
        assertTrue(aMap.containsKey("a/b"));
        assertTrue(aMap.get("a/b").isDirectory());
        assertFalse(aMap.containsKey("a/b/c.txt"));

        List<StorageEntry> bKids = fs.list("a/b");
        assertEquals(1, bKids.size());
        assertEquals("a/b/c.txt", bKids.get(0).path());
    }

    @Test
    public void deleteDirectoryRemovesDescendants() {
        FileStorage fs = open(new FakeMailGateway());
        fs.mkdir("docs");
        fs.upload("docs/a.txt", "x".getBytes(StandardCharsets.UTF_8));
        fs.mkdir("docs/sub");
        fs.upload("docs/sub/b.txt", "y".getBytes(StandardCharsets.UTF_8));
        fs.delete("docs");
        assertFalse(fs.exists("docs"));
        assertFalse(fs.exists("docs/a.txt"));
        assertFalse(fs.exists("docs/sub/b.txt"));
    }

    @Test
    public void rootExistsAndLists() {
        FileStorage fs = open(new FakeMailGateway());
        assertTrue(fs.exists(""));
        assertEquals("", fs.stat("").path());
        assertTrue(fs.stat("").isDirectory());
        assertTrue(fs.list("").isEmpty());
    }

    @Test
    public void productionCtorConnectFailureOnUnreachableHost() {
        try {
            new MailFileStorage(StorageConfig.empty()
                    .set("host", "127.0.0.1")
                    .set("imapPort", "1")
                    .set("ssl", "false")
                    .set("user", "u@qq.com")
                    .set("password", "auth"));
            fail("不可达主机应 CONNECT_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONNECT_FAILED, e.code());
        }
    }

    @Test
    public void rfc2047DecodeWordsBAndQ() {
        String plain = "hello.txt";
        String b = "=?UTF-8?B?"
                + java.util.Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8))
                + "?=";
        assertEquals(plain, ImapMailSession.decodeMimeWords(b));
        assertEquals("你好.txt", ImapMailSession.decodeMimeWords("=?UTF-8?B?"
                + java.util.Base64.getEncoder().encodeToString("你好.txt".getBytes(StandardCharsets.UTF_8))
                + "?="));
        assertEquals("你", ImapMailSession.decodeMimeWords("=?UTF-8?Q?=E4=BD=A0?="));
        assertEquals("ab", ImapMailSession.decodeMimeWords(
                "=?UTF-8?B?YQ==?= =?UTF-8?B?Yg==?="));
        assertTrue(ImapMailSession.namesMatch(b, plain));
        assertTrue(ImapMailSession.namesMatch(plain, b));
        assertTrue(ImapMailSession.namesMatch(b, b));
        assertFalse(ImapMailSession.namesMatch(b, "other.txt"));
    }

    @Test
    public void encodedSubjectFromMimeMatchesPlainPath() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        byte[] mime = MailFileStorage.buildStoredMime("report-中文.txt",
                "payload".getBytes(StandardCharsets.UTF_8));
        String mimeText = new String(mime, StandardCharsets.UTF_8);
        int at = mimeText.indexOf("Subject: ");
        assertTrue("MIME 应含 Subject", at >= 0);
        int end = mimeText.indexOf("\r\n", at);
        String subject = mimeText.substring(at + "Subject: ".length(), end).trim();
        fake.putEncodedSubjectEntry(subject, "payload".getBytes(StandardCharsets.UTF_8));

        assertTrue(fs.exists("report-中文.txt"));
        assertEquals("report-中文.txt", fs.stat("report-中文.txt").name());
        assertEquals("payload", fs.readString("report-中文.txt"));
        List<StorageEntry> listed = fs.list("");
        assertEquals(1, listed.size());
        assertEquals("report-中文.txt", listed.get(0).name());
        assertFalse("list 不应仍是 encoded-word", listed.get(0).name().contains("=?"));
        fs.delete("report-中文.txt");
        assertFalse(fs.exists("report-中文.txt"));
    }

    @Test
    public void asciiRoundtripStillWorksWhenSubjectPlain() {
        FakeMailGateway fake = new FakeMailGateway();
        FileStorage fs = open(fake);
        fs.upload("plain-ascii.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(fs.exists("plain-ascii.txt"));
        assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), fs.readBytes("plain-ascii.txt"));
    }

    @Test
    public void channelPoolConfigAccepted() {
        FakeMailGateway fake = new FakeMailGateway();
        MailFileStorage fs = new MailFileStorage(StorageConfig.empty()
                .set("host", "imap.example.com")
                .set("user", "u")
                .set("password", "p")
                .set("channelPool", "4"), fake);
        assertEquals(4, fs.channelPool());
    }

    private static boolean visibleInList(FileStorage fs, String path) {
        for (StorageEntry e : fs.list("")) {
            if (path.equals(e.path())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, StorageEntry> indexByPath(List<StorageEntry> entries) {
        Map<String, StorageEntry> map = new LinkedHashMap<String, StorageEntry>();
        for (StorageEntry e : entries) {
            map.put(e.path(), e);
        }
        return map;
    }

    private static com.alianga.jkit.storage.StorageField findField(StoragePlatform platform, String key) {
        for (com.alianga.jkit.storage.StorageField f : platform.fields()) {
            if (key.equals(f.key())) {
                return f;
            }
        }
        return null;
    }

    private static FileStorage open(FakeMailGateway fake) {
        return new MailFileStorage(StorageConfig.empty()
                .set("host", "imap.example.com")
                .set("user", "u")
                .set("password", "p")
                .set("basePath", "jkit-mail-disk"), fake);
    }

    private static void assertUnsupported(Runnable action) {
        try {
            action.run();
            fail("应 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
    }

    /**
     * 内存 Fake：name → 条目内容；支持逻辑路径与 mkdir 钩子。
     */
    static final class FakeMailGateway implements MailGateway {
        private final Map<String, Stored> store = new LinkedHashMap<String, Stored>();
        private final AtomicLong uidSeq = new AtomicLong(1L);
        private int deleteCount;
        private int appendCount;
        private int mkdirCount;

        @Override
        public void connect(Map<String, String> cfg) {
            // no-op
        }

        @Override
        public List<MailEntry> list(String path) {
            List<MailEntry> out = new ArrayList<MailEntry>();
            for (Stored s : store.values()) {
                out.add(s.toEntry());
            }
            return out;
        }

        @Override
        public void download(String path, OutputStream out) {
            Stored s = findByLogicalName(path);
            if (s == null) {
                throw StorageException.notFound(path);
            }
            try {
                out.write(s.bytes);
            } catch (IOException e) {
                throw StorageException.io("fake download", path, e);
            }
        }

        @Override
        public void delete(String path) {
            deleteCount++;
            Stored s = findByLogicalName(path);
            if (s == null) {
                throw StorageException.notFound(path);
            }
            store.remove(path);
            store.remove(ImapMailSession.decodeMimeWords(path));
            store.remove(s.name);
            String logical = ImapMailSession.decodeMimeWords(s.name);
            store.remove(logical);
        }

        @Override
        public boolean exists(String path) {
            return findByLogicalName(path) != null;
        }

        @Override
        public MailEntry stat(String path) {
            Stored s = findByLogicalName(path);
            if (s == null) {
                throw StorageException.notFound(path);
            }
            return s.toEntry();
        }

        @Override
        public void mkdir(String dir) {
            mkdirCount++;
            // Plan A：真 CREATE 空实现；.keep 由 MailFileStorage 追加
        }

        @Override
        public void append(String folder, InputStream mimeMessage, long sizeBytes) {
            appendCount++;
            try {
                byte[] mime = readAll(mimeMessage);
                ParsedAttachment parsed = parseAttachment(mime);
                String uid = String.valueOf(uidSeq.getAndIncrement());
                store.put(parsed.name, new Stored(parsed.name, parsed.payload, uid,
                        System.currentTimeMillis()));
            } catch (IOException e) {
                throw StorageException.io("fake append", folder, e);
            }
        }

        @Override
        public void close() {
            // no-op
        }

        String lastUidFor(String name) {
            Stored s = findByLogicalName(name);
            return s == null ? null : s.uid;
        }

        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }

        private static ParsedAttachment parseAttachment(byte[] mime) {
            String text = new String(mime, StandardCharsets.UTF_8);
            String name = "unnamed";
            int subjectAt = text.indexOf("Subject: ");
            if (subjectAt >= 0) {
                int end = text.indexOf("\r\n", subjectAt);
                if (end > subjectAt) {
                    name = decodeMimeWords(
                            text.substring(subjectAt + "Subject: ".length(), end).trim());
                }
            }
            String marker = "Content-Transfer-Encoding: base64\r\n";
            int encAt = text.lastIndexOf(marker);
            if (encAt < 0) {
                return new ParsedAttachment(name, new byte[0]);
            }
            int bodyStart = text.indexOf("\r\n\r\n", encAt);
            if (bodyStart < 0) {
                return new ParsedAttachment(name, new byte[0]);
            }
            bodyStart += 4;
            int bodyEnd = text.indexOf("\r\n--", bodyStart);
            if (bodyEnd < 0) {
                bodyEnd = text.length();
            }
            String b64 = text.substring(bodyStart, bodyEnd).replace("\r\n", "").replace("\n", "");
            byte[] payload = b64.isEmpty() ? new byte[0]
                    : java.util.Base64.getDecoder().decode(b64);
            return new ParsedAttachment(name, payload);
        }

        private static String decodeMimeWords(String raw) {
            return ImapMailSession.decodeMimeWords(raw);
        }

        void putEncodedSubjectEntry(String encodedSubject, byte[] payload) {
            String uid = String.valueOf(uidSeq.getAndIncrement());
            String logical = ImapMailSession.decodeMimeWords(encodedSubject);
            store.put(logical, new Stored(encodedSubject, payload, uid, System.currentTimeMillis()));
        }

        /** 直接塞底层条目（用于残缺分卷等场景，绕过 MailFileStorage.upload）。 */
        void putEntry(String name, byte[] payload) {
            String uid = String.valueOf(uidSeq.getAndIncrement());
            store.put(name, new Stored(name, payload, uid, System.currentTimeMillis()));
        }

        private Stored findByLogicalName(String path) {
            Stored direct = store.get(path);
            if (direct != null) {
                return direct;
            }
            for (Stored s : store.values()) {
                if (ImapMailSession.namesMatch(s.name, path)) {
                    return s;
                }
            }
            return null;
        }
    }

    private static final class Stored {
        final String name;
        final byte[] bytes;
        final String uid;
        final long mtime;

        Stored(String name, byte[] bytes, String uid, long mtime) {
            this.name = name;
            this.bytes = bytes;
            this.uid = uid;
            this.mtime = mtime;
        }

        MailEntry toEntry() {
            String logical = ImapMailSession.decodeMimeWords(name);
            return new MailEntry(logical, false, bytes.length, mtime, uid);
        }
    }

    private static final class ParsedAttachment {
        final String name;
        final byte[] payload;

        ParsedAttachment(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }
}
