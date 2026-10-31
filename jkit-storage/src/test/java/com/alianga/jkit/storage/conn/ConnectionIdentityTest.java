package com.alianga.jkit.storage.conn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 连接的稳定身份：改名不换 id。
 *
 * <p>同步档案此前只记连接名，用户把「公司 OSS」改成「生产 OSS」，档案就指向一个
 * 不存在的名字，定时同步静默失效——界面只在调度那一刻弹一句告警，平时看不出来。
 * 现在档案记 id，改名不影响。</p>
 *
 * @author 郑明亮
 */
public class ConnectionIdentityTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private ConnectionStore store(String name) throws Exception {
        return ConnectionStore.load(tmp.newFolder(name).toPath());
    }

    @Test
    public void newConnectionGetsStableId() {
        Connection a = new Connection("oss", "aliyun-oss", ConnMaps.map("bucket", "b"));
        Connection b = new Connection("oss", "aliyun-oss", ConnMaps.map("bucket", "b"));
        assertFalse("每条连接都要有自己的 id", a.id().trim().isEmpty());
        assertFalse("两条不同连接的 id 不能相同", a.id().equals(b.id()));
    }

    @Test
    public void renamingKeepsId() {
        Connection original = new Connection("公司 OSS", "aliyun-oss", ConnMaps.map("bucket", "b"));
        Connection renamed = original.with("生产 OSS", "aliyun-oss", original.config());
        assertEquals("改名不能换身份", original.id(), renamed.id());
        assertEquals("生产 OSS", renamed.name());
    }

    @Test
    public void renameSurvivesReload() throws Exception {
        ConnectionStore store = store("rename");
        Connection added = new Connection("公司 OSS", "aliyun-oss", ConnMaps.map("bucket", "b"));
        store.upsert(added);
        String id = added.id();

        store.replace("公司 OSS", store.find("公司 OSS").with("生产 OSS", "aliyun-oss",
                store.find("公司 OSS").config()));

        ConnectionStore reloaded = ConnectionStore.load(
                tmp.getRoot().toPath().resolve("rename"));
        Connection byId = reloaded.findById(id);
        assertNotNull("改名后按 id 必须还能找到这条连接", byId);
        assertEquals("生产 OSS", byId.name());
        assertNull("旧名字不该还指向它", reloaded.find("公司 OSS"));
    }

    @Test
    public void idIsPersisted() throws Exception {
        ConnectionStore store = store("persist");
        Connection added = new Connection("oss", "aliyun-oss", ConnMaps.map("bucket", "b"));
        store.upsert(added);
        ConnectionStore reloaded = ConnectionStore.load(
                tmp.getRoot().toPath().resolve("persist"));
        assertEquals("重新加载后 id 必须一致，否则每次启动都换身份",
                added.id(), reloaded.find("oss").id());
    }

    @Test
    public void overwritingSameNameKeepsId() throws Exception {
        ConnectionStore store = store("overwrite");
        Connection first = new Connection("oss", "aliyun-oss", ConnMaps.map("bucket", "b1"));
        store.upsert(first);
        // 导入同名连接：对象不同、id 也不同，但名字相同就该视为同一条
        store.upsert(new Connection("oss", "aliyun-oss", ConnMaps.map("bucket", "b2")));
        assertEquals("同名覆盖不能换 id，否则指向它的档案全部断链",
                first.id(), store.find("oss").id());
        assertEquals("配置要更新", "b2", store.find("oss").config().get("bucket"));
    }

    @Test
    public void legacyConnectionWithoutIdGetsOne() throws Exception {
        Path dir = tmp.newFolder("legacy").toPath();
        java.nio.file.Files.write(dir.resolve("connections.json"),
                ("{\"connections\":[{\"name\":\"old\",\"platform\":\"local\","
                        + "\"config\":{\"basePath\":\"/tmp\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
        ConnectionStore store = ConnectionStore.load(dir);
        Connection old = store.find("old");
        assertNotNull(old);
        assertFalse("旧配置没有 id，加载时要补上", old.id().trim().isEmpty());
    }
}
