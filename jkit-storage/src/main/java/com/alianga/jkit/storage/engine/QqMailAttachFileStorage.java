package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 邮箱网页版「附件管理」只读引擎（{@code qqmail-attach}）。
 *
 * <p><b>目录</b>：根下虚拟目录 {@code /全部 /图片 /文档 /音频 /视频 /压缩包 /其他 /收藏}
 * （attach_type 1..7；收藏为 func=2）。<b>文件</b>：{@code /<类型>/<name>__<shortId>.<ext>}，
 * shortId = sha256(mailid + "\n" + fileid) 前 8 位 hex，同名附件可区分。</p>
 *
 * <p><b>下载/预览</b>：GET {@code /attach/download|preview|thumbnail?mailid&fileid&name&sid}，
 * 302 到 {@code /download/download}（普通）或 {@code *.ftn.qq.com}（超大附件），Cookie 经
 * {@link QqMailCookieJar} 仅发 *.qq.com，最多 5 次重定向。</p>
 *
 * <p><b>只读</b>：upload/delete/mkdir/copy/rename → UNSUPPORTED。扩展：{@link #setCollect}、
 * {@link #openPreview}、{@link #openThumbnail}、{@link #checkSid}。</p>
 *
 * <p><b>凭据</b>：cookie、sid、重定向地址、大附件 key/code 与其 JSON fileid 均不进日志/异常/路径。</p>
 *
 * <p>TODO(待抓包)：必需 Cookie 名与有效期；{@code ret} 鉴权失败码（暂由 {@code authRetCodes} 配置）；
 * {@code /read/readmail} 取 Message-ID 以接 IMAP 回退。</p>
 *
 * @author 郑明亮
 */
public class QqMailAttachFileStorage implements FileStorage {
    /** 平台 / 引擎 id。 */
    public static final String ENGINE = "qqmail-attach";
    static final String BASE_URL = QqMailWebClient.BASE_URL;
    static final String FAVORITES = "收藏";
    /** 目录名 → attach_type（收藏单独 func=2）。 */
    static final Map<String, Integer> TYPES = new LinkedHashMap<String, Integer>();

    static {
        TYPES.put("全部", 1);
        TYPES.put("图片", 2);
        TYPES.put("文档", 3);
        TYPES.put("音频", 4);
        TYPES.put("视频", 5);
        TYPES.put("压缩包", 6);
        TYPES.put("其他", 7);
    }

    private static final List<String> ROOT_DIRS;

    static {
        List<String> dirs = new ArrayList<String>(TYPES.keySet());
        dirs.add(FAVORITES);
        ROOT_DIRS = Collections.unmodifiableList(dirs);
    }

    private static final Pattern FILE_NAME = Pattern.compile("^(.*)__([0-9a-f]{8})((?:\\.[^./]*)?)$");
    private static final Set<String> IMAGE_EXT = new HashSet<String>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tif", "tiff"));

    /** 节流休眠（可注入）。 */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final QqMailWebClient client;
    private final QqMailCookieJar jar;
    private final int sortType;
    private final int sortDirection;
    private final int pageSize;
    private final long requestIntervalMs;
    private final Set<Long> authRetCodes;
    private final Sleeper sleeper;
    private final LongSupplier clock;
    private volatile AttachImapFallback imapFallback;
    private final boolean imapFallbackConfigured;
    private final Map<String, QqMailAttachItem> index = new ConcurrentHashMap<String, QqMailAttachItem>();
    private long lastRequestAt = -1L;

    /**
     * 生产构造。
     *
     * @param config sid（必填）、cookie（秘密）、sortType、sortDirection、pageSize、requestIntervalMs、imapFallback
     */
    public QqMailAttachFileStorage(StorageConfig config) {
        this(config, null, null, null);
    }

    QqMailAttachFileStorage(StorageConfig config, QqMailTransport transport, Sleeper sleeper, LongSupplier clock) {
        if (config == null) {
            throw StorageException.invalidConfig("qqmail-attach 配置不能为 null");
        }
        String sid = config.getOr("sid", "").trim();
        if (sid.isEmpty()) {
            throw StorageException.invalidConfig("缺少必填配置: sid");
        }
        this.jar = new QqMailCookieJar(config.getOr("cookie", ""));
        List<String> missing = jar.missing(REQUIRED_COOKIES);
        if (!missing.isEmpty()) {
            throw new StorageException(StorageErrorCode.AUTH_FAILED,
                    "cookie 不完整，缺少 " + String.join("、", missing)
                            + "；请在已登录 wx.mail.qq.com 的浏览器里重新复制完整 Cookie", null);
        }
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.client = new QqMailWebClient(sid, jar,
                transport != null ? transport : QqMailWebClient.httpCoreTransport(config), this.clock);
        this.sortType = (int) config.longOr("sortType", 1L);
        this.sortDirection = (int) config.longOr("sortDirection", 1L);
        this.pageSize = (int) Math.max(1L, Math.min(500L, config.longOr("pageSize", 100L)));
        this.requestIntervalMs = Math.max(0L, config.longOr("requestIntervalMs", 300L));
        this.authRetCodes = parseCodes(config.getOr("authRetCodes", "-3,-101,-102,-20002"));
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
        // TODO: imapFallback 引用 mail 引擎配置；readmail 取 Message-ID 未抓包，宿主可 setImapFallback 注入
        this.imapFallbackConfigured = !config.getOr("imapFallback", "").trim().isEmpty();
    }

    // ------------------------------------------------------------------ 扩展 API

    /** @param listener Cookie 更新回调（attach_list 等响应的 Set-Cookie），宿主可加密写回配置 */
    public void setCookieUpdateListener(QqMailCookieJar.CookieUpdateListener listener) {
        jar.setListener(listener);
    }

    /** @param fallback 网页下载失败时的 IMAP 回退（超大附件不回退） */
    public void setImapFallback(AttachImapFallback fallback) {
        this.imapFallback = fallback;
    }

    /** 校验 sid；失效抛 AUTH_FAILED（需重新扫码），不自动重试。 */
    public void checkSid() {
        call("/login/checksid", new LinkedHashMap<String, String>(), true);
    }

    /**
     * 收藏 / 取消收藏。
     *
     * @param path    文件路径
     * @param collect true 收藏
     */
    public void setCollect(String path, boolean collect) {
        QqMailAttachItem item = requireItem(path);
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("filter", "{\"fileid\":" + jsonStr(item.fileid) + ",\"name\":" + jsonStr(item.name)
                + ",\"mailid\":" + jsonStr(item.mailid) + "}");
        form.put("is_collect", String.valueOf(collect));
        call("/attach/set_collect", form);
        item.collect = collect;
    }

    /**
     * 原文件预览流（GET /attach/preview → 302 → 原文件）；宿主 inline 输出，重定向地址不外泄。
     *
     * @param path 文件路径
     * @return 流（调用方 close）
     */
    public QqMailAttachStream openPreview(String path) {
        return client.openStream("/attach/preview", attachQuery(requireItem(path)));
    }

    /**
     * 缩略图流（GET /attach/thumbnail）；仅图片。
     *
     * @param path 文件路径
     * @return 流（调用方 close）
     */
    public QqMailAttachStream openThumbnail(String path) {
        QqMailAttachItem item = requireItem(path);
        if (!isImage(item.name)) {
            throw StorageException.unsupported("仅图片支持缩略图: " + path);
        }
        return client.openStream("/attach/thumbnail", attachQuery(item));
    }

    // ------------------------------------------------------------------ FileStorage

    @Override
    public String platform() {
        return ENGINE;
    }

    @Override
    public boolean exists(String path) {
        try {
            stat(path);
            return true;
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public StorageEntry stat(String path) {
        String p = StoragePaths.normalize(path);
        if (p.isEmpty()) {
            return StorageEntry.dir("");
        }
        if (ROOT_DIRS.contains(p)) {
            return StorageEntry.dir(p);
        }
        QqMailAttachItem item = requireItem(p);
        return toEntry(StoragePaths.parent(p), item);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String d = StoragePaths.normalize(dir);
        List<StorageEntry> out = new ArrayList<StorageEntry>();
        if (d.isEmpty()) {
            for (String name : ROOT_DIRS) {
                out.add(StorageEntry.dir(name));
            }
            return out;
        }
        if (!ROOT_DIRS.contains(d)) {
            throw StorageException.notFound(d);
        }
        // 只取第一页（附件可达数万条）；更多页由 listPage 按需加载
        return listPage(d, 0).entries;
    }

    /** 一页附件列表（服务器分页）。 */
    public static final class Page {
        /** 本页条目。 */
        public final List<StorageEntry> entries;
        /** 服务器返回的总数（body.total；缺失为 -1）。 */
        public final long total;
        /** 页号（从 0 起）。 */
        public final int pageNow;
        /** 每页大小。 */
        public final int pageSize;

        Page(List<StorageEntry> entries, long total, int pageNow, int pageSize) {
            this.entries = entries;
            this.total = total;
            this.pageNow = pageNow;
            this.pageSize = pageSize;
        }

        /** @return 是否还有下一页 */
        public boolean hasMore() {
            if (total >= 0L) {
                return (long) (pageNow + 1) * pageSize < total;
            }
            return entries.size() >= pageSize;
        }
    }

    /**
     * 按服务器分页取一页（排序用配置的 sortType / sortDirection）。
     *
     * @param dir     分类目录（如 {@code 全部}）
     * @param pageNow 页号，从 0 起
     * @return 一页
     */
    public Page listPage(String dir, int pageNow) {
        return listPage(dir, pageNow, sortType, sortDirection, null);
    }

    /**
     * 按服务器分页取一页；排序与搜索都交给服务器。
     *
     * @param dir           分类目录
     * @param pageNow       页号，从 0 起
     * @param sortType      服务器 sort_type
     * @param sortDirection 服务器 sort_direction
     * @param key           搜索关键字；null 走普通列表
     * @return 一页
     */
    public Page listPage(String dir, int pageNow, int sortType, int sortDirection, String key) {
        String d = StoragePaths.normalize(dir);
        if (!ROOT_DIRS.contains(d)) {
            throw StorageException.notFound(d);
        }
        PageResult r = fetchPage(d, key, Math.max(0, pageNow), sortType, sortDirection);
        List<StorageEntry> out = new ArrayList<StorageEntry>(r.items.size());
        for (QqMailAttachItem item : r.items) {
            out.add(toEntry(d, item));
        }
        return new Page(out, r.total, Math.max(0, pageNow), pageSize);
    }

    private static final class PageResult {
        final List<QqMailAttachItem> items;
        final int rawCount;
        final long total;

        PageResult(List<QqMailAttachItem> items, int rawCount, long total) {
            this.items = items;
            this.rawCount = rawCount;
            this.total = total;
        }
    }

    /** 单页请求（表单字段顺序同网页 HAR）。 */
    private PageResult fetchPage(String dir, String key, int page, int sortType, int sortDirection) {
        boolean fav = FAVORITES.equals(dir);
        int type = fav ? 1 : TYPES.get(dir);
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("page_size", String.valueOf(pageSize));
        form.put("page_now", String.valueOf(page));
        form.put("sort_type", String.valueOf(sortType));
        form.put("func", fav ? "2" : "1");
        form.put("attach_type", String.valueOf(type));
        if (key != null) {
            form.put("key", key);
        }
        form.put("sort_direction", String.valueOf(sortDirection));
        String api = key != null ? "/attach/search" : "/attach/attach_list";
        Map<String, Object> resp = call(api, form);
        Map<String, Object> body = bodyOf(resp, api);
        List<Map<String, Object>> raws = itemsOf(body, api);
        List<QqMailAttachItem> items = new ArrayList<QqMailAttachItem>();
        for (Map<String, Object> raw : raws) {
            QqMailAttachItem item = new QqMailAttachItem(raw);
            if (item.shortId == null) {
                continue;
            }
            index.put(item.shortId, item);
            if (fav && !item.collect && raw.get("is_collect") != null) {
                continue;
            }
            if (fav) {
                item.collect = true;
            }
            items.add(item);
        }
        return new PageResult(items, raws.size(), QqMailAttachItem.num(body.get("total"), -1L));
    }

    @Override
    public void download(String path, OutputStream out) {
        if (out == null) {
            throw StorageException.invalidConfig("download 输出流不能为 null");
        }
        QqMailAttachItem item = requireItem(path);
        try {
            QqMailAttachStream in = client.openStream("/attach/download", attachQuery(item));
            try {
                pump(in, out, path);
            } finally {
                closeQuietly(in);
            }
        } catch (StorageException e) {
            AttachImapFallback fb = imapFallback;
            if (fb == null || item.isBig() || e.code() == StorageErrorCode.AUTH_FAILED) {
                throw e;
            }
            fb.download(item, out);
        }
    }

    /** @return 是否配置了 imapFallback（TODO：自动装配） */
    boolean imapFallbackConfigured() {
        return imapFallbackConfigured;
    }

    @Override
    public void mkdir(String dir) {
        throw readOnly();
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        throw readOnly();
    }

    @Override
    public void delete(String path) {
        throw readOnly();
    }

    @Override
    public void copy(String src, String dst) {
        throw readOnly();
    }

    @Override
    public void rename(String src, String dst) {
        throw readOnly();
    }

    @Override
    public void close() {
        index.clear();
    }

    @Override
    public String toString() {
        return "QqMailAttachFileStorage{redacted}";
    }

    // ------------------------------------------------------------------ 内部

    private static StorageException readOnly() {
        return StorageException.unsupported("qqmail-attach 为只读引擎");
    }

    private Map<String, String> attachQuery(QqMailAttachItem item) {
        Map<String, String> q = new LinkedHashMap<String, String>();
        q.put("mailid", item.mailid == null ? "" : item.mailid);
        q.put("fileid", item.fileid == null ? "" : item.fileid); // 大附件为含 key/code 的 JSON，URL 编码、不记录
        q.put("name", item.name == null ? "" : item.name);
        return q;
    }

    /** 按路径定位条目：先查索引，冷启动用 /attach/search 重建。 */
    QqMailAttachItem requireItem(String path) {
        String p = StoragePaths.normalize(path);
        String dir = StoragePaths.parent(p);
        if (!ROOT_DIRS.contains(dir)) {
            throw StorageException.notFound(p);
        }
        Matcher m = FILE_NAME.matcher(StoragePaths.name(p));
        if (!m.matches()) {
            throw StorageException.notFound(p);
        }
        String original = m.group(1) + m.group(3);
        String sid8 = m.group(2);
        QqMailAttachItem hit = index.get(sid8);
        if (hit == null) {
            fetchAll(dir, original);
            hit = index.get(sid8);
        }
        if (hit == null || !safeName(hit.name).equals(original)) {
            throw StorageException.notFound(p);
        }
        if (FAVORITES.equals(dir) && !hit.collect) {
            throw StorageException.notFound(p);
        }
        return hit;
    }

    /** 搜索最多翻多少页（定位单个文件用，不做全量拉取）。 */
    static final int MAX_SEARCH_PAGES = 10;

    /**
     * 按关键字分页搜索（key 为 null 时为普通列表，仅测试 / 兼容用）：按 body.total 翻页，
     * 短页先重试，仍不满报错，绝不静默截断；key 非空最多 {@link #MAX_SEARCH_PAGES} 页。
     */
    List<QqMailAttachItem> fetchAll(String dir, String key) {
        List<QqMailAttachItem> all = new ArrayList<QqMailAttachItem>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int shortPageRetries = 0;
        for (int page = 0; key == null || page < MAX_SEARCH_PAGES; page++) {
            PageResult r = fetchPage(dir, key, page, sortType, sortDirection);
            for (QqMailAttachItem item : r.items) {
                if (seen.add(item.shortId)) {
                    all.add(item);
                }
            }
            long total = r.total;
            if (total < 0L) {
                if (r.rawCount == 0 || r.rawCount < pageSize) {
                    break;
                }
                continue;
            }
            if ((long) (page + 1) * pageSize >= total) {
                break;
            }
            if (r.rawCount < pageSize) {
                if (shortPageRetries < PAGE_RETRIES) {
                    shortPageRetries++;
                    page--;
                    continue;
                }
                throw StorageException.io("QQ 附件列表分页不完整：" + dir + " 第 " + (page + 1) + " 页只返回 "
                        + r.rawCount + " 条（每页 " + pageSize + "，total=" + total + "，已取 " + all.size()
                        + "）", dir, null);
            }
            shortPageRetries = 0;
        }
        return all;
    }

    /** 节流 + ret 映射。 */
    Map<String, Object> call(String path, Map<String, String> form) {
        return call(path, form, false);
    }

    private Map<String, Object> call(String path, Map<String, String> form, boolean inQuery) {
        throttle();
        Map<String, Object> resp = client.post(path, form, inQuery);
        // HAR 实测：所有接口 ret 在 head.ret；head.stack / show_err_msg 不进异常
        Object head = resp.get("head");
        if (!(head instanceof Map) || ((Map<?, ?>) head).get("ret") == null) {
            throw badFormat(path);
        }
        long ret = QqMailAttachItem.num(((Map<?, ?>) head).get("ret"), Long.MIN_VALUE);
        if (ret == Long.MIN_VALUE) {
            throw badFormat(path);
        }
        if (ret != 0L) {
            throw mapRet(ret, path);
        }
        return resp;
    }

    /** 网页登录态必需的 Cookie 名。 */
    static final String[] REQUIRED_COOKIES = {"xm_sid", "xm_skey", "xm_data_ticket", "xm_uin"};
    /** 一页数量不足 / 拿不满 total 时的重试次数。 */
    static final int PAGE_RETRIES = 2;

    StorageException mapRet(long ret, String path) {
        if (ret == -20002L) {
            return new StorageException(StorageErrorCode.AUTH_FAILED,
                    "登录态无效或 cookie 不完整（ret=-20002 " + path + "），请重新扫码登录", null);
        }
        if (authRetCodes.contains(ret)) {
            return new StorageException(StorageErrorCode.AUTH_FAILED,
                    "QQ 邮箱网页会话失效（ret=" + ret + " " + path + "），请重新扫码登录", null);
        }
        if (ret == -21619L) {
            return StorageException.unsupported("批量打包超过 50MB 限制（ret=-21619）");
        }
        return StorageException.io("QQ 邮箱接口失败 ret=" + ret + "（" + path + "）", null, null);
    }

    private void throttle() {
        synchronized (this) {
            long now = clock.getAsLong();
            if (lastRequestAt >= 0L && requestIntervalMs > 0L) {
                long wait = requestIntervalMs - (now - lastRequestAt);
                if (wait > 0L) {
                    try {
                        sleeper.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new StorageException(StorageErrorCode.CANCELLED, "节流等待被中断", null);
                    }
                    now += wait;
                }
            }
            lastRequestAt = now;
        }
    }

    private static StorageException badFormat(String api) {
        return StorageException.io("unexpected response format: " + api, null, null);
    }

    /** HAR 实测：{@code body.attach_list.attach[]}，{@code body.total}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Map<String, Object> resp, String api) {
        Object body = resp.get("body");
        if (!(body instanceof Map)) {
            throw badFormat(api);
        }
        return (Map<String, Object>) body;
    }

    /**
     * {@code body.attach_list.attach[]}；搜索无结果时 {@code attach_list} 为 {@code {}} → 空列表。
     * 缺 attach_list、或 attach 存在但非数组 → IO_ERROR。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> body, String api) {
        Object al = body.get("attach_list");
        if (!(al instanceof Map)) {
            throw badFormat(api);
        }
        Map<String, Object> attachList = (Map<String, Object>) al;
        if (!attachList.containsKey("attach")) {
            return Collections.emptyList();
        }
        Object v = attachList.get("attach");
        if (!(v instanceof List)) {
            throw badFormat(api);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object o : (List<Object>) v) {
            if (!(o instanceof Map)) {
                throw badFormat(api);
            }
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    private StorageEntry toEntry(String dir, QqMailAttachItem item) {
        return StorageEntry.file(StoragePaths.join(dir, fileName(item)), item.size, item.mtimeMs,
                item.md5 != null && !item.md5.isEmpty() ? item.md5 : item.shortId)
                .withAttribute(StorageEntry.ATTR_COLLECTED, String.valueOf(item.collect));
    }

    /** {@code name__shortId.ext}；无扩展名 / 点开头视为无扩展名。 */
    static String fileName(QqMailAttachItem item) {
        String name = safeName(item.name);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + "__" + item.shortId;
        }
        return name.substring(0, dot) + "__" + item.shortId + name.substring(dot);
    }

    private static String safeName(String name) {
        String n = name == null || name.isEmpty() ? "unnamed" : name;
        return n.replace('/', '_').replace('\\', '_');
    }

    static String shortId(String mailid, String fileid) {
        if (mailid == null && fileid == null) {
            return null;
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(
                    ((mailid == null ? "" : mailid) + "\n" + (fileid == null ? "" : fileid))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", h[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isImage(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXT.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static Set<Long> parseCodes(String raw) {
        Set<Long> out = new HashSet<Long>();
        for (String s : raw.split(",")) {
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static void pump(InputStream in, OutputStream out, String path) {
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw StorageException.io("附件下载读写失败", path, e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
