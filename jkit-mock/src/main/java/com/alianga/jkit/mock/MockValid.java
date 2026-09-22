package com.alianga.jkit.mock;

import com.alianga.jkit.json.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板校验与 JSON Schema 推导，对应 Mock.js 的 {@code Mock.valid} 与 {@code Mock.toJSONSchema}。
 *
 * <pre>{@code
 * List<String> problems = MockValid.valid("{'id|+1':1,'name':'@cname'}", data);
 * Map<String, Object> schema = MockValid.toJsonSchema("{'list|1-10':[{'id|+1':1}]}");
 * }</pre>
 *
 * @author 郑明亮
 */
public final class MockValid {
    private MockValid() {
        throw new UnsupportedOperationException("MockValid");
    }

    /**
     * 按模板校验真实数据。
     *
     * @param template 数据模板
     * @param data     待校验的数据（JSON 字符串或已解析对象）
     * @return 问题列表，为空表示校验通过
     */
    public static List<String> valid(String template, Object data) {
        Object parsed = data;
        if (data instanceof String) {
            String text = ((String) data).trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                parsed = JSON.parse(text);
            }
        }
        Object tpl = template;
        if (template != null) {
            String text = template.trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                tpl = JSON.parse(MockJs.normalizeQuotes(text));
            }
        }
        List<String> problems = new ArrayList<String>();
        walk(tpl, parsed, "ROOT", problems);
        return problems;
    }

    /**
     * 由模板推导 JSON Schema。
     *
     * @param template 数据模板
     * @return JSON Schema（Map 结构）
     */
    public static Map<String, Object> toJsonSchema(String template) {
        String text = template == null ? "" : template.trim();
        Object tpl = (text.startsWith("{") || text.startsWith("["))
                ? JSON.parse(MockJs.normalizeQuotes(text)) : text;
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", typeName(tpl));
        if (tpl instanceof Map) {
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) tpl).entrySet()) {
                MockRule rule = MockRule.parse(entry.getKey(), new MockRandom());
                Object value = unwrapPickOne(entry.getValue(), rule);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("type", typeName(value));
                if (rule.getMin() != null || rule.getMax() != null) {
                    Map<String, Object> range = new LinkedHashMap<String, Object>();
                    range.put("min", rule.getMin());
                    range.put("max", rule.getMax());
                    item.put("range", range);
                }
                if (rule.getInc() != null) {
                    item.put("increment", rule.getInc());
                }
                if (value instanceof Map) {
                    item.put("properties",
                            toJsonSchema(JSON.toJsonString(value)).get("properties"));
                } else if (value instanceof List) {
                    List<Object> items = new ArrayList<Object>();
                    List<Object> list = (List<Object>) value;
                    if (!list.isEmpty()) {
                        Map<String, Object> sub = new LinkedHashMap<String, Object>();
                        sub.put("type", typeName(list.get(0)));
                        items.add(sub);
                    }
                    item.put("items", items);
                }
                properties.put(rule.getName(), item);
            }
            schema.put("properties", properties);
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object template, Object data, String path, List<String> problems) {
        if (template == null) {
            return;
        }
        if (template instanceof Map) {
            if (!(data instanceof Map)) {
                problems.add(path + " 期望对象，实际为 " + typeName(data));
                return;
            }
            Map<String, Object> tplMap = (Map<String, Object>) template;
            Map<String, Object> dataMap = (Map<String, Object>) data;
            for (Map.Entry<String, Object> entry : tplMap.entrySet()) {
                MockRule rule = MockRule.parse(entry.getKey(), new MockRandom());
                String child = path + "." + rule.getName();
                if (!dataMap.containsKey(rule.getName())) {
                    problems.add(child + " 缺少属性");
                    continue;
                }
                checkRule(unwrapPickOne(entry.getValue(), rule), rule,
                        dataMap.get(rule.getName()), child, problems);
            }
            return;
        }
        if (template instanceof List) {
            if (!(data instanceof List)) {
                problems.add(path + " 期望数组，实际为 " + typeName(data));
                return;
            }
            List<Object> tplList = (List<Object>) template;
            List<Object> dataList = (List<Object>) data;
            if (!tplList.isEmpty()) {
                for (int i = 0; i < dataList.size(); i++) {
                    walk(tplList.get(0), dataList.get(i), path + "[" + i + "]", problems);
                }
            }
            return;
        }
        checkRule(template, null, data, path, problems);
    }

    private static void checkRule(Object template, MockRule rule, Object data, String path,
                                  List<String> problems) {
        String expected = typeName(template);
        String actual = typeName(data);
        if ("string".equals(expected) && template instanceof String) {
            String text = (String) template;
            if (isPlaceholder(text)) {
                return;
            }
        }
        if (!expected.equals(actual)) {
            problems.add(path + " 期望 " + expected + "，实际为 " + actual);
            return;
        }
        if (rule == null) {
            return;
        }
        Integer min = rule.getMin();
        Integer max = rule.getMax();
        if (min == null && max == null) {
            return;
        }
        if (data instanceof List) {
            int size = ((List<?>) data).size();
            int unit = template instanceof List ? ((List<?>) template).size() : 1;
            if (unit < 1) {
                unit = 1;
            }
            // 'name|min-max' 对数组是「重复 min-max 次」，按倍数校验
            int times = size % unit == 0 ? size / unit : -1;
            if (times < 0) {
                problems.add(path + " 数组长度 " + size + " 不是模板长度 " + unit + " 的整数倍");
            } else if (min != null && times < min) {
                problems.add(path + " 数组重复 " + times + " 次，少于 " + min);
            } else if (max != null && times > max) {
                problems.add(path + " 数组重复 " + times + " 次，多于 " + max);
            }
        } else if (data instanceof Number) {
            double value = ((Number) data).doubleValue();
            if (min != null && value < min) {
                problems.add(path + " 数值 " + value + " 小于 " + min);
            }
            if (max != null && value > max) {
                problems.add(path + " 数值 " + value + " 大于 " + max);
            }
        }
        if (data instanceof Map && min != null) {
            int size = ((Map<?, ?>) data).size();
            if (size > min) {
                problems.add(path + " 属性个数 " + size + " 超过 " + min);
            }
        }
    }

    /**
     * {@code 'name|1'} 对数组是「随机取一个元素」，校验 / Schema 推导时降级为元素本身。
     *
     * @param template 模板属性值
     * @param rule     解析出的规则
     * @return 降级后的模板值
     */
    private static Object unwrapPickOne(Object template, MockRule rule) {
        if (template instanceof List && !((List<?>) template).isEmpty()
                && rule.getInc() == null && rule.getMin() != null
                && rule.getMin() == 1 && rule.getMax() == null) {
            return ((List<Object>) template).get(0);
        }
        return template;
    }

    private static boolean isPlaceholder(String text) {
        return text.contains("@") || text.startsWith("/");
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return "unknown";
    }
}
