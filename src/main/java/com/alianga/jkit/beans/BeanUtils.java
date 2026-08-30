package com.alianga.jkit.beans;

import com.alianga.jkit.json.internal.utils.CollectionUtils;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.GetterInfo;
import com.alianga.jkit.reflect.SetterInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体bean相关工具类.
 *
 * <p>提供对象属性拷贝与合并功能，支持 JavaBean 与 Map 之间的互相拷贝。
 * 内部通过 {@link UtilsSecureTrustedAccess} 获得 getter/setter 的安全调用权限，
 * 无需继承任何基类，纯静态方法调用。
 *
 * @time 2019/12/7 11:48
 */
public final class BeanUtils {
    private static final UtilsSecureTrustedAccess TRUSTED_ACCESS = new UtilsSecureTrustedAccess();

    /**
     * 拷贝对象属性（浅拷贝）.
     *
     * @param srcBean 源对象
     * @param targetBean 目标对象
     */
    public static void copy(Object srcBean, Object targetBean) {
        copyProperties(srcBean, targetBean, null);
    }

    /**
     * 拷贝对象属性，可指定忽略字段.
     *
     * @param src 源对象
     * @param target 目标对象
     * @param excludeFields 需要忽略的属性名数组
     */
    public static void copyProperties(Object src, Object target, String[] excludeFields) {
        if (src == null || target == null) {
            return;
        }
        Map targetMap = null;
        ClassStrucWrap targetClassStrucWrap = null;
        Class<?> targetClass = target.getClass();
        boolean isSameBeanType = src.getClass() == targetClass;
        if (target instanceof Map) {
            targetMap = (Map) target;
        } else {
            targetClassStrucWrap = ClassStrucWrap.get(targetClass);
        }
        if (src instanceof Map) {
            Map sourceMap = (Map) src;
            if (targetMap != null) {
                if (excludeFields == null || excludeFields.length == 0) {
                    targetMap.putAll(sourceMap);
                } else {
                    Set<Map.Entry> entrySet = sourceMap.entrySet();
                    for (Map.Entry entry : entrySet) {
                        Object key = entry.getKey();
                        if (CollectionUtils.indexOf(excludeFields, key) > -1) {
                            continue;
                        }
                        sourceMap.put(key, entry.getValue());
                    }
                }
            } else {
                Set<Map.Entry> entrySet = sourceMap.entrySet();
                for (Map.Entry entry : entrySet) {
                    Object key = entry.getKey();
                    if (key == null) {
                        continue;
                    }
                    if (CollectionUtils.indexOf(excludeFields, key) > -1) {
                        continue;
                    }
                    Object value = entry.getValue();
                    SetterInfo setterInfo = targetClassStrucWrap.getSetterInfo(key.toString());
                    if (setterInfo != null) {
                        value = ObjectUtils.toType(value, setterInfo.getParameterType());
                        TRUSTED_ACCESS.set(setterInfo, target, value);
                    }
                }
            }
        } else {
            ClassStrucWrap sourceClassStrucWrap =
                    isSameBeanType ? targetClassStrucWrap : ClassStrucWrap.get(src.getClass());
            List<GetterInfo> sourceGetterInfos = sourceClassStrucWrap.getGetterInfos();
            for (GetterInfo getterInfo : sourceGetterInfos) {
                String fieldName = getterInfo.getName();
                if (CollectionUtils.indexOf(excludeFields, fieldName) > -1) {
                    continue;
                }
                Object value = TRUSTED_ACCESS.get(getterInfo, src);
                if (targetMap != null) {
                    targetMap.put(fieldName, value);
                } else {
                    SetterInfo setterInfo = targetClassStrucWrap.getSetterInfo(fieldName);
                    if (setterInfo != null) {
                        TRUSTED_ACCESS.set(setterInfo, target,
                                ObjectUtils.toType(value, setterInfo.getParameterType()));
                    }
                }
            }
        }
    }

    /**
     * 合并属性（将 src 中非空的属性拷贝到 target 中）.
     *
     * @param src 源对象
     * @param target 目标对象
     */
    public static void mergeProperties(Object src, Object target) {
        if (src == null || target == null) {
            return;
        }
        Class<?> targetClass = target.getClass();
        Map targetMap = null;
        ClassStrucWrap targetClassStrucWrap = null;
        boolean isSameBeanType = src.getClass() == targetClass;
        if (target instanceof Map) {
            targetMap = (Map) target;
        } else {
            targetClassStrucWrap = ClassStrucWrap.get(targetClass);
        }
        if (src instanceof Map) {
            Map sourceMap = (Map) src;
            if (targetMap != null) {
                targetMap.putAll(sourceMap);
            } else {
                Set<Map.Entry> entrySet = sourceMap.entrySet();
                for (Map.Entry entry : entrySet) {
                    Object key = entry.getKey();
                    SetterInfo setterInfo = targetClassStrucWrap.getSetterInfo(String.valueOf(key));
                    if (setterInfo != null) {
                        TRUSTED_ACCESS.set(setterInfo, target,
                                ObjectUtils.toType(entry.getValue(), setterInfo.getParameterType()));
                    }
                }
            }
        } else {
            ClassStrucWrap sourceClassStrucWrap =
                    isSameBeanType ? targetClassStrucWrap : ClassStrucWrap.get(src.getClass());
            List<GetterInfo> getterInfos = sourceClassStrucWrap.getGetterInfos();
            for (GetterInfo getterInfo : getterInfos) {
                String fieldName = getterInfo.getName();
                Object value = TRUSTED_ACCESS.get(getterInfo, src);
                if (targetMap != null) {
                    targetMap.put(fieldName, value);
                } else {
                    SetterInfo setterInfo;
                    if (value != null && !"".equals(value) &&
                            (setterInfo = targetClassStrucWrap.getSetterInfo(fieldName)) != null) {
                        TRUSTED_ACCESS.set(setterInfo, target,
                                ObjectUtils.toType(value, setterInfo.getParameterType()));
                    }
                }
            }
        }
    }
}
