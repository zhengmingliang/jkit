package com.alianga.jkit.json.internal.utils;

import com.alianga.jkit.reflect.ReflectConsts;

import java.lang.reflect.Array;
import java.util.*;

/**
 * 集合和数组类工具方法
 *
 * @time 2020/3/25 0:09
 */
public final class CollectionUtils {
    /**
     * 将可变参数按顺序放入一个新的 List。
     *
     * @param elements 元素列表，允许包含 {@code null} 元素
     * @return 新创建的 ArrayList，按参数顺序保存全部元素
     */
    public static List listOf(Object... elements) {
        List list = new ArrayList();
        for (Object element : elements) {
            list.add(element);
        }
        return list;
    }

    /**
     * 将可变参数放入一个新的 Set，重复元素会被去重。
     *
     * @param elements 元素列表，允许包含 {@code null} 元素
     * @return 新创建的 HashSet，包含去重后的全部元素
     */
    public static Set setOf(Object... elements) {
        Set set = new HashSet();
        for (Object element : elements) {
            set.add(element);
        }
        return set;
    }

    /**
     * 返回数组中元素等于obj的索引位置,如果没有找到返回-1
     *
     * @param arr 待查找的数组，为 {@code null} 时返回 -1
     * @param obj 待查找的元素，为 {@code null} 时返回 -1
     * @param <T> 数组元素类型
     * @return 第一个与 obj 相等（{@code ==} 或 {@code equals}）的元素下标，未找到返回 -1
     */
    public static <T> int indexOf(T[] arr, T obj) {
        return indexOf(arr, obj, 0);
    }

    /**
     * 从指定位置开始，向后查找数组中等于obj的元素,如果没有找到返回-1
     *
     * @param arr       待查找的数组，为 {@code null} 时返回 -1
     * @param obj       待查找的元素，为 {@code null} 时返回 -1
     * @param fromIndex 起始查找下标（包含）
     * @param <T>       数组元素类型
     * @return 从 fromIndex 起第一个与 obj 相等的元素下标，未找到返回 -1
     */
    public static <T> int indexOf(T[] arr, T obj, int fromIndex) {
        if (arr == null || obj == null) {
            return -1;
        }
        T e;
        for (int i = fromIndex; i < arr.length; i++) {
            e = arr[i];
            if (e == obj || obj.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从指定区间，从左开始查找数组中等于obj的元素，如果没有找到返回-1
     *
     * @param arr       待查找的数组，为 {@code null} 时返回 -1
     * @param obj       待查找的元素，为 {@code null} 时返回 -1
     * @param fromIndex 起始查找下标（包含）
     * @param toIndex   结束查找下标（不包含）
     * @param <T>       数组元素类型
     * @return 区间内第一个与 obj 相等的元素下标，未找到返回 -1
     */
    public static <T> int indexOf(T[] arr, T obj, int fromIndex, int toIndex) {
        if (arr == null || obj == null) {
            return -1;
        }
        T e;
        for (int i = fromIndex; i < toIndex; i++) {
            e = arr[i];
            if (e == obj || obj.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找 int 数组中值等于 j 的第一个元素下标。
     *
     * @param arr 待查找的数组，为 {@code null} 时返回 -1
     * @param j   待查找的值
     * @return 第一个等于 j 的元素下标，未找到返回 -1
     */
    public static int indexOf(int[] arr, int j) {
        return indexOf(arr, j, 0);
    }

    /**
     * 从指定位置开始向后查找 int 数组中值等于 j 的元素下标。
     *
     * @param arr       待查找的数组，为 {@code null} 时返回 -1
     * @param j         待查找的值
     * @param fromIndex 起始查找下标（包含）
     * @return 从 fromIndex 起第一个等于 j 的元素下标，未找到返回 -1
     */
    public static int indexOf(int[] arr, int j, int fromIndex) {
        if (arr == null) {
            return -1;
        }
        for (int i = fromIndex; i < arr.length; i++) {
            if (arr[i] == j) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 在指定区间内查找 int 数组中值等于 j 的元素下标。
     *
     * @param arr       待查找的数组，为 {@code null} 时返回 -1
     * @param j         待查找的值
     * @param fromIndex 起始查找下标（包含）
     * @param toIndex   结束查找下标（不包含）
     * @return 区间内第一个等于 j 的元素下标，未找到返回 -1
     */
    public static int indexOf(int[] arr, int j, int fromIndex, int toIndex) {
        if (arr == null) {
            return -1;
        }
        for (int i = fromIndex; i < toIndex; i++) {
            if (arr[i] == j) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找 char 数组中值等于 j 的第一个字符下标。
     *
     * @param arr 待查找的字符数组，不能为 {@code null}
     * @param j   待查找的字符
     * @return 第一个等于 j 的字符下标，未找到返回 -1
     */
    public static int indexOf(char[] arr, char j) {
        return indexOf(arr, j, 0, arr.length);
    }

    /**
     * 从指定位置开始向后查找 char 数组中值等于 j 的字符下标。
     *
     * @param arr       待查找的字符数组，不能为 {@code null}
     * @param j         待查找的字符
     * @param fromIndex 起始查找下标（包含）
     * @return 从 fromIndex 起第一个等于 j 的字符下标，未找到返回 -1
     */
    public static int indexOf(char[] arr, char j, int fromIndex) {
        return indexOf(arr, j, fromIndex, arr.length);
    }

    /**
     * 在指定区间内查找 char 数组中值等于 j 的字符下标。
     *
     * @param arr       待查找的字符数组，为 {@code null} 时返回 -1
     * @param j         待查找的字符
     * @param fromIndex 起始查找下标（包含）
     * @param toIndex   结束查找下标（不包含）
     * @return 区间内第一个等于 j 的字符下标，未找到返回 -1
     */
    public static int indexOf(char[] arr, char j, int fromIndex, int toIndex) {
        if (arr == null) {
            return -1;
        }
        for (int i = fromIndex; i < toIndex; i++) {
            if (arr[i] == j) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取数组指定下标的元素
     *
     * @param arr   数组对象，支持基本类型数组和对象数组
     * @param index 元素下标
     * @return 指定下标的元素，基本类型会被自动装箱
     */
    public static Object arrayValueAt(Object arr, int index) {
        if (index == -1) {
            throw new ArrayIndexOutOfBoundsException(-1);
        }
        Class<?> arrCls = arr.getClass();
        if (!arrCls.isArray()) {
            throw new UnsupportedOperationException("Non array object do not support get value by index");
        }
        Class<?> componentType = arrCls.getComponentType();
        ReflectConsts.PrimitiveType primitiveType = ReflectConsts.PrimitiveType.typeOf(componentType);
        if (primitiveType != null) {
            return primitiveType.elementAt(arr, index);
        } else {
            Object[] objects = (Object[]) arr;
            return objects[index];
        }
    }

    /**
     * 判断对象是否为集合类型
     *
     * @param target 待判断的对象
     * @return 是数组或 {@link Collection} 时返回 {@code true}，为 {@code null} 或其他类型返回 {@code false}
     */
    public static boolean isCollection(Object target) {
        if (target == null) {
            return false;
        }
        if (target.getClass().isArray()) {
            return true;
        }
        return target instanceof Collection;
    }

    /**
     * 判断集合中是否包含obj
     *
     * @param arr 待查找的数组
     * @param obj 待查找的元素
     * @return 数组中存在与 obj 相等的元素时返回 {@code true}，数组为 {@code null}、为空或未找到时返回 {@code false}
     */
    public static boolean contains(Object[] arr, Object obj) {
        if (arr == null || arr.length == 0) {
            return false;
        }
        return indexOf(arr, obj) > -1;
    }

    /**
     * 判断集合类是否为null或者空
     *
     * @param collection 待判断的集合
     * @return 集合为 {@code null} 或元素个数为 0 时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isEmpty(Collection collection) {
        return collection == null || collection.size() == 0;
    }

    /**
     * 判断集合类是否为null或者空
     *
     * @param arr 待判断的数组
     * @return 数组为 {@code null} 或长度为 0 时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isEmpty(Object[] arr) {
        return arr == null || arr.length == 0;
    }

    /**
     * 获取集合类的大小
     *
     * @param target 集合或数组对象
     * @return 集合的元素个数或数组长度，target 为 {@code null} 时返回 0
     */
    public static int getSize(Object target) {
        if (target == null) {
            return 0;
        }
        if (target instanceof Collection) {
            return ((Collection<?>) target).size();
        }
        if (target instanceof Object[]) {
            return ((Object[]) target).length;
        }
        return Array.getLength(target);
    }

    /**
     * 获取集合中指定位置的索引，如果不是集合抛出空指针
     *
     * @param target 集合或数组对象
     * @param index  元素下标
     * @return 指定位置的元素，target 为 {@code null} 时返回 {@code null}
     */
    public static Object getElement(Object target, int index) {
        if (target == null) {
            return null;
        }
        Object[] arr = null;
        if (target instanceof Collection) {
            if (target instanceof List) {
                return ((List<?>) target).get(index);
            }
            arr = ((Collection<?>) target).toArray();
        } else if (arr instanceof Object[]) {
            arr = (Object[]) target;
        } else {
            try {
                return arrayValueAt(target, index);
            } catch (RuntimeException throwable) {
                throw new UnsupportedOperationException(
                        "Non array object do not support get value by index, " + target.getClass());
            }
        }
        return arr[index];
    }

    /**
     * 为集合或数组的指定位置赋值，target 为 {@code null} 时直接返回。
     *
     * @param target 目标对象，支持 {@link List} 和对象数组，其他集合类型会抛出
     *               {@link UnsupportedOperationException}
     * @param index  待赋值的位置下标
     * @param value  待写入的值
     */
    public static void setElement(Object target, int index, Object value) {
        if (target == null) {
            return;
        }
        if (target instanceof Collection) {
            if (target instanceof List) {
                ((List) target).set(index, value);
            } else {
                throw new UnsupportedOperationException("不支持的集合赋值操作");
            }
        } else {
            Object[] arr = (Object[]) target;
            arr[index] = value;
        }
    }

    /**
     * 集合转化为指定组件的数组
     *
     * @param collection    待转换的集合，不能为 {@code null}
     * @param componentType 目标数组的组件类型，支持基本类型
     * @return 长度与集合相同、按集合迭代顺序填充的数组对象
     */
    public static Object toArray(Collection collection, Class<?> componentType) {
        componentType.getClass();
        Object array = Array.newInstance(componentType, collection.size());
        int k = 0;
        ReflectConsts.PrimitiveType primitiveType = ReflectConsts.PrimitiveType.typeOf(componentType);
        if (primitiveType != null) {
            for (Object obj : collection) {
                primitiveType.setElementAt(array, k++, obj);
            }
        } else {
            Object[] objects = (Object[]) array;
            for (Object obj : collection) {
                objects[k++] = obj;
            }
        }
        return array;
    }

    /**
     * 将数组中的所有元素置为 {@code null}，便于释放引用。
     *
     * @param values 待清空的数组，不能为 {@code null}
     */
    public static void clear(Object[] values) {
        for (int i = 0, len = values.length; i < len; ++i) {
            values[i] = null;
        }
    }
}
