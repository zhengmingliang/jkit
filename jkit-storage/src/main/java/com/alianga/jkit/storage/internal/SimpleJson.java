package com.alianga.jkit.storage.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多吉云引擎专用的最小 JSON 编解码：只覆盖其 API 实际用到的
 * object / array / string / number / boolean / null，零第三方依赖。
 *
 * @author 郑明亮
 */
public final class SimpleJson {
    private SimpleJson() {
    }

    /**
     * 解析 JSON 对象（根必须是对象）。
     *
     * @param text JSON 文本
     * @return 解析结果，非法返回 null
     */
    public static Map<String, Object> parseObject(String text) {
        try {
            Parser parser = new Parser(text);
            parser.skipWhite();
            Object value = parser.parse();
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 序列化为紧凑 JSON（Map / List / String / Number / Boolean / null）。
     *
     * @param value 值
     * @return JSON 文本
     */
    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String) {
            writeString(out, (String) value);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
            return;
        }
        writeString(out, String.valueOf(value));
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /** 递归下降解析器。 */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parse() {
            skipWhite();
            char c = peek();
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            if (c == 'n') {
                expectWord("null");
                return null;
            }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhite();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (pos < text.length()) {
                skipWhite();
                String key = parseString();
                skipWhite();
                expect(':');
                map.put(key, parse());
                skipWhite();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                break;
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhite();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (pos < text.length()) {
                skipWhite();
                list.add(parse());
                skipWhite();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                break;
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '\\') {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"':
                        case '\\':
                        case '/':
                            sb.append(e);
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(e);
                    }
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("字符串未闭合");
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && (Character.isDigit(peek()) || peek() == '.')) {
                pos++;
            }
            String num = text.substring(start, pos);
            return num.contains(".") ? Double.parseDouble(num) : (Number) Long.parseLong(num);
        }

        Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            expectWord("false");
            return Boolean.FALSE;
        }

        void expectWord(String word) {
            if (!text.startsWith(word, pos)) {
                throw new IllegalStateException("期望 " + word + " pos=" + pos);
            }
            pos += word.length();
        }

        char peek() {
            return text.charAt(pos);
        }

        void expect(char c) {
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw new IllegalStateException("期望 '" + c + "' pos=" + pos);
            }
            pos++;
        }

        void skipWhite() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
