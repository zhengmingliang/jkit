package com.alianga.jkit.storage.conn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * QQ 邮箱附件（qqmail-attach）接入：密钥字段、Cookie 回推加密写回、只读引擎注册。
 *
 * @author 郑明亮
 */
public class QqMailAttachIntegrationTest {

    private static final String SID = "SID-PLAIN-123";
    private static final String COOKIE = "qm_sid=COOKIE-PLAIN; skey=@abc; xm_sid=t; xm_skey=t; xm_data_ticket=t; xm_uin=1";
    private static final String NEW_COOKIE = "qm_sid=COOKIE-ROTATED; skey=@def; xm_sid=t; xm_skey=t; xm_data_ticket=t; xm_uin=1";

    @Test
    public void platformProvidesSecretSidAndCookie() {
        StoragePlatform platform = null;
        for (StoragePlatform p : FileStorages.platforms()) {
            if ("qqmail-attach".equals(p.engine())) {
                platform = p;
            }
        }
        assertNotNull("新建连接应能选到 QQ 邮箱附件", platform);
        Map<String, StorageField> fields = new LinkedHashMap<String, StorageField>();
        for (StorageField field : platform.fields()) {
            fields.put(field.key(), field);
        }
        assertTrue("sid 按密钥处理（jkit 预设提供）", fields.get("sid").isSecret());
        assertTrue("sid 仍必填", fields.get("sid").isRequired());
        assertTrue("cookie 按密钥处理", fields.get("cookie").isSecret());
        assertEquals("只读引擎：平台挂在其专属引擎上",
                "qqmail-attach", FileStorages.platform(platform.id()).engine());
    }

    @Test
    public void sidAndCookieEncryptedOnDiskAndCookieUpdatePersists() throws Exception {
        Path dir = Files.createTempDirectory("jkit-qqattach-test");
        String platformId = attachPlatformId();
        ConnectionStore store = ConnectionStore.load(dir);
        Map<String, String> config = new LinkedHashMap<String, String>();
        config.put("sid", SID);
        config.put("cookie", COOKIE);
        Connection connection = new Connection("QQ附件", platformId, config);
        store.upsert(connection);
        String raw = new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8);
        assertFalse("sid 明文不应落盘", raw.contains(SID));
        assertFalse("cookie 明文不应落盘", raw.contains("COOKIE-PLAIN"));

        List<String> sunk = new ArrayList<String>();
        Sessions.setCookieSink(new java.util.function.BiConsumer<Connection, String>() {
            @Override
            public void accept(Connection c, String cookie) {
                sunk.add(cookie);
                store.updateConfigValue(c.id(), "cookie", cookie);
            }
        });
        try {
            Sessions.onCookieUpdated(connection, COOKIE);
            assertTrue("值未变不落盘", sunk.isEmpty());
            Sessions.onCookieUpdated(connection, NEW_COOKIE);
            assertEquals(Collections.singletonList(NEW_COOKIE), sunk);
            Sessions.onCookieUpdated(connection, NEW_COOKIE);
            assertEquals("重复回推不重复落盘", 1, sunk.size());

            raw = new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8);
            assertFalse("新 cookie 明文不应落盘", raw.contains("COOKIE-ROTATED"));
            Connection reloaded = ConnectionStore.load(dir).findById(connection.id());
            assertEquals(NEW_COOKIE, reloaded.config().get("cookie"));
            assertEquals(SID, reloaded.config().get("sid"));
            // 界面持有的旧连接对象再次打开时叠加最新 cookie
            assertEquals(NEW_COOKIE, Sessions.withLatestCookie(connection).config().get("cookie"));
        } finally {
            Sessions.setCookieSink(null);
        }
    }

    @Test
    public void openRegistersCookieListenerWithoutNetwork() throws Exception {
        Map<String, String> config = new LinkedHashMap<String, String>();
        config.put("sid", SID);
        config.put("cookie", COOKIE);
        Connection connection = new Connection("QQ附件2", attachPlatformId(), config);
        FileStorage storage = Sessions.open(connection);
        try {
            assertTrue(storage instanceof com.alianga.jkit.storage.engine.QqMailAttachFileStorage);
            assertFalse("toString 不应带凭据", storage.toString().contains("COOKIE-PLAIN")
                    || storage.toString().contains(SID));
        } finally {
            storage.close();
        }
    }

    private static String attachPlatformId() {
        for (StoragePlatform p : FileStorages.platforms()) {
            if ("qqmail-attach".equals(p.engine())) {
                return p.id();
            }
        }
        throw new AssertionError("no qqmail-attach");
    }
}
