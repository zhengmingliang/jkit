package com.alianga.jkit.json.internal.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式工具类
 *
 * @time 2020/3/11 21:33
 */
public class RegexUtils {
    /**
     * 编译缓存上限。Pattern 对象较大，而本方法又是 public API、key 是任意调用方
     * 字符串：无界缓存在 regSource 可由外部输入构造时（如模板渲染路径）构成
     * 内存 DoS 面。LRU 淘汰只影响缓存命中率，不影响任何已返回的 Pattern 实例。
     */
    private static final int PATTERN_CACHE_MAX = 512;

    private static final Map<String, Pattern> cachePatterns =
            Collections.synchronizedMap(new LinkedHashMap<String, Pattern>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > PATTERN_CACHE_MAX;
                }
            });

    /**
     * 获取正则表达式对应的 {@link Pattern}，编译结果会被缓存复用（有界 LRU，见
     * {@link #PATTERN_CACHE_MAX}）。
     *
     * <p>用 {@link Map#computeIfAbsent} 实现原子"查-编-存"，不对入参字符串加锁。
     * 原实现对 {@code regSource}（String）加锁有两个隐患：
     * <ul>
     *   <li>字符串常量池让锁对象被 JVM 全局共享，业务代码或其它库若也
     *       {@code synchronized} 同一字面量会发生跨模块死锁；</li>
     *   <li>{@code putIfAbsent} 语义不对（"后写覆盖"），高并发下两个线程可能都编译一次。</li>
     * </ul>
     *
     * <p>锁对象是缓存自身的包装器（私有的，不会暴露给调用方），外层同步让
     * {@code computeIfAbsent} 在 JDK 8 的 {@code Collections.synchronizedMap} 上
     * 也保持原子；锁内嵌的 get/put 对同一监视器可重入。</p>
     *
     * @param regSource 正则表达式源串
     * @return 该正则表达式编译后的 {@link Pattern} 实例
     */
    public static Pattern getPattern(String regSource) {
        synchronized (cachePatterns) {
            return cachePatterns.computeIfAbsent(regSource, Pattern::compile);
        }
    }

    /**
     * 提取源串中所有匹配到的分组内容，默认提取每次匹配的全部分组
     *
     * @param source 待匹配的源字符串
     * @param groupRegex 带分组的正则表达式
     * @return 所有匹配到的分组内容列表，正则中不含分组时返回空列表
     */
    public static List<String> getMatcherGroups(String source, String groupRegex) {
        return getMatcherGroups(source, groupRegex, true);
    }

    /**
     * 提取源串中所有匹配到的分组内容
     *
     * @param source 待匹配的源字符串
     * @param groupRegex 带分组的正则表达式
     * @param iterator 为 {@code true} 时收集每次匹配的所有分组，为 {@code false} 时只收集第一个分组
     * @return 所有匹配到的分组内容列表，正则中不含分组时返回空列表
     */
    public static List<String> getMatcherGroups(String source, String groupRegex, boolean iterator) {
        List<String> matcherGroups = new ArrayList<String>();
        int begin = groupRegex.indexOf('(');
        if (begin == -1 || groupRegex.indexOf(')') <= begin) {
            return matcherGroups;
        }
        Pattern pattern = getPattern(groupRegex);
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int groupCount = matcher.groupCount();
            if (iterator) {
                int i = 0;
                while (i++ < groupCount) {
                    matcherGroups.add(matcher.group(i));
                }
            } else {
                if (groupCount > 0) {
                    matcherGroups.add(matcher.group(1));
                }
            }
        }
        return matcherGroups;
    }

}
