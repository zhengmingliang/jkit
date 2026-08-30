package com.alianga.jkit.beans;

import com.alianga.jkit.exception.TypeNotMatchExecption;
import com.alianga.jkit.json.internal.beans.DateParser;
import com.alianga.jkit.json.internal.beans.GregorianDate;
import com.alianga.jkit.json.internal.utils.CollectionUtils;
import com.alianga.jkit.reflect.*;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用对象操作工具类.
 *
 * <p>提供反射式的对象属性读写、类型转换、多级路径访问等功能。
 * 内部通过 {@link UtilsSecureTrustedAccess} 获得 getter/setter 的安全调用权限，
 * 不依赖任何基类，所有方法均为静态。
 *
 * @time 2019/12/7 11:48
 */
@SuppressWarnings("unchecked")
public final class ObjectUtils {
    private static final UtilsSecureTrustedAccess TRUSTED_ACCESS = new UtilsSecureTrustedAccess();

    /**
     * 判断目标对象中是否包含指定 key（Map容器检查key存在性，JavaBean检查getter）.
     *
     * @param target 目标对象，Map 容器或 JavaBean，不能为 {@code null}
     * @param key 待判断的 key 或属性名，比较时会去除首尾空白
     * @return 存在该 key 或同名 getter 时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean contains(Object target, String key) {
        target.getClass();
        if (target instanceof Map) {
            return ((Map<?, ?>) target).containsKey(key);
        } else {
            ClassStrucWrap classStrucWrap = ClassStrucWrap.get(target.getClass());
            List<GetterInfo> getterInfos = classStrucWrap.getGetterInfos();
            for (GetterInfo getterInfo : getterInfos) {
                if (getterInfo.getName().equals(key.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从上下文中读取表达式 key 的值并转型为指定类型.
     *
     * @param context 上下文对象
     * @param exprKey 属性路径
     * @param clazz 期望类型
     * @param <E> 返回类型
     * @return 转换后的值，若为 null 或类型不匹配则抛出异常
     */
    public static <E> E getContext(Object context, String exprKey, Class<E> clazz) {
        Object result = get(context, exprKey);
        if (result == null || clazz == null) {
            return (E) result;
        }
        if (isInstance(clazz, result)) {
            return (E) result;
        }
        throw new ClassCastException(result.getClass().getName() + " cannot be cast to " + clazz.getName());
    }

    /**
     * 按属性路径读取值并强制转换为指定类型。
     *
     * @param target 目标对象（Map / JavaBean / 集合）
     * @param key 属性路径，支持多级与数组下标写法
     * @param clazz 期望类型，仅用于类型不匹配时的异常提示
     * @param <E> 返回值类型
     * @return 读取到的值，路径对应的值为 {@code null} 时返回 {@code null}
     */
    public static <E> E get(Object target, String key, Class<E> clazz) {
        Object result = get(target, key);
        if (result == null) {
            return null;
        }
        try {
            return (E) result;
        } catch (Throwable throwable) {
            throw new TypeNotMatchExecption(" type is not match, expect " + clazz + ", but get " + result.getClass());
        }
    }

    /**
     * 从对象中查找属性或 key 对应的 value.
     *
     * <p>支持多级访问 (例: user.name) 和数组访问 (例: users.[n].name).
     * 路径中的属性一旦为空则返回 null.
     *
     * @param target 目标对象（Map / JavaBean / 集合）
     * @param key 属性路径
     * @return 属性值
     */
    public static Object get(Object target, String key) {
        if (target == null) {
            return null;
        }
        key = key.trim();
        int dotIndex;
        if (target instanceof Map) {
            Map<String, Object> mapTarget = (Map<String, Object>) target;
            Object value = mapTarget.get(key);
            if (value != null) {
                return value;
            }
            dotIndex = key.indexOf('.');
            if (dotIndex == -1) {
                return null;
            }
            String topKey = key.substring(0, dotIndex);
            String nextKey = key.substring(dotIndex + 1);
            Object nextTarget = mapTarget.get(topKey);
            return get(nextTarget, nextKey);
        } else {
            dotIndex = key.indexOf('.');
            if (dotIndex > -1) {
                String topKey = key.substring(0, dotIndex);
                String nextKey = key.substring(dotIndex + 1);
                Object nextTarget = get(target, topKey);
                return get(nextTarget, nextKey);
            } else {
                if (CollectionUtils.isCollection(target)) {
                    if ("size".equals(key) || "length".equals(key)) {
                        return CollectionUtils.getSize(target);
                    }
                    if (key.startsWith("[") && key.endsWith("]")) {
                        return CollectionUtils.getElement(target, Integer.parseInt(key.substring(1, key.length() - 1)));
                    }
                    throw new TypeNotMatchExecption("context property '" + key + "' is invalid ");
                } else {
                    return getPropertyValue(target, key);
                }
            }
        }
    }

    /**
     * 获取对象的属性值.
     *
     * @param target 对象
     * @param field 属性名
     * @return 属性值
     */
    public static Object getAttrValue(Object target, String field) {
        if (target == null) {
            return null;
        }
        field.getClass();
        if (target instanceof Map) {
            return ((Map<?, ?>) target).get(field);
        }
        ReflectConsts.ClassCategory classCategory = ReflectConsts.getClassCategory(target.getClass());
        switch (classCategory) {
            case ObjectCategory: {
                return getPropertyValue(target, field);
            }
            case CollectionCategory:
            case ArrayCategory: {
                if ("size".equals(field) || "length".equals(field)) {
                    return CollectionUtils.getSize(target);
                }
                if (field.startsWith("[") && field.endsWith("]")) {
                    return CollectionUtils.getElement(target, Integer.parseInt(field.substring(1, field.length() - 1)));
                }
                throw new TypeNotMatchExecption("context property '" + field + "' is invalid ");
            }
            default: {
                return null;
            }
        }
    }

    /**
     * 按 bean 属性名读取属性值。
     *
     * <p><strong>注意：存在同名字段时不会执行 getter 方法体。</strong>
     * 属性元数据来自带缓存的 {@link ClassStrucWrap}，其中「纯字段」访问器会覆盖同名的 getter 方法访问器，
     * 因此只要类里有同名字段，就用 {@code Unsafe} 偏移量直读该字段，getter 里的额外计算会被跳过。
     * 只有<em>没有</em>对应字段的纯计算属性（只有 getter 的派生属性）才会真正调用 getter 方法。
     *
     * <p><strong>与 {@link com.alianga.jkit.ReflectionUtils#getDeclaredFieldValue(Object, String)} 的区别</strong>：
     * 本方法按 <b>bean 属性名</b>查找（支持下划线别名、元数据按类缓存、能读到无字段的计算属性），
     * 那个方法按 <b>字段名精确匹配</b>并逐级遍历父类、不做缓存、只能读到真实存在的字段。
     *
     * @param target 目标对象
     * @param field 属性名
     * @return 属性值，没有对应属性时为 {@code null}
     */
    public static Object getPropertyValue(Object target, String field) {
        ClassStrucWrap classStrucWrap = ClassStrucWrap.get(target.getClass());
        GetterInfo getterInfo = classStrucWrap.getGetterInfo(field);
        if (getterInfo != null) {
            return getterInfo.invoke(target);
        }
        return null;
    }

    /**
     * 按 bean 属性名读取属性值。
     *
     * @param target 目标对象
     * @param field 属性名
     * @return 属性值
     * @deprecated 方法名与 {@code ReflectionUtils.getObjectFieldValue} 同名同签名，但查找规则不同
     *     （此处按 bean 属性名查带缓存的元数据，那边按字段名精确匹配并遍历父类），易误用。
     *     请改用语义明确的 {@link #getPropertyValue(Object, String)}。
     */
    @Deprecated
    public static Object getObjectFieldValue(Object target, String field) {
        return getPropertyValue(target, field);
    }

    /**
     * 给目标对象的结构（key）赋值(update).
     *
     * <p>支持多级访问 (例: user.name) 和数组访问 (例: users.[n].name).
     *
     * @param target 目标对象
     * @param key 属性路径
     * @param value 新值
     */
    public static void set(Object target, String key, Object value) {
        set(target, key, value, false);
    }

    /**
     * 给目标对象的结构（key）赋值(update)，支持路径中自动创建 Map 节点.
     *
     * @param target 目标对象
     * @param key 属性路径
     * @param value 新值
     * @param createIfMapNull 如果路径中属性为空是否自动创建 Map
     */
    public static void set(Object target, String key, Object value, boolean createIfMapNull) {
        if (target == null) {
            return;
        }
        key = key.trim();
        int dotIndex = key.indexOf('.');
        if (target instanceof Map) {
            Map<String, Object> mapTarget = (Map<String, Object>) target;
            if (mapTarget.containsKey(key)) {
                mapTarget.put(key, value);
            } else {
                if (dotIndex > -1) {
                    String topKey = key.substring(0, dotIndex);
                    String nextKey = key.substring(dotIndex + 1);
                    Object nextTarget = mapTarget.get(topKey);
                    if (createIfMapNull && nextTarget == null) {
                        nextTarget = new LinkedHashMap<String, Object>();
                        mapTarget.put(topKey, nextTarget);
                    }
                    set(nextTarget, nextKey, value, createIfMapNull);
                } else {
                    mapTarget.put(key, value);
                }
            }
        } else {
            if (dotIndex > -1) {
                String topKey = key.substring(0, dotIndex);
                String nextKey = key.substring(dotIndex + 1);
                Object nextTarget = get(target, topKey);
                set(nextTarget, nextKey, value, createIfMapNull);
            } else {
                if (CollectionUtils.isCollection(target)) {
                    if (key.startsWith("[") && key.endsWith("]")) {
                        CollectionUtils.setElement(target, Integer.parseInt(key.substring(1, key.length() - 1)), value);
                    }
                    throw new TypeNotMatchExecption("context property '" + key + "' is invalid ");
                } else {
                    ClassStrucWrap classStrucWrap = ClassStrucWrap.get(target.getClass());
                    SetterInfo setterInfo = classStrucWrap.getSetterInfo(key);
                    if (setterInfo != null) {
                        TRUSTED_ACCESS.set(setterInfo, target, toType(value, setterInfo.getParameterType()));
                    }
                }
            }
        }
    }

    /**
     * 批量读取目标对象中多个属性路径对应的值。
     *
     * @param target 目标对象（Map / JavaBean / 集合）
     * @param keys 属性路径集合
     * @return 与 {@code keys} 顺序一一对应的值数组，取不到的位置为 {@code null}
     */
    public static Object[] get(Object target, List<String> keys) {
        Object[] values = new Object[keys.size()];
        int index = 0;
        for (String key : keys) {
            values[index++] = get(target, key);
        }
        return values;
    }

    /**
     * 将对象转为 Map 结构（JavaBean 通过 getter 读取，Map 直接返回）.
     *
     * @param target 待转换的对象
     * @return 属性名到属性值的 Map；{@code target} 本身是 Map 时原样返回，为 {@code null} 时返回 {@code null}
     */
    public static Map<String, Object> toMap(Object target) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map) {
            return (Map<String, Object>) target;
        }
        Map<String, Object> map = new HashMap<String, Object>();
        ClassStrucWrap classStrucWrap = ClassStrucWrap.get(target.getClass());
        List<GetterInfo> getterInfos = classStrucWrap.getGetterInfos();
        for (GetterInfo getterInfo : getterInfos) {
            map.put(getterInfo.getName(), TRUSTED_ACCESS.get(getterInfo, target));
        }
        return map;
    }

    /**
     * 获取对象的非空属性列表.
     *
     * @param target 目标对象，Map 容器或 JavaBean
     * @return 值非空的属性名列表，{@code target} 为 {@code null} 时返回 {@code null}
     */
    public static List<String> getNonEmptyFields(Object target) {
        return getNonEmptyFields(target, new String[0]);
    }

    /**
     * 获取对象的非空属性列表，可排除指定字段.
     *
     * @param target 目标对象，Map 容器或 JavaBean
     * @param excludeKeys 需要排除的属性名
     * @return 值非空且未被排除的属性名列表；JavaBean 场景下数值 0 也视为空而被跳过，
     *     {@code target} 为 {@code null} 时返回 {@code null}
     */
    public static List<String> getNonEmptyFields(Object target, String... excludeKeys) {
        if (target == null) {
            return null;
        }
        List<String> fields = new ArrayList<String>();
        if (target instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) target;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String field = key.toString();
                Object val = entry.getValue();
                if (val != null && !val.equals("") && !CollectionUtils.contains(excludeKeys, field)) {
                    fields.add(field);
                }
            }
        } else {
            ClassStrucWrap classStrucWrap = ClassStrucWrap.get(target.getClass());
            List<GetterInfo> getterInfos = classStrucWrap.getGetterInfos();
            for (GetterInfo getterInfo : getterInfos) {
                Object val = getterInfo.invoke(target);
                if (val == null || val.equals("")) {
                    continue;
                }
                if (Number.class.isInstance(val) && val.toString().equals("0")) {
                    continue;
                }
                String name = getterInfo.getName();
                if (CollectionUtils.contains(excludeKeys, name)) {
                    continue;
                }
                fields.add(name);
            }
        }
        return fields;
    }

    /**
     * 判断两个 Method 签名是否相同（弱比较，忽略声明类）.
     *
     * @param source 待比较的方法之一
     * @param target 待比较的方法之二
     * @return 方法名、返回类型与参数类型列表全部一致时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean methodWeakEquals(Method source, Method target) {
        if (!source.getName().equals(target.getName())) {
            return false;
        }
        if (!source.getReturnType().equals(target.getReturnType())) {
            return false;
        }
        Class<?>[] params1 = source.getParameterTypes();
        Class<?>[] params2 = target.getParameterTypes();
        if (params1.length == params2.length) {
            for (int i = 0; i < params1.length; i++) {
                if (params1[i] != params2[i]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 按属性路径读取值，并将其包装为可迭代对象。
     *
     * @param context 上下文对象（Map / JavaBean / 集合）
     * @param key 属性路径
     * @return 值本身是 {@link Iterable} 时直接返回，是对象数组时返回其 {@link Arrays#asList} 视图
     * @throws TypeNotMatchExecption 值为 {@code null} 或既不是 Iterable 也不是数组时抛出
     */
    public static Iterable<Object> getIterable(Object context, String key) {
        Object target = get(context, key);
        if (target == null) {
            throw new TypeNotMatchExecption("context property '" + key + "' is null or not iterable ");
        }
        if (target instanceof Iterable) {
            return (Iterable) target;
        }
        if (target.getClass().isArray()) {
            Object[] array = (Object[]) target;
            return Arrays.asList(array);
        } else {
            throw new TypeNotMatchExecption("context property '" + key + "' is not array or iterable ");
        }
    }

    /**
     * 将 Number 值转化为指定数字类型.
     *
     * @param value 待转换的数字，不能为 {@code null}
     * @param numberType 目标数字类型，支持包装类型、原始类型、BigDecimal、BigInteger、AtomicInteger、AtomicLong
     * @return 转换后的数字；目标类型为 {@code Object}、{@code Number} 或已是该类型时原样返回，
     *     目标类型不受支持时返回 {@code null}
     */
    public static Number toTypeNumber(Number value, Class<?> numberType) {
        value.getClass();
        if (numberType.isInstance(value) || numberType == Object.class || numberType == Number.class) {
            return value;
        }
        if (numberType == Double.class || numberType == double.class) {
            return value.doubleValue();
        } else if (numberType == Long.class || numberType == long.class) {
            return value.longValue();
        } else if (numberType == Integer.class || numberType == int.class) {
            return value.intValue();
        } else if (numberType == Float.class || numberType == float.class) {
            return value.floatValue();
        } else if (numberType == Short.class || numberType == short.class) {
            return value.shortValue();
        } else if (numberType == Byte.class || numberType == byte.class) {
            return value.byteValue();
        } else if (numberType == BigDecimal.class) {
            return new BigDecimal(value.toString());
        } else if (numberType == BigInteger.class) {
            return new BigInteger(value.toString());
        } else if (numberType == AtomicInteger.class) {
            return new AtomicInteger(value.intValue());
        } else if (numberType == AtomicLong.class) {
            return new AtomicLong(value.longValue());
        }
        return null;
    }

    /**
     * 返回指定类型的默认值（原始类型返回 0/false/'\0'，引用类型返回 null）.
     *
     * @param type 目标类型
     * @return 该类型的默认值，引用类型返回 {@code null}
     */
    public static Object defaulValue(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == boolean.class) {
                return false;
            } else if (type == char.class) {
                return (char) 0;
            } else if (type == byte.class) {
                return (byte) 0;
            } else if (type == short.class) {
                return (short) 0;
            } else if (type == long.class) {
                return 0L;
            } else if (type == double.class) {
                return 0.0;
            } else if (type == float.class) {
                return 0.0f;
            } else {
                return 0;
            }
        } else {
            return null;
        }
    }

    /**
     * 判断 value 是否为指定类型的实例，原始类型会先转换为对应的包装类型再判断。
     *
     * @param valueClass 目标类型，允许传入原始类型
     * @param value 待判断的值
     * @return value 是该类型（或其包装类型）的实例时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isInstance(Class<?> valueClass, Object value) {
        if (valueClass.isPrimitive()) {
            valueClass = ReflectConsts.PrimitiveType.getWrap(valueClass);
        }
        return valueClass.isInstance(value);
    }

    /**
     * 按已知的类型分类将 value 转化为 valueClass 的实例，避免重复计算分类。
     *
     * @param value 待转换的值
     * @param valueClass 目标类型
     * @param classCategory {@code valueClass} 对应的类型分类
     * @param <E> 目标类型
     * @return 转换后的实例；{@code value} 或 {@code valueClass} 为 {@code null}、
     *     或 value 已是目标类型实例时原样返回，无法转换时返回 {@code null}
     */
    public static <E> E toType(Object value, Class<E> valueClass, ReflectConsts.ClassCategory classCategory) {
        if (value == null || valueClass == null) {
            return (E) value;
        }
        if (isInstance(valueClass, value)) {
            return (E) value;
        }
        return toTypeByClassCategory(value, valueClass, classCategory);
    }

    /**
     * 将 value 转化为 valueClass 的实例，缺省情况下返回 0 或者 null.
     *
     * @param value 待转换的值
     * @param valueClass 目标类型
     * @param <E> 目标类型
     * @return 转换后的实例；{@code value} 或 {@code valueClass} 为 {@code null}、
     *     或 value 已是目标类型实例时原样返回，无法转换时返回 {@code null}
     */
    public static <E> E toType(Object value, Class<E> valueClass) {
        if (value == null || valueClass == null) {
            return (E) value;
        }
        if (isInstance(valueClass, value)) {
            return (E) value;
        }
        ReflectConsts.ClassCategory classCategory = ReflectConsts.getClassCategory(valueClass);
        return toTypeByClassCategory(value, valueClass, classCategory);
    }

    private static <E> E toTypeByClassCategory(Object value, Class<E> valueClass,
                                               ReflectConsts.ClassCategory classCategory) {
        switch (classCategory) {
            case CharSequence: {
                if (valueClass == String.class) {
                    if (value instanceof Date) {
                        return (E) new GregorianDate(((Date) value).getTime()).format();
                    } else if (value instanceof byte[]) {
                        return (E) new String((byte[]) value);
                    }
                    return (E) value.toString();
                }
                break;
            }
            case NumberCategory: {
                boolean isNumber = value instanceof Number;
                Number numValue;
                if (isNumber) {
                    numValue = (Number) value;
                    return (E) toTypeNumber(numValue, valueClass);
                } else {
                    if (valueClass == Double.class || valueClass == double.class) {
                        numValue = Double.parseDouble(value.toString());
                        return (E) numValue;
                    }
                    if (valueClass == Float.class || valueClass == float.class) {
                        numValue = Float.parseFloat(value.toString());
                        return (E) numValue;
                    }
                    if (valueClass == BigDecimal.class) {
                        numValue = new BigDecimal(value.toString());
                    } else if (valueClass == BigInteger.class) {
                        numValue = new BigInteger(value.toString());
                    } else {
                        numValue = Long.parseLong(value.toString());
                    }
                    return (E) toTypeNumber(numValue, valueClass);
                }
            }
            case BoolCategory: {
                if (value == Boolean.TRUE || value == Boolean.FALSE) {
                    return (E) value;
                }
                if (value instanceof Number) {
                    Boolean bool = ((Number) value).intValue() != 0;
                    return (E) bool;
                } else {
                    String stringValue = value.toString().toLowerCase();
                    if (stringValue.equals("true") || stringValue.equals("yes") || stringValue.equals("on")) {
                        return (E) Boolean.TRUE;
                    } else if (stringValue.equals("false") || stringValue.equals("no") || stringValue.equals("off")) {
                        return (E) Boolean.FALSE;
                    }
                }
                break;
            }
            case DateCategory: {
                String dateValue = value.toString();
                long time = DateParser.parseTime(dateValue);
                if (valueClass == Date.class) {
                    return (E) new Date(time);
                } else if (valueClass == Timestamp.class) {
                    return (E) new Timestamp(time);
                } else {
                    try {
                        Constructor constructor = valueClass.getDeclaredConstructor(new Class[]{long.class});
                        UnsafeHelper.setAccessible(constructor);
                        return (E) constructor.newInstance(time);
                    } catch (Exception e) {
                        throw new UnsupportedOperationException("not supported for date type " + valueClass);
                    }
                }
            }
            case EnumCategory: {
                if (value instanceof Number) {
                    Class<? extends Enum> enumCls = (Class<? extends Enum>) valueClass;
                    Enum[] values = enumCls.getEnumConstants();
                    int index = ((Number) value).intValue();
                    Enum enumValue = index < values.length ? values[index] : null;
                    return (E) enumValue;
                } else {
                    return (E) Enum.valueOf((Class<? extends Enum>) valueClass, value.toString());
                }
            }
            case Binary: {
                if (value instanceof String) {
                    return (E) ((String) value).getBytes();
                }
            }
            case ANY: {
                return (E) value;
            }
            case ObjectCategory: {
                ClassStrucWrap structureWrapper = ClassStrucWrap.get(valueClass);
                if (structureWrapper.isTemporal()) {
                    // jdk8+ time api
                }
                break;
            }
            default:
                break;
        }
        return null;
    }

    /**
     * 判断 value 是否为空（null / "" / [] / {}）.
     *
     * @param value 待判断的值
     * @return 为 {@code null}、空白字符串、空集合、空 Map 或长度为 0 的数组时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence) {
            String toString = value.toString();
            return toString.trim().isEmpty();
        }
        if (value instanceof Collection) {
            return ((Collection) value).isEmpty();
        }
        if (value instanceof Map) {
            return ((Map) value).isEmpty();
        }
        Class cls = value.getClass();
        if (cls.isArray()) {
            Class componentType = cls.getComponentType();
            if (componentType.isPrimitive()) {
                return Array.getLength(value) == 0;
            } else {
                return ((Object[]) value).length == 0;
            }
        }
        return false;
    }
}
