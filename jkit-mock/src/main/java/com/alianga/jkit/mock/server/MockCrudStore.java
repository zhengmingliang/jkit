package com.alianga.jkit.mock.server;

import com.alianga.jkit.json.JSON;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CRUD 资源的内存数据存储（json-server 风格子集）：每个资源是一张 {@code List<Map>}，
 * 支持等值过滤、全文 {@code _q}、排序、分页 / 切片，以及增删改与可选的文件持久化。
 *
 * <p>保留 query 参数（json-server 惯例）：{@code _page}（1 起）与 {@code _limit}、
 * {@code _start} / {@code _end}、{@code _sort} + {@code _order}（asc/desc）、
 * {@code _q}（全字段包含匹配）；其余参数按「字段等值」过滤。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockCrudStore {
    private static final String PAGE = "_page";
    private static final String LIMIT = "_limit";
    private static final String START = "_start";
    private static final String END = "_end";
    private static final String SORT = "_sort";
    private static final String ORDER = "_order";
    private static final String FULL_TEXT = "_q";
    /** json-server 惯例的全文检索参数（与 {@code _q} 等效，等值过滤时跳过）。 */

    private final Map<String, List<Map<String, Object>>> resources = new LinkedHashMap<>();
    private final Map<String, AtomicLong> sequences = new LinkedHashMap<>();
    private final Path dataDir;

    /**
     * @param dataDir 数据落盘目录；{@code null} 表示纯内存（重启即失）
     */
    public MockCrudStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    /** 纯内存存储。 */
    public static MockCrudStore inMemory() {
        return new MockCrudStore(null);
    }

    /** 资源是否已有数据（含已落盘加载）。 */
    public synchronized boolean exists(String resource) {
        return resources.containsKey(resource);
    }

    /**
     * 播种初始数据（仅在资源尚不存在时生效），并立即落盘。
     *
     * @param resource 资源名
     * @param rows     初始记录（深拷贝后保存）
     */
    public synchronized void seed(String resource, List<Map<String, Object>> rows) {
        // 盘上有历史数据就先加载，绝不覆盖
        if (!rowsOf(resource).isEmpty()) {
            return;
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            copy.add(new LinkedHashMap<>(row));
        }
        resources.put(resource, copy);
        sequences.put(resource, maxId(copy));
        persistQuietly(resource);
    }

    /** 查询：过滤 + 全文 + 排序 + 分页 / 切片。 */
    public synchronized List<Map<String, Object>> query(String resource, Map<String, List<String>> query) {
        List<Map<String, Object>> rows = rowsOf(resource);
        List<Map<String, Object>> out = new ArrayList<>();
        String fullText = first(query, FULL_TEXT);
        if (fullText == null) {
            fullText = first(query, "q");
        }
        for (Map<String, Object> row : rows) {
            if (fullText != null && !containsText(row, fullText)) {
                continue;
            }
            if (!equalsFilters(row, query)) {
                continue;
            }
            out.add(row);
        }
        out = sorted(out, first(query, SORT), first(query, ORDER));
        return sliced(out, query);
    }

    /** 按 id 查一条；不存在返回 {@code null}。 */
    public synchronized Map<String, Object> find(String resource, String id) {
        for (Map<String, Object> row : rowsOf(resource)) {
            if (id.equals(String.valueOf(row.get("id")))) {
                return row;
            }
        }
        return null;
    }

    /** 新增：生成自增 id，返回带 id 的新记录。 */
    public synchronized Map<String, Object> insert(String resource, Map<String, Object> body) {
        List<Map<String, Object>> rows = rowsOf(resource);
        AtomicLong seq = sequences.computeIfAbsent(resource, k -> maxId(rows));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", seq.incrementAndGet());
        row.putAll(body);
        row.remove("id");
        // id 永远排第一，保证输出稳定
        Map<String, Object> withId = new LinkedHashMap<>();
        withId.put("id", seq.get());
        withId.putAll(row);
        rows.add(withId);
        persistQuietly(resource);
        return withId;
    }

    /** 更新：{@code patch=false} 整体替换（保留 id）；不存在返回 {@code null}。 */
    public synchronized Map<String, Object> update(String resource, String id,
                                                   Map<String, Object> body, boolean patch) {
        List<Map<String, Object>> rows = rowsOf(resource);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> old = rows.get(i);
            if (id.equals(String.valueOf(old.get("id")))) {
                Map<String, Object> next = patch ? new LinkedHashMap<>(old) : new LinkedHashMap<>();
                next.putAll(body);
                next.put("id", old.get("id"));
                rows.set(i, next);
                persistQuietly(resource);
                return next;
            }
        }
        return null;
    }

    /** 删除：返回被删记录；不存在返回 {@code null}。 */
    public synchronized Map<String, Object> delete(String resource, String id) {
        List<Map<String, Object>> rows = rowsOf(resource);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (id.equals(String.valueOf(row.get("id")))) {
                rows.remove(i);
                persistQuietly(resource);
                return row;
            }
        }
        return null;
    }

    /** 清空指定资源（测试 / 重置用）。 */
    public synchronized void reset(String resource) {
        resources.remove(resource);
        sequences.remove(resource);
    }

    private List<Map<String, Object>> rowsOf(String resource) {
        List<Map<String, Object>> rows = resources.get(resource);
        if (rows == null && dataDir != null) {
            rows = loadFromDisk(resource);
            if (rows != null) {
                resources.put(resource, rows);
                sequences.put(resource, maxId(rows));
            }
        }
        return rows != null ? rows : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFromDisk(String resource) {
        if (dataDir == null) {
            return null;
        }
        Path file = dataDir.resolve(resource + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Object parsed = JSON.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof List) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object o : (List<?>) parsed) {
                    if (o instanceof Map) {
                        rows.add(new LinkedHashMap<>((Map<String, Object>) o));
                    }
                }
                return rows;
            }
        } catch (IOException | RuntimeException ignore) {
            // 数据文件损坏按空资源处理
        }
        return null;
    }

    private void persistQuietly(String resource) {
        if (dataDir == null) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve(resource + ".json");
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJsonString(resources.get(resource)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignore) {
            // 落盘失败不阻塞 mock 响应（内存态仍可用）
        }
    }

    private static List<Map<String, Object>> sorted(List<Map<String, Object>> rows,
                                                    String sortField, String order) {
        if (sortField == null || sortField.isEmpty()) {
            return rows;
        }
        boolean desc = "desc".equalsIgnoreCase(order);
        Comparator<Map<String, Object>> byField = (a, b) -> {
            Object av = a.get(sortField);
            Object bv = b.get(sortField);
            if (av instanceof Number && bv instanceof Number) {
                return Double.compare(((Number) av).doubleValue(), ((Number) bv).doubleValue());
            }
            return String.valueOf(av).compareTo(String.valueOf(bv));
        };
        rows.sort(desc ? byField.reversed() : byField);
        return rows;
    }

    private static List<Map<String, Object>> sliced(List<Map<String, Object>> rows,
                                                    Map<String, List<String>> query) {
        Integer start = intOrNull(query, START);
        Integer end = intOrNull(query, END);
        Integer page = intOrNull(query, PAGE);
        Integer limit = intOrNull(query, LIMIT);
        int from = 0;
        int to = rows.size();
        if (page != null && page > 0) {
            int size = limit != null && limit > 0 ? limit : 10;
            from = (page - 1) * size;
            to = Math.min(rows.size(), from + size);
        } else {
            if (start != null && start > 0) {
                from = Math.min(start, rows.size());
            }
            if (end != null && end >= from) {
                to = Math.min(end, rows.size());
            }
        }
        if (from >= to) {
            return new ArrayList<>();
        }
        return new ArrayList<>(rows.subList(from, to));
    }

    private static boolean equalsFilters(Map<String, Object> row, Map<String, List<String>> query) {
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("_") || FULL_TEXT.equals(key) || "q".equals(key)
                    || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            Object value = row.get(key);
            String expected = e.getValue().get(0);
            if (value == null || !String.valueOf(value).equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsText(Map<String, Object> row, String text) {
        for (Object value : row.values()) {
            if (value != null && String.valueOf(value).contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static String first(Map<String, List<String>> query, String key) {
        List<String> list = query.get(key);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static Integer intOrNull(Map<String, List<String>> query, String key) {
        String v = first(query, key);
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static AtomicLong maxId(List<Map<String, Object>> rows) {
        long max = 0L;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            if (id instanceof Number) {
                max = Math.max(max, ((Number) id).longValue());
            }
        }
        return new AtomicLong(max);
    }
}
