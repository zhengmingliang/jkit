package com.alianga.jkit.config;

import com.alianga.jkit.yaml.YamlDocument;
import com.alianga.jkit.yaml.YamlNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 将单个 YAML / properties 资源解析为扁平的 {@code key=value} 映射。
 *
 * <p>YAML 嵌套结构按 Spring Boot 规则展开：对象用 {@code .} 连接，
 * 列表用 {@code key[0]}、{@code key[1]} 形式。</p>
 */
final class ConfigFiles {
    private ConfigFiles() {
    }

    static boolean isYaml(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    static Map<String, Object> read(InputStream in, String fileName) throws IOException {
        if (isYaml(fileName)) {
            return flattenYaml(YamlDocument.read(in, StandardCharsets.UTF_8));
        }
        return readProperties(in);
    }

    static Map<String, Object> readProperties(InputStream in) throws IOException {
        Properties props = new Properties();
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        props.load(reader);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (String name : props.stringPropertyNames()) {
            map.put(name, props.getProperty(name));
        }
        return map;
    }

    static Map<String, Object> flattenYaml(YamlDocument document) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (document == null) {
            return out;
        }
        if (document.isMultiple()) {
            List<YamlNode> nodes = document.getYamlNodeList();
            if (nodes != null) {
                for (YamlNode node : nodes) {
                    flatten("", node == null ? null : node.toMap(), out);
                }
            }
            return out;
        }
        YamlNode root = document.getRoot();
        if (root != null) {
            flatten("", root.toMap(), out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void flatten(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                String child = isEmpty(prefix) ? key : prefix + "." + key;
                flatten(child, entry.getValue(), out);
            }
            return;
        }
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), out);
            }
            return;
        }
        if (!isEmpty(prefix)) {
            out.put(prefix, value);
        }
    }

    static void copyToProperties(Map<String, Object> source, Properties target) {
        if (source == null || target == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            target.setProperty(entry.getKey(), value == null ? "" : String.valueOf(value));
        }
    }

    /**
     * 将扁平 key（{@code a.b[0].c}）还原为指定前缀下的嵌套 Map。
     *
     * @param properties 扁平属性
     * @param prefix 前缀，空表示整份配置
     * @return 嵌套 Map
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unflatten(Map<String, Object> properties, String prefix) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        if (properties == null || properties.isEmpty()) {
            return root;
        }
        String base = prefix == null ? "" : prefix.trim();
        String dotted = base.isEmpty() ? "" : base + ".";
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String rest;
            if (base.isEmpty()) {
                rest = key;
            } else if (key.equals(base)) {
                continue;
            } else if (key.startsWith(dotted)) {
                rest = key.substring(dotted.length());
            } else {
                continue;
            }
            if (rest.isEmpty()) {
                continue;
            }
            putPath(root, rest, entry.getValue());
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    static void putPath(Map<String, Object> root, String path, Object value) {
        List<String> tokens = tokenize(path);
        Object current = root;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean last = i == tokens.size() - 1;
            boolean indexToken = token.charAt(0) == '[';
            if (indexToken) {
                int idx = Integer.parseInt(token.substring(1, token.length() - 1));
                List<Object> list = (List<Object>) current;
                while (list.size() <= idx) {
                    list.add(null);
                }
                if (last) {
                    list.set(idx, value);
                } else {
                    Object next = list.get(idx);
                    if (next == null) {
                        next = newContainer(tokens.get(i + 1));
                        list.set(idx, next);
                    }
                    current = next;
                }
            } else {
                Map<String, Object> map = (Map<String, Object>) current;
                if (last) {
                    map.put(token, value);
                } else {
                    Object next = map.get(token);
                    if (next == null) {
                        next = newContainer(tokens.get(i + 1));
                        map.put(token, next);
                    }
                    current = next;
                }
            }
        }
    }

    private static Object newContainer(String nextToken) {
        if (nextToken.charAt(0) == '[') {
            return new ArrayList<Object>();
        }
        return new LinkedHashMap<String, Object>();
    }

    static List<String> tokenize(String path) {
        List<String> tokens = new ArrayList<String>();
        int i = 0;
        int len = path.length();
        while (i < len) {
            char ch = path.charAt(i);
            if (ch == '.') {
                i++;
                continue;
            }
            if (ch == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    tokens.add(path.substring(i));
                    break;
                }
                tokens.add(path.substring(i, end + 1));
                i = end + 1;
                continue;
            }
            int dot = path.indexOf('.', i);
            int bracket = path.indexOf('[', i);
            int end = len;
            if (dot >= 0 && (bracket < 0 || dot < bracket)) {
                end = dot;
            } else if (bracket >= 0) {
                end = bracket;
            }
            tokens.add(path.substring(i, end));
            i = end;
        }
        return tokens;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
