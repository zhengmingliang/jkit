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
 * 邮件存储一期单测：内存 FakeMailGateway，不连真实 QQ / IMAP。
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
    public void oversizeConflict() {
        StorageConfig cfg = StorageConfig.empty()
                .set("host", "imap.example.com")
                .set("user", "u")
                .set("password", "p")
                .set("maxAttachmentBytes", "10");
        FileStorage fs = new MailFileStorage(cfg, new FakeMailGateway());
        try {
            fs.upload("big.bin", new byte[11]);
            fail("超限应 CONFLICT");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
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
        StoragePlatform generic = FileStorages.platform("mail");
        assertNotNull(generic);
        assertEquals("mail", generic.engine());
    }

    @Test
    public void mkdirCopyRenameUnsupported() {
        FileStorage fs = open(new FakeMailGateway());
        assertUnsupported(() -> fs.mkdir("d"));
        assertUnsupported(() -> fs.copy("a", "b"));
        assertUnsupported(() -> fs.rename("a", "b"));
    }

    @Test
    public void nestedPathRejected() {
        FileStorage fs = open(new FakeMailGateway());
        try {
            fs.upload("dir/a.txt", "x".getBytes(StandardCharsets.UTF_8));
            fail("嵌套路径应 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
        assertFalse(fs.exists("dir/a.txt"));
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
        // Q-encoding: =E4=BD=A0 is 你 in UTF-8
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
        // 先走真实 MailMime 渲染，再模拟 QQ 把编码 Subject 原样塞进网关
        byte[] mime = MailFileStorage.buildStoredMime("report-中文.txt",
                "payload".getBytes(StandardCharsets.UTF_8));
        String mimeText = new String(mime, StandardCharsets.UTF_8);
        int at = mimeText.indexOf("Subject: ");
        assertTrue("MIME 应含 Subject", at >= 0);
        int end = mimeText.indexOf("\r\n", at);
        String subject = mimeText.substring(at + "Subject: ".length(), end).trim();
        // 中文文件名 Subject 必为 RFC2047；即便未来 ASCII 不编码，namesMatch 仍应成立
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
     * 内存 Fake：name → 条目内容。
     */
    static final class FakeMailGateway implements MailGateway {
        private final Map<String, Stored> store = new LinkedHashMap<String, Stored>();
        private final AtomicLong uidSeq = new AtomicLong(1L);
        private int deleteCount;
        private int appendCount;

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
                // 后端找不到时抛 NOT_FOUND；MailFileStorage.delete 负责吞掉以保持幂等
                throw StorageException.notFound(path);
            }
            // 可能按逻辑名或编码名存着，两边都尝试移除
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
            // SmtpChannel 正文与附件均为 base64；取最后一个 base64 part 作为附件载荷
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

        /** 与活连 ImapMailSession 共用 RFC2047 解码。 */
        private static String decodeMimeWords(String raw) {
            return ImapMailSession.decodeMimeWords(raw);
        }

        /**
         * 模拟 QQ：list/stat 仍暴露编码 Subject，查找时按解码后明文匹配。
         */
        void putEncodedSubjectEntry(String encodedSubject, byte[] payload) {
            String uid = String.valueOf(uidSeq.getAndIncrement());
            String logical = ImapMailSession.decodeMimeWords(encodedSubject);
            store.put(logical, new Stored(encodedSubject, payload, uid, System.currentTimeMillis()));
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
            // 与 ImapMailSession.fetchMeta 一致：对外逻辑名为解码后的明文
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
