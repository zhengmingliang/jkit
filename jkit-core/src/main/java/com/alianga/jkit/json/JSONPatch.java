package com.alianga.jkit.json;

import com.alianga.jkit.json.exceptions.JSONPatchException;
import com.alianga.jkit.json.options.ReadOption;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Patch（RFC 6902）应用工具：对一份 JSON 文档施加一组操作（add / remove / replace /
 * move / copy / test），返回修改后的文档。操作文档本身是一个 JSON 数组，每个元素是一个
 * 带 {@code op} 与 {@code path} 字段的对象。
 *
 * <p>路径采用 JSON Pointer（RFC 6901）：以 {@code /} 分隔的引用记号，空字符串表示根文档；
 * 数组下标用非负整数，{@code -} 表示数组末尾；记号中的 {@code ~1} 解码为 {@code /}，
 * {@code ~0} 解码为 {@code ~}。
 *
 * <p>内部在 {@link JSON#parse} 返回的 {@link LinkedHashMap} / {@link ArrayList} 之上原地修改，
 * 因此传入的文档对象会被改动；调用方应使用返回值。字符串入口会自行解析与序列化。
 *
 * <p>示例：
 * <pre>{@code
 * String patched = JSONPatch.apply(
 *         "{\"a\":1}",
 *         "[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]");
 * // -> {"a":1,"b":2}
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class JSONPatch {
    private JSONPatch() {
    }

    /**
     * 对 JSON 文本文档施加 JSON Patch 文本，返回修改后的 JSON 文本。
     *
     * @param documentJson 原始文档（JSON 文本）
     * @param patchJson 补丁（JSON 数组文本）
     * @return 应用补丁后的文档（JSON 文本）
     */
    public static String apply(String documentJson, String patchJson) {
        return apply(documentJson, patchJson, new ReadOption[0]);
    }

    /**
     * 对 JSON 文本文档施加 JSON Patch 文本，返回修改后的 JSON 文本，并透传解析选项。
     *
     * @param documentJson 原始文档（JSON 文本）
     * @param patchJson 补丁（JSON 数组文本）
     * @param readOptions 解析选项
     * @return 应用补丁后的文档（JSON 文本）
     */
    public static String apply(String documentJson, String patchJson, ReadOption... readOptions) {
        Object document = JSON.parse(documentJson, readOptions);
        Object patch = JSON.parse(patchJson, readOptions);
        Object result = apply(document, patch);
        return JSON.toJsonString(result);
    }

    /**
     * 对已解析的文档对象施加已解析的补丁（必须是 {@link List}），返回修改后的对象。
     *
     * @param document 原始文档（{@link Map} / {@link List} 等可被 JSON Patch 修改的结构）
     * @param patch 补丁（操作的 {@link List}）
     * @return 应用补丁后的文档
     */
    public static Object apply(Object document, Object patch) {
        if (!(patch instanceof List)) {
            throw new JSONPatchException("patch must be a JSON array of operations");
        }
        return apply(document, (List<?>) patch);
    }

    /**
     * 对已解析的文档对象施加一组操作，返回修改后的对象（原地修改 {@code document}）。
     *
     * @param document 原始文档
     * @param operations 操作列表
     * @return 应用补丁后的文档
     */
    public static Object apply(Object document, List<?> operations) {
        if (operations == null) {
            throw new JSONPatchException("patch must not be null");
        }
        Root root = new Root();
        root.value = document;
        for (Object op : operations) {
            applyOne(root, op);
        }
        return root.value;
    }

    private static void applyOne(Root root, Object op) {
        if (!(op instanceof Map)) {
            throw new JSONPatchException("each operation must be an object");
        }
        Map<?, ?> o = (Map<?, ?>) op;
        Object opName = o.get("op");
        if (!(opName instanceof String)) {
            throw new JSONPatchException("operation missing string field 'op'");
        }
        Object pathObj = o.get("path");
        if (!(pathObj instanceof String)) {
            throw new JSONPatchException("operation missing string field 'path'");
        }
        List<String> path = parsePointer((String) pathObj);
        switch ((String) opName) {
            case "add":
                addOp(root, path, requireValue(o));
                break;
            case "remove":
                removeOp(root, path);
                break;
            case "replace":
                replaceOp(root, path, requireValue(o));
                break;
            case "test":
                testOp(root, path, requireValue(o));
                break;
            case "move":
                moveOp(root, path, requireFrom(o));
                break;
            case "copy":
                copyOp(root, path, requireFrom(o));
                break;
            default:
                throw new JSONPatchException("unknown operation: " + opName);
        }
    }

    private static Object requireValue(Map<?, ?> op) {
        if (!op.containsKey("value")) {
            throw new JSONPatchException("operation '" + op.get("op") + "' requires field 'value'");
        }
        return op.get("value");
    }

    private static List<String> requireFrom(Map<?, ?> op) {
        Object from = op.get("from");
        if (!(from instanceof String)) {
            throw new JSONPatchException("operation '" + op.get("op") + "' requires string field 'from'");
        }
        return parsePointer((String) from);
    }

    private static void addOp(Root root, List<String> tokens, Object value) {
        if (tokens.isEmpty()) {
            root.value = value;
            return;
        }
        Object parent = parentOf(root, tokens);
        String last = tokens.get(tokens.size() - 1);
        if (parent instanceof List) {
            List<Object> list = (List<Object>) parent;
            if ("-".equals(last)) {
                list.add(value);
            } else {
                int idx = parseArrayIndexForAdd(last, list.size());
                list.add(idx, value);
            }
        } else if (parent instanceof Map) {
            ((Map<String, Object>) parent).put(last, value);
        } else {
            throw new JSONPatchException("cannot add into scalar at token '" + last + "'");
        }
    }

    private static void removeOp(Root root, List<String> tokens) {
        if (tokens.isEmpty()) {
            throw new JSONPatchException("cannot remove the document root");
        }
        Object parent = parentOf(root, tokens);
        String last = tokens.get(tokens.size() - 1);
        if (parent instanceof List) {
            List<Object> list = (List<Object>) parent;
            list.remove(parseArrayIndex(last, list.size()));
        } else if (parent instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) parent;
            if (!map.containsKey(last)) {
                throw new JSONPatchException("cannot remove: key not found: '" + last + "'");
            }
            map.remove(last);
        } else {
            throw new JSONPatchException("cannot remove from scalar at token '" + last + "'");
        }
    }

    private static void replaceOp(Root root, List<String> tokens, Object value) {
        if (tokens.isEmpty()) {
            root.value = value;
            return;
        }
        Object parent = parentOf(root, tokens);
        String last = tokens.get(tokens.size() - 1);
        if (parent instanceof List) {
            List<Object> list = (List<Object>) parent;
            list.set(parseArrayIndex(last, list.size()), value);
        } else if (parent instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) parent;
            if (!map.containsKey(last)) {
                throw new JSONPatchException("cannot replace: key not found: '" + last + "'");
            }
            map.put(last, value);
        } else {
            throw new JSONPatchException("cannot replace scalar at token '" + last + "'");
        }
    }

    private static void testOp(Root root, List<String> tokens, Object expected) {
        Object actual = getAt(root, tokens);
        if (!deepEquals(actual, expected)) {
            throw new JSONPatchException("test failed: value at path does not match");
        }
    }

    private static void moveOp(Root root, List<String> path, List<String> from) {
        Object value = getAt(root, from);
        removeOp(root, from);
        addOp(root, path, value);
    }

    private static void copyOp(Root root, List<String> path, List<String> from) {
        Object value = getAt(root, from);
        addOp(root, path, deepCopy(value));
    }

    private static Object parentOf(Root root, List<String> tokens) {
        if (tokens.isEmpty()) {
            throw new JSONPatchException("empty path has no parent");
        }
        Object current = root.value;
        for (int i = 0; i < tokens.size() - 1; i++) {
            current = stepInto(current, tokens.get(i));
        }
        return current;
    }

    private static Object getAt(Root root, List<String> tokens) {
        Object current = root.value;
        for (String token : tokens) {
            current = stepInto(current, token);
        }
        return current;
    }

    private static Object stepInto(Object current, String token) {
        if (current instanceof List) {
            return ((List<?>) current).get(parseArrayIndex(token, ((List<?>) current).size()));
        }
        if (current instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) current;
            if (!map.containsKey(token)) {
                throw new JSONPatchException("path segment not found: '" + token + "'");
            }
            return map.get(token);
        }
        throw new JSONPatchException("cannot navigate into scalar at token '" + token + "'");
    }

    private static int parseArrayIndex(String token, int size) {
        if ("-".equals(token)) {
            throw new JSONPatchException("'-' is not a valid index for lookup");
        }
        int idx = parseIndexRaw(token);
        if (idx < 0 || idx >= size) {
            throw new JSONPatchException("array index out of range: " + token + " (size=" + size + ")");
        }
        return idx;
    }

    private static int parseArrayIndexForAdd(String token, int size) {
        int idx = parseIndexRaw(token);
        if (idx < 0 || idx > size) {
            throw new JSONPatchException("array index out of range for add: " + token + " (size=" + size + ")");
        }
        return idx;
    }

    private static int parseIndexRaw(String token) {
        // RFC 6901 的 array-index 语法要求：至少一位数字、无前导零（"0" 本身除外）、不允许 '+'
        if (!isArrayIndex(token)) {
            throw new JSONPatchException("invalid array index: '" + token + "'");
        }
        int idx;
        try {
            idx = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new JSONPatchException("invalid array index: '" + token + "'");
        }
        return idx;
    }

    /**
     * 按 RFC 6901 的 {@code array-index} 产生式校验：{@code 0} / 无前导零的十进制数字串。
     *
     * @param token 路径记号
     * @return 合法数组索引返回 {@code true}
     */
    private static boolean isArrayIndex(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if ("0".equals(token)) {
            return true;
        }
        if (token.charAt(0) == '0' || token.charAt(0) == '+') {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<String> parsePointer(String path) {
        if (path == null) {
            throw new JSONPatchException("path must not be null");
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            throw new JSONPatchException("invalid JSON Pointer (must start with '/'): " + path);
        }
        List<String> tokens = new ArrayList<String>();
        if (path.isEmpty()) {
            return tokens;
        }
        String[] parts = path.split("/", -1);
        for (int i = 1; i < parts.length; i++) {
            tokens.add(unescape(parts[i]));
        }
        return tokens;
    }

    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private static Object deepCopy(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Object> copy = new ArrayList<Object>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : ma.entrySet()) {
                if (!mb.containsKey(entry.getKey())) {
                    return false;
                }
                if (!deepEquals(entry.getValue(), mb.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a;
            List<?> lb = (List<?>) b;
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!deepEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return numEquals((Number) a, (Number) b);
        }
        return a.equals(b);
    }

    private static boolean numEquals(Number a, Number b) {
        // NaN / Infinity 无法转 BigDecimal，退回 double 语义比较
        if (Double.isNaN(a.doubleValue()) || Double.isInfinite(a.doubleValue())
                || Double.isNaN(b.doubleValue()) || Double.isInfinite(b.doubleValue())) {
            return a.doubleValue() == b.doubleValue();
        }
        // 统一走 BigDecimal 精确比较：doubleValue() 在 >2^53 的整数上会截断，
        // 造成雪花 ID 级别大数假相等，test 操作会误放行
        return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        if (n instanceof BigInteger) {
            // 不能经 longValue() 中转，超 long 范围会截断
            return new BigDecimal((BigInteger) n);
        }
        if (n instanceof Double || n instanceof Float) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.valueOf(n.longValue());
    }

    private static final class Root {
        Object value;
    }
}
