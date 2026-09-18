package com.alianga.jkit.html;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签名与属性名的只读驻留表。
 *
 * <p>解析时每个标签、每个属性都会产生一个新的 {@code String}；真实页面里这些名字高度重复，
 * 驻留后同一名字复用同一实例，可显著降低大文档的常驻内存并降低字符串比较成本。
 * 表在类初始化时一次性填充，之后只读，因此多线程安全。未收录的名字按原样返回。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class Names {
    private static final List<String> TAGS = Arrays.asList(
            "a", "abbr", "address", "area", "article", "aside", "audio", "b", "base", "bdi",
            "bdo", "blockquote", "body", "br", "button", "canvas", "caption", "cite", "code",
            "col", "colgroup", "data", "datalist", "dd", "del", "details", "dfn", "dialog",
            "div", "dl", "dt", "em", "embed", "fieldset", "figcaption", "figure", "footer",
            "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr",
            "html", "i", "iframe", "img", "input", "ins", "kbd", "label", "legend", "li",
            "link", "main", "map", "mark", "menu", "meta", "meter", "nav", "noscript",
            "object", "ol", "optgroup", "option", "output", "p", "param", "picture", "pre",
            "progress", "q", "rp", "rt", "ruby", "s", "samp", "script", "search", "section",
            "select", "slot", "small", "source", "span", "strong", "style", "sub", "summary",
            "sup", "svg", "table", "tbody", "td", "template", "textarea", "tfoot", "th",
            "thead", "time", "title", "tr", "track", "u", "ul", "var", "video", "wbr");

    private static final List<String> ATTRS = Arrays.asList(
            "accept", "accesskey", "action", "align", "alt", "async", "autocomplete",
            "autofocus", "autoplay", "bgcolor", "border", "cellpadding", "cellspacing",
            "charset", "checked", "cite", "class", "clear", "cols", "colspan", "content",
            "controls", "coords", "crossorigin", "data", "datetime", "default", "defer",
            "dir", "disabled", "download", "draggable", "enctype", "for", "form", "frameborder",
            "headers", "height", "hidden", "href", "hreflang", "http-equiv", "id", "ismap",
            "itemprop", "itemscope", "itemtype", "lang", "list", "loading", "loop", "max",
            "maxlength", "media", "method", "min", "multiple", "muted", "name", "novalidate",
            "onclick", "onerror", "onload", "open", "pattern", "placeholder", "poster",
            "preload", "property", "readonly", "rel", "required", "reversed", "role", "rows",
            "rowspan", "sandbox", "scope", "selected", "shape", "size", "sizes", "span",
            "src", "srcset", "start", "step", "style", "tabindex", "target", "title", "type",
            "usemap", "valign", "value", "width", "wrap");

    private static final Map<String, String> TAG_MAP = new HashMap<String, String>(256);
    private static final Map<String, String> ATTR_MAP = new HashMap<String, String>(256);

    static {
        for (String t : TAGS) {
            TAG_MAP.put(t, t);
        }
        for (String a : ATTRS) {
            ATTR_MAP.put(a, a);
        }
    }

    private Names() {
    }

    /**
     * 返回驻留后的标签名。
     *
     * @param lower 已转为小写的标签名
     * @return 共享实例
     */
    static String tag(String lower) {
        String v = TAG_MAP.get(lower);
        return v != null ? v : lower;
    }

    /**
     * 返回驻留后的属性名。
     *
     * @param lower 已转为小写的属性名
     * @return 共享实例
     */
    static String attr(String lower) {
        String v = ATTR_MAP.get(lower);
        return v != null ? v : lower;
    }
}
