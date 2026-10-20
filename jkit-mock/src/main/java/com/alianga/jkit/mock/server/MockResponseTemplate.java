package com.alianga.jkit.mock.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 响应模板变量渲染器（{@code {{request.*}}} / {@code {{faker.*}}} 等扩展占位符）。
 * 只在 {@link MockRoute#templated} 为 true 时由 {@link MockBodyRenderer} 接入；
 * false 时路由继续走 {@link MockTemplateRenderer}，行为与历史完全一致——已有路由的
 * 响应体里可能本来就写着 {@code {{，一个开关误开就会把用户原文当占位符吃掉。
 *
 * <p>支持的占位符（首个 token 后可跟空格分隔参数，任一位置可加 {@code seed=<整数>}）：</p>
 * <ul>
 *   <li>请求类：{@code {{request.method}}} / {@code {{request.path}}} /
 *       {@code {{request.pathParams.id}}} / {@code {{request.query.uid}}} /
 *       {@code {{request.headers.X-Token}}}（名称大小写不敏感）/
 *       {@code {{request.body.a.b.c}}} 或 {@code {{request.body.a[0].id}}}</li>
 *   <li>动态值：{@code {{uuid}}} / {@code {{now}}}（可带格式参数，如 {@code {{now yyyy/MM/dd}}}）/
 *       {@code {{timestamp}}}（秒级 epoch）/ {@code {{randomInt 1 100}}}（双端含）</li>
 *   <li>假数据（内置 zh-CN 小数据集）：{@code {{faker.name}}} / {@code {{faker.phone}}} /
 *       {@code {{faker.email}}} / {@code {{faker.city}}}</li>
 *   <li>历史语法在 templated 模式同样取值：{@code {{param.id}}} / {@code {{query.x}}} /
 *       {@code {{header.x}}} / {@code {{body.a.b}}} / {@code {{var.name}}} /
 *       {@code {{method}}} / {@code {{path}}} / {@code {{event}}} / 自定义 extra</li>
 * </ul>
 *
 * <p>取值语义与旧渲染器的最大差别（这是开关存在的意义）：</p>
 * <ul>
 *   <li>取不到值或写错名称：原样保留 {@code {{...}}} 文本，并把一条
 *       {@code unknown-template-variable: <表达式>} 记入错误表（调用方经
 *       {@code X-Mock-Warning} 响应头暴露）；</li>
 *   <li>替换值一律做 JSON 字符串转义（引号 / 反斜杠 / 换行 / 控制字符），
 *       塞进 JSON 字符串位置不会破坏结构；</li>
 *   <li>{@code \{{}} 字面量转义：输出 {@code {{} 而不进入占位符扫描。</li>
 * </ul>
 *
 * <p>模板解析结果（原文 → 记号序列）在 {@link #PARSE_CACHE} 内缓存，热路径只做取值不重扫；
 * 缓存上限 1024，超过整表清空（解析很便宜，清空远比等一个精确的 LRU 划算）。</p>
 *
 * @author 郑明亮
 * @since 2.0.5
 */
public final class MockResponseTemplate {
    private MockResponseTemplate() {
    }

    /** 解析缓存：模板原文 → 记号序列；命中即免扫。 */
    private static final Map<String, List<Part>> PARSE_CACHE = new ConcurrentHashMap<>();
    /** 缓存容量上限；超限整体清空（见类注释的取舍说明）。 */
    private static final int CACHE_MAX = 1024;

    /** 渲染结果的一个片段：字面文本或占位符表达式，二者互斥。 */
    private static final class Part {
        /** 非 null 表示字面片段;非 null 表示占位符表达式。 */
        final String literal;
        final String expr;
        /** 占位符原文 {{expr}}，未解析时按原样输出。 */
        final String raw;

        Part(String literal, String expr, String raw) {
            this.literal = literal;
            this.expr = expr;
            this.raw = raw;
        }
    }

    /**
     * 渲染模板；unresolved 保留原文并把原因追加到 errors（可空）。
     *
     * @param template 模板文本
     * @param ctx      请求上下文；null 时所有请求类变量均未命中
     * @param errors   原因收集表（可空）
     * @return 渲染后的文本
     */
    public static String render(String template, MockRequestContext ctx, List<String> errors) {
        return render(template, ctx, null, errors);
    }

    /**
     * 同 {@link #render(String, MockRequestContext, List)}，另支持一组自定义占位符
     * （仅精确字符串名，如 {@code request=回显 JSON}）；自定义表优先于表达式解析。
     *
     * @param template 模板文本
     * @param ctx      请求上下文
     * @param extra    自定义占位符表（可空）
     * @param errors   原因收集表（可空）
     * @return 渲染后的文本
     */
    public static String render(String template, MockRequestContext ctx,
                                Map<String, String> extra, List<String> errors) {
        String tpl = template == null ? "" : template;
        if (tpl.indexOf('{') < 0) {
            return tpl;
        }
        List<Part> parts = parse(tpl);
        StringBuilder out = new StringBuilder(tpl.length() + 32);
        for (Part part : parts) {
            if (part.literal != null) {
                out.append(part.literal);
                continue;
            }
            String value;
            if (extra != null && extra.containsKey(part.expr)) {
                String extraValue = extra.get(part.expr);
                value = extraValue == null ? "" : escapeIntoString(extraValue);
            } else {
                String resolved = resolve(part.expr, ctx);
                if (resolved == null) {
                    // 取不到值或写错名称：保留原文文本，原因交给调用方经 X-Mock-Warning 暴露
                    out.append(part.raw);
                    if (errors != null) {
                        errors.add("unknown-template-variable: " + part.expr);
                    }
                    continue;
                }
                value = escapeIntoString(resolved);
            }
            out.append(value);
        }
        return out.toString();
    }

    /** 渲染响应头值表（每枚逐一按模板渲染；键不动，保序返回可独立放头的副本）。 */
    public static Map<String, List<String>> renderHeaders(Map<String, List<String>> headers,
                                                          MockRequestContext ctx,
                                                          List<String> errors) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (headers == null || headers.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            List<String> rendered = new ArrayList<>(e.getValue().size());
            for (String value : e.getValue()) {
                rendered.add(value == null ? "" : render(value, ctx, null, errors));
            }
            out.put(e.getKey(), rendered);
        }
        return out;
    }

    /** 解析为记号序列；同一模板只扫一次（缓存）。 */
    private static List<Part> parse(String template) {
        List<Part> cached = PARSE_CACHE.get(template);
        if (cached != null) {
            return cached;
        }
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        int n = template.length();
        while (i < n) {
            // \{{ 字面量转义：吃掉反斜杠，输出 {{ 而不进占位符
            if (template.charAt(i) == '\\' && i + 2 < n
                    && template.charAt(i + 1) == '{' && template.charAt(i + 2) == '{') {
                literal.append("{{");
                i += 3;
                continue;
            }
            if (template.charAt(i) == '{' && i + 1 < n && template.charAt(i + 1) == '{') {
                int end = template.indexOf("}}", i + 2);
                if (end >= 0) {
                    flush(parts, literal);
                    parts.add(new Part(null, template.substring(i + 2, end).trim(),
                            template.substring(i, end + 2)));
                    i = end + 2;
                    continue;
                }
            }
            literal.append(template.charAt(i));
            i++;
        }
        flush(parts, literal);
        if (PARSE_CACHE.size() >= CACHE_MAX) {
            PARSE_CACHE.clear();
        }
        PARSE_CACHE.put(template, parts);
        return parts;
    }

    private static void flush(List<Part> parts, StringBuilder literal) {
        if (literal.length() > 0) {
            parts.add(new Part(literal.toString(), null, null));
            literal.setLength(0);
        }
    }

    /**
     * 解析单个表达式并取值；取不到返回 {@code null}（调用方保留原文并记 warning）。
     * 注意「取到空串」与「取不到」是两回事：前者正常返回空串。
     */
    private static String resolve(String expr, MockRequestContext ctx) {
        if (expr.isEmpty()) {
            return null;
        }
        if (nameNeedsRequestContext(expr) && ctx == null) {
            // 请求类变量没有上下文等于 ",但 uuid / faker 等与请求无关的可以继续渲染
            return null;
        }
        String name;
        String args;
        int space = expr.indexOf(' ');
        if (space < 0) {
            name = expr;
            args = "";
        } else {
            name = expr.substring(0, space).trim();
            args = expr.substring(space + 1).trim();
        }
        // 可选随机种子：seed=<整数>；命中后从参数中摘除
        long seed = Long.MIN_VALUE;
        if (args.contains("seed=")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("seed=(-?\\d+)").matcher(args);
            if (m.find()) {
                seed = Long.parseLong(m.group(1));
                args = args.replace(m.group(0), "").trim();
            }
        }
        if (name.startsWith("request.") || "method".equals(name) || "path".equals(name)
                || name.startsWith("param.") || name.startsWith("query.")
                || name.startsWith("header.") || name.startsWith("body.") || name.startsWith("var.")
                || "event".equals(name)) {
            return requestScoped(name, args, ctx);
        }
        switch (name) {
            case "uuid":
                return seed == Long.MIN_VALUE
                        ? UUID.randomUUID().toString()
                        : new UUID(seed, seed * 7919L + 12345L).toString();
            case "now":
                return resolveNow(args);
            case "timestamp":
                return String.valueOf(System.currentTimeMillis() / 1000L);
            case "randomInt":
                return resolveRandomInt(args, seed);
            case "faker.name":
                return fakerName(random(seed));
            case "faker.phone":
                return fakerPhone(random(seed));
            case "faker.email":
                return fakerEmail(random(seed));
            case "faker.city":
                return CITIES[nextInt(CITIES.length, random(seed))];
            default:
                return null;
        }
    }

    /** 是否需要请求上下文才能求值：请求类与历史语法；uuid / now / timestamp / randomInt / faker 不需要。 */
    private static boolean nameNeedsRequestContext(String name) {
        return "method".equals(name) || "path".equals(name) || "event".equals(name)
                || name.startsWith("request.") || name.startsWith("param.")
                || name.startsWith("query.") || name.startsWith("header.")
                || name.startsWith("body.") || name.startsWith("var.");
    }

    /** 请求类与历史语法表达式取值；带参数临时不参与请求类（保留原文记警告，便于发现配置笔误）。 */
    private static String requestScoped(String name, String args, MockRequestContext ctx) {
        if (args != null && !args.isEmpty()) {
            return null;
        }
        if ("method".equals(name)) {
            return ctx.method();
        }
        if ("path".equals(name)) {
            return ctx.path();
        }
        if ("event".equals(name)) {
            return ctx.eventText() == null ? "" : ctx.eventText();
        }
        String key = name.contains(".") ? name.substring(name.indexOf('.') + 1) : "";
        String family = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if ("request".equals(family)) {
            // {{request.*}}：method / path / pathParams.<名> / query.<名> / headers.<名> / body.<JSON 路径>
            if ("method".equals(key)) {
                return ctx.method();
            }
            if ("path".equals(key)) {
                return ctx.path();
            }
            int second = key.indexOf('.');
            String sub = second > 0 ? key.substring(0, second) : key;
            String rest = second > 0 ? key.substring(second + 1) : "";
            if ("pathParams".equals(sub)) {
                return rest.isEmpty() ? null : ctx.pathParams().get(rest);
            }
            if ("query".equals(sub)) {
                return rest.isEmpty() ? null : ctx.queryFirst(rest);
            }
            if ("headers".equals(sub)) {
                return rest.isEmpty() ? null : ctx.headerFirst(rest);
            }
            if ("body".equals(sub)) {
                String path = normalizeJsonPath(rest);
                return path.isEmpty() ? ctx.bodyText() : ctx.bodyPathOrRaw(path);
            }
            return null;
        }
        if ("param".equals(family)) {
            return key.isEmpty() ? null : ctx.pathParams().get(key);
        }
        if ("query".equals(family)) {
            return ctx.queryFirst(key);
        }
        if ("header".equals(family)) {
            return ctx.headerFirst(key);
        }
        if ("body".equals(family)) {
            String path = normalizeJsonPath(key);
            return path.isEmpty() ? ctx.bodyText() : ctx.bodyPathOrRaw(path);
        }
        if ("var".equals(family)) {
            return ctx.variable(key);
        }
        return null;
    }

    /** {{request.body.a[0].id}} 的下标语法归一到 bodyPathOrRaw 的点分形式 a.0.id。 */
    private static String normalizeJsonPath(String path) {
        if (path.indexOf('[') < 0) {
            return path;
        }
        return path.replaceAll("\\[([0-9]+)\\]", ".$1");
    }

    /** now:缺省 yyyy-MM-dd HH:mm:ss,其余参数当作 DateTimeFormatter pattern;非法 pattern 按缺省并保留原文交给上层报警。 */
    private static String resolveNow(String args) {
        String pattern = args == null || args.isEmpty() ? "yyyy-MM-dd HH:mm:ss" : args;
        try {
            return java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern(pattern));
        } catch (RuntimeException ex) {
            // pattern 非法:返回 null 让上层保留原文 + unknown-template-variable,一眼看到写错
            return null;
        }
    }

    /** randomInt:参数为 min 与 max(双端含)。缺一或 min>max 视为配置错误,保留原文。 */
    private static String resolveRandomInt(String args, long seed) {
        String[] items = args == null ? new String[0] : args.trim().split("\\s+");
        if (items.length != 2 || !items[0].matches("-?\\d+") || !items[1].matches("-?\\d+")) {
            return null;
        }
        int min = Integer.parseInt(items[0]);
        int max = Integer.parseInt(items[1]);
        if (min > max) {
            return null;
        }
        int range = max - min + 1;
        return String.valueOf(min + nextInt(range, random(seed)));
    }

    private static Random random(long seed) {
        return seed == Long.MIN_VALUE ? new Random() : new Random(seed);
    }

    private static int nextInt(int bound, Random random) {
        return bound <= 0 ? 0 : random.nextInt(bound);
    }

    /** JSON 字符串位置转义：引号 / 反斜杠 / 换行制表等控制字符替换为转义序列。 */
    static String escapeIntoString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
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
        return sb.toString();
    }

    // ~~~~~~~~~~~~~~~~~~~~~ 假数据小数据集（内置 zh-CN,够用就好,不追求大而全） ~~~~~~~~~~~~~~~~~~~~~

    private static final String[] SURNAMES = {
            "张", "王", "李", "赵", "刘", "陈", "杨", "黄", "周", "吴", "徐", "孙", "胡", "朱",
            "高", "林", "何", "郭", "马", "罗", "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "程"};
    private static final String[] GIVEN = {
            "伟", "芳", "娜", "敏", "静", "丽", "强", "磊", "军", "洋", "勇", "艳", "杰", "娟",
            "涛", "明", "超", "秀英", "兰", "霞", "玉", "刚", "颖", "洁", "梅", "慧", "鹏", "浩", "宇", "欣"};
    private static final String[] CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆",
            "苏州", "天津", "长沙", "郑州", "青岛", "厦门", "合肥", "宁波", "佛山", "东莞"};
    private static final String[] PHONE_PREFIX = {
            "130", "131", "132", "133", "135", "137", "138", "150", "152", "155",
            "157", "158", "166", "176", "177", "180", "182", "185", "188", "189", "199"};
    private static final String[] EMAIL_DOMAIN = {
            "qq.com", "163.com", "126.com", "gmail.com", "example.com", "alianga.com"};

    private static String fakerName(Random random) {
        String surname = SURNAMES[nextInt(SURNAMES.length, random)];
        String given = GIVEN[nextInt(GIVEN.length, random)];
        return surname + given;
    }

    private static String fakerPhone(Random random) {
        StringBuilder sb = new StringBuilder(11);
        sb.append(PHONE_PREFIX[nextInt(PHONE_PREFIX.length, random)]);
        for (int i = 0; i < 8; i++) {
            sb.append(nextInt(10, random));
        }
        return sb.toString();
    }

    private static String fakerEmail(Random random) {
        String surname = SURNAMES[nextInt(SURNAMES.length, random)];
        String given = GIVEN[nextInt(GIVEN.length, random)];
        StringBuilder local = new StringBuilder();
        for (char c : (surname + given).toCharArray()) {
            // 中文取不到稳定拼音,用字内码段做前缀:换种子也能复现,且比乱码可读
            local.append(String.format("%04x", (int) c));
        }
        local.append(nextInt(100, random));
        return local + "@" + EMAIL_DOMAIN[nextInt(EMAIL_DOMAIN.length, random)];
    }
}
