package com.alianga.jkit.storage.conn;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 连接配置持久化：所有存储连接（平台 + 键值配置）保存为 {@code connections.json}。
 *
 * <p>密钥类字段（密码 / 私钥口令等）用 {@link SecretCipher}（AES-256-GCM）
 * 加密后落盘，密文前缀 {@code enc:v1:}；加密密钥是安装级随机密钥
 * （数据目录 {@code .secret-key}，仅所有者可读写），丢失后需在连接编辑里重新录入密码。
 * 旧版本的 {@code b64:} Base64 混淆值加载时仍然兼容，下一次保存自动升级为加密。</p>
 *
 * <p><b>解不开的密文必须原样保留</b>：密钥文件丢失或损坏时 {@link SecretCipher#decrypt}
 * 返回空串，此时原始密文暂存在 {@link #preservedSecrets} 里，落盘时写回原值——
 * 否则任何一次无关保存（哪怕是切换连接）都会把密文覆盖成空串，造成永久丢失。
 * 用户显式编辑过某条连接后，该连接的暂存即失效，以用户录入的值为准。</p>
 *
 * <p>本类线程安全：后台任务与界面线程可能同时读写，所有公开方法均同步，
 * {@link #connections()} 返回不可变副本。</p>
 *
 * @author 郑明亮
 */
public class ConnectionStore {
    /** 旧版混淆前缀：仅兼容读取，保存时一律升级为加密。 */
    private static final String LEGACY_PREFIX = "b64:";

    /** 平台未注册时，按字段名兜底判定为密钥的关键词（小写包含匹配）。 */
    private static final String[] SECRET_NAME_HINTS = {
            "secret", "password", "passwd", "passphrase", "token", "privatekey", "credential"
    };

    private final Path file;
    private final List<Connection> connections = new ArrayList<Connection>();
    private final SecretCipher cipher;
    /** 连接名 →（字段 → 解不开的原始密文）；用户编辑该连接后清除。 */
    private final Map<String, Map<String, String>> preservedSecrets = new LinkedHashMap<String, Map<String, String>>();
    private String lastConnection = "";

    private ConnectionStore(Path file, SecretCipher cipher) {
        this.file = file;
        this.cipher = cipher;
    }

    /**
     * 从数据目录加载（不存在 / 损坏返回空库，不阻断启动）。
     *
     * @param dir 数据目录
     * @return 连接库
     */
    public static ConnectionStore load(Path dir) {
        ConnectionStore store = new ConnectionStore(dir.resolve("connections.json"),
                SecretCipher.load(dir));
        String text = JsonStore.readStringOrEmpty(store.file);
        if (!text.trim().isEmpty()) {
            try {
                Map<String, Object> root = JSON.parseObject(text);
                Object last = root.get("lastConnection");
                if (last != null) {
                    store.lastConnection = String.valueOf(last);
                }
                Object array = root.get("connections");
                if (array instanceof List<?>) {
                    for (Object item : (List<?>) array) {
                        if (item instanceof Map<?, ?>) {
                            store.connections.add(store.fromMap(asStringMap((Map<?, ?>) item)));
                        }
                    }
                }
            } catch (RuntimeException malformed) {
                CorruptJsonBackup.preserve(store.file);
            }
        }
        return store;
    }

    /**
     * 全部连接（按保存顺序）。
     *
     * <p>返回不可变副本：调用方拿不到内部列表，避免误改与并发修改。</p>
     *
     * @return 连接列表
     */
    public synchronized List<Connection> connections() {
        return Collections.unmodifiableList(new ArrayList<Connection>(connections));
    }

    /**
     * 上次使用的连接名（界面恢复用）。
     *
     * @return 连接名，可能为空串
     */
    public synchronized String lastConnection() {
        return lastConnection;
    }

    /**
     * 有字段解不开的连接名（密钥文件丢失或损坏）。
     *
     * <p>界面据此提示用户重新录入密码；密文本身已被
     * {@link #preservedSecrets} 保留，不会因保存而丢失。</p>
     *
     * @return 连接名清单，正常情况为空
     */
    public synchronized List<String> undecryptableConnections() {
        return new ArrayList<String>(preservedSecrets.keySet());
    }

    /**
     * 某条连接里具体哪些字段解不开（密钥文件丢失或更换）。
     *
     * <p>界面据此把该字段标成「密钥已失效，请重新录入」而不是一片空白，
     * 让用户知道这里原本有值、需要重录，而不是被清空了。</p>
     *
     * @param connectionName 连接名
     * @return 字段名集合，正常情况为空
     */
    public synchronized Set<String> undecryptableFields(String connectionName) {
        Map<String, String> preserved = preservedSecrets.get(connectionName);
        if (preserved == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<String>(preserved.keySet()));
    }

    /** 配置文件路径（测试断言用）。 */
    Path file() {
        return file;
    }

    /**
     * 记录上次使用的连接并落盘。
     *
     * @param name 连接名
     */
    public synchronized void touchLast(String name) {
        this.lastConnection = name == null ? "" : name;
        save();
    }

    /**
     * 新增或更新连接（按名称覆盖）并落盘。
     *
     * @param connection 连接
     */
    public synchronized void upsert(Connection connection) {
        commit(connection);
    }

    /**
     * 写入内存并落盘。
     *
     * <p>暂存密文<b>不在这里整体失效</b>，而是由 {@link #encodeIfSecret} 逐字段判定：
     * 只有用户真的录入了新值，该字段的暂存才作废。若改成"保存过一次就整条失效"，
     * 那么「打开编辑 → 只改 host、密码留空 → 保存」之后内存里的密码已经是空串，
     * 再有一次无关保存（切连接）就会把密文覆盖成空串。</p>
     */
    private void commit(Connection connection) {
        upsertInMemory(connection);
        save();
    }

    private void upsertInMemory(Connection connection) {
        for (int i = 0; i < connections.size(); i++) {
            Connection existing = connections.get(i);
            if (existing.name().equals(connection.name())) {
                // 同名即同一条连接：沿用旧 id，否则导入覆盖一次就换一个身份，
                // 指向它的同步档案全部断链
                connections.set(i, existing.id().equals(connection.id())
                        ? connection : connection.withId(existing.id()));
                return;
            }
        }
        connections.add(connection);
    }

    /**
     * 批量新增或更新并<b>只落盘一次</b>。
     *
     * <p>导入几十上百条连接时逐条 {@link #upsert} 会触发同数量的全量
     * 序列化与全量重加密，这里合并为一次写。</p>
     *
     * @param batch 连接清单
     */
    public synchronized void upsertAll(List<Connection> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (Connection connection : batch) {
            upsertInMemory(connection);
        }
        save();
    }

    /**
     * 整体接管另一个库（刚从磁盘加载的）的内容与暂存密文。
     *
     * <p>界面「刷新连接列表」用：只替换连接清单会丢掉 {@link #preservedSecrets}，
     * 之后一次保存就会把解不开的密文覆盖成空串，等于刷新一下反而丢数据。</p>
     *
     * @param source 来源库
     */
    public synchronized void adoptFrom(ConnectionStore source) {
        connections.clear();
        connections.addAll(source.connections);
        preservedSecrets.clear();
        preservedSecrets.putAll(source.preservedSecrets);
    }

    /**
     * 保存连接。名称变了时先去掉旧名，避免改名后留下两条。
     *
     * @param previousName 改名前的名称；新建时传 null
     * @param connection   新连接
     */
    public synchronized void replace(String previousName, Connection connection) {
        if (previousName != null && !previousName.equals(connection.name())) {
            removeByName(previousName);
            if (previousName.equals(lastConnection)) {
                lastConnection = connection.name();
            }
            // 改名：暂存密文跟着一起改挂到新名下，否则改名后保存会清空解不开的密文
            Map<String, String> moved = preservedSecrets.remove(previousName);
            if (moved != null) {
                preservedSecrets.put(connection.name(), moved);
            }
        }
        commit(connection);
    }

    private void removeByName(String name) {
        for (java.util.Iterator<Connection> it = connections.iterator(); it.hasNext(); ) {
            if (it.next().name().equals(name)) {
                it.remove();
            }
        }
    }

    /**
     * 删除连接并落盘。
     *
     * @param name 连接名
     * @return true 表示删除了
     */
    public synchronized boolean remove(String name) {
        boolean removed = false;
        for (java.util.Iterator<Connection> it = connections.iterator(); it.hasNext(); ) {
            if (it.next().name().equals(name)) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            if (name.equals(lastConnection)) {
                lastConnection = "";
            }
            preservedSecrets.remove(name);
            save();
        }
        return removed;
    }

    /**
     * 按名称取连接。
     *
     * @param name 连接名
     * @return 连接，不存在返回 null
     */
    public synchronized Connection find(String name) {
        for (Connection connection : connections) {
            if (connection.name().equals(name)) {
                return connection;
            }
        }
        return null;
    }

    /**
     * 按稳定 id 取连接：改名不影响查找。
     *
     * @param id 连接 id
     * @return 连接，不存在返回 null
     */
    public synchronized Connection findById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        for (Connection connection : connections) {
            if (id.equals(connection.id())) {
                return connection;
            }
        }
        return null;
    }

    /**
     * 更新连接里的单个配置值并落盘（密钥字段照常加密）。用于引擎回推的凭据刷新，
     * 例如 QQ 邮箱附件的 Set-Cookie。值不打日志。
     *
     * @param id    连接 id
     * @param key   配置键
     * @param value 新值
     * @return true 已更新；连接不存在或值未变返回 false
     */
    public synchronized boolean updateConfigValue(String id, String key, String value) {
        Connection current = findById(id);
        if (current == null || value == null || value.equals(current.config().get(key))) {
            return false;
        }
        Map<String, String> config = new LinkedHashMap<String, String>(current.config());
        config.put(key, value);
        upsertInMemory(current.with(current.name(), current.platform(), config));
        save();
        return true;
    }

    /**
     * 落盘（原子写）。
     */
    public synchronized void save() {
        List<Map<String, Object>> array = new ArrayList<Map<String, Object>>();
        for (Connection connection : connections) {
            array.add(toMap(connection));
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("connections", array);
        root.put("lastConnection", lastConnection);
        try {
            JsonStore.writeString(file, JSON.toPrettifyJsonString(root));
            restrictToOwner(file);
        } catch (IOException ignore) {
            // 保存失败不打断界面，下次操作会再试
        }
    }

    /** 连接文件含密钥，收成仅所有者可读写。非 POSIX 文件系统忽略。 */
    private static void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignore) {
            // Windows 等没有 POSIX 权限位
        }
    }

    private Map<String, Object> toMap(Connection connection) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", connection.id());
        map.put("name", connection.name());
        map.put("platform", connection.platform());
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> entry : connection.config().entrySet()) {
            config.put(entry.getKey(),
                    encodeIfSecret(connection.platform(), entry.getKey(), entry.getValue(),
                            connection.name()));
        }
        map.put("config", config);
        return map;
    }

    private String encodeIfSecret(String platform, String key, String value, String connectionName) {
        if (!isSecretField(platform, key)) {
            return value;
        }
        // 已是本加密器的密文（含解不开被保留下来的），原样写回，绝不二次加密
        if (cipher.isEncrypted(value)) {
            return value;
        }
        Map<String, String> preserved = preservedSecrets.get(connectionName);
        if (value.isEmpty()) {
            // 留空 = 用户没打算改，写回暂存的旧密文
            if (preserved != null && preserved.containsKey(key)) {
                return preserved.get(key);
            }
        } else if (preserved != null && preserved.remove(key) != null && preserved.isEmpty()) {
            // 录入了新值：该字段的暂存作废，以录入值为准
            preservedSecrets.remove(connectionName);
        }
        return cipher.encrypt(value);
    }

    private Connection fromMap(Map<String, Object> map) {
        String name = String.valueOf(map.getOrDefault("name", ""));
        String platform = String.valueOf(map.getOrDefault("platform", ""));
        Map<String, String> config = new LinkedHashMap<String, String>();
        Object raw = map.get("config");
        if (raw instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = String.valueOf(entry.getKey());
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                config.put(key, decodeIfSecret(name, platform, key, value));
            }
        }
        String id = String.valueOf(map.getOrDefault("id", ""));
        if (id.trim().isEmpty() || "null".equals(id)) {
            // 旧配置没有 id：补一个，落盘后就稳定下来
            return new Connection(name, platform, config);
        }
        return new Connection(id, name, platform, config);
    }

    private String decodeIfSecret(String connectionName, String platform, String key, String value) {
        if (!isSecretField(platform, key)) {
            return value;
        }
        if (cipher.isEncrypted(value)) {
            String plain = cipher.decrypt(value);
            if (plain.isEmpty()) {
                // 解不开：界面上显示为空供重新录入，但原密文暂存下来，
                // 落盘时原样写回，避免被空串覆盖导致永久丢失
                preservedSecrets
                        .computeIfAbsent(connectionName, ignored -> new LinkedHashMap<String, String>())
                        .put(key, value);
            }
            return plain;
        }
        if (value.startsWith(LEGACY_PREFIX)) {
            try {
                return new String(Base64.getDecoder().decode(value.substring(LEGACY_PREFIX.length())),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignore) {
                return value;
            }
        }
        return value;
    }

    /**
     * 用平台字段定义判断某键是否密钥。
     *
     * <p>平台未注册（手写配置、导入了新版本才有的平台）时查不到字段定义，
     * 此时按字段名兜底判定——否则密码类字段会以明文落盘。</p>
     */
    private static boolean isSecretField(String platform, String key) {
        StoragePlatform defined = FileStorages.platform(platform);
        if (defined == null) {
            return looksLikeSecretName(key);
        }
        for (StorageField field : defined.fields()) {
            if (field.key().equals(key) && field.isSecret()) {
                return true;
            }
        }
        return false;
    }

    /** 字段名兜底：含密钥类关键词即视为密钥（不匹配裸 "key"，避免误判 accessKey 等公开标识）。 */
    private static boolean looksLikeSecretName(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String hint : SECRET_NAME_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
