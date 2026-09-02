package com.alianga.jkit;

import com.alianga.jkit.collection.ArrayUtils;
import com.alianga.jkit.jdk.UnsafeUtils;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.FieldAccessor;
import com.alianga.jkit.valid.Preconditions;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 郑明亮
 * @time：2016年12月20日 下午4:34:52
 * @description <p> 反射相关工具类<br>
 */
public class ReflectionUtils {
    private static final Method[] EMPTY_METHOD_ARRAY = new Method[0];

    /**
     * Cache for {@link Class#getDeclaredFields()}, allowing for fast iteration.
     */
    private static final Map<Class<?>, Field[]> declaredFieldsCache = new ConcurrentHashMap<>(256);
    /**
     * 根据字段名缓存Field
     */
    private static final Map<String, Field> declaredFieldCache = new ConcurrentHashMap<>(256);

    /**
     * Cache for {@link Class#getDeclaredMethods()} plus equivalent default methods
     * from Java 8 based interfaces, allowing for fast iteration.
     */
    private static final Map<Class<?>, Method[]> declaredMethodsCache = new ConcurrentHashMap<>(256);
    /**
     * 根据方法名缓存 Method
     */
    private static final Map<String, Method> declaredMethodCache = new ConcurrentHashMap<>(256);

    private ReflectionUtils() {
        throw new UnsupportedOperationException(" you can not instantiate me");
    }

    /**
     * 获取传入类中声明的成员变量、常量的成员名称
     *
     * @param clz 要解析的目标类
     * @param upperFirstLetter 是否将首字母转换为大写
     * @return 该类自身声明的字段名称数组，不包含父类字段
     */
    public static String[] getFieldsNames(Class<?> clz, boolean upperFirstLetter) {
        return getFieldsNames(clz, upperFirstLetter, false);
    }

    /**
     * @param clz 要解析的目标类
     * @param upperFirstLetter 是否首字母大写
     * @param includeParent 是否获取直接父类中的成员
     * @return 字段名称数组，{@code includeParent} 为 {@code true} 时追加直接父类声明的字段名
     * @author 郑明亮
     * @time：2016年12月19日 下午5:40:50 获取传入类中声明的成员变量、常量的成员名称，并将首字母变为大写
     */
    public static String[] getFieldsNames(Class<?> clz, boolean upperFirstLetter, boolean includeParent) {
        List<String> list = new ArrayList<String>();
        List<Field> fieldList = new ArrayList<>();
        fieldList.addAll(Arrays.asList(clz.getDeclaredFields()));
        if (includeParent) {
            fieldList.addAll(Arrays.asList(clz.getSuperclass().getDeclaredFields()));
        }
        for (int i = 0; i < fieldList.size(); i++) {
            String fieldName = fieldList.get(i).getName();
            if (upperFirstLetter) {
                fieldName = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1); // 首字母大写
            }
            list.add(fieldName);
        }
        return list.toArray(new String[list.size()]);
    }

    /**
     * @param clz 要解析的目标类
     * @param exceptNames 不包含的声明的成员变量、常量的名称，必需与实体类中的成员名称大小写一致
     * @return 首字母已转换为大写的字段名称数组
     * @author 郑明亮
     * @time 2017年1月9日 下午7:47:27
     * 获取传入类中声明的成员变量、常量的成员名称，并将首字母变为大写，特定成员除外
     * <br>
     */
    public static String[] getFieldsNames(Class<?> clz, String... exceptNames) {
        List<String> list = new ArrayList<String>();

        Field[] fields = clz.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            String fieldName = fields[i].getName();
            boolean excluded = false;
            for (int j = 0; j < exceptNames.length; j++) {
                if (exceptNames[j].equals(fieldName)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) {
                continue;
            }

            list.add(fieldName.substring(0, 1).toUpperCase()
                    + fieldName.substring(1)); // 首字母大写
        }
        return list.toArray(new String[list.size()]);
    }

    /**
     * 按字段名查找字段，查找结果会被缓存，找不到时抛出异常
     *
     * @param cls 目标类
     * @param fieldName 字段名
     * @return 匹配的 {@link Field}，包含父类中声明的字段
     * @throws IllegalArgumentException 类及其父类中都不存在该字段时抛出
     */
    public static Field getField(Class<?> cls, String fieldName) {
        String key = cls.getName() + "." + fieldName;
        Field cache = declaredFieldCache.get(key);
        if (cache != null) {
            return cache;
        }
        Field field = getFieldNullable(cls, fieldName);
        if (field == null) {
            String msg = String.format("class %s doesn't have field %s", cls, fieldName);
            throw new IllegalArgumentException(msg);
        }
        declaredFieldCache.put(key, field);
        return field;
    }

    /**
     * 沿类继承链按字段名查找字段，不使用缓存
     *
     * @param cls 目标类
     * @param fieldName 字段名
     * @return 匹配的 {@link Field}，类及其所有父类中都不存在该字段时返回 {@code null}
     */
    public static Field getFieldNullable(Class<?> cls, String fieldName) {
        Class<?> clazz = cls;
        do {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals(fieldName)) {
                    return field;
                }
            }
            clazz = clazz.getSuperclass();
        } while (clazz != null);
        return null;
    }

    /**
     * 获取类中声明的所有字段
     *
     * @param cls 目标类，不能为 {@code null}
     * @param searchParent 是否同时收集所有父类中声明的字段
     * @return 字段列表，类中没有声明字段时返回空列表
     */
    public static List<Field> getFields(Class<?> cls, boolean searchParent) {
        Preconditions.checkNotNull(cls);
        List<Field> fields = new ArrayList<>();
        if (searchParent) {
            Class<?> clazz = cls;
            do {
                Collections.addAll(fields, clazz.getDeclaredFields());
                clazz = clazz.getSuperclass();
            } while (clazz != null);
        } else {
            Collections.addAll(fields, cls.getDeclaredFields());
        }
        return fields;
    }

    /**
     * @param clz 要解析的目标类
     * @param maxSize 需要得到的filed的最大个数，maxSize&lt;clz.getDeclaredFields().size()
     * @return 长度为 {@code maxSize} 且首字母已转换为大写的字段名称数组
     * @author 郑明亮
     * @time 2017年1月9日 下午7:36:21
     * 获取特定个数的传入类中声明的成员变量、常量的成员名称，并将首字母变为大写
     * <br>
     */
    public static String[] getFields(Class<?> clz, int maxSize) {
        List<String> list = new ArrayList<String>();

        Field[] fields = clz.getDeclaredFields();
        String[] fieldsArray = new String[maxSize];
        for (int i = 0; i < maxSize; i++) {
            String fieldName = fields[i].getName();
            list.add(fieldName.substring(0, 1).toUpperCase()
                    + fieldName.substring(1)); // 首字母大写
            fieldsArray[i] = fieldName.substring(0, 1).toUpperCase()
                    + fieldName.substring(1);
        }
        return fieldsArray;
    }

    /**
     * @param <T> 实例类型
     * @param t 要执行方法的实例
     * @param methodName 方法名
     * @param paramters 传入方法的参数，没有参数可不传
     * @return 目标方法的返回值，方法返回 {@code void} 时为 {@code null}
     * @throws IllegalAccessException 目标方法不可访问时抛出
     * @throws InvocationTargetException 目标方法执行过程中抛出异常时抛出
     * @throws NoSuchMethodException 目标类中不存在匹配签名的公有方法时抛出
     * @author 郑明亮
     * @time 2017年1月9日 下午7:34:15
     * 通过反射执行某特定实例中的特定方法
     * <br>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> Object methodInvoke(T t, String methodName,
                                          Class<?>... paramters)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        return methodInvoke(t, methodName, null, paramters);

    }

    /**
     * 通过反射执行某特定实例中的特定方法，并传入实参
     *
     * @param <T> 实例类型
     * @param t 要执行方法的实例
     * @param methodName 方法名
     * @param params 调用方法时传入的实参数组，为 {@code null} 时按无参方式调用
     * @param paramters 方法的形参类型列表，用于定位方法签名
     * @return 目标方法的返回值，方法返回 {@code void} 时为 {@code null}
     * @throws IllegalAccessException 目标方法不可访问时抛出
     * @throws InvocationTargetException 目标方法执行过程中抛出异常时抛出
     * @throws NoSuchMethodException 目标类中不存在匹配签名的公有方法时抛出
     */
    public static <T> Object methodInvoke(T t, String methodName, Object[] params,
                                          Class<?>... paramters)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        Class clz = t.getClass();
        Method method = clz.getMethod(methodName, paramters);
        if (params != null) {
            return method.invoke(t, params);
        }
        return method.invoke(t);

    }

    /**
     * <ol>
     * <li>功能：获取包括父类所有的字段
     * </ol>
     *
     * @param object 目标对象
     * @return 该对象所属类及其所有父类中声明的字段数组，结果按类为单位缓存
     * @author 沈建飞
     */
    public static Field[] getAllFields(Object object) {
        Class clazz = object.getClass();
        Field[] fields = declaredFieldsCache.get(clazz);
        if (fields != null) {
            return fields;
        }
        List<Field> fieldList = new ArrayList<Field>();
        while (clazz != null) {
            fieldList.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        fields = new Field[fieldList.size()];
        fieldList.toArray(fields);
        declaredFieldsCache.put(object.getClass(), fields);
        return fields;
    }

    /**
     * Gets parameterized type.
     *
     * @param interfaceType interface type must not be null
     * @param implementationClass implementation class of the interface must not be null
     * @return parameterized type of the interface or null if it is mismatch
     */
    public static ParameterizedType getParameterizedType(Class<?> interfaceType,
                                                         Class<?> implementationClass) {
        Assert.notNull(interfaceType, "Interface type must not be null");
        Assert.isTrue(interfaceType.isInterface(), "The give type must be an interface");

        if (implementationClass == null) {
            // If the super class is Object parent then return null
            return null;
        }

        // Get parameterized type
        ParameterizedType currentType =
                getParameterizedType(interfaceType, implementationClass.getGenericInterfaces());

        if (currentType != null) {
            // return the current type
            return currentType;
        }

        Class<?> superclass = implementationClass.getSuperclass();

        return getParameterizedType(interfaceType, superclass);
    }

    /**
     * Gets parameterized type.
     *
     * @param superType super type must not be null (super class or super interface)
     * @param genericTypes generic type array
     * @return parameterized type of the interface or null if it is mismatch
     */
    public static ParameterizedType getParameterizedType(Class<?> superType,
                                                         Type... genericTypes) {
        Assert.notNull(superType, "Interface or super type must not be null");

        ParameterizedType currentType = null;

        for (Type genericType : genericTypes) {
            if (genericType instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) genericType;
                if (parameterizedType.getRawType().getTypeName().equals(superType.getTypeName())) {
                    currentType = parameterizedType;
                    break;
                }
            }
        }

        return currentType;
    }

    /**
     * 使给定的方法可访问，如果需要，显式地将其设置为可访问。setAccessible（true）方法仅在实际需要时调用，
     * 以避免与JVM SecurityManager（如果处于活动状态）发生不必要的冲突
     *
     * @param field 字段
     */
    public static void makeAccessible(Field field) {
        if ((!Modifier.isPublic(field.getModifiers()) ||
                !Modifier.isPublic(field.getDeclaringClass().getModifiers()) ||
                Modifier.isFinal(field.getModifiers())) && !field.isAccessible()) {
            field.setAccessible(true);
        }
    }

    /**
     * 使构造方法可以被访问
     *
     * @param constructor 要放开访问限制的构造方法
     */
    public static void makeAccessible(Constructor constructor) {
        if (!isAccessible(constructor) && !constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
    }

    /**
     * 判断该成员 {@link Member} 是否public 并且所在类也是public的
     *
     * @param <T> 要测试其可访问性的对象的类型
     * @param member 要检查公共可访问性的成员(不能是{@code null})。
     * @return 返回{@code true} 如果 {@code member} 是 public 的并且是在一个public 类中.
     * @throws NullPointerException if {@code member} is {@code null}.
     */
    public static <T extends AccessibleObject & Member> boolean isAccessible(final T member) {
        Objects.requireNonNull(member, "No member provided");
        return Modifier.isPublic(member.getModifiers()) && Modifier.isPublic(member.getDeclaringClass().getModifiers());
    }

    /**
     * 使给定的字段可访问，如果需要，显式地将其设置为可访问。setAccessible（true）方法仅在实际需要时调用，
     * 以避免与JVM SecurityManager（如果处于活动状态）发生不必要的冲突
     *
     * @param method 方法
     */
    public static void makeAccessible(Method method) {
        if ((!Modifier.isPublic(method.getModifiers()) ||
                !Modifier.isPublic(method.getDeclaringClass().getModifiers())) && !method.isAccessible()) {
            method.setAccessible(true);
        }
    }

    /**
     * Determine whether the given method is an "equals" method.
     *
     * @param method the method to check, may be {@code null}
     * @return {@code true} if the method is named "equals" and takes a single
     *         {@code Object} parameter, {@code false} otherwise
     * @see Object#equals(Object)
     */
    public static boolean isEqualsMethod(Method method) {
        if (method == null || !method.getName().equals("equals")) {
            return false;
        }
        if (method.getParameterCount() != 1) {
            return false;
        }
        return method.getParameterTypes()[0] == Object.class;
    }

    /**
     * Determine whether the given method is a "hashCode" method.
     *
     * @param method the method to check, may be {@code null}
     * @return {@code true} if the method is named "hashCode" and takes no parameter,
     *         {@code false} otherwise
     * @see Object#hashCode()
     */
    public static boolean isHashCodeMethod(Method method) {
        return (method != null && method.getName().equals("hashCode") && method.getParameterCount() == 0);
    }

    /**
     * Determine whether the given method is a "toString" method.
     *
     * @param method the method to check, may be {@code null}
     * @return {@code true} if the method is named "toString" and takes no parameter,
     *         {@code false} otherwise
     * @see Object#toString()
     */
    public static boolean isToStringMethod(Method method) {
        return (method != null && method.getName().equals("toString") && method.getParameterCount() == 0);
    }

    /**
     * Determine whether the given method is originally declared by {@link Object}.
     *
     * @param method the method to check, may be {@code null}
     * @return {@code true} if the method is declared by {@code Object} or is one of the
     *         equals/hashCode/toString methods, {@code false} otherwise
     */
    public static boolean isObjectMethod(Method method) {
        return (method != null && (method.getDeclaringClass() == Object.class ||
                isEqualsMethod(method) || isHashCodeMethod(method) || isToStringMethod(method)));
    }

    /**
     * 获取类中声明的方法（含接口默认方法），结果带缓存并返回防御性副本
     *
     * @param clazz 目标类，不能为 {@code null}
     * @return 该类声明的方法数组以及其接口上的非抽象方法，没有方法时返回空数组
     */
    public static Method[] getDeclaredMethods(Class<?> clazz) {
        return getDeclaredMethods(clazz, true);
    }

    /**
     * 按方法名与形参类型查找公有方法，查找结果会被缓存
     *
     * @param clazz 目标类
     * @param methodName 方法名
     * @param parameterTypes 形参类型列表，未传则查找无参方法
     * @return 匹配到的 {@link Method}
     * @throws NoSuchMethodException 目标类中不存在与该名称和形参类型匹配的公有方法时抛出
     */
    public static Method getDeclaredMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        String parameterTypeKey = "";
        if (ArrayUtils.isNotEmpty(parameterTypes)) {
            for (Class<?> parameterType : parameterTypes) {
                parameterTypeKey += parameterType.getSimpleName() + ",";
            }
            parameterTypeKey = "(" + parameterTypeKey.substring(0, parameterTypeKey.length() - 1) + ")";
        }
        String key = clazz.getName() + methodName + parameterTypeKey;
        Method method = declaredMethodCache.get(key);
        if (method != null) {
            return method;
        }
        method = clazz.getMethod(methodName, parameterTypes);
        declaredMethodCache.put(key, method);
        return method;
    }

    private static Method[] getDeclaredMethods(Class<?> clazz, boolean defensive) {
        Assert.notNull(clazz, "Class must not be null");
        Method[] result = declaredMethodsCache.get(clazz);
        if (result == null) {
            try {
                Method[] declaredMethods = clazz.getDeclaredMethods();
                List<Method> defaultMethods = findConcreteMethodsOnInterfaces(clazz);
                if (defaultMethods != null) {
                    result = new Method[declaredMethods.length + defaultMethods.size()];
                    System.arraycopy(declaredMethods, 0, result, 0, declaredMethods.length);
                    int index = declaredMethods.length;
                    for (Method defaultMethod : defaultMethods) {
                        result[index] = defaultMethod;
                        index++;
                    }
                } else {
                    result = declaredMethods;
                }
                declaredMethodsCache.put(clazz, (result.length == 0 ? EMPTY_METHOD_ARRAY : result));
            } catch (Throwable ex) {
                throw new IllegalStateException("Failed to introspect Class [" + clazz.getName() +
                        "] from ClassLoader [" + clazz.getClassLoader() + "]", ex);
            }
        }
        return (result.length == 0 || !defensive) ? result : result.clone();
    }

    private static List<Method> findConcreteMethodsOnInterfaces(Class<?> clazz) {
        List<Method> result = null;
        for (Class<?> ifc : clazz.getInterfaces()) {
            for (Method ifcMethod : ifc.getMethods()) {
                if (!Modifier.isAbstract(ifcMethod.getModifiers())) {
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(ifcMethod);
                }
            }
        }
        return result;
    }

    /**
     * 清空字段反射结果缓存
     */
    public static void clearCache() {
//        declaredMethodsCache.clear();
        declaredFieldsCache.clear();
    }

    /**
     * 获取 bean 的全部属性描述符（不含 {@code Object} 上的属性）
     *
     * @param type 目标 bean 类型
     * @return 该类型的所有属性描述符；内省失败时返回空数组
     */
    public static PropertyDescriptor[] getBeanProperties(Class type) {
        return getPropertiesHelper(type, true, true);
    }

    /**
     * 获取 bean 中带 getter 方法的属性描述符
     *
     * @param type 目标 bean 类型
     * @return 可读属性的描述符数组；内省失败时返回空数组
     */
    public static PropertyDescriptor[] getBeanGetters(Class type) {
        return getPropertiesHelper(type, true, false);
    }

    /**
     * 获取 bean 中带 setter 方法的属性描述符
     *
     * @param type 目标 bean 类型
     * @return 可写属性的描述符数组；内省失败时返回空数组
     */
    public static PropertyDescriptor[] getBeanSetters(Class type) {
        return getPropertiesHelper(type, false, true);
    }

    private static PropertyDescriptor[] getPropertiesHelper(Class type, boolean read, boolean write) {
        try {
            BeanInfo info = Introspector.getBeanInfo(type, Object.class);
            PropertyDescriptor[] all = info.getPropertyDescriptors();
            if (read && write) {
                return all;
            }
            List properties = new ArrayList(all.length);
            for (int i = 0; i < all.length; i++) {
                PropertyDescriptor pd = all[i];
                if ((read && pd.getReadMethod() != null) ||
                        (write && pd.getWriteMethod() != null)) {
                    properties.add(pd);
                }
            }
            return (PropertyDescriptor[]) properties.toArray(new PropertyDescriptor[properties.size()]);
        } catch (IntrospectionException e) {
            e.printStackTrace();
        }
        return emptyProperty;
    }

    private static final PropertyDescriptor[] emptyProperty = {};

    /**
     * 通过默认无参构造方法创建实例，私有构造方法也可被调用
     *
     * @param <T> 要创建的实例类型
     * @param clazz 目标类，不能为 {@code null}
     * @return 通过无参构造方法新建的实例
     * @throws IllegalArgumentException 类无法被初始化（如缺少依赖类）时抛出
     * @throws IllegalStateException 无参构造方法不存在或不可访问时抛出
     */
    public static <T> T instantiate(final Class<T> clazz) {
        Objects.requireNonNull(clazz, "No class provided");
        final Constructor<T> constructor = getDefaultConstructor(clazz);
        try {
            return constructor.newInstance();
        } catch (final LinkageError | InstantiationException e) {
            // LOG4J2-1051
            // On platforms like Google App Engine and Android, some JRE classes are not supported: JMX, JNDI, etc.
            throw new IllegalArgumentException(e);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (final InvocationTargetException e) {
            throw new InternalError("Unreachable", e);
        }
    }

    /**
     * 获取默认的无参构造方法.
     *
     * @param clazz 要为其查找构造函数的类
     * @param <T> 构造函数创建的类型
     * @return 给定类的默认构造函数
     * @throws IllegalStateException 给定类的默认构造函数如果找不到则抛出该异常
     */
    public static <T> Constructor<T> getDefaultConstructor(final Class<T> clazz) {
        Objects.requireNonNull(clazz, "No class provided");
        try {
            final Constructor<T> constructor = clazz.getDeclaredConstructor();
            makeAccessible(constructor);
            return constructor;
        } catch (final NoSuchMethodException ignored) {
            try {
                final Constructor<T> constructor = clazz.getConstructor();
                makeAccessible(constructor);
                return constructor;
            } catch (final NoSuchMethodException e) {
                throw new IllegalStateException("没有默认的无参构造方法", e);
            }
        }
    }

    /**
     * 按字段名沿类继承链查找<em>声明字段</em>，并绕过访问控制直接读取其值。
     *
     * <p><strong>与 {@link com.alianga.jkit.beans.ObjectUtils#getPropertyValue(Object, String)} 的区别</strong>：
     * 只要字段存在，两者都是直读字段（都不执行 getter 方法体），真正的差异在于「怎么找」和「能找到什么」——
     * <ul>
     * <li>本方法：用 {@code getDeclaredField} 按<b>字段名精确匹配</b>，逐级向上遍历父类，不做缓存，
     *     不认 bean 属性名规则或下划线别名；字段不存在就返回 {@code null}，读不到只有 getter 的计算属性。</li>
     * <li>{@code ObjectUtils.getPropertyValue}：按<b>bean 属性名</b>在带缓存的
     *     {@link ClassStrucWrap} 元数据里查找，支持下划线别名，
     *     并且能读到没有字段、仅由 getter 计算出来的派生属性。</li>
     * </ul>
     * 需要「读到字段真实值」时用本方法；需要「按 bean 属性语义读取」时用那一个。
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @return 字段值，字段不存在时为 {@code null}
     */
    public static Object getDeclaredFieldValue(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        Preconditions.checkArgument(!cls.isPrimitive());
        while (cls != Object.class) {
            try {
                Field field = cls.getDeclaredField(fieldName);
                long fieldOffset = UnsafeUtils.objectFieldOffset(field);
                return UnsafeUtils.getObject(obj, fieldOffset);
                // CHECKSTYLE.OFF:EmptyCatchBlock
            } catch (NoSuchFieldException ignored) {
            }
            // CHECKSTYLE.ON:EmptyCatchBlock
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * 直读声明字段的值。
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @return 字段值
     * @deprecated 方法名与 {@code beans.ObjectUtils.getObjectFieldValue} 同名同签名，但查找规则不同
     *     （此处按字段名精确匹配并遍历父类，那边按 bean 属性名查带缓存的元数据），易误用。
     *     请改用语义明确的 {@link #getDeclaredFieldValue(Object, String)}。
     */
    @Deprecated
    public static Object getObjectFieldValue(Object obj, String fieldName) {
        return getDeclaredFieldValue(obj, fieldName);
    }

    /**
     * 批量读取指定字段在给定对象上的值
     *
     * @param fields 待读取的字段集合
     * @param o 目标对象
     * @return 与 {@code fields} 迭代顺序一致的字段值列表
     */
    public static List<Object> getFieldValues(Collection<Field> fields, Object o) {
        List<Object> results = new ArrayList<>(fields.size());
        for (Field field : fields) {
            // UNSAFE.objectFieldOffset(field)无法处理基本数据类型字段.
            Object fieldValue = FieldAccessor.createAccessor(field).get(o);
            results.add(fieldValue);
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // 泛型与实际类型解析（源自 json.internal.utils.ReflectUtils）
    // -----------------------------------------------------------------------

    /**
     * 获取目标类声明的实际泛型类型参数。
     *
     * @param targetClass 目标类
     * @return 父类（父类不带泛型时取第一个泛型接口）上的实际类型参数数组；
     *         入参为 {@code null}、无泛型父类和泛型接口时返回 {@code null}
     */
    public static Type[] getActualTypes(Class<?> targetClass) {
        if (targetClass == null) {
            return null;
        }
        Type superClass = targetClass.getGenericSuperclass();
        if (superClass == null) {
            Type[] interfaces = targetClass.getGenericInterfaces();
            if (interfaces.length == 0) {
                return null;
            }
            superClass = interfaces[0];
        }
        if (superClass instanceof ParameterizedType) {
            return ((ParameterizedType) superClass).getActualTypeArguments();
        }
        return null;
    }

    /**
     * 获取目标类声明的第一个实际泛型类型。
     *
     * @param targetClass 目标类
     * @return 第一个实际类型参数对应的 {@link Class}；不存在或该参数不是具体类时返回 {@code null}
     */
    public static Class<?> getActualType(Class<?> targetClass) {
        Type[] types = getActualTypes(targetClass);
        if (types == null || types.length == 0) {
            return null;
        }
        return types[0] instanceof Class ? (Class<?>) types[0] : null;
    }

    /**
     * 获取实现了某个接口的类的实际泛型参数类型。
     *
     * @param targetClass 目标类
     * @return 第一个带泛型的接口上的首个实际类型参数；未找到时返回 {@code null}
     */
    public static Class<?> getImplementActualType(Class<?> targetClass) {
        Type[] interfaces = targetClass.getGenericInterfaces();
        for (Type iface : interfaces) {
            if (iface instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) iface;
                Type[] types = pt.getActualTypeArguments();
                if (types.length > 0 && types[0] instanceof Class) {
                    return (Class<?>) types[0];
                }
            }
        }
        return null;
    }

    /**
     * 获取集合方法参数中集合元素的实际泛型类型。
     *
     * @param method 目标方法
     * @param parameterType 期望匹配的参数原始类型，如 {@link Collection}
     * @return 首个匹配参数的元素泛型类型；未找到匹配参数或元素类型不是具体类时返回 {@code null}
     */
    public static Class<?> getCollectionActualParamType(Method method, Class<?> parameterType) {
        Type[] genericParamTypes = method.getGenericParameterTypes();
        for (int i = 0; i < genericParamTypes.length; i++) {
            if (genericParamTypes[i] instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) genericParamTypes[i];
                if (parameterType.isAssignableFrom((Class<?>) pt.getRawType())) {
                    Type[] actualTypes = pt.getActualTypeArguments();
                    if (actualTypes.length > 0 && actualTypes[0] instanceof Class) {
                        return (Class<?>) actualTypes[0];
                    }
                }
            }
        }
        return null;
    }

    /**
     * 通过方法名反射调用对象方法。
     *
     * @param invoker 方法调用的目标对象
     * @param methodName 公有方法名
     * @param params 调用时传入的实参数组
     * @return 目标方法的返回值，方法返回 {@code void} 时为 {@code null}
     * @throws Exception 方法不存在或方法执行过程中抛出异常时抛出
     */
    public static Object invoke(Object invoker, String methodName, Object[] params) throws Exception {
        Class<?> invokerCls = invoker.getClass();
        ClassStrucWrap classStrucWrap = ClassStrucWrap.get(invokerCls);
        return classStrucWrap.invokePublic(invoker, methodName, params);
    }
}
