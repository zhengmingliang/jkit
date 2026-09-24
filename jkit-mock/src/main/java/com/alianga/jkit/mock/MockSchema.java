package com.alianga.jkit.mock;

import com.alianga.jkit.json.JSON;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由 JSON Schema 反向生成样例数据，是 {@link MockValid#toJsonSchema(String)} 的逆向能力。
 *
 * <p>支持的关键字：</p>
 * <ul>
 *   <li>结构：{@code type}（含 {@code ["string","null"]} 这类多类型）、{@code properties}、
 *       {@code required}、{@code items}（含元组写法）、{@code additionalProperties}</li>
 *   <li>取值：{@code enum}、{@code const}、{@code default}（可选）</li>
 *   <li>组合：{@code allOf}（合并）、{@code oneOf} / {@code anyOf}（随机取一个）</li>
 *   <li>引用：{@code $ref} 指向 {@code #/definitions/*}、{@code #/$defs/*}、
 *       {@code #/components/schemas/*}，深度超限即停止，避免循环引用死递归</li>
 *   <li>字符串：{@code pattern}（走 {@link MockRegex} 反向生成）、{@code format}
 *       （email / date-time / uri / ipv4 / uuid 等）、{@code minLength} / {@code maxLength}</li>
 *   <li>数值：{@code minimum} / {@code maximum} / {@code exclusiveMinimum} /
 *       {@code exclusiveMaximum} / {@code multipleOf}</li>
 * </ul>
 *
 * <p>属性名还会做语义推断：{@code name} 生成中文姓名、{@code email} 生成邮箱、
 * {@code phone} 生成手机号、{@code createdAt} 生成时间、{@code id} 生成 GUID 等，
 * 让样例数据更接近真实业务数据。</p>
 *
 * <pre>{@code
 * String schema = "{\"type\":\"object\",\"properties\":{"
 *         + "\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\",\"minimum\":18,\"maximum\":60}}}";
 * Object data = MockSchema.mock(schema);            // {name=张三, age=32}
 * List<Object> many = new MockSchema().mockMany(schema, 10);
 * }</pre>
 *
 * @author 郑明亮
 */
public class MockSchema {
    private final MockRandom random;

    /**
     * 仅指定单边界（只有 minimum 或只有 maximum）时，用来推导另一侧的默认跨度。
     * 避免「只有 minimum=100」退化成恒等于 100 的情况。
     */
    private static final double DEFAULT_NUM_SPAN = 1000;

    /**
     * 默认构造。
     */
    public MockSchema() {
        this(new MockRandom());
    }

    /**
     * 指定随机源（传入带种子的 {@link MockRandom} 即可复现同一批样例）。
     *
     * @param random 随机源
     */
    public MockSchema(MockRandom random) {
        this.random = random == null ? new MockRandom() : random;
    }

    /**
     * 按 JSON Schema 生成一条样例数据（静态便捷方法）。
     *
     * @param schemaJson JSON Schema 文本
     * @return 生成的数据（Map / List / 基本类型）
     */
    public static Object mock(String schemaJson) {
        return new MockSchema().generate(schemaJson);
    }

    /**
     * 按 JSON Schema 生成一条样例数据（静态便捷方法）。
     *
     * @param schemaJson JSON Schema 文本
     * @param options    生成选项
     * @return 生成的数据
     */
    public static Object mock(String schemaJson, MockSchemaOptions options) {
        return new MockSchema().generate(schemaJson, options);
    }

    /**
     * 按 JSON Schema 生成一条样例并序列化为缩进 JSON。
     *
     * @param schemaJson JSON Schema 文本
     * @return JSON 字符串
     */
    public static String mockJson(String schemaJson) {
        return MockDataFormatter.prettyJson(mock(schemaJson));
    }

    /**
     * 按 JSON Schema 生成一条样例数据。
     *
     * @param schemaJson JSON Schema 文本
     * @return 生成的数据
     */
    public Object generate(String schemaJson) {
        return generate(schemaJson, MockSchemaOptions.defaults());
    }

    /**
     * 按 JSON Schema 生成一条样例数据。
     *
     * @param schemaJson JSON Schema 文本
     * @param options    生成选项
     * @return 生成的数据
     */
    public Object generate(String schemaJson, MockSchemaOptions options) {
        MockSchemaOptions opt = options == null ? MockSchemaOptions.defaults() : options;
        Object root = parse(schemaJson);
        return gen(root, null, root, 0, opt);
    }

    /**
     * 按 JSON Schema 生成多条样例数据。
     *
     * <p>每条都是独立随机生成的一份完整样例；条数较大时请自行控制，
     * 超大批次建议改用模板模式的流式生成。</p>
     *
     * @param schemaJson JSON Schema 文本
     * @param count      条数
     * @return 样例列表
     */
    public List<Object> mockMany(String schemaJson, long count) {
        return mockMany(schemaJson, count, MockSchemaOptions.defaults());
    }

    /**
     * 按 JSON Schema 生成多条样例数据。
     *
     * @param schemaJson JSON Schema 文本
     * @param count      条数
     * @param options    生成选项
     * @return 样例列表
     */
    public List<Object> mockMany(String schemaJson, long count, MockSchemaOptions options) {
        MockSchemaOptions opt = options == null ? MockSchemaOptions.defaults() : options;
        Object root = parse(schemaJson);
        long total = count <= 0 ? 1 : count;
        List<Object> out = new ArrayList<Object>(total > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) total);
        for (long i = 0; i < total; i++) {
            out.add(gen(root, null, root, 0, opt));
        }
        return out;
    }

    /**
     * 当前随机源，便于调用方复用。
     *
     * @return 随机源
     */
    public MockRandom getRandom() {
        return random;
    }

    /**
     * 常用 JSON Schema 示例，供 UI 下拉直接选用。
     *
     * @return 示例名到 Schema 文本的映射
     */
    public static Map<String, String> samples() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("用户", "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"id\": { \"type\": \"integer\", \"minimum\": 1 },\n"
                + "    \"name\": { \"type\": \"string\" },\n"
                + "    \"gender\": { \"type\": \"string\", \"enum\": [\"男\", \"女\"] },\n"
                + "    \"age\": { \"type\": \"integer\", \"minimum\": 18, \"maximum\": 60 },\n"
                + "    \"email\": { \"type\": \"string\", \"format\": \"email\" },\n"
                + "    \"phone\": { \"type\": \"string\", \"pattern\": \"^1[3-9]\\\\d{9}$\" },\n"
                + "    \"vip\": { \"type\": \"boolean\" },\n"
                + "    \"balance\": { \"type\": \"number\", \"minimum\": 0, \"maximum\": 9999 },\n"
                + "    \"city\": { \"type\": \"string\" },\n"
                + "    \"createdAt\": { \"type\": \"string\", \"format\": \"date-time\" }\n"
                + "  },\n"
                + "  \"required\": [\"id\", \"name\"]\n"
                + "}");
        map.put("订单", "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"orderNo\": { \"type\": \"string\" },\n"
                + "    \"status\": { \"type\": \"string\", \"enum\": [\"CREATED\", \"PAID\", \"SHIPPED\", \"DONE\"] },\n"
                + "    \"amount\": { \"type\": \"number\", \"minimum\": 1, \"maximum\": 9999 },\n"
                + "    \"paid\": { \"type\": \"boolean\" },\n"
                + "    \"items\": {\n"
                + "      \"type\": \"array\", \"minItems\": 1, \"maxItems\": 3,\n"
                + "      \"items\": { \"$ref\": \"#/definitions/Item\" }\n"
                + "    },\n"
                + "    \"address\": {\n"
                + "      \"type\": \"object\",\n"
                + "      \"properties\": {\n"
                + "        \"province\": { \"type\": \"string\" },\n"
                + "        \"city\": { \"type\": \"string\" },\n"
                + "        \"zip\": { \"type\": \"string\" }\n"
                + "      },\n"
                + "      \"required\": [\"city\"]\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"orderNo\", \"status\", \"amount\"],\n"
                + "  \"definitions\": {\n"
                + "    \"Item\": {\n"
                + "      \"type\": \"object\",\n"
                + "      \"properties\": {\n"
                + "        \"sku\": { \"type\": \"string\" },\n"
                + "        \"name\": { \"type\": \"string\" },\n"
                + "        \"price\": { \"type\": \"number\", \"minimum\": 1, \"maximum\": 999 },\n"
                + "        \"qty\": { \"type\": \"integer\", \"minimum\": 1, \"maximum\": 5 }\n"
                + "      },\n"
                + "      \"required\": [\"sku\", \"name\", \"price\", \"qty\"]\n"
                + "    }\n"
                + "  }\n"
                + "}");
        map.put("商品", "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"skuId\": { \"type\": \"string\", \"format\": \"uuid\" },\n"
                + "    \"title\": { \"type\": \"string\" },\n"
                + "    \"category\": { \"type\": \"string\", \"enum\": [\"手机\", \"电脑\", \"家电\", \"图书\"] },\n"
                + "    \"price\": { \"type\": \"number\", \"minimum\": 9, \"maximum\": 9999 },\n"
                + "    \"stock\": { \"type\": \"integer\", \"minimum\": 0, \"maximum\": 999 },\n"
                + "    \"tags\": { \"type\": \"array\", \"items\": { \"type\": \"string\" },\n"
                + "               \"minItems\": 2, \"maxItems\": 4 },\n"
                + "    \"onSale\": { \"type\": \"boolean\" }\n"
                + "  },\n"
                + "  \"required\": [\"skuId\", \"title\", \"price\"]\n"
                + "}");
        map.put("接口响应", "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"code\": { \"type\": \"integer\", \"enum\": [0, 200, 400, 500] },\n"
                + "    \"message\": { \"type\": \"string\" },\n"
                + "    \"data\": {\n"
                + "      \"type\": \"array\", \"minItems\": 3, \"maxItems\": 5,\n"
                + "      \"items\": {\n"
                + "        \"type\": \"object\",\n"
                + "        \"properties\": {\n"
                + "          \"id\": { \"type\": \"integer\" },\n"
                + "          \"title\": { \"type\": \"string\" },\n"
                + "          \"author\": { \"type\": \"string\" },\n"
                + "          \"views\": { \"type\": \"integer\", \"minimum\": 0, \"maximum\": 9999 }\n"
                + "        },\n"
                + "        \"required\": [\"id\", \"title\"]\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"code\", \"message\", \"data\"]\n"
                + "}");
        return Collections.unmodifiableMap(map);
    }

    /**
     * 解析 Schema 文本；直接解析失败时退一步做单引号规范化再试，
     * 兼容从文档里复制出来的非严格 JSON。
     *
     * @param text Schema 文本
     * @return 解析结果
     */
    private static Object parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("schema is empty");
        }
        try {
            return JSON.parse(trimmed);
        } catch (Exception e) {
            return JSON.parse(MockJs.normalizeQuotes(trimmed));
        }
    }

    /**
     * 按 Schema 生成一个值。
     *
     * @param schema Schema 节点（null / true 视为「无约束」）
     * @param name   属性名，用于语义推断
     * @param root   Schema 根文档，供 {@code $ref} 解析
     * @param depth  当前深度
     * @param opt    生成选项
     * @return 生成的值
     */
    @SuppressWarnings("unchecked")
    private Object gen(Object schema, String name, Object root, int depth, MockSchemaOptions opt) {
        if (depth > opt.getMaxDepth()) {
            return null;
        }
        Object node = schema;
        if (node == null) {
            node = new LinkedHashMap<String, Object>();
        } else if (node instanceof Boolean) {
            if (!((Boolean) node)) {
                return null;
            }
            node = new LinkedHashMap<String, Object>();
        }
        if (!(node instanceof Map)) {
            return node;
        }
        Map<String, Object> s = (Map<String, Object>) node;

        Object ref = s.get("$ref");
        if (ref instanceof String) {
            Object target = resolveRef((String) ref, root);
            if (target != null && target != node) {
                return gen(target, name, root, depth + 1, opt);
            }
            return null;
        }
        if (s.containsKey("const")) {
            return s.get("const");
        }
        Object enumValues = s.get("enum");
        if (enumValues instanceof List && !((List<?>) enumValues).isEmpty()) {
            List<?> values = (List<?>) enumValues;
            return values.get((int) random.natural(0, values.size() - 1));
        }
        if (opt.isUseDefaults() && s.containsKey("default")) {
            return s.get("default");
        }
        Object allOf = s.get("allOf");
        if (allOf instanceof List && !((List<?>) allOf).isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            for (Object sub : (List<?>) allOf) {
                if (sub instanceof Map) {
                    mergeInto(merged, (Map<String, Object>) sub);
                }
            }
            mergeInto(merged, s);
            merged.remove("allOf");
            return gen(merged, name, root, depth + 1, opt);
        }
        Object branch = s.get("oneOf") != null ? s.get("oneOf") : s.get("anyOf");
        if (branch instanceof List && !((List<?>) branch).isEmpty()) {
            List<?> branches = (List<?>) branch;
            Object picked = branches.get((int) random.natural(0, branches.size() - 1));
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            if (picked instanceof Map) {
                mergeInto(merged, (Map<String, Object>) picked);
            }
            Map<String, Object> siblings = new LinkedHashMap<String, Object>(s);
            siblings.remove("oneOf");
            siblings.remove("anyOf");
            mergeInto(merged, siblings);
            return gen(merged, name, root, depth + 1, opt);
        }

        String type = typeOf(s, name);
        if ("object".equals(type)) {
            return genObject(s, root, depth, opt);
        }
        if ("array".equals(type)) {
            return genArray(s, name, root, depth, opt);
        }
        if ("boolean".equals(type)) {
            return random.bool();
        }
        if ("null".equals(type)) {
            return null;
        }
        if ("integer".equals(type)) {
            return genNumber(s, true);
        }
        if ("number".equals(type)) {
            return genNumber(s, false);
        }
        return genString(s, name);
    }

    /**
     * 推断 Schema 的节点类型：显式 {@code type} 优先，其次按 {@code properties} /
     * {@code items} 推断，最后按属性名猜（age / count / price 等视为数值）。
     *
     * @param schema Schema 节点
     * @param name   属性名
     * @return 类型名
     */
    private String typeOf(Map<String, Object> schema, String name) {
        Object type = schema.get("type");
        if (type instanceof String) {
            return (String) type;
        }
        if (type instanceof List) {
            List<String> candidates = new ArrayList<String>();
            for (Object item : (List<?>) type) {
                if (item instanceof String && !"null".equals(item)) {
                    candidates.add((String) item);
                }
            }
            if (candidates.isEmpty()) {
                return "null";
            }
            return candidates.get((int) random.natural(0, candidates.size() - 1));
        }
        if (schema.containsKey("properties")) {
            return "object";
        }
        if (schema.containsKey("items")) {
            return "array";
        }
        String lower = name == null ? "" : name.toLowerCase();
        if (looksBoolean(lower)) {
            return "boolean";
        }
        if (looksNumeric(lower)) {
            return "integer";
        }
        return "string";
    }

    @SuppressWarnings("unchecked")
    private Object genObject(Map<String, Object> schema, Object root, int depth,
                             MockSchemaOptions opt) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<String> required = strList(schema.get("required"));
        Object properties = schema.get("properties");
        if (properties instanceof Map) {
            for (Map.Entry<String, Object> entry
                    : ((Map<String, Object>) properties).entrySet()) {
                String key = entry.getKey();
                if (!required.contains(key) && !opt.isIncludeOptional()) {
                    continue;
                }
                out.put(key, gen(entry.getValue(), key, root, depth + 1, opt));
            }
        }
        for (String key : required) {
            if (!out.containsKey(key)) {
                out.put(key, gen(null, key, root, depth + 1, opt));
            }
        }
        Object additional = schema.get("additionalProperties");
        if (out.isEmpty() && additional instanceof Map) {
            int count = (int) random.natural(opt.getArrayMinItems(),
                    Math.max(opt.getArrayMinItems(), opt.getArrayMaxItems()));
            for (int i = 1; i <= count; i++) {
                String key = "key" + i;
                out.put(key, gen(additional, key, root, depth + 1, opt));
            }
        }
        return out;
    }

    private Object genArray(Map<String, Object> schema, String name, Object root, int depth,
                            MockSchemaOptions opt) {
        List<Object> out = new ArrayList<Object>();
        Object items = schema.get("items");
        if (items instanceof List) {
            for (Object sub : (List<?>) items) {
                out.add(gen(sub, name, root, depth + 1, opt));
            }
            return out;
        }
        int count = (int) random.natural(arrayMin(schema, opt), arrayMax(schema, opt));
        for (int i = 0; i < count; i++) {
            out.add(gen(items, name, root, depth + 1, opt));
        }
        return out;
    }

    private int arrayMin(Map<String, Object> schema, MockSchemaOptions opt) {
        Double value = numOf(schema.get("minItems"));
        if (value == null) {
            return opt.getArrayMinItems();
        }
        return Math.max(0, (int) Math.round(value));
    }

    private int arrayMax(Map<String, Object> schema, MockSchemaOptions opt) {
        Double value = numOf(schema.get("maxItems"));
        int max = value == null ? opt.getArrayMaxItems() : (int) Math.round(value);
        int min = arrayMin(schema, opt);
        return Math.max(min, max);
    }

    private Object genString(Map<String, Object> schema, String name) {
        Object pattern = schema.get("pattern");
        if (pattern instanceof String && !((String) pattern).isEmpty()) {
            try {
                String generated = MockRegex.generate((String) pattern, random);
                if (generated != null) {
                    return fitLength(generated, schema);
                }
            } catch (Exception ignore) {
                // 正则过于复杂时退回按 format / 名称生成
            }
        }
        Object format = schema.get("format");
        if (format instanceof String) {
            String value = byFormat((String) format);
            if (value != null) {
                return fitLength(value, schema);
            }
        }
        String byName = byName(name);
        if (byName != null) {
            return fitLength(byName, schema);
        }
        return fitLength(randomString(schema), schema);
    }

    /**
     * 按 {@code minLength} / {@code maxLength} 生成定长随机串。
     *
     * @param schema Schema 节点
     * @return 随机串
     */
    private String randomString(Map<String, Object> schema) {
        Double min = numOf(schema.get("minLength"));
        Double max = numOf(schema.get("maxLength"));
        if (min == null && max == null) {
            return random.string((int) random.natural(5, 10));
        }
        int lo = min == null ? -1 : Math.max(0, (int) Math.round(min));
        int hi = max == null ? -1 : Math.max(0, (int) Math.round(max));
        if (lo < 0) {
            return random.string((int) random.natural(Math.max(0, hi - 5), hi));
        }
        if (hi < 0) {
            return random.string((int) random.natural(lo, lo + 5));
        }
        return random.string((int) random.natural(lo, hi));
    }

    /**
     * 把生成结果收敛到 {@code minLength} / {@code maxLength} 之内：
     * 超长截断、不足则补随机字符。
     *
     * @param value  已生成的值
     * @param schema Schema 节点
     * @return 收敛后的字符串
     */
    private String fitLength(String value, Map<String, Object> schema) {
        String text = value == null ? "" : value;
        Double min = numOf(schema.get("minLength"));
        Double max = numOf(schema.get("maxLength"));
        if (max != null && max >= 0 && text.length() > max) {
            text = text.substring(0, (int) Math.round(max));
        }
        if (min != null && text.length() < min) {
            StringBuilder sb = new StringBuilder(text);
            while (sb.length() < min) {
                sb.append(random.character("lower"));
            }
            text = sb.toString();
        }
        return text;
    }

    /**
     * 按 JSON Schema 的 {@code format} 取值。
     *
     * @param format format 值
     * @return 生成结果，未识别时返回 null
     */
    private String byFormat(String format) {
        String key = format.toLowerCase();
        if ("date-time".equals(key)) {
            return random.datetime();
        }
        if ("date".equals(key)) {
            return random.date();
        }
        if ("time".equals(key)) {
            return random.time();
        }
        if ("email".equals(key) || "idn-email".equals(key)) {
            return random.email();
        }
        if ("uri".equals(key) || "url".equals(key) || "uri-reference".equals(key)
                || "uri-template".equals(key)) {
            return random.url();
        }
        if ("hostname".equals(key) || "idn-hostname".equals(key)) {
            return random.domain();
        }
        if ("ipv4".equals(key) || "ipv6".equals(key)) {
            return random.ip();
        }
        if ("uuid".equals(key)) {
            return random.uuid();
        }
        if ("password".equals(key)) {
            return random.password();
        }
        if ("color".equals(key)) {
            return random.color();
        }
        if ("regex".equals(key)) {
            return random.word();
        }
        if ("byte".equals(key) || "binary".equals(key)) {
            return random.string(8);
        }
        return null;
    }

    /**
     * 按属性名做语义推断，让样例更贴近真实业务数据。
     *
     * @param name 属性名
     * @return 生成结果，无法推断时返回 null
     */
    private String byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String n = name.toLowerCase();
        if (n.contains("email")) {
            return random.email();
        }
        if (n.contains("phone") || n.contains("mobile") || n.contains("tel")) {
            return random.phone();
        }
        if (n.contains("url") || n.contains("link") || n.contains("website")
                || n.contains("href")) {
            return random.url();
        }
        if (n.contains("zip") || n.contains("postcode") || n.contains("postal")) {
            return random.zip();
        }
        if (isIpName(n)) {
            return random.ip();
        }
        if (n.contains("avatar") || n.contains("image") || n.contains("img")
                || n.contains("cover") || n.contains("photo") || n.contains("icon")) {
            return random.image();
        }
        if (n.contains("city")) {
            return random.city();
        }
        if (n.contains("province")) {
            return random.province();
        }
        if (n.contains("county") || n.contains("district") || n.contains("address")) {
            return random.county();
        }
        if (n.contains("company") || n.contains("org")) {
            return random.company();
        }
        if (n.contains("department") || n.contains("dept")) {
            return random.department();
        }
        if (n.contains("position") || n.contains("job")) {
            return random.position();
        }
        if (n.contains("username") || n.contains("account")) {
            return random.string("lower", 6, 10);
        }
        if (n.contains("nickname") || n.contains("nick")) {
            return random.cname();
        }
        if (n.contains("password") || n.contains("pwd")) {
            return random.password();
        }
        if (n.contains("token")) {
            return random.token();
        }
        if (n.contains("bankcard")) {
            return random.bankCard();
        }
        if (n.contains("creditcard")) {
            return random.creditCard();
        }
        if (n.contains("useragent") || n.contains("user_agent") || "ua".equals(n)) {
            return random.userAgent();
        }
        if (n.contains("filename") || n.endsWith("file")) {
            return random.fileName();
        }
        if (n.contains("create") || n.contains("update") || n.contains("birth")
                || n.contains("expire")) {
            return random.datetime();
        }
        if (n.contains("time") || n.contains("date")) {
            return random.datetime();
        }
        if (n.contains("gender") || n.contains("sex")) {
            return random.gender();
        }
        if (n.contains("title")) {
            return random.ctitle();
        }
        if (n.contains("desc") || n.contains("remark") || n.contains("summary")
                || n.contains("content") || n.contains("message") || n.contains("intro")) {
            return random.csentence();
        }
        if (n.contains("price") || n.contains("amount") || n.contains("money")
                || n.contains("balance") || n.contains("salary")) {
            return String.valueOf(random.floatValue(1, 9999, 2, 2));
        }
        if (n.contains("status") || n.contains("state") || n.contains("level")
                || n.contains("category") || n.contains("tag")) {
            return random.word();
        }
        if (n.contains("id")) {
            return random.guid();
        }
        if (n.contains("name")) {
            return random.cname();
        }
        if (n.contains("color")) {
            return random.color();
        }
        if (n.contains("version")) {
            return random.natural(1, 9) + "." + random.natural(0, 9) + "." + random.natural(0, 9);
        }
        return null;
    }

    /**
     * 属性名是否像 IP：{@code ip}、{@code clientIp}、{@code ipAddress} 等。
     *
     * <p>这里刻意不用 {@code contains("ip")}：{@code description}、{@code zip} 这类名字
     * 也会被误命中。</p>
     *
     * @param n 小写属性名
     * @return 是否像 IP
     */
    private static boolean isIpName(String n) {
        return "ip".equals(n) || n.endsWith("ip") || n.contains("ipaddr")
                || n.contains("ipaddress") || n.contains("ip_address");
    }

    /**
     * 按 {@code minimum} / {@code maximum} / {@code exclusiveMinimum} /
     * {@code exclusiveMaximum} / {@code multipleOf} 生成数值。
     *
     * @param schema     Schema 节点
     * @param isInteger  是否整数
     * @return 数值（整数为 Long，小数为 Double）
     */
    private Object genNumber(Map<String, Object> schema, boolean isInteger) {
        double lo = 0;
        double hi = 100;
        Double minimum = numOf(schema.get("minimum"));
        Double maximum = numOf(schema.get("maximum"));
        boolean hasMin = minimum != null;
        boolean hasMax = maximum != null;
        if (hasMin) {
            lo = minimum;
        }
        if (hasMax) {
            hi = maximum;
        }
        if (hasMin && !hasMax) {
            // 仅指定下界：向上推导一个合理的默认上界，避免结果恒等于下界
            hi = lo + DEFAULT_NUM_SPAN;
        } else if (!hasMin && hasMax) {
            // 仅指定上界：向下推导一个合理的默认下界（非负时从 0 起）
            lo = hi > 0 ? 0 : hi - DEFAULT_NUM_SPAN;
        }
        Double exMin = numOf(schema.get("exclusiveMinimum"));
        Double exMax = numOf(schema.get("exclusiveMaximum"));
        if (exMin != null) {
            lo = Math.max(lo, exMin + (isInteger ? 1 : 1e-9));
        }
        if (exMax != null) {
            hi = Math.min(hi, exMax - (isInteger ? 1 : 1e-9));
        }
        if (hi < lo) {
            hi = lo;
        }
        Double multipleOf = numOf(schema.get("multipleOf"));
        double step = multipleOf == null || multipleOf <= 0 ? 0 : multipleOf;
        if (isInteger) {
            long low = (long) Math.ceil(lo);
            long high = (long) Math.floor(hi);
            long value = low >= 0 ? random.natural(low, high) : random.integer(low, high);
            if (step >= 1) {
                long unit = Math.max(1, Math.round(step));
                value = (value / unit) * unit;
            }
            if (value < low) {
                value = low;
            }
            if (value > high) {
                value = high;
            }
            return value;
        }
        double value = random.floatValue((long) Math.floor(lo), (long) Math.floor(hi), 1, 2);
        if (value < lo) {
            value = lo;
        }
        if (value > hi) {
            value = hi;
        }
        if (step > 0) {
            value = Math.round(value / step) * step;
            if (value < lo) {
                value = lo;
            }
            if (value > hi) {
                value = hi;
            }
        }
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 解析 {@code $ref}：支持 {@code #/definitions/X}、{@code #/$defs/X}、
     * {@code #/components/schemas/X}；外部引用（非 {@code #} 开头）不支持。
     *
     * @param ref  引用串
     * @param root 根文档
     * @return 目标节点，找不到时返回 null
     */
    private static Object resolveRef(String ref, Object root) {
        if (ref == null || !ref.startsWith("#") || root == null) {
            return null;
        }
        Object current = root;
        String[] parts = ref.substring(1).split("/");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else if (current instanceof List) {
                Double index = numOf(part);
                if (index == null || index < 0 || index >= ((List<?>) current).size()) {
                    return null;
                }
                current = ((List<?>) current).get((int) Math.round(index));
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 合并两个 Schema 节点（用于 {@code allOf}）：{@code properties} 合并、
     * {@code required} 去重拼接，其余关键字后者覆盖前者。
     *
     * @param target 合并目标
     * @param src    来源
     */
    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String, Object> target, Map<String, Object> src) {
        for (Map.Entry<String, Object> entry : src.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("properties".equals(key) && target.get(key) instanceof Map
                    && value instanceof Map) {
                ((Map<String, Object>) target.get(key)).putAll(
                        (Map<String, Object>) deepCopy(value));
            } else if ("required".equals(key) && target.get(key) instanceof List
                    && value instanceof List) {
                List<Object> merged = new ArrayList<Object>((List<Object>) target.get(key));
                for (Object item : (List<?>) value) {
                    if (!merged.contains(item)) {
                        merged.add(item);
                    }
                }
                target.put(key, merged);
            } else {
                target.put(key, deepCopy(value));
            }
        }
    }

    /**
     * 深拷贝 Schema 片段，避免 {@code allOf} 合并时改动到原始解析结果。
     *
     * @param value 待拷贝的值
     * @return 拷贝结果
     */
    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                out.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                out.add(deepCopy(item));
            }
            return out;
        }
        return value;
    }

    /**
     * 把 {@code required} 转成字符串列表。
     *
     * @param value Schema 里的 required 值
     * @return 属性名列表
     */
    private static List<String> strList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    /**
     * 属性名是否像布尔：{@code isXxx} / {@code hasXxx} / {@code enabled} / {@code deleted} 等。
     *
     * @param n 小写属性名
     * @return 是否像布尔
     */
    private static boolean looksBoolean(String n) {
        return n.startsWith("is") || n.startsWith("has") || n.startsWith("can")
                || n.contains("enable") || n.contains("disable") || n.contains("deleted")
                || n.contains("visible") || n.endsWith("flag");
    }

    /**
     * 属性名是否像数值：{@code age} / {@code count} / {@code price} / {@code total} 等。
     *
     * @param n 小写属性名
     * @return 是否像数值
     */
    private static boolean looksNumeric(String n) {
        String[] hints = {"age", "count", "num", "total", "price", "amount", "money", "score",
                "qty", "quantity", "stock", "index", "level", "size", "views", "salary",
                "year", "month", "day", "hour", "minute", "second", "port", "weight",
                "height", "width", "distance", "duration", "percent", "rate"};
        for (String hint : hints) {
            if (n.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取数值型关键字，字符串数字也兼容。
     *
     * @param value Schema 里的值
     * @return 数值，非数值时返回 null
     */
    private static Double numOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.valueOf((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
