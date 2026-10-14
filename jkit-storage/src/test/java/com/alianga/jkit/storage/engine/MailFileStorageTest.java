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
            Stored s = store.get(path);
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
            if (!store.containsKey(path)) {
                // 后端找不到时抛 NOT_FOUND；MailFileStorage.delete 负责吞掉以保持幂等
                throw StorageException.notFound(path);
            }
            store.remove(path);
        }

        @Override
        public boolean exists(String path) {
            return store.containsKey(path);
        }

        @Override
        public MailEntry stat(String path) {
            Stored s = store.get(path);
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
            Stored s = store.get(name);
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

        /** 解 SmtpChannel Subject 的 RFC2047 encoded-word，与 ImapMailSession 对齐。 */
        private static String decodeMimeWords(String raw) {
            if (raw == null || !raw.contains("=?")) {
                return raw;
            }
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");
                java.util.regex.Matcher m = p.matcher(raw);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String charset = m.group(1);
                    char enc = Character.toUpperCase(m.group(2).charAt(0));
                    String data = m.group(3);
                    String decoded;
                    if (enc == 'B') {
                        byte[] bytes = java.util.Base64.getDecoder().decode(data);
                        decoded = new String(bytes, charset);
                    } else {
                        decoded = data.replace("_", " ");
                    }
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(decoded));
                }
                m.appendTail(sb);
                return sb.toString();
            } catch (Exception e) {
                return raw;
            }
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
            return new MailEntry(name, false, bytes.length, mtime, uid);
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
