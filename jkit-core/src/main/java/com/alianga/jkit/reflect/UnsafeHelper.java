package com.alianga.jkit.reflect;

import com.alianga.jkit.jdk.JDKVersion;
import sun.misc.Unsafe;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.TimeZone;

/**
 * <p> 请谨慎调用，真的不安全！
 * <p> 内部使用
 *
 * @time 2022/6/12
 */
public final class UnsafeHelper {
    /**
     * {@link HttpURLConnection} 中 method 字段的内存偏移量，获取失败时为 -1
     */
    public static final long HTTP_URL_CONNECTION_METHOD_OFFSET;
    /**
     * {@link String} 中 value 字段的内存偏移量，获取失败时为 -1
     */
    public static final long STRING_VALUE_OFFSET;
    /**
     * {@link String} 中 coder 字段的内存偏移量，jdk8 及以下不存在该字段，此时为 -1
     */
    public static final long STRING_CODER_OFFSET;
    /**
     * {@link TimeZone} 中静态字段 defaultTimeZone 的内存偏移量，获取失败时为 -1
     */
    public static final long DEFAULT_TIME_ZONE_OFFSET;
    /**
     * {@link BigInteger} 中 mag 字段的内存偏移量，获取失败时为 -1
     */
    public static final long BIGINTEGER_MAG_OFFSET;
    /**
     * {@link ArrayList} 中 elementData 字段的内存偏移量，获取失败时为 -1
     */
    public static final long ARRAYLIST_ELEMENT_DATA_OFFSET;
    /**
     * {@link ArrayList} 中 size 字段的内存偏移量，获取失败时为 -1
     */
    public static final long ARRAYLIST_SIZE_OFFSET;
    private static final long OVERRIDE_OFFSET;

    static final Unsafe UNSAFE;

    static final Field modifierField;

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
            } catch (Throwable throwable) {
            }
        }
        modifierField = field;
    }

    /**
     * 去掉字段的 final 修饰，使其可被反射赋值；修饰符字段不可用时静默忽略。
     *
     * @param field 待去掉 final 修饰的字段
     */
    public static void clearFinalModifiers(Field field) {
        if (modifierField != null) {
            try {
                modifierField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            } catch (Exception e) {
            }
        }
    }

    static {
        Field theUnsafeField;
        try {
            theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
        } catch (NoSuchFieldException exception) {
            theUnsafeField = null;
        }

        Unsafe instance = null;
        if (theUnsafeField != null) {
            try {
                instance = (Unsafe) theUnsafeField.get(null);
            } catch (IllegalAccessException exception) {
                throw new RuntimeException(exception);
            }
        }
        UNSAFE = instance;

        if (JDKVersion.VERSION >= 25) {
            try {
                // suppress unsafe warnings
                Field field = Unsafe.class.getDeclaredField("memoryAccessWarned");
                field.setAccessible(true);
                field.set(null, true);
            } catch (Throwable e) {
            }
        }
    }

    /**
     * char 数组的首元素内存偏移量，Unsafe 不可用时为 -1
     */
    public static final long CHAR_ARRAY_OFFSET = arrayBaseOffset(char[].class);
    /**
     * byte 数组的首元素内存偏移量，Unsafe 不可用时为 -1
     */
    public static final long BYTE_ARRAY_OFFSET = arrayBaseOffset(byte[].class);

    // String
    static {
        Field valueField;
        long valueOffset = -1;
        long coderOffset = -1;
        try {
            valueField = String.class.getDeclaredField("value");
            valueOffset = objectFieldOffset(valueField);
            Object emptyValue = getObjectValue("", valueOffset);
            if (!char[].class.isInstance(emptyValue)) {
                Field coderField = String.class.getDeclaredField("coder");
                coderOffset = objectFieldOffset(coderField);
            }
        } catch (Exception e) {
        }
        STRING_VALUE_OFFSET = valueOffset;
        STRING_CODER_OFFSET = coderOffset;

        long defaultTimeZoneOff = -1;
        try {
            Field timeZoneField = TimeZone.class.getDeclaredField("defaultTimeZone");
            defaultTimeZoneOff = UNSAFE.staticFieldOffset(timeZoneField);
        } catch (Throwable throwable) {
        }
        DEFAULT_TIME_ZONE_OFFSET = defaultTimeZoneOff;

        long http_url_connection_method_offset = -1;
        try {
            Field httpUrlConnectionMethodField = HttpURLConnection.class.getDeclaredField("method");
            setAccessible(httpUrlConnectionMethodField);
            http_url_connection_method_offset = objectFieldOffset(httpUrlConnectionMethodField);
        } catch (Throwable throwable) {
        }
        HTTP_URL_CONNECTION_METHOD_OFFSET = http_url_connection_method_offset;
    }

    // BigInteger
    static {
        long magOffset = -1;
        try {
            Field magField = BigInteger.class.getDeclaredField("mag");
            magOffset = objectFieldOffset(magField);
        } catch (Exception e) {
        }
        BIGINTEGER_MAG_OFFSET = magOffset;
    }

    // ArrayList
    static {
        long elementDataOffset = -1;
        long sizeOffset = -1;
        try {
            Field elementDataField = ArrayList.class.getDeclaredField("elementData");
            elementDataOffset = objectFieldOffset(elementDataField);
        } catch (Exception e) {
        }
        try {
            Field sizeField = ArrayList.class.getDeclaredField("size");
            sizeOffset = objectFieldOffset(sizeField);
        } catch (Exception e) {
        }
        if (elementDataOffset == -1 || sizeOffset == -1) {
            elementDataOffset = -1;
            sizeOffset = -1;
        }
        ARRAYLIST_ELEMENT_DATA_OFFSET = elementDataOffset;
        ARRAYLIST_SIZE_OFFSET = sizeOffset;
    }

    // reflect
    static {
        long overrideOffset = 12;
        try {
            //note: jdk18 not supported
            Field overrideField = AccessibleObject.class.getDeclaredField("override");
            overrideOffset = objectFieldOffset(overrideField);
        } catch (NoSuchFieldException e) {
        }
        OVERRIDE_OFFSET = overrideOffset;
    }

    /***
     * jdk version 9+ use toCharArray
     * jdk version &lt;= 8 use unsafe
     *
     * @param string the source string, must not be null
     * @return the char array of the string, a copy on jdk9+ and the internal value array on jdk8 and below
     */
    public static char[] getChars(String string) {
        // note: jdk9+ value is byte[] and stringValueField will set to null
        if (STRING_CODER_OFFSET > -1) {
            return string.toCharArray();
        }
        string.getClass();
        return (char[]) getObjectValue(string, STRING_VALUE_OFFSET);
    }

    /**
     * 获取指定类中声明字段的内存偏移量。
     *
     * @param targetClass 目标类
     * @param fieldName   字段名
     * @return 字段的内存偏移量，字段不存在或获取失败时返回 -1
     */
    public static long getDeclaredFieldOffset(Class<?> targetClass, String fieldName) {
        try {
            Field field = targetClass.getDeclaredField(fieldName);
            return objectFieldOffset(field);
        } catch (Throwable throwable) {
            return -1;
        }
    }

    /**
     * 获取字符串的value
     *
     * @param source 源字符串，不能为 {@code null}
     * @return 字符串内部的字符存储数组，jdk9+ 为 {@code byte[]}，jdk8 及以下为 {@code char[]}
     */
    public static Object getStringValue(String source) {
        source.getClass();
        return getObjectValue(source, STRING_VALUE_OFFSET);
    }

    /**
     * Build a string according to the character array to reduce the copy of the character array once
     *
     * @param buf the character array to be used as the string content, must not be null
     * @return a string sharing the given array on jdk8 and below, otherwise a string copied from the array
     */
    public static String getString(char[] buf) {
        if (STRING_CODER_OFFSET == -1) {
            buf.getClass();
            String result = new String();
            putObjectValue(result, STRING_VALUE_OFFSET, buf);
            return result;
        }
        return new String(buf);
    }

    /**
     * ensure that buf is an ASCII byte array, no any check
     *
     * @param bytes the ASCII byte array to be used as the string content, must not be null
     * @return a LATIN1 string sharing the given array on jdk9+, otherwise a string decoded from the array
     */
    public static String getAsciiString(byte[] bytes) {
        // note: jdk9+ value is byte[] and direct setting
        if (STRING_CODER_OFFSET > -1) {
            // allocateInstance
            String result = new String();
            // String coder is LATIN1(0)
            putObjectValue(result, STRING_VALUE_OFFSET, bytes);
            return result;
        }
        // if version <= jdk8, encoding byte[] to char[] even if it is ascii mode
        return new String(bytes);
    }

    /**
     * 通过utf16的字节构建字符串
     * support by jdk9+
     *
     * @param utf16Bytes UTF16 编码的字节数组，不能为 {@code null}
     * @return 直接复用该字节数组、coder 为 UTF16 的字符串
     * @throws UnsupportedOperationException 运行在 jdk8 及以下（不存在 coder 字段）时抛出
     */
    public static String getUTF16String(byte[] utf16Bytes) {
        if (STRING_CODER_OFFSET > -1) {
            utf16Bytes.getClass();
            String result = new String();
            UNSAFE.putObject(result, STRING_VALUE_OFFSET, utf16Bytes);
            UNSAFE.putByte(result, STRING_CODER_OFFSET, (byte) 1);
            return result;
        }
        throw new UnsupportedOperationException();
    }

    /**
     * get mag of BigInteger
     *
     * @param value the BigInteger to read, must not be null
     * @return the internal magnitude array of the BigInteger
     * @throws UnsupportedOperationException if the offset of the mag field is unavailable
     */
    public static int[] getMag(BigInteger value) {
        if (BIGINTEGER_MAG_OFFSET > -1) {
            value.getClass();
            return (int[]) UNSAFE.getObject(value, BIGINTEGER_MAG_OFFSET);
        }
        throw new UnsupportedOperationException();
    }

    /**
     * <p> 获取ArrayList的elementData属性</p>
     * <p> 注： 如果要对返回的数组遍历必须使用for(; i &lt; size;)语法</p>
     *
     * @param value 待读取的 ArrayList，不能为 {@code null}
     * @return ArrayList 内部的元素数组，偏移量不可用时退化为 {@code value.toArray()} 的结果
     */
    public static Object[] getArrayListData(ArrayList value) {
        if (ARRAYLIST_ELEMENT_DATA_OFFSET > -1) {
            value.getClass();
            return (Object[]) UNSAFE.getObject(value, ARRAYLIST_ELEMENT_DATA_OFFSET);
        } else {
            return value.toArray();
        }
    }

    /**
     * 把 char 数组的内容按内存拷贝写入 byte 数组，当前实现暂未开放。
     *
     * @param chars 源字符数组
     * @param cOff  源字符数组的起始下标
     * @param bytes 目标字节数组
     * @param bOff  目标字节数组的起始下标
     * @param cLen  待拷贝的字符个数
     * @throws UnsupportedOperationException 该方法尚未实现，调用时总是抛出
     */
    public static void copyMemory(char[] chars, int cOff, byte[] bytes, int bOff, int cLen) {
//        if (cLen <= 0) return;
//        if (cOff < -1 || cLen > chars.length - cOff) {
//            throw new IndexOutOfBoundsException("source offset = " + cOff + ", len = " + cLen);
//        }
//        if (bOff < -1 || cLen * 2 > bytes.length - bOff) {
//            throw new IndexOutOfBoundsException("target offset = " + bOff + ", len = " + cLen);
//        }
//        int arrayBaseOffset = ReflectConsts.PrimitiveType.PrimitiveCharacter.arrayBaseOffset;
//        int arrayIndexScale = ReflectConsts.PrimitiveType.PrimitiveCharacter.arrayIndexScale;
//        int targetArrayBaseOffset = ReflectConsts.PrimitiveType.PrimitiveByte.arrayBaseOffset;
//        int targetIndexScale = ReflectConsts.PrimitiveType.PrimitiveByte.arrayIndexScale;
//        UNSAFE.copyMemory(chars, arrayBaseOffset + arrayIndexScale * cOff, bytes, targetArrayBaseOffset +
//        targetIndexScale * bOff, cLen * arrayIndexScale);
        throw new UnsupportedOperationException();
    }

    /**
     * 把 byte 数组的内容按内存拷贝写入 char 数组，当前实现暂未开放。
     *
     * @param bytes 源字节数组
     * @param bOff  源字节数组的起始下标
     * @param chars 目标字符数组
     * @param cOff  目标字符数组的起始下标
     * @param bLen  待拷贝的字节个数
     * @throws UnsupportedOperationException 该方法尚未实现，调用时总是抛出
     */
    public static void copyMemory(byte[] bytes, int bOff, char[] chars, int cOff, int bLen) {
//        if (bLen <= 0) return;
//        if (bOff < -1 || bLen > bytes.length - bOff) {
//            throw new IndexOutOfBoundsException("source offset = " + bOff + ", len = " + bLen);
//        }
//        if (cOff < -1 || bLen / 2 > chars.length - cOff) {
//            throw new IndexOutOfBoundsException("target offset = " + cOff + ", len = " + bLen);
//        }
//        int arrayBaseOffset = ReflectConsts.PrimitiveType.PrimitiveByte.arrayBaseOffset;
//        int arrayIndexScale = ReflectConsts.PrimitiveType.PrimitiveByte.arrayIndexScale;
//        int targetArrayBaseOffset = ReflectConsts.PrimitiveType.PrimitiveCharacter.arrayBaseOffset;
//        int targetIndexScale = ReflectConsts.PrimitiveType.PrimitiveCharacter.arrayIndexScale;
//        UNSAFE.copyMemory(bytes, arrayBaseOffset + arrayIndexScale * bOff, chars, targetArrayBaseOffset +
//        targetIndexScale * cOff, bLen * arrayIndexScale);
        throw new UnsupportedOperationException();
    }

    /**
     * 获取 JVM 默认时区，优先直接读取 {@link TimeZone} 的静态缓存字段以避免加锁与克隆。
     *
     * @return 默认时区，直接读取失败时返回 {@link TimeZone#getDefault()} 的结果
     */
    public static TimeZone getDefaultTimeZone() {
        if (DEFAULT_TIME_ZONE_OFFSET > -1) {
            try {
                TimeZone timeZone = (TimeZone) UNSAFE.getObject(TimeZone.class, DEFAULT_TIME_ZONE_OFFSET);
                if (timeZone != null) {
                    return timeZone;
                }
            } catch (Throwable throwable) {
            }
        }
        return TimeZone.getDefault();
    }

    static long objectFieldOffset(Field field) {
        if (UNSAFE != null) {
            return UNSAFE.objectFieldOffset(field);
        }
        return -1;
    }

    static void putObjectValue(Object target, long fieldOffset, Object value) {
        target.getClass();
        UNSAFE.putObject(target, fieldOffset, value);
    }

    static Object getObjectValue(Object target, long fieldOffset) {
        target.getClass();
        return UNSAFE.getObject(target, fieldOffset);
    }

    /**
     * 创建指定类的实例，优先调用无参构造，失败时退化为 Unsafe 直接分配对象（不执行构造器）。
     *
     * @param targetClass 待实例化的类
     * @return 新创建的实例
     * @throws RuntimeException 无参构造与 Unsafe 分配均失败时抛出
     */
    public static Object newInstance(Class<?> targetClass) {
        try {
            return targetClass.newInstance();
        } catch (Throwable throwable) {
            try {
                targetClass.getClass();
                return UNSAFE.allocateInstance(targetClass);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 直接写入 override 标记以绕过访问检查，替代 {@code setAccessible(true)}。
     *
     * @param accessibleObject 待开放访问权限的反射对象
     * @return 设置成功时返回 {@code true}，override 字段偏移量不可用时返回 {@code false}
     */
    public static boolean setAccessible(AccessibleObject accessibleObject) {
        if (OVERRIDE_OFFSET > -1) {
            accessibleObject.getClass();
            UNSAFE.putBoolean(accessibleObject, OVERRIDE_OFFSET, true);
            return true;
        }
        return false;
    }

    static int arrayBaseOffset(Class arrayCls) {
        if (UNSAFE != null) {
            return UNSAFE.arrayBaseOffset(arrayCls);
        }
        return -1;
    }

    static int arrayIndexScale(Class arrayCls) {
        if (UNSAFE != null) {
            return UNSAFE.arrayIndexScale(arrayCls);
        }
        return -1;
    }

//    public static final long NEGATIVE_MASK = 0x8080808080808080L;

    /**
     * 判断字节数组指定区间内是否存在负数字节（即非 ASCII 字节）。
     *
     * @param bytes  待检查的字节数组
     * @param offset 起始下标
     * @param len    检查长度
     * @return 区间内存在负数字节时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean hasNegativesUnsafe(byte[] bytes, int offset, int len) {
//        if (offset > -1 && offset + len <= bytes.length) {
//            if (len > 7) {
//                do {
//                    long val = UNSAFE.getLong(bytes, BYTE_ARRAY_OFFSET + offset);
//                    if ((val & NEGATIVE_MASK) != 0) return true;
//                    offset += 8;
//                    len -= 8;
//                } while (len > 7);
//                if (len == 0) return false;
//                return (UNSAFE.getLong(bytes, BYTE_ARRAY_OFFSET + offset + len - 8) & NEGATIVE_MASK) != 0;
//            } else {
//                for (int i = offset, end = offset + len; i < end; ++i) {
//                    if (bytes[i] < 0) return true;
//                }
//                return false;
//            }
//        }
//        throw new IndexOutOfBoundsException("offset " + offset);
        for (int i = offset, end = offset + len; i < end; ++i) {
            if (bytes[i] < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 直接写入连接的 method 字段以设置请求方法，可绕过 {@code setRequestMethod} 对方法名的白名单校验。
     *
     * @param urlConnection 目标连接，不能为 {@code null}
     * @param method        请求方法名，如 {@code PATCH}
     * @return 设置成功时返回 {@code true}，method 字段偏移量不可用时返回 {@code false}
     */
    public static boolean setRequestMethod(HttpURLConnection urlConnection, String method) {
        urlConnection.getClass();
        if (HTTP_URL_CONNECTION_METHOD_OFFSET > -1) {
            UNSAFE.putObject(urlConnection, HTTP_URL_CONNECTION_METHOD_OFFSET, method);
            return true;
        }
        return false;
    }
}
