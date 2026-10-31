package com.alianga.jkit.storage.conn;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 连接持久化测试：保存 / 加载 / 密钥加密 / 旧版混淆兼容 / 损坏文件兜底。
 *
 * @author 郑明亮
 */
public class ConnectionStoreTest {

    @Test
    public void saveAndReloadEncryptedSecrets() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            Map<String, String> config = new LinkedHashMap<>();
            config.put("endpoint", "https://oss-cn-hangzhou.aliyuncs.com");
            config.put("bucket", "demo");
            config.put("accessKey", "AKID");
            config.put("secretKey", "SECRET-VALUE");
            config.put("basePath", "/");
            store.upsert(new Connection("我的 OSS", "aliyun-oss", config));
            store.touchLast("我的 OSS");

            assertTrue(store.file().toString(), Files.exists(dir.resolve("connections.json")));
            String raw = new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8);
            assertTrue("secretKey 明文不应落盘", !raw.contains("SECRET-VALUE"));
            assertTrue("密钥字段应加密为 enc:v1 密文", raw.contains("enc:v1:"));
            assertTrue("Base64 混淆不应再出现", !raw.contains("b64:"));
            assertTrue("安装级密钥文件应生成", Files.exists(dir.resolve(".secret-key")));
            Set<PosixFilePermission> keyPermissions =
                    Files.getPosixFilePermissions(dir.resolve(".secret-key"));
            assertFalse("密钥文件不应放开他人读",
                    keyPermissions.contains(PosixFilePermission.OTHERS_READ));

            ConnectionStore reloaded = ConnectionStore.load(dir);
            assertEquals(1, reloaded.connections().size());
            Connection restored = reloaded.connections().get(0);
            assertEquals("我的 OSS", restored.name());
            assertEquals("aliyun-oss", restored.platform());
            assertEquals("SECRET-VALUE", restored.config().get("secretKey"));
            assertEquals("AKID", restored.config().get("accessKey"));
            assertEquals("我的 OSS", reloaded.lastConnection());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void legacyBase64SecretStillLoads() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            String encoded = Base64.getEncoder()
                    .encodeToString("LEGACY-SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String json = "{\"connections\":[{\"name\":\"旧版\",\"platform\":\"aliyun-oss\","
                    + "\"config\":{\"accessKey\":\"AK\",\"secretKey\":\"b64:" + encoded + "\"}}],"
                    + "\"lastConnection\":\"\"}";
            Files.write(dir.resolve("connections.json"), json.getBytes(StandardCharsets.UTF_8));
            ConnectionStore store = ConnectionStore.load(dir);
            assertEquals("LEGACY-SECRET", store.find("旧版").config().get("secretKey"));
            // 重新保存后应升级为加密
            store.upsert(store.find("旧版"));
            String raw = new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8);
            assertTrue(raw.contains("enc:v1:"));
            assertFalse(raw.contains("b64:"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void missingKeyFileYieldsEmptySecret() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp",
                    ConnMaps.map("host", "h", "user", "u", "password", "PLAIN")));
            Files.deleteIfExists(dir.resolve(".secret-key"));
            ConnectionStore reloaded = ConnectionStore.load(dir);
            assertEquals("密钥丢失时解不开应得空串而非乱码",
                    "", reloaded.find("sftp").config().get("password"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void undecryptableSecretSurvivesUnrelatedSave() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp",
                    ConnMaps.map("host", "h", "user", "u", "password", "PLAIN")));
            String keyBackup = new String(Files.readAllBytes(dir.resolve(".secret-key")), StandardCharsets.UTF_8);
            Files.deleteIfExists(dir.resolve(".secret-key"));

            // 密钥丢失：解不开，界面上显示为空，但要能被识别出来
            ConnectionStore broken = ConnectionStore.load(dir);
            assertEquals("", broken.find("sftp").config().get("password"));
            assertTrue(broken.undecryptableConnections().contains("sftp"));

            // 无关保存（切换连接就会触发）不得把密文覆盖成空串
            broken.touchLast("sftp");
            String raw = new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8);
            assertTrue("解不开的密文必须原样保留", raw.contains("enc:v1:"));

            // 找回旧密钥后原值仍在——这是本次修复的核心断言
            Files.write(dir.resolve(".secret-key"), keyBackup.getBytes(StandardCharsets.UTF_8));
            ConnectionStore restored = ConnectionStore.load(dir);
            assertEquals("PLAIN", restored.find("sftp").config().get("password"));
            assertTrue(restored.undecryptableConnections().isEmpty());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void undecryptableFieldsNamesTheBrokenFieldOnly() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp",
                    ConnMaps.map("host", "h", "user", "u", "password", "PLAIN")));
            Files.deleteIfExists(dir.resolve(".secret-key"));

            // 界面要按字段标黄，所以必须精确到字段名：只列解不开的那个
            ConnectionStore broken = ConnectionStore.load(dir);
            assertEquals(ConnMaps.set("password"), broken.undecryptableFields("sftp"));
            assertTrue("不存在的连接没有失效字段", broken.undecryptableFields("nope").isEmpty());

            // 重新录入后标记要清掉，否则界面会一直提示「已失效」
            broken.upsert(new Connection("sftp", "sftp", ConnMaps.map("password", "NEW")));
            assertTrue(broken.undecryptableFields("sftp").isEmpty());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void savingWithoutRetypingSecretKeepsCiphertext() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp", ConnMaps.map("host", "h", "password", "PLAIN")));
            String keyBackup = new String(Files.readAllBytes(dir.resolve(".secret-key")), StandardCharsets.UTF_8);
            Files.deleteIfExists(dir.resolve(".secret-key"));

            // 用户为了改 host 打开编辑、密码留空直接保存：密文必须还在，
            // 而且之后的无关保存（切连接）也不能把它清掉
            ConnectionStore broken = ConnectionStore.load(dir);
            broken.upsert(new Connection("sftp", "sftp", ConnMaps.map("host", "h2", "password", "")));
            assertTrue("改别的字段、密钥留空，密文不能被清空",
                    new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8).contains("enc:v1:"));
            broken.touchLast("sftp");
            assertTrue("保存过一次再保存同样不能清空",
                    new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8).contains("enc:v1:"));

            Files.write(dir.resolve(".secret-key"), keyBackup.getBytes(StandardCharsets.UTF_8));
            assertEquals("PLAIN", ConnectionStore.load(dir).find("sftp").config().get("password"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void renamingConnectionKeepsPreservedCiphertext() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp", ConnMaps.map("password", "PLAIN")));
            String keyBackup = new String(Files.readAllBytes(dir.resolve(".secret-key")), StandardCharsets.UTF_8);
            Files.deleteIfExists(dir.resolve(".secret-key"));

            // 改名保存：暂存密文要跟着挂到新名下
            ConnectionStore broken = ConnectionStore.load(dir);
            broken.replace("sftp", new Connection("sftp-2", "sftp", ConnMaps.map("password", "")));
            assertTrue("改名后密文必须还在",
                    new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8).contains("enc:v1:"));

            Files.write(dir.resolve(".secret-key"), keyBackup.getBytes(StandardCharsets.UTF_8));
            assertEquals("PLAIN", ConnectionStore.load(dir).find("sftp-2").config().get("password"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void adoptingReloadedStoreKeepsPreservedCiphertext() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp", ConnMaps.map("password", "PLAIN")));
            String keyBackup = new String(Files.readAllBytes(dir.resolve(".secret-key")), StandardCharsets.UTF_8);
            Files.deleteIfExists(dir.resolve(".secret-key"));

            // 界面「刷新连接列表」：接管磁盘内容时暂存密文也要一起接管
            store.adoptFrom(ConnectionStore.load(dir));
            assertTrue(store.undecryptableConnections().contains("sftp"));
            store.touchLast("sftp");
            assertTrue("刷新后保存同样不能清空密文",
                    new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8).contains("enc:v1:"));

            Files.write(dir.resolve(".secret-key"), keyBackup.getBytes(StandardCharsets.UTF_8));
            assertEquals("PLAIN", ConnectionStore.load(dir).find("sftp").config().get("password"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void editedConnectionTakesUserValueOverPreservedCiphertext() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("sftp", "sftp", ConnMaps.map("password", "OLD")));
            Files.deleteIfExists(dir.resolve(".secret-key"));
            ConnectionStore broken = ConnectionStore.load(dir);
            // 用户重新录入后保存：以录入值为准，不再写回解不开的旧密文
            broken.upsert(new Connection("sftp", "sftp", ConnMaps.map("password", "NEW")));
            assertEquals("NEW", broken.find("sftp").config().get("password"));
            assertEquals("NEW", ConnectionStore.load(dir).find("sftp").config().get("password"));
            assertTrue(broken.undecryptableConnections().isEmpty());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void unknownPlatformPasswordStillEncrypted() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            assertNull("测试前提：该平台确实未注册",
                    FileStorages.platform("no-such-platform-xyz"));
            ConnectionStore store = ConnectionStore.load(dir);
            Map<String, String> config = new LinkedHashMap<>();
            config.put("host", "h");
            config.put("password", "PLAIN-PASS");
            config.put("accessKey", "AKID-PUBLIC");
            store.upsert(new Connection("自制", "no-such-platform-xyz", config));

            String raw = new String(Files.readAllBytes(dir.resolve("connections.json")), StandardCharsets.UTF_8);
            assertFalse("未注册平台的 password 也不能明文落盘", raw.contains("PLAIN-PASS"));
            assertTrue(raw.contains("enc:v1:"));
            assertTrue("accessKey 是公开标识，不该被当成密钥", raw.contains("AKID-PUBLIC"));

            ConnectionStore reloaded = ConnectionStore.load(dir);
            assertEquals("PLAIN-PASS", reloaded.find("自制").config().get("password"));
            assertEquals("AKID-PUBLIC", reloaded.find("自制").config().get("accessKey"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void connectionsSnapshotIsImmutable() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("本地", "local", ConnMaps.map("basePath", "/tmp")));
            List<Connection> snapshot = store.connections();
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.add(new Connection("x", "local", ConnMaps.map())));
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
            assertEquals("外部改不到内部清单", 1, store.connections().size());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void upsertAllReplacesByNameInOnePass() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            List<Connection> batch = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                batch.add(new Connection("c" + i, "local", ConnMaps.map("basePath", "/d" + i)));
            }
            store.upsertAll(batch);
            assertEquals(50, store.connections().size());
            assertEquals("/d49", store.find("c49").config().get("basePath"));

            // 再导入同名项：覆盖而不新增
            store.upsertAll(ConnMaps.list(new Connection("c1", "local", ConnMaps.map("basePath", "/changed"))));
            assertEquals(50, store.connections().size());
            assertEquals("/changed", store.find("c1").config().get("basePath"));

            ConnectionStore reloaded = ConnectionStore.load(dir);
            assertEquals(50, reloaded.connections().size());
            assertEquals("/changed", reloaded.find("c1").config().get("basePath"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void upsertUpdatesAndRemoveDeletes() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("本地", "local",
                    ConnMaps.map("basePath", System.getProperty("java.io.tmpdir"))));
            store.upsert(new Connection("本地", "local",
                    ConnMaps.map("basePath", System.getProperty("user.home"))));
            assertEquals(1, store.connections().size());
            assertEquals(System.getProperty("user.home"), store.connections().get(0).config().get("basePath"));
            assertTrue(store.remove("本地"));
            assertEquals(0, store.connections().size());
            assertNull(store.find("本地"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void renameReplacesOldName() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("旧名", "local", ConnMaps.map("basePath", "/tmp/old")));
            store.touchLast("旧名");
            store.replace("旧名", new Connection("新名", "local", ConnMaps.map("basePath", "/tmp/new")));
            assertEquals(1, store.connections().size());
            assertNull(store.find("旧名"));
            assertEquals("/tmp/new", store.find("新名").config().get("basePath"));
            ConnectionStore reloaded = ConnectionStore.load(dir);
            assertEquals("新名", reloaded.lastConnection());
            assertNull(reloaded.find("旧名"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void connectionsFileIsOwnerReadWriteOnly() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            ConnectionStore store = ConnectionStore.load(dir);
            store.upsert(new Connection("本地", "local", ConnMaps.map("basePath", "/tmp")));
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(store.file());
            assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
            assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void corruptedFileIsPreservedBeforeEmptyRecovery() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            Path original = dir.resolve("connections.json");
            String malformed = "{broken json!!!";
            Files.write(original, malformed.getBytes(StandardCharsets.UTF_8));
            ConnectionStore store = ConnectionStore.load(dir);
            assertEquals(0, store.connections().size());

            Path backup;
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                backup = files.filter(path -> path.getFileName().toString()
                                .startsWith("connections.json.corrupt-"))
                        .findFirst().orElseThrow();
            }
            assertEquals("损坏内容必须完整保留", malformed, new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
            assertFalse("隔离后原路径应可用于新库", Files.exists(original));

            store.upsert(new Connection("新连接", "local", ConnMaps.map("basePath", "/tmp")));
            assertEquals(1, ConnectionStore.load(dir).connections().size());
            assertEquals("保存新库不能改写备份", malformed, new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void corruptedFileBackupDoesNotOverwriteExistingBackup() throws Exception {
        Path dir = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            Path original = dir.resolve("connections.json");
            String malformed = "{broken json!!!";
            Path priorBackup = dir.resolve("connections.json.corrupt-existing");
            Files.write(original, malformed.getBytes(StandardCharsets.UTF_8));
            Files.write(priorBackup, "prior backup".getBytes(StandardCharsets.UTF_8));

            ConnectionStore.load(dir);

            assertEquals("既有备份不得被覆盖", "prior backup", new String(Files.readAllBytes(priorBackup), StandardCharsets.UTF_8));
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                List<Path> backups = files.filter(path -> path.getFileName().toString()
                        .startsWith("connections.json.corrupt-")).collect(java.util.stream.Collectors.toList());
                assertEquals("应同时保留既有备份和本次备份", 2, backups.size());
                assertTrue("本次损坏内容必须完整保留", backups.stream()
                        .anyMatch(path -> readString(path).equals(malformed)));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private static String readString(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void sessionOpensLocalConnection() throws Exception {
        Path base = Files.createTempDirectory("jkit-storage-ui-test");
        try {
            Connection connection = new Connection("本地测试", "local",
                    ConnMaps.map("basePath", base.toString()));
            try (FileStorage storage = Sessions.open(connection)) {
                assertEquals("local", storage.platform());
                storage.upload("a.txt", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals("hello", new String(
                    Files.readAllBytes(base.resolve("a.txt")), java.nio.charset.StandardCharsets.UTF_8));
            // 平台清单经引擎注册表可用（界面动态表单的依据）
            List<com.alianga.jkit.storage.StoragePlatform> platforms = FileStorages.platforms();
            assertTrue("内置平台应不少于 30 个", platforms.size() >= 30);
        } finally {
            deleteRecursively(base);
        }
    }

    @Test
    public void jsonSmoke() {
        Map<String, Object> root = JSON.parseObject("{\"connections\":[]}");
        assertTrue(root.containsKey("connections"));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
