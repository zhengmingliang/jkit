package com.alianga.jkit.storage.conn;

import com.alianga.jkit.storage.StoragePlatform;

import com.alianga.jkit.storage.FileStorages;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 复制连接：新 id、名称加「副本」不重名、长期凭证照抄、会话令牌清空、配置不共享。
 *
 * @author 郑明亮
 */
public class ConnectionCopiesTest {

    @Test
    public void copyGetsNewIdAndSuffixedName() {
        Connection source = new Connection("生产 SFTP", "sftp",
                ConnMaps.map("host", "10.0.0.1", "user", "root", "password", "pw-placeholder", "basePath", "/data"));
        Connection copy = ConnectionCopies.copyOf(source, ConnMaps.list("生产 SFTP"), ConnMaps.set());
        assertNotEquals(source.id(), copy.id());
        assertEquals("生产 SFTP 副本", copy.name());
        assertEquals("sftp", copy.platform());
        // 长期凭证照抄：复制出来改个路径就能直接用
        assertEquals(source.config(), copy.config());
    }

    @Test
    public void copyNameAvoidsCollisions() {
        assertEquals("a 副本 3", ConnectionCopies.copyName("a", ConnMaps.list("a", "a 副本", "a 副本 2")));
    }

    @Test
    public void copyConfigIsIndependent() {
        Map<String, String> config = new HashMap<>(ConnMaps.map("host", "h", "user", "u"));
        Connection source = new Connection("x", "ftp", config);
        Connection copy = ConnectionCopies.copyOf(source, ConnMaps.list(), ConnMaps.set());
        copy.config().put("host", "changed");
        assertEquals("h", source.config().get("host"));
    }

    @Test
    public void brokenSecretsAreNotCopied() {
        Connection source = new Connection("x", "sftp", ConnMaps.map("host", "h", "user", "u", "password", "cipher"));
        Connection copy = ConnectionCopies.copyOf(source, ConnMaps.list(), ConnMaps.set("password"));
        assertFalse(copy.config().containsKey("password"));
        assertEquals("h", copy.config().get("host"));
    }

    @Test
    public void qqMailAttachSessionTokensAreCleared() {
        StoragePlatform attach = FileStorages.platforms().stream()
                .filter(p -> "qqmail-attach".equals(p.engine()))
                .findFirst().orElse(null);
        assertNotNull("应存在 QQ 邮箱附件平台", attach);
        Connection source = new Connection("QQ 附件", attach.id(),
                ConnMaps.map("sid", "sid-placeholder", "cookie", "cookie-placeholder", "group", "邮箱"));
        Connection copy = ConnectionCopies.copyOf(source, ConnMaps.list(), ConnMaps.set());
        assertFalse(copy.config().containsKey("sid"));
        assertFalse(copy.config().containsKey("cookie"));
        assertEquals("邮箱", copy.config().get("group"));
        assertTrue(ConnectionCopies.clearedFields(attach.id()).containsAll(ConnMaps.set("sid", "cookie")));
        assertTrue(ConnectionCopies.clearedFields("sftp").isEmpty());
    }

    @Test
    public void everySessionLikeFieldIsDeclared() {
        java.util.Set<String> suspicious = ConnMaps.set("sid", "cookie", "session", "sessionid",
                "sessiontoken", "securitytoken");
        for (com.alianga.jkit.storage.StoragePlatform platform : FileStorages.platforms()) {
            for (com.alianga.jkit.storage.StorageField field : platform.fields()) {
                if (suspicious.contains(field.key().toLowerCase(java.util.Locale.ROOT))) {
                    org.junit.Assert.assertTrue("平台 " + platform.id() + " 的字段 " + field.key()
                                    + " 疑似会话令牌，请在 ConnectionCopies.SESSION_FIELD_TABLE 声明",
                            ConnectionCopies.clearedFields(platform.id()).contains(field.key()));
                }
            }
        }
    }

    @Test
    public void tableIsDeclarativeAndKeepsQqMailBehavior() {
        org.junit.Assert.assertTrue(ConnectionCopies.SESSION_FIELD_TABLE.stream().anyMatch(entry ->
                entry.engine().equals("qqmail-attach")
                        && entry.fields().equals(ConnMaps.set("sid", "cookie"))));
    }
}
