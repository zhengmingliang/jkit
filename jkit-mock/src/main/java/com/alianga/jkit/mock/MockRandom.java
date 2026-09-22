package com.alianga.jkit.mock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock.js {@code Mock.Random} 的 Java 实现：数据模板中的 {@code @占位符} 全部由本类生成。
 *
 * <p>方法名与 Mock.js 一一对应（{@code boolean}、{@code float} 因是 Java 关键字，分别改名为
 * {@link #bool()} 与 {@link #floatValue()}）。占位符名大小写不敏感，{@code @CNAME} 与
 * {@code @cname} 等价。除主名外还支持 Mock.js 的别名：{@code bool / int / number / char /
 * str / img / inc}。</p>
 *
 * <pre>{@code
 * MockRandom r = new MockRandom();
 * r.cname();                    // => "吴勇"
 * r.integer(1, 100);            // => 37
 * r.string("lower", 8);         // => "gpemikdz"
 * r.invoke("pick", Arrays.asList("a", "b", "c"));
 * }</pre>
 *
 * @author 郑明亮
 */
public class MockRandom {
    /** Mock.js 中 {@code natural()} 的默认上界 2^53。 */
    private static final long MAX_SAFE = 9007199254740992L;

    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String NUMBER = "0123456789";
    private static final String SYMBOL = "!@#$%^&*()[]";
    private static final String GUID_POOL = "abcdefABCDEF1234567890";

    private static final Map<String, String> POOLS = new HashMap<String, String>();
    private static final List<String> PLACEHOLDERS = new ArrayList<String>();

    /** 占位符推荐写法（带典型参数），用于速查面板一键插入。 */
    private static final Map<String, String> SAMPLES = new HashMap<String, String>();

    static {
        POOLS.put("lower", LOWER);
        POOLS.put("upper", UPPER);
        POOLS.put("number", NUMBER);
        POOLS.put("symbol", SYMBOL);
        POOLS.put("alpha", LOWER + UPPER);
        POOLS.put("undefined", LOWER + UPPER + NUMBER + SYMBOL);
        POOLS.put("hex", "0123456789abcdef");
        PLACEHOLDERS.addAll(Arrays.asList(
                "boolean", "bool", "natural", "integer", "int", "number", "float", "character",
                "char", "string", "str", "range", "date", "time", "datetime", "now",
                "image", "img", "dataImage", "color", "hex", "rgb", "rgba", "hsl",
                "paragraph", "sentence", "word", "title", "cparagraph", "csentence", "cword",
                "ctitle", "first", "last", "name", "cfirst", "clast", "cname",
                "url", "domain", "protocol", "tld", "email", "ip",
                "region", "province", "city", "county", "zip",
                "capitalize", "upper", "lower", "pick", "shuffle",
                "guid", "uuid", "id", "increment", "inc",
                "phone", "gender", "company", "department", "position", "salary",
                "bankCard", "creditCard", "currency", "mac", "userAgent", "password",
                "token", "timestamp", "fileName", "mime"));
        SAMPLES.put("integer", "@integer(1,100)");
        SAMPLES.put("natural", "@natural(0,99)");
        SAMPLES.put("float", "@float(1,100,2,4)");
        SAMPLES.put("character", "@character('lower')");
        SAMPLES.put("string", "@string('lower',8)");
        SAMPLES.put("range", "@range(1,10,2)");
        SAMPLES.put("boolean", "@boolean");
        SAMPLES.put("date", "@date('yyyy-MM-dd')");
        SAMPLES.put("time", "@time('HH:mm:ss')");
        SAMPLES.put("datetime", "@datetime('yyyy-MM-dd HH:mm:ss')");
        SAMPLES.put("now", "@now('year')");
        SAMPLES.put("image", "@image('200x100')");
        SAMPLES.put("dataImage", "@dataImage('200x100')");
        SAMPLES.put("pick", "@pick(['a','b','c'])");
        SAMPLES.put("shuffle", "@shuffle([1,2,3])");
        SAMPLES.put("city", "@city(true)");
        SAMPLES.put("county", "@county(true)");
        SAMPLES.put("increment", "@increment");
        SAMPLES.put("phone", "@phone");
        SAMPLES.put("salary", "@salary(5000,30000)");
        SAMPLES.put("password", "@password(8)");
        SAMPLES.put("timestamp", "@timestamp");
        SAMPLES.put("cparagraph", "@cparagraph(3)");
        SAMPLES.put("csentence", "@csentence");
        SAMPLES.put("cword", "@cword(2,5)");
        SAMPLES.put("ctitle", "@ctitle(3,8)");
    }

    /**
     * 自定义占位符生成器，对应 Mock.js 的 {@code Random.extend()} 扩展机制。
     *
     * @author 郑明亮
     */
    public interface MockPlaceholder {
        /**
         * 生成占位符的值。
         *
         * @param random 随机源，实现内应复用它以保证种子复现
         * @param args   模板里传入的参数，元素可为字符串 / 数字 / 布尔 / 数组
         * @return 生成结果
         */
        Object apply(MockRandom random, Object... args);
    }

    private final Random random;
    private final AtomicLong incrementKey = new AtomicLong();
    private final Map<String, MockPlaceholder> extensions = new HashMap<String, MockPlaceholder>();
    private double hue = -1;

    /**
     * 默认构造，随机源为 {@link SecureRandom}。
     */
    public MockRandom() {
        this(new SecureRandom());
    }

    /**
     * 指定随机源，便于测试复现。
     *
     * @param random 随机源
     */
    public MockRandom(Random random) {
        this.random = random == null ? new SecureRandom() : random;
    }

    /**
     * 指定随机种子。
     *
     * @param seed 种子
     */
    public MockRandom(long seed) {
        this(new Random(seed));
    }

    /**
     * 全部可用占位符名（含别名）。
     *
     * @return 占位符名列表
     */
    public static List<String> placeholders() {
        return Collections.unmodifiableList(PLACEHOLDERS);
    }

    /**
     * 占位符分类目录，供速查面板按类分组展示。
     *
     * <p>key 是分类标识（{@code basic} / {@code date} / {@code business} …，便于界面做国际化），
     * value 是该分类下的主占位符名（不含 {@code bool} / {@code int} 这类别名）。</p>
     *
     * @return 有序的分类目录
     */
    public static Map<String, List<String>> placeholderGroups() {
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        groups.put("basic", Arrays.asList("boolean", "natural", "integer", "float",
                "character", "string", "range"));
        groups.put("date", Arrays.asList("date", "time", "datetime", "now"));
        groups.put("image", Arrays.asList("image", "dataImage"));
        groups.put("color", Arrays.asList("color", "hex", "rgb", "rgba", "hsl"));
        groups.put("text", Arrays.asList("paragraph", "sentence", "word", "title"));
        groups.put("name", Arrays.asList("first", "last", "name"));
        groups.put("web", Arrays.asList("url", "domain", "protocol", "tld", "email", "ip"));
        groups.put("address", Arrays.asList("region", "province", "city", "county", "zip"));
        groups.put("helper", Arrays.asList("capitalize", "upper", "lower", "pick", "shuffle"));
        groups.put("misc", Arrays.asList("guid", "uuid", "id", "increment"));
        groups.put("business", Arrays.asList("phone", "gender", "company", "department",
                "position", "salary", "bankCard", "creditCard", "currency", "mac",
                "userAgent", "password", "token", "timestamp", "fileName", "mime"));
        groups.put("cn", Arrays.asList("cparagraph", "csentence", "cword", "ctitle",
                "cfirst", "clast", "cname"));
        return groups;
    }

    /**
     * 占位符的推荐写法：带上典型参数，可直接插进模板。
     *
     * <p>{@code sample("integer")} 返回 {@code "@integer(1,100)"}；未收录的返回 {@code "@" + name}。</p>
     *
     * @param name 占位符名
     * @return 推荐写法
     */
    public static String sample(String name) {
        String hit = SAMPLES.get(name == null ? "" : name.toLowerCase());
        return hit == null ? "@" + name : hit;
    }

    /**
     * 注册自定义占位符，覆盖 Mock.js 的 {@code Random.extend()}。重名时后来者优先。
     *
     * <pre>{@code
     * MockRandom r = new MockRandom();
     * r.extend("employeeNo", (random, args) -> "E" + random.integer(1000, 9999));
     * r.invoke("employeeNo");          // => "E3821"
     * }</pre>
     *
     * @param name 占位符名，大小写不敏感
     * @param fn   生成逻辑
     * @return 当前实例，便于链式注册
     */
    public MockRandom extend(String name, MockPlaceholder fn) {
        String key = name == null ? "" : name.trim().toLowerCase();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("占位符名不能为空");
        }
        if (fn == null) {
            throw new IllegalArgumentException("占位符生成逻辑不能为空");
        }
        extensions.put(key, fn);
        return this;
    }

    /**
     * 内置占位符加上本实例已注册的自定义占位符。
     *
     * @return 全部可用占位符名
     */
    public List<String> allPlaceholders() {
        List<String> all = new ArrayList<String>(PLACEHOLDERS);
        for (String key : extensions.keySet()) {
            if (!all.contains(key)) {
                all.add(key);
            }
        }
        return all;
    }

    /**
     * 按名字调用占位符，名字大小写不敏感。
     *
     * @param name 占位符名，如 {@code cname}
     * @param args 参数，元素可为字符串 / 数字 / 布尔 / 数组
     * @return 生成结果
     */
    public Object invoke(String name, Object... args) {
        String key = name == null ? "" : name.toLowerCase();
        Object[] a = args == null ? new Object[0] : args;
        MockPlaceholder custom = extensions.get(key);
        if (custom != null) {
            return custom.apply(this, a);
        }
        if ("boolean".equals(key) || "bool".equals(key)) {
            return bool(a);
        }
        if ("natural".equals(key)) {
            return natural(a);
        }
        if ("integer".equals(key) || "int".equals(key) || "number".equals(key)) {
            return integer(a);
        }
        if ("float".equals(key)) {
            return floatValue(a);
        }
        if ("character".equals(key) || "char".equals(key)) {
            return character(a);
        }
        if ("string".equals(key) || "str".equals(key)) {
            return string(a);
        }
        if ("range".equals(key)) {
            return range(a);
        }
        if ("date".equals(key)) {
            return date(a);
        }
        if ("time".equals(key)) {
            return time(a);
        }
        if ("datetime".equals(key)) {
            return datetime(a);
        }
        if ("now".equals(key)) {
            return now(a);
        }
        if ("image".equals(key) || "img".equals(key)) {
            return image(a);
        }
        if ("dataimage".equals(key)) {
            return dataImage(a);
        }
        if ("color".equals(key)) {
            return color(a);
        }
        if ("hex".equals(key)) {
            return hex();
        }
        if ("rgb".equals(key)) {
            return rgb();
        }
        if ("rgba".equals(key)) {
            return rgba();
        }
        if ("hsl".equals(key)) {
            return hsl();
        }
        if ("paragraph".equals(key)) {
            return paragraph(a);
        }
        if ("sentence".equals(key)) {
            return sentence(a);
        }
        if ("word".equals(key)) {
            return word(a);
        }
        if ("title".equals(key)) {
            return title(a);
        }
        if ("cparagraph".equals(key)) {
            return cparagraph(a);
        }
        if ("csentence".equals(key)) {
            return csentence(a);
        }
        if ("cword".equals(key)) {
            return cword(a);
        }
        if ("ctitle".equals(key)) {
            return ctitle(a);
        }
        if ("first".equals(key)) {
            return first();
        }
        if ("last".equals(key)) {
            return last();
        }
        if ("name".equals(key)) {
            return name(a);
        }
        if ("cfirst".equals(key)) {
            return cfirst();
        }
        if ("clast".equals(key)) {
            return clast();
        }
        if ("cname".equals(key)) {
            return cname();
        }
        if ("url".equals(key)) {
            return url(a);
        }
        if ("domain".equals(key)) {
            return domain(a);
        }
        if ("protocol".equals(key)) {
            return protocol();
        }
        if ("tld".equals(key)) {
            return tld();
        }
        if ("email".equals(key)) {
            return email(a);
        }
        if ("ip".equals(key)) {
            return ip();
        }
        if ("region".equals(key)) {
            return region();
        }
        if ("province".equals(key)) {
            return province();
        }
        if ("city".equals(key)) {
            return city(a);
        }
        if ("county".equals(key)) {
            return county(a);
        }
        if ("zip".equals(key)) {
            return zip(a);
        }
        if ("capitalize".equals(key)) {
            return capitalize(a);
        }
        if ("upper".equals(key)) {
            return upper(a);
        }
        if ("lower".equals(key)) {
            return lower(a);
        }
        if ("pick".equals(key)) {
            return pick(a);
        }
        if ("shuffle".equals(key)) {
            return shuffle(a);
        }
        if ("guid".equals(key)) {
            return guid();
        }
        if ("uuid".equals(key)) {
            return uuid();
        }
        if ("id".equals(key)) {
            return id();
        }
        if ("increment".equals(key) || "inc".equals(key)) {
            return increment(a);
        }
        if ("phone".equals(key)) {
            return phone(a);
        }
        if ("gender".equals(key)) {
            return gender(a);
        }
        if ("company".equals(key)) {
            return company(a);
        }
        if ("department".equals(key) || "dept".equals(key)) {
            return department(a);
        }
        if ("position".equals(key)) {
            return position(a);
        }
        if ("salary".equals(key)) {
            return salary(a);
        }
        if ("bankcard".equals(key)) {
            return bankCard(a);
        }
        if ("creditcard".equals(key)) {
            return creditCard(a);
        }
        if ("currency".equals(key)) {
            return currency(a);
        }
        if ("mac".equals(key)) {
            return mac(a);
        }
        if ("useragent".equals(key) || "ua".equals(key)) {
            return userAgent(a);
        }
        if ("password".equals(key) || "pwd".equals(key)) {
            return password(a);
        }
        if ("token".equals(key)) {
            return token(a);
        }
        if ("timestamp".equals(key)) {
            return timestamp(a);
        }
        if ("filename".equals(key)) {
            return fileName(a);
        }
        if ("mime".equals(key) || "mimetype".equals(key)) {
            return mime(a);
        }
        throw new IllegalArgumentException("未知占位符：" + name);
    }

    // ------------------------------------------------------------------ Basic

    /**
     * 随机布尔值。{@code bool(min, max)} 时为 {@code true} 的概率约 {@code min/(min+max)}；
     * {@code bool(min, max, cur)} 在 {@code cur} 基础上按概率翻转。
     *
     * @param args 可选 {@code (min, max)} 或 {@code (min, max, cur)}
     * @return 布尔值
     */
    public boolean bool(Object... args) {
        if (args.length >= 3 && args[2] != null) {
            int min = argInt(args, 0, 1);
            int max = argInt(args, 1, 1);
            boolean cur = argBool(args, 2, true);
            double total = min + max;
            return random.nextDouble() > (total == 0 ? 0.5 : min / total) ? !cur : cur;
        }
        return random.nextDouble() >= 0.5;
    }

    /**
     * 随机自然数（{@code >= 0}），默认上界 2^53。
     *
     * @param args 可选 {@code (min, max)}
     * @return 自然数
     */
    public long natural(Object... args) {
        long min = argLong(args, 0, 0);
        long max = argLong(args, 1, MAX_SAFE);
        return min + Math.round(random.nextDouble() * (max - min));
    }

    /**
     * 随机整数，默认区间 {@code [-2^53, 2^53]}。
     *
     * @param args 可选 {@code (min, max)}
     * @return 整数
     */
    public long integer(Object... args) {
        long min = argLong(args, 0, -MAX_SAFE);
        long max = argLong(args, 1, MAX_SAFE);
        return min + Math.round(random.nextDouble() * (max - min));
    }

    /**
     * 随机浮点数。小数位数在 {@code [dmin, dmax]} 间随机，末位不为 0。
     *
     * @param args 可选 {@code (min, max, dmin, dmax)}
     * @return 浮点数
     */
    public double floatValue(Object... args) {
        int dmin = clamp(argInt(args, 2, 0), 0, 17);
        int dmax = clamp(argInt(args, 3, 17), 0, 17);
        long base = integer(slice(args, 0, 2));
        int count = (int) natural(dmin, dmax);
        if (count <= 0) {
            return base;
        }
        StringBuilder sb = new StringBuilder(String.valueOf(base));
        sb.append('.');
        for (int i = 0; i < count; i++) {
            sb.append(character(i < count - 1 ? "number" : "123456789"));
        }
        return Double.parseDouble(sb.toString());
    }

    /**
     * 随机字符。{@code pool} 可为 {@code lower / upper / number / symbol / alpha}，
     * 或直接给自定义字符池；缺省时取全部字符。
     *
     * @param args 可选 {@code (pool)}
     * @return 单字符字符串
     */
    public String character(Object... args) {
        String pool = poolOf(args.length == 0 ? null : args[0]);
        if (pool.isEmpty()) {
            return "";
        }
        int idx = (int) natural(0, pool.length() - 1);
        return String.valueOf(pool.charAt(idx));
    }

    /**
     * 随机字符串。签名随参数个数变化：{@code ()} 取 3~7 位；{@code (len)} 定长；
     * {@code (pool, len)} 指定字符池；{@code (pool, min, max)} 指定长度区间。
     *
     * @param args 可选 {@code (pool, min, max)}
     * @return 字符串
     */
    public String string(Object... args) {
        String pool = null;
        int len;
        switch (args.length) {
            case 0:
                len = (int) natural(3, 7);
                break;
            case 1:
                len = argInt(args, 0, 0);
                break;
            case 2:
                if (args[0] instanceof String) {
                    pool = String.valueOf(args[0]);
                    len = argInt(args, 1, 0);
                } else {
                    len = (int) natural(argInt(args, 0, 0), argInt(args, 1, 0));
                }
                break;
            default:
                pool = String.valueOf(args[0]);
                len = (int) natural(argInt(args, 1, 0), argInt(args, 2, 0));
                break;
        }
        String chars = poolOf(pool);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(character(chars));
        }
        return sb.toString();
    }

    /**
     * 生成整数序列，语义同 Mock.js 的 {@code range(start, stop, step)}。
     *
     * @param args {@code (stop)} / {@code (start, stop)} / {@code (start, stop, step)}
     * @return 整数列表
     */
    public List<Long> range(Object... args) {
        long start;
        long stop;
        if (args.length <= 1) {
            stop = argLong(args, 0, 0);
            start = 0;
        } else {
            start = argLong(args, 0, 0);
            stop = argLong(args, 1, 0);
        }
        long step = argLong(args, 2, 1);
        if (step == 0) {
            step = 1;
        }
        long len = Math.max((long) Math.ceil((double) (stop - start) / step), 0);
        List<Long> result = new ArrayList<Long>();
        long value = start;
        for (long i = 0; i < len; i++) {
            result.add(value);
            value += step;
        }
        return result;
    }

    // ------------------------------------------------------------------- Date

    /**
     * 随机日期字符串，默认格式 {@code yyyy-MM-dd}，取值范围 1970-01-01 至今。
     *
     * @param args 可选 {@code (format)}
     * @return 日期字符串
     */
    public String date(Object... args) {
        String format = args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "yyyy-MM-dd";
        return formatDate(randomDate(), format);
    }

    /**
     * 随机时间字符串，默认格式 {@code HH:mm:ss}。
     *
     * @param args 可选 {@code (format)}
     * @return 时间字符串
     */
    public String time(Object... args) {
        String format = args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "HH:mm:ss";
        return formatDate(randomDate(), format);
    }

    /**
     * 随机日期时间字符串，默认格式 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param args 可选 {@code (format)}
     * @return 日期时间字符串
     */
    public String datetime(Object... args) {
        String format = args.length > 0 && args[0] != null
                ? String.valueOf(args[0]) : "yyyy-MM-dd HH:mm:ss";
        return formatDate(randomDate(), format);
    }

    /**
     * 当前时间字符串。{@code unit} 可取 {@code year / month / week / day / hour /
     * minute / second}，用于把时间归整到该单位起点；单参数且不是这些单位时按格式串处理。
     *
     * @param args 可选 {@code (unit)}、{@code (format)} 或 {@code (unit, format)}
     * @return 时间字符串
     */
    public String now(Object... args) {
        String unit = "";
        String format = "yyyy-MM-dd HH:mm:ss";
        if (args.length >= 1 && args[0] != null) {
            String first = String.valueOf(args[0]);
            boolean isUnit = "year".equals(first) || "month".equals(first) || "day".equals(first)
                    || "hour".equals(first) || "minute".equals(first) || "second".equals(first)
                    || "week".equals(first);
            if (isUnit) {
                unit = first;
            } else {
                format = first;
            }
        }
        if (args.length >= 2 && args[1] != null) {
            format = String.valueOf(args[1]);
        }
        Calendar cal = Calendar.getInstance();
        if ("year".equals(unit)) {
            cal.set(Calendar.MONTH, 0);
            cal.set(Calendar.DAY_OF_MONTH, 1);
        } else if ("month".equals(unit)) {
            cal.set(Calendar.DAY_OF_MONTH, 1);
        }
        if ("week".equals(unit) || "day".equals(unit) || "year".equals(unit)
                || "month".equals(unit)) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
        }
        if (!"".equals(unit) && !"second".equals(unit)) {
            cal.set(Calendar.MINUTE, 0);
        }
        if (!"".equals(unit) && !"second".equals(unit) && !"minute".equals(unit)) {
            cal.set(Calendar.SECOND, 0);
        }
        cal.set(Calendar.MILLISECOND, 0);
        if ("week".equals(unit)) {
            cal.add(Calendar.DAY_OF_MONTH, -(cal.get(Calendar.DAY_OF_WEEK) - 1));
        }
        return formatDate(cal.getTime(), format);
    }

    // ------------------------------------------------------------------ Image

    /**
     * 随机图片地址（dummyimage.com）。参数可为
     * {@code (size, background, foreground, format, text)}，也兼容
     * {@code (size, background, text)} 与 {@code (size, background, foreground, text)}。
     *
     * @param args 见上
     * @return 图片地址
     */
    public String image(Object... args) {
        Object[] p = args;
        if (p.length == 4) {
            p = new Object[]{args[0], args[1], args[2], null, args[3]};
        } else if (p.length == 3) {
            p = new Object[]{args[0], args[1], null, null, args[2]};
        }
        String size = argStr(p, 0, null);
        if (size == null || size.isEmpty()) {
            size = pickOne(MockDict.AD_SIZES);
        }
        String bg = strip(argStr(p, 1, null));
        String fg = strip(argStr(p, 2, null));
        String format = argStr(p, 3, null);
        String text = argStr(p, 4, null);
        StringBuilder sb = new StringBuilder("http://dummyimage.com/").append(size);
        if (bg != null && !bg.isEmpty()) {
            sb.append('/').append(bg);
        }
        if (fg != null && !fg.isEmpty()) {
            sb.append('/').append(fg);
        }
        if (format != null && !format.isEmpty()) {
            sb.append('.').append(format);
        }
        if (text != null && !text.isEmpty()) {
            sb.append("&text=").append(text);
        }
        return sb.toString();
    }

    /**
     * 随机 SVG 图片，返回 {@code data:image/svg+xml;base64,...} 形式的 data URI。
     *
     * @param args 可选 {@code (size, text)}
     * @return data URI
     */
    public String dataImage(Object... args) {
        String size = argStr(args, 0, null);
        if (size == null || size.isEmpty()) {
            size = pickOne(MockDict.AD_SIZES);
        }
        String text = argStr(args, 1, null);
        if (text == null || text.isEmpty()) {
            text = size;
        }
        int[] wh = parseSize(size);
        String bg = hex();
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" baseProfile=\"full\""
                + " width=\"" + wh[0] + "\" height=\"" + wh[1] + "\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"" + bg + "\"/>"
                + "<text x=\"" + (wh[0] / 2) + "\" y=\"" + (wh[1] / 2)
                + "\" fill=\"#FFFFFF\" text-anchor=\"middle\""
                + " font-family=\"sans-serif\" font-size=\"" + Math.max(12, wh[1] / 5) + "\">"
                + escapeXml(text) + "</text></svg>";
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ Color

    /**
     * 随机颜色，格式 {@code #RRGGBB}。
     *
     * @param args 兼容 Mock.js 的颜色名参数（未命中时退化为随机色）
     * @return 颜色字符串
     */
    public String color(Object... args) {
        return hex();
    }

    /**
     * 随机颜色，格式 {@code #rrggbb}。
     *
     * @return 颜色字符串
     */
    public String hex() {
        double[] hsv = goldenRatioColor();
        int[] rgb = hsv2rgb(hsv[0], hsv[1], hsv[2]);
        return rgb2hex(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * 随机颜色，格式 {@code rgb(r, g, b)}。
     *
     * @return 颜色字符串
     */
    public String rgb() {
        double[] hsv = goldenRatioColor();
        int[] rgb = hsv2rgb(hsv[0], hsv[1], hsv[2]);
        return "rgb(" + rgb[0] + ", " + rgb[1] + ", " + rgb[2] + ")";
    }

    /**
     * 随机颜色，格式 {@code rgba(r, g, b, a)}，透明度两位小数。
     *
     * @return 颜色字符串
     */
    public String rgba() {
        double[] hsv = goldenRatioColor();
        int[] rgb = hsv2rgb(hsv[0], hsv[1], hsv[2]);
        return "rgba(" + rgb[0] + ", " + rgb[1] + ", " + rgb[2] + ", "
                + String.format(java.util.Locale.ROOT, "%.2f", random.nextDouble()) + ")";
    }

    /**
     * 随机颜色，格式 {@code hsl(h, s, l)}。
     *
     * @return 颜色字符串
     */
    public String hsl() {
        double[] hsv = goldenRatioColor();
        double[] hsl = hsv2hsl(hsv[0], hsv[1], hsv[2]);
        return "hsl(" + (int) hsl[0] + ", " + (int) hsl[1] + ", " + (int) hsl[2] + ")";
    }

    // ------------------------------------------------------------------- Text

    /**
     * 随机英文段落，默认 3~7 句。
     *
     * @param args 可选 {@code (min, max)} 指定句数
     * @return 段落
     */
    public String paragraph(Object... args) {
        int len = rangeCount(args, 3, 7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(sentence());
        }
        return sb.toString();
    }

    /**
     * 随机英文句子，默认 12~18 个词，首字母大写并以 {@code .} 结尾。
     *
     * @param args 可选 {@code (min, max)} 指定词数
     * @return 句子
     */
    public String sentence(Object... args) {
        int len = rangeCount(args, 12, 18);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(word());
        }
        return capitalizeWord(sb.toString()) + '.';
    }

    /**
     * 随机英文单词，默认 3~10 个小写字母。
     *
     * @param args 可选 {@code (min, max)} 指定长度
     * @return 单词
     */
    public String word(Object... args) {
        int len = rangeCount(args, 3, 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(character("lower"));
        }
        return sb.toString();
    }

    /**
     * 随机英文标题，默认 3~7 个词，每个词首字母大写。
     *
     * @param args 可选 {@code (min, max)} 指定词数
     * @return 标题
     */
    public String title(Object... args) {
        int len = rangeCount(args, 3, 7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(capitalizeWord(word()));
        }
        return sb.toString();
    }

    /**
     * 随机中文段落，默认 3~7 句。
     *
     * @param args 可选 {@code (min, max)} 指定句数
     * @return 段落
     */
    public String cparagraph(Object... args) {
        int len = rangeCount(args, 3, 7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(csentence());
        }
        return sb.toString();
    }

    /**
     * 随机中文句子，默认 12~18 个汉字，以 {@code 。} 结尾。
     *
     * @param args 可选 {@code (min, max)} 指定字数
     * @return 句子
     */
    public String csentence(Object... args) {
        int len = rangeCount(args, 12, 18);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(cword());
        }
        return sb.append('。').toString();
    }

    /**
     * 随机汉字。签名随参数个数变化：{@code ()} 单字；{@code (len)} 定长；
     * {@code (pool, len)} 指定字池；{@code (pool, min, max)} 指定长度区间。
     *
     * @param args 可选 {@code (pool, min, max)}
     * @return 汉字串
     */
    public String cword(Object... args) {
        String pool = MockDict.KANZI;
        int len = 1;
        switch (args.length) {
            case 1:
                if (args[0] instanceof String) {
                    pool = String.valueOf(args[0]);
                } else {
                    len = argInt(args, 0, 1);
                }
                break;
            case 2:
                if (args[0] instanceof String) {
                    pool = String.valueOf(args[0]);
                    len = argInt(args, 1, 1);
                } else {
                    len = (int) natural(argInt(args, 0, 0), argInt(args, 1, 0));
                }
                break;
            case 3:
                pool = String.valueOf(args[0]);
                len = (int) natural(argInt(args, 1, 0), argInt(args, 2, 0));
                break;
            default:
                break;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && !pool.isEmpty(); i++) {
            sb.append(pool.charAt((int) natural(0, pool.length() - 1)));
        }
        return sb.toString();
    }

    /**
     * 随机中文标题，默认 3~7 个汉字。
     *
     * @param args 可选 {@code (min, max)} 指定字数
     * @return 标题
     */
    public String ctitle(Object... args) {
        int len = rangeCount(args, 3, 7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(cword());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- Name

    /**
     * 随机英文名字（名）。
     *
     * @return 名字
     */
    public String first() {
        return pickOne(MockDict.EN_FIRST);
    }

    /**
     * 随机英文姓氏。
     *
     * @return 姓氏
     */
    public String last() {
        return pickOne(MockDict.EN_LAST);
    }

    /**
     * 随机英文姓名。
     *
     * @param args 可选 {@code (middle)}，为 true 时带中间名
     * @return 姓名
     */
    public String name(Object... args) {
        boolean middle = argBool(args, 0, false);
        if (middle) {
            return first() + ' ' + first() + ' ' + last();
        }
        return first() + ' ' + last();
    }

    /**
     * 随机中文姓氏。
     *
     * @return 姓氏
     */
    public String cfirst() {
        return pickOne(MockDict.CN_FIRST);
    }

    /**
     * 随机中文名。
     *
     * @return 名
     */
    public String clast() {
        return pickOne(MockDict.CN_LAST);
    }

    /**
     * 随机中文姓名。
     *
     * @return 姓名
     */
    public String cname() {
        return cfirst() + clast();
    }

    // -------------------------------------------------------------------- Web

    /**
     * 随机 URL。
     *
     * @param args 可选 {@code (protocol, host)}
     * @return URL
     */
    public String url(Object... args) {
        String protocol = argStr(args, 0, null);
        String host = argStr(args, 1, null);
        if (protocol == null || protocol.isEmpty()) {
            protocol = this.protocol();
        }
        if (host == null || host.isEmpty()) {
            host = domain();
        }
        return protocol + "://" + host + "/" + word();
    }

    /**
     * 随机域名。
     *
     * @param args 可选 {@code (tld)}
     * @return 域名
     */
    public String domain(Object... args) {
        String tld = argStr(args, 0, null);
        return word() + '.' + (tld == null || tld.isEmpty() ? this.tld() : tld);
    }

    /**
     * 随机 URL 协议。
     *
     * @return 协议
     */
    public String protocol() {
        return pickOne(MockDict.PROTOCOLS);
    }

    /**
     * 随机顶级域名。
     *
     * @return 顶级域名
     */
    public String tld() {
        return pickOne(MockDict.TLDS);
    }

    /**
     * 随机邮箱地址。
     *
     * @param args 可选 {@code (domain)}
     * @return 邮箱
     */
    public String email(Object... args) {
        String domain = argStr(args, 0, null);
        if (domain == null || domain.isEmpty()) {
            domain = word() + '.' + tld();
        }
        return character("lower") + '.' + word() + '@' + domain;
    }

    /**
     * 随机 IP 地址。
     *
     * @return IP
     */
    public String ip() {
        return natural(0, 255) + "." + natural(0, 255) + "." + natural(0, 255) + "."
                + natural(0, 255);
    }

    // ---------------------------------------------------------------- Address

    /**
     * 随机大区（东北 / 华北 / …）。
     *
     * @return 大区
     */
    public String region() {
        return pickOne(MockDict.REGIONS);
    }

    /**
     * 随机省（含直辖市、自治区、特别行政区）。
     *
     * @return 省名
     */
    public String province() {
        return pickProvince().getName();
    }

    /**
     * 随机市。
     *
     * @param args 可选 {@code (prefix)}，为 true 时带省名前缀
     * @return 市名
     */
    public String city(Object... args) {
        boolean prefix = argBool(args, 0, false);
        MockDict.Region p = pickProvince();
        String city = pickChild(p, "其它区");
        return prefix ? p.getName() + ' ' + city : city;
    }

    /**
     * 随机县（区）。
     *
     * @param args 可选 {@code (prefix)}，为 true 时带省市前缀
     * @return 县名
     */
    public String county(Object... args) {
        boolean prefix = argBool(args, 0, false);
        MockDict.Region p = pickProvince();
        MockDict.Region c = pickOne(p.getChildren());
        String county = c.getChildren().isEmpty() ? "-" : pickChild(c, "-");
        return prefix ? p.getName() + ' ' + c.getName() + ' ' + county : county;
    }

    /**
     * 随机邮政编码，默认 6 位。
     *
     * @param args 可选 {@code (len)}
     * @return 邮编
     */
    public String zip(Object... args) {
        int len = argInt(args, 0, 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(natural(0, 9));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- Helper

    /**
     * 首字母大写。
     *
     * @param args {@code (word)}
     * @return 结果
     */
    public String capitalize(Object... args) {
        return capitalizeWord(argStr(args, 0, ""));
    }

    /**
     * 转大写。
     *
     * @param args {@code (str)}
     * @return 结果
     */
    public String upper(Object... args) {
        String s = argStr(args, 0, "");
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 转小写。
     *
     * @param args {@code (str)}
     * @return 结果
     */
    public String lower(Object... args) {
        String s = argStr(args, 0, "");
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 从数组随机选取。{@code (arr)} 取一个；{@code (arr, count)} 或
     * {@code (arr, min, max)} 取多个（不重复）。
     *
     * @param args {@code (arr[, min[, max]])}
     * @return 单个元素或元素列表
     */
    public Object pick(Object... args) {
        List<Object> arr = argList(args, 0);
        if (arr.isEmpty()) {
            return null;
        }
        int min = argInt(args, 1, 1);
        int max = argInt(args, 2, min);
        if (min == 1 && max == 1) {
            return arr.get((int) natural(0, arr.size() - 1));
        }
        return shuffle(arr, min, max);
    }

    /**
     * 打乱数组。{@code (arr)} 返回全排列；带 {@code min} / {@code max} 时截取前 N 个。
     *
     * @param args {@code (arr[, min[, max]])}
     * @return 打乱后的列表
     */
    public List<Object> shuffle(Object... args) {
        List<Object> arr = argList(args, 0);
        List<Object> pool = new ArrayList<Object>(arr);
        List<Object> result = new ArrayList<Object>();
        while (!pool.isEmpty()) {
            result.add(pool.remove((int) natural(0, pool.size() - 1)));
        }
        if (args.length <= 1) {
            return result;
        }
        int min = argInt(args, 1, 1);
        int max = args.length >= 3 ? argInt(args, 2, min) : min;
        int end = (int) natural(min, max);
        if (end < 0) {
            end = 0;
        }
        if (end > result.size()) {
            end = result.size();
        }
        return new ArrayList<Object>(result.subList(0, end));
    }

    // ----------------------------------------------------------- Miscellaneous

    /**
     * 随机 GUID。
     *
     * @return GUID
     */
    public String guid() {
        return string(GUID_POOL, 8) + '-' + string(GUID_POOL, 4) + '-' + string(GUID_POOL, 4)
                + '-' + string(GUID_POOL, 4) + '-' + string(GUID_POOL, 12);
    }

    /**
     * 随机 UUID（Mock.js 中与 GUID 同实现）。
     *
     * @return UUID
     */
    public String uuid() {
        return guid();
    }

    /**
     * 随机 18 位身份证号，带 GB 11643 校验位。
     *
     * @return 身份证号
     */
    public String id() {
        int[] rank = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String[] last = {"1", "0", "X", "9", "8", "7", "6", "5", "4", "3", "2"};
        String body = pickOne(MockDict.PROVINCE_CODES) + date("yyyyMMdd") + string("number", 3);
        long sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += (body.charAt(i) - '0') * rank[i];
        }
        return body + last[(int) (sum % 11)];
    }

    /**
     * 自增整数，步长缺省为 1，同一实例内连续。
     *
     * @param args 可选 {@code (step)}
     * @return 自增值
     */
    public long increment(Object... args) {
        long step = argLong(args, 0, 1);
        if (step == 0) {
            step = 1;
        }
        return incrementKey.addAndGet(step);
    }

    /**
     * 重置自增计数器。
     */
    public void resetIncrement() {
        incrementKey.set(0);
    }

    // --------------------------------------------------- 业务字段（与字段模式共用语料）

    /**
     * 中国大陆手机号：{@code 1[3-9]} 前缀 + 8 位数字。
     *
     * @param args 忽略
     * @return 11 位手机号
     */
    public String phone(Object... args) {
        return pickOne(MockDict.PHONE_PREFIXES) + string("number", 8);
    }

    /**
     * 性别：{@code 男} 或 {@code 女}。
     *
     * @param args 忽略
     * @return 性别
     */
    public String gender(Object... args) {
        return random.nextDouble() > 0.5 ? "男" : "女";
    }

    /**
     * 公司名：前缀 + 后缀，如 {@code 腾讯科技有限公司}。
     *
     * @param args 忽略
     * @return 公司名
     */
    public String company(Object... args) {
        return pickOne(MockDict.COMPANY_PREFIXES) + pickOne(MockDict.COMPANY_SUFFIXES);
    }

    /**
     * 部门名，如 {@code 技术部}。
     *
     * @param args 忽略
     * @return 部门名
     */
    public String department(Object... args) {
        return pickOne(MockDict.DEPARTMENTS);
    }

    /**
     * 职位名，如 {@code 后端工程师}。
     *
     * @param args 忽略
     * @return 职位名
     */
    public String position(Object... args) {
        return pickOne(MockDict.POSITIONS);
    }

    /**
     * 月薪，5000 到 50000。
     *
     * @param args 可选 {@code (min, max)}
     * @return 月薪
     */
    public long salary(Object... args) {
        int min = argInt(args, 0, 5000);
        int max = args.length > 1 ? argInt(args, 1, 50000) : 50000;
        return integer(min, max);
    }

    /**
     * 银行卡号：前缀 + 12 位数字。
     *
     * @param args 忽略
     * @return 银行卡号
     */
    public String bankCard(Object... args) {
        return pickOne(MockDict.BANK_CARD_PREFIXES) + string("number", 12);
    }

    /**
     * 信用卡号：前缀 + 15 位数字。
     *
     * @param args 忽略
     * @return 信用卡号
     */
    public String creditCard(Object... args) {
        return pickOne(MockDict.CREDIT_CARD_PREFIXES) + string("number", 15);
    }

    /**
     * 货币代码，如 {@code CNY}。
     *
     * @param args 忽略
     * @return 货币代码
     */
    public String currency(Object... args) {
        return pickOne(MockDict.CURRENCIES);
    }

    /**
     * MAC 地址，冒号分隔的 6 组十六进制。
     *
     * @param args 忽略
     * @return MAC 地址
     */
    public String mac(Object... args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(character("hex")).append(character("hex"));
        }
        return sb.toString().toUpperCase();
    }

    /**
     * 浏览器 User-Agent。
     *
     * @param args 忽略
     * @return UA 字符串
     */
    public String userAgent(Object... args) {
        return pickOne(MockDict.USER_AGENTS);
    }

    /**
     * 密码：字母数字符号混合，默认 12 位。
     *
     * @param args 可选 {@code (length)} 或 {@code (min, max)}
     * @return 密码
     */
    public String password(Object... args) {
        int len = args.length > 1 ? (int) integer(argInt(args, 0, 8), argInt(args, 1, 16))
                : argInt(args, 0, 12);
        return string("undefined", Math.max(len, 1));
    }

    /**
     * 令牌：32 位十六进制。
     *
     * @param args 可选 {@code (length)}
     * @return 令牌
     */
    public String token(Object... args) {
        return string("hex", argInt(args, 0, 32));
    }

    /**
     * Unix 时间戳（毫秒），默认取当前时间，给参数则在距今一年内随机。
     *
     * @param args 传 {@code true} 时取当前时间，否则随机
     * @return 时间戳
     */
    public long timestamp(Object... args) {
        if (args.length > 0 && argBool(args, 0, true)) {
            return System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        long year = 365L * 24 * 60 * 60 * 1000;
        return now - (long) (random.nextDouble() * year);
    }

    /**
     * 文件名：随机名 + 扩展名，如 {@code report_x8k2.pdf}。
     *
     * @param args 忽略
     * @return 文件名
     */
    public String fileName(Object... args) {
        return string("lower", 8) + "." + pickOne(MockDict.FILE_EXTENSIONS);
    }

    /**
     * MIME 类型，如 {@code application/json}。
     *
     * @param args 忽略
     * @return MIME 类型
     */
    public String mime(Object... args) {
        return pickOne(MockDict.MIME_TYPES);
    }

    // ------------------------------------------------------------- 内部工具

    private String poolOf(Object pool) {
        if (pool == null) {
            return POOLS.get("undefined");
        }
        String key = String.valueOf(pool).toLowerCase(java.util.Locale.ROOT);
        String value = POOLS.get(key);
        return value == null ? String.valueOf(pool) : value;
    }

    private double[] goldenRatioColor() {
        if (hue < 0) {
            hue = random.nextDouble();
        }
        hue = (hue + 0.618033988749895) % 1;
        return new double[]{hue * 360, 50, 95};
    }

    private static int[] hsv2rgb(double h, double s, double v) {
        double hs = h / 60;
        double ss = s / 100;
        double vv = v / 100;
        int hi = (int) Math.floor(hs) % 6;
        double f = hs - Math.floor(hs);
        double p = vv * (1 - ss);
        double q = vv * (1 - f * ss);
        double t = vv * (1 - (1 - f) * ss);
        double r;
        double g;
        double b;
        switch (hi) {
            case 0:
                r = vv;
                g = t;
                b = p;
                break;
            case 1:
                r = q;
                g = vv;
                b = p;
                break;
            case 2:
                r = p;
                g = vv;
                b = t;
                break;
            case 3:
                r = p;
                g = q;
                b = vv;
                break;
            case 4:
                r = t;
                g = p;
                b = vv;
                break;
            default:
                r = vv;
                g = p;
                b = q;
                break;
        }
        return new int[]{(int) Math.round(r * 255), (int) Math.round(g * 255),
                (int) Math.round(b * 255)};
    }

    private static double[] hsv2hsl(double h, double s, double v) {
        double vv = v / 100;
        double ss = s / 100;
        double l = vv * (1 - ss / 2);
        double sl = (l == 0 || l == 1) ? 0 : (vv - l) / Math.min(l, 1 - l);
        return new double[]{h, sl * 100, l * 100};
    }

    private static String rgb2hex(int r, int g, int b) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    private static String strip(String color) {
        if (color == null) {
            return null;
        }
        return color.startsWith("#") ? color.substring(1) : color;
    }

    private static int[] parseSize(String size) {
        String[] parts = size.split("[xX]");
        int w = 300;
        int h = 250;
        if (parts.length == 2) {
            w = safeInt(parts[0], w);
            h = safeInt(parts[1], h);
        }
        return new int[]{w, h};
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else if (c == '\'') {
                sb.append("&apos;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String capitalizeWord(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private Date randomDate() {
        return new Date((long) (random.nextDouble() * System.currentTimeMillis()));
    }

    private String formatDate(Date date, String format) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            String token = null;
            if (i + 4 <= format.length() && "yyyy".equals(format.substring(i, i + 4))) {
                token = "yyyy";
            } else if (i + 2 <= format.length()) {
                String two = format.substring(i, i + 2);
                if (isPattern(two)) {
                    token = two;
                }
            }
            if (token == null) {
                String one = format.substring(i, i + 1);
                if (isPattern(one)) {
                    token = one;
                }
            }
            if (token == null) {
                sb.append(format.charAt(i));
                i++;
            } else {
                sb.append(patternValue(cal, token));
                i += token.length();
            }
        }
        return sb.toString();
    }

    private static boolean isPattern(String token) {
        return "yyyy".equals(token) || "yy".equals(token) || "y".equals(token)
                || "MM".equals(token) || "M".equals(token) || "dd".equals(token)
                || "d".equals(token) || "HH".equals(token) || "H".equals(token)
                || "hh".equals(token) || "h".equals(token) || "mm".equals(token)
                || "m".equals(token) || "ss".equals(token) || "s".equals(token)
                || "SS".equals(token) || "S".equals(token) || "A".equals(token)
                || "a".equals(token) || "T".equals(token);
    }

    private static String patternValue(Calendar cal, String token) {
        if ("yyyy".equals(token) || "yy".equals(token) || "y".equals(token)) {
            String year = String.valueOf(cal.get(Calendar.YEAR));
            return "yyyy".equals(token) ? year : year.substring(year.length() - 2);
        }
        if ("MM".equals(token) || "M".equals(token)) {
            return pad2(cal.get(Calendar.MONTH) + 1, "MM".equals(token));
        }
        if ("dd".equals(token) || "d".equals(token)) {
            return pad2(cal.get(Calendar.DAY_OF_MONTH), "dd".equals(token));
        }
        if ("HH".equals(token) || "H".equals(token)) {
            return pad2(cal.get(Calendar.HOUR_OF_DAY), "HH".equals(token));
        }
        if ("hh".equals(token) || "h".equals(token)) {
            return pad2(cal.get(Calendar.HOUR_OF_DAY) % 12, "hh".equals(token));
        }
        if ("mm".equals(token) || "m".equals(token)) {
            return pad2(cal.get(Calendar.MINUTE), "mm".equals(token));
        }
        if ("ss".equals(token) || "s".equals(token)) {
            return pad2(cal.get(Calendar.SECOND), "ss".equals(token));
        }
        if ("SS".equals(token) || "S".equals(token)) {
            return "SS".equals(token)
                    ? String.format(java.util.Locale.ROOT, "%03d", cal.get(Calendar.MILLISECOND))
                    : String.valueOf(cal.get(Calendar.MILLISECOND));
        }
        if ("A".equals(token) || "a".equals(token)) {
            boolean am = cal.get(Calendar.HOUR_OF_DAY) < 12;
            return "A".equals(token) ? (am ? "AM" : "PM") : (am ? "am" : "pm");
        }
        return String.valueOf(cal.getTimeInMillis());
    }

    private static String pad2(int value, boolean pad) {
        return pad ? String.format(java.util.Locale.ROOT, "%02d", value) : String.valueOf(value);
    }

    private int rangeCount(Object[] args, int defMin, int defMax) {
        if (args.length == 0) {
            return (int) natural(defMin, defMax);
        }
        if (args.length == 1) {
            return argInt(args, 0, defMin);
        }
        return (int) natural(argInt(args, 0, defMin), argInt(args, 1, defMax));
    }

    private MockDict.Region pickProvince() {
        return pickOne(MockDict.regionTree());
    }

    private String pickChild(MockDict.Region region, String def) {
        if (region.getChildren().isEmpty()) {
            return def;
        }
        return pickOne(region.getChildren()).getName();
    }

    private <T> T pickOne(List<T> list) {
        return list.get((int) natural(0, list.size() - 1));
    }

    private <T> T pickOne(T[] array) {
        return array[(int) natural(0, array.length - 1)];
    }

    private static Object[] slice(Object[] args, int from, int to) {
        int end = Math.min(to, args.length);
        Object[] out = new Object[Math.max(0, end - from)];
        System.arraycopy(args, from, out, 0, out.length);
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(Math.min(value, max), min);
    }

    private static boolean argBool(Object[] args, int index, boolean def) {
        if (index >= args.length || args[index] == null) {
            return def;
        }
        Object v = args[index];
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int argInt(Object[] args, int index, int def) {
        if (index >= args.length || args[index] == null) {
            return def;
        }
        return (int) toLong(args[index], def);
    }

    private static long argLong(Object[] args, int index, long def) {
        if (index >= args.length || args[index] == null) {
            return def;
        }
        return toLong(args[index], def);
    }

    private static long toLong(Object value, long def) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                return def;
            }
        }
    }

    private static String argStr(Object[] args, int index, String def) {
        if (index >= args.length || args[index] == null) {
            return def;
        }
        return String.valueOf(args[index]);
    }

    private static List<Object> argList(Object[] args, int index) {
        if (index >= args.length || args[index] == null) {
            return new ArrayList<Object>();
        }
        Object v = args[index];
        if (v instanceof List) {
            return new ArrayList<Object>((List<?>) v);
        }
        if (v instanceof Object[]) {
            return new ArrayList<Object>(Arrays.asList((Object[]) v));
        }
        List<Object> out = new ArrayList<Object>();
        out.add(v);
        return out;
    }
}
