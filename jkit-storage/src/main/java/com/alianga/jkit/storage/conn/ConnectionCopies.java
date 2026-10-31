package com.alianga.jkit.storage.conn;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StoragePlatform;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「复制连接」：按已有连接生成一份待编辑的新连接（新 id、名称加「副本」）。
 *
 * <p>密钥处理：密码 / 授权码 / AccessKey 等<b>长期凭证照抄</b>，复制出来改个路径
 * 或主机即可直接用；只有<b>会话级令牌</b>（QQ 邮箱附件的 {@code sid} / {@code cookie}，
 * 每次网页登录都会变、且会被引擎回写刷新）一律清空，避免两条连接共用并互相覆盖同一登录态。
 * 源连接里读不出来的密钥（密钥文件丢失）也不抄，免得把坏密文带过去。</p>
 *
 * @author 郑明亮
 */
public final class ConnectionCopies {
    /** QQ 邮箱附件只读引擎 id（会话级字段声明用）。 */
    private static final String QQMAIL_ATTACH_ENGINE = "qqmail-attach";

    /**
     * 会话级字段声明：某引擎哪些配置项是「登录态」而非长期凭证，复制连接时清空。
     *
     * @author 郑明亮
     */
    public static final class SessionFields {
        private final String engine;
        private final Set<String> fields;

        /**
         * @param engine 引擎 id（按引擎匹配，平台 id 换了也照样生效）
         * @param fields 会话级字段键
         */
        public SessionFields(String engine, Set<String> fields) {
            this.engine = engine;
            this.fields = Collections.unmodifiableSet(new LinkedHashSet<String>(fields));
        }

        /** @return 引擎 id */
        public String engine() {
            return engine;
        }

        /** @return 会话级字段键集合 */
        public Set<String> fields() {
            return fields;
        }
    }

    /**
     * 声明表：新增带会话令牌（sid / cookie / session / token 之类）的引擎时<b>在这里加一行</b>。
     * {@code ConnectionCopiesTest} 会扫描全部平台的字段，出现疑似会话字段却未声明时直接失败。
     */
    static final List<SessionFields> SESSION_FIELD_TABLE = Collections.singletonList(
            new SessionFields(QQMAIL_ATTACH_ENGINE,
                    new LinkedHashSet<String>(Arrays.asList("sid", "cookie"))));

    private static final Map<String, Set<String>> SESSION_FIELDS = index(SESSION_FIELD_TABLE);

    private static Map<String, Set<String>> index(List<SessionFields> table) {
        Map<String, Set<String>> map = new LinkedHashMap<String, Set<String>>();
        for (SessionFields entry : table) {
            Set<String> existing = map.get(entry.engine());
            if (existing == null) {
                map.put(entry.engine(), entry.fields());
                continue;
            }
            Set<String> merged = new LinkedHashSet<String>(existing);
            merged.addAll(entry.fields());
            map.put(entry.engine(), Collections.unmodifiableSet(merged));
        }
        return Collections.unmodifiableMap(map);
    }

    private ConnectionCopies() {
    }

    /**
     * 复制时会被清空的字段（会话级令牌）。
     *
     * @param platform 平台 id
     * @return 字段键集合
     */
    public static Set<String> clearedFields(String platform) {
        StoragePlatform resolved = FileStorages.platform(platform);
        String engine = resolved == null ? platform : resolved.engine();
        Set<String> fields = SESSION_FIELDS.get(engine);
        return fields == null ? Collections.<String>emptySet() : fields;
    }

    /**
     * 生成副本（不落盘，交给编辑表单确认后保存）。
     *
     * @param source        源连接
     * @param existingNames 已有连接名（用于避开重名）
     * @param brokenFields  源连接中解不开的密钥字段（不复制）
     * @return 新连接：新 id、独立的配置 Map
     */
    public static Connection copyOf(Connection source, Collection<String> existingNames,
                                    Set<String> brokenFields) {
        Map<String, String> config = new LinkedHashMap<String, String>(source.config());
        config.keySet().removeAll(clearedFields(source.platform()));
        if (brokenFields != null) {
            config.keySet().removeAll(brokenFields);
        }
        return new Connection(copyName(source.name(), existingNames), source.platform(), config);
    }

    /**
     * 副本名：「原名 副本」，重名时依次「原名 副本 2」「原名 副本 3」…
     *
     * @param name          原名
     * @param existingNames 已有连接名
     * @return 不重名的副本名
     */
    public static String copyName(String name, Collection<String> existingNames) {
        Collection<String> taken = existingNames == null
                ? Collections.<String>emptyList() : existingNames;
        String base = name + " 副本";
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + " " + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }
}
