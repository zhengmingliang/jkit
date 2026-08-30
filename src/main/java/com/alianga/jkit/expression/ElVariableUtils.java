package com.alianga.jkit.expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 变量模型处理工具
 *
 * @time 2022/10/30 0:37
 */
public final class ElVariableUtils {
    /**
     * 根据链式表达式构建变量执行模型
     *
     * @param elKey 以英文点号分隔的链式表达式，如 {@code user.name}
     * @return 该表达式最末一级变量对应的执行器，其 prev 链指向各级父变量
     */
    public static ElVariableInvoker build(String elKey) {
        return build(elKey, new HashMap<String, ElVariableInvoker>(), new HashMap<String, ElVariableInvoker>());
    }

    /**
     * 根据链式表达式构建变量执行模型
     *
     * @param elKey 以英文点号分隔的链式表达式，如 {@code user.name}
     * @param variableNodeInvokes 变量节点缓存，key 为从根节点起的完整路径，构建过程中会被写入
     * @param tailNodeInvokes 末级节点缓存，key 为完整路径，构建过程中会被写入
     * @return 该表达式最末一级变量对应的执行器
     */
    public static ElVariableInvoker build(String elKey, Map<String, ElVariableInvoker> variableNodeInvokes,
                                          Map<String, ElVariableInvoker> tailNodeInvokes) {
        List<String> variableKeys = new ArrayList<String>();
        int beginIndex = 0;
        int splitIndex = elKey.indexOf('.');
        while (splitIndex > -1) {
            variableKeys.add(new String(elKey.substring(beginIndex, splitIndex)));
            beginIndex = ++splitIndex;
            splitIndex = elKey.indexOf('.', splitIndex);
        }
        variableKeys.add(new String(elKey.substring(beginIndex)));
        return build(variableKeys, variableNodeInvokes, tailNodeInvokes);
    }

    /**
     * 根据集合构建变量执行模型
     *
     * @param keys 按层级顺序排列的变量名集合，第一个元素为根变量；以 {@code (} 开头的元素视为子表达式
     * @param variableInvokes 变量节点缓存，key 为从根节点起的完整路径，构建过程中会被写入
     * @param tailNodeInvokes 末级节点缓存，key 为完整路径，构建过程中会被写入
     * @return last value invoke
     */
    public static ElVariableInvoker build(List<String> keys, Map<String, ElVariableInvoker> variableInvokes,
                                          Map<String, ElVariableInvoker> tailNodeInvokes) {
        ElVariableInvoker prev = null;
        String path = null;
        int index = 0;
        for (String key : keys) {
            boolean isRoot = index++ == 0;
            path = isRoot ? key : path + '.' + key;
            boolean isChildEL = key.charAt(0) == '(';
            ElVariableInvoker variableInvoke = variableInvokes.get(path);
            if (variableInvoke == null) {
                if (isRoot) {
                    variableInvoke = isChildEL ?
                            new ElVariableInvoker.ChildElImpl(new String(key.substring(1, key.length() - 1))) :
                            new ElVariableInvoker.RootImpl(key.intern());
                } else {
                    variableInvoke = isChildEL ?
                            new ElVariableInvoker.ChildElImpl(new String(key.substring(1, key.length() - 1)), prev) :
                            new ElVariableInvoker(key, prev);
                }
                variableInvokes.put(path, variableInvoke.index(variableInvokes.size()));
            }
            prev = variableInvoke;
        }

        ElVariableInvoker result = prev;
        if (!tailNodeInvokes.containsKey(path)) {
            tailNodeInvokes.put(path, result.tailIndex(tailNodeInvokes.size()));
        }
        result.setTail(true);
        return result;
    }

    /**
     * 构建一级变量执行模型
     *
     * @param key 一级变量名；以 {@code (} 开头时视为子表达式
     * @param variableInvokes 变量节点缓存，命中则复用，未命中时写入新建的执行器
     * @param tailNodeInvokes 末级节点缓存，新建执行器时会写入
     * @return 该一级变量对应的根节点执行器，已被标记为末级节点
     */
    public static ElVariableInvoker buildRoot(String key, Map<String, ElVariableInvoker> variableInvokes,
                                              Map<String, ElVariableInvoker> tailNodeInvokes) {
        boolean isChildEL = key.charAt(0) == '(';
        ElVariableInvoker variableInvoke = variableInvokes.get(key);
        if (variableInvoke == null) {
            variableInvoke =
                    isChildEL ? new ElVariableInvoker.ChildElImpl(new String(key.substring(1, key.length() - 1))) :
                            new ElVariableInvoker.RootImpl(key);
            variableInvokes.put(key, variableInvoke.index(variableInvokes.size()));
            tailNodeInvokes.put(key, variableInvoke.tailIndex(tailNodeInvokes.size()));
        }
        variableInvoke.setTail(true);
        return variableInvoke;
    }
}
