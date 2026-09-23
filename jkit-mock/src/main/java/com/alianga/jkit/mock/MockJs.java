package com.alianga.jkit.mock;

import com.alianga.jkit.json.JSON;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock.js 数据模板引擎：解析「数据模板定义 DTD」与「数据占位符定义 DPD」，
 * 生成最终数据。
 *
 * <p><b>数据模板定义</b>（属性名 + 生成规则）：</p>
 * <ul>
 *   <li>{@code 'name|count': value} —— 重复 count 次</li>
 *   <li>{@code 'name|min-max': value} —— 重复 min~max 次</li>
 *   <li>{@code 'name|+step': number} —— 自增</li>
 *   <li>{@code 'name|+step': [...]} —— 顺序取数组元素</li>
 *   <li>{@code 'name|1': [...]} —— 随机取一个元素</li>
 *   <li>{@code 'name|min-max': {...}} —— 随机取 min~max 个属性</li>
 *   <li>{@code 'name|min-max.dmin-dmax': number} —— 带小数位</li>
 * </ul>
 *
 * <p><b>数据占位符定义</b>：{@code @cname}、{@code @integer(1,10)}、
 * {@code @pick(['a','b'])}、{@code @string('lower',5)}，大小写不敏感。<br>
 * 属性值写成 {@code "/^1[3-9]\\d{9}$/"} 形式时按正则反向生成。</p>
 *
 * <pre>{@code
 * String json = "{ 'list|1-3': [{ 'id|+1': 1, 'name': '@cname' }] }";
 * Object data = MockJs.mock(json);
 * }</pre>
 *
 * @author 郑明亮
 */
public class MockJs {
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\\\*@([^@#%&()?\\s]+)(?:\\((.*?)\\))?");
    private static final Pattern REGEX_VALUE = Pattern.compile("^/(.+)/[gimsuy]*$");
    private static final Pattern REGEX_META = Pattern.compile("[\\\\\\[\\](){}+*?|^$]");

    /**
     * 重复次数达到该值、且开启流式时，数组改为惰性生成。
     */
    private static final int STREAM_REPEAT_THRESHOLD = 1000;

    private final MockRandom random;
    private final Map<Object, Integer> orderIndex = new IdentityHashMap<Object, Integer>();
    private boolean streaming;

    /**
     * 默认构造。
     */
    public MockJs() {
        this(new MockRandom());
    }

    /**
     * 指定随机源。
     *
     * @param random 随机源
     */
    public MockJs(MockRandom random) {
        this.random = random == null ? new MockRandom() : random;
    }

    /**
     * 直接生成数据（静态便捷方法）。
     *
     * @param template JSON 模板或纯占位符字符串
     * @return 生成的数据
     */
    public static Object mock(String template) {
        return new MockJs().mock((Object) template);
    }

    /**
     * 生成数据并序列化为 JSON 字符串（缩进两空格）。
     *
     * @param template JSON 模板或纯占位符字符串
     * @return JSON 字符串
     */
    public static String mockJson(String template) {
        return JSON.toJsonString(mock(template));
    }

    /**
     * 生成数据。
     *
     * @param template JSON 模板字符串、纯占位符字符串，或已解析的 Map / List
     * @return 生成的数据
     */
    public Object mock(Object template) {
        if (template instanceof String) {
            String text = ((String) template).trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                return gen(JSON.parse(normalizeQuotes(text)), "");
            }
            return gen(text, "");
        }
        return gen(template, "");
    }

    /**
     * 把单引号字符串统一成双引号，兼容 Mock.js 文档里常见的单引号写法。
     *
     * @param text 模板文本
     * @return 规范化后的文本
     */
    public static String normalizeQuotes(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inString) {
                if (c == '\'' || c == '"') {
                    inString = true;
                    quote = c;
                    out.append('"');
                } else {
                    out.append(c);
                }
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                out.append(c).append(text.charAt(i + 1));
                i++;
            } else if (c == quote) {
                inString = false;
                out.append('"');
            } else if (c == '"') {
                out.append("\\\"");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 格式化模板：把单引号统一成双引号，并对合法 JSON 做缩进美化。
     *
     * <p>如果模板不是合法 JSON（例如模板本身就是纯占位符字符串，或包含正则值），
     * 则原样返回文本。</p>
     *
     * @param text 模板文本
     * @return 格式化后的文本
     */
    public static String prettyTemplate(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String normalized = normalizeQuotes(trimmed);
        try {
            Object parsed = JSON.parse(normalized);
            return MockDataFormatter.prettyJson(parsed);
        } catch (Exception e) {
            return trimmed;
        }
    }

    /**
     * 生成数据并转成分行记录，便于导出 CSV / SQL / XML。
     *
     * @param template 模板
     * @return 记录列表；结果不是对象数组时退化成单条记录
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> mockRecords(String template) {
        return MockDataFormatter.toRows(mock(template));
    }

    /**
     * 按模板生成一个值。
     *
     * @param template 模板值
     * @param key      属性名（含生成规则）
     * @return 生成结果
     */
    @SuppressWarnings("unchecked")
    public Object gen(Object template, String key) {
        MockRule rule = MockRule.parse(key, random);
        if (template == null) {
            return null;
        }
        if (template instanceof Map) {
            return genObject((Map<String, Object>) template, rule);
        }
        if (template instanceof List) {
            return genArray((List<Object>) template, rule);
        }
        if (template instanceof String) {
            return genString((String) template, rule);
        }
        if (template instanceof Boolean) {
            return genBoolean((Boolean) template, rule);
        }
        if (template instanceof Number) {
            return genNumber((Number) template, rule);
        }
        return template;
    }

    @SuppressWarnings("unchecked")
    private Object genObject(Map<String, Object> template, MockRule rule) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<String> keys = new ArrayList<String>(template.keySet());
        if (rule.getMin() != null) {
            keys = shuffleKeys(keys);
            int count = rule.getCount() == null ? 0 : rule.getCount();
            if (count < keys.size()) {
                keys = new ArrayList<String>(keys.subList(0, count));
            }
        }
        for (String key : keys) {
            Object value = template.get(key);
            MockRule inner = MockRule.parse(key, random);
            result.put(inner.getName(), gen(value, key));
            if (inner.getInc() != null && value instanceof Number) {
                template.put(key, ((Number) value).longValue() + inner.getInc());
            }
        }
        return result;
    }

    private List<String> shuffleKeys(List<String> keys) {
        List<Object> shuffled = random.shuffle(new ArrayList<Object>(keys));
        List<String> out = new ArrayList<String>();
        for (Object item : shuffled) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object genArray(List<Object> template, MockRule rule) {
        List<Object> result = new ArrayList<Object>();
        if (template.isEmpty()) {
            return result;
        }
        if (!rule.isRuled()) {
            for (int i = 0; i < template.size(); i++) {
                result.add(gen(template.get(i), String.valueOf(i)));
            }
            return result;
        }
        if (rule.getInc() != null) {
            Integer index = orderIndex.get(template);
            int at = index == null ? 0 : index;
            Object picked = gen(template.get(at % template.size()), String.valueOf(at));
            orderIndex.put(template, at + rule.getInc());
            return picked;
        }
        if (rule.getMin() != null && rule.getMin() == 1 && rule.getMax() == null) {
            List<Object> expanded = new ArrayList<Object>();
            for (int i = 0; i < template.size(); i++) {
                expanded.add(gen(template.get(i), String.valueOf(i)));
            }
            return random.pick(expanded);
        }
        int count = rule.getCount() == null ? 1 : rule.getCount();
        if (streaming && count >= STREAM_REPEAT_THRESHOLD) {
            return new MockRepeat(this, template, count);
        }
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < template.size(); j++) {
                result.add(gen(template.get(j), String.valueOf(j)));
            }
        }
        return result;
    }

    private Object genString(String template, MockRule rule) {
        String regex = asRegex(template);
        if (regex != null) {
            int count = rule.getCount() == null ? 1 : rule.getCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append(MockRegex.generate(regex, random));
            }
            return sb.toString();
        }
        if (template.isEmpty()) {
            return rule.getMin() != null ? random.string(rule.getCount()) : template;
        }
        String result;
        if (rule.getCount() == null) {
            result = template;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rule.getCount(); i++) {
                sb.append(template);
            }
            result = sb.toString();
        }
        return resolvePlaceholders(result);
    }

    private Object genNumber(Number template, MockRule rule) {
        if (rule.hasDecimal()) {
            String[] parts = String.valueOf(template).split("\\.");
            String integerPart = rule.hasRange() ? String.valueOf(rule.getCount()) : parts[0];
            String decimalPart = parts.length > 1 ? parts[1] : "";
            int dcount = rule.getDecimalCount();
            if (decimalPart.length() > dcount) {
                decimalPart = decimalPart.substring(0, dcount);
            }
            StringBuilder sb = new StringBuilder(decimalPart);
            while (sb.length() < dcount) {
                sb.append(random.character(sb.length() < dcount - 1 ? "number" : "123456789"));
            }
            if (sb.length() == 0) {
                return Double.parseDouble(integerPart);
            }
            if (rule.hasRange() && rule.getMax() != null
                    && Long.parseLong(integerPart) >= rule.getMax()) {
                // 整数部分已取到上界，小数必须归零，否则 999.25 会超出模板声明的范围
                sb.setLength(0);
                for (int i = 0; i < dcount; i++) {
                    sb.append('0');
                }
            }
            return Double.parseDouble(integerPart + "." + sb);
        }
        if (rule.hasRange() && rule.getInc() == null) {
            return rule.getCount();
        }
        return template;
    }

    private Object genBoolean(Boolean template, MockRule rule) {
        if (!rule.isRuled()) {
            return template;
        }
        int min = rule.hasRange() ? (int) Math.round(rule.getMinValue()) : 1;
        int max = rule.hasRange() ? (int) Math.round(rule.getMaxValue()) : min;
        if (min < 0) {
            min = 0;
        }
        if (max < min) {
            max = min;
        }
        return random.bool(min, max, template);
    }

    private String asRegex(String value) {
        if (value.length() < 3 || value.charAt(0) != '/' || !REGEX_VALUE.matcher(value).matches()) {
            return null;
        }
        String body = REGEX_VALUE.matcher(value).replaceAll("$1");
        return REGEX_META.matcher(body).find() ? body : null;
    }

    private Object resolvePlaceholders(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        List<int[]> spans = new ArrayList<int[]>();
        List<Object> values = new ArrayList<Object>();
        while (m.find()) {
            String matched = m.group();
            if (matched.startsWith("\\")) {
                continue;
            }
            spans.add(new int[]{m.start(), m.end()});
            values.add(evalPlaceholder(matched, m.group(1), m.group(2)));
        }
        if (spans.isEmpty()) {
            return text;
        }
        if (spans.size() == 1 && spans.get(0)[0] == 0 && spans.get(0)[1] == text.length()) {
            return values.get(0);
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < spans.size(); i++) {
            sb.append(text, cursor, spans.get(i)[0]);
            sb.append(stringOf(values.get(i)));
            cursor = spans.get(i)[1];
        }
        sb.append(text.substring(cursor));
        return sb.toString();
    }

    private Object evalPlaceholder(String matched, String name, String args) {
        try {
            return random.invoke(name, parseArgs(args).toArray());
        } catch (IllegalArgumentException e) {
            return matched;
        }
    }

    private static String stringOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<?>) value) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(stringOf(item));
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /**
     * 解析占位符参数串，支持字符串、数字、布尔、null 与数组。
     *
     * @param args 参数串，可能为 null
     * @return 参数列表
     */
    public static List<Object> parseArgs(String args) {
        List<Object> out = new ArrayList<Object>();
        if (args == null || args.trim().isEmpty()) {
            return out;
        }
        List<String> tokens = splitTopLevel(args);
        for (String token : tokens) {
            out.add(parseValue(token));
        }
        return out;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> tokens = new ArrayList<String>();
        int depth = 0;
        char quote = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                sb.append(c);
                continue;
            }
            if (c == '[' || c == '(' || c == '{') {
                depth++;
            } else if (c == ']' || c == ')' || c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString().trim());
        }
        return tokens;
    }

    private static Object parseValue(String token) {
        if (token.isEmpty()) {
            return "";
        }
        char first = token.charAt(0);
        if ((first == '\'' || first == '"') && token.length() >= 2
                && token.charAt(token.length() - 1) == first) {
            return token.substring(1, token.length() - 1);
        }
        if ("true".equals(token) || "false".equals(token)) {
            return Boolean.valueOf(token);
        }
        if ("null".equals(token)) {
            return null;
        }
        if (first == '[') {
            String inner = token.substring(1, token.length() - 1);
            List<Object> list = new ArrayList<Object>();
            for (String part : splitTopLevel(inner)) {
                list.add(parseValue(part));
            }
            return list;
        }
        try {
            return Long.valueOf(token);
        } catch (NumberFormatException e) {
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException ex) {
                return token;
            }
        }
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
     * 清空顺序取值（{@code 'name|+1': [...]}）的游标。
     */
    public void reset() {
        orderIndex.clear();
        random.resetIncrement();
    }

    /**
     * 是否开启流式生成。
     *
     * <p>开启后，重复次数达到 {@link #STREAM_REPEAT_THRESHOLD} 的数组不再一次性展开成
     * {@code List}，而是返回 {@link MockRepeat} 惰性生成，配合
     * {@link MockDataFormatter#formatTo(Appendable, Object, MockOutputFormat, MockSqlOptions)}
     * 可以边生成边输出，把内存占用从「随条数线性增长」降到常量级。</p>
     *
     * @return true 表示流式
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * 设置是否开启流式生成。
     *
     * @param streaming true 表示流式
     */
    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    /**
     * 常用模板示例，供 UI 下拉直接选用。
     *
     * @return 模板名到模板内容的映射
     */
    public static Map<String, String> samples() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("用户列表", "{\n  \"code\": 200,\n  \"message\": \"success\",\n"
                + "  \"data|5-10\": [{\n    \"id|+1\": 1,\n    \"name\": \"@cname\",\n"
                + "    \"gender|1\": [\"男\", \"女\"],\n    \"age|18-60\": 1,\n"
                + "    \"email\": \"@email\",\n    \"phone\": \"/^1[3-9]\\\\d{9}$/\",\n"
                + "    \"city\": \"@city(true)\",\n    \"avatar\": \"@image('100x100')\",\n"
                + "    \"balance|100-9999.2\": 1,\n    \"vip|1\": true,\n"
                + "    \"createdAt\": \"@datetime\"\n  }]\n}");
        map.put("分页响应", "{\n  \"total|100-500\": 1,\n  \"pageSize\": 10,\n"
                + "  \"list|10\": [{\n    \"id|+1\": 1,\n    \"title\": \"@ctitle\",\n"
                + "    \"summary\": \"@csentence\",\n    \"author\": \"@cname\",\n"
                + "    \"tags|1\": [\"Java\", \"MySQL\", \"Redis\", \"MQ\", \"K8s\"],\n"
                + "    \"views|0-9999\": 1\n  }]\n}");
        map.put("商品", "{\n  \"skuId\": \"@guid\",\n  \"name\": \"@ctitle\",\n"
                + "  \"price|9-999.2\": 1,\n  \"stock|0-999\": 1,\n"
                + "  \"category|1\": [\"手机\", \"电脑\", \"家电\", \"图书\"],\n"
                + "  \"onSale|1-9\": true,\n  \"cover\": \"@dataImage('300x300')\"\n}");
        map.put("接口日志", "{\n  \"traceId\": \"@guid\",\n  \"method|1\": [\"GET\", \"POST\", \"PUT\"],\n"
                + "  \"url\": \"@url\",\n  \"ip\": \"@ip\",\n  \"status|1\": [200, 400, 500],\n"
                + "  \"cost|1-999\": 1,\n  \"referer\": \"@url\",\n  \"time\": \"@now\"\n}");
        return Collections.unmodifiableMap(map);
    }
}
