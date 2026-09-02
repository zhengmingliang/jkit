package com.alianga.jkit.reflect;

import com.alianga.jkit.json.internal.beans.ArrayQueueMap;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 反射常量
 *
 * @time 2019/12/21 15:27
 */
public final class ReflectConsts {
    /**
     * Cache type class classification
     */
    private static final Map<Class, ClassCategory> classClassCategoryMap =
            new ArrayQueueMap<Class, ClassCategory>(8192);

    static {
        // 字节数组(将Byte除去)
        putAllType(ClassCategory.Binary, byte[].class/*, Byte[].class*/);

        /** 预置常用集合类型 */
        putAllType(ClassCategory.CollectionCategory, ArrayList.class, HashSet.class);

        /** 预置常用Map类型 */
        putAllType(ClassCategory.MapCategory, HashMap.class, LinkedHashMap.class);

        /** 预置常用CharSequence类型 */
        putAllType(ClassCategory.CharSequence, String.class, char[].class, StringBuilder.class, StringBuffer.class,
                char.class, Character.class);

        /** 预置常用Number类型 */
        putAllType(ClassCategory.NumberCategory, BigDecimal.class, BigInteger.class, int.class, Integer.class,
                byte.class, Byte.class, short.class, Short.class, float.class, Float.class, long.class, Long.class,
                double.class, Double.class);

        /** boolean */
        putAllType(ClassCategory.BoolCategory, boolean.class, Boolean.class);

        /** Date */
        putAllType(ClassCategory.DateCategory, Date.class, Timestamp.class, java.sql.Date.class);

        /** 任意类型 */
        putAllType(ClassCategory.ANY, Object.class);
    }

    // Batch put
    private static void putAllType(ClassCategory classCategory, Class<?>... classList) {
        for (Class cls : classList) {
            classClassCategoryMap.put(cls, classCategory);
        }
    }

    private static synchronized void putType(Class<?> cls, ClassCategory classCategory) {
        // Prevent accidental explosion
        if (classClassCategoryMap.size() >= 1 << 12) {
            return;
        }
        classClassCategoryMap.put(cls, classCategory);
    }

    /**
     * 获取指定 class 的类别，未命中缓存时按继承关系判定并写入缓存（缓存条目上限为 4096）。
     *
     * @param cls 待判定的类型，为 {@code null} 时视为任意类型
     * @return 该类型对应的类别；不属于任何特定类别的普通类返回 {@link ClassCategory#ObjectCategory}
     */
    public static ClassCategory getClassCategory(Class cls) {
        if (cls == null) {
            return ClassCategory.ANY;
        }
        ClassCategory classCategory = classClassCategoryMap.get(cls);
        if (classCategory != null) {
            return classCategory;
        }
        if (CharSequence.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.CharSequence);
            return classCategory;
        }
        if (Number.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.NumberCategory);
            return classCategory;
        }
        if (Date.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.DateCategory);
            return classCategory;
        }
        if (cls.isEnum()) {
            putType(cls, classCategory = ClassCategory.EnumCategory);
            return classCategory;
        }
        if (Collection.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.CollectionCategory);
            return classCategory;
        }
        if (Map.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.MapCategory);
            return classCategory;
        }
        if (cls.isArray()) {
            putType(cls, classCategory = ClassCategory.ArrayCategory);
            return classCategory;
        }
        if (Class.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.ClassCategory);
            return classCategory;
        }
        if (Annotation.class.isAssignableFrom(cls)) {
            putType(cls, classCategory = ClassCategory.AnnotationCategory);
            return classCategory;
        }
        if (cls.isInterface() || Modifier.isAbstract(cls.getModifiers())) {
            putType(cls, classCategory = ClassCategory.NonInstance);
            return classCategory;
        }
        putType(cls, classCategory = ClassCategory.ObjectCategory);
        return classCategory;
    }

    /**
     * class类别
     */
    public enum ClassCategory {
        /**
         * 字符串（String, StringBuffer, StringBuild, Character）
         */
        CharSequence,
        /**
         * 数字类型
         */
        NumberCategory,
        /**
         * boolean类型（boolean, Boolean）
         */
        BoolCategory,
        /**
         * 日期类型
         */
        DateCategory,
        /**
         * 类类型
         */
        ClassCategory,
        /**
         * 枚举类型
         */
        EnumCategory,
        /**
         * 注释类型
         */
        AnnotationCategory,
        /**
         * 二进制数组
         */
        Binary,
        /**
         * 数组类型
         */
        ArrayCategory,
        /**
         * 集合类型(Set,List)
         */
        CollectionCategory,
        /**
         * Map类型(Map,HashMap,LinkHashMap...)
         */
        MapCategory,
        /**
         * 实体对象类型
         */
        ObjectCategory,
        /**
         * 任意类型
         */
        ANY,
        /**
         * 不可实例化类型（接口和抽象类，不包括基础类型）
         */
        NonInstance
    }

    /***
     * 基本类型枚举
     */
    public enum PrimitiveType {
        /**
         * byte 基本类型，对应包装类 {@link Byte}，数组类型为 {@code byte[]}
         */
        PrimitiveByte(byte[].class, arrayBaseOffset(byte[].class), arrayIndexScale(byte[].class)) {
            @Override
            public Class<?> getWrap() {
                return Byte.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putByte(target, fieldOffset, (Byte) value);
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getByte(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((byte[]) objects).length;
            }

            @Override
            public Byte elementAt(Object objects, int index) {
                return ((byte[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((byte[]) objects)[index] = (Byte) value;
            }
        },
        /**
         * short 基本类型，对应包装类 {@link Short}，数组类型为 {@code short[]}
         */
        PrimitiveShort(short[].class, arrayBaseOffset(short[].class), arrayIndexScale(short[].class)) {
            @Override
            public Class<?> getWrap() {
                return Short.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putShort(target, fieldOffset, (Short) value);
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getShort(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((short[]) objects).length;
            }

            @Override
            public Short elementAt(Object objects, int index) {
                return ((short[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((short[]) objects)[index] = (Short) value;
            }
        },
        /**
         * int 基本类型，对应包装类 {@link Integer}，数组类型为 {@code int[]}
         */
        PrimitiveInt(int[].class, arrayBaseOffset(int[].class), arrayIndexScale(int[].class)) {
            @Override
            public Class<?> getWrap() {
                return Integer.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putInt(target, fieldOffset, ((Number) value).intValue());
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getInt(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((int[]) objects).length;
            }

            @Override
            public Integer elementAt(Object objects, int index) {
                return ((int[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((int[]) objects)[index] = (Integer) value;
            }
        },
        /**
         * float 基本类型，对应包装类 {@link Float}，数组类型为 {@code float[]}
         */
        PrimitiveFloat(float[].class, arrayBaseOffset(float[].class), arrayIndexScale(float[].class)) {
            @Override
            public Class<?> getWrap() {
                return Float.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putFloat(target, fieldOffset, ((Number) value).floatValue());
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getFloat(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((float[]) objects).length;
            }

            @Override
            public Float elementAt(Object objects, int index) {
                return ((float[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((float[]) objects)[index] = (Float) value;
            }
        },
        /**
         * long 基本类型，对应包装类 {@link Long}，数组类型为 {@code long[]}
         */
        PrimitiveLong(long[].class, arrayBaseOffset(long[].class), arrayIndexScale(long[].class)) {
            @Override
            public Class<?> getWrap() {
                return Long.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putLong(target, fieldOffset, ((Number) value).longValue());
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getLong(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((long[]) objects).length;
            }

            @Override
            public Long elementAt(Object objects, int index) {
                return ((long[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((long[]) objects)[index] = (Long) value;
            }
        },
        /**
         * double 基本类型，对应包装类 {@link Double}，数组类型为 {@code double[]}
         */
        PrimitiveDouble(double[].class, arrayBaseOffset(double[].class), arrayIndexScale(double[].class)) {
            @Override
            public Class<?> getWrap() {
                return Double.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putDouble(target, fieldOffset, ((Number) value).doubleValue());
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getDouble(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((double[]) objects).length;
            }

            @Override
            public Double elementAt(Object objects, int index) {
                return ((double[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((double[]) objects)[index] = (Double) value;
            }
        },
        /**
         * boolean 基本类型，对应包装类 {@link Boolean}，数组类型为 {@code boolean[]}
         */
        PrimitiveBoolean(boolean[].class, arrayBaseOffset(boolean[].class), arrayIndexScale(boolean[].class)) {
            @Override
            public Class<?> getWrap() {
                return Boolean.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putBoolean(target, fieldOffset, (Boolean) value);
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getBoolean(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((boolean[]) objects).length;
            }

            @Override
            public Boolean elementAt(Object objects, int index) {
                return ((boolean[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((boolean[]) objects)[index] = (Boolean) value;
            }
        },
        /**
         * char 基本类型，对应包装类 {@link Character}，数组类型为 {@code char[]}
         */
        PrimitiveCharacter(char[].class, arrayBaseOffset(char[].class), arrayIndexScale(char[].class)) {
            @Override
            public Class<?> getWrap() {
                return Character.class;
            }

            @Override
            void putValue(Object target, long fieldOffset, Object value) {
                UnsafeHelper.UNSAFE.putChar(target, fieldOffset, (Character) value);
            }

            @Override
            Object getValue(Object target, long fieldOffset) {
                return UnsafeHelper.UNSAFE.getChar(target, fieldOffset);
            }

            @Override
            public int arrayLength(Object objects) {
                return ((char[]) objects).length;
            }

            @Override
            public Character elementAt(Object objects, int index) {
                return ((char[]) objects)[index];
            }

            @Override
            public void setElementAt(Object objects, int index, Object value) {
                ((char[]) objects)[index] = (Character) value;
            }
        };

        final Class genericArrayType;
        final int arrayBaseOffset;
        final int arrayIndexScale;

        PrimitiveType(Class genericArrayType, int arrayBaseOffset, int arrayIndexScale) {
            this.genericArrayType = genericArrayType;
            this.arrayBaseOffset = arrayBaseOffset;
            this.arrayIndexScale = arrayIndexScale;
        }

        /**
         * 获取基本类型对应的包装类型。
         *
         * @param eClass 基本类型的 class，如 {@code int.class}
         * @param <E> 基本类型对应的泛型
         * @return 对应的包装类型，如 {@code Integer.class}
         * @throws NullPointerException 传入的不是基本类型时抛出（此时 {@link #typeOf(Class)} 返回 {@code null}）
         */
        public static <E> Class<E> getWrap(Class<E> eClass) {
            return typeOf(eClass).getWrap();
        }

        /**
         * 获取当前基本类型对应的包装类型。
         *
         * @param <E> 包装类型对应的泛型
         * @return 对应的包装类型
         */
        public abstract <E> Class<E> getWrap();

        /**
         * 获取基本类型数组的长度。
         *
         * @param objects 与当前枚举项类型一致的基本类型数组
         * @return 数组长度；枚举项未覆写该方法时返回 0
         */
        public int arrayLength(Object objects) {
            return 0;
        }

        /**
         * 读取基本类型数组中指定下标的元素。
         *
         * @param objects 与当前枚举项类型一致的基本类型数组
         * @param index 元素下标
         * @return 该下标元素装箱后的值
         */
        public abstract Object elementAt(Object objects, int index);

        /**
         * 向基本类型数组的指定下标写入元素。
         *
         * @param objects 与当前枚举项类型一致的基本类型数组
         * @param index 元素下标
         * @param value 待写入的值，需为对应的包装类型实例
         */
        public abstract void setElementAt(Object objects, int index, Object value);

        void put(Object target, long fieldOffset, Object value) {
            target.getClass();
            if (value == null) {
                return;
            }
            putValue(target, fieldOffset, value);
        }

        Object get(Object target, long fieldOffset) {
            target.getClass();
            return getValue(target, fieldOffset);
        }

        abstract Object getValue(Object target, long fieldOffset);

        abstract void putValue(Object target, long fieldOffset, Object value);

        static final int DoubleNameHash = -1325958191;
        static final int IntNameHash = 104431;
        static final int ByteNameHash = 3039496;
        static final int CharNameHash = 3052374;
        static final int LongNameHash = 3327612;
        static final int BooleanNameHash = 64711720;
        static final int FloatNameHash = 97526364;
        static final int ShortNameHash = 109413500;

        /**
         * 根据 class 查找对应的基本类型枚举项，内部通过类名 hash 匹配。
         *
         * @param fieldType 待查找的类型
         * @return 对应的基本类型枚举项；不是基本类型（含 {@code void}）时返回 {@code null}
         */
        public static PrimitiveType typeOf(Class<?> fieldType) {
            if (!fieldType.isPrimitive()) {
                return null;
            }
            int hash = fieldType.getName().hashCode();
            switch (hash) {
                case DoubleNameHash:
                    return PrimitiveDouble;
                case IntNameHash:
                    return PrimitiveInt;
                case ByteNameHash:
                    return PrimitiveByte;
                case CharNameHash:
                    return PrimitiveCharacter;
                case LongNameHash:
                    return PrimitiveLong;
                case BooleanNameHash:
                    return PrimitiveBoolean;
                case FloatNameHash:
                    return PrimitiveFloat;
                case ShortNameHash:
                    return PrimitiveShort;
                default:
                    break;
            }
            return null;
        }

        /**
         * 获取该基本类型对应的一维数组类型。
         *
         * @return 一维数组类型，如 {@code int[].class}
         */
        public Class getGenericArrayType() {
            return genericArrayType;
        }
    }

    static int arrayBaseOffset(Class arrayCls) {
        return UnsafeHelper.arrayBaseOffset(arrayCls);
    }

    static int arrayIndexScale(Class arrayCls) {
        return UnsafeHelper.arrayIndexScale(arrayCls);
    }
}
