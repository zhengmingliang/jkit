package com.alianga.jkit.reflect;

import com.alianga.jkit.ReflectionUtils;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.crypto.Hash64;
import com.alianga.jkit.exception.InvokeReflectException;
import com.alianga.jkit.json.internal.annotation.MethodInvokePriority;
import com.alianga.jkit.json.internal.beans.ArrayQueueMap;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/**
 * class序列化和反序列化结构包装
 *
 */
public final class ClassStrucWrap {
    // cache
    private static final Map<Class<?>, ClassStrucWrap> CLASS_STRUC_WRAP_MAP =
            new ArrayQueueMap<Class<?>, ClassStrucWrap>(8192);
    private static final Map<Class<?>, List<?>> COMPATIBLE_TYPES = new HashMap<Class<?>, List<?>>();
    // 内置类默认使用field序列化，可维护超类列表控制使用getter method
    private static final Class<?>[] USE_GETTER_METHOD_TYPE_LIST = {
            Throwable.class,
            Error.class
    };

    static {
        COMPATIBLE_TYPES.put(double.class,
                Arrays.asList(Double.class, long.class, Long.class, Float.class, float.class, Integer.class, int.class,
                        Short.class, short.class, byte.class, Byte.class));
        COMPATIBLE_TYPES.put(float.class,
                Arrays.asList(long.class, Long.class, Float.class, Integer.class, int.class, Short.class, short.class,
                        byte.class, Byte.class));
        COMPATIBLE_TYPES.put(long.class,
                Arrays.asList(Long.class, Integer.class, int.class, Short.class, short.class, byte.class, Byte.class));
        COMPATIBLE_TYPES.put(int.class, Arrays.asList(Integer.class, Short.class, short.class, byte.class, Byte.class));
        COMPATIBLE_TYPES.put(short.class, Arrays.asList(Short.class, byte.class, Byte.class));
        COMPATIBLE_TYPES.put(byte.class, Collections.singletonList(Byte.class));

        COMPATIBLE_TYPES.put(Double.class, Collections.singletonList(double.class));
        COMPATIBLE_TYPES.put(Float.class, Collections.singletonList(float.class));
        COMPATIBLE_TYPES.put(Long.class, Collections.singletonList(long.class));
        COMPATIBLE_TYPES.put(Integer.class, Collections.singletonList(int.class));
        COMPATIBLE_TYPES.put(Short.class, Collections.singletonList(short.class));
        COMPATIBLE_TYPES.put(Byte.class, Collections.singletonList(byte.class));
    }

    private ClassStrucWrap(Class<?> sourceClass) {
        this.sourceClass = sourceClass;
        this.privateFlag = Modifier.isPrivate(sourceClass.getModifiers());
        this.publicFlag = Modifier.isPublic(sourceClass.getModifiers());
        this.assignableFromMap = Map.class.isAssignableFrom(sourceClass);

        Map<Class<? extends Annotation>, Annotation> annotationMap =
                new HashMap<Class<? extends Annotation>, Annotation>();
        addAnnotations(annotationMap, sourceClass.getDeclaredAnnotations());
        this.annotationMap = annotationMap;
    }

    // jdk invoke
    private final Class<?> sourceClass;
    private final boolean privateFlag;
    private final boolean publicFlag;
    private final boolean assignableFromMap;
    private final Map<Class<? extends Annotation>, Annotation> annotationMap;

    // type
    private ClassWrapperType classWrapperType = ClassWrapperType.Normal;

    // is built in module
    private boolean javaBuiltInModule;

    // force use fields
    private boolean forceUseFields;

    // is record(jdk14+)
    private boolean record;

    // is Temporal
    private boolean temporal;

    private boolean subEnum;

    private int fieldCount;

    // setter的属性和SetterMethodInfo映射
    private Map<String, SetterInfo> setterInfos = new LinkedHashMap<String, SetterInfo>();

    /**
     * getter方法有序集合
     */
    private List<GetterInfo> getterInfos;

    /**
     * fieldAgent方法
     */
    private List<GetterInfo> getterInfoOfFields;

    // getter的属性和GetInfo映射
    private final Map<String, GetterInfo> getterInfoMap = new HashMap<String, GetterInfo>();

    private Map<String, FieldInfo> fieldInfoMap = new HashMap<String, FieldInfo>();

    private long fieldsCheckCode;

    /**
     * 构造方法参数
     */
    private Object[] constructorArgs;

    /**
     * 构造方法
     */
    private Constructor<?> defaultConstructor;

    /**
     * public 方法集合
     */
    volatile Map<String, List<Method>> publicMethods;

    /**
     * 获取所有getter方法映射的GetterMethodInfo信息
     *
     * @return getter 信息的有序集合
     */
    public List<GetterInfo> getGetterInfos() {
        return getterInfos;
    }

    /**
     * 获取使用属性代理的所有GetterMethodInfo信息
     *
     * @param fieldAgent 是否强制使用属性（field）代理方式取值
     * @return fieldAgent 为 {@code true} 或该类属于 JDK 内置模块时返回基于属性的 getter 信息集合，
     *         否则返回基于 getter 方法的集合
     */
    public List<GetterInfo> getGetterInfos(boolean fieldAgent) {
        if (fieldAgent || javaBuiltInModule) {
            return getterInfoOfFields;
        }
        return getterInfos;
    }

    // public getter 方法 or field
    /**
     * 按名称获取 getter 信息，名称可以是 getter 属性名，也可以是属性字段名。
     *
     * @param name getter 属性名或字段名
     * @return 匹配的 getter 信息，不存在时返回 {@code null}
     */
    public GetterInfo getGetterInfo(String name) {
        return getterInfoMap.get(name);
    }

    /**
     * If used to generate compiled code, the method takes priority
     *
     * @param name getter 属性名
     * @return 优先在 getter 方法集合中匹配，其次在属性集合中匹配，都未匹配到时返回 {@code null}
     */
    public GetterInfo matchGenerateGetterInfo(String name) {
        for (GetterInfo getterInfo : getterInfos) {
            if (name.equals(getterInfo.getName())/*|| name.equals(getterInfo.getUnderlineName())*/) {
                return getterInfo;
            }
        }
        for (GetterInfo getterInfo : getterInfoOfFields) {
            if (name.equals(getterInfo.getName())/*|| name.equals(getterInfo.getUnderlineName())*/) {
                return getterInfo;
            }
        }
        return null;
    }

    private void fillGetterInfoMap() {
        for (GetterInfo getterInfo : getterInfos) {
            if (getterInfo.existField()) {
                String name = getterInfo.getField().getName();
                FieldInfo fieldInfo = fieldInfoMap.get(name);
                if (fieldInfo != null) {
                    getterInfo.setGenericParameterizedType(fieldInfo.getSetterInfo().getGenericParameterizedType());
                }
            } else {
                if (getterInfo.getGenericParameterizedType() == null) {
                    getterInfo.setGenericParameterizedType(
                            GenericParameterizedType.actualType(getterInfo.getReturnType()));
                }
            }
            getterInfoMap.put(getterInfo.getName(), getterInfo);
        }
        for (GetterInfo getterInfo : getterInfoOfFields) {
            getterInfoMap.put(getterInfo.getName(), getterInfo);
            getterInfoMap.put(getterInfo.getField().getName(), getterInfo);
        }
    }

    /**
     * 按名称获取 setter 信息。
     *
     * @param name setter 对应的属性名
     * @return 匹配的 setter 信息，不存在时返回 {@code null}
     */
    public SetterInfo getSetterInfo(String name) {
        return setterInfos.get(name);
    }

    /**
     * 判断是否存在指定名称的 setter 信息。
     *
     * @param fieldName 属性名
     * @return 存在时返回 {@code true}，否则返回 {@code false}
     */
    public boolean containsSetterKey(String fieldName) {
        return setterInfos.containsKey(fieldName);
    }

    /**
     * 获取被包装的原始类。
     *
     * @return 当前包装的目标类
     */
    public Class<?> getSourceClass() {
        return sourceClass;
    }

    /**
     * 使用默认构造方法与内部缓存的构造参数创建实例。
     *
     * @return 新创建的实例
     * @throws Exception 构造方法调用失败时抛出
     */
    public Object newInstance() throws Exception {
//        return UnsafeHelper.getUnsafe().allocateInstance(sourceClass);
        return defaultConstructor.newInstance(constructorArgs);
    }

    /**
     * 使用指定参数调用默认构造方法创建实例。
     *
     * @param constructorArgs 构造方法参数
     * @return 新创建的实例
     * @throws Exception 构造方法调用失败时抛出
     */
    public Object newInstance(Object[] constructorArgs) throws Exception {
        return defaultConstructor.newInstance(constructorArgs);
    }

    /**
     * 判断目标类是否为 {@link Map} 的实现类。
     *
     * @return 是 Map 实现类时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAssignableFromMap() {
        return assignableFromMap;
    }

    /**
     * 判断目标类是否为 record 类型（JDK14+）。
     *
     * @return 是 record 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isRecord() {
        return record;
    }

    /**
     * 判断目标类是否为时间类型（{@code java.time} 下的 Temporal 实现）。
     *
     * @return 是时间类型时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isTemporal() {
        return temporal;
    }

    /**
     * 判断目标类是否为枚举的匿名子类。
     *
     * @return 是枚举子类时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isSubEnum() {
        return subEnum;
    }

    /**
     * 获取参与序列化的属性个数，record 类型为其构造方法参数个数。
     *
     * @return 属性个数
     */
    public int getFieldCount() {
        return fieldCount;
    }

    /**
     * 判断是否强制使用属性（field）方式读写。
     *
     * @return 强制使用属性时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isForceUseFields() {
        return forceUseFields;
    }

    /**
     * 获取类的包装类型，用于区分普通 pojo、record 与各种时间类型。
     *
     * @return 当前的类包装类型
     */
    public ClassWrapperType getClassWrapperType() {
        return classWrapperType;
    }

    /**
     * 创建一份构造方法参数数组的副本，元素为各参数类型的默认值。
     *
     * @return 长度为属性个数的构造参数数组副本
     */
    public Object[] createConstructorArgs() {
        Object[] constructorArgs = new Object[fieldCount];
        System.arraycopy(this.constructorArgs, 0, constructorArgs, 0, fieldCount);
        return constructorArgs;
    }

    /**
     * 获取指定类的结构包装对象，结果会被缓存复用。
     *
     * @param sourceClass 目标类
     * @return 该类的结构包装对象；接口、枚举、数组和基本类型返回 {@code null}
     * @throws IllegalArgumentException sourceClass 为 {@code null} 时抛出
     */
    public static ClassStrucWrap get(Class<?> sourceClass) {
        if (sourceClass == null) {
            throw new IllegalArgumentException("sourceClass is null");
        }
        ClassStrucWrap wrapper = CLASS_STRUC_WRAP_MAP.get(sourceClass);
        if (wrapper != null) {
            return wrapper;
        }
        if (sourceClass.isInterface() || sourceClass.isEnum() || sourceClass.isArray() || sourceClass.isPrimitive()) {
            return null;
        }
        synchronized (sourceClass) {
            if (CLASS_STRUC_WRAP_MAP.containsKey(sourceClass)) {
                return CLASS_STRUC_WRAP_MAP.get(sourceClass);
            }
            wrapper = createBy(sourceClass);
            CLASS_STRUC_WRAP_MAP.put(sourceClass, wrapper);
        }
        return wrapper;
    }

    /**
     * 创建枚举类的结构包装对象，并强制使用属性方式读写，结果不进入缓存。
     *
     * @param enumClass 枚举类
     * @return 该枚举类的结构包装对象
     * @throws UnsupportedOperationException 入参不是枚举类时抛出
     */
    public static ClassStrucWrap ofEnumClass(Class<? extends Enum> enumClass) {
        if (!enumClass.isEnum()) {
            throw new UnsupportedOperationException("not enum class " + enumClass);
        }
        ClassStrucWrap wrapper = createBy(enumClass);
        wrapper.forceUseFields = true;
        return wrapper;
    }

    private static ClassStrucWrap createBy(Class<?> sourceClass) {
        ClassStrucWrap wrapper = new ClassStrucWrap(sourceClass);
        wrapper.checkClassStructure();
        // parse genericClass
        Type genericSuperclass = sourceClass.getGenericSuperclass();
        Map<String, Class<?>> superGenericClassMap = new HashMap<String, Class<?>>();
        if (genericSuperclass instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
            Type[] types = parameterizedType.getActualTypeArguments();
            Class<?> superclass = (Class<?>) parameterizedType.getRawType();
            TypeVariable<?>[] typeParameters = superclass.getTypeParameters();
            int i = 0;
            for (TypeVariable<?> typeVariable : typeParameters) {
                String name = typeVariable.getName();
                Type actualTypeArgument = types[i++];
                if (actualTypeArgument instanceof Class) {
                    superGenericClassMap.put(name, (Class<?>) actualTypeArgument);
                }
            }
        }
        if (wrapper.record) {
            // Initialize the wrapper by constructing information
            wrapperWithRecordConstructor(wrapper, superGenericClassMap);
        } else {
            // Initialize the wrapper through the specifications (conventions) of pojo or Javabeans, namely method or
            // field
            wrapperWithMethodAndField(wrapper, superGenericClassMap);
        }
        return wrapper;
    }

    // record use getter method and parameters construction
    private static void wrapperWithRecordConstructor(ClassStrucWrap wrapper,
                                                     Map<String, Class<?>> superGenericClassMap) {
        // sourceClass
        Class<?> sourceClass = wrapper.sourceClass;
        Constructor<?>[] constructors = sourceClass.getDeclaredConstructors();
        if (constructors.length == 0) {
            return;
        }
        Constructor<?> constructor = null; // constructors[0];
        int maxParameterCount = 0;
        for (Constructor<?> c : constructors) {
            if (constructor == null || c.getParameterCount() > maxParameterCount) {
                constructor = c;
                maxParameterCount = constructor.getParameterCount();
            }
        }

        wrapper.defaultConstructor = constructor;
        setAccessible(constructor);

        List<GetterInfo> getterInfoOfFields = new ArrayList<GetterInfo>();
        wrapper.getterInfoOfFields = getterInfoOfFields;
        wrapper.getterInfos = getterInfoOfFields;
        try {
            // parameters数组
            Object[] parameters = (Object[]) getParametersMethod.invoke(constructor);
            int len = parameters.length;
            wrapper.fieldCount = len;
            Method parameterNameMethod = null;
            Type[] genericParameterTypes = constructor.getGenericParameterTypes();

            Object[] constructorArgs = new Object[len];
            wrapper.constructorArgs = constructorArgs;

            Map<String, FieldInfo> fieldInfoMap = new HashMap<String, FieldInfo>();
            long fieldsCheckCode = 0;
            for (int i = 0; i < len; i++) {
                Object parameter = parameters[i];
                if (parameterNameMethod == null) {
                    parameterNameMethod = parameter.getClass().getMethod("getName");
                    setAccessible(parameterNameMethod);
                }
                // invoke name
                String name = (String) parameterNameMethod.invoke(parameter);
                if (fieldsCheckCode == 0) {
                    fieldsCheckCode = Hash64.hash(name);
                } else {
                    fieldsCheckCode = Hash64.hash(fieldsCheckCode, name);
                }
                FieldInfo fieldInfo = new FieldInfo();
                fieldInfo.setName(name);
                fieldInfo.setIndex(i);

                Field nameField = sourceClass.getDeclaredField(name);
                setAccessible(nameField);
                clearFinalModifiers(nameField);
                Method nameMethod = sourceClass.getDeclaredMethod(name);
                setAccessible(nameMethod);

                Class<?> fieldType = nameField.getType();
                constructorArgs[i] = defaulTypeValue(fieldType);

                GetterInfo getterInfo = new GetterMethodInfo(nameMethod);
                getterInfo.setField(nameField);
                getterInfo.setRecord(true);

                getterInfo.setName(name);
                getterInfo.setUnderlineName(StringUtils.camelCaseToSymbol(name));

                Map<Class<? extends Annotation>, Annotation> annotationMap =
                        new HashMap<Class<? extends Annotation>, Annotation>();
                addAnnotations(annotationMap, nameMethod.getAnnotations());

                getterInfo.setAnnotations(annotationMap);
                getterInfoOfFields.add(getterInfo);

                // 构建setter
                SetterInfo setterInfo = new SetterInfo.FieldImpl();
                setterInfo.setName(name);
                setterInfo.setField(nameField);
                setterInfo.setParameterType(fieldType);
                setterInfo.setIndex(i);
                Type genericType = genericParameterTypes[i];
                Class<?> declaringClass = nameField.getDeclaringClass();
                // parse
                parseSetterGenericType(superGenericClassMap, sourceClass, declaringClass, setterInfo, genericType,
                        fieldType);
                setterInfo.setAnnotations(annotationMap);
                // put to setterInfos
                wrapper.setterInfos.put(name, setterInfo);

                fieldInfo.setGetterInfo(getterInfo);
                fieldInfo.setSetterInfo(setterInfo);
                fieldInfoMap.put(name, fieldInfo);
            }
            wrapper.fieldInfoMap = fieldInfoMap;
            wrapper.fieldsCheckCode = fieldsCheckCode;
        } catch (Throwable ignored) {
        }
    }

    private static void wrapperWithMethodAndField(ClassStrucWrap wrapper, Map<String, Class<?>> superGenericClassMap) {
        // sourceClass
        Class<?> sourceClass = wrapper.sourceClass;
        boolean globalMIP = wrapper.annotationMap.containsKey(MethodInvokePriority.class);
        Constructor<?>[] constructors = sourceClass.getDeclaredConstructors();
        Constructor<?> defaultConstructor = null;
        int minParamCount = -1;
        Class<?>[] constructorParameterTypes = null;
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            int parameterCount = parameterTypes.length;
            if (minParamCount == -1 || minParamCount > parameterCount) {
                minParamCount = parameterCount;
                defaultConstructor = constructor;
                constructorParameterTypes = parameterTypes;
            }
            if (minParamCount == 0) {
                break;
            }
            if (minParamCount == parameterCount) {
                // 优先使用基本类型构造，防止在构造函数中出现NPE
                for (int i = 0; i < parameterCount; i++) {
                    if (parameterTypes[i].isPrimitive() && !constructorParameterTypes[i].isPrimitive()) {
                        defaultConstructor = constructor;
                        constructorParameterTypes = parameterTypes;
                        break;
                    }
                }
            }
        }

        setAccessible(defaultConstructor);
        Object[] args = new Object[minParamCount];
        for (int i = 0; i < minParamCount; i++) {
            Class<?> type = constructorParameterTypes[i];
            args[i] = defaulTypeValue(type);
        }

        wrapper.defaultConstructor = defaultConstructor;
        wrapper.constructorArgs = args;

        List<GetterInfo> getterInfos = new ArrayList<GetterInfo>();

        // public methods
        Method[] methods = sourceClass.getMethods();
        for (Method method : methods) {
            if (method.isSynthetic()) {
                continue;
            }
            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass == Object.class || Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            String methodName = method.getName();
            Class<?> returnType = method.getReturnType();
            Class<?>[] parameterTypes = method.getParameterTypes();

            boolean startsWithGet;
            boolean isVoid = returnType == void.class;
            if (parameterTypes.length == 0 &&
                    ((startsWithGet = methodName.startsWith("get")) || methodName.startsWith("is"))
                    && !isVoid) {
                int startIndex = startsWithGet ? 3 : 2;
                if (methodName.length() == startIndex) {
                    continue;
                }
                boolean boolGetter = !startsWithGet;
                if (boolGetter && returnType != boolean.class) {
                    // isXXX only supported boolean
                    continue;
                }
                // getter方法
                setAccessible(method);
                GetterMethodInfo getterInfo = new GetterMethodInfo(method);
                getterInfo.setGenericParameterizedType(GenericParameterizedType.of(method.getGenericReturnType()));

                String fieldName = new String(methodName.substring(startIndex));
                char[] fieldNameChars = UnsafeHelper.getChars(fieldName);
                if (fieldNameChars.length == 1 || !Character.isUpperCase(fieldNameChars[1])) {
                    fieldNameChars[0] = Character.toLowerCase(fieldNameChars[0]);
                    fieldName = new String(fieldNameChars);
                }

                getterInfo.setName(fieldName);
                getterInfo.setUnderlineName(StringUtils.camelCaseToSymbol(fieldName));

                // load annotations
                Map<Class<? extends Annotation>, Annotation> annotationMap =
                        new HashMap<Class<? extends Annotation>, Annotation>();
                addAnnotations(annotationMap, method.getAnnotations());
                if (!globalMIP && !annotationMap.containsKey(MethodInvokePriority.class)) {
                    try {
                        // declared field, not considering inheriting
                        Field refField = ReflectionUtils.getField(declaringClass, fieldName);
                        if (refField != null) {
                            if (!Modifier.isStatic(refField.getModifiers())) {
                                // 当声明属性的类型和getter方法返回的类型不一致时，如果触发invoke，则以method的call为准
                                if (setAccessible(refField) && compatibleType(returnType, refField.getType())) {
                                    getterInfo.setField(refField);
                                }
                            }
                            addAnnotations(annotationMap, refField.getAnnotations());
                        } else {
                            if (boolGetter) {
                                // isXXX
                                try {
                                    Field field = ReflectionUtils.getField(declaringClass, methodName);
                                    if (field != null) {
                                        if (!Modifier.isStatic(field.getModifiers())) {
                                            // 当声明属性的类型和getter方法返回的类型不一致时，如果触发invoke，则以method的call为准
                                            if (setAccessible(field) && field.getType() == boolean.class) {
                                                getterInfo.setField(field);
                                                getterInfo.setName(field.getName());
                                                getterInfo.setUnderlineName(
                                                        StringUtils.camelCaseToSymbol(field.getName()));
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                getterInfo.setAnnotations(annotationMap);
                getterInfos.add(getterInfo);

            } else if (parameterTypes.length == 1 && methodName.startsWith("set")
                    && isVoid) {
                if (methodName.length() == 3) {
                    continue;
                }

                // setter方法
                setAccessible(method);
                SetterMethodInfo setterInfo = new SetterMethodInfo(method);

//                String setFieldName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
                String setFieldName = new String(methodName.substring(3));
                char[] fieldNameChars = UnsafeHelper.getChars(setFieldName);
                if (fieldNameChars.length == 1 || !Character.isUpperCase(fieldNameChars[1])) {
                    fieldNameChars[0] = Character.toLowerCase(fieldNameChars[0]);
                    setFieldName = new String(fieldNameChars);
                }

                wrapper.setterInfos.put(setFieldName, setterInfo);
                // Support underline to camelCase
                String underlineName = StringUtils.camelCaseToSymbol(setFieldName);
                wrapper.setterInfos.put(underlineName, setterInfo);

                setterInfo.setName(setFieldName);
                Class<?> parameterType = parameterTypes[0];
                setterInfo.setParameterType(parameterType);

                Type genericType = method.getGenericParameterTypes()[0];
                parseSetterGenericType(superGenericClassMap, sourceClass, declaringClass, setterInfo, genericType,
                        parameterType);

                // 解析setter和field注解集合
                Map<Class<? extends Annotation>, Annotation> annotationMap =
                        new HashMap<Class<? extends Annotation>, Annotation>();

                Annotation[] methodAnnotations = method.getAnnotations();
                addAnnotations(annotationMap, methodAnnotations);
                if (!globalMIP && !annotationMap.containsKey(MethodInvokePriority.class)) {
                    try {
                        Field field = sourceClass.getDeclaredField(setFieldName);
                        if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                            if (setAccessible(field) && compatibleType(field.getType(), parameterType)) {
                                setterInfo.setField(field);
                            } else {
                                setterInfo.setFieldDisabled(true);
                            }
                        }
                        Annotation[] fieldAnnotations = field.getAnnotations();
                        addAnnotations(annotationMap, fieldAnnotations);
                    } catch (Exception ignored) {
                    }
                }
                setterInfo.setAnnotations(annotationMap);
            }
        }

        // Resolve all fields
        parseWrapperFields(wrapper, sourceClass, superGenericClassMap);

        // Sort output to prevent inconsistent serialization order after each restart of the JVM
        Collections.sort(getterInfos, (o1, o2) -> o1.getName().compareTo(o2.getName()));

        wrapper.getterInfos = Collections.unmodifiableList(getterInfos);
        wrapper.setterInfos = Collections.unmodifiableMap(wrapper.setterInfos);
        wrapper.fillGetterInfoMap();
        if (wrapper.getterInfos.isEmpty() && wrapper.getterInfoOfFields != null &&
                wrapper.getterInfoOfFields.size() > 0) {
            wrapper.forceUseFields = true;
        }
    }

    private static boolean compatibleType(Class<?> type, Class<?> parameterType) {
        if (type.isAssignableFrom(parameterType)) {
            return true;
        }
        List<?> types = COMPATIBLE_TYPES.get(type);
        if (types == null) {
            return false;
        }
        return types.contains(parameterType);
    }

    private static Object defaulTypeValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        } else if (type.isPrimitive()) {
            if (type == char.class) {
                return (char) 0;
            } else if (type == byte.class) {
                return (byte) 0;
            } else if (type == short.class) {
                return (short) 0;
            }
            return 0;
        } else if (type == String.class) {
            return "";
        } else if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        } else {
            return null;
        }
    }

    private void checkClassStructure() {
        String pckName = sourceClass.getPackage().getName();
        if (pckName.startsWith("java.") || pckName.startsWith("sun.")) {
            this.javaBuiltInModule = true;
        }

        // jdk17 java.lang.Record
        Class<?> theSuperClass = sourceClass.getSuperclass();
        if (theSuperClass == null) {
            return;
        }
        if (theSuperClass.getName().equals("java.lang.Record")) {
            this.record = true;
            this.javaBuiltInModule = true;
            this.classWrapperType = ClassWrapperType.Record;
        }

        if (theSuperClass.isEnum()) {
            this.subEnum = true;
        }

        if (javaBuiltInModule) {
            forceUseFields = true;
            for (Class<?> superClass : USE_GETTER_METHOD_TYPE_LIST) {
                if (superClass.isAssignableFrom(sourceClass)) {
                    forceUseFields = false;
                    break;
                }
            }
            String className = sourceClass.getName();
            if (className.equals("java.time.LocalDate")) {
                this.classWrapperType = ClassWrapperType.TemporalLocalDate;
                this.temporal = true;
            } else if (className.equals("java.time.LocalTime")) {
                this.classWrapperType = ClassWrapperType.TemporalLocalTime;
                this.temporal = true;
            } else if (className.equals("java.time.LocalDateTime")) {
                this.classWrapperType = ClassWrapperType.TemporalLocalDateTime;
                this.temporal = true;
            } else if (className.equals("java.time.Instant")) {
                this.classWrapperType = ClassWrapperType.TemporalInstant;
                this.temporal = true;
            } else if (className.equals("java.time.ZonedDateTime")) {
                this.classWrapperType = ClassWrapperType.TemporalZonedDateTime;
                this.temporal = true;
            } else if (className.equals("java.time.OffsetDateTime")) {
                this.classWrapperType = ClassWrapperType.TemporalOffsetDateTime;
                this.temporal = true;
            } else if (className.equals("java.time.MonthDay")) {
                this.classWrapperType = ClassWrapperType.TemporalMonthDay;
                this.temporal = true;
            } else if (className.equals("java.time.YearMonth")) {
                this.classWrapperType = ClassWrapperType.TemporalYearMonth;
                this.temporal = true;
            }
        }
    }

    private static void parseSetterGenericType(Map<String, Class<?>> superGenericClassMap, Class<?> sourceClass,
                                               Class<?> declaringClass, SetterInfo setterInfo, Type genericType,
                                               Class<?> parameterType) {
        GenericParameterizedType<?> genericParameterizedType = null;
        if (Collection.class.isAssignableFrom(parameterType)) {
            if (genericType instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) genericType;
                Type type = pt.getActualTypeArguments()[0];
                if (type instanceof Class<?>) {
                    setterInfo.setActualTypeArgument((Class<?>) type);
                }
                genericParameterizedType = GenericParameterizedType.genericCollectionType(parameterType, type);
            } else {
                genericParameterizedType = GenericParameterizedType.createInternal(parameterType);
                Class<?> valueClass = ReflectionUtils.getActualType(parameterType);
                if (valueClass != null) {
                    genericParameterizedType.valueType = GenericParameterizedType.createInternal(valueClass);
                }
            }
        } else if (parameterType.isArray()) {
            Class<?> componentType = parameterType.getComponentType();
            setterInfo.setActualTypeArgument(componentType);
            if (genericType instanceof GenericArrayType) {
                GenericArrayType genericArrayType = (GenericArrayType) genericType;
                Type genericComponentType = genericArrayType.getGenericComponentType();
                genericParameterizedType = GenericParameterizedType.genericArrayType(genericComponentType);
            } else {
                genericParameterizedType = GenericParameterizedType.arrayType(componentType);
            }
        } else if (Map.class.isAssignableFrom(parameterType)) {
            if (genericType instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) genericType;
                Type[] actualTypeArguments = pt.getActualTypeArguments();
                if (actualTypeArguments.length == 2) {
                    genericParameterizedType =
                            GenericParameterizedType.genericMapType(parameterType, actualTypeArguments[0],
                                    actualTypeArguments[1]);
                }
            } else {
                // 没有泛型创建普通实体类泛型结构
                genericParameterizedType = GenericParameterizedType.createInternal(parameterType);
            }
        } else {
            if (parameterType.isInterface() || Modifier.isAbstract(parameterType.getModifiers())) {
                // Map(LinkHashMap, HashMap)和Collection(ArayList)都有缺省实现类，其他接口或者抽象类,无法通过newInstance反射创建实例
                // 可以根据设置属性默认值来获取实际实例化的类型
                // 基本类型需要排除
                if (!parameterType.isPrimitive()) {
                    setterInfo.setNonInstanceType(true);
                }
            }
            if (genericType instanceof TypeVariable) {
                // 伪泛型
                TypeVariable<?> typeVariable = (TypeVariable<?>) genericType;
                String name = typeVariable.getName();
                if (declaringClass != sourceClass) {
                    // maybe parent method
                    Class<?> superGenericClass = superGenericClassMap.get(name);
                    genericParameterizedType = GenericParameterizedType.createInternal(superGenericClass);
                } else {
                    genericParameterizedType =
                            GenericParameterizedType.genericEntityType(parameterType, typeVariable.getName());
                }
            } else if (genericType instanceof ParameterizedType) {
                // 实泛型
                ParameterizedType pt = (ParameterizedType) genericType;
                Type[] actualTypeArguments = pt.getActualTypeArguments();
                if (actualTypeArguments.length == 1) {
                    Type actualTypeArgument = actualTypeArguments[0];
                    if (actualTypeArgument instanceof Class) {
                        genericParameterizedType =
                                GenericParameterizedType.entityType(parameterType, (Class<?>) actualTypeArgument);
                    } else {
                        genericParameterizedType = GenericParameterizedType.createInternal(parameterType);
                    }
                } else {
                    TypeVariable<?>[] typeParameters = parameterType.getTypeParameters();
                    int i = 0;
                    Map<String, Class<?>> genericClassMap = new HashMap<String, Class<?>>();
                    for (TypeVariable<?> typeVariable : typeParameters) {
                        String name = typeVariable.getName();
                        Type actualTypeArgument = actualTypeArguments[i++];
                        if (actualTypeArgument instanceof Class) {
                            genericClassMap.put(name, (Class<?>) actualTypeArgument);
                        }
                    }
                    genericParameterizedType = GenericParameterizedType.entityType(parameterType, genericClassMap);
                }
            } else {
                genericParameterizedType = GenericParameterizedType.createInternal(parameterType);
            }
        }

        if (genericParameterizedType != null) {
            setterInfo.setGenericParameterizedType(genericParameterizedType);
        }
    }

    /**
     * 解析类的所有字段（包含父类字段）
     */
    private static void parseWrapperFields(ClassStrucWrap wrapper, Class<?> sourceClass,
                                           Map<String, Class<?>> superGenericClassMap) {
        Class<?> target = sourceClass;
        Set<String> fieldNames = new HashSet<String>();
        List<GetterInfo> getterInfoOfFields = new ArrayList<GetterInfo>();
        Map<String, FieldInfo> fieldInfoMap = new HashMap<String, FieldInfo>();
        int cnt = 0;
        int index = 0;
        long fieldsCheckCode = 0;
        while (target != Object.class) {
            Field[] fields = target.getDeclaredFields();
            for (Field field : fields) {
                if (field.isSynthetic()) {
                    continue;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                String fieldName = field.getName();
                String underlineName = StringUtils.camelCaseToSymbol(fieldName);
                if (fieldNames.add(fieldName)) {
                    setAccessible(field);
                    clearFinalModifiers(field);

                    FieldInfo fieldInfo = new FieldInfo();
                    fieldInfo.setName(fieldName);
                    fieldInfo.setIndex(index++);

                    if (fieldsCheckCode == 0) {
                        fieldsCheckCode = Hash64.hash(fieldName);
                    } else {
                        fieldsCheckCode = Hash64.hash(fieldsCheckCode, fieldName);
                    }
                    Class<?> fieldType = field.getType();
                    // 构建getter
                    GetterInfo getterInfo = new GetterInfo();
                    getterInfo.setField(field);
                    getterInfo.setName(fieldName);
                    getterInfo.setUnderlineName(underlineName);

                    Map<Class<? extends Annotation>, Annotation> annotationMap =
                            new HashMap<Class<? extends Annotation>, Annotation>();
                    addAnnotations(annotationMap, field.getAnnotations());

                    getterInfo.setAnnotations(annotationMap);
                    getterInfoOfFields.add(getterInfo);
                    fieldInfo.setGetterInfo(getterInfo);

                    // create setter
                    SetterInfo setterInfo = SetterInfo.fromField(field);
                    setterInfo.setName(fieldName);
                    setterInfo.setField(field);
                    setterInfo.setParameterType(fieldType);

                    Type genericType = field.getGenericType();
                    Class<?> declaringClass = field.getDeclaringClass();
                    // parse Generic Type
                    parseSetterGenericType(superGenericClassMap, sourceClass, declaringClass, setterInfo, genericType,
                            fieldType);
                    setterInfo.setAnnotations(annotationMap);

                    // Consistent getter and setter generic information reflected by attributes
                    getterInfo.setGenericParameterizedType(setterInfo.getGenericParameterizedType());

                    SetterInfo oldSetterInfo = wrapper.setterInfos.get(fieldName);
                    if (oldSetterInfo == null || !oldSetterInfo.isFieldDisabled()) {
                        wrapper.setterInfos.put(fieldName, setterInfo);
                    }

                    if (!underlineName.equals(fieldName)) {
                        wrapper.setterInfos.put(underlineName, setterInfo);
                    }
                    fieldInfo.setSetterInfo(setterInfo);
                    fieldInfoMap.put(fieldName, fieldInfo);
                }
            }
            target = target.getSuperclass();
            // Avoid deadlock
            if (++cnt == 100) {
                break;
            }
        }
        wrapper.getterInfoOfFields = Collections.unmodifiableList(getterInfoOfFields);
        wrapper.fieldInfoMap = fieldInfoMap;
        wrapper.fieldsCheckCode = fieldsCheckCode;
    }

    static final Field modifierField;
    static final Method getParametersMethod;

    static {
        Field field = null;
        try {
            field = Field.class.getDeclaredField("modifiers");
            setAccessible(field);
        } catch (Exception e) {
            try {
                Method getDeclaredFields0 = Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
                setAccessible(getDeclaredFields0);
                Field[] fields = (Field[]) getDeclaredFields0.invoke(Field.class, false);
                for (Field target : fields) {
                    if ("modifiers".equals(target.getName())) {
                        field = target;
                        setAccessible(field);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        modifierField = field;

        // jdk8+ supported
        Method parametersMethod = null;
        try {
            parametersMethod = Method.class.getMethod("getParameters");
            parametersMethod.setAccessible(true);
            setAccessible(parametersMethod);
        } catch (Exception ignored) {
        }
        getParametersMethod = parametersMethod;
    }

    private static void clearFinalModifiers(Field field) {
        if (modifierField != null) {
            try {
                modifierField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean setAccessible(AccessibleObject accessibleObject) {
        try {
            boolean accessible = UnsafeHelper.setAccessible(accessibleObject);
            if (accessible) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            accessibleObject.setAccessible(true);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 获取所有setter信息的名称set
     *
     * @return 所有 setter 对应的属性名集合
     */
    public Set<String> setterNames() {
        return setterInfos.keySet();
    }

    private static void addAnnotations(Map<Class<? extends Annotation>, Annotation> annotationMap,
                                       Annotation[] annotationArr) {
        if (annotationMap == null || annotationArr == null) {
            return;
        }
        for (Annotation annotation : annotationArr) {
            annotationMap.put(annotation.annotationType(), annotation);
        }
    }

    /**
     * 按方法名反射调用目标类的 public 方法，首次调用会缓存该类所有 public 方法。
     *
     * <p>只有一个同名方法时会尝试把实参转换为形参类型；存在重载时按实参类型匹配。
     *
     * @param invoker    方法调用者实例，静态方法可传 {@code null}
     * @param methodName 方法名
     * @param params     调用参数
     * @return 方法的返回值，void 方法返回 {@code null}
     * @throws UnsupportedOperationException 方法不存在、不是 public 方法或参数无法匹配时抛出
     * @throws InvokeReflectException        反射调用过程中出现异常时抛出
     */
    public Object invokePublic(Object invoker, String methodName, Object[] params) {
        if (publicMethods == null) {
            synchronized (this) {
                if (publicMethods == null) {
                    publicMethods = new HashMap<String, List<Method>>();
                    Method[] methods = sourceClass.getMethods();
                    for (Method method : methods) {
                        String name = method.getName();
                        List<Method> nameMethods = publicMethods.get(name);
                        if (nameMethods == null) {
                            nameMethods = new ArrayList<Method>();
                            publicMethods.put(name.intern(), nameMethods);
                        }
                        setAccessible(method);
                        nameMethods.add(method);
                    }
                }
            }
        }
        List<Method> nameMethods = publicMethods.get(methodName);
        if (nameMethods == null) {
            throw new UnsupportedOperationException("method " + methodName + " is not exist or not a public method ");
        }
        try {
            if (nameMethods.size() == 1) {
                final Method method = nameMethods.get(0);
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != params.length) {
                    throw new IllegalArgumentException("argument mismatch");
                }
                for (int i = 0, n = params.length; i < n; ++i) {
                    Object value = params[i];
                    Class<?> parameterType = parameterTypes[i];
                    if (!ObjectUtils.isInstance(parameterType, value)) {
                        try {
                            params[i] = ObjectUtils.toType(value, parameterType,
                                    ReflectConsts.getClassCategory(parameterType));
                        } catch (Throwable throwable) {
                            throw new IllegalArgumentException("argument mismatch: " + parameterType + " ");
                        }
                    }
                }
                return method.invoke(invoker, params);
            }
            for (Method method : nameMethods) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == params.length) {
                    boolean matched = true;
                    for (int i = 0; i < parameterTypes.length; i++) {
                        if (params[i] != null && !ObjectUtils.isInstance(parameterTypes[i], params[i])) {
                            matched = false;
                            break;
                        }
                    }
                    if (matched) {
                        return method.invoke(invoker, params);
                    }
                }
            }
            throw new UnsupportedOperationException(
                    "method " + methodName + " of " + sourceClass + " Parameter mismatch ");
        } catch (Throwable throwable) {
            throw new InvokeReflectException(throwable);
        }
    }

    /**
     * 获取所有 setter 信息的集合。
     *
     * @return 由所有 setter 信息构成的新 HashSet
     */
    public Set<SetterInfo> setterSet() {
        return new HashSet<SetterInfo>(setterInfos.values());
    }

    /**
     * 获取所有属性信息。
     *
     * @return 由所有属性信息构成的新数组
     */
    public FieldInfo[] getFieldInfos() {
        FieldInfo[] fieldInfos = new FieldInfo[fieldInfoMap.size()];
        return fieldInfoMap.values().toArray(fieldInfos);
    }

    /**
     * 获取由所有属性名累积计算出的 64 位校验码，可用于快速判断类结构是否一致。
     *
     * @return 属性名的哈希校验码，无属性时为 0
     */
    public long getFieldsCheckCode() {
        return fieldsCheckCode;
    }

    /**
     * 判断目标类是否为 private 修饰。
     *
     * @return 是 private 类时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPrivate() {
        return privateFlag;
    }

    /**
     * 判断目标类是否为 public 修饰。
     *
     * @return 是 public 类时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPublic() {
        return publicFlag;
    }

    /**
     * 判断目标类是否属于 JDK 内置模块。
     *
     * @return 属于内置模块时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isJavaBuiltInModule() {
        return javaBuiltInModule;
    }

    /**
     * 获取目标类上声明的指定注解。
     *
     * @param annotationClass 注解类型
     * @return 该类上声明的注解实例，未声明时返回 {@code null}
     */
    public Annotation getDeclaredAnnotation(Class<? extends Annotation> annotationClass) {
        return annotationMap.get(annotationClass);
    }

    /**
     * 类结构包装类型，用于区分普通 pojo、record 以及各种时间类型。
     */
    public enum ClassWrapperType {
        /**
         * 普通的pojo
         */
        Normal,

        /**
         * record(jdk15+) support
         */
        Record,

        /**
         * MonthDay(jdk8+) support
         */
        TemporalMonthDay,

        /**
         * YearMonth(jdk8+) support
         */
        TemporalYearMonth,

        /**
         * LocalDate(jdk8+) support
         */
        TemporalLocalDate,

        /**
         * LocalDateTime(jdk8+) support
         */
        TemporalLocalDateTime,

        /**
         * LocalTime(jdk8+) support
         */
        TemporalLocalTime,

        /**
         * Instant(jdk8+) support
         */
        TemporalInstant,

        /**
         * ZonedDateTime(jdk8+) support
         */
        TemporalZonedDateTime,

        /**
         * OffsetDateTime(jdk8+) support
         */
        TemporalOffsetDateTime
    }
}
