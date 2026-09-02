package com.alianga.jkit.collection;

import com.alianga.jkit.Assert;
import com.alianga.jkit.valid.Preconditions;

import java.io.PrintStream;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Map 相关的静态工具方法集合。
 *
 * <p>本类提供常用 Map 实现的创建方法、空值安全的类型转换、Map 与其他对象的转换、
 * 格式化输出以及 Map 查询辅助方法。除特别说明外，方法不会修改传入的 Map。</p>
 *
 * <p>本类仅用于调用静态方法，不能被实例化。</p>
 *
 * @author 郑明亮
 * @since 1.3.3
 */
public class Maps {
    /**
     * HashMap 和 LinkedHashMap 使用的默认加载因子。
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * 不可修改的空 Map。
     */
    public static final Map EMPTY_MAP = Collections.emptyMap();

    /**
     * 格式化输出时使用的缩进字符串。
     */
    private static final String INDENT_STRING = "    ";

    /**
     * 禁止实例化。
     */
    private Maps() {
    }

    /**
     * 创建一个可变的空 HashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新创建的空 HashMap
     */
    public static <K, V> HashMap<K, V> newHashMap() {
        return new HashMap<>();
    }

    /**
     * 创建一个可变的 HashMap，并复制指定 Map 中的所有映射。
     *
     * @param map 要复制的映射
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 包含指定映射的新 HashMap
     */
    public static <K, V> HashMap<K, V> newHashMap(Map<? extends K, ? extends V> map) {
        return new HashMap<>(map);
    }

    /**
     * 创建一个预计可容纳指定条目数且无需扩容的 HashMap。
     *
     * @param expectedSize 预计添加的条目数
     * @param <K>          键类型
     * @param <V>          值类型
     * @return 预留了适当容量的空 HashMap
     * @throws IllegalArgumentException expectedSize 为负数时抛出
     */
    public static <K, V> HashMap<K, V> newHashMapWithExpectedSize(int expectedSize) {
        return new HashMap<>(capacity(expectedSize), DEFAULT_LOAD_FACTOR);
    }

    /**
     * 创建一个可变的、按插入顺序迭代的空 LinkedHashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新创建的空 LinkedHashMap
     */
    public static <K, V> LinkedHashMap<K, V> newLinkedHashMap() {
        return new LinkedHashMap<>();
    }

    /**
     * 创建一个可变的 LinkedHashMap，并复制指定 Map 中的所有映射。
     *
     * @param map 要复制的映射
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 包含指定映射的新 LinkedHashMap
     */
    public static <K, V> LinkedHashMap<K, V> newLinkedHashMap(Map<? extends K, ? extends V> map) {
        return new LinkedHashMap<>(map);
    }

    /**
     * 创建一个预计可容纳指定条目数且无需扩容的 LinkedHashMap。
     *
     * @param expectedSize 预计添加的条目数
     * @param <K>          键类型
     * @param <V>          值类型
     * @return 预留了适当容量的空 LinkedHashMap
     * @throws IllegalArgumentException expectedSize 为负数时抛出
     * @since 19.0
     */
    public static <K, V> LinkedHashMap<K, V> newLinkedHashMapWithExpectedSize(int expectedSize) {
        return new LinkedHashMap<>(expectedSize);
    }

    /**
     * 创建一个空的 ConcurrentHashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新创建的空 ConcurrentHashMap
     */
    public static <K, V> ConcurrentMap<K, V> newConcurrentMap() {
        return new ConcurrentHashMap<>();
    }

    /**
     * 创建一个按键的自然顺序排序的空 TreeMap。
     *
     * @param <K> 键类型，必须实现 Comparable
     * @param <V> 值类型
     * @return 新创建的空 TreeMap
     */
    public static <K extends Comparable, V> TreeMap<K, V> newTreeMap() {
        return new TreeMap<>();
    }

    /**
     * 根据指定有序 Map 的映射和比较器创建 TreeMap。
     *
     * @param map 要复制的有序 Map
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新 TreeMap
     */
    public static <K, V> TreeMap<K, V> newTreeMap(SortedMap<K, ? extends V> map) {
        return new TreeMap<>(map);
    }

    /**
     * 使用指定比较器创建空 TreeMap。
     *
     * @param comparator 键比较器；为 null 时使用自然顺序
     * @param <C>        比较器接受的类型
     * @param <K>        键类型
     * @param <V>        值类型
     * @return 新 TreeMap
     */
    public static <C, K extends C, V> TreeMap<K, V> newTreeMap(Comparator<C> comparator) {
        return new TreeMap<>(comparator);
    }

    /**
     * 使用枚举类型创建空 EnumMap。
     *
     * @param type 枚举键类型
     * @param <K>  枚举键类型
     * @param <V>  值类型
     * @return 新 EnumMap
     * @throws NullPointerException type 为 null 时抛出
     */
    public static <K extends Enum<K>, V> EnumMap<K, V> newEnumMap(Class<K> type) {
        return new EnumMap<>(Preconditions.checkNotNull(type));
    }

    /**
     * 根据指定 Map 创建 EnumMap。
     *
     * @param map 要复制的 Map
     * @param <K> 枚举键类型
     * @param <V> 值类型
     * @return 新 EnumMap
     * @throws IllegalArgumentException map 不是 EnumMap 且为空时无法推断键类型
     */
    public static <K extends Enum<K>, V> EnumMap<K, V> newEnumMap(Map<K, ? extends V> map) {
        return new EnumMap<>(map);
    }

    /**
     * 创建一个使用对象身份而非 equals 比较键的空 IdentityHashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新创建的空 IdentityHashMap
     */
    public static <K, V> IdentityHashMap<K, V> newIdentityHashMap() {
        return new IdentityHashMap<>();
    }

    /**
     * 从 Map 中以空值安全方式获取原始对象。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 键对应的值，Map 为 null 或键不存在时返回 null
     */
    public static Object getObject(final Map map, final Object key) {
        return map == null ? null : map.get(key);
    }

    /**
     * 从 Map 中获取值并转换为字符串；非 null 值使用其 toString() 结果。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的字符串，Map、键或值不存在时返回 null
     */
    public static String getString(final Map map, final Object key) {
        Object answer = map == null ? null : map.get(key);
        return answer == null ? null : answer.toString();
    }

    /**
     * 从 Map 中获取布尔值。Boolean 原样返回，String 按 Boolean.valueOf 解析，数字 0 为 false、非 0 为 true。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Boolean，无法转换或 Map 为空时返回 null
     */
    public static Boolean getBoolean(final Map map, final Object key) {
        Object answer = map == null ? null : map.get(key);
        if (answer instanceof Boolean) {
            return (Boolean) answer;
        }
        if (answer instanceof String) {
            return Boolean.valueOf((String) answer);
        }
        if (answer instanceof Number) {
            return ((Number) answer).intValue() != 0;
        }
        return null;
    }

    /**
     * 从 Map 中获取 Number；字符串使用系统默认 NumberFormat 解析。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Number，值既不是数字也不是可解析的字符串时返回 null
     */
    public static Number getNumber(final Map map, final Object key) {
        Object answer = map == null ? null : map.get(key);
        if (answer instanceof Number) {
            return (Number) answer;
        }
        if (answer instanceof String) {
            try {
                return NumberFormat.getInstance().parse((String) answer);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    /**
     * 获取 Byte，无法转换时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Byte，值无法转换为数字时返回 null
     */
    public static Byte getByte(final Map map, final Object key) {
        Number answer = getNumber(map, key);
        return answer == null ? null : answer instanceof Byte ? (Byte) answer : Byte.valueOf(answer.byteValue());
    }

    /**
     * 获取 Short，无法转换时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Short，值无法转换为数字时返回 null
     */
    public static Short getShort(final Map map, final Object key) {
        Number answer = getNumber(map, key);
        return answer == null ? null : answer instanceof Short ? (Short) answer : Short.valueOf(answer.shortValue());
    }

    /**
     * 获取 Integer，无法转换时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Integer，值无法转换为数字时返回 null
     */
    public static Integer getInteger(final Map map, final Object key) {
        Number answer = getNumber(map, key);
        return answer == null ? null :
                answer instanceof Integer ? (Integer) answer : Integer.valueOf(answer.intValue());
    }

    /**
     * 获取 Long，无法转换时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Long，值无法转换为数字时返回 null
     */
    public static Long getLong(final Map map, final Object key) {
        Number answer = getNumber(map, key);
        return answer == null ? null : answer instanceof Long ? (Long) answer : Long.valueOf(answer.longValue());
    }

    /**
     * 获取 Float，无法转换时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Float，值无法转换为数字时返回 null
     */
    public static Float getFloat(final Map map, final Object key) {
        Number answer = getNumber(map, key);
        return answer == null ? null : answer instanceof Float ? (Float) answer : Float.valueOf(answer.floatValue());
    }

    /**
     * 获取 Double，无法转换时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 Double，值无法转换为数字时返回 null
     */
    public static Double getDouble(final Map map, final Object key) {
        Number answer = getNumber(map, key);
        return answer == null ? null :
                answer instanceof Double ? (Double) answer : Double.valueOf(answer.doubleValue());
    }

    /**
     * 获取嵌套 Map；值不是 Map 或输入 Map 为空时返回 null。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 键对应的 Map，值不是 Map 实例时返回 null
     */
    public static Map getMap(final Map map, final Object key) {
        Object answer = map == null ? null : map.get(key);
        return answer instanceof Map ? (Map) answer : null;
    }

    /**
     * 获取对象；值不存在或 Map 为空时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 取不到值时返回的默认值
     * @return 键对应的值，取到的值为 null 时返回 defaultValue
     */
    public static Object getObject(Map map, Object key, Object defaultValue) {
        Object answer = getObject(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取字符串；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 取不到值时返回的默认值
     * @return 转换后的字符串，值不存在时返回 defaultValue
     */
    public static String getString(Map map, Object key, String defaultValue) {
        String answer = getString(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Boolean；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Boolean，无法转换时返回 defaultValue
     */
    public static Boolean getBoolean(Map map, Object key, Boolean defaultValue) {
        Boolean answer = getBoolean(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Number；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Number，无法转换时返回 defaultValue
     */
    public static Number getNumber(Map map, Object key, Number defaultValue) {
        Number answer = getNumber(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Byte；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Byte，无法转换时返回 defaultValue
     */
    public static Byte getByte(Map map, Object key, Byte defaultValue) {
        Byte answer = getByte(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Short；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Short，无法转换时返回 defaultValue
     */
    public static Short getShort(Map map, Object key, Short defaultValue) {
        Short answer = getShort(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Integer；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Integer，无法转换时返回 defaultValue
     */
    public static Integer getInteger(Map map, Object key, Integer defaultValue) {
        Integer answer = getInteger(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Long；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Long，无法转换时返回 defaultValue
     */
    public static Long getLong(Map map, Object key, Long defaultValue) {
        Long answer = getLong(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Float；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Float，无法转换时返回 defaultValue
     */
    public static Float getFloat(Map map, Object key, Float defaultValue) {
        Float answer = getFloat(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取 Double；无法转换时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 Double，无法转换时返回 defaultValue
     */
    public static Double getDouble(Map map, Object key, Double defaultValue) {
        Double answer = getDouble(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取嵌套 Map；不存在或类型不符时返回默认值。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 取不到 Map 时返回的默认值
     * @return 键对应的 Map，值不是 Map 实例时返回 defaultValue
     */
    public static Map getMap(Map map, Object key, Map defaultValue) {
        Map answer = getMap(map, key);
        return answer == null ? defaultValue : answer;
    }

    /**
     * 获取布尔基本类型；无法转换时返回 false。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 值可转换为真时返回 {@code true}，无法转换或为假时返回 {@code false}
     */
    public static boolean getBooleanValue(final Map map, final Object key) {
        Boolean value = getBoolean(map, key);
        return value != null && value;
    }

    /**
     * 获取 byte 基本类型；无法转换时返回 0。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 byte 值，无法转换时返回 0
     */
    public static byte getByteValue(final Map map, final Object key) {
        Byte value = getByte(map, key);
        return value == null ? 0 : value;
    }

    /**
     * 获取 short 基本类型；无法转换时返回 0。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 short 值，无法转换时返回 0
     */
    public static short getShortValue(final Map map, final Object key) {
        Short value = getShort(map, key);
        return value == null ? 0 : value;
    }

    /**
     * 获取 int 基本类型；无法转换时返回 0。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 int 值，无法转换时返回 0
     */
    public static int getIntValue(final Map map, final Object key) {
        Integer value = getInteger(map, key);
        return value == null ? 0 : value;
    }

    /**
     * 获取 long 基本类型；无法转换时返回 0L。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 long 值，无法转换时返回 0L
     */
    public static long getLongValue(final Map map, final Object key) {
        Long value = getLong(map, key);
        return value == null ? 0L : value;
    }

    /**
     * 获取 float 基本类型；无法转换时返回 0F。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 float 值，无法转换时返回 0F
     */
    public static float getFloatValue(final Map map, final Object key) {
        Float value = getFloat(map, key);
        return value == null ? 0F : value;
    }

    /**
     * 获取 double 基本类型；无法转换时返回 0D。
     *
     * @param map 数据来源 Map，可为 null
     * @param key 要查询的键
     * @return 转换后的 double 值，无法转换时返回 0D
     */
    public static double getDoubleValue(final Map map, final Object key) {
        Double value = getDouble(map, key);
        return value == null ? 0D : value;
    }

    /**
     * 获取布尔基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 boolean 值，无法转换时返回 defaultValue
     */
    public static boolean getBooleanValue(final Map map, final Object key, boolean defaultValue) {
        Boolean value = getBoolean(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取 byte 基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 byte 值，无法转换时返回 defaultValue
     */
    public static byte getByteValue(final Map map, final Object key, byte defaultValue) {
        Byte value = getByte(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取 short 基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 short 值，无法转换时返回 defaultValue
     */
    public static short getShortValue(final Map map, final Object key, short defaultValue) {
        Short value = getShort(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取 int 基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 int 值，无法转换时返回 defaultValue
     */
    public static int getIntValue(final Map map, final Object key, int defaultValue) {
        Integer value = getInteger(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取 long 基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 long 值，无法转换时返回 defaultValue
     */
    public static long getLongValue(final Map map, final Object key, long defaultValue) {
        Long value = getLong(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取 float 基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 float 值，无法转换时返回 defaultValue
     */
    public static float getFloatValue(final Map map, final Object key, float defaultValue) {
        Float value = getFloat(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 获取 double 基本类型；无法转换时返回 defaultValue。
     *
     * @param map          数据来源 Map，可为 null
     * @param key          要查询的键
     * @param defaultValue 无法转换时返回的默认值
     * @return 转换后的 double 值，无法转换时返回 defaultValue
     */
    public static double getDoubleValue(final Map map, final Object key, double defaultValue) {
        Double value = getDouble(map, key);
        return value == null ? defaultValue : value;
    }

    /**
     * 将 Map 的键值复制到新的 Properties；输入为 null 时返回空 Properties。
     *
     * @param map 要复制的 Map，可为 null
     * @return 包含 Map 中全部键值对的新 Properties
     */
    public static Properties toProperties(final Map map) {
        Properties answer = new Properties();
        if (map != null) {
            for (Object item : map.entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                answer.put(entry.getKey(), entry.getValue());
            }
        }
        return answer;
    }

    /**
     * 将 ResourceBundle 的键值复制到新的 HashMap。
     *
     * @param resourceBundle 要转换的资源包，不得为 null
     * @return 包含资源包中全部键与对应对象的新 HashMap
     * @throws NullPointerException resourceBundle 为 null 时抛出
     */
    public static Map toMap(final ResourceBundle resourceBundle) {
        Enumeration enumeration = resourceBundle.getKeys();
        Map map = new HashMap();
        while (enumeration.hasMoreElements()) {
            String key = (String) enumeration.nextElement();
            map.put(key, resourceBundle.getObject(key));
        }
        return map;
    }

    /**
     * 以易读的多行格式输出 Map；嵌套 Map 会递归输出，并标记循环引用。
     *
     * @param out   输出流，不得为 null
     * @param label 输出标签，可为 null
     * @param map   要输出的 Map，可为 null
     * @throws NullPointerException out 为 null 时抛出
     */
    public static void verbosePrint(final PrintStream out, final Object label, final Map map) {
        verbosePrintInternal(out, label, map, new ArrayStack(), false);
    }

    /**
     * 以易读的多行格式输出 Map，并额外输出值的类型名称。
     *
     * @param out   输出流，不得为 null
     * @param label 输出标签，可为 null
     * @param map   要输出的 Map，可为 null
     */
    public static void debugPrint(final PrintStream out, final Object label, final Map map) {
        verbosePrintInternal(out, label, map, new ArrayStack(), true);
    }

    /**
     * 向标准输出记录异常信息，供内部兼容使用。
     *
     * @param ex 要记录的异常
     */
    protected static void logInfo(final Exception ex) {
        System.out.println("INFO: Exception: " + ex);
    }

    /**
     * verbosePrint 和 debugPrint 的递归实现。
     */
    private static void verbosePrintInternal(final PrintStream out, final Object label, final Map map,
                                             final ArrayStack lineage, final boolean debug) {
        printIndent(out, lineage.size());
        if (map == null) {
            if (label != null) {
                out.print(label);
                out.print(" = ");
            }
            out.println("null");
            return;
        }
        if (label != null) {
            out.print(label);
            out.println(" = ");
        }
        printIndent(out, lineage.size());
        out.println("{");
        lineage.push(map);
        for (Iterator it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry entry = (Map.Entry) it.next();
            Object childKey = entry.getKey();
            Object childValue = entry.getValue();
            if (childValue instanceof Map && !lineage.contains(childValue)) {
                verbosePrintInternal(out, childKey == null ? "null" : childKey, (Map) childValue, lineage, debug);
            } else {
                printIndent(out, lineage.size());
                out.print(childKey);
                out.print(" = ");
                int lineageIndex = lineage.indexOf(childValue);
                if (lineageIndex == -1) {
                    out.print(childValue);
                } else if (lineage.size() - 1 == lineageIndex) {
                    out.print("(this Map)");
                } else {
                    out.print("(ancestor[" + (lineage.size() - 1 - lineageIndex - 1) + "] Map)");
                }
                if (debug && childValue != null) {
                    out.print(' ');
                    out.println(childValue.getClass().getName());
                } else {
                    out.println();
                }
            }
        }
        lineage.pop();
        printIndent(out, lineage.size());
        out.println(debug ? "} " + map.getClass().getName() : "}");
    }

    /**
     * 向输出流写入指定层数的缩进。
     */
    private static void printIndent(final PrintStream out, final int indent) {
        for (int i = 0; i < indent; i++) {
            out.print(INDENT_STRING);
        }
    }

    /**
     * 反转 Map 的键和值，生成新的 HashMap。
     * <p>当多个键对应同一个值时，结果只保留其中一个键，具体保留哪个键取决于遍历顺序。</p>
     *
     * @param map 要反转的 Map，不得为 null
     * @return 反转后的 Map
     * @throws NullPointerException map 为 null 时抛出
     */
    public static Map invertMap(Map map) {
        Map out = new HashMap(map.size());
        for (Iterator it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry entry = (Map.Entry) it.next();
            out.put(entry.getValue(), entry.getKey());
        }
        return out;
    }

    /**
     * 将 null 值替换为空字符串后放入 Map；键不做校验。
     *
     * @param map   目标 Map，不得为 null
     * @param key   要写入的键
     * @param value 要写入的值，为 null 时写入空字符串
     * @throws NullPointerException map 为 null 时抛出
     */
    public static void safeAddToMap(Map map, Object key, Object value) throws NullPointerException {
        map.put(key, value == null ? "" : value);
    }

    /**
     * 将对象数组中的键值对放入 Map。支持 Map.Entry 数组、二维对象数组以及交替排列的键值数组。
     *
     * @param map   要填充的 Map，不得为 null
     * @param array 数据数组，可为 null
     * @return 传入的 Map
     * @throws NullPointerException     map 为 null 时抛出
     * @throws IllegalArgumentException 二维数组中的元素无效时抛出
     */
    public static Map putAll(Map map, Object[] array) {
        map.size();
        if (array == null || array.length == 0) {
            return map;
        }
        Object obj = array[0];
        if (obj instanceof Map.Entry) {
            for (Object item : array) {
                Map.Entry entry = (Map.Entry) item;
                map.put(entry.getKey(), entry.getValue());
            }
        } else if (obj instanceof Object[]) {
            for (int i = 0; i < array.length; i++) {
                Object[] sub = (Object[]) array[i];
                if (sub == null || sub.length < 2) {
                    throw new IllegalArgumentException("Invalid array element: " + i);
                }
                map.put(sub[0], sub[1]);
            }
        } else {
            for (int i = 0; i < array.length - 1; ) {
                map.put(array[i++], array[i++]);
            }
        }
        return map;
    }

    /**
     * 判断 Map 是否为空；null 也视为空。
     *
     * @param map 要判断的 Map，可为 null
     * @return Map 为 null 或没有任何条目时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isEmpty(Map map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断 Map 是否非空；null 视为非空判断失败。
     *
     * @param map 要判断的 Map，可为 null
     * @return Map 不为 null 且至少包含一个条目时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isNotEmpty(Map map) {
        return !Maps.isEmpty(map);
    }

    /**
     * 根据预计条目数计算 Map 初始容量。
     */
    static int capacity(int expectedSize) {
        Assert.isTrue(expectedSize >= 0, "expectedSize cannot be negative but was: " + expectedSize);
        if (expectedSize < 3) {
            return expectedSize + 1;
        }
        if (expectedSize < 1073741824) {
            return (int) Math.ceil(expectedSize / DEFAULT_LOAD_FACTOR);
        }
        return Integer.MAX_VALUE;
    }
}
