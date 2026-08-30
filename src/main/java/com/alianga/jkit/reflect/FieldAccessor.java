/**
 * Created by 郑明亮 on 2023/8/13 14:31.
 */
package com.alianga.jkit.reflect;

import com.alianga.jkit.valid.Preconditions;

import java.lang.reflect.Field;

/**
 * <p> 基本类型和对象类型的字段访问器.</p>
 * 注意对于基本类型，将会有box/unbox开销。使用{@link UnsafeFieldAccessor}尽可能避免这种开销。
 *
 * @author 郑明亮
 * @time 2023/8/13 14:31
 * @since 1.4.3
 */
public abstract class FieldAccessor {
    /** 被访问的目标字段 */
    protected final Field field;
    /** 基于 unsafe 字段偏移量的底层读写器，用于避免反射调用开销 */
    protected final UnsafeFieldAccessor unsafeFieldAccessor;

    /**
     * 基于目标字段构建访问器，同时创建对应的 unsafe 底层读写器。
     *
     * @param field 被访问的目标字段
     */
    public FieldAccessor(Field field) {
        this.field = field;
        this.unsafeFieldAccessor = new UnsafeFieldAccessor(field);
    }

    /**
     * 读取指定对象上该字段的值，基本类型会装箱返回。
     *
     * @param obj 目标对象，必须是字段声明类或其子类的实例
     * @return 字段当前的值
     */
    public abstract Object get(Object obj);

    /**
     * 设置指定对象上该字段的值，基本类型入参会先拆箱。
     *
     * @param obj   目标对象，必须是字段声明类或其子类的实例
     * @param value 待写入的值
     */
    public abstract void set(Object obj, Object value);

    /**
     * 获取被访问的目标字段。
     *
     * @return 当前访问器绑定的字段
     */
    public Field getField() {
        return field;
    }

    void checkObj(Object obj) {
        if (!this.field.getDeclaringClass().isAssignableFrom(obj.getClass())) {
            throw new IllegalArgumentException("Illegal class " + obj.getClass());
        }
    }

    /**
     * 按字段类型创建匹配的访问器实现。
     *
     * @param field 被访问的目标字段
     * @return 与字段类型对应的访问器；八种基本类型分别返回各自的访问器，其余类型返回 {@link ObjectAccessor}
     */
    public static FieldAccessor createAccessor(Field field) {
        if (field.getType() == boolean.class) {
            return new BooleanAccessor(field);
        } else if (field.getType() == byte.class) {
            return new ByteAccessor(field);
        } else if (field.getType() == char.class) {
            return new CharAccessor(field);
        } else if (field.getType() == short.class) {
            return new ShortAccessor(field);
        } else if (field.getType() == int.class) {
            return new IntAccessor(field);
        } else if (field.getType() == long.class) {
            return new LongAccessor(field);
        } else if (field.getType() == float.class) {
            return new FloatAccessor(field);
        } else if (field.getType() == double.class) {
            return new DoubleAccessor(field);
        } else {
            return new ObjectAccessor(field);
        }
    }

    /**
     * Primitive boolean accessor.
     */
    public static class BooleanAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code boolean} field.
         *
         * @param field the target field, must be of type {@code boolean}
         */
        public BooleanAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == boolean.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getBoolean(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putBoolean(obj, (Boolean) value);
        }
    }

    /**
     * Primitive byte accessor.
     */
    public static class ByteAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code byte} field.
         *
         * @param field the target field, must be of type {@code byte}
         */
        public ByteAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == byte.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getByte(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putByte(obj, (Byte) value);
        }
    }

    /**
     * Primitive char accessor.
     */
    public static class CharAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code char} field.
         *
         * @param field the target field, must be of type {@code char}
         */
        public CharAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == char.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getChar(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putChar(obj, (Character) value);
        }
    }

    /**
     * Primitive short accessor.
     */
    public static class ShortAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code short} field.
         *
         * @param field the target field, must be of type {@code short}
         */
        public ShortAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == short.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getShort(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putShort(obj, (Short) value);
        }
    }

    /**
     * Primitive int accessor.
     */
    public static class IntAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code int} field.
         *
         * @param field the target field, must be of type {@code int}
         */
        public IntAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == int.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getInt(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putInt(obj, (Integer) value);
        }
    }

    /**
     * Primitive long accessor.
     */
    public static class LongAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code long} field.
         *
         * @param field the target field, must be of type {@code long}
         */
        public LongAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == long.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getLong(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putLong(obj, (Long) value);
        }
    }

    /**
     * Primitive float accessor.
     */
    public static class FloatAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code float} field.
         *
         * @param field the target field, must be of type {@code float}
         */
        public FloatAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == float.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getFloat(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putFloat(obj, (Float) value);
        }
    }

    /**
     * Primitive double accessor.
     */
    public static class DoubleAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a primitive {@code double} field.
         *
         * @param field the target field, must be of type {@code double}
         */
        public DoubleAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(field.getType() == double.class);
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getDouble(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putDouble(obj, (Double) value);
        }
    }

    /**
     * Object accessor.
     */
    public static class ObjectAccessor extends FieldAccessor {
        /**
         * Creates an accessor for a non-primitive (reference type) field.
         *
         * @param field the target field, must not be of a primitive type
         */
        public ObjectAccessor(Field field) {
            super(field);
            Preconditions.checkArgument(!(field.getType().isPrimitive()));
        }

        @Override
        public Object get(Object obj) {
            checkObj(obj);
            return unsafeFieldAccessor.getObject(obj);
        }

        @Override
        public void set(Object obj, Object value) {
            checkObj(obj);
            unsafeFieldAccessor.putObject(obj, value);
        }
    }
}
