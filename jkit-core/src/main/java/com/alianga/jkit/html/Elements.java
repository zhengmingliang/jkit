package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 元素集合，对 {@link Element} 列表的常见批量操作做便捷封装。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class Elements extends ArrayList<Element> {
    /**
     * 构造空集合。
     */
    public Elements() {
        super();
    }

    /**
     * 基于已有元素列表构造集合。
     *
     * @param elements 元素列表
     */
    public Elements(List<Element> elements) {
        super(elements);
    }

    /**
     * 拼接所有元素的纯文本。
     *
     * @return 合并后的文本
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Element e : this) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.text());
        }
        return sb.toString();
    }

    /**
     * 返回首个元素的指定属性值，集合为空时为空串。
     *
     * @param key 属性名
     * @return 属性值
     */
    public String attr(String key) {
        return isEmpty() ? "" : get(0).attr(key);
    }

    /**
     * 拼接所有元素的外联 HTML。
     *
     * @return 合并后的 HTML
     */
    public String html() {
        StringBuilder sb = new StringBuilder();
        for (Element e : this) {
            sb.append(e.outerHtml());
        }
        return sb.toString();
    }

    /**
     * 返回每个元素的纯文本列表。
     *
     * @return 文本列表
     */
    public List<String> eachText() {
        List<String> list = new ArrayList<String>();
        for (Element e : this) {
            list.add(e.text());
        }
        return list;
    }

    /**
     * 返回首个元素，集合为空时 {@code null}。
     *
     * @return 首个元素
     */
    public Element first() {
        return isEmpty() ? null : get(0);
    }

    /**
     * 在集合每个元素子树中查询，合并去重后的结果（与 jsoup 行为一致：同一元素只出现一次）。
     *
     * @param css CSS 选择器
     * @return 匹配元素集合
     */
    public Elements select(String css) {
        Elements result = new Elements();
        Set<Element> seen = new LinkedHashSet<Element>();
        for (Element e : this) {
            for (Element hit : e.select(css)) {
                if (seen.add(hit)) {
                    result.add(hit);
                }
            }
        }
        return result;
    }
}
