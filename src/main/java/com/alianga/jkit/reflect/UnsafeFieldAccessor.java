/**
 * Created by 郑明亮 on 2023/8/16 11:57.
 */
package com.alianga.jkit.reflect;

import com.alianga.jkit.ReflectionUtils;
import com.alianga.jkit.jdk.UnsafeUtils;
import com.alianga.jkit.valid.Preconditions;

import java.lang.reflect.Field;

/**
 * <p> this is your description</p>
 *
 * @author 郑明亮
 * @time 2023/8/16 11:57
 * @since 1.0.0
 */
public class UnsafeFieldAccessor {
    private final Field field;
    private final long fieldOffset;

    /**
     * Search parent class if <code>cls</code> doesn't have a field named <code>fieldName</code>.
     *
     * @param cls class
     * @param fieldName field name
     */
    public UnsafeFieldAccessor(Class<?> cls, String fieldName) {
        this(ReflectionUtils.getField(cls, fieldName));
    }

    /**
     * 构造字段访问器，并解析该字段的内存偏移量
     *
     * @param field 目标字段，不能为 {@code null}，且必须能取到有效的偏移量，否则抛出异常
     */
    public UnsafeFieldAccessor(Field field) {
        Preconditions.checkNotNull(field);
        this.field = field;
        this.fieldOffset = UnsafeUtils.objectFieldOffset(field);
        Preconditions.checkArgument(fieldOffset != -1);
    }

    /**
     * 获取被访问的字段
     *
     * @return 当前访问器绑定的字段
     */
    public Field getField() {
        return field;
    }

    /**
     * 读取目标对象上该字段的 boolean 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 boolean 值
     */
    public boolean getBoolean(Object obj) {
        return UnsafeUtils.getBoolean(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 boolean 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 boolean 值
     */
    public void putBoolean(Object obj, boolean value) {
        UnsafeUtils.putBoolean(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 byte 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 byte 值
     */
    public byte getByte(Object obj) {
        return UnsafeUtils.getByte(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 byte 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 byte 值
     */
    public void putByte(Object obj, byte value) {
        UnsafeUtils.putByte(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 char 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 char 值
     */
    public char getChar(Object obj) {
        return UnsafeUtils.getChar(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 char 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 char 值
     */
    public void putChar(Object obj, char value) {
        UnsafeUtils.putChar(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 short 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 short 值
     */
    public short getShort(Object obj) {
        return UnsafeUtils.getShort(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 short 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 short 值
     */
    public void putShort(Object obj, short value) {
        UnsafeUtils.putShort(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 int 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 int 值
     */
    public int getInt(Object obj) {
        return UnsafeUtils.getInt(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 int 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 int 值
     */
    public void putInt(Object obj, int value) {
        UnsafeUtils.putInt(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 long 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 long 值
     */
    public long getLong(Object obj) {
        return UnsafeUtils.getLong(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 long 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 long 值
     */
    public void putLong(Object obj, long value) {
        UnsafeUtils.putLong(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 float 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 float 值
     */
    public float getFloat(Object obj) {
        return UnsafeUtils.getFloat(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 float 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 float 值
     */
    public void putFloat(Object obj, float value) {
        UnsafeUtils.putFloat(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的 double 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的 double 值
     */
    public double getDouble(Object obj) {
        return UnsafeUtils.getDouble(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的 double 值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的 double 值
     */
    public void putDouble(Object obj, double value) {
        UnsafeUtils.putDouble(obj, fieldOffset, value);
    }

    /**
     * 读取目标对象上该字段的引用值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @return 字段当前的引用值，字段值为空时返回 {@code null}
     */
    public Object getObject(Object obj) {
        return UnsafeUtils.getObject(obj, fieldOffset);
    }

    /**
     * 写入目标对象上该字段的引用值
     *
     * @param obj 目标对象，静态字段场景下为所属类
     * @param value 要写入的引用值，允许为 {@code null}
     */
    public void putObject(Object obj, Object value) {
        UnsafeUtils.putObject(obj, fieldOffset, value);
    }
}
