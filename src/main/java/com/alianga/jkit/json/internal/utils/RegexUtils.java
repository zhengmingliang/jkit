package com.alianga.jkit.json.internal.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式工具类
 *
 * @time 2020/3/11 21:33
 */
public class RegexUtils {
    private static Map<String, Pattern> cachePatterns = new ConcurrentHashMap<String, Pattern>();

    /**
     * 获取正则表达式对应的 {@link Pattern}，编译结果会被缓存复用
     *
     * @param regSource 正则表达式源串
     * @return 该正则表达式编译后的 {@link Pattern} 实例
     */
    public static Pattern getPattern(String regSource) {
        Pattern pattern = cachePatterns.get(regSource);
        if (pattern != null) {
            return pattern;
        }
        synchronized (regSource) {
            pattern = cachePatterns.get(regSource);
            if (pattern == null) {
                pattern = Pattern.compile(regSource);
                cachePatterns.put(regSource, pattern);
            }
            return pattern;
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
