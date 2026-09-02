/**
 * Created by 郑明亮 on 2023/8/16 11:13.
 */
package com.alianga.jkit.jdk;

import com.alianga.jkit.ReflectionUtils;
import com.alianga.jkit.TypeUtils;
import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.valid.Preconditions;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * <p> 封装jdk的不安全类的使用</p>
 *
 * @author 郑明亮
 * @time 2023/8/16 11:13
 * @since 1.4.3
 */
public class UnsafeUtils {
    /**
     * jdk 内部的 {@link Unsafe} 实例，反射获取失败时为 {@code null}
     */
    public static final Unsafe UNSAFE;

    static {
        Unsafe unsafe = null;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafe = (Unsafe) theUnsafeField.get(null);
        } catch (Throwable ignored) {
            // ignored
        }
        UNSAFE = unsafe;
    }

    /**
     * 读取对象指定内存偏移处的引用值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的引用，可能为 {@code null}
     */
    public static Object getObject(Object o, long offset) {
        return UNSAFE.getObject(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 long 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 long 值
     */
    public static long getLong(Object o, long offset) {
        return UNSAFE.getLong(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 int 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 int 值
     */
    public static int getInt(Object o, long offset) {
        return UNSAFE.getInt(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 short 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 short 值
     */
    public static short getShort(Object o, long offset) {
        return UNSAFE.getShort(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 byte 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 byte 值
     */
    public static byte getByte(Object o, long offset) {
        return UNSAFE.getByte(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 float 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 float 值
     */
    public static float getFloat(Object o, long offset) {
        return UNSAFE.getFloat(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 double 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 double 值
     */
    public static double getDouble(Object o, long offset) {
        return UNSAFE.getDouble(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 boolean 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 boolean 值
     */
    public static boolean getBoolean(Object o, long offset) {
        return UNSAFE.getBoolean(o, offset);
    }

    /**
     * 读取对象指定内存偏移处的 char 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @return 该偏移处保存的 char 值
     */
    public static char getChar(Object o, long offset) {
        return UNSAFE.getChar(o, offset);
    }

    /**
     * 向对象指定内存偏移处写入引用值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的引用，允许为 {@code null}
     */
    public static void putObject(Object o, long offset, Object x) {
        UNSAFE.putObject(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 int 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putInt(Object o, long offset, int x) {
        UNSAFE.putInt(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 long 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putLong(Object o, long offset, long x) {
        UNSAFE.putLong(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 float 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putFloat(Object o, long offset, float x) {
        UNSAFE.putFloat(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 double 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putDouble(Object o, long offset, double x) {
        UNSAFE.putDouble(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 short 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putShort(Object o, long offset, short x) {
        UNSAFE.putShort(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 byte 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putByte(Object o, long offset, byte x) {
        UNSAFE.putByte(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 char 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putChar(Object o, long offset, char x) {
        UNSAFE.putChar(o, offset, x);
    }

    /**
     * 向对象指定内存偏移处写入 boolean 值。
     *
     * @param o      目标对象
     * @param offset 字段的内存偏移量
     * @param x      待写入的值
     */
    public static void putBoolean(Object o, long offset, boolean x) {
        UNSAFE.putBoolean(o, offset, x);
    }

    /**
     * 直接在堆上分配一个cls类的实例,并返回这个实例的引用。
     * <p>
     * 由于没有调用任何构造函数,分配出来的对象中的字段值都是默认值(数值类型是0,boolean是false,引用类型是null)。
     * <p>
     * 并且完全忽略了cls类的访问权限,即使cls类没有public构造函数也可以分配实例。
     * <p>
     * 这种完全绕过构造函数和访问控制的实例分配方式很危险,可能会破坏对象的状态,应该尽量避免使用或者很小心的使用
     *
     * @param cls 待实例化的类
     * @return 未调用任何构造函数、字段均为默认值的新实例
     * @throws InstantiationException 该类无法实例化（如接口、抽象类）时抛出
     */
    public static Object allocateInstance(Class<?> cls) throws InstantiationException {
        return UNSAFE.allocateInstance(cls);
    }

    /**
     * 获取实例字段在对象中的内存偏移量。
     *
     * @param field 实例字段
     * @return 该字段相对于对象起始地址的偏移量
     */
    public static long objectFieldOffset(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    /**
     * 按字段名读取对象的字段值，字段不存在时由 {@link ReflectionUtils#getField(Class, String)} 抛出异常。
     *
     * @param o         目标对象
     * @param fieldName 字段名称
     * @return 字段的值，字段为引用类型且未赋值时为 {@code null}
     */
    public static Object get(Object o, String fieldName) {
        return get(o, ReflectionUtils.getField(o.getClass(), fieldName));
    }

    /**
     * 按字段名读取对象的字段值，字段不存在时不抛异常。
     *
     * @param o         目标对象
     * @param fieldName 字段名称
     * @return 字段的值；字段不存在时返回 {@code null}
     */
    public static Object getNullable(Object o, String fieldName) {
        return get(o, ReflectionUtils.getFieldNullable(o.getClass(), fieldName));
    }

    /**
     * 获取指定对象某个字段的值
     *
     * @param o 对象
     * @param field 字段
     * @return 字段的值，基本类型会被装箱；{@code o} 或 {@code field} 为 {@code null} 时返回 {@code null}
     */
    public static Object get(Object o, Field field) {
        if (o == null || field == null) {
            return null;
        }

        long offset = objectFieldOffset(field);

        if (field.getType() == boolean.class) {
            return getBoolean(o, offset);
        } else if (field.getType() == byte.class) {
            return getByte(o, offset);
        } else if (field.getType() == char.class) {
            return getChar(o, offset);
        } else if (field.getType() == short.class) {
            return getShort(o, offset);
        } else if (field.getType() == int.class) {
            return getInt(o, offset);
        } else if (field.getType() == long.class) {
            return getLong(o, offset);
        } else if (field.getType() == float.class) {
            return getFloat(o, offset);
        } else if (field.getType() == double.class) {
            return getDouble(o, offset);
        } else {
            return getObject(o, offset);
        }
    }

    /**
     * 按字段名给对象的字段设置值，字段不存在时直接返回、不做任何处理。
     *
     * @param o         目标对象
     * @param fieldName 字段名称
     * @param value     设置的值（值和字段类型必需要一致）
     */
    public static void set(Object o, String fieldName, Object value) {
        Field fieldNullable = ReflectionUtils.getFieldNullable(o.getClass(), fieldName);
        if (fieldNullable == null) {
            return;
        }
        set(o, fieldNullable, value);
    }

    /**
     * 给指定对象的字段设置值（设置字段的值类型和字段类型必需一致）
     *
     * @param o 设置值的对象
     * @param field 字段
     * @param value 设置的值（值和字段类型必需要一致）
     */
    public static void set(Object o, Field field, Object value) {
        long offset = objectFieldOffset(field);

        if (value != null) {
            Class<?> type = field.getType();
            Class<?> targetType = value.getClass();
            if (type.isPrimitive() && !targetType.isPrimitive()) {
                type = TypeUtils.getBoxedType(type);
            }
            Preconditions.checkArgument(type == targetType,
                    String.format("字段类型和设置的值类型不一致,value需要是%s类型", type.getName()));
        } else {
            putObject(o, offset, null);
            return;
        }

        if (field.getType() == boolean.class) {
            putBoolean(o, offset, ConvertUtils.toBoolean(value));
        } else if (field.getType() == byte.class) {
            putByte(o, offset, (byte) value);
        } else if (field.getType() == char.class) {
            putChar(o, offset, (char) value);
        } else if (field.getType() == short.class) {
            putShort(o, offset, (short) value);
        } else if (field.getType() == int.class) {
            putInt(o, offset, (int) value);
        } else if (field.getType() == long.class) {
            putLong(o, offset, (long) value);
        } else if (field.getType() == float.class) {
            putFloat(o, offset, (float) value);
        } else if (field.getType() == double.class) {
            putDouble(o, offset, (double) value);
        } else {
            putObject(o, offset, value);
        }
    }
}
