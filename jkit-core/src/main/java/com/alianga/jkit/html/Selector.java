package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 CSS 选择器引擎，支持标签、{@code #id}、{@code .class}、属性选择器、
 * 后代 / 子代 / 相邻兄弟 / 通用兄弟组合符、分组，以及 {@code :not}、
 * {@code :first-child}、{@code :nth-child}、{@code :contains} 等常用伪类子集。
 *
 * <p>无法解析的选择器抛出 {@link SelectorException}，不做静默降级。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class Selector {
    /** 已解析选择器的缓存上限，超出后淘汰最久未用者。 */
    private static final int CACHE_MAX = 256;

    /** 解析结果不可变且线程安全，按访问序缓存，避免重复解析同一选择器。 */
    private static final Map<String, List<Complex>> CACHE =
            new LinkedHashMap<String, List<Complex>>(64, 0.75f, true);

    private Selector() {
    }

    /**
     * 解析选择器并缓存结果；解析失败时抛出 {@link SelectorException} 且不写入缓存。
     *
     * @param query CSS 选择器
     * @return 解析后的复合选择器列表
     */
    private static List<Complex> parseCached(String query) {
        synchronized (CACHE) {
            List<Complex> hit = CACHE.get(query);
            if (hit != null) {
                return hit;
            }
        }
        List<Complex> parsed = new Parser(query).parse();
        synchronized (CACHE) {
            if (CACHE.size() >= CACHE_MAX) {
                Iterator<String> it = CACHE.keySet().iterator();
                it.next();
                it.remove();
            }
            CACHE.put(query, parsed);
        }
        return parsed;
    }

    /**
     * 在 {@code root} 子树中按 CSS 选择器查询所有匹配元素（文档顺序、去重）。
     *
     * @param query CSS 选择器
     * @param root 查询根元素
     * @return 匹配元素集合
     */
    public static Elements select(String query, Element root) {
        List<Complex> complexes = parseCached(query);
        Elements out = new Elements();
        boolean dedup = complexes.size() > 1;
        for (int i = 0; i < complexes.size(); i++) {
            Complex c = complexes.get(i);
            int last = c.steps.size() - 1;
            collect(c, last, root, out, dedup);
        }
        return out;
    }

    /**
     * 深度优先收集匹配元素。直接走子节点表，不构建中间列表、不分配迭代器。
     *
     * @param c 复合选择器
     * @param last 最末一步下标
     * @param el 当前元素
     * @param out 结果容器
     * @param dedup 是否去重（仅逗号分组时需要）
     */
    private static void collect(Complex c, int last, Element el, Elements out, boolean dedup) {
        if (matchFrom(c, last, el) && (!dedup || !out.contains(el))) {
            out.add(el);
        }
        int size = el.childCount();
        for (int k = 0; k < size; k++) {
            Node n = el.childAt(k);
            if (n instanceof Element) {
                collect(c, last, (Element) n, out, dedup);
            }
        }
    }

    /**
     * 返回第一个匹配元素，无匹配时 {@code null}。
     *
     * @param query CSS 选择器
     * @param root 查询根元素
     * @return 首个匹配元素
     */
    public static Element selectFirst(String query, Element root) {
        List<Complex> complexes = parseCached(query);
        for (int i = 0; i < complexes.size(); i++) {
            Complex c = complexes.get(i);
            int last = c.steps.size() - 1;
            Element hit = findFirst(c, last, root);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Element findFirst(Complex c, int last, Element el) {
        if (matchFrom(c, last, el)) {
            return el;
        }
        int size = el.childCount();
        for (int k = 0; k < size; k++) {
            Node n = el.childAt(k);
            if (n instanceof Element) {
                Element hit = findFirst(c, last, (Element) n);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static boolean matchFrom(Complex c, int idx, Element el) {
        if (!c.steps.get(idx).matches(el)) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        Combinator comb = c.combs.get(idx - 1);
        if (comb == Combinator.CHILD) {
            Element parent = el.parentElement();
            return parent != null && matchFrom(c, idx - 1, parent);
        }
        if (comb == Combinator.NEXT_SIBLING) {
            Element prev = el.previousElementSibling();
            return prev != null && matchFrom(c, idx - 1, prev);
        }
        if (comb == Combinator.SUBSEQUENT_SIBLING) {
            Element prev = el.previousElementSibling();
            while (prev != null) {
                if (matchFrom(c, idx - 1, prev)) {
                    return true;
                }
                prev = prev.previousElementSibling();
            }
            return false;
        }
        Element parent = el.parentElement();
        while (parent != null) {
            if (matchFrom(c, idx - 1, parent)) {
                return true;
            }
            parent = parent.parentElement();
        }
        return false;
    }

    private enum Combinator {
        DESCENDANT, CHILD, NEXT_SIBLING, SUBSEQUENT_SIBLING
    }

    private interface Simple {
        boolean matches(Element e);
    }

    private static final class Compound {
        final List<Simple> simples = new ArrayList<Simple>();

        /** 按下标遍历，避免每个被测试元素都分配一次 Iterator。 */
        boolean matches(Element e) {
            for (int i = 0; i < simples.size(); i++) {
                if (!simples.get(i).matches(e)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Complex {
        final List<Compound> steps = new ArrayList<Compound>();
        final List<Combinator> combs = new ArrayList<Combinator>();
    }

    private static final class TagSimple implements Simple {
        private final String tag;

        TagSimple(String tag) {
            // DOM 标签名解析时已统一小写，选择器侧也归一，匹配退化为 equals
            this.tag = "*".equals(tag) ? tag : tag.toLowerCase();
        }

        public boolean matches(Element e) {
            return "*".equals(tag) || e.tagName().equals(tag);
        }
    }

    private static final class IdSimple implements Simple {
        private final String id;

        IdSimple(String id) {
            this.id = id;
        }

        public boolean matches(Element e) {
            return e.id().equals(id);
        }
    }

    private static final class ClassSimple implements Simple {
        private final String cls;

        ClassSimple(String cls) {
            this.cls = cls;
        }

        public boolean matches(Element e) {
            return e.hasClass(cls);
        }
    }

    private static final class AttrSimple implements Simple {
        private final String name;
        private final String op;
        private final String value;

        AttrSimple(String name, String op, String value) {
            this.name = name;
            this.op = op;
            this.value = value;
        }

        public boolean matches(Element e) {
            if (op.isEmpty()) {
                return e.hasAttr(name);
            }
            if ("!=".equals(op)) {
                return !e.attr(name).equals(value);
            }
            String av = e.attrOrNull(name);
            if (av == null) {
                return false;
            }
            if ("=".equals(op)) {
                return av.equals(value);
            }
            if ("~=".equals(op)) {
                return hasToken(av, value);
            }
            if ("|=".equals(op)) {
                return av.equals(value) || av.startsWith(value + "-");
            }
            if ("^=".equals(op)) {
                return av.startsWith(value);
            }
            if ("$=".equals(op)) {
                return av.endsWith(value);
            }
            if ("*=".equals(op)) {
                return av.contains(value);
            }
            return false;
        }

        /** {@code ~=} 的空白分词匹配，手写扫描替代正则 split。 */
        private static boolean hasToken(String av, String tok) {
            int i = 0;
            int n = av.length();
            int len = tok.length();
            while (i < n) {
                while (i < n && isSep(av.charAt(i))) {
                    i++;
                }
                int s = i;
                while (i < n && !isSep(av.charAt(i))) {
                    i++;
                }
                if (i - s == len && av.regionMatches(s, tok, 0, len)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSep(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
        }
    }

    private static final class PseudoSimple implements Simple {
        private final String name;
        private final Compound inner;
        private final int[] nth;
        private final String arg;

        PseudoSimple(String name, Compound inner, int[] nth, String arg) {
            this.name = name;
            this.inner = inner;
            this.nth = nth;
            this.arg = arg;
        }

        public boolean matches(Element e) {
            switch (name) {
                case "first-child":
                    return indexInParent(e) == 1;
                case "last-child":
                    return indexInParent(e) == elementChildCount(e.parentElement());
                case "only-child":
                    return elementChildCount(e.parentElement()) == 1 && indexInParent(e) == 1;
                case "root":
                    return e.parent() == null;
                case "empty":
                    return isEmptyOfContent(e);
                case "not":
                    return !inner.matches(e);
                case "nth-child":
                    return nthMatches(e, nth[0], nth[1]);
                case "nth-last-child":
                    return reverseNthMatches(e, nth[0], nth[1]);
                case "nth-of-type":
                    return typeMatches(e, nth[0], nth[1]);
                case "nth-last-of-type":
                    return nth(nth[0], nth[1], sameTypeCount(e) - typeIndexOf(e) + 1);
                case "first-of-type":
                    return typeIndexOf(e) == 1;
                case "last-of-type":
                    return typeIndexOf(e) == sameTypeCount(e);
                case "only-of-type":
                    return sameTypeCount(e) == 1;
                case "contains":
                    return e.text().contains(arg);
                case "containsown":
                    return e.ownText().contains(arg);
                case "matches":
                    return Pattern.compile(arg).matcher(e.text()).matches();
                case "matchesown":
                    return Pattern.compile(arg).matcher(e.ownText()).matches();
                default:
                    throw new SelectorException("unsupported pseudo: " + name);
            }
        }

        private boolean nthMatches(Element e, int a, int b) {
            return nth(a, b, indexInParent(e));
        }

        private boolean typeMatches(Element e, int a, int b) {
            return nth(a, b, typeIndexOf(e));
        }

        private boolean reverseNthMatches(Element e, int a, int b) {
            Element p = e.parentElement();
            if (p == null) {
                return false;
            }
            return nth(a, b, elementChildCount(p) - indexInParent(e) + 1);
        }

        /** 与 Jsoup 一致：空白文本节点与注释不算“有内容”。 */
        private boolean isEmptyOfContent(Element e) {
            int size = e.childCount();
            for (int k = 0; k < size; k++) {
                Node n = e.childAt(k);
                if (n instanceof Comment) {
                    continue;
                }
                if (n instanceof TextNode && ((TextNode) n).text().trim().isEmpty()) {
                    continue;
                }
                return false;
            }
            return true;
        }

        private boolean nth(int a, int b, int idx) {
            if (idx <= 0) {
                return false;
            }
            if (a == 0) {
                return idx == b;
            }
            int diff = idx - b;
            return diff % a == 0 && diff / a >= 0;
        }

        /** 在同类型兄弟中的序号，从 1 开始。 */
        private int typeIndexOf(Element e) {
            Element p = e.parentElement();
            if (p == null) {
                return 1;
            }
            int idx = 0;
            int size = p.childCount();
            for (int k = 0; k < size; k++) {
                Node n = p.childAt(k);
                if (n instanceof Element && ((Element) n).tagName().equals(e.tagName())) {
                    idx++;
                    if (n == e) {
                        return idx;
                    }
                }
            }
            return idx;
        }

        private int sameTypeCount(Element e) {
            Element p = e.parentElement();
            if (p == null) {
                return 1;
            }
            int count = 0;
            int size = p.childCount();
            for (int k = 0; k < size; k++) {
                Node n = p.childAt(k);
                if (n instanceof Element && ((Element) n).tagName().equals(e.tagName())) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 元素在同级元素中的序号，从 1 开始；无父元素或不在子节点中返回 0。 */
    private static int indexInParent(Element e) {
        Element p = e.parentElement();
        if (p == null) {
            return 0;
        }
        int idx = 0;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            Node n = p.childAt(k);
            if (!(n instanceof Element)) {
                continue;
            }
            idx++;
            if (n == e) {
                return idx;
            }
        }
        return 0;
    }

    /** 直接子元素个数，父元素为 null 时返回 0。 */
    private static int elementChildCount(Element p) {
        if (p == null) {
            return 0;
        }
        int count = 0;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            if (p.childAt(k) instanceof Element) {
                count++;
            }
        }
        return count;
    }

    private static final class Parser {
        private final String q;
        private final int n;
        private int i;

        Parser(String q) {
            this.q = q;
            this.n = q.length();
            this.i = 0;
        }

        List<Complex> parse() {
            List<Complex> complexes = new ArrayList<Complex>();
            Complex cur = new Complex();
            Compound comp = new Compound();
            while (i < n) {
                boolean ws = false;
                while (i < n && isWs(q.charAt(i))) {
                    i++;
                    ws = true;
                }
                if (i >= n) {
                    break;
                }
                char c = q.charAt(i);
                if (c == ',') {
                    cur.steps.add(comp);
                    comp = new Compound();
                    complexes.add(cur);
                    cur = new Complex();
                    i++;
                    continue;
                }
                if (c == '>') {
                    flush(cur, comp, Combinator.CHILD);
                    comp = new Compound();
                    i++;
                    continue;
                }
                if (c == '+') {
                    flush(cur, comp, Combinator.NEXT_SIBLING);
                    comp = new Compound();
                    i++;
                    continue;
                }
                if (c == '~') {
                    flush(cur, comp, Combinator.SUBSEQUENT_SIBLING);
                    comp = new Compound();
                    i++;
                    continue;
                }
                if (ws && !comp.simples.isEmpty()) {
                    flush(cur, comp, Combinator.DESCENDANT);
                    comp = new Compound();
                }
                comp.simples.add(readSimple());
            }
            if (!comp.simples.isEmpty()) {
                cur.steps.add(comp);
            }
            complexes.add(cur);
            return complexes;
        }

        private void flush(Complex cur, Compound comp, Combinator comb) {
            if (comp.simples.isEmpty()) {
                throw new SelectorException("组合符缺少操作数：" + q);
            }
            cur.steps.add(comp);
            cur.combs.add(comb);
        }

        private Simple readSimple() {
            char c = q.charAt(i);
            if (c == '#') {
                i++;
                return new IdSimple(readIdent());
            }
            if (c == '.') {
                i++;
                return new ClassSimple(readIdent());
            }
            if (c == '[') {
                return readAttr();
            }
            if (c == ':') {
                return readPseudo();
            }
            if (c == '*') {
                i++;
                return new TagSimple("*");
            }
            int ks = i;
            while (i < n && isIdentPart(q.charAt(i))) {
                i++;
            }
            if (i == ks) {
                throw new SelectorException("无法解析的选择器，位置 " + ks + " 附近：" + q);
            }
            return new TagSimple(q.substring(ks, i));
        }

        private String readIdent() {
            int ks = i;
            while (i < n && isIdentPart(q.charAt(i))) {
                i++;
            }
            return q.substring(ks, i);
        }

        private Simple readAttr() {
            i++;
            skipWs();
            int ks = i;
            while (i < n && !isWs(q.charAt(i)) && q.charAt(i) != '='
                    && q.charAt(i) != ']' && q.charAt(i) != '>'
                    && q.charAt(i) != '^' && q.charAt(i) != '$'
                    && q.charAt(i) != '*' && q.charAt(i) != '~'
                    && q.charAt(i) != '|' && q.charAt(i) != '!') {
                i++;
            }
            String name = q.substring(ks, i).toLowerCase();
            skipWs();
            String op = "";
            String value = "";
            if (i < n && q.charAt(i) == ']') {
                i++;
                return new AttrSimple(name, "", "");
            }
            char o1 = q.charAt(i);
            if (o1 == '=') {
                op = "=";
                i++;
            } else if (o1 == '!' && i + 1 < n && q.charAt(i + 1) == '=') {
                op = "!=";
                i += 2;
            } else if (i + 1 < n && q.charAt(i + 1) == '=') {
                op = "" + o1 + '=';
                i += 2;
            }
            skipWs();
            if (i < n && (q.charAt(i) == '"' || q.charAt(i) == '\'')) {
                char qc = q.charAt(i);
                i++;
                int vs = i;
                while (i < n && q.charAt(i) != qc) {
                    i++;
                }
                value = q.substring(vs, i);
                if (i < n) {
                    i++;
                }
            } else {
                int vs = i;
                while (i < n && !isWs(q.charAt(i)) && q.charAt(i) != ']' && q.charAt(i) != '>') {
                    i++;
                }
                value = q.substring(vs, i);
            }
            skipWs();
            if (i < n && q.charAt(i) == ']') {
                i++;
            }
            return new AttrSimple(name, op, value);
        }

        private Simple readPseudo() {
            i++;
            if (i < n && q.charAt(i) == ':') {
                i++;
            }
            String name = readIdent().toLowerCase();
            String arg = null;
            if (i < n && q.charAt(i) == '(') {
                int depth = 1;
                int start = i + 1;
                i++;
                while (i < n && depth > 0) {
                    char ch = q.charAt(i);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                        if (depth == 0) {
                            i++;
                            break;
                        }
                    }
                    i++;
                }
                arg = q.substring(start, i - 1).trim();
            }
            return makePseudo(name, arg);
        }

        private Simple makePseudo(String name, String arg) {
            if (name.isEmpty()) {
                throw new SelectorException("伪类名缺失：" + q);
            }
            if ("not".equals(name)) {
                if (arg == null || arg.isEmpty()) {
                    throw new SelectorException(":not() 缺少参数：" + q);
                }
                List<Complex> cs = new Parser(arg).parse();
                if (cs.get(0).steps.isEmpty()) {
                    throw new SelectorException(":not() 参数无法解析：" + q);
                }
                Compound inner = cs.get(0).steps.get(0);
                return new PseudoSimple(name, inner, null, arg);
            }
            if ("nth-child".equals(name) || "nth-of-type".equals(name)
                    || "nth-last-child".equals(name) || "nth-last-of-type".equals(name)) {
                requireArg(name, arg);
                return new PseudoSimple(name, null, parseNth(arg), arg);
            }
            if ("contains".equals(name) || "containsown".equals(name)
                    || "matches".equals(name) || "matchesown".equals(name)) {
                requireArg(name, arg);
                return new PseudoSimple(name, null, null, arg);
            }
            return new PseudoSimple(name, null, null, arg);
        }

        private void requireArg(String name, String arg) {
            if (arg == null || arg.isEmpty()) {
                throw new SelectorException(":" + name + "() 缺少参数：" + q);
            }
        }

        private int[] parseNth(String arg) {
            String s = arg.replace(" ", "");
            if ("odd".equals(s)) {
                return new int[]{2, 1};
            }
            if ("even".equals(s)) {
                return new int[]{2, 0};
            }
            Matcher m = Pattern.compile("^([+-]?\\d*)n([+-]\\d+)?$").matcher(s);
            if (m.matches()) {
                String aStr = m.group(1);
                int a;
                if (aStr.isEmpty() || "+".equals(aStr)) {
                    a = 1;
                } else if ("-".equals(aStr)) {
                    a = -1;
                } else {
                    a = Integer.parseInt(aStr);
                }
                String bStr = m.group(2);
                int b = bStr == null ? 0 : Integer.parseInt(bStr);
                return new int[]{a, b};
            }
            return new int[]{0, Integer.parseInt(s)};
        }

        private void skipWs() {
            while (i < n && isWs(q.charAt(i))) {
                i++;
            }
        }

        private boolean isWs(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
        }

        private boolean isIdentPart(char c) {
            return Character.isLetterOrDigit(c) || c == '-' || c == '_';
        }
    }
}
